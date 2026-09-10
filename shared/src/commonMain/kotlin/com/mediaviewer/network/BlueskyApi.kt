package com.mediaviewer.network

import com.mediaviewer.model.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement

// KMP port of the Retrofit BlueskyApi interface: same class name, same
// suspend function names/signatures, Ktor-backed. Retrofit's Response<T>
// wrapper is gone — functions return the deserialized @Serializable model
// directly (Ktor throws on transport/parse failures instead), so the
// repository layer's `response.isSuccessful` / `response.code()` checks
// become try/catch at the call site. @Query -> parameter() (null-safe:
// nulls are omitted, matching Retrofit), @Header -> header() (also
// null-safe), @Body -> setBody, @Path -> string interpolation.
class BlueskyApi(
    private val client: HttpClient,
    private val baseUrl: String = "https://bsky.social/"
) {
    private fun path(p: String) = baseUrl.trimEnd('/') + "/" + p.trimStart('/')

    suspend fun createSession(request: BskyCreateSessionRequest): BskySession =
        client.post(path("xrpc/com.atproto.server.createSession")) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun refreshSession(refreshToken: String): BskyRefreshResponse =
        client.post(path("xrpc/com.atproto.server.refreshSession")) {
            header("Authorization", refreshToken)
        }.body()

    suspend fun getTimeline(
        token: String,
        limit: Int = 50,
        cursor: String? = null
    ): BskyTimelineResponse =
        client.get(path("xrpc/app.bsky.feed.getTimeline")) {
            header("Authorization", token)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    suspend fun getFeed(
        token: String,
        feedUri: String,
        limit: Int = 50,
        cursor: String? = null
    ): BskyTimelineResponse =
        client.get(path("xrpc/app.bsky.feed.getFeed")) {
            header("Authorization", token)
            parameter("feed", feedUri)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    suspend fun getActorLikes(
        token: String,
        actor: String,
        limit: Int = 100,
        cursor: String? = null
    ): BskyActorLikesResponse =
        client.get(path("xrpc/app.bsky.feed.getActorLikes")) {
            header("Authorization", token)
            parameter("actor", actor)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    suspend fun getPostThread(
        token: String,
        uri: String,
        depth: Int = 10
    ): BskyThreadResponse =
        client.get(path("xrpc/app.bsky.feed.getPostThread")) {
            header("Authorization", token)
            parameter("uri", uri)
            parameter("depth", depth)
        }.body()

    // Item 7: search — Posts/Accounts/Starter Packs tabs. Note: Bluesky's
    // public API has no equivalent search for Lists (only per-actor
    // app.bsky.graph.getLists), so that tab has no backing endpoint — see
    // SearchOverlay.kt.
    suspend fun searchPosts(
        token: String,
        query: String,
        limit: Int = 25,
        cursor: String? = null
    ): BskySearchPostsResponse =
        client.get(path("xrpc/app.bsky.feed.searchPosts")) {
            header("Authorization", token)
            parameter("q", query)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    suspend fun searchActors(
        token: String,
        query: String,
        limit: Int = 25,
        cursor: String? = null
    ): BskySearchActorsResponse =
        client.get(path("xrpc/app.bsky.actor.searchActors")) {
            header("Authorization", token)
            parameter("q", query)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    suspend fun searchStarterPacks(
        token: String,
        query: String,
        limit: Int = 25,
        cursor: String? = null
    ): BskySearchStarterPacksResponse =
        client.get(path("xrpc/app.bsky.graph.searchStarterPacks")) {
            header("Authorization", token)
            parameter("q", query)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

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
    ) {
        client.post(path("xrpc/app.bsky.feed.sendInteractions")) {
            header("Authorization", token)
            // Ktor's header() skips null values, matching Retrofit's
            // behavior of omitting a null @Header.
            header("atproto-proxy", proxy)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body<Unit>()
    }

    suspend fun createRecord(
        token: String,
        request: BskyCreateRecordRequest
    ): BskyCreateRecordResponse =
        client.post(path("xrpc/com.atproto.repo.createRecord")) {
            header("Authorization", token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    // ── Compose Post (upload flow) ──────────────────────────────────────────
    // Raw-bytes blob upload — used for both images and (through
    // BlueskyRepository's own video.bsky.app client below) video. Content-
    // Type is per-call since it depends on the file being uploaded, so it's
    // a plain @Header rather than a fixed @Headers annotation.
    //
    // KMP port note: Retrofit took an okhttp3.RequestBody here; the KMP
    // signature takes the raw bytes instead. Sent as the raw request body
    // (ByteArrayContent) with a per-call Content-Type header, exactly like
    // the legacy @Body RequestBody — the ATProto lexicon does NOT use
    // multipart for this endpoint.
    suspend fun uploadBlob(
        token: String,
        contentType: String,
        bytes: ByteArray,
    ): BskyUploadBlobResponse =
        client.post(path("xrpc/com.atproto.repo.uploadBlob")) {
            header("Authorization", token)
            header(HttpHeaders.ContentType, contentType)
            setBody(ByteArrayContent(bytes))
        }.body()

    // Mints a short-lived service-auth token scoped to a single lexicon
    // method (here, uploadBlob) for a specific audience service — required
    // to authenticate directly against video.bsky.app, which is a separate
    // service from the user's own PDS. See BlueskyRepository.uploadVideoBlob.
    suspend fun getServiceAuth(
        token: String,
        aud: String,
        lxm: String,
        exp: Long
    ): BskyServiceAuthResponse =
        client.get(path("xrpc/com.atproto.server.getServiceAuth")) {
            header("Authorization", token)
            parameter("aud", aud)
            parameter("lxm", lxm)
            parameter("exp", exp)
        }.body()

    suspend fun deleteRecord(
        token: String,
        request: BskyDeleteRecordRequest
    ) {
        client.post(path("xrpc/com.atproto.repo.deleteRecord")) {
            header("Authorization", token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body<Unit>()
    }

    suspend fun getPreferences(token: String): BskyPreferencesResponse =
        client.get(path("xrpc/app.bsky.actor.getPreferences")) {
            header("Authorization", token)
        }.body()

    // Writes the full preferences array back — used to add a feed to the
    // user's saved feeds (see BlueskyRepository.addSavedFeed). Bluesky's
    // putPreferences lexicon takes the whole array, not a delta, so callers
    // always read-modify-write via getPreferences first.
    suspend fun putPreferences(
        token: String,
        request: BskyPreferencesResponse
    ) {
        client.post(path("xrpc/app.bsky.actor.putPreferences")) {
            header("Authorization", token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body<Unit>()
    }

    // Search page's Feeds filter (renamed from the old, never-actually-
    // implemented "Lists" filter — Bluesky's public API has no list-search
    // endpoint, but it does have this one for feed generators). Same
    // endpoint the official app's feed search uses.
    suspend fun searchFeedGenerators(
        token: String,
        query: String,
        limit: Int = 25
    ): BskyGetFeedGeneratorsResponse =
        client.get(path("xrpc/app.bsky.unspecced.getPopularFeedGenerators")) {
            header("Authorization", token)
            parameter("query", query)
            parameter("limit", limit)
        }.body()

    suspend fun getFeedGenerators(
        token: String,
        feeds: List<String>
    ): BskyGetFeedGeneratorsResponse =
        client.get(path("xrpc/app.bsky.feed.getFeedGenerators")) {
            header("Authorization", token)
            // Retrofit repeated @Query("feeds") once per list item, matching
            // the lexicon's array query param — Ktor needs the loop.
            feeds.forEach { parameter("feeds", it) }
        }.body()

    suspend fun getActorFeeds(
        token: String,
        actor: String,
        limit: Int = 30
    ): BskyActorFeedsResponse =
        client.get(path("xrpc/app.bsky.feed.getActorFeeds")) {
            header("Authorization", token)
            parameter("actor", actor)
            parameter("limit", limit)
        }.body()

    suspend fun getPopularFeedGenerators(
        token: String,
        limit: Int = 15
    ): BskyActorFeedsResponse =
        client.get(path("xrpc/app.bsky.unspecced.getPopularFeedGenerators")) {
            header("Authorization", token)
            parameter("limit", limit)
        }.body()

    suspend fun getAuthorFeed(
        token: String,
        actor: String,
        limit: Int = 50,
        cursor: String? = null,
        filter: String = "posts_no_replies"
    ): BskyTimelineResponse =
        client.get(path("xrpc/app.bsky.feed.getAuthorFeed")) {
            header("Authorization", token)
            parameter("actor", actor)
            parameter("limit", limit)
            parameter("cursor", cursor)
            parameter("filter", filter)
        }.body()

    suspend fun getProfile(
        token: String,
        actor: String
    ): BskyProfile =
        client.get(path("xrpc/app.bsky.actor.getProfile")) {
            header("Authorization", token)
            parameter("actor", actor)
        }.body()

    // Profile Overhaul: full profileViewDetailed (banner, bio, counts) —
    // same endpoint as getProfile above, different response shape.
    suspend fun getProfileDetailed(
        token: String,
        actor: String
    ): BskyProfileDetailed =
        client.get(path("xrpc/app.bsky.actor.getProfile")) {
            header("Authorization", token)
            parameter("actor", actor)
        }.body()

    // Feature (this session): Live Now — batch profile fetch (up to 25
    // actors per the real lexicon's maxLength) used to check a set of
    // mutuals' "Live Now" status all at once instead of one getProfile call
    // per account. Retrofit repeated @Query("actors") once per list item,
    // matching the lexicon's array query param.
    suspend fun getProfiles(
        token: String,
        actors: List<String>
    ): BskyGetProfilesResponse =
        client.get(path("xrpc/app.bsky.actor.getProfiles")) {
            header("Authorization", token)
            actors.forEach { parameter("actors", it) }
        }.body()

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
    ): BskyListRecordsResponse =
        client.get(path("xrpc/com.atproto.repo.listRecords")) {
            header("Authorization", token)
            parameter("repo", repo)
            parameter("collection", collection)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    suspend fun getLists(
        token: String,
        actor: String,
        limit: Int = 100
    ): BskyGetListsResponse =
        client.get(path("xrpc/app.bsky.graph.getLists")) {
            header("Authorization", token)
            parameter("actor", actor)
            parameter("limit", limit)
        }.body()

    suspend fun getActorStarterPacks(
        token: String,
        actor: String,
        limit: Int = 100
    ): BskyGetStarterPacksResponse =
        client.get(path("xrpc/app.bsky.graph.getActorStarterPacks")) {
            header("Authorization", token)
            parameter("actor", actor)
            parameter("limit", limit)
        }.body()

    suspend fun getFollows(
        token: String,
        actor: String,
        limit: Int = 100,
        cursor: String? = null
    ): BskyGetFollowsResponse =
        client.get(path("xrpc/app.bsky.graph.getFollows")) {
            header("Authorization", token)
            parameter("actor", actor)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    suspend fun getFollowers(
        token: String,
        actor: String,
        limit: Int = 100,
        cursor: String? = null
    ): BskyGetFollowersResponse =
        client.get(path("xrpc/app.bsky.graph.getFollowers")) {
            header("Authorization", token)
            parameter("actor", actor)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    // Accounts the current user is blocking — used to filter them out of DMs / From Friends.
    suspend fun getBlocks(
        token: String,
        limit: Int = 100,
        cursor: String? = null
    ): BskyGetBlocksResponse =
        client.get(path("xrpc/app.bsky.graph.getBlocks")) {
            header("Authorization", token)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    // ── Batch post hydration — used to resolve posts shared to us over DM ────
    suspend fun getPosts(
        token: String,
        uris: List<String>
    ): BskyGetPostsResponse =
        client.get(path("xrpc/app.bsky.feed.getPosts")) {
            header("Authorization", token)
            uris.forEach { parameter("uris", it) }
        }.body()

    // ── Chat / DMs — proxied to Bluesky's dedicated chat service ─────────────
    // All chat.bsky.* calls must be routed through the atproto-proxy header,
    // per Bluesky's documented DM API.
    private fun chatProxy() = "did:web:api.bsky.chat#bsky_chat"

    suspend fun listConvos(
        token: String,
        limit: Int = 50,
        cursor: String? = null
    ): BskyListConvosResponse =
        client.get(path("xrpc/chat.bsky.convo.listConvos")) {
            header("Authorization", token)
            header("atproto-proxy", chatProxy())
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    suspend fun getConvoForMembers(
        token: String,
        members: List<String>
    ): BskyGetConvoForMembersResponse =
        client.get(path("xrpc/chat.bsky.convo.getConvoForMembers")) {
            header("Authorization", token)
            header("atproto-proxy", chatProxy())
            members.forEach { parameter("members", it) }
        }.body()

    // Delta/catch-up feed across ALL convos at once — see BlueskyRepository.
    // getConvoLog's doc comment for why this (rather than re-fetching full
    // convo lists on a timer) is what powers real-time-feeling DMs here.
    suspend fun getConvoLog(
        token: String,
        cursor: String? = null
    ): BskyGetConvoLogResponse =
        client.get(path("xrpc/chat.bsky.convo.getLog")) {
            header("Authorization", token)
            header("atproto-proxy", chatProxy())
            parameter("cursor", cursor)
        }.body()

    suspend fun getMessages(
        token: String,
        convoId: String,
        limit: Int = 30,
        cursor: String? = null
    ): BskyGetMessagesResponse =
        client.get(path("xrpc/chat.bsky.convo.getMessages")) {
            header("Authorization", token)
            header("atproto-proxy", chatProxy())
            parameter("convoId", convoId)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    suspend fun sendMessage(
        token: String,
        request: BskySendMessageRequest
    ): JsonElement =
        client.post(path("xrpc/chat.bsky.convo.sendMessage")) {
            header("Authorization", token)
            header("atproto-proxy", chatProxy())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    // ── Bookmarks / Saves (Settings Update) ──────────────────────────────────
    suspend fun getBookmarks(
        token: String,
        limit: Int = 50,
        cursor: String? = null
    ): BskyGetBookmarksResponse =
        client.get(path("xrpc/app.bsky.bookmark.getBookmarks")) {
            header("Authorization", token)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    suspend fun createBookmark(
        token: String,
        body: Map<String, String>
    ) {
        client.post(path("xrpc/app.bsky.bookmark.createBookmark")) {
            header("Authorization", token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body<Unit>()
    }

    suspend fun deleteBookmark(
        token: String,
        body: Map<String, String>
    ) {
        client.post(path("xrpc/app.bsky.bookmark.deleteBookmark")) {
            header("Authorization", token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body<Unit>()
    }
}

/**
 * Separate from [BlueskyApi] because video upload/processing happens on a
 * dedicated service (video.bsky.app), not the user's own PDS — see
 * NetworkClient.buildBlueskyVideoApi and BlueskyRepository.uploadVideoBlob.
 */
class BlueskyVideoApi(
    private val client: HttpClient,
    private val baseUrl: String = "https://video.bsky.app/"
) {
    private fun path(p: String) = baseUrl.trimEnd('/') + "/" + p.trimStart('/')

    // KMP port note: Retrofit took an okhttp3.RequestBody here; the KMP
    // signature takes the raw bytes instead. Sent as the raw request body
    // (ByteArrayContent) with a per-call Content-Type header, exactly like
    // the legacy @Body RequestBody — the ATProto lexicon does NOT use
    // multipart for this endpoint.
    suspend fun uploadVideo(
        serviceAuthToken: String,
        contentType: String,
        did: String,
        name: String,
        bytes: ByteArray,
    ): BskyJobStatus =
        client.post(path("xrpc/app.bsky.video.uploadVideo")) {
            header("Authorization", serviceAuthToken)
            header(HttpHeaders.ContentType, contentType)
            parameter("did", did)
            parameter("name", name)
            setBody(ByteArrayContent(bytes))
        }.body()

    suspend fun getJobStatus(jobId: String): BskyJobStatusResponse =
        client.get(path("xrpc/app.bsky.video.getJobStatus")) {
            parameter("jobId", jobId)
        }.body()
}
