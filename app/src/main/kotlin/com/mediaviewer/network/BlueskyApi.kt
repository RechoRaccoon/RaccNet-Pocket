package com.mediaviewer.network

import com.mediaviewer.model.*
import retrofit2.Response
import retrofit2.http.*

interface BlueskyApi {

    @POST("xrpc/com.atproto.server.createSession")
    suspend fun createSession(@Body request: BskyCreateSessionRequest): Response<BskySession>

    @POST("xrpc/com.atproto.server.refreshSession")
    suspend fun refreshSession(
        @Header("Authorization") refreshToken: String
    ): Response<BskyRefreshResponse>

    @GET("xrpc/app.bsky.feed.getTimeline")
    suspend fun getTimeline(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null
    ): Response<BskyTimelineResponse>

    @GET("xrpc/app.bsky.feed.getFeed")
    suspend fun getFeed(
        @Header("Authorization") token: String,
        @Query("feed") feedUri: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null
    ): Response<BskyTimelineResponse>

    @GET("xrpc/app.bsky.feed.getActorLikes")
    suspend fun getActorLikes(
        @Header("Authorization") token: String,
        @Query("actor") actor: String,
        @Query("limit") limit: Int = 100,
        @Query("cursor") cursor: String? = null
    ): Response<BskyActorLikesResponse>

    @GET("xrpc/app.bsky.feed.getPostThread")
    suspend fun getPostThread(
        @Header("Authorization") token: String,
        @Query("uri") uri: String,
        @Query("depth") depth: Int = 10
    ): Response<BskyThreadResponse>

    // Item 7: search — Posts/Accounts/Starter Packs tabs. Note: Bluesky's
    // public API has no equivalent search for Lists (only per-actor
    // app.bsky.graph.getLists), so that tab has no backing endpoint — see
    // SearchOverlay.kt.
    @GET("xrpc/app.bsky.feed.searchPosts")
    suspend fun searchPosts(
        @Header("Authorization") token: String,
        @Query("q") query: String,
        @Query("limit") limit: Int = 25,
        @Query("cursor") cursor: String? = null
    ): Response<BskySearchPostsResponse>

    @GET("xrpc/app.bsky.actor.searchActors")
    suspend fun searchActors(
        @Header("Authorization") token: String,
        @Query("q") query: String,
        @Query("limit") limit: Int = 25,
        @Query("cursor") cursor: String? = null
    ): Response<BskySearchActorsResponse>

    @GET("xrpc/app.bsky.graph.searchStarterPacks")
    suspend fun searchStarterPacks(
        @Header("Authorization") token: String,
        @Query("q") query: String,
        @Query("limit") limit: Int = 25,
        @Query("cursor") cursor: String? = null
    ): Response<BskySearchStarterPacksResponse>

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
    @POST("xrpc/app.bsky.feed.sendInteractions")
    suspend fun sendInteractions(
        @Header("Authorization") token: String,
        @Header("atproto-proxy") proxy: String?,
        @Body request: BskySendInteractionsRequest
    ): Response<Unit>

    @POST("xrpc/com.atproto.repo.createRecord")
    suspend fun createRecord(
        @Header("Authorization") token: String,
        @Body request: BskyCreateRecordRequest
    ): Response<BskyCreateRecordResponse>

    // ── Compose Post (upload flow) ──────────────────────────────────────────
    // Raw-bytes blob upload — used for both images and (through
    // BlueskyRepository's own video.bsky.app client below) video. Content-
    // Type is per-call since it depends on the file being uploaded, so it's
    // a plain @Header rather than a fixed @Headers annotation.
    @POST("xrpc/com.atproto.repo.uploadBlob")
    suspend fun uploadBlob(
        @Header("Authorization") token: String,
        @Header("Content-Type") contentType: String,
        @Body body: okhttp3.RequestBody
    ): Response<BskyUploadBlobResponse>

    // Mints a short-lived service-auth token scoped to a single lexicon
    // method (here, uploadBlob) for a specific audience service — required
    // to authenticate directly against video.bsky.app, which is a separate
    // service from the user's own PDS. See BlueskyRepository.uploadVideoBlob.
    @GET("xrpc/com.atproto.server.getServiceAuth")
    suspend fun getServiceAuth(
        @Header("Authorization") token: String,
        @Query("aud") aud: String,
        @Query("lxm") lxm: String,
        @Query("exp") exp: Long
    ): Response<BskyServiceAuthResponse>

    @POST("xrpc/com.atproto.repo.deleteRecord")
    suspend fun deleteRecord(
        @Header("Authorization") token: String,
        @Body request: BskyDeleteRecordRequest
    ): Response<Unit>

