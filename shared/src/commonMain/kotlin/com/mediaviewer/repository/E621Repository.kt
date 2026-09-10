package com.mediaviewer.repository

import com.mediaviewer.model.*
import com.mediaviewer.network.NetworkClient
import kotlin.io.encoding.Base64

class E621Repository {

    private val api = NetworkClient.buildE621Api()

    // PORT: android.util.Base64 -> kotlin.io.encoding.Base64 (no line
    // breaks by default, matching Base64.NO_WRAP).
    fun basicAuth(username: String, apiKey: String): String {
        val creds = "$username:$apiKey"
        return "Basic " + Base64.encode(creds.encodeToByteArray())
    }

    suspend fun searchPosts(username: String, apiKey: String, tags: String, page: Int = 1)
        : Result<List<MediaItem>> = runCatching {
        apiCall("Search") { api.searchPosts(basicAuth(username, apiKey), tags.ifBlank { "order:hot" }, page) }
            .posts.mapNotNull { it.toMediaItem() }
    }

    suspend fun getFavorites(username: String, apiKey: String, page: Int = 1)
        : Result<List<MediaItem>> = runCatching {
        apiCall("Favorites") { api.getFavorites(basicAuth(username, apiKey), page) }
            .posts.mapNotNull { it.toMediaItem() }
    }

    /** Batch-hydrates arbitrary e621 post URIs into MediaItems — used by
     *  the AI Tagging feature's search, mirroring
     *  BlueskyRepository.getPostsByUris. e621 has no dedicated
     *  get-posts-by-id endpoint, but its search endpoint accepts an `id:`
     *  tag filter that does the same job. */
    suspend fun getPostsByUris(username: String, apiKey: String, uris: List<String>): Result<List<MediaItem>> = runCatching {
        val ids = uris.mapNotNull { it.substringAfterLast('/').toIntOrNull() }
        if (ids.isEmpty()) return@runCatching emptyList()
        val items = mutableListOf<MediaItem>()
        ids.chunked(40).forEach { batch ->
            apiCall("Search") { api.searchPosts(basicAuth(username, apiKey), "id:${batch.joinToString(",")}", 1) }
                .posts.mapNotNullTo(items) { it.toMediaItem() }
        }
        items
    }

    suspend fun getComments(username: String, apiKey: String, postId: Int)
        : Result<List<CommentItem>> = runCatching {
        apiCall("Comments") { api.getComments(basicAuth(username, apiKey), postId = postId) }.map { c ->
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
        apiCall("Comment") { api.createComment(basicAuth(username, apiKey), postId, body) }
    }

    suspend fun addFavorite(username: String, apiKey: String, postId: Int): Result<Unit> = runCatching {
        apiCall("Favorite") { api.addFavorite(basicAuth(username, apiKey), postId) }
    }

    suspend fun removeFavorite(username: String, apiKey: String, postId: Int): Result<Unit> = runCatching {
        apiCall("UnFavorite") { api.removeFavorite(basicAuth(username, apiKey), postId) }
    }

    suspend fun votePost(username: String, apiKey: String, postId: Int, vote: Int): Result<Unit> = runCatching {
        apiCall("Vote") { api.votePost(basicAuth(username, apiKey), postId, vote) }
    }

    suspend fun voteComment(username: String, apiKey: String, commentId: Int, vote: Int): Result<Unit> = runCatching {
        apiCall("CommentVote") { api.voteComment(basicAuth(username, apiKey), commentId, vote) }
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
            // Tagging-speed fix: e621's "sample" rendition (~850px longest
            // edge JPEG) instead of the full original file — which for
            // e621 posts is routinely a multi-MB, sometimes multi-ten-MB,
            // PNG. Only used by TaggingRepository; falls back to `url`
            // itself when a post has no sample (small originals below the
            // site's sample threshold don't get one).
            taggingUrl       = sample?.url ?: url,
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
