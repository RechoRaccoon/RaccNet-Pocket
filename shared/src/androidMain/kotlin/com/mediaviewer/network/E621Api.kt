package com.mediaviewer.network

import com.mediaviewer.model.E621Comment
import com.mediaviewer.model.E621PostsResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface E621Api {

    @GET("posts.json")
    suspend fun searchPosts(
        @Header("Authorization") auth: String,
        @Query("tags") tags: String = "",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 75
    ): Response<E621PostsResponse>

    @GET("favorites.json")
    suspend fun getFavorites(
        @Header("Authorization") auth: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 75
    ): Response<E621PostsResponse>

    @GET("comments.json")
    suspend fun getComments(
        @Header("Authorization") auth: String,
        @Query("group_by") groupBy: String = "comment",
        @Query("search[post_id]") postId: Int
    ): Response<List<E621Comment>>

    @FormUrlEncoded
    @POST("favorites.json")
    suspend fun addFavorite(
        @Header("Authorization") auth: String,
        @Field("post_id") postId: Int
    ): Response<ResponseBody>

    @DELETE("favorites/{post_id}.json")
    suspend fun removeFavorite(
        @Header("Authorization") auth: String,
        @Path("post_id") postId: Int
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("posts/{id}/votes.json")
    suspend fun votePost(
        @Header("Authorization") auth: String,
        @Path("id") id: Int,
        @Field("score") score: Int,
        @Field("no_unvote") noUnvote: Boolean = false
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("comment_votes.json")
    suspend fun voteComment(
        @Header("Authorization") auth: String,
        @Field("id") commentId: Int,
        @Field("score") score: Int
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("comments.json")
    suspend fun createComment(
        @Header("Authorization") auth: String,
        @Field("comment[post_id]") postId: Int,
        @Field("comment[body]") body: String,
        @Field("comment[bump]") bump: Boolean = true
    ): Response<ResponseBody>
}
