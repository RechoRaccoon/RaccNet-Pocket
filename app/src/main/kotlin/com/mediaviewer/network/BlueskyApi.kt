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
}
