package com.mediaviewer.repository

import com.mediaviewer.model.*
import com.mediaviewer.network.BlueskyApi
import com.mediaviewer.network.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    private suspend fun ensureChatApi(myDid: String) {
        if (chatPdsResolvedFor == myDid) return
        chatPdsResolvedFor = myDid
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
        // On any failure, chatApi silently stays whatever it already was
        // (defaults to the same bsky.social-backed client as `api`).
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
        Pair(body.feed.flatMap { parseFeedItem(it) }, body.cursor)
    }

    suspend fun getFeed(token: String, feedUri: String, cursor: String? = null, limit: Int = 50)
        : Result<Pair<List<MediaItem>, String?>> = runCatching {
        val resp = api.getFeed("Bearer $token", feedUri, limit, cursor)
        val body = resp.body() ?: error("Feed ${resp.code()}: ${resp.message()}")
        Pair(body.feed.flatMap { parseFeedItem(it) }, body.cursor)
    }

    suspend fun getActorLikes(token: String, did: String, cursor: String? = null)
        : Result<Pair<List<MediaItem>, String?>> = runCatching {
        val resp = api.getActorLikes("Bearer $token", did, 100, cursor)
        val body = resp.body() ?: error("Likes ${resp.code()}")
        Pair(body.feed.flatMap { parseFeedItem(it) }, body.cursor)
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
        Pair(ownPosts.flatMap { parseFeedItem(it) }, body.cursor)
    }

    // ── Thread / Comments ─────────────────────────────────────────────────────

    suspend fun getPostThread(token: String, uri: String): Result<List<CommentItem>> = runCatching {
        val resp = api.getPostThread("Bearer $token", uri, 10)
        val body = resp.body() ?: error("Thread ${resp.code()}")
        (body.thread.replies ?: emptyList()).mapNotNull { view ->
            val post = view.post ?: return@mapNotNull null
            CommentItem(
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
                likeUri           = post.viewer?.like
            )
        }
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
        follows.values.filter { followerDids.contains(it.did) }.map {
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
    suspend fun getFriendsSharedPosts(token: String, myDid: String, convos: List<DmConversation>): Result<List<MediaItem>> = runCatching {
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
                            if (senderDid != null && senderDid != myDid) {
                                val embedObj  = msg.embed?.takeIf { it.isJsonObject }?.asJsonObject
                                val recordObj = embedObj?.getAsJsonObject("record")
                                val uri = recordObj?.get("uri")?.takeIf { it.isJsonPrimitive }?.asString
                                val cid = recordObj?.get("cid")?.takeIf { it.isJsonPrimitive }?.asString
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
            parseFeedItem(BskyFeedItem(post = post)).map {
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun createRecord(token: String, did: String, collection: String, record: Map<String, Any>): Result<String> = runCatching {
        val resp = api.createRecord("Bearer $token", BskyCreateRecordRequest(did, collection, record))
        resp.body()?.uri ?: error("CreateRecord ${resp.code()}")
    }

    private suspend fun deleteRecord(token: String, did: String, collection: String, rkey: String): Result<Unit> = runCatching {
        val resp = api.deleteRecord("Bearer $token", BskyDeleteRecordRequest(did, collection, rkey))
        if (!resp.isSuccessful) error("DeleteRecord ${resp.code()}")
    }

    private fun String.rkey() = this.substringAfterLast('/')

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
                embed.type.contains("images") -> {
                    val images = embed.images ?: emptyList()
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
                else -> textOnlyItem()
            }
        }
    }
}
