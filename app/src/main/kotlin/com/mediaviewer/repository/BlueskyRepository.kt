package com.mediaviewer.repository

import android.util.Log
import com.mediaviewer.model.*
import com.mediaviewer.network.BlueskyApi
import com.mediaviewer.network.NetworkClient
import com.mediaviewer.worker.BlueskyBlobResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

class BlueskyRepository {

    private var api: BlueskyApi = NetworkClient.buildBlueskyApi()
    private var baseUrl: String = "https://bsky.social/"

    fun updateServiceUrl(url: String) { baseUrl = url; api = NetworkClient.buildBlueskyApi(url) }

    /** The current account's PDS host (e.g. "bsky.social") — used to mint
     *  the service-auth token video.bsky.app upload requires. */
    private fun currentPdsHost(): String = runCatching { java.net.URI(baseUrl).host }.getOrDefault("bsky.social")

    // ── Chat PDS resolution ──────────────────────────────────────────────────
    // chat.bsky.* calls must be routed through the account's ACTUAL PDS host,
    // not necessarily bsky.social (many accounts are sharded onto other PDS
    // instances even when they log in via bsky.social). Regular repo writes
    // work fine through bsky.social directly, so this is scoped to chat only.
    private var chatApi: BlueskyApi = api
    private var chatPdsResolvedFor: String? = null
    // Bug fix ("From Friends works immediately, but says Feed Empty once the
    // background preload finishes"): ensureChatApi used to set
    // `chatPdsResolvedFor` *before* the network resolution below had actually
    // finished. At app launch, `loadDmConversations` and `preloadFriendsFeed`
    // both call into chat.bsky.* endpoints nearly simultaneously — whichever
    // got there first would mark the DID "resolved" immediately, so the other
    // one's call to ensureChatApi returned right away too, using `chatApi`
    // while it was still the default bsky.social-backed client (not yet
    // pointed at the account's real PDS). That silently returned zero
    // messages instead of erroring, and the empty result got cached as if it
    // were the real (empty) answer. A live fetch triggered later — well after
    // that first resolution had time to finish — always worked, which is
    // exactly the "works immediately, breaks once preloaded" symptom. This
    // mutex makes a second concurrent caller for the same DID actually wait
    // for the first one's resolution instead of racing past it.
    private val chatApiMutex = Mutex()

