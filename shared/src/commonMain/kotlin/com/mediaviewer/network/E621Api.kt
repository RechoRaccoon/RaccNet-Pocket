package com.mediaviewer.network

import com.mediaviewer.model.E621Comment
import com.mediaviewer.model.E621PostsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Parameters

// KMP port of the Retrofit E621Api interface: same class name, same suspend
// function names/signatures, Ktor-backed. @FormUrlEncoded @Field params become
// FormDataContent bodies. Retrofit's Response<ResponseBody> becomes ByteArray
// (the raw response body); Response<T> becomes T.
class E621Api(
    private val client: HttpClient,
    private val baseUrl: String = "https://e621.net/"
) {
    private fun path(p: String) = baseUrl.trimEnd('/') + "/" + p.trimStart('/')

    suspend fun searchPosts(
        auth: String,
        tags: String = "",
        page: Int = 1,
        limit: Int = 75
    ): E621PostsResponse =
        client.get(path("posts.json")) {
            header("Authorization", auth)
            parameter("tags", tags)
            parameter("page", page)
            parameter("limit", limit)
        }.body()

    suspend fun getFavorites(
        auth: String,
        page: Int = 1,
        limit: Int = 75
    ): E621PostsResponse =
        client.get(path("favorites.json")) {
            header("Authorization", auth)
            parameter("page", page)
            parameter("limit", limit)
        }.body()

    suspend fun getComments(
        auth: String,
        groupBy: String = "comment",
        postId: Int
    ): List<E621Comment> =
        client.get(path("comments.json")) {
            header("Authorization", auth)
            parameter("group_by", groupBy)
            parameter("search[post_id]", postId)
        }.body()

    suspend fun addFavorite(
        auth: String,
        postId: Int
    ): ByteArray =
        client.post(path("favorites.json")) {
            header("Authorization", auth)
            setBody(FormDataContent(Parameters.build {
                append("post_id", postId.toString())
            }))
        }.body()

    suspend fun removeFavorite(
        auth: String,
        postId: Int
    ): ByteArray =
        client.delete(path("favorites/$postId.json")) {
            header("Authorization", auth)
        }.body()

    suspend fun votePost(
        auth: String,
        id: Int,
        score: Int,
        noUnvote: Boolean = false
    ): ByteArray =
        client.post(path("posts/$id/votes.json")) {
            header("Authorization", auth)
            setBody(FormDataContent(Parameters.build {
                append("score", score.toString())
                append("no_unvote", noUnvote.toString())
            }))
        }.body()

    suspend fun voteComment(
        auth: String,
        commentId: Int,
        score: Int
    ): ByteArray =
        client.post(path("comment_votes.json")) {
            header("Authorization", auth)
            setBody(FormDataContent(Parameters.build {
                append("id", commentId.toString())
                append("score", score.toString())
            }))
        }.body()

    suspend fun createComment(
        auth: String,
        postId: Int,
        body: String,
        bump: Boolean = true
    ): ByteArray =
        client.post(path("comments.json")) {
            header("Authorization", auth)
            setBody(FormDataContent(Parameters.build {
                append("comment[post_id]", postId.toString())
                append("comment[body]", body)
                append("comment[bump]", bump.toString())
            }))
        }.body()
}