    @GET("xrpc/app.bsky.actor.getPreferences")
    suspend fun getPreferences(
        @Header("Authorization") token: String
    ): Response<BskyPreferencesResponse>

    // Writes the full preferences array back — used to add a feed to the
    // user's saved feeds (see BlueskyRepository.addSavedFeed). Bluesky's
    // putPreferences lexicon takes the whole array, not a delta, so callers
    // always read-modify-write via getPreferences first.
    @POST("xrpc/app.bsky.actor.putPreferences")
    suspend fun putPreferences(
        @Header("Authorization") token: String,
        @Body request: BskyPreferencesResponse
    ): Response<Unit>

    // Search page's Feeds filter (renamed from the old, never-actually-
    // implemented "Lists" filter — Bluesky's public API has no list-search
    // endpoint, but it does have this one for feed generators). Same
    // endpoint the official app's feed search uses.
    @GET("xrpc/app.bsky.unspecced.getPopularFeedGenerators")
    suspend fun searchFeedGenerators(
        @Header("Authorization") token: String,
        @Query("query") query: String,
        @Query("limit") limit: Int = 25
    ): Response<BskyGetFeedGeneratorsResponse>

    @GET("xrpc/app.bsky.feed.getFeedGenerators")
    suspend fun getFeedGenerators(
        @Header("Authorization") token: String,
        @Query("feeds") feeds: List<String>
    ): Response<BskyGetFeedGeneratorsResponse>

    @GET("xrpc/app.bsky.feed.getActorFeeds")
    suspend fun getActorFeeds(
        @Header("Authorization") token: String,
        @Query("actor") actor: String,
        @Query("limit") limit: Int = 30
    ): Response<BskyActorFeedsResponse>

    @GET("xrpc/app.bsky.unspecced.getPopularFeedGenerators")
    suspend fun getPopularFeedGenerators(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 15
    ): Response<BskyActorFeedsResponse>

    @GET("xrpc/app.bsky.feed.getAuthorFeed")
    suspend fun getAuthorFeed(
        @Header("Authorization") token: String,
        @Query("actor") actor: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
        @Query("filter") filter: String = "posts_no_replies"
    ): Response<BskyTimelineResponse>

    @GET("xrpc/app.bsky.actor.getProfile")
    suspend fun getProfile(
        @Header("Authorization") token: String,
        @Query("actor") actor: String
    ): Response<BskyProfile>

    // Profile Overhaul: full profileViewDetailed (banner, bio, counts) —
    // same endpoint as getProfile above, different response shape.
    @GET("xrpc/app.bsky.actor.getProfile")
    suspend fun getProfileDetailed(
        @Header("Authorization") token: String,
        @Query("actor") actor: String
    ): Response<BskyProfileDetailed>

    // Feature (this session): Live Now — batch profile fetch (up to 25
    // actors per the real lexicon's maxLength) used to check a set of
    // mutuals' "Live Now" status all at once instead of one getProfile call
    // per account. Retrofit repeats @Query("actors") once per list item,
    // matching the lexicon's array query param.
    @GET("xrpc/app.bsky.actor.getProfiles")
    suspend fun getProfiles(
        @Header("Authorization") token: String,
        @Query("actors") actors: List<String>
    ): Response<BskyGetProfilesResponse>

    // Generic repo record listing — used for likes/reposts of OTHER accounts
    // (which getActorLikes can't fetch), and for probing/reading third-party
    // AT Proto app records (Leaflet blogs, Popfeed reviews) that have no
    // dedicated AppView endpoint of their own. Works unauthenticated against
    // any public PDS, so the Authorization header is optional.
    @GET("xrpc/com.atproto.repo.listRecords")
    suspend fun listRecords(
        @Header("Authorization") token: String?,
        @Query("repo") repo: String,
        @Query("collection") collection: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null
    ): Response<BskyListRecordsResponse>

    @GET("xrpc/app.bsky.graph.getLists")
    suspend fun getLists(
        @Header("Authorization") token: String,
        @Query("actor") actor: String,
        @Query("limit") limit: Int = 100
    ): Response<BskyGetListsResponse>

    @GET("xrpc/app.bsky.graph.getActorStarterPacks")
    suspend fun getActorStarterPacks(
        @Header("Authorization") token: String,
        @Query("actor") actor: String,
        @Query("limit") limit: Int = 100
    ): Response<BskyGetStarterPacksResponse>

