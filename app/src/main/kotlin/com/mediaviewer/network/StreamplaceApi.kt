package com.mediaviewer.network

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** Streamplace (stream.place) runs its own AT Protocol repos/AppView under a
 *  place.stream.* lexicon namespace, entirely separate from Bluesky's own
 *  app.bsky.* namespace/host — see item 19. Base host is stream.place's own
 *  AppView, inferred from their public docs at docs.stream.place; this app
 *  has no confirmed-working account to test against, so double-check this
 *  base URL still resolves if VODs fail to load. */
interface StreamplaceApi {

    // place.stream.media.getVideoList — lists a repo's VODs newest-first.
    @GET("xrpc/place.stream.media.getVideoList")
    suspend fun getVideoList(
        @Query("repo") repo: String,
        @Query("limit") limit: Int = 25,
        @Query("cursor") cursor: String? = null
    ): Response<StreamplaceVideoListResponse>
}

data class StreamplaceVideoListResponse(
    val videos: List<StreamplaceRawVideoView> = emptyList(),
    val cursor: String? = null
)

data class StreamplaceRawVideoView(
    val uri: String,
    val cid: String,
    val author: StreamplaceRawAuthor,
    val record: StreamplaceRawVideoRecord,
    val likeCount: Int = 0,
    val viewCounts: StreamplaceRawViewCounts? = null
)

data class StreamplaceRawAuthor(
    val did: String,
    val handle: String,
    val displayName: String? = null,
    val avatar: String? = null
)

// `thumb` is a standard AT Protocol blob ref: { $type, ref: { $link: cid }, mimeType, size }.
data class StreamplaceRawVideoRecord(
    val title: String = "",
    val description: String? = null,
    val durationMs: Long = 0,
    val createdAt: String = "",
    val thumb: StreamplaceRawBlob? = null
)

data class StreamplaceRawBlob(val ref: StreamplaceRawBlobRef? = null, val mimeType: String? = null)
data class StreamplaceRawBlobRef(@com.google.gson.annotations.SerializedName("\$link") val link: String? = null)
data class StreamplaceRawViewCounts(val count: Int = 0)