    private suspend fun ensureChatApi(myDid: String) {
        if (chatPdsResolvedFor == myDid) return
        chatApiMutex.withLock {
            // Re-check inside the lock: another caller may have already
            // finished resolving this exact DID while we were waiting.
            if (chatPdsResolvedFor == myDid) return@withLock
            runCatching {
                val req = okhttp3.Request.Builder().url("https://plc.directory/$myDid").build()
                val resp = NetworkClient.downloadClient.newCall(req).execute()
                resp.use { r ->
                    if (!r.isSuccessful) return@runCatching
                    val body = r.body?.string() ?: return@runCatching
                    val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val services = json.getAsJsonArray("service") ?: return@runCatching
                    for (s in services) {
                        val obj = s.asJsonObject
                        if (obj.get("id")?.asString == "#atproto_pds") {
                            val endpoint = obj.get("serviceEndpoint")?.asString ?: continue
                            chatApi = NetworkClient.buildBlueskyApi(endpoint.trimEnd('/') + "/")
                            return@runCatching
                        }
                    }
                }
            }
            // Only mark this DID "resolved" once the attempt has actually
            // finished (success or failure) — on failure chatApi silently
            // stays whatever it already was, same as before, it just no
            // longer lets a concurrent second caller skip ahead of it.
            chatPdsResolvedFor = myDid
        }
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    suspend fun login(identifier: String, password: String): Result<BskySession> = runCatching {
        val resp = api.createSession(BskyCreateSessionRequest(identifier, password))
        resp.body() ?: error("Login failed: ${resp.code()} ${resp.message()}")
    }

    suspend fun refreshToken(refreshJwt: String): Result<BskyRefreshResponse> = runCatching {
        val resp = api.refreshSession("Bearer $refreshJwt")
        resp.body() ?: error("Refresh failed: ${resp.code()}")
    }

    // ── Feed ──────────────────────────────────────────────────────────────────

    suspend fun getTimeline(token: String, cursor: String? = null, limit: Int = 50)
        : Result<Pair<List<MediaItem>, String?>> = runCatching {
        val resp = api.getTimeline("Bearer $token", limit, cursor)
        val body = resp.body() ?: error("Timeline ${resp.code()}: ${resp.message()}")
        Pair(body.feed.flatMap { parseFeedItemSafe(it) }, body.cursor)
    }

    suspend fun getFeed(token: String, feedUri: String, cursor: String? = null, limit: Int = 50)
        : Result<Pair<List<MediaItem>, String?>> = runCatching {
        val resp = api.getFeed("Bearer $token", feedUri, limit, cursor)
        val body = resp.body() ?: error("Feed ${resp.code()}: ${resp.message()}")
        Pair(body.feed.flatMap { parseFeedItemSafe(it) }, body.cursor)
    }

    suspend fun getActorLikes(token: String, did: String, cursor: String? = null)
        : Result<Pair<List<MediaItem>, String?>> = runCatching {
        val resp = api.getActorLikes("Bearer $token", did, 100, cursor)
        val body = resp.body() ?: error("Likes ${resp.code()}")
        Pair(body.feed.flatMap { parseFeedItemSafe(it) }, body.cursor)
    }

    /** Batch-hydrates arbitrary post URIs into MediaItems — used by the AI
     *  Tagging feature's search (TaggingRepository only stores URIs/tags
     *  locally, not full post content, so a search hit's URI has to be
     *  turned back into something the feed UI can render). Reuses the same
     *  getPosts + parseFeedItemSafe pattern as the DM-shared-posts hydration
     *  above. */
    suspend fun getPostsByUris(token: String, uris: List<String>): Result<List<MediaItem>> = runCatching {
        if (uris.isEmpty()) return@runCatching emptyList()
        val items = mutableListOf<MediaItem>()
        uris.chunked(25).forEach { batch ->
            val body = runCatching { api.getPosts("Bearer $token", batch) }.getOrNull()?.takeIf { it.isSuccessful }?.body()
            body?.posts?.forEach { post -> items.addAll(parseFeedItemSafe(BskyFeedItem(post = post))) }
        }
        items
    }

    // ── Saved Feeds — robust JSON parsing ────────────────────────────────────

    // Slot kinds preserved from the raw preferences, in pin order, so the final
    // list can be reassembled in the order the user actually arranged them —
    // including the "Following" timeline, which isn't a feed generator at all
    // and so can't be resolved through getFeedGenerators.
    private data class PrefSlot(val isTimeline: Boolean, val uri: String)

    suspend fun getSavedFeeds(token: String, did: String): Result<List<BskyFeedInfo>> = runCatching {
        val slots = mutableListOf<PrefSlot>()

        // Fetch the user's actual saved/pinned feed preferences.
        // Bluesky accounts use EITHER the V2 format OR the legacy V1 format — never both
        // meaningfully — so we use V2 if present, otherwise fall back to V1.
        var prefsError: String? = null
        runCatching {
            val resp = api.getPreferences("Bearer $token")
            if (!resp.isSuccessful) { prefsError = "Prefs HTTP ${resp.code()}"; return@runCatching }
            val body = resp.body() ?: run { prefsError = "Prefs: empty body"; return@runCatching }

            val v2 = body.preferences.firstOrNull {
                it.isJsonObject && it.asJsonObject.get("\$type")?.asString?.endsWith("savedFeedsPrefV2") == true
            }
            if (v2 != null) {
                val items = v2.asJsonObject.getAsJsonArray("items")
                items?.forEach { item ->
                    if (!item.isJsonObject) return@forEach
                    val itemObj = item.asJsonObject
                    // Known types per the app.bsky.actor.defs#savedFeed lexicon are
                    // "feed", "list", and "timeline" — the pinned "Following" home
                    // feed is a "timeline" slot with value "following", not an
                    // at:// feed generator URI, so it needs separate handling or it
                    // silently disappears from the saved-feeds list.
                    when (itemObj.get("type")?.asString) {
                        "feed" -> itemObj.get("value")?.asString?.let { v ->
                            if (v.startsWith("at://")) slots.add(PrefSlot(isTimeline = false, uri = v))
                        }
                        "timeline" -> slots.add(PrefSlot(isTimeline = true, uri = FOLLOWING_FEED_URI))
                        // "list" (a pinned List shown as a feed) isn't a feed generator
                        // either; left unhandled for now rather than mis-resolved.
                    }
                }
            } else {
                val v1 = body.preferences.firstOrNull {
                    it.isJsonObject && it.asJsonObject.get("\$type")?.asString?.endsWith("savedFeedsPref") == true
                }
                if (v1 != null) {
                    val obj = v1.asJsonObject
                    val pinned = obj.getAsJsonArray("pinned")?.mapNotNull { it.asString } ?: emptyList()
                    val saved  = obj.getAsJsonArray("saved")?.mapNotNull { it.asString }  ?: emptyList()
                    (pinned + saved).filter { it.startsWith("at://") }.distinct().forEach {
                        slots.add(PrefSlot(isTimeline = false, uri = it))
                    }
                }
            }
        }

        val feedUris = slots.filter { !it.isTimeline }.map { it.uri }.distinct()
        val infoByUri = mutableMapOf<String, BskyFeedInfo>()
        if (feedUris.isNotEmpty()) {
            feedUris.chunked(25).forEach { batch ->
                val batchResult = runCatching { api.getFeedGenerators("Bearer $token", batch) }
                val batchBody = batchResult.getOrNull()?.takeIf { it.isSuccessful }?.body()
                if (batchBody != null) {
                    batchBody.feeds.forEach { infoByUri[it.uri] = BskyFeedInfo(it.uri, it.displayName, it.avatar, it.acceptsInteractions, it.did) }
                } else {
                    // One bad URI shouldn't sink the whole batch — retry individually
                    batch.forEach { uri ->
                        runCatching { api.getFeedGenerators("Bearer $token", listOf(uri)) }
                            .getOrNull()?.body()?.feeds?.firstOrNull()?.let {
                                infoByUri[it.uri] = BskyFeedInfo(it.uri, it.displayName, it.avatar, it.acceptsInteractions, it.did)
                            }
                    }
                }
            }
        }

        // Reassemble in the user's original pin order, substituting the synthetic
        // "Following" entry for timeline slots.
        val allFeeds = mutableListOf<BskyFeedInfo>()
        val seen = mutableSetOf<String>()
        slots.forEach { slot ->
            val info = if (slot.isTimeline) BskyFeedInfo(FOLLOWING_FEED_URI, "Following", null) else infoByUri[slot.uri]
            if (info != null && seen.add(info.uri)) allFeeds.add(info)
        }

        // Fallback: feeds the user created themself (only if they have no saved feeds at all)
        if (allFeeds.isEmpty()) {
            runCatching { api.getActorFeeds("Bearer $token", did, 30) }
                .getOrNull()?.body()?.feeds?.forEach {
                    allFeeds.add(BskyFeedInfo(it.uri, it.displayName, it.avatar))
                }
        }

        if (allFeeds.isEmpty() && prefsError != null) error(prefsError!!)

        allFeeds
    }

    companion object {
        /** Sentinel URI standing in for the pinned "Following" home timeline, which
         *  (unlike every other saved feed) is served by getTimeline, not getFeed. */
        const val FOLLOWING_FEED_URI = "timeline://following"

        /** All known Popfeed review/Leaflet blog collection names — shared by
         *  the backfill paths below, so there's exactly one place that knows
         *  what these third-party lexicons are currently called. */
        val REVIEW_COLLECTIONS = listOf("social.popfeed.feed.review", "social.popfeed.review", "app.popsky.review")
        val LEAFLET_COLLECTIONS = listOf("site.standard.document", "pub.leaflet.document")

        // See getSubscribedReviews's doc comment for where these numbers come
        // from — deliberately well under the ~10 req/sec the documented
        // 3,000-per-5-min HTTP limit implies, since a subscribed list can hit
        // several different third-party PDSs at once, each with its own
        // unknown limit.
        private const val SUBSCRIBED_FETCH_CONCURRENCY = 3
        private const val SUBSCRIBED_FETCH_STAGGER_MS = 150L
    }

    suspend fun getAuthorFeed(token: String, actorDid: String, cursor: String? = null)
        : Result<Pair<List<MediaItem>, String?>> = runCatching {
        val resp = api.getAuthorFeed("Bearer $token", actorDid, 50, cursor, "posts_no_replies")
        val body = resp.body() ?: error("AuthorFeed ${resp.code()}")
        // Filter out reposts — items with a non-null reason are reposts by the author of someone else's post
        val ownPosts = body.feed.filter { it.reason == null }
        Pair(ownPosts.flatMap { parseFeedItemSafe(it) }, body.cursor)
    }

    // ── Profile Overhaul ──────────────────────────────────────────────────────

    suspend fun getFullProfile(token: String, did: String): Result<ProfileData> = runCatching {
        val resp = api.getProfileDetailed("Bearer $token", did)
        val body = resp.body() ?: error("Profile ${resp.code()}: ${resp.message()}")
        ProfileData(
            author = AuthorInfo(
                did = body.did, handle = body.handle,
                displayName = body.displayName?.takeIf { it.isNotBlank() } ?: body.handle,
                avatarUrl = body.avatar,
                followingUri = body.viewer?.following,
                isFollowing = body.viewer?.following != null
            ),
            bannerUrl = body.banner,
            description = body.description ?: "",
            followersCount = body.followersCount ?: 0,
            followsCount = body.followsCount ?: 0,
            postsCount = body.postsCount ?: 0
        )
    }

    /** Profile "Posts" tab: the account's own original posts only — reposts,
     *  quote reposts, and replies/comments the account left under other posts
     *  are all excluded (posts_no_replies already drops replies; reason==null
     *  drops reposts; the embed-type check drops quote reposts). */
    suspend fun getProfilePosts(token: String, did: String, cursor: String? = null)
        : Result<Pair<List<MediaItem>, String?>> = runCatching {
        val resp = api.getAuthorFeed("Bearer $token", did, 50, cursor, "posts_no_replies")
        val body = resp.body() ?: error("AuthorFeed ${resp.code()}")
        val ownPosts = body.feed.filter { item ->
            item.reason == null && item.post.embed?.type?.contains("record") != true
        }
        Pair(ownPosts.flatMap { parseFeedItemSafe(it) }, body.cursor)
    }

    /** Profile "Reposts" tab: posts this account reposted — both plain
     *  reposts (reason == reasonRepost) and quote reposts (an own post,
     *  reason == null, whose embed wraps another record). Quote reposts used
     *  to be deliberately excluded here (mirroring how getProfilePosts
     *  excludes them from the Posts/Media tabs), but they belong in Reposts
     *  too — they just weren't being matched by the reason-only filter. */
    suspend fun getProfileReposts(token: String, did: String, cursor: String? = null)
        : Result<Pair<List<MediaItem>, String?>> = runCatching {
        val resp = api.getAuthorFeed("Bearer $token", did, 50, cursor, "posts_no_replies")
        val body = resp.body() ?: error("AuthorFeed ${resp.code()}")
        val reposted = body.feed.filter { item ->
            item.reason?.type?.contains("reasonRepost") == true ||
                (item.reason == null && item.post.embed?.type?.contains("record") == true)
        }
        Pair(reposted.flatMap { parseFeedItemSafe(it) }, body.cursor)
    }

    /** Profile "Likes" tab: works for the logged-in user's own account (via the
     *  authenticated getActorLikes endpoint) and, for anyone else, by reading
     *  their public app.bsky.feed.like records straight off their repo and
     *  hydrating the liked posts in batches — the same approach RaccNet uses,
     *  since getActorLikes itself only returns results for the caller's own DID. */
    suspend fun getProfileLikes(token: String, viewerDid: String, targetDid: String, cursor: String? = null)
        : Result<Pair<List<MediaItem>, String?>> = runCatching {
        if (viewerDid.isNotBlank() && targetDid == viewerDid) {
            val resp = api.getActorLikes("Bearer $token", targetDid, 50, cursor)
            val body = resp.body() ?: error("Likes ${resp.code()}")
            return@runCatching Pair(body.feed.flatMap { parseFeedItemSafe(it) }, body.cursor)
        }
        val authHeader = "Bearer $token".takeIf { token.isNotBlank() }
        val listResp = api.listRecords(authHeader, targetDid, "app.bsky.feed.like", 50, cursor)
        val listBody = listResp.body() ?: error("ListRecords ${listResp.code()}")
        val uris = listBody.records.mapNotNull { rec ->
            rec.value?.takeIf { it.isJsonObject }?.asJsonObject
                ?.getAsJsonObject("subject")?.get("uri")?.takeIf { it.isJsonPrimitive }?.asString
        }
        if (uris.isEmpty()) return@runCatching Pair(emptyList(), listBody.cursor)
        val posts = mutableListOf<BskyPost>()
        uris.chunked(25).forEach { batch ->
            runCatching { api.getPosts(authHeader ?: "", batch) }.getOrNull()
                ?.takeIf { it.isSuccessful }?.body()?.posts?.let { posts.addAll(it) }
        }
        Pair(posts.flatMap { parseFeedItemSafe(BskyFeedItem(post = it)) }, listBody.cursor)
    }

    /** Profile "Blogs" tab (Leaflet, pub.leaflet.* — migrated to site.standard.*
     *  in mid-2026). Tries the current collection first, then falls back to the
     *  legacy one so older/un-migrated accounts still show their blogs. Returns
     *  an empty list (never an error) if the account has no Leaflet documents —
     *  callers use that to decide whether the Blogs tab appears at all. */
    @Volatile private var knownLeafletCollection: String? = null

    suspend fun getLeafletBlogs(did: String): List<LeafletBlog> {
        // Same known-collection fast path as getPopfeedReviews below — once
        // any account's blogs are found under one of the two candidate
        // collection names, try that one first for every other account,
        // cutting the common case down to 1 request instead of up to 2.
        // Rate limiting is the whole reason this matters now: see
        // getSubscribedReviews/getSubscribedBlogs's doc comment on pacing.
        knownLeafletCollection?.let { known ->
            val resp = runCatching { api.listRecords(null, did, known, 50, null) }.getOrNull()
            val body = resp?.takeIf { it.isSuccessful }?.body()
            if (body != null && body.records.isNotEmpty()) {
                val blogs = body.records.mapNotNull { rec ->
                    val obj = rec.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    parseLeafletBlogRecord(did, rec.uri, obj)
                }
                if (blogs.isNotEmpty()) return blogs.sortedByDescending { it.createdAt }
            }
        }
        for (collection in LEAFLET_COLLECTIONS.filterNot { it == knownLeafletCollection }) {
            val resp = runCatching { api.listRecords(null, did, collection, 50, null) }.getOrNull()
            val body = resp?.takeIf { it.isSuccessful }?.body() ?: continue
            if (body.records.isEmpty()) continue
            val blogs = body.records.mapNotNull { rec ->
                val obj = rec.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                parseLeafletBlogRecord(did, rec.uri, obj)
            }
            if (blogs.isNotEmpty()) { knownLeafletCollection = collection; return blogs.sortedByDescending { it.createdAt } }
        }
        return emptyList()
    }

    /** Parses one raw Leaflet document record (whether read via listRecords
     *  during backfill, or pushed live off the Jetstream firehose — see
     *  getSubscribedBlogs) into a [LeafletBlog]. Pulled out of [getLeafletBlogs]
     *  so both paths share exactly one parsing implementation. Best-effort
     *  thumbnail/description extraction, same defensive style as
     *  getPopfeedReviews below — Leaflet's block schema isn't fully modeled,
     *  so these are just "the first image/text-ish field found under a
     *  handful of likely key names", not a guaranteed-correct parse. */
    suspend fun parseLeafletBlogRecord(did: String, uri: String, obj: com.google.gson.JsonObject): LeafletBlog? {
        val title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: return null
        val createdAt = obj.get("publishedAt")?.takeIf { it.isJsonPrimitive }?.asString
            ?: obj.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val description = firstStringField(obj, "description", "subtitle", "summary")
        val thumbnailUrl = firstImageField(obj, did, "coverImage", "cover", "image", "thumb", "icon")
        return LeafletBlog(
            uri = uri, title = title, bodyText = extractLeafletBodyText(obj), createdAt = createdAt,
            description = description, thumbnailUrl = thumbnailUrl,
            blocks = runCatching { parseLeafletBlocks(did, obj) }.getOrDefault(emptyList())
        )
    }

    /** Best-effort structural parse of a Leaflet document's block tree into
     *  [LeafletBlock]s — headers, bold runs, checklist items, and inline
     *  images all render as themselves now (item: blog rich formatting)
     *  instead of collapsing into [extractLeafletBodyText]'s flattened
     *  plain text. Leaflet's block schema keeps evolving and isn't fully
     *  published, so this walks the whole tree the same way
     *  [extractLeafletBodyText] already does (same root-resolution
     *  precedence, same text-key names) rather than assuming one fixed
     *  pages[].blocks[].block nesting — a node is recognized as a leaf
     *  block by its `$type` substring or by carrying a text/checked field
     *  directly, wherever in the tree it actually sits. */
    private suspend fun parseLeafletBlocks(did: String, root: com.google.gson.JsonObject): List<LeafletBlock> {
        val out = mutableListOf<LeafletBlock>()
        // Same text-carrying key names extractLeafletBodyText already
        // confirmed work against real records (including the camelCase
        // "plainText" variant) — kept in sync with that function rather
        // than re-guessing a separate set here.
        val textKeys = listOf("plaintext", "plainText", "text")

        fun rawTextOf(block: com.google.gson.JsonObject): String? {
            for (key in textKeys) {
                val v = block.get(key)
                if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                    val s = v.asString
                    if (s.isNotBlank()) return s
                }
            }
            return null
        }

        // Splits one text-bearing block's plaintext into bold/non-bold runs
        // using its `facets` (byte-offset ranges + feature list), the same
        // richtext-facet shape Bluesky posts themselves use.
        fun textSpansOf(block: com.google.gson.JsonObject): List<LeafletTextSpan> {
            val plaintext = rawTextOf(block) ?: return emptyList()
            val bytes = plaintext.toByteArray(Charsets.UTF_8)
            val boldRanges = mutableListOf<IntRange>()
            val facets = block.getAsJsonArray("facets")
            if (facets != null) {
                for (facetEl in facets) {
                    val facet = facetEl.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val features = facet.getAsJsonArray("features") ?: continue
                    val isBold = features.any { f ->
                        f.isJsonObject && (f.asJsonObject.get("\$type")?.takeIf { it.isJsonPrimitive }?.asString
                            ?.contains("bold", ignoreCase = true) == true)
                    }
                    if (!isBold) continue
                    val idx = facet.getAsJsonObject("index") ?: continue
                    val start = idx.get("byteStart")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
                    val end = idx.get("byteEnd")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
                    if (start in 0..bytes.size && end in start..bytes.size && end > start) boldRanges += start until end
                }
            }
            if (boldRanges.isEmpty()) return listOf(LeafletTextSpan(plaintext, bold = false))
            val cuts = sortedSetOf(0, bytes.size)
            boldRanges.forEach { cuts.add(it.first); cuts.add(it.last + 1) }
            val sortedCuts = cuts.sorted()
            val spans = mutableListOf<LeafletTextSpan>()
            for (i in 0 until sortedCuts.size - 1) {
                val s = sortedCuts[i]; val e = sortedCuts[i + 1]
                if (s >= e) continue
                val runText = runCatching { String(bytes, s, e - s, Charsets.UTF_8) }.getOrNull() ?: continue
                val isBold = boldRanges.any { s >= it.first && e <= it.last + 1 }
                if (runText.isNotEmpty()) spans += LeafletTextSpan(runText, isBold)
            }
            return spans.ifEmpty { listOf(LeafletTextSpan(plaintext, bold = false)) }
        }

        suspend fun parseLeaf(block: com.google.gson.JsonObject, alignment: LeafletAlign = LeafletAlign.START): LeafletBlock? {
            val type = block.get("\$type")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val checkedField = block.get("checked")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            return when {
                type.contains("header", ignoreCase = true) -> {
                    val text = rawTextOf(block) ?: return null
                    val level = block.get("level")?.takeIf { it.isJsonPrimitive }?.asInt ?: 2
                    LeafletBlock.Header(text, level.coerceIn(1, 4), alignment)
                }
                type.contains("image", ignoreCase = true) -> {
                    val url = firstImageField(block, did, "image", "src", "media", "thumb") ?: return null
                    val alt = firstStringField(block, "alt", "caption", "altText")
                    LeafletBlock.ImageBlock(url, alt, alignment)
                }
                checkedField != null && rawTextOf(block) != null -> {
                    val checked = checkedField.asBoolean
                    val text = textSpansOf(block).joinToString("") { it.text }
                    if (text.isBlank()) null else LeafletBlock.ChecklistItem(text, checked, alignment)
                }
                rawTextOf(block) != null -> {
                    val spans = textSpansOf(block)
                    if (spans.isEmpty()) null else LeafletBlock.Paragraph(spans, alignment)
                }
                else -> null
            }
        }

        // Bug fix (blogs not rendering rich formatting): this used to only
        // look under a rigid pages[].blocks[].block path, silently
        // returning nothing (and falling back to plain bodyText) for any
        // record shaped differently. extractLeafletBodyText — which does
        // demonstrably find real text in real records — resolves its root
        // as `content ?? pages ?? root itself` and then walks the *entire*
        // tree rather than assuming one fixed nesting; this now does the
        // same instead of assuming "pages" is the only valid root or that
        // blocks are strictly one level deep under it. A node is emitted
        // as a leaf block the moment it looks like one (recognized $type,
        // or a text/checked field); anything else is walked field-by-field
        // so content isn't lost just because it sits one level deeper (or
        // shallower) than expected.
        // Bug fix (blogs not honoring text alignment): Leaflet's block-list
        // schema (pub.leaflet.pages.linearDocument#block) wraps each real
        // block in a container shaped like { block: {...the actual block},
        // alignment?: "text-align-left" | "text-align-center" |
        // "text-align-right" } — alignment lives on this outer wrapper, a
        // sibling of "block", not inside the block object itself. The
        // generic walk below used to just recurse straight through this
        // wrapper's fields (since the wrapper itself never looks like a
        // leaf block to parseLeaf) — which does eventually find and parse
        // the nested block fine, but by the time it gets there the
        // wrapper's own "alignment" field is a sibling that's already been
        // stepped past, not an ancestor of anything walk() still has a
        // handle on. This wrapper shape is now detected explicitly so
        // alignment can be read here and carried into the parsed leaf,
        // before generic recursion ever has a chance to lose that context.
        fun alignmentOf(obj: com.google.gson.JsonObject): LeafletAlign {
            val raw = obj.get("alignment")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return LeafletAlign.START
            return when {
                raw.contains("center", ignoreCase = true) -> LeafletAlign.CENTER
                raw.contains("right", ignoreCase = true) || raw.contains("end", ignoreCase = true) -> LeafletAlign.END
                else -> LeafletAlign.START
            }
        }

        suspend fun walk(el: com.google.gson.JsonElement?) {
            if (el == null || el.isJsonNull) return
            if (el.isJsonArray) { el.asJsonArray.forEach { walk(it) }; return }
            if (!el.isJsonObject) return
            val obj = el.asJsonObject
            val wrappedBlock = obj.get("block")?.takeIf { it.isJsonObject }?.asJsonObject
            if (wrappedBlock != null) {
                val leaf = parseLeaf(wrappedBlock, alignmentOf(obj))
                if (leaf != null) { out.add(leaf); return }
            }
            val leaf = parseLeaf(obj)
            if (leaf != null) { out.add(leaf); return }
            for ((_, v) in obj.entrySet()) walk(v)
        }

        walk(root.get("content") ?: root.get("pages") ?: root)
        return out
    }

