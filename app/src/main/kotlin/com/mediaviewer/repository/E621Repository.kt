package com.mediaviewer.repository

import android.util.Base64
import com.mediaviewer.model.*
import com.mediaviewer.network.NetworkClient

class E621Repository {

    private val api = NetworkClient.buildE621Api()

    fun basicAuth(username: String, apiKey: String): String {
        val creds = "$username:$apiKey"
        return "Basic " + Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun searchPosts(username: String, apiKey: String, tags: String, page: Int = 1)
        : Result<List<MediaItem>> = runCatching {
        val resp = api.searchPosts(basicAuth(username, apiKey), tags.ifBlank { "order:hot" }, page)
        resp.body()?.posts?.mapNotNull { it.toMediaItem() } ?: error("Search failed: ${resp.code()}")
    }

    suspend fun getFavorites(username: String, apiKey: String, page: Int = 1)
        : Result<List<MediaItem>> = runCatching {
        val resp = api.getFavorites(basicAuth(username, apiKey), page)
        resp.body()?.posts?.mapNotNull { it.toMediaItem() } ?: error("Favorites failed: ${resp.code()}")
    }

    suspend fun getComments(username: String, apiKey: String, postId: Int)
        : Result<List<CommentItem>> = runCatching {
        val resp = api.getComments(basicAuth(username, apiKey), postId = postId)
        (resp.body() ?: error("Comments failed: ${resp.code()}")).map { c ->
            CommentItem(
                id                = c.id.toString(),
                authorHandle      = c.creator_name,
                authorDisplayName = c.creator_name,
                authorAvatarUrl   = null,
                body              = c.body,
                createdAt         = c.created_at,
                likeCount         = c.score
            )
        }
    }

    suspend fun createComment(username: String, apiKey: String, postId: Int, body: String)
        : Result<Unit> = runCatching {
        val resp = api.createComment(basicAuth(username, apiKey), postId, body)
        if (!resp.isSuccessful) error("Comment failed: ${resp.code()}")
    }

    suspend fun addFavorite(username: String, apiKey: String, postId: Int): Result<Unit> = runCatching {
        val resp = api.addFavorite(basicAuth(username, apiKey), postId)
        if (!resp.isSuccessful) error("Favorite failed: ${resp.code()}")
    }

    suspend fun removeFavorite(username: String, apiKey: String, postId: Int): Result<Unit> = runCatching {
        val resp = api.removeFavorite(basicAuth(username, apiKey), postId)
        if (!resp.isSuccessful) error("UnFavorite failed: ${resp.code()}")
    }

    suspend fun votePost(username: String, apiKey: String, postId: Int, vote: Int): Result<Unit> = runCatching {
        val resp = api.votePost(basicAuth(username, apiKey), postId, vote)
        if (!resp.isSuccessful) error("Vote failed: ${resp.code()}")
    }

    suspend fun voteComment(username: String, apiKey: String, commentId: Int, vote: Int): Result<Unit> = runCatching {
        val resp = api.voteComment(basicAuth(username, apiKey), commentId, vote)
        if (!resp.isSuccessful) error("CommentVote failed: ${resp.code()}")
    }

    private fun E621Post.toMediaItem(): MediaItem? {
        val url   = file.url ?: return null
        val thumb = preview.url ?: sample?.url ?: url
        val isVid = file.ext in listOf("webm", "mp4")
        val artist = tags.artist.firstOrNull() ?: "unknown"
        return MediaItem(
            id               = id.toString(),
            mediaUrl         = url,
            thumbUrl         = thumb,
            isVideo          = isVid,
            videoPlaylistUrl = if (isVid) url else null,
            postUri          = "https://e621.net/posts/$id",
            postCid          = id.toString(),
            author           = AuthorInfo(did = artist, handle = artist, displayName = artist, avatarUrl = null),
            isBookmarked     = is_favorited,
            likeCount        = score.total,
            replyCount       = comment_count,
            e621PostId       = id,
            e621Score        = score.total,
            tags             = (tags.general + tags.species + tags.character + tags.artist).take(20).joinToString(" ")
        )
    }
}