    @GET("xrpc/app.bsky.graph.getFollows")
    suspend fun getFollows(
        @Header("Authorization") token: String,
        @Query("actor") actor: String,
        @Query("limit") limit: Int = 100,
        @Query("cursor") cursor: String? = null
    ): Response<BskyGetFollowsResponse>

    @GET("xrpc/app.bsky.graph.getFollowers")
    suspend fun getFollowers(
        @Header("Authorization") token: String,
        @Query("actor") actor: String,
        @Query("limit") limit: Int = 100,
        @Query("cursor") cursor: String? = null
    ): Response<BskyGetFollowersResponse>

    // Accounts the current user is blocking — used to filter them out of DMs / From Friends.
    @GET("xrpc/app.bsky.graph.getBlocks")
    suspend fun getBlocks(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 100,
        @Query("cursor") cursor: String? = null
    ): Response<BskyGetBlocksResponse>

    // ── Batch post hydration — used to resolve posts shared to us over DM ────
    @GET("xrpc/app.bsky.feed.getPosts")
    suspend fun getPosts(
        @Header("Authorization") token: String,
        @Query("uris") uris: List<String>
    ): Response<BskyGetPostsResponse>

    // ── Chat / DMs — proxied to Bluesky's dedicated chat service ─────────────
    // All chat.bsky.* calls must be routed through the atproto-proxy header,
    // per Bluesky's documented DM API.
    @Headers("atproto-proxy: did:web:api.bsky.chat#bsky_chat")
    @GET("xrpc/chat.bsky.convo.listConvos")
    suspend fun listConvos(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null
    ): Response<BskyListConvosResponse>

    @Headers("atproto-proxy: did:web:api.bsky.chat#bsky_chat")
    @GET("xrpc/chat.bsky.convo.getConvoForMembers")
    suspend fun getConvoForMembers(
        @Header("Authorization") token: String,
        @Query("members") members: List<String>
    ): Response<BskyGetConvoForMembersResponse>

    // Delta/catch-up feed across ALL convos at once — see BlueskyRepository.
    // getConvoLog's doc comment for why this (rather than re-fetching full
    // convo lists on a timer) is what powers real-time-feeling DMs here.
    @Headers("atproto-proxy: did:web:api.bsky.chat#bsky_chat")
    @GET("xrpc/chat.bsky.convo.getLog")
    suspend fun getConvoLog(
        @Header("Authorization") token: String,
        @Query("cursor") cursor: String? = null
    ): Response<BskyGetConvoLogResponse>

    @Headers("atproto-proxy: did:web:api.bsky.chat#bsky_chat")
    @GET("xrpc/chat.bsky.convo.getMessages")
    suspend fun getMessages(
        @Header("Authorization") token: String,
        @Query("convoId") convoId: String,
        @Query("limit") limit: Int = 30,
        @Query("cursor") cursor: String? = null
    ): Response<BskyGetMessagesResponse>

    @Headers("atproto-proxy: did:web:api.bsky.chat#bsky_chat")
    @POST("xrpc/chat.bsky.convo.sendMessage")
    suspend fun sendMessage(
        @Header("Authorization") token: String,
        @Body request: BskySendMessageRequest
    ): Response<com.google.gson.JsonElement>

    // ── Bookmarks / Saves (Settings Update) ──────────────────────────────────
    @GET("xrpc/app.bsky.bookmark.getBookmarks")
    suspend fun getBookmarks(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null
    ): Response<BskyGetBookmarksResponse>

    @POST("xrpc/app.bsky.bookmark.createBookmark")
    suspend fun createBookmark(
        @Header("Authorization") token: String,
        @Body body: Map<String, String>
    ): Response<Unit>

    @POST("xrpc/app.bsky.bookmark.deleteBookmark")
    suspend fun deleteBookmark(
        @Header("Authorization") token: String,
        @Body body: Map<String, String>
    ): Response<Unit>
}

/**
 * Separate from [BlueskyApi] because video upload/processing happens on a
 * dedicated service (video.bsky.app), not the user's own PDS — see
 * NetworkClient.buildBlueskyVideoApi and BlueskyRepository.uploadVideoBlob.
 */
interface BlueskyVideoApi {
    @POST("xrpc/app.bsky.video.uploadVideo")
    suspend fun uploadVideo(
        @Header("Authorization") serviceAuthToken: String,
        @Header("Content-Type") contentType: String,
        @Query("did") did: String,
        @Query("name") name: String,
        @Body body: okhttp3.RequestBody
    ): Response<BskyJobStatus>

    @GET("xrpc/app.bsky.video.getJobStatus")
    suspend fun getJobStatus(
        @Query("jobId") jobId: String
    ): Response<BskyJobStatusResponse>
}
