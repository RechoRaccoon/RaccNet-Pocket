package com.mediaviewer.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import com.mediaviewer.model.*

class BlueskyApi(baseUrl: String = "https://bsky.social/", profile: HttpProfile = HttpProfile.BLUESKY) : ApiClient(baseUrl, profile) {
    suspend fun createSession(
        request: BskyCreateSessionRequest
    ): Response<BskySession> = call(
        "POST",
        "xrpc/com.atproto.server.createSession",
        body = json(request)
    )
    suspend fun refreshSession(
        refreshToken: String
    ): Response<BskyRefreshResponse> = call(
        "POST",
        "xrpc/com.atproto.server.refreshSession",
        headers = listOf("Authorization" to refreshToken)
    )
    suspend fun getTimeline(
        token: String,
        limit: Int = 50,
        cursor: String? = null
    ): Response<BskyTimelineResponse> = call(
        "GET",
        "xrpc/app.bsky.feed.getTimeline",
        headers = listOf("Authorization" to token),
        query = listOf("limit" to limit, "cursor" to cursor)
    )
    suspend fun getFeed(
        token: String,
        feedUri: String,
        limit: Int = 50,
        cursor: String? = null
    ): Response<BskyTimelineResponse> = call(
        "GET",
        "xrpc/app.bsky.feed.getFeed",
        headers = listOf("Authorization" to token),
        query = listOf("feed" to feedUri, "limit" to limit, "cursor" to cursor)
    )
    suspend fun getActorLikes(
        token: String,
        actor: String,
        limit: Int = 100,
        cursor: String? = null
    ): Response<BskyActorLikesResponse> = call(
        "GET",
        "xrpc/app.bsky.feed.getActorLikes",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor, "limit" to limit, "cursor" to cursor)
    )
    suspend fun getPostThread(
        token: String,
        uri: String,
        depth: Int = 10
    ): Response<BskyThreadResponse> = call(
        "GET",
        "xrpc/app.bsky.feed.getPostThread",
        headers = listOf("Authorization" to token),
        query = listOf("uri" to uri, "depth" to depth)
    )
    // Item 7: search — Posts/Accounts/Starter Packs tabs. Note: Bluesky's
    // public API has no equivalent search for Lists (only per-actor
    // app.bsky.graph.getLists), so that tab has no backing endpoint — see
    // SearchOverlay.kt.
    suspend fun searchPosts(
        token: String,
        query: String,
        limit: Int = 25,
        cursor: String? = null
    ): Response<BskySearchPostsResponse> = call(
        "GET",
        "xrpc/app.bsky.feed.searchPosts",
        headers = listOf("Authorization" to token),
        query = listOf("q" to query, "limit" to limit, "cursor" to cursor)
    )
    suspend fun searchActors(
        token: String,
        query: String,
        limit: Int = 25,
        cursor: String? = null
    ): Response<BskySearchActorsResponse> = call(
        "GET",
        "xrpc/app.bsky.actor.searchActors",
        headers = listOf("Authorization" to token),
        query = listOf("q" to query, "limit" to limit, "cursor" to cursor)
    )
    suspend fun searchStarterPacks(
        token: String,
        query: String,
        limit: Int = 25,
        cursor: String? = null
    ): Response<BskySearchStarterPacksResponse> = call(
        "GET",
        "xrpc/app.bsky.graph.searchStarterPacks",
        headers = listOf("Authorization" to token),
        query = listOf("q" to query, "limit" to limit, "cursor" to cursor)
    )
    // Item 4: "Show more/less like this" — Bluesky's own feed-personalization
    // signal. The main AppView doesn't implement this itself for third-party
    // feeds (it 501s) — it only *proxies* the request on to whichever feed
    // generator actually supplied the post, the same way chat.bsky.* calls
    // above are proxied to the chat service. Unlike chat's fixed target
    // though, the target here is a different DID per feed generator, so it
    // can't be a static @Headers annotation — it's passed per-call as a
    // regular @Header instead (see BlueskyRepository.sendFeedInteraction,
    // which builds "did:...#bsky_fg" from the feed's own URI). Null/blank
    // when there's no known feed generator to proxy to (e.g. a chronological
    // timeline with no algorithm behind it), in which case the request goes
    // straight to the default AppView, same as before.
    suspend fun sendInteractions(
        token: String,
        proxy: String?,
        request: BskySendInteractionsRequest
    ): Response<Unit> = call(
        "POST",
        "xrpc/app.bsky.feed.sendInteractions",
        headers = listOf("Authorization" to token, "atproto-proxy" to proxy),
        body = json(request)
    )
    // Reporting a post or account to Bluesky's own moderation team. The
    // report goes through your PDS, proxied to Bluesky's moderation service
    // (its labeler DID) — the same route the official app uses.
    suspend fun createReport(
        token: String,
        body: kotlinx.serialization.json.JsonObject
    ): Response<Unit> = call(
        "POST",
        "xrpc/com.atproto.moderation.createReport",
        headers = listOf("Authorization" to token, "atproto-proxy" to "did:plc:ar7c4by46qjdydhdevvrndac#atproto_labeler"),
        body = json(body)
    )
    suspend fun createRecord(
        token: String,
        request: BskyCreateRecordRequest
    ): Response<BskyCreateRecordResponse> = call(
        "POST",
        "xrpc/com.atproto.repo.createRecord",
        headers = listOf("Authorization" to token),
        body = json(request)
    )
    // ── Compose Post (upload flow) ──────────────────────────────────────────
    // Raw-bytes blob upload — used for both images and (through
    // BlueskyRepository's own video.bsky.app client below) video. Content-
    // Type is per-call since it depends on the file being uploaded, so it's
    // a plain @Header rather than a fixed @Headers annotation.
    suspend fun uploadBlob(
        token: String,
        contentType: String,
        body: RequestBody
    ): Response<BskyUploadBlobResponse> = call(
        "POST",
        "xrpc/com.atproto.repo.uploadBlob",
        headers = listOf("Authorization" to token, "Content-Type" to contentType),
        body = body
    )
    // Mints a short-lived service-auth token scoped to a single lexicon
    // method (here, uploadBlob) for a specific audience service — required
    // to authenticate directly against video.bsky.app, which is a separate
    // service from the user's own PDS. See BlueskyRepository.uploadVideoBlob.
    suspend fun getServiceAuth(
        token: String,
        aud: String,
        lxm: String,
        exp: Long
    ): Response<BskyServiceAuthResponse> = call(
        "GET",
        "xrpc/com.atproto.server.getServiceAuth",
        headers = listOf("Authorization" to token),
        query = listOf("aud" to aud, "lxm" to lxm, "exp" to exp)
    )
    suspend fun deleteRecord(
        token: String,
        request: BskyDeleteRecordRequest
    ): Response<Unit> = call(
        "POST",
        "xrpc/com.atproto.repo.deleteRecord",
        headers = listOf("Authorization" to token),
        body = json(request)
    )
    // Live Link widget: upserts app.bsky.actor.status (rkey "self") — see
    // BskyPutRecordRequest's comment for why this needs put, not create.
    suspend fun putRecord(
        token: String,
        request: BskyPutRecordRequest
    ): Response<BskyPutRecordResponse> = call(
        "POST",
        "xrpc/com.atproto.repo.putRecord",
        headers = listOf("Authorization" to token),
        body = json(request)
    )
    // Profile editing / blog editing: read one record back.
    suspend fun getRecord(
        token: String,
        repo: String,
        collection: String,
        rkey: String
    ): Response<com.mediaviewer.model.BskyRecordEnvelope> = call(
        "GET",
        "xrpc/com.atproto.repo.getRecord",
        headers = listOf("Authorization" to token),
        query = listOf("repo" to repo, "collection" to collection, "rkey" to rkey)
    )
    // Profile editing: changing your handle.
    suspend fun updateHandle(
        token: String,
        body: Map<String, String>
    ): Response<Unit> = call(
        "POST",
        "xrpc/com.atproto.identity.updateHandle",
        headers = listOf("Authorization" to token),
        body = json(body)
    )
    suspend fun getPreferences(
        token: String
    ): Response<BskyPreferencesResponse> = call(
        "GET",
        "xrpc/app.bsky.actor.getPreferences",
        headers = listOf("Authorization" to token)
    )
    // Writes the full preferences array back — used to add a feed to the
    // user's saved feeds (see BlueskyRepository.addSavedFeed). Bluesky's
    // putPreferences lexicon takes the whole array, not a delta, so callers
    // always read-modify-write via getPreferences first.
    suspend fun putPreferences(
        token: String,
        request: BskyPreferencesResponse
    ): Response<Unit> = call(
        "POST",
        "xrpc/app.bsky.actor.putPreferences",
        headers = listOf("Authorization" to token),
        body = json(request)
    )
    // Search page's Feeds filter (renamed from the old, never-actually-
    // implemented "Lists" filter — Bluesky's public API has no list-search
    // endpoint, but it does have this one for feed generators). Same
    // endpoint the official app's feed search uses.
    suspend fun searchFeedGenerators(
        token: String,
        query: String,
        limit: Int = 25
    ): Response<BskyGetFeedGeneratorsResponse> = call(
        "GET",
        "xrpc/app.bsky.unspecced.getPopularFeedGenerators",
        headers = listOf("Authorization" to token),
        query = listOf("query" to query, "limit" to limit)
    )
    suspend fun getFeedGenerators(
        token: String,
        feeds: List<String>
    ): Response<BskyGetFeedGeneratorsResponse> = call(
        "GET",
        "xrpc/app.bsky.feed.getFeedGenerators",
        headers = listOf("Authorization" to token),
        query = listOf("feeds" to feeds)
    )
    suspend fun getActorFeeds(
        token: String,
        actor: String,
        limit: Int = 30
    ): Response<BskyActorFeedsResponse> = call(
        "GET",
        "xrpc/app.bsky.feed.getActorFeeds",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor, "limit" to limit)
    )
    suspend fun getPopularFeedGenerators(
        token: String,
        limit: Int = 15
    ): Response<BskyActorFeedsResponse> = call(
        "GET",
        "xrpc/app.bsky.unspecced.getPopularFeedGenerators",
        headers = listOf("Authorization" to token),
        query = listOf("limit" to limit)
    )
    suspend fun getAuthorFeed(
        token: String,
        actor: String,
        limit: Int = 50,
        cursor: String? = null,
        filter: String = "posts_no_replies"
    ): Response<BskyTimelineResponse> = call(
        "GET",
        "xrpc/app.bsky.feed.getAuthorFeed",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor, "limit" to limit, "cursor" to cursor, "filter" to filter)
    )
    suspend fun getProfile(
        token: String,
        actor: String
    ): Response<BskyProfile> = call(
        "GET",
        "xrpc/app.bsky.actor.getProfile",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor)
    )
    // Profile Overhaul: full profileViewDetailed (banner, bio, counts) —
    // same endpoint as getProfile above, different response shape.
    suspend fun getProfileDetailed(
        token: String,
        actor: String
    ): Response<BskyProfileDetailed> = call(
        "GET",
        "xrpc/app.bsky.actor.getProfile",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor)
    )
    // Feature (this session): Live Now — batch profile fetch (up to 25
    // actors per the real lexicon's maxLength) used to check a set of
    // mutuals' "Live Now" status all at once instead of one getProfile call
    // per account. Retrofit repeats @Query("actors") once per list item,
    // matching the lexicon's array query param.
    suspend fun getProfiles(
        token: String,
        actors: List<String>
    ): Response<BskyGetProfilesResponse> = call(
        "GET",
        "xrpc/app.bsky.actor.getProfiles",
        headers = listOf("Authorization" to token),
        query = listOf("actors" to actors)
    )
    // Generic repo record listing — used for likes/reposts of OTHER accounts
    // (which getActorLikes can't fetch), and for probing/reading third-party
    // AT Proto app records (Leaflet blogs, Popfeed reviews) that have no
    // dedicated AppView endpoint of their own. Works unauthenticated against
    // any public PDS, so the Authorization header is optional.
    suspend fun listRecords(
        token: String?,
        repo: String,
        collection: String,
        limit: Int = 50,
        cursor: String? = null
    ): Response<BskyListRecordsResponse> = call(
        "GET",
        "xrpc/com.atproto.repo.listRecords",
        headers = listOf("Authorization" to token),
        query = listOf("repo" to repo, "collection" to collection, "limit" to limit, "cursor" to cursor)
    )
    suspend fun getLists(
        token: String,
        actor: String,
        limit: Int = 100
    ): Response<BskyGetListsResponse> = call(
        "GET",
        "xrpc/app.bsky.graph.getLists",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor, "limit" to limit)
    )
    /** Customize Hub → list rows: the latest posts by a list's members
     *  (AppView — never the members' PDSs). */
    suspend fun getListFeed(
        token: String,
        list: String,
        limit: Int = 50,
        cursor: String? = null
    ): Response<BskyTimelineResponse> = call(
        "GET",
        "xrpc/app.bsky.feed.getListFeed",
        headers = listOf("Authorization" to token),
        query = listOf("list" to list, "limit" to limit, "cursor" to cursor)
    )
    /** A list's own view plus its members (AppView). */
    suspend fun getList(
        token: String?,
        list: String,
        limit: Int = 100,
        cursor: String? = null
    ): Response<com.mediaviewer.json.JsonObject> = call(
        "GET",
        "xrpc/app.bsky.graph.getList",
        headers = listOf("Authorization" to token),
        query = listOf("list" to list, "limit" to limit, "cursor" to cursor)
    )
    suspend fun resolveHandle(
        handle: String
    ): Response<com.mediaviewer.json.JsonObject> = call(
        "GET",
        "xrpc/com.atproto.identity.resolveHandle",
        query = listOf("handle" to handle)
    )
    suspend fun getActorStarterPacks(
        token: String,
        actor: String,
        limit: Int = 100
    ): Response<BskyGetStarterPacksResponse> = call(
        "GET",
        "xrpc/app.bsky.graph.getActorStarterPacks",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor, "limit" to limit)
    )
    suspend fun getFollows(
        token: String,
        actor: String,
        limit: Int = 100,
        cursor: String? = null
    ): Response<BskyGetFollowsResponse> = call(
        "GET",
        "xrpc/app.bsky.graph.getFollows",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor, "limit" to limit, "cursor" to cursor)
    )
    /** Same endpoint as [getFollows], parsed with chat settings/viewer state
     *  (the New chat popup's suggestions — see BlueskyRepository.getFollowsForChat). */
    suspend fun getFollowsFull(
        token: String,
        actor: String,
        limit: Int = 100,
        cursor: String? = null
    ): Response<BskyGetFollowsFullResponse> = call(
        "GET",
        "xrpc/app.bsky.graph.getFollows",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor, "limit" to limit, "cursor" to cursor)
    )
    suspend fun getFollowers(
        token: String,
        actor: String,
        limit: Int = 100,
        cursor: String? = null
    ): Response<BskyGetFollowersResponse> = call(
        "GET",
        "xrpc/app.bsky.graph.getFollowers",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor, "limit" to limit, "cursor" to cursor)
    )
    // Accounts the current user is blocking — used to filter them out of DMs / From Friends.
    suspend fun getBlocks(
        token: String,
        limit: Int = 100,
        cursor: String? = null
    ): Response<BskyGetBlocksResponse> = call(
        "GET",
        "xrpc/app.bsky.graph.getBlocks",
        headers = listOf("Authorization" to token),
        query = listOf("limit" to limit, "cursor" to cursor)
    )
    // ── Batch post hydration — used to resolve posts shared to us over DM ────
    suspend fun getPosts(
        token: String,
        uris: List<String>
    ): Response<BskyGetPostsResponse> = call(
        "GET",
        "xrpc/app.bsky.feed.getPosts",
        headers = listOf("Authorization" to token),
        query = listOf("uris" to uris)
    )
    // ── Chat / DMs — proxied to Bluesky's dedicated chat service ─────────────
    // All chat.bsky.* calls must be routed through the atproto-proxy header,
    // per Bluesky's documented DM API.
    suspend fun listConvos(
        token: String,
        limit: Int = 50,
        cursor: String? = null
    ): Response<BskyListConvosResponse> = call(
        "GET",
        "xrpc/chat.bsky.convo.listConvos",
        headers = listOf("atproto-proxy" to "did:web:api.bsky.chat#bsky_chat", "Authorization" to token),
        query = listOf("limit" to limit, "cursor" to cursor)
    )
    suspend fun getConvoForMembers(
        token: String,
        members: List<String>
    ): Response<BskyGetConvoForMembersResponse> = call(
        "GET",
        "xrpc/chat.bsky.convo.getConvoForMembers",
        headers = listOf("atproto-proxy" to "did:web:api.bsky.chat#bsky_chat", "Authorization" to token),
        query = listOf("members" to members)
    )
    // Delta/catch-up feed across ALL convos at once — see BlueskyRepository.
    // getConvoLog's doc comment for why this (rather than re-fetching full
    // convo lists on a timer) is what powers real-time-feeling DMs here.
    suspend fun getConvoLog(
        token: String,
        cursor: String? = null
    ): Response<BskyGetConvoLogResponse> = call(
        "GET",
        "xrpc/chat.bsky.convo.getLog",
        headers = listOf("atproto-proxy" to "did:web:api.bsky.chat#bsky_chat", "Authorization" to token),
        query = listOf("cursor" to cursor)
    )
    // ── Notifications (the Hub's Inbox). Normally called straight on the
    // AppView with a service-auth token — see BlueskyRepository.viaService.
    suspend fun getNotificationUnreadCount(
        token: String
    ): Response<BskyUnreadCountResponse> = call(
        "GET",
        "xrpc/app.bsky.notification.getUnreadCount",
        headers = listOf("Authorization" to token)
    )
    /** Your lists, each with the list item for [actor] if they're on it
     *  (Add To's + / − buttons). */
    suspend fun getListsWithMembership(
        token: String,
        actor: String,
        limit: Int = 50,
        cursor: String? = null
    ): Response<com.mediaviewer.json.JsonObject> = call(
        "GET",
        "xrpc/app.bsky.graph.getListsWithMembership",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor, "limit" to limit, "cursor" to cursor)
    )
    /** Your starter packs, each with the list item for [actor] if they're in it. */
    suspend fun getStarterPacksWithMembership(
        token: String,
        actor: String,
        limit: Int = 50,
        cursor: String? = null
    ): Response<com.mediaviewer.json.JsonObject> = call(
        "GET",
        "xrpc/app.bsky.graph.getStarterPacksWithMembership",
        headers = listOf("Authorization" to token),
        query = listOf("actor" to actor, "limit" to limit, "cursor" to cursor)
    )
    suspend fun listNotifications(
        token: String,
        limit: Int = 50,
        cursor: String? = null
    ): Response<BskyListNotificationsResponse> = call(
        "GET",
        "xrpc/app.bsky.notification.listNotifications",
        headers = listOf("Authorization" to token),
        query = listOf("limit" to limit, "cursor" to cursor)
    )
    suspend fun updateNotificationsSeen(
        token: String,
        request: BskyUpdateSeenRequest
    ): Response<com.mediaviewer.json.JsonElement> = call(
        "POST",
        "xrpc/app.bsky.notification.updateSeen",
        headers = listOf("Authorization" to token),
        body = json(request)
    )
    suspend fun searchActorsTypeahead(
        token: String,
        q: String,
        limit: Int = 25
    ): Response<BskyActorsTypeaheadResponse> = call(
        "GET",
        "xrpc/app.bsky.actor.searchActorsTypeahead",
        headers = listOf("Authorization" to token),
        query = listOf("q" to q, "limit" to limit)
    )
    suspend fun getConvoAvailability(
        token: String,
        members: List<String>
    ): Response<BskyConvoAvailabilityResponse> = call(
        "GET",
        "xrpc/chat.bsky.convo.getConvoAvailability",
        headers = listOf("atproto-proxy" to "did:web:api.bsky.chat#bsky_chat", "Authorization" to token),
        query = listOf("members" to members)
    )
    suspend fun createGroup(
        token: String,
        request: BskyCreateGroupRequest
    ): Response<BskyConvoResponse> = call(
        "POST",
        "xrpc/chat.bsky.group.createGroup",
        headers = listOf("atproto-proxy" to "did:web:api.bsky.chat#bsky_chat", "Authorization" to token),
        body = json(request)
    )
    suspend fun updateConvoRead(
        token: String,
        request: BskyUpdateReadRequest
    ): Response<BskyConvoResponse> = call(
        "POST",
        "xrpc/chat.bsky.convo.updateRead",
        headers = listOf("atproto-proxy" to "did:web:api.bsky.chat#bsky_chat", "Authorization" to token),
        body = json(request)
    )
    // Group chats: the full member list (convoView only lists a few).
    suspend fun getConvoMembers(
        token: String,
        convoId: String,
        limit: Int = 100,
        cursor: String? = null
    ): Response<BskyGetConvoMembersResponse> = call(
        "GET",
        "xrpc/chat.bsky.convo.getConvoMembers",
        headers = listOf("atproto-proxy" to "did:web:api.bsky.chat#bsky_chat", "Authorization" to token),
        query = listOf("convoId" to convoId, "limit" to limit, "cursor" to cursor)
    )
    suspend fun getMessages(
        token: String,
        convoId: String,
        limit: Int = 30,
        cursor: String? = null
    ): Response<BskyGetMessagesResponse> = call(
        "GET",
        "xrpc/chat.bsky.convo.getMessages",
        headers = listOf("atproto-proxy" to "did:web:api.bsky.chat#bsky_chat", "Authorization" to token),
        query = listOf("convoId" to convoId, "limit" to limit, "cursor" to cursor)
    )
    suspend fun sendMessage(
        token: String,
        request: BskySendMessageRequest
    ): Response<com.mediaviewer.json.JsonElement> = call(
        "POST",
        "xrpc/chat.bsky.convo.sendMessage",
        headers = listOf("atproto-proxy" to "did:web:api.bsky.chat#bsky_chat", "Authorization" to token),
        body = json(request)
    )
    suspend fun addReaction(
        token: String,
        request: BskyReactionRequest
    ): Response<BskyMessageResponse> = call(
        "POST",
        "xrpc/chat.bsky.convo.addReaction",
        headers = listOf("atproto-proxy" to "did:web:api.bsky.chat#bsky_chat", "Authorization" to token),
        body = json(request)
    )
    suspend fun removeReaction(
        token: String,
        request: BskyReactionRequest
    ): Response<BskyMessageResponse> = call(
        "POST",
        "xrpc/chat.bsky.convo.removeReaction",
        headers = listOf("atproto-proxy" to "did:web:api.bsky.chat#bsky_chat", "Authorization" to token),
        body = json(request)
    )
    // ── Bookmarks / Saves (Settings Update) ──────────────────────────────────
    suspend fun getBookmarks(
        token: String,
        limit: Int = 50,
        cursor: String? = null
    ): Response<BskyGetBookmarksResponse> = call(
        "GET",
        "xrpc/app.bsky.bookmark.getBookmarks",
        headers = listOf("Authorization" to token),
        query = listOf("limit" to limit, "cursor" to cursor)
    )
    suspend fun createBookmark(
        token: String,
        body: Map<String, String>
    ): Response<Unit> = call(
        "POST",
        "xrpc/app.bsky.bookmark.createBookmark",
        headers = listOf("Authorization" to token),
        body = json(body)
    )
    suspend fun deleteBookmark(
        token: String,
        body: Map<String, String>
    ): Response<Unit> = call(
        "POST",
        "xrpc/app.bsky.bookmark.deleteBookmark",
        headers = listOf("Authorization" to token),
        body = json(body)
    )}

/**
 * Separate from [BlueskyApi] because video upload/processing happens on a
 * dedicated service (video.bsky.app), not the user's own PDS — see
 * NetworkClient.buildBlueskyVideoApi and BlueskyRepository.uploadVideoBlob.
 */
class BlueskyVideoApi(baseUrl: String = "https://video.bsky.app/", profile: HttpProfile = HttpProfile.VIDEO) : ApiClient(baseUrl, profile) {
    suspend fun uploadVideo(
        serviceAuthToken: String,
        contentType: String,
        did: String,
        name: String,
        body: RequestBody
    ): Response<ResponseBody> = call(
        "POST",
        "xrpc/app.bsky.video.uploadVideo",
        headers = listOf("Authorization" to serviceAuthToken, "Content-Type" to contentType),
        query = listOf("did" to did, "name" to name),
        body = body
    )
    suspend fun getJobStatus(
        jobId: String
    ): Response<BskyJobStatusResponse> = call(
        "GET",
        "xrpc/app.bsky.video.getJobStatus",
        query = listOf("jobId" to jobId)
    )}