    /** Leaflet documents are block-based (pages -> blocks -> nested content),
     *  and the exact block schema keeps evolving, so rather than modeling every
     *  block type we defensively walk the whole JSON tree and concatenate any
     *  string found under a handful of known text-carrying keys. Good enough
     *  for a readable plain-text rendering of the blog body; block-level
     *  formatting (headers, lists, images) is intentionally not preserved. */
    private fun extractLeafletBodyText(root: com.google.gson.JsonObject): String {
        val textKeys = setOf("plaintext", "plainText", "text")
        val out = StringBuilder()
        fun walk(el: com.google.gson.JsonElement?) {
            if (el == null) return
            when {
                el.isJsonObject -> {
                    val o = el.asJsonObject
                    for (key in textKeys) {
                        val v = o.get(key)
                        if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                            val s = v.asString
                            if (s.isNotBlank()) { out.append(s); out.append("\n\n") }
                        }
                    }
                    for ((k, v) in o.entrySet()) { if (k !in textKeys) walk(v) }
                }
                el.isJsonArray -> el.asJsonArray.forEach { walk(it) }
            }
        }
        walk(root.get("content") ?: root.get("pages") ?: root)
        return out.toString().trim()
    }

    // Speed fix (this session): getPopfeedReviews used to try 3 possible
    // Popfeed collection names SEQUENTIALLY — one full network round trip
    // each — before giving up on an account with no reviews under the
    // first name tried. With N accounts fanned out in parallel via
    // getFriendsPopfeedReviews, that's up to 3x the necessary round trips
    // for every account whose reviews live under whichever collection name
    // this app happens to check last — a very plausible explanation for
    // "loads reviews from one person but not another known to have some"
    // (a slow 2nd/3rd sequential attempt losing the race against
    // OkHttp's per-host concurrency cap under that much fan-out, or simply
    // taking long enough that it looked like it never happened). Two
    // fixes: (1) the 3 collection names are now tried in parallel per
    // account instead of sequentially — same total request count, far less
    // wall-clock time; (2) whichever collection name actually returns data
    // is remembered process-wide, and checked FIRST (alone, no fan-out at
    // all) for every subsequent account — in practice a given Popfeed
    // deployment uses one collection name for everyone, so only the very
    // first review lookup each app run pays the "try all 3" cost.
    @Volatile private var knownPopfeedCollection: String? = null

    /** Parses one raw Popfeed review record (backfill via listRecords, or a
     *  getSubscribedReviews) into a [PopfeedReview].
     *  Pulled out of [getPopfeedReviews] so both paths share one parser. */
    suspend fun parsePopfeedReviewRecord(did: String, uri: String, obj: com.google.gson.JsonObject): PopfeedReview? {
        val subject = obj.getAsJsonObject("subject") ?: obj.getAsJsonObject("item") ?: obj
        val title = firstStringField(subject, "title", "name") ?: firstStringField(obj, "title", "name")
            ?: return null
        val image = firstImageField(subject, did, "poster", "posterUrl", "coverUrl", "artworkUrl", "image", "coverImage", "thumb")
            ?: firstImageField(obj, did, "poster", "posterUrl", "coverUrl", "artworkUrl", "image", "coverImage", "thumb")
        // Distinct landscape/backdrop art (as opposed to the portrait
        // poster above) — used for the wide banner in the review
        // detail popup so it isn't a cropped portrait image.
        val backdrop = firstImageField(subject, did, "backdrop", "backdropUrl", "banner", "bannerUrl", "landscape", "landscapeUrl", "fanart", "heroImage", "wideImage")
            ?: firstImageField(obj, did, "backdrop", "backdropUrl", "banner", "bannerUrl", "landscape", "landscapeUrl", "fanart", "heroImage", "wideImage")
        val text = firstStringField(obj, "text", "review", "body", "content") ?: ""
        // Popfeed's rating field is on a fixed 0–10 scale (half-star
        // granularity — one point per half star), not a 0–5 scale.
        // Always dividing by 2 here is what matches that confirmed
        // 0–10 scale (see history for the previous, wrong heuristic).
        val rawRating = obj.get("rating")?.takeIf { it.isJsonPrimitive }?.asFloat
            ?: obj.get("stars")?.takeIf { it.isJsonPrimitive }?.asFloat
            ?: obj.get("score")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
        val rating5 = rawRating / 2f
        val createdAt = firstStringField(obj, "createdAt", "publishedAt") ?: ""
        val category = firstStringField(subject, "creativeWorkType", "mediaType", "type")
            ?: firstStringField(obj, "creativeWorkType", "mediaType", "type")
        return PopfeedReview(
            uri = uri, mediaTitle = title, mediaImageUrl = image, mediaBackdropUrl = backdrop,
            ratingOutOf5 = rating5.coerceIn(0f, 5f), reviewText = text, createdAt = createdAt,
            mediaCategory = category
        )
    }

    suspend fun getPopfeedReviews(did: String): List<PopfeedReview> {
        suspend fun tryCollection(collection: String): List<PopfeedReview>? {
            val resp = runCatching { api.listRecords(null, did, collection, 50, null) }.getOrNull()
            val body = resp?.takeIf { it.isSuccessful }?.body() ?: return null
            if (body.records.isEmpty()) return null
            val reviews = body.records.mapNotNull { rec ->
                val obj = rec.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                parsePopfeedReviewRecord(did, rec.uri, obj)
            }
            return reviews.takeIf { it.isNotEmpty() }
        }

        val allCollections = REVIEW_COLLECTIONS

        // Fast path: we already know which collection this Popfeed
        // deployment uses — a single request, no fan-out.
        knownPopfeedCollection?.let { known ->
            tryCollection(known)?.let { return it.sortedByDescending { r -> r.createdAt } }
        }

        // Otherwise (first lookup this session, or this specific account
        // just has no reviews under the known collection) check the rest
        // one at a time, not in parallel — this used to fire all of them
        // simultaneously, which meant every account still in "unknown
        // collection" state during a subscribed-accounts fetch (see getSubscribedReviews/getSubscribedBlogs)
        // was contributing up to 3 concurrent requests instead of 1, right
        // when the whole point was to keep that burst small enough not to
        // trip Bluesky's rate limiting. Once any account resolves this,
        // knownPopfeedCollection short-circuits every account after it back
        // down to a single request, so this sequential fallback path is
        // only ever actually slow for the very first few accounts of a
        // session, not the whole follow list.
        for (collection in allCollections.filterNot { it == knownPopfeedCollection }) {
            val hit = tryCollection(collection)
            if (hit != null) {
                knownPopfeedCollection = collection
                return hit.sortedByDescending { it.createdAt }
            }
        }
        return emptyList()
    }

    /** Reviews for a caller-chosen set of subscribed accounts (see the
     *  profile "Subscribe" button in the Reviews tab) — replaces the old
     *  Jetstream/firehose-backed "everyone you follow" pipeline entirely.
     *  There's genuinely no bulk "reviews for these N accounts" endpoint on
     *  Popfeed's side (confirmed — no public AppView for it), so one
     *  com.atproto.repo.listRecords call per subscribed account is
     *  unavoidable; what's controllable is how it's paced.
     *
     *  Pacing here is sized off Bluesky's actually-documented limits, not a
     *  guess: the write-quota "5,000 points/hour" limit some people cite
     *  doesn't apply at all (listRecords is a read, points are only charged
     *  for record creates/updates/deletes) — the real constraint for reads
     *  is the general HTTP API limit, published as 3,000 requests per 5
     *  minutes per IP (~10/sec sustained), plus each account's own PDS
     *  potentially applying its own, unknown limit if it's not
     *  bsky.social-hosted. A subscribed-accounts list is expected to be
     *  small (tens, not hundreds — it's an explicit opt-in per account, not
     *  "everyone followed"), so CONCURRENCY here is deliberately
     *  conservative relative to the ~10 req/sec headroom that limit implies,
     *  leaving comfortable margin for whatever a given third-party PDS's own
     *  limit turns out to be. */
    suspend fun getSubscribedReviews(token: String, dids: List<String>): List<FriendPopfeedReview> = coroutineScope {
        if (dids.isEmpty()) return@coroutineScope emptyList()
        val authors = fetchAuthorInfos(token, dids)
        // Bug fix (Hub Reviews flashing in then disappearing on app start):
        // fetchAuthorInfos can fail WHOLESALE (cold-start network storm, an
        // auth token that isn't ready yet, a rate limit) and, since it
        // swallows its own per-batch/per-account errors, returns an empty
        // (or near-empty) map with no exception at all. The mapNotNull
        // below used to treat every one of those as "this specific account
        // has nothing to show" and skip it — so a wholesale failure quietly
        // produced an empty-but-"successful" list, which
        // loadFriendsReviewsIfNeeded then happily wrote over perfectly good
        // cached data, and (since no exception means reviewsOk stays true)
        // permanently stopped the background retry loop from ever trying
        // again. A real "we asked for N accounts and resolved none of
        // them" is a failure, not an empty result, and must propagate as
        // one so the caller keeps the cache and keeps retrying instead.
        if (authors.isEmpty()) throw java.io.IOException("Couldn't resolve any subscribed author profiles")
        val gate = Semaphore(SUBSCRIBED_FETCH_CONCURRENCY)
        dids.distinct().mapNotNull { did ->
            // Bug fix (item 4): an account fetchAuthorInfos genuinely
            // couldn't resolve (even after its own individual-retry pass)
            // used to fall back to a placeholder AuthorInfo with the raw
            // DID as its displayName and no avatar — rendering as a
            // broken-looking card with no icon or real name. Skipping that
            // account's reviews entirely instead means the Hub only ever
            // shows cards it can actually put a name and icon on.
            val author = authors[did] ?: return@mapNotNull null
            async {
                delay((dids.indexOf(did) % SUBSCRIBED_FETCH_CONCURRENCY) * SUBSCRIBED_FETCH_STAGGER_MS)
                gate.withPermit {
                    runCatching { getPopfeedReviews(did) }.getOrDefault(emptyList())
                        .map { FriendPopfeedReview(author, it) }
                }
            }
        }.awaitAll().flatten().sortedByDescending { it.review.createdAt }
    }

    /** Blogs equivalent of [getSubscribedReviews] — same reasoning, same
     *  pacing, separate subscription list (an account can be subscribed for
     *  Reviews, Blogs, both, or neither). */
    suspend fun getSubscribedBlogs(token: String, dids: List<String>): List<FriendLeafletBlog> = coroutineScope {
        if (dids.isEmpty()) return@coroutineScope emptyList()
        val authors = fetchAuthorInfos(token, dids)
        // See getSubscribedReviews' matching comment just above — same
        // wholesale-failure-vs-genuinely-empty distinction applies here.
        if (authors.isEmpty()) throw java.io.IOException("Couldn't resolve any subscribed author profiles")
        val gate = Semaphore(SUBSCRIBED_FETCH_CONCURRENCY)
        dids.distinct().mapNotNull { did ->
            // See getSubscribedReviews' matching comment (item 4) — skip
            // rather than render a broken did-as-name/no-icon card.
            val author = authors[did] ?: return@mapNotNull null
            async {
                delay((dids.indexOf(did) % SUBSCRIBED_FETCH_CONCURRENCY) * SUBSCRIBED_FETCH_STAGGER_MS)
                gate.withPermit {
                    runCatching { getLeafletBlogs(did) }.getOrDefault(emptyList())
                        .map { FriendLeafletBlog(author, it) }
                }
            }
        }.awaitAll().flatten().sortedByDescending { it.blog.createdAt }
    }

    /** One batched app.bsky.actor.getProfiles call (groups of 25, same as
     *  [getLiveNowStreams]) for display info (avatar/name) on a set of
     *  DIDs — this part genuinely does have a real bulk AppView endpoint,
     *  unlike the review/blog records themselves. */
    /** Bug fix (item 4 — Hub Reviews/Blogs cards showing raw DIDs with no
     *  avatar instead of a real name/icon): this used to be a single
     *  getProfiles call per 25-account batch, with no recovery if that
     *  batch came back missing some (or all) of the accounts it asked for
     *  — a batch-level retryOnce covers a fully-failed request, but not a
     *  successful response that's just missing a handful of accounts (a
     *  deactivated account, a slow/unreachable third-party PDS, etc. can
     *  each drop just that one profile out of an otherwise-fine batch).
     *  Every gap like that silently fell through to getSubscribedReviews/
     *  getSubscribedBlogs' did-as-displayName placeholder, which is exactly
     *  the broken-looking card in the bug report. Now any dids still
     *  missing after the batched pass get one more individual-request pass
     *  each, so a single bad account in a batch doesn't take its healthy
     *  batch-mates down with it, and only genuinely unresolvable accounts
     *  still fall through to the caller's placeholder. */
    private suspend fun fetchAuthorInfos(token: String, dids: List<String>): Map<String, AuthorInfo> = coroutineScope {
        val distinct = dids.distinct()
        val found = distinct.chunked(25).map { batch ->
            async {
                val resp = runCatching { retryOnce { api.getProfiles("Bearer $token", batch) } }.getOrNull()
                resp?.takeIf { it.isSuccessful }?.body()?.profiles.orEmpty().map { p ->
                    AuthorInfo(did = p.did, handle = p.handle, displayName = p.displayName ?: p.handle, avatarUrl = p.avatar)
                }
            }
        }.awaitAll().flatten().associateBy { it.did }

        val missing = distinct.filterNot { it in found }
        if (missing.isEmpty()) return@coroutineScope found

        // Bug fix (reviews/blogs going empty instead of just missing a few
        // cards): if the batched pass above failed wholesale (a network
        // blip affecting every batch, not just one bad account), `missing`
        // is every requested did — this recovery pass used to fire that
        // many individual requests with no concurrency limit at all, which
        // can itself trigger rate limiting that makes the recovery pass
        // fail too, leaving `authors` mostly empty and, in turn,
        // getSubscribedReviews/getSubscribedBlogs filtering nearly
        // everything out (see their own doc comments). Gating it behind
        // the same SUBSCRIBED_FETCH_CONCURRENCY the rest of this file's
        // per-account fan-outs already respect keeps this pass from being
        // the thing that causes the failure it's trying to recover from.
        val recoveryGate = Semaphore(SUBSCRIBED_FETCH_CONCURRENCY)
        val recovered = missing.map { did ->
            async {
                recoveryGate.withPermit {
                    val resp = runCatching { retryOnce { api.getProfiles("Bearer $token", listOf(did)) } }.getOrNull()
                    resp?.takeIf { it.isSuccessful }?.body()?.profiles.orEmpty().map { p ->
                        AuthorInfo(did = p.did, handle = p.handle, displayName = p.displayName ?: p.handle, avatarUrl = p.avatar)
                    }
                }
            }
        }.awaitAll().flatten().associateBy { it.did }

        found + recovered
    }

    /** Every account the user follows (not just mutuals/DM contacts) — used by
     *  item 8's Latest Reviews / Livestreams sections, which per Popfeed's own
     *  "Reviews from Friends" design (confirmed via their public writeups)
     *  pull from the full following list, not just people you actually talk
     *  to. Also filters out blocked accounts, same as getMutuals/
     *  getFriendsSharedPosts. Capped at `cap` follows to keep the resulting
     *  N-parallel-requests fan-out (one per follow, in getFriendsPopfeedReviews/
     *  getLiveFriends) reasonable for accounts following thousands of people. */
    suspend fun getAllFollows(token: String, myDid: String, cap: Int = 300): Result<List<AuthorInfo>> = runCatching {
        val blockedDids = getBlockedDids(token).getOrDefault(emptySet())
        val out = LinkedHashMap<String, AuthorInfo>()
        var cursor: String? = null
        do {
            val resp = api.getFollows("Bearer $token", myDid, 100, cursor)
            if (!resp.isSuccessful) error("getFollows ${resp.code()}: ${resp.message()}")
            val body = resp.body() ?: break
            body.follows.forEach {
                if (it.did != myDid && it.did !in blockedDids) {
                    out[it.did] = AuthorInfo(
                        did = it.did, handle = it.handle,
                        displayName = it.displayName?.takeIf { n -> n.isNotBlank() } ?: it.handle,
                        avatarUrl = it.avatar
                    )
                }
            }
            cursor = body.cursor
        } while (!cursor.isNullOrBlank() && out.size < cap)
        out.values.toList()
    }

    // getFriendsPopfeedReviews (the old N-parallel-requests-per-follow fan-out
    // for the Hub's "Latest Reviews" section) has been removed — it was dead
    // code with zero call sites (superseded by the Subscribe-list model, which fetches only
    // the same data with bounded/staggered per-account requests once, caches
    // it to disk, and keeps it live afterward via a single filtered Jetstream
    // subscription instead of ever re-running a full fan-out). Purged per the
    // architecture note's "remove legacy PDS-loop caches that run parallel to
    // the new pipeline" instruction — see getSubscribedReviews's doc comment
    // for why a literal single "batched" REST call isn't possible here (no
    // such bulk endpoint exists for third-party lexicons like Popfeed/Leaflet)
    // and why Jetstream is the correct replacement instead.

    /** Feature (this session): Bluesky's native "Live Now" badge — checks a
     *  set of accounts' profile `status` (app.bsky.actor.defs#statusView,
     *  confirmed against the real indigo/Go reference implementation — see
     *  BskyStatusView's comment in Models.kt) for an active status with an
     *  off-platform embed link, via app.bsky.actor.getProfiles (batched 25
     *  actors at a time, the lexicon's real cap, fetched in parallel).
     *  Wrapped per-batch and per-item in runCatching, per this file's
     *  established defensive-parsing pattern, so one bad batch/account can't
     *  take the others down with it. */
    suspend fun getLiveNowStreams(token: String, dids: List<String>): Result<List<BlueskyLiveNowStream>> = runCatching {
        coroutineScope {
            dids.distinct().chunked(25).map { batch ->
                async {
                    val resp = runCatching { retryOnce { api.getProfiles("Bearer $token", batch) } }.getOrNull()
                    val profiles = resp?.takeIf { it.isSuccessful }?.body()?.profiles ?: return@async emptyList()
                    profiles.mapNotNull { profile ->
                        runCatching {
                            val status = profile.status ?: return@runCatching null
                            if (status.isActive == false) return@runCatching null
                            val external = status.embed?.external ?: return@runCatching null
                            if (external.uri.isBlank()) return@runCatching null
                            BlueskyLiveNowStream(
                                author = AuthorInfo(
                                    did = profile.did, handle = profile.handle,
                                    displayName = profile.displayName ?: profile.handle, avatarUrl = profile.avatar
                                ),
                                title = external.title?.takeIf { it.isNotBlank() } ?: "Live now",
                                uri = external.uri,
                                thumbUrl = external.thumb,
                                platform = liveNowPlatformFor(external.uri)
                            )
                        }.getOrNull()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private fun liveNowPlatformFor(uri: String): LiveNowPlatform {
        val host = runCatching { java.net.URI(uri).host?.lowercase() }.getOrNull() ?: ""
        return when {
            host.contains("twitch.tv") -> LiveNowPlatform.TWITCH
            host.contains("youtube.com") || host.contains("youtu.be") -> LiveNowPlatform.YOUTUBE
            else -> LiveNowPlatform.OTHER
        }
    }

    private fun firstStringField(obj: com.google.gson.JsonObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (k in keys) {
            val v = obj.get(k)
            if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString && v.asString.isNotBlank()) return v.asString
        }
        return null
    }

    /** Bug fix: Popfeed reviews' posters weren't showing up because the poster
     *  field, when present, is very likely a blob reference (the standard
     *  AT-proto shape for an embedded image: `{"$type":"blob","ref":{"$link":
     *  cid},"mimeType":...}`) rather than a plain URL string — and
     *  [firstStringField] only matches string primitives, so it silently
     *  treated a present-but-blob field as absent. This checks both shapes:
     *  a direct URL string, or a blob to resolve via the account's own PDS
     *  (com.atproto.sync.getBlob), the same way this app already resolves
     *  video blobs (see BlueskyBlobResolver). */
    private suspend fun firstImageField(obj: com.google.gson.JsonObject?, ownerDid: String, vararg keys: String): String? {
        if (obj == null) return null
        for (k in keys) {
            val v = obj.get(k) ?: continue
            if (v.isJsonPrimitive && v.asJsonPrimitive.isString && v.asString.isNotBlank()) return v.asString
            if (v.isJsonObject) {
                val blob = v.asJsonObject
                val cid = blob.getAsJsonObject("ref")?.get("\$link")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: blob.get("cid")?.takeIf { it.isJsonPrimitive }?.asString
                if (!cid.isNullOrBlank()) {
                    val resolved = runCatching { withContext(Dispatchers.IO) { BlueskyBlobResolver.resolveBlobUrl(ownerDid, cid) } }.getOrNull()
                    if (resolved != null) return resolved
                }
            }
        }
        return null
    }

    /** Profile "Backlog" tab (Popfeed's backlog + watchlist — movies, TV
     *  shows, and games the account has logged to watch/play eventually).
     *
     *  Unlike [getPopfeedReviews], this one is grounded in a confirmed real
     *  schema rather than a guess: an open-source third-party Popfeed
     *  integration (paperbnd.koplugin, a KOReader reading-progress sync
     *  plugin) reads and writes real `social.popfeed.feed.listItem` records
     *  with fields `title`, `creativeWorkType` (e.g. "book"), and `listType`
     *  (e.g. "currently_reading_books") — see
     *  tangled.org/graham.systems/paperbnd.koplugin. That confirms the
     *  collection name and those three field names for books specifically;
     *  it does NOT confirm the exact `listType` strings Popfeed uses for a
     *  movie/TV/game backlog or watchlist, or the field name for a
     *  poster/cover image, so those two are still matched defensively
     *  (keyword/alias matching) rather than as exact known values. */
    suspend fun getPopfeedBacklog(did: String): List<PopfeedBacklogItem> {
        val resp = runCatching { api.listRecords(null, did, "social.popfeed.feed.listItem", 100, null) }.getOrNull()
        val body = resp?.takeIf { it.isSuccessful }?.body() ?: return emptyList()

        // listType follows a "{status}_{mediaTypePlural}" convention for books
        // (confirmed: "currently_reading_books") — these are the plausible
        // equivalents for a movie/TV/game/music backlog or watchlist.
        val backlogKeywords = listOf("backlog", "watchlist", "want_to", "towatch", "to_watch", "toplay", "to_play", "plan_to", "planning")
        // Broadened to include music (album/song/track) — profile tabs
        // sub-filter row (this session) needs Music as a real Backlog
        // bucket alongside Movies/TV/Games, not just the original three.
        val mediaTypeKeywords = listOf("movie", "film", "tv", "show", "game", "album", "music", "song", "track")

        val result = LinkedHashMap<String, PopfeedBacklogItem>()
        for (rec in body.records) {
            val obj = rec.value?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val creativeWorkType = firstStringField(obj, "creativeWorkType", "mediaType", "type")?.lowercase() ?: continue
            if (mediaTypeKeywords.none { creativeWorkType.contains(it) }) continue
            val listType = firstStringField(obj, "listType")?.lowercase() ?: ""
            if (backlogKeywords.none { listType.contains(it) }) continue
            val title = firstStringField(obj, "title") ?: continue
            val image = firstImageField(obj, did, "posterUrl", "coverUrl", "artworkUrl", "poster", "image", "coverImage", "thumb")
            val createdAt = firstStringField(obj, "createdAt", "updatedAt") ?: ""
            result[rec.uri] = PopfeedBacklogItem(uri = rec.uri, title = title, imageUrl = image, createdAt = createdAt, mediaCategory = creativeWorkType)
        }
        return result.values.sortedByDescending { it.createdAt }
    }

    // ── Thread / Comments ─────────────────────────────────────────────────────

    suspend fun getPostThread(token: String, uri: String): Result<List<CommentItem>> = runCatching {
        val resp = api.getPostThread("Bearer $token", uri, 10)
        val body = resp.body() ?: error("Thread ${resp.code()}")

        // Recursively converts one ThreadView node (and everything under it, up
        // to the depth=10 the API call already fetched) into a CommentItem whose
        // own `replies` list is fully populated — the reply-chain UI can then
        // page down into it locally without any further network calls.
        fun toCommentItem(view: BskyThreadView): CommentItem? {
            val post = view.post ?: return null
            val childReplies = (view.replies ?: emptyList()).mapNotNull { toCommentItem(it) }
            return CommentItem(
                id                = post.cid,
                uri               = post.uri,
                cid               = post.cid,
                authorHandle      = post.author.handle,
                authorDisplayName = post.author.displayName ?: post.author.handle,
                authorAvatarUrl   = post.author.avatar,
                body              = post.record.text ?: "",
                createdAt         = post.record.createdAt ?: "",
                likeCount         = post.likeCount ?: 0,
                isLiked           = post.viewer?.like != null,
                likeUri           = post.viewer?.like,
                replyCount        = childReplies.size.takeIf { it > 0 } ?: (post.replyCount ?: 0),
                replies           = childReplies
            )
        }

        (body.thread.replies ?: emptyList()).mapNotNull { toCommentItem(it) }
    }

    // ── Search (item 7) ──────────────────────────────────────────────────────
    // Note: Lists have no search endpoint in Bluesky's public API — only
    // per-actor app.bsky.graph.getLists — so there's no getSearchLists here;
    // see SearchOverlay.kt for how that tab is handled in the UI.

    suspend fun searchPosts(token: String, query: String, cursor: String? = null)
        : Result<Pair<List<MediaItem>, String?>> = runCatching {
        val resp = api.searchPosts("Bearer $token", query, cursor = cursor)
        val body = resp.body() ?: error("Search posts ${resp.code()}")
        // Same defensive per-item parsing as every other feed source — see
        // item 18's fix. Each result is a bare postView (no feed-item
        // envelope), so it's wrapped the same way list feeds already are.
        Pair(body.posts.flatMap { parseFeedItemSafe(BskyFeedItem(post = it)) }, body.cursor)
    }

    suspend fun searchActors(token: String, query: String, cursor: String? = null)
        : Result<Pair<List<SearchAccountResult>, String?>> = runCatching {
        val resp = api.searchActors("Bearer $token", query, cursor = cursor)
        val body = resp.body() ?: error("Search actors ${resp.code()}")
        val results = body.actors.mapNotNull { a ->
            runCatching {
                SearchAccountResult(
                    author = AuthorInfo(did = a.did, handle = a.handle, displayName = a.displayName ?: a.handle, avatarUrl = a.avatar),
                    description = a.description,
                    isFollowing = a.viewer?.following != null
                )
            }.getOrNull()
        }
        Pair(results, body.cursor)
    }

    suspend fun searchStarterPacks(token: String, query: String, cursor: String? = null)
        : Result<Pair<List<SearchStarterPackResult>, String?>> = runCatching {
        val resp = api.searchStarterPacks("Bearer $token", query, cursor = cursor)
        val body = resp.body() ?: error("Search starter packs ${resp.code()}")
        val results = body.starterPacks.mapNotNull { sp ->
            runCatching {
                SearchStarterPackResult(
                    uri = sp.uri, cid = sp.cid,
                    name = sp.record?.name ?: "Starter Pack",
                    description = sp.record?.description,
                    creator = AuthorInfo(did = sp.creator.did, handle = sp.creator.handle, displayName = sp.creator.displayName ?: sp.creator.handle, avatarUrl = sp.creator.avatar),
                    joinedCount = sp.joinedAllTimeCount
                )
            }.getOrNull()
        }
        Pair(results, body.cursor)
    }

    /** Search page's Feeds filter — see BlueskyApi.searchFeedGenerators'
     *  doc comment for why this replaces the old (never-implemented)
     *  "Lists" filter: Bluesky's public API has a feed-search endpoint but
     *  no list-search one. */
    suspend fun searchFeeds(token: String, query: String): Result<List<SearchFeedResult>> = runCatching {
        val resp = api.searchFeedGenerators("Bearer $token", query)
        val body = resp.body() ?: error("Search feeds ${resp.code()}")
        body.feeds.map { f ->
            SearchFeedResult(
                uri = f.uri, displayName = f.displayName, description = f.description,
                avatarUrl = f.avatar, creatorHandle = f.creator?.handle ?: ""
            )
        }
    }

    /** Adds a feed generator to the user's saved feeds (unpinned — shows up
     *  in their feed picker, matching what tapping "Add" on a feed does in
     *  the official app). Preferences are read-modify-write: there's no
     *  delta endpoint, so this fetches the current preferences array,
     *  appends into (or creates) the savedFeedsPrefV2 entry, and writes the
     *  whole array back. See getSavedFeeds above for the matching read-side
     *  parsing this mirrors. */
    suspend fun addSavedFeed(token: String, feedUri: String): Result<Unit> = runCatching {
        val getResp = api.getPreferences("Bearer $token")
        val body = getResp.body() ?: error("Prefs ${getResp.code()}")
        val preferences = body.preferences.toMutableList()

        val v2Index = preferences.indexOfFirst {
            it.isJsonObject && it.asJsonObject.get("\$type")?.asString?.endsWith("savedFeedsPrefV2") == true
        }

        val newItem = com.google.gson.JsonObject().apply {
            addProperty("type", "feed")
            addProperty("value", feedUri)
            addProperty("pinned", false)
            addProperty("id", java.util.UUID.randomUUID().toString())
        }

        if (v2Index >= 0) {
            val v2Obj = preferences[v2Index].asJsonObject
            val items = v2Obj.getAsJsonArray("items") ?: com.google.gson.JsonArray().also { v2Obj.add("items", it) }
            // Don't add a duplicate if it's somehow already saved.
            val alreadySaved = items.any { it.isJsonObject && it.asJsonObject.get("value")?.asString == feedUri }
            if (!alreadySaved) items.add(newItem)
        } else {
            val newPref = com.google.gson.JsonObject().apply {
                addProperty("\$type", "app.bsky.actor.defs#savedFeedsPrefV2")
                add("items", com.google.gson.JsonArray().apply { add(newItem) })
            }
            preferences.add(newPref)
        }

        val putResp = api.putPreferences("Bearer $token", BskyPreferencesResponse(preferences))
        if (!putResp.isSuccessful) error("Put prefs ${putResp.code()}: ${errorBodyText(putResp)}")
    }

    // ── Social Actions ────────────────────────────────────────────────────────

    suspend fun likePost(token: String, did: String, postUri: String, postCid: String): Result<String> =
        createRecord(token, did, "app.bsky.feed.like", mapOf(
            "\$type" to "app.bsky.feed.like",
            "subject" to mapOf("uri" to postUri, "cid" to postCid),
            "createdAt" to Instant.now().toString()
        ))

    suspend fun unlikePost(token: String, did: String, likeUri: String): Result<Unit> =
        deleteRecord(token, did, "app.bsky.feed.like", likeUri.rkey())

    suspend fun repostPost(token: String, did: String, postUri: String, postCid: String): Result<String> =
        createRecord(token, did, "app.bsky.feed.repost", mapOf(
            "\$type" to "app.bsky.feed.repost",
            "subject" to mapOf("uri" to postUri, "cid" to postCid),
            "createdAt" to Instant.now().toString()
        ))

    suspend fun unrepost(token: String, did: String, repostUri: String): Result<Unit> =
        deleteRecord(token, did, "app.bsky.feed.repost", repostUri.rkey())

    suspend fun followUser(token: String, did: String, targetDid: String): Result<String> =
        createRecord(token, did, "app.bsky.graph.follow", mapOf(
            "\$type" to "app.bsky.graph.follow",
            "subject" to targetDid,
            "createdAt" to Instant.now().toString()
        ))

    suspend fun unfollowUser(token: String, did: String, followUri: String): Result<Unit> =
        deleteRecord(token, did, "app.bsky.graph.follow", followUri.rkey())

    // ── Block (item 3) ────────────────────────────────────────────────────────

    // ── Item 4: "Show more/less like this" ──────────────────────────────────
    // Sends Bluesky's own feed-personalization interaction event
    // (app.bsky.feed.defs#requestMore / #requestLess) for one post back to
    // whichever feed generator actually supplied that post (via the post's
    // own feedContext, if the generator set one) so it can fine-tune what it
    // serves this account next.
    //
    // Bug fix (round 2): the default AppView host doesn't implement this
    // endpoint itself for third-party feeds — it 501s ("Not Implemented") —
    // because sendInteractions has to be proxied to the feed generator's own
    // service, the same way chat.bsky.* calls are proxied to the chat
    // service (see BlueskyApi's static atproto-proxy header on those).
    //
    // The first attempt at this fix derived the proxy target from the
    // authority segment of the *feed's* at:// URI, e.g.
    // `at://did:plc:alice/app.bsky.feed.generator/foo` → `did:plc:alice`.
    // That's wrong: that DID only identifies whoever *published* the
    // app.bsky.feed.generator record, which is frequently a different
    // account than whoever actually *runs* the feed generator's server.
    // The record has its own explicit `did` field for that (required by the
    // app.bsky.feed.generator lexicon) — e.g. a generator record living at
    // `at://did:plc:alice/app.bsky.feed.generator/foo` can declare
    // `did: "did:web:somefeedhost.example"`, and it's that second DID whose
    // "#bsky_fg" service endpoint actually needs to receive this request.
    // Proxying to the *publisher's* DID instead (as before) sends the
    // request to whatever service that account happens to run — usually
    // nothing that implements sendInteractions at all — which is exactly
    // what was producing the 501.
    //
    // getFeedGenerators (the same batch call already used to resolve
    // acceptsInteractions) surfaces the correct value as generatorView.did,
    // which callers now thread through as [generatorDid] — see
    // BskyFeedInfo.generatorDid and MainViewModel's feed-interaction cache.
    // When [generatorDid] is null (feed generator lookup never resolved, or
    // there isn't one — e.g. the chronological Following timeline), the
    // request just goes straight to the default AppView unproxied, same as
    // before.
    suspend fun sendFeedInteraction(token: String, postUri: String, wantMore: Boolean, feedContext: String?, generatorDid: String?): Result<Unit> = runCatching {
        val event = if (wantMore) "app.bsky.feed.defs#requestMore" else "app.bsky.feed.defs#requestLess"
        val proxy = generatorDid?.takeIf { it.startsWith("did:") }?.let { "$it#bsky_fg" }
        val resp = api.sendInteractions(
            "Bearer $token",
            proxy,
            BskySendInteractionsRequest(listOf(BskyInteraction(item = postUri, event = event, feedContext = feedContext)))
        )
        if (!resp.isSuccessful) error("sendInteractions ${resp.code()}")
    }

    // ── Item 3 (rework): does the currently-viewed feed even support this? ──
    // The AppView's own sendInteractions is proxied straight through to the
    // feed generator's own service via the atproto-proxy header above — it's
    // the generator's service, not the AppView, that has to actually
    // implement handling for it. Most don't; a feed generator has to
    // explicitly opt in by setting `acceptsInteractions: true` on its own
    // app.bsky.feed.generator record for that to be safe to try (otherwise
    // the proxied request typically comes back 501 from the generator's own
    // service, exactly the failure this was hitting). This looks up that
    // declaration for one specific feed via the same getFeedGenerators
    // batch endpoint getSavedFeeds already uses, so MainViewModel can gate
    // the "Show more/less like this" menu items on it per-feed instead of
    // just on "is this a feed generator at all".
    suspend fun getFeedGeneratorInfo(token: String, feedUri: String): Result<BskyFeedGeneratorView> = runCatching {
        val resp = api.getFeedGenerators("Bearer $token", listOf(feedUri))
        if (!resp.isSuccessful) error("getFeedGenerators ${resp.code()}")
        val body = resp.body() ?: error("getFeedGenerators: empty body")
        body.feeds.firstOrNull { it.uri == feedUri } ?: error("Feed generator not found: $feedUri")
    }

    suspend fun blockUser(token: String, did: String, targetDid: String): Result<String> =
        createRecord(token, did, "app.bsky.graph.block", mapOf(
            "\$type" to "app.bsky.graph.block",
            "subject" to targetDid,
            "createdAt" to Instant.now().toString()
        ))

    suspend fun unblockUser(token: String, did: String, blockUri: String): Result<Unit> =
        deleteRecord(token, did, "app.bsky.graph.block", blockUri.rkey())

    // ── Quote repost (item 5) ────────────────────────────────────────────────

    suspend fun quoteRepost(
        token: String, did: String, text: String,
        quotedUri: String, quotedCid: String
    ): Result<String> {
        val record = mutableMapOf<String, Any>(
            "\$type" to "app.bsky.feed.post",
            "text" to text,
            "embed" to mapOf(
                "\$type" to "app.bsky.embed.record",
                "record" to mapOf("uri" to quotedUri, "cid" to quotedCid)
            ),
            "createdAt" to Instant.now().toString()
        )
        buildHashtagFacets(text).takeIf { it.isNotEmpty() }?.let { record["facets"] = it }
        return createRecord(token, did, "app.bsky.feed.post", record)
    }

    /** Builds byte-offset facets so #hashtags render as tappable tags (item 5). */
    private fun buildHashtagFacets(text: String): List<Map<String, Any>> {
        val regex = Regex("(?<=^|[\\s])#([a-zA-Z0-9_]+)")
        return regex.findAll(text).map { m ->
            val tag = m.groupValues[1]
            val byteStart = text.substring(0, m.range.first).toByteArray(Charsets.UTF_8).size
            val byteEnd   = text.substring(0, m.range.last + 1).toByteArray(Charsets.UTF_8).size
            mapOf(
                "index" to mapOf("byteStart" to byteStart, "byteEnd" to byteEnd),
                "features" to listOf(mapOf("\$type" to "app.bsky.richtext.facet#tag", "tag" to tag))
            )
        }.toList()
    }

    // ── DMs / chat (item 6, item 7) ──────────────────────────────────────────

    /** Bug fix: retries a single paginated page once before giving up on it —
     *  used by fetchAllFollows/fetchAllFollowers below so one transient
     *  network hiccup on, say, page 6 of 12 doesn't have to take down the
     *  whole fetch. */
    private suspend fun <T> retryOnce(block: suspend () -> T): T =
        runCatching { block() }.getOrElse { block() }

    /** All accounts that follow us AND we follow back — the set Bluesky allows DMs with by default.
     *  Bug fix: a single failed page used to throw and discard the entire
     *  accumulated list (even if e.g. 5 of 6 pages had already succeeded),
     *  which is what caused the Send Post popup and the Hub's Friends
     *  section to intermittently show only people with an existing DM
     *  thread instead of every mutual — one bad page on a paginated fetch
     *  silently fell back to a much smaller, convo-only list for the rest of
     *  that session (this only runs once per launch). Both loops now retry a
     *  failed page once, and if it still fails, stop and return whatever was
     *  already gathered instead of throwing away the whole result. */
    suspend fun getMutuals(token: String, myDid: String): Result<List<AuthorInfo>> = runCatching {
        suspend fun fetchAllFollows(): Map<String, BskyProfileBasic> {
            val out = LinkedHashMap<String, BskyProfileBasic>()
            var cursor: String? = null
            do {
                val resp = runCatching { retryOnce { api.getFollows("Bearer $token", myDid, 100, cursor) } }.getOrNull()
                if (resp == null || !resp.isSuccessful) break
                val body = resp.body() ?: break
                body.follows.forEach { out[it.did] = it }
                cursor = body.cursor
            } while (!cursor.isNullOrBlank())
            return out
        }
        suspend fun fetchAllFollowers(): Set<String> {
            val out = HashSet<String>()
            var cursor: String? = null
            do {
                val resp = runCatching { retryOnce { api.getFollowers("Bearer $token", myDid, 100, cursor) } }.getOrNull()
                if (resp == null || !resp.isSuccessful) break
                val body = resp.body() ?: break
                body.followers.forEach { out.add(it.did) }
                cursor = body.cursor
            } while (!cursor.isNullOrBlank())
            return out
        }
        val (follows, followerDids) = coroutineScope {
            val f1 = async { fetchAllFollows() }
            val f2 = async { fetchAllFollowers() }
            f1.await() to f2.await()
        }
        // Item 12 bugfix: never surface the current user's own account as a DM
        // recipient (can happen via odd follow-graph edge cases like a stale
        // self-follow record).
        follows.values.filter { followerDids.contains(it.did) && it.did != myDid }.map {
            AuthorInfo(
                did = it.did, handle = it.handle,
                displayName = it.displayName?.takeIf { n -> n.isNotBlank() } ?: it.handle,
                avatarUrl = it.avatar
            )
        }
    }

    /** Every account the current user is currently blocking. Neither DMs nor the
     *  From Friends feed should ever surface a blocked account. */
    suspend fun getBlockedDids(token: String): Result<Set<String>> = runCatching {
        val out = HashSet<String>()
        var cursor: String? = null
        do {
            val resp = api.getBlocks("Bearer $token", 100, cursor)
            if (!resp.isSuccessful) error("getBlocks ${resp.code()}: ${resp.message()}")
            val body = resp.body() ?: break
            body.blocks.forEach { out.add(it.did) }
            cursor = body.cursor
        } while (!cursor.isNullOrBlank())
        out
    }

    /** Full DM recipient list: every mutual (the set Bluesky allows DMs with by
     *  default) merged with existing conversations for sort/preview info. Falls
     *  back gracefully — if the chat service call fails, mutuals still populate
     *  the picker; if mutuals fail, existing convos still populate it. Blocked
     *  accounts are excluded even if an old conversation with them still exists. */
    suspend fun loadDmRecipients(token: String, myDid: String): Result<List<DmConversation>> = runCatching {
        val (convosResult, mutualsResult, blockedDids) = coroutineScope {
            val c = async { listConvos(token, myDid) }
            val m = async { getMutuals(token, myDid) }
            val b = async { getBlockedDids(token).getOrDefault(emptySet()) }
            Triple(c.await(), m.await(), b.await())
        }

        if (convosResult.isFailure && mutualsResult.isFailure) {
            throw mutualsResult.exceptionOrNull() ?: convosResult.exceptionOrNull() ?: Exception("Failed to load conversations")
        }

        val convos = convosResult.getOrDefault(emptyList()).filter { it.member.did !in blockedDids }
        val byDid = LinkedHashMap<String, DmConversation>()
        convos.forEach { byDid[it.member.did] = it }

        mutualsResult.getOrDefault(emptyList()).forEach { mutual ->
            if (mutual.did !in blockedDids && !byDid.containsKey(mutual.did)) {
                // Mutual we can message but haven't started a conversation with yet —
                // convoId is resolved lazily (fetch-or-create) at send time.
                byDid[mutual.did] = DmConversation(convoId = "", member = mutual, lastSentByUsAt = "", lastActivityAt = "")
            }
        }

        byDid.values.sortedByDescending { it.lastActivityAt.ifBlank { it.lastSentByUsAt } }
    }

    /** Existing conversations only, sorted by most recent interaction —
     *  whichever of us sent the last message, not just ones we sent
     *  (falling back to lastSentByUsAt only if lastActivityAt is somehow
     *  unavailable). Used by [loadDmRecipients] and to know which convos
     *  actually have history for the "From Friends" scan.
     *  Bug fix: this used to sort by "most recent message WE sent" first,
     *  falling back to overall activity only for convos we'd never sent
     *  anything in — which biased the order toward people you message a lot
     *  rather than genuine recency, and wasn't what "most recent
     *  interaction" should mean. */
    suspend fun listConvos(token: String, myDid: String): Result<List<DmConversation>> = runCatching {
        ensureChatApi(myDid)
        val resp = chatApi.listConvos("Bearer $token")
        val body = resp.body() ?: error("ListConvos ${resp.code()}: ${errorBodyText(resp)}")
        coroutineScope {
            body.convos.map { convo ->
                async {
                    val other = convo.members.firstOrNull { it.did != myDid }
                    val author = AuthorInfo(
                        did = other?.did ?: convo.id,
                        handle = other?.handle ?: "unknown",
                        displayName = other?.displayName?.takeIf { it.isNotBlank() } ?: other?.handle ?: "Unknown",
                        avatarUrl = other?.avatar
                    )
                    var lastSentByUs = if (convo.lastMessage?.sender?.did == myDid) convo.lastMessage.sentAt else ""
                    if (lastSentByUs.isBlank()) {
                        // Peek at recent history to find the last message we sent here
                        runCatching { chatApi.getMessages("Bearer $token", convo.id, 30) }
                            .getOrNull()?.takeIf { it.isSuccessful }?.body()
                            ?.messages?.firstOrNull { it.sender?.did == myDid }
                            ?.let { lastSentByUs = it.sentAt }
                    }
                    DmConversation(
                        convoId = convo.id,
                        member = author,
                        lastSentByUsAt = lastSentByUs,
                        lastActivityAt = convo.lastMessage?.sentAt ?: ""
                    )
                }
            }.awaitAll()
        }.sortedByDescending { it.lastActivityAt.ifBlank { it.lastSentByUsAt } }
    }

    /** Real-time-ish DM sync (see MainViewModel's DM polling
     *  loop): chat.bsky.convo.getLog is a delta/cursor endpoint — it returns
     *  only what changed across ALL of the user's conversations since the
     *  given cursor, so a short poll loop against this is cheap (one small
     *  request) instead of re-fetching every conversation's full message
     *  list on a timer. There's no public chat firehose/WebSocket the way
     *  there is for repo commits (Jetstream), so polling this delta endpoint
     *  is the standard approach for "real-time" DMs in an unofficial client —
     *  see the architecture note's §2 Catch-Up/Delta Fetching. Passing
     *  cursor = null returns recent history rather than everything, which is
     *  fine for the poll loop's first call (it just seeds the cursor). */
    suspend fun getConvoLog(token: String, myDid: String, cursor: String? = null)
        : Result<Pair<List<BskyConvoLogEntry>, String?>> = runCatching {
        ensureChatApi(myDid)
        val resp = chatApi.getConvoLog("Bearer $token", cursor)
        val body = resp.body() ?: error("GetConvoLog ${resp.code()}: ${errorBodyText(resp)}")
        Pair(body.logs, body.cursor)
    }

    suspend fun getOrCreateConvo(token: String, myDid: String, memberDids: List<String>): Result<String> = runCatching {
        ensureChatApi(myDid)
        val resp = chatApi.getConvoForMembers("Bearer $token", memberDids)
        resp.body()?.convo?.id ?: error("GetConvo ${resp.code()}: ${errorBodyText(resp)}")
    }

    /** Full linear message history for one conversation — powers the DMs inbox
     *  thread view. Bluesky returns messages newest-first; reversed here so
     *  callers get them in normal reading order (oldest at index 0). */
    suspend fun getConvoMessages(token: String, myDid: String, convoId: String, cursor: String? = null)
        : Result<Pair<List<BskyMessageView>, String?>> = runCatching {
        ensureChatApi(myDid)
        val resp = chatApi.getMessages("Bearer $token", convoId, 50, cursor)
        val body = resp.body() ?: error("GetMessages ${resp.code()}: ${errorBodyText(resp)}")
        Pair(body.messages.reversed(), body.cursor)
    }

    /** Sends [text], optionally with an embedded post (for sharing media via DM). */
    suspend fun sendMessage(
        token: String, myDid: String, convoId: String, text: String,
        embedPostUri: String? = null, embedPostCid: String? = null
    ): Result<Unit> = runCatching {
        ensureChatApi(myDid)
        val facets = buildHashtagFacets(text).takeIf { it.isNotEmpty() }
        val embed  = if (embedPostUri != null && embedPostCid != null) mapOf(
            "\$type" to "app.bsky.embed.record",
            "record" to mapOf("uri" to embedPostUri, "cid" to embedPostCid)
        ) else null
        val resp = chatApi.sendMessage("Bearer $token", BskySendMessageRequest(convoId, BskySendMessageInput(text, facets, embed)))
        if (!resp.isSuccessful) error("SendMessage ${resp.code()}: ${errorBodyText(resp)}")
    }

    private fun errorBodyText(resp: retrofit2.Response<*>): String =
        runCatching { resp.errorBody()?.string() }.getOrNull()?.takeIf { it.isNotBlank() } ?: resp.message()

    /** Scans recent history in each convo for posts friends have shared with us,
     *  then hydrates the underlying posts — powers the "From Friends" feed.
     *  Paginates back through each convo's history (not just the newest page)
     *  since a shared post could be from a while ago. */
    // Bug fix/item 12 follow-up: `includeSelfSent` lets the DM-thread "shared
    // posts" feed include posts *I* shared too, not just ones the other
    // person shared with me — the "From Friends" feed (default false) still
    // only wants what friends shared with you, unchanged.
    suspend fun getFriendsSharedPosts(token: String, myDid: String, convos: List<DmConversation>, includeSelfSent: Boolean = false): Result<List<MediaItem>> = runCatching {
        ensureChatApi(myDid)
        // Never surface posts shared by an account the user has blocked.
        val blockedDids = getBlockedDids(token).getOrDefault(emptySet())
        val convos = convos.filter { it.member.did !in blockedDids }
        data class Raw(val uri: String, val cid: String, val text: String, val sentAt: String, val author: AuthorInfo, val convoId: String)
        val raw = java.util.Collections.synchronizedList(mutableListOf<Raw>())
        coroutineScope {
            convos.map { convo ->
                async {
                    var cursor: String? = null
                    var pages = 0
                    do {
                        val body = runCatching { chatApi.getMessages("Bearer $token", convo.convoId, 50, cursor) }
                            .getOrNull()?.takeIf { it.isSuccessful }?.body()
                        body?.messages?.forEach { msg ->
                            val senderDid = msg.sender?.did
                            if (senderDid != null && (senderDid != myDid || includeSelfSent)) {
                                val embedObj  = msg.embed?.takeIf { it.isJsonObject }?.asJsonObject
                                val recordObj = embedObj?.getAsJsonObject("record")
                                val uri = recordObj?.get("uri")?.takeIf { it.isJsonPrimitive }?.asString
                                val cid = recordObj?.get("cid")?.takeIf { it.isJsonPrimitive }?.asString
                                // Shown in the feed as "shared by @x" context
                                // either way, using the other side of the DM
                                // (the conversation's member) as that author —
                                // relevant regardless of who actually sent it.
                                if (uri != null && cid != null) raw.add(Raw(uri, cid, msg.text, msg.sentAt, convo.member, convo.convoId))
                            }
                        }
                        cursor = body?.cursor
                        pages++
                        // Cap at 10 pages (~500 messages) per convo so this can't run forever
                        // on a very long-lived conversation, while still reaching well back
                        // in time for posts shared a while ago.
                    } while (!cursor.isNullOrBlank() && pages < 10)
                }
            }.awaitAll()
        }
        if (raw.isEmpty()) return@runCatching emptyList()

        val hydrated = mutableMapOf<String, BskyPost>()
        raw.map { it.uri }.distinct().chunked(25).forEach { batch ->
            runCatching { api.getPosts("Bearer $token", batch) }.getOrNull()?.takeIf { it.isSuccessful }?.body()
                ?.posts?.forEach { hydrated[it.uri] = it }
        }

        raw.sortedByDescending { it.sentAt }.flatMap { r ->
            val post = hydrated[r.uri] ?: return@flatMap emptyList<MediaItem>()
            parseFeedItemSafe(BskyFeedItem(post = post)).map {
                it.copy(sentByAuthor = r.author, sentByMessage = r.text, sentByConvoId = r.convoId)
            }
        }
    }

    suspend fun replyToPost(
        token: String, did: String,
        rootUri: String, rootCid: String,
        parentUri: String, parentCid: String,
        text: String
    ): Result<String> = createRecord(token, did, "app.bsky.feed.post", mapOf(
        "\$type" to "app.bsky.feed.post",
        "text" to text,
        "reply" to mapOf(
            "root"   to mapOf("uri" to rootUri,   "cid" to rootCid),
            "parent" to mapOf("uri" to parentUri, "cid" to parentCid)
        ),
        "createdAt" to Instant.now().toString()
    ))

    suspend fun getUserLists(token: String, did: String): Result<List<BskyList>> = runCatching {
        val resp = api.getLists("Bearer $token", did, 100)
        val body = resp.body() ?: error("Lists ${resp.code()}: ${resp.message()}")
        body.lists
    }

    /** Returns the user's starter packs. To add a member, call addToList() using
     *  starterPack.record.list as the listUri — that's the underlying list. */
    suspend fun getUserStarterPacks(token: String, did: String): Result<List<BskyStarterPackView>> = runCatching {
        val resp = api.getActorStarterPacks("Bearer $token", did, 100)
        val body = resp.body() ?: error("StarterPacks ${resp.code()}: ${resp.message()}")
        body.starterPacks
    }

    suspend fun addToList(token: String, repoDid: String, listUri: String, targetDid: String): Result<String> =
        createRecord(token, repoDid, "app.bsky.graph.listitem", mapOf(
            "\$type" to "app.bsky.graph.listitem",
            "subject" to targetDid,
            "list" to listUri,
            "createdAt" to Instant.now().toString()
        ))

    suspend fun likeComment(token: String, did: String, commentUri: String, commentCid: String): Result<String> =
        likePost(token, did, commentUri, commentCid)

    suspend fun unlikeComment(token: String, did: String, likeUri: String): Result<Unit> =
        unlikePost(token, did, likeUri)

    // ── Bookmarks / Saves ─────────────────────────────────────────────────────

    suspend fun addBookmark(token: String, uri: String, cid: String): Result<Unit> = runCatching {
        val resp = api.createBookmark("Bearer $token", mapOf("uri" to uri, "cid" to cid))
        if (!resp.isSuccessful) error("Bookmark ${resp.code()}: ${errorBodyText(resp)}")
    }

    suspend fun removeBookmark(token: String, uri: String): Result<Unit> = runCatching {
        val resp = api.deleteBookmark("Bearer $token", mapOf("uri" to uri))
        if (!resp.isSuccessful) error("Unbookmark ${resp.code()}: ${errorBodyText(resp)}")
    }

    suspend fun getBookmarkedPosts(token: String, cursor: String? = null): Result<Pair<List<MediaItem>, String?>> = runCatching {
        val resp = api.getBookmarks("Bearer $token", 50, cursor)
        val body = resp.body() ?: error("Bookmarks ${resp.code()}: ${errorBodyText(resp)}")
        val posts = body.bookmarks.mapNotNull { it.item }
        // Gson bypasses Kotlin's constructor null-checks when a JSON field is
        // missing (e.g. a bookmarked post whose author was deleted/suspended),
        // so `post.author` can be null at runtime despite its non-null type.
        // That previously crashed the whole Saves screen with an NPE on
        // author.getDid(); skip just the malformed entry instead.
        val items = posts.flatMap { post ->
            runCatching {
                parseFeedItem(BskyFeedItem(post = post)).map { media -> media.copy(isBookmarked = true) }
            }.getOrElse { emptyList() }
        }
        Pair(items, body.cursor)
    }

    // ── Compose Post (upload flow) ──────────────────────────────────────────
    // See ComposePostScreen.kt's header comment for the overall plan this
    // implements. Images/thread/textshot are wired end to end; video posts
    // (uploadVideoBlob below) follow Bluesky's documented service-auth flow
    // but haven't been exercised against the live API yet, and the custom-
    // thumbnail-as-first-frame trick RaccNet Legacy does with ffmpeg isn't
    // ported yet (see ComposePostScreen.kt) — a video post today uploads and
    // publishes fine, it just always gets Bluesky's own auto-generated
    // thumbnail (frame 0 of the real video) rather than a custom one.

    /** Reads an image at [uri], downscaling/re-encoding as needed to satisfy
     *  Bluesky's current app.bsky.embed.images limits (2,000,000 byte blob
     *  cap, images rendered at up to 4000×4000 — see the class doc comment
     *  in ComposePostScreen.kt for where these numbers come from), then
     *  uploads it via com.atproto.repo.uploadBlob. */
    suspend fun uploadImageBlob(
        token: String, context: android.content.Context, uri: android.net.Uri
    ): Result<BskyBlob> = withContext(Dispatchers.IO) {
        runCatching {
            val (bytes, mimeType) = prepareImageForUpload(context, uri)
            val body = bytes.toRequestBody(mimeType.toMediaType())
            val resp = api.uploadBlob("Bearer $token", mimeType, body)
            resp.body()?.blob ?: error("uploadBlob ${resp.code()}: ${resp.errorBody()?.string()}")
        }
    }

    private val IMAGE_MAX_BLOB_BYTES = 2_000_000
    private val IMAGE_MAX_DIMENSION = 4000

    private fun prepareImageForUpload(context: android.content.Context, uri: android.net.Uri): Pair<ByteArray, String> {
        val resolver = context.contentResolver
        val original = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read image")
        val declaredType = resolver.getType(uri)

        // Fast path: already within both limits and a format Bluesky
        // accepts as-is (jpeg/png/webp/gif) — upload the original bytes
        // untouched rather than a re-encoded copy.
        if (original.size <= IMAGE_MAX_BLOB_BYTES && declaredType != null &&
            (declaredType == "image/jpeg" || declaredType == "image/png" || declaredType == "image/webp" || declaredType == "image/gif")) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
            if (bounds.outWidth <= IMAGE_MAX_DIMENSION && bounds.outHeight <= IMAGE_MAX_DIMENSION) {
                return original to declaredType
            }
        }

        var bitmap = android.graphics.BitmapFactory.decodeByteArray(original, 0, original.size)
            ?: error("Couldn't decode image")
        if (bitmap.width > IMAGE_MAX_DIMENSION || bitmap.height > IMAGE_MAX_DIMENSION) {
            val scale = IMAGE_MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
            bitmap = android.graphics.Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
        }
        var quality = 92
        var out = java.io.ByteArrayOutputStream().apply { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, this) }
        while (out.size() > IMAGE_MAX_BLOB_BYTES && quality > 30) {
            quality -= 12
            out = java.io.ByteArrayOutputStream().apply { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, this) }
        }
        return out.toByteArray() to "image/jpeg"
    }

    /** Builds an app.bsky.embed.images embed (≤4 images) or an
     *  app.bsky.embed.gallery embed (5-10 images) depending on count — the
     *  two are different lexicons; images tops out at 4 by schema, gallery
     *  covers 5-10 (soft-capped in authoring UIs; schema ceiling is 20). */
    private fun buildImagesEmbed(blobs: List<BskyBlob>): Map<String, Any> {
        val imageObjs = blobs.map { blob -> mapOf("image" to blob, "alt" to "") }
        return if (blobs.size <= 4) {
            mapOf("\$type" to "app.bsky.embed.images", "images" to imageObjs)
        } else {
            mapOf(
                "\$type" to "app.bsky.embed.gallery",
                "items" to blobs.map { blob -> mapOf("\$type" to "app.bsky.embed.gallery#image", "image" to blob, "alt" to "") }
            )
        }
    }

    /** Creates a single post — optionally with up to 10 images attached —
     *  and optionally as a reply (used by [createThread] below for the
     *  self-reply chain). Returns the new post's (uri, cid). */
    suspend fun createPost(
        token: String, did: String, text: String,
        imageBlobs: List<BskyBlob> = emptyList(),
        reply: BskyReplyRef? = null
    ): Result<BskyRef> = withContext(Dispatchers.IO) {
        runCatching {
        val record = mutableMapOf<String, Any>(
            "\$type" to "app.bsky.feed.post",
            "text" to text,
            "createdAt" to Instant.now().toString()
        )
        if (imageBlobs.isNotEmpty()) record["embed"] = buildImagesEmbed(imageBlobs)
        if (reply != null) record["reply"] = mapOf(
            "root" to mapOf("uri" to reply.root.uri, "cid" to reply.root.cid),
            "parent" to mapOf("uri" to reply.parent.uri, "cid" to reply.parent.cid)
        )
        buildHashtagFacets(text).takeIf { it.isNotEmpty() }?.let { record["facets"] = it }

        val resp = api.createRecord("Bearer $token", BskyCreateRecordRequest(did, "app.bsky.feed.post", record))
        val body = resp.body() ?: error("createPost ${resp.code()}: ${resp.errorBody()?.string()}")
        BskyRef(body.uri, body.cid)
    }
    }

    /** Posts a self-thread: each entry's images are uploaded and attached to
     *  that entry, and each post after the first replies to the previous one
     *  (root always the first post) — a standard Bluesky self-thread. Stops
     *  and returns whatever succeeded so far if any step fails, since partial
     *  threads still need to be visible to the caller/person rather than
     *  silently vanishing. */
    suspend fun createThread(
        token: String, did: String, context: android.content.Context,
        posts: List<ThreadPostToSend>
    ): Result<List<BskyRef>> = withContext(Dispatchers.IO) {
        runCatching {
        val created = mutableListOf<BskyRef>()
        var root: BskyRef? = null
        for (post in posts) {
            val blobs = post.images.map { uri ->
                uploadImageBlob(token, context, uri).getOrElse { throw it }
            }
            val reply = root?.let { r -> BskyReplyRef(root = r, parent = created.last()) }
            val ref = createPost(token, did, post.text, blobs, reply).getOrElse { throw it }
            if (root == null) root = ref
            created += ref
        }
        created
    }
    }

    /** One post's worth of content + already-resolved local media, ready to
     *  send — the network-layer counterpart of ComposePostScreen's
     *  ThreadPostDraft (which carries raw content:// Uris instead). */
    data class ThreadPostToSend(val text: String, val images: List<android.net.Uri> = emptyList())

    /** Renders [text] to a transparent-background/white-text square PNG
     *  (same shrink-to-fit layout ComposePostScreen's live preview uses),
     *  uploads it, and posts it as a single-image post — the network side of
     *  Textshot mode. */
    suspend fun createTextshotPost(token: String, did: String, textshotBitmap: android.graphics.Bitmap): Result<BskyRef> =
        withContext(Dispatchers.IO) {
            runCatching {
                val out = java.io.ByteArrayOutputStream()
                textshotBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                val bytes = out.toByteArray()
                val body = bytes.toRequestBody("image/png".toMediaType())
                val resp = api.uploadBlob("Bearer $token", "image/png", body)
                val blob = resp.body()?.blob ?: error("uploadBlob ${resp.code()}: ${resp.errorBody()?.string()}")
                createPost(token, did, "", listOf(blob)).getOrElse { throw it }
            }
        }

    /** Uploads a video via video.bsky.app (a separate service from the
     *  user's own PDS, per Bluesky's documented flow — see the class doc
     *  comment above), polling until it's encoded, then posts it. `did` and
     *  `pdsHost` identify the account/PDS the service-auth token is minted
     *  for. */
    suspend fun createVideoPost(
        token: String, did: String, context: android.content.Context,
        videoUri: android.net.Uri, thumbnailUri: android.net.Uri? = null,
        title: String, description: String
    ): Result<BskyRef> = withContext(Dispatchers.IO) {
        runCatching {
            val pdsHost = currentPdsHost()
            // See VideoThumbnailStitcher's header comment — this is the
            // only way a custom thumbnail actually shows up on Bluesky,
            // since the platform always shows frame 0 as the thumbnail.
            // Falls back to the original video untouched if no thumbnail
            // was picked, or splicing fails for any reason (a missing
            // thumbnail beats a failed post).
            val uploadUri = if (thumbnailUri != null) {
                runCatching { com.mediaviewer.util.VideoThumbnailStitcher.stitch(context, videoUri, thumbnailUri) }
                    .getOrDefault(videoUri)
            } else videoUri
            val authResp = api.getServiceAuth(
                "Bearer $token",
                aud = "did:web:$pdsHost",
                lxm = "com.atproto.repo.uploadBlob",
                exp = (System.currentTimeMillis() / 1000) + 60 * 30
            )
            val serviceToken = authResp.body()?.token ?: error("getServiceAuth ${authResp.code()}")

            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uploadUri)?.use { it.readBytes() } ?: error("Couldn't read video")
            // Transformer always re-muxes to mp4, so once stitching has
            // happened the original content:// URI's declared type (mov,
            // etc.) no longer applies to the bytes we're actually sending.
            val mimeType = if (uploadUri != videoUri) "video/mp4" else (resolver.getType(videoUri) ?: "video/mp4")
            val fileName = "raccnet-${System.currentTimeMillis()}.mp4"
            val body = bytes.toRequestBody(mimeType.toMediaType())

            var jobStatus: BskyJobStatus? = videoApi.uploadVideo("Bearer $serviceToken", mimeType, did, fileName, body).body()
                ?: error("uploadVideo failed")
            while (jobStatus?.state != "JOB_STATE_COMPLETED" && jobStatus?.blob == null) {
                if (jobStatus?.state == "JOB_STATE_FAILED") error("Video processing failed: ${jobStatus?.error ?: jobStatus?.message}")
                delay(2000)
                jobStatus = videoApi.getJobStatus(jobStatus?.jobId ?: error("no jobId")).body()?.jobStatus
            }
            val blob = jobStatus?.blob ?: error("Video job completed with no blob")

            val text = if (description.isBlank()) title else "$title\n\n$description"
            val record = mutableMapOf<String, Any>(
                "\$type" to "app.bsky.feed.post",
                "text" to text,
                "embed" to mapOf("\$type" to "app.bsky.embed.video", "video" to blob),
                "createdAt" to Instant.now().toString()
            )
            val resp = api.createRecord("Bearer $token", BskyCreateRecordRequest(did, "app.bsky.feed.post", record))
            val respBody = resp.body() ?: error("createPost ${resp.code()}")
            BskyRef(respBody.uri, respBody.cid)
        }
    }

    private val videoApi: com.mediaviewer.network.BlueskyVideoApi by lazy { NetworkClient.buildBlueskyVideoApi() }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun createRecord(token: String, did: String, collection: String, record: Map<String, Any>): Result<String> = runCatching {
        val resp = api.createRecord("Bearer $token", BskyCreateRecordRequest(did, collection, record))
        resp.body()?.uri ?: error("CreateRecord ${resp.code()}")
    }

    private suspend fun deleteRecord(token: String, did: String, collection: String, rkey: String): Result<Unit> = runCatching {
        val resp = api.deleteRecord("Bearer $token", BskyDeleteRecordRequest(did, collection, rkey))
        if (!resp.isSuccessful) error("DeleteRecord ${resp.code()}")
    }

    private val embedGson = com.google.gson.Gson()

    /** Item 12: parses a DM message's raw embed JSON into a lightweight
     *  preview the DM thread can render inline. Only app.bsky.embed.record
     *  (a shared/quoted post — the same shape quote-reposts use elsewhere in
     *  the app) is rendered; other embed kinds (e.g. a shared feed or list)
     *  return null and the bubble just shows plain text. */
    fun parseMessageEmbed(embed: com.google.gson.JsonElement?): DmEmbeddedPost? {
        if (embed == null || embed.isJsonNull) return null
        return runCatching {
            val parsed = embedGson.fromJson(embed, BskyEmbed::class.java) ?: return@runCatching null
            if (!parsed.type.contains("record")) return@runCatching null
            val record = parsed.record?.record ?: parsed.record ?: return@runCatching null
            val rawAuthor = record.author ?: return@runCatching null
            val uri = record.uri ?: return@runCatching null
            val quotedEmbed = record.embeds?.firstOrNull()
            // Bug fix: same gallery/carousel handling as parseFeedItem below —
            // a quoted/embedded carousel post's images live under `items`,
            // not `images`, on an app.bsky.embed.gallery#view embed, and
            // each item's thumbnail comes back under "thumbnail" instead of
            // "thumb" (see BskyImageView's comment in Models.kt for the
            // confirmed root cause).
            val quotedImage = quotedEmbed?.takeIf { it.type.contains("images") || it.type.contains("gallery") }
                ?.let { it.images?.firstOrNull() ?: it.items?.firstOrNull() }
            val quotedImageThumb = quotedImage?.let { img ->
                val t = img.thumb
                if (!t.isNullOrBlank()) t else img.thumbnail?.takeIf { it.isNotBlank() } ?: img.fullsize
            }
            val quotedVideo = quotedEmbed?.takeIf { it.type.contains("video") }
            DmEmbeddedPost(
                postUri = uri,
                postCid = record.cid ?: "",
                author = AuthorInfo(
                    did = rawAuthor.did, handle = rawAuthor.handle,
                    displayName = rawAuthor.displayName ?: rawAuthor.handle,
                    avatarUrl = rawAuthor.avatar
                ),
                text = record.value?.text ?: "",
                thumbUrl = quotedVideo?.thumbnail ?: quotedImageThumb,
                isVideo = quotedVideo != null
            )
        }.getOrNull()
    }

    private fun String.rkey() = this.substringAfterLast('/')

    // Item 18 fix: parseFeedItem() can throw for a single malformed post —
    // most likely a "non-null" String field (e.g. BskyImageView.thumb/
    // fullsize) that Gson actually left null at runtime because the JSON
    // was missing it (Gson bypasses the constructor via Unsafe, so Kotlin's
    // non-null guarantee doesn't actually hold — see item 13's fix for the
    // same class of bug). Every call site below used to run parseFeedItem
    // directly inside .flatMap with no per-item isolation, so one bad post
    // anywhere in a page of ~50 would throw, propagate up through the
    // page's own outer runCatching, and silently fail the ENTIRE page —
    // not just the one bad post. New multi-image (5-10 image) posts are
    // exactly the kind of post most likely to hit an edge case like this,
    // which is why they were disappearing from feeds entirely instead of
    // just being capped/mis-rendered. This wraps each post individually so
    // one bad post is skipped instead of taking its whole page down with it.
    private fun parseFeedItemSafe(item: BskyFeedItem): List<MediaItem> =
        runCatching { parseFeedItem(item) }.getOrDefault(emptyList())

    private fun parseFeedItem(item: BskyFeedItem): List<MediaItem> {
        val post   = item.post
        val author = AuthorInfo(
            did          = post.author.did,
            handle       = post.author.handle,
            displayName  = post.author.displayName ?: post.author.handle,
            avatarUrl    = post.author.avatar,
            followingUri = post.author.viewer?.following,
            isFollowing  = post.author.viewer?.following != null
        )
        val text = post.record.text ?: ""

        // A text-only post (no embed at all, or an embed type we don't render
        // as media, e.g. a link card or a bare quote-post) still deserves a
        // spot in the feed — Big Update #3 — as long as it actually has text.
        fun textOnlyItem(): List<MediaItem> =
            if (text.isBlank()) emptyList() else listOf(
                MediaItem(
                    id = post.cid, mediaUrl = "", thumbUrl = "", isVideo = false,
                    postUri = post.uri, postCid = post.cid, feedContext = item.feedContext,
                    author = author, likeUri = post.viewer?.like, repostUri = post.viewer?.repost,
                    isLiked = post.viewer?.like != null, isReposted = post.viewer?.repost != null,
                    likeCount = post.likeCount ?: 0, replyCount = post.replyCount ?: 0,
                    repostCount = post.repostCount ?: 0, text = text
                )
            )

        return when (val embed = post.embed) {
            null -> textOnlyItem()
            else -> when {
                // Bug fix: Bluesky's 5-10 image "photo carousel" posts (added
                // mid-2026) were falling through to textOnlyItem() below and
                // rendering as text-only, image-less posts. This block used
                // to gate purely on `embed.type.contains("images")`, which
                // assumed every image embed reports a $type string containing
                // that substring. If the carousel's hydrated view ever comes
                // back under a different/new $type while still populating the
                // same `images: [ViewImage]` shape the classic 1-4 image
                // embed uses (the actual on-the-wire failure mode wasn't
                // directly observable without a live carousel post to trace),
                // Bug fix (root cause CONFIRMED this session against a real
                // live app.bsky.feed.getPostThread response for an actual
                // 5-10 image post — see BskyImageView's comment in
                // Models.kt): these posts use a real, distinct
                // "app.bsky.embed.gallery#view" embed (not a soft-raised
                // app.bsky.embed.images#view as previously guessed), with
                // photos under `items` (not `images`) — so the detection
                // logic below (checking type/images/items) was already
                // correct. The actual bug was one level deeper: each
                // gallery photo's thumbnail URL comes back under a DIFFERENT
                // JSON key — "thumbnail" — than the "thumb" key
                // app.bsky.embed.images#view uses for the exact same thing.
                // BskyImageView.thumb is a non-null Kotlin String, so Gson
                // silently left it null for every gallery photo (this app's
                // documented "Gson bypasses constructors" bug class), which
                // the crash-safety filter added earlier this session (to
                // stop that null from throwing when passed into MediaItem's
                // constructor) correctly treated as "unusable" — for EVERY
                // photo in EVERY gallery post, since none of them ever have
                // `thumb` populated. That's why `images` always ended up
                // empty after filtering and the post fell through to
                // text-only: not a crash, not a detection miss, just every
                // single photo failing the same too-strict check. resolvedThumb()
                // below now checks both keys (falling back to the fullsize
                // URL itself as a last resort — still a valid, just
                // unscaled-down, image), and the filter accepts an image as
                // long as EITHER key is present.
                embed.type.contains("images") || embed.type.contains("gallery") ||
                    !embed.images.isNullOrEmpty() || !embed.items.isNullOrEmpty() -> {
                    fun resolvedThumb(img: BskyImageView): String {
                        // Bug fix: must use the null-safe isNullOrBlank()
                        // here, not isNotBlank() — img.thumb is statically
                        // typed as non-null String, but for a gallery photo
                        // it's genuinely null at runtime (see comment
                        // above), and isNotBlank() dereferences its receiver
                        // without a null check, which would throw exactly
                        // the crash this whole fix exists to prevent.
                        val t = img.thumb
                        return if (!t.isNullOrBlank()) t else img.thumbnail?.takeIf { it.isNotBlank() } ?: img.fullsize
                    }
                    val images = (embed.images?.takeIf { it.isNotEmpty() } ?: embed.items ?: emptyList())
                        .filter { !it.fullsize.isNullOrBlank() && (!it.thumb.isNullOrBlank() || !it.thumbnail.isNullOrBlank()) }
                    if (images.isEmpty()) textOnlyItem() else {
                        val first = images.first()
                        listOf(
                            MediaItem(
                                id = post.cid, mediaUrl = first.fullsize,
                                thumbUrl = resolvedThumb(first),
                                // Tagging-speed fix: same reasoning as
                                // E621Repository's taggingUrl — reuse the
                                // CDN-served thumbnail (already sized well
                                // above the tagger's 448px input) instead of
                                // fetching the full, uncapped `fullsize` blob
                                // just to tag it.
                                taggingUrl = resolvedThumb(first),
                                isVideo = false, postUri = post.uri, postCid = post.cid, feedContext = item.feedContext,
                                author = author, likeUri = post.viewer?.like, repostUri = post.viewer?.repost,
                                isLiked = post.viewer?.like != null, isReposted = post.viewer?.repost != null,
                                likeCount = post.likeCount ?: 0, replyCount = post.replyCount ?: 0,
                                repostCount = post.repostCount ?: 0, altText = first.alt ?: "",
                                mediaGroup = if (images.size > 1) images.map {
                                    MediaGroupItem(mediaUrl = it.fullsize, thumbUrl = resolvedThumb(it), altText = it.alt ?: "")
                                } else emptyList(),
                                text = text
                            )
                        )
                    }
                }
                embed.type.contains("video") -> listOf(
                    MediaItem(
                        id = post.cid, mediaUrl = embed.thumbnail ?: "", thumbUrl = embed.thumbnail ?: "",
                        isVideo = true, videoPlaylistUrl = embed.playlist, videoBlobCid = embed.cid,
                        postUri = post.uri, postCid = post.cid, feedContext = item.feedContext,
                        author = author, likeUri = post.viewer?.like, repostUri = post.viewer?.repost,
                        isLiked = post.viewer?.like != null, isReposted = post.viewer?.repost != null,
                        likeCount = post.likeCount ?: 0, replyCount = post.replyCount ?: 0,
                        repostCount = post.repostCount ?: 0, text = text
                    )
                )
                embed.type.contains("recordWithMedia") ->
                    embed.media?.let { parseFeedItem(item.copy(post = post.copy(embed = it))) } ?: textOnlyItem()
                // A quote repost: the profile owner's own post record is just
                // their commentary wrapping a reference to someone else's post.
                // Show the ORIGINAL (quoted) post's actual content as the card
                // — not the quoting commentary, which used to be all that
                // rendered here — and surface the commentary via the same
                // "sentBy" attribution header the From-Friends feed uses,
                // reworded to "<name> reposted: ..." (see sentByIsRepost).
                embed.type.contains("record") -> {
                    val quoted = embed.record?.record ?: embed.record
                    val quotedAuthorRaw = quoted?.author
                    if (quoted == null || quotedAuthorRaw == null) {
                        textOnlyItem()
                    } else {
                        val quotedAuthor = AuthorInfo(
                            did = quotedAuthorRaw.did, handle = quotedAuthorRaw.handle,
                            displayName = quotedAuthorRaw.displayName ?: quotedAuthorRaw.handle,
                            avatarUrl = quotedAuthorRaw.avatar,
                            followingUri = quotedAuthorRaw.viewer?.following,
                            isFollowing = quotedAuthorRaw.viewer?.following != null
                        )
                        val quotedEmbed = quoted.embeds?.firstOrNull()
                        // Bug fix (root cause confirmed this session — see
                        // BskyImageView's comment in Models.kt): gallery
                        // (5-10 image) photos use a "thumbnail" key instead
                        // of "thumb" for the exact same value, which the
                        // filter here was treating as "unusable" for every
                        // single gallery photo. goodImage() now accepts
                        // either key, and quotedImageThumb resolves whichever
                        // one is actually present (falling back to the
                        // fullsize URL as a last resort).
                        val quotedImage = quotedEmbed?.takeIf {
                            it.type.contains("images") || it.type.contains("gallery") ||
                                !it.images.isNullOrEmpty() || !it.items.isNullOrEmpty()
                        }?.let { qe ->
                            fun goodImage(list: List<BskyImageView>?) =
                                list?.firstOrNull { !it.fullsize.isNullOrBlank() && (!it.thumb.isNullOrBlank() || !it.thumbnail.isNullOrBlank()) }
                            goodImage(qe.images) ?: goodImage(qe.items)
                        }
                        val quotedImageThumb = quotedImage?.let { img ->
                            val t = img.thumb
                            if (!t.isNullOrBlank()) t else img.thumbnail?.takeIf { it.isNotBlank() } ?: img.fullsize
                        }
                        val quotedVideo = quotedEmbed?.takeIf { it.type.contains("video") }
                        listOf(
                            MediaItem(
                                id = post.cid,
                                mediaUrl = quotedVideo?.thumbnail ?: quotedImage?.fullsize ?: "",
                                thumbUrl = quotedVideo?.thumbnail ?: quotedImageThumb ?: "",
                                isVideo = quotedVideo != null,
                                videoPlaylistUrl = quotedVideo?.playlist, videoBlobCid = quotedVideo?.cid,
                                // Interactions (like/repost/etc.) still act on the
                                // outer quote-repost post itself, not the quoted
                                // one — same as tapping "repost" on a quote post
                                // in the real Bluesky app.
                                postUri = post.uri, postCid = post.cid, feedContext = item.feedContext,
                                author = quotedAuthor,
                                likeUri = post.viewer?.like, repostUri = post.viewer?.repost,
                                isLiked = post.viewer?.like != null, isReposted = post.viewer?.repost != null,
                                likeCount = post.likeCount ?: 0, replyCount = post.replyCount ?: 0,
                                repostCount = post.repostCount ?: 0,
                                altText = quotedImage?.alt ?: "",
                                text = quoted.value?.text ?: "",
                                sentByAuthor = author, sentByMessage = text, sentByIsRepost = true
                            )
                        )
                    }
                }
                else -> {
                    // Diagnostic logging (this session): despite broadening
                    // detection to trust `items` regardless of the `$type`
                    // string, and despite live-checking Bluesky's own
                    // lexicon repo (still shows maxLength: 4 on
                    // app.bsky.embed.images as of this fix, no separate
                    // published "gallery" type exists), 5-10 image posts are
                    // still falling through to this catch-all — meaning the
                    // real embed shape genuinely doesn't match any of
                    // "images"/"gallery" in its $type AND has nothing usable
                    // in `images` or `items`. Rather than guess a third
                    // time, this logs the actual $type string and which
                    // fields Gson did/didn't populate whenever an embed
                    // exists but isn't recognized, specifically flagging the
                    // 5+ media items case bsky.app's own carousel targets.
                    // Filtering logcat for "RaccNet-Embed" on a real 5-10
                    // image post will show the literal wire shape instead of
                    // another guess — that's the fastest way to get this
                    // right on the next round.
                    if (post.embed != null) {
                        Log.w("RaccNet-Embed", "Unrecognized embed on ${post.uri}: type='${embed.type}' " +
                            "images=${embed.images?.size ?: -1} items=${embed.items?.size ?: -1} " +
                            "hasMedia=${embed.media != null} hasRecord=${embed.record != null} " +
                            "textLen=${text.length}")
                    }
                    textOnlyItem()
                }
            }
        }
    }
}
