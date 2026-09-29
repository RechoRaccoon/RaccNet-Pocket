package com.mediaviewer.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import com.mediaviewer.model.E621Comment
import com.mediaviewer.model.E621PostsResponse

class E621Api(baseUrl: String = "https://e621.net/", profile: HttpProfile = HttpProfile.E621) : ApiClient(baseUrl, profile) {
    suspend fun searchPosts(
        auth: String,
        tags: String = "",
        page: Int = 1,
        limit: Int = 75
    ): Response<E621PostsResponse> = call(
        "GET",
        "posts.json",
        headers = listOf("Authorization" to auth),
        query = listOf("tags" to tags, "page" to page, "limit" to limit)
    )
    suspend fun getFavorites(
        auth: String,
        page: Int = 1,
        limit: Int = 75
    ): Response<E621PostsResponse> = call(
        "GET",
        "favorites.json",
        headers = listOf("Authorization" to auth),
        query = listOf("page" to page, "limit" to limit)
    )
    suspend fun getComments(
        auth: String,
        groupBy: String = "comment",
        postId: Int
    ): Response<List<E621Comment>> = call(
        "GET",
        "comments.json",
        headers = listOf("Authorization" to auth),
        query = listOf("group_by" to groupBy, "search[post_id]" to postId)
    )
    suspend fun addFavorite(
        auth: String,
        postId: Int
    ): Response<ResponseBody> = call(
        "POST",
        "favorites.json",
        headers = listOf("Authorization" to auth),
        body = formRequestBody(listOf("post_id" to postId.toString()))
    )
    suspend fun removeFavorite(
        auth: String,
        postId: Int
    ): Response<ResponseBody> = call(
        "DELETE",
        "favorites/${postId}.json",
        headers = listOf("Authorization" to auth)
    )
    suspend fun votePost(
        auth: String,
        id: Int,
        score: Int,
        noUnvote: Boolean = false
    ): Response<ResponseBody> = call(
        "POST",
        "posts/${id}/votes.json",
        headers = listOf("Authorization" to auth),
        body = formRequestBody(listOf("score" to score.toString(), "no_unvote" to noUnvote.toString()))
    )
    suspend fun voteComment(
        auth: String,
        commentId: Int,
        score: Int
    ): Response<ResponseBody> = call(
        "POST",
        "comment_votes.json",
        headers = listOf("Authorization" to auth),
        body = formRequestBody(listOf("id" to commentId.toString(), "score" to score.toString()))
    )
    suspend fun createComment(
        auth: String,
        postId: Int,
        body: String,
        bump: Boolean = true
    ): Response<ResponseBody> = call(
        "POST",
        "comments.json",
        headers = listOf("Authorization" to auth),
        body = formRequestBody(listOf("comment[post_id]" to postId.toString(), "comment[body]" to body.toString(), "comment[bump]" to bump.toString()))
    )}
