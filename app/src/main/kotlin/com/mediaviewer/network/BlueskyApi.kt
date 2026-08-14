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

    @POST("xrpc/com.atproto.repo.createRecord")
    suspend fun createRecord(
        @Header("Authorization") token: String,
        @Body request: BskyCreateRecordRequest
    ): Response<BskyCreateRecordResponse>

    @POST("xrpc/com.atproto.repo.deleteRecord")
    suspend fun deleteRecord(
        @Header("Authorization") token: String,
        @Body request: BskyDeleteRecordRequest
    ): Response<Unit>

    @GET("xrpc/app.bsky.actor.getPreferences")
    suspend fun getPreferences(
        @Header("Authorization") token: String
    ): Response<BskyPreferencesResponse>

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
