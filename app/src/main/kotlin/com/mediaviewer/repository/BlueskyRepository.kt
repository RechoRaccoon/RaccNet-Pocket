package com.mediaviewer.repository

import com.mediaviewer.model.*
import com.mediaviewer.network.BlueskyApi
import com.mediaviewer.network.NetworkClient
import com.mediaviewer.worker.BlueskyBlobResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

class BlueskyRepository {

    private var api: BlueskyApi = NetworkClient.buildBlueskyApi()
    private var baseUrl: String = "https://bsky.social/"

    fun updateServiceUrl(url: String) { baseUrl = url; api = NetworkClient.buildBlueskyApi(url) }

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
                    batchBody.feeds.forEach { infoByUri[it.uri] = BskyFeedInfo(it.uri, it.displayName, it.avatar) }
                } else {
                    // One bad URI shouldn't sink the whole batch — retry individually
                    batch.forEach { uri ->
                        runCatching { api.getFeedGenerators("Bearer $token", listOf(uri)) }
                            .getOrNull()?.body()?.feeds?.firstOrNull()?.let {
                                infoByUri[it.uri] = BskyFeedInfo(it.uri, it.displayName, it.avatar)
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
    suspend fun getLeafletBlogs(did: String): List<LeafletBlog> {
        for (collection in listOf("site.standard.document", "pub.leaflet.document")) {
            val resp = runCatching { api.listRecords(null, did, collection, 50, null) }.getOrNull()
            val body = resp?.takeIf { it.isSuccessful }?.body() ?: continue
            if (body.records.isEmpty()) continue
            val blogs = body.records.mapNotNull { rec ->
                val obj = rec.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val createdAt = obj.get("publishedAt")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: obj.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                LeafletBlog(uri = rec.uri, title = title, bodyText = extractLeafletBodyText(obj), createdAt = createdAt)
            }
            if (blogs.isNotEmpty()) return blogs.sortedByDescending { it.createdAt }
        }
        return emptyList()
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

    /** Profile "Reviews" tab (Popfeed, social.popfeed.review — formerly the
     *  deprecated app.popsky.review). Field names aren't publicly documented,
     *  so several plausible aliases are checked for the media title/image,
     *  rating, and body text. Returns empty (never an error) if the account
     *  has no reviews or Popfeed isn't reachable. */
    suspend fun getPopfeedReviews(did: String): List<PopfeedReview> {
        for (collection in listOf("social.popfeed.feed.review", "social.popfeed.review", "app.popsky.review")) {
            val resp = runCatching { api.listRecords(null, did, collection, 50, null) }.getOrNull()
            val body = resp?.takeIf { it.isSuccessful }?.body() ?: continue
            if (body.records.isEmpty()) continue
            val reviews = body.records.mapNotNull { rec ->
                val obj = rec.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val subject = obj.getAsJsonObject("subject") ?: obj.getAsJsonObject("item") ?: obj
                val title = firstStringField(subject, "title", "name") ?: firstStringField(obj, "title", "name")
                    ?: return@mapNotNull null
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
                // The previous "divide by 2 only if raw > 5" heuristic was
                // wrong: it silently skipped the conversion for any rating at
                // or below 2.5 stars (raw <= 5), which displayed as roughly
                // double the real rating — e.g. a true 2.5-star review (raw
                // rating = 5) stayed at 5 and rendered as a full 5 stars, and
                // a true 0.5-star review (raw rating = 1) rendered as a full
                // star instead of a half star. Always dividing by 2 here is
                // what actually matches the confirmed 0–10 scale.
                val rawRating = obj.get("rating")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: obj.get("stars")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: obj.get("score")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
                val rating5 = rawRating / 2f
                val createdAt = firstStringField(obj, "createdAt", "publishedAt") ?: ""
                PopfeedReview(
                    uri = rec.uri, mediaTitle = title, mediaImageUrl = image, mediaBackdropUrl = backdrop,
                    ratingOutOf5 = rating5.coerceIn(0f, 5f), reviewText = text, createdAt = createdAt
                )
            }
            if (reviews.isNotEmpty()) return reviews.sortedByDescending { it.createdAt }
        }
        return emptyList()
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
        // equivalents for a movie/TV/game backlog or watchlist.
        val backlogKeywords = listOf("backlog", "watchlist", "want_to", "towatch", "to_watch", "toplay", "to_play", "plan_to", "planning")
        val mediaTypeKeywords = listOf("movie", "film", "tv", "show", "game")

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
            result[rec.uri] = PopfeedBacklogItem(uri = rec.uri, title = title, imageUrl = image, createdAt = createdAt)
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

    /** All accounts that follow us AND we follow back — the set Bluesky allows DMs with by default. */
    suspend fun getMutuals(token: String, myDid: String): Result<List<AuthorInfo>> = runCatching {
        suspend fun fetchAllFollows(): Map<String, BskyProfileBasic> {
            val out = LinkedHashMap<String, BskyProfileBasic>()
            var cursor: String? = null
            do {
                val resp = api.getFollows("Bearer $token", myDid, 100, cursor)
                if (!resp.isSuccessful) error("getFollows ${resp.code()}: ${resp.message()}")
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
                val resp = api.getFollowers("Bearer $token", myDid, 100, cursor)
                if (!resp.isSuccessful) error("getFollowers ${resp.code()}: ${resp.message()}")
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

        byDid.values.sortedByDescending { it.lastSentByUsAt.ifBlank { it.lastActivityAt } }
    }

    /** Existing conversations only, sorted by the most recent message WE sent
     *  in each one (falling back to overall last activity). Used by
     *  [loadDmRecipients] and to know which convos actually have history for
     *  the "From Friends" scan. */
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
        }.sortedByDescending { it.lastSentByUsAt.ifBlank { it.lastActivityAt } }
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
            // not `images`, on an app.bsky.embed.gallery#view embed.
            val quotedImage = quotedEmbed?.takeIf { it.type.contains("images") || it.type.contains("gallery") }
                ?.let { it.images?.firstOrNull() ?: it.items?.firstOrNull() }
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
                thumbUrl = quotedVideo?.thumbnail ?: quotedImage?.thumb,
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
                    postUri = post.uri, postCid = post.cid,
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
                // checking for a populated `images` list directly — not just
                // the type string — means it's still recognized as an image
                // post instead of silently falling to the `else` catch-all
                // below. This is additive/defensive: it doesn't change
                // behavior for any embed that already matched the old check.
                // Bug fix: Bluesky's 5-10 image "photo carousel" posts use a
                // distinct app.bsky.embed.gallery#view embed, not
                // app.bsky.embed.images#view — confirmed via the actual
                // lexicon: the type string contains "gallery", not "images",
                // and the photo list comes back under `items` instead of
                // `images` (same BskyImageView shape). These were falling
                // through to the `else -> textOnlyItem()` catch-all below
                // because nothing recognized the gallery type or looked at
                // `items`. The `!embed.images.isNullOrEmpty()` fallback stays
                // too, purely defensive, in case some other embed variant
                // ever populates `images` under a $type this app doesn't
                // otherwise recognize.
                embed.type.contains("images") || embed.type.contains("gallery") || !embed.images.isNullOrEmpty() -> {
                    val images = embed.images?.takeIf { it.isNotEmpty() } ?: embed.items ?: emptyList()
                    if (images.isEmpty()) textOnlyItem() else {
                        val first = images.first()
                        listOf(
                            MediaItem(
                                id = post.cid, mediaUrl = first.fullsize,
                                thumbUrl = first.thumb, isVideo = false, postUri = post.uri, postCid = post.cid,
                                author = author, likeUri = post.viewer?.like, repostUri = post.viewer?.repost,
                                isLiked = post.viewer?.like != null, isReposted = post.viewer?.repost != null,
                                likeCount = post.likeCount ?: 0, replyCount = post.replyCount ?: 0,
                                repostCount = post.repostCount ?: 0, altText = first.alt ?: "",
                                mediaGroup = if (images.size > 1) images.map {
                                    MediaGroupItem(mediaUrl = it.fullsize, thumbUrl = it.thumb, altText = it.alt ?: "")
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
                        postUri = post.uri, postCid = post.cid,
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
                        // Bug fix: quoted carousel posts (app.bsky.embed.
                        // gallery#view) carry their photos under `items`
                        // instead of `images` — same fix as parseFeedItem's
                        // main image branch above.
                        val quotedImage = quotedEmbed?.takeIf { it.type.contains("images") || it.type.contains("gallery") || !it.images.isNullOrEmpty() }
                            ?.let { it.images?.firstOrNull() ?: it.items?.firstOrNull() }
                        val quotedVideo = quotedEmbed?.takeIf { it.type.contains("video") }
                        listOf(
                            MediaItem(
                                id = post.cid,
                                mediaUrl = quotedVideo?.thumbnail ?: quotedImage?.fullsize ?: "",
                                thumbUrl = quotedVideo?.thumbnail ?: quotedImage?.thumb ?: "",
                                isVideo = quotedVideo != null,
                                videoPlaylistUrl = quotedVideo?.playlist, videoBlobCid = quotedVideo?.cid,
                                // Interactions (like/repost/etc.) still act on the
                                // outer quote-repost post itself, not the quoted
                                // one — same as tapping "repost" on a quote post
                                // in the real Bluesky app.
                                postUri = post.uri, postCid = post.cid,
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
                else -> textOnlyItem()
            }
        }
    }
}
