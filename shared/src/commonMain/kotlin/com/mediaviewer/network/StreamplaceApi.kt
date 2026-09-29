package com.mediaviewer.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/** Streamplace (stream.place) runs its own AT Protocol repos/AppView under a
 *  place.stream.* lexicon namespace, entirely separate from Bluesky's own
 *  app.bsky.* namespace/host — see item 19. Base host is stream.place's own
 *  AppView, inferred from their public docs at docs.stream.place; this app
 *  has no confirmed-working account to test against, so double-check this
 *  base URL still resolves if VODs fail to load. */
class StreamplaceApi(baseUrl: String = "https://stream.place/", profile: HttpProfile = HttpProfile.STREAMPLACE) : ApiClient(baseUrl, profile) {

    // place.stream.media.getVideoList — lists a repo's VODs newest-first.
    suspend fun getVideoList(
        repo: String,
        limit: Int = 25,
        cursor: String? = null
    ): Response<StreamplaceVideoListResponse> = call(
        "GET",
        "xrpc/place.stream.media.getVideoList",
        query = listOf("repo" to repo, "limit" to limit, "cursor" to cursor)
    )
    // Item 8/19: place.stream.live.getLiveUsers — every currently-live
    // stream platform-wide (there's no per-user/per-follows filter on this
    // endpoint itself; the app filters the result down to the person's own
    // friends/DM contacts client-side). Confirmed against the real lexicon
    // at stream.place/docs/lex-reference/live/place-stream-live-getliveusers.
    suspend fun getLiveUsers(
        limit: Int = 100,
        before: String? = null
    ): Response<StreamplaceLiveUsersResponse> = call(
        "GET",
        "xrpc/place.stream.live.getLiveUsers",
        query = listOf("limit" to limit, "before" to before)
    )}

@Serializable
data class StreamplaceVideoListResponse(
    val videos: List<StreamplaceRawVideoView> = emptyList(),
    val cursor: String? = null
)

@Serializable
data class StreamplaceRawVideoView(
    val uri: String = "",
    val cid: String = "",
    val author: StreamplaceRawAuthor = StreamplaceRawAuthor(),
    val record: StreamplaceRawVideoRecord = StreamplaceRawVideoRecord(),
    val likeCount: Int = 0,
    val viewCounts: StreamplaceRawViewCounts? = null
)

@Serializable
data class StreamplaceRawAuthor(
    val did: String = "",
    val handle: String = "",
    val displayName: String? = null,
    val avatar: String? = null
)

// `thumb` is a standard AT Protocol blob ref: { $type, ref: { $link: cid }, mimeType, size }.
@Serializable
data class StreamplaceRawVideoRecord(
    val title: String = "",
    val description: String? = null,
    val durationMs: Long = 0,
    val createdAt: String = "",
    val thumb: StreamplaceRawBlob? = null
)

@Serializable
data class StreamplaceRawBlob(val ref: StreamplaceRawBlobRef? = null, val mimeType: String? = null)
@Serializable
data class StreamplaceRawBlobRef(@SerialName("\$link") val link: String? = null)
@Serializable
data class StreamplaceRawViewCounts(val count: Int = 0)

// Item 8/19: place.stream.live.getLiveUsers's livestreamView — `record` is
// typed "unknown" in the real lexicon (a raw livestream record, whose
// confirmed fields per place.stream.livestream#main include `title` and a
// `thumb` blob), so it's parsed as a raw JsonObject the same way this app
// already handles other "unknown"-typed AT Protocol fields (e.g. DM message
// embeds), rather than a strongly-typed data class.
@Serializable
data class StreamplaceLiveUsersResponse(val streams: List<StreamplaceRawLivestreamView> = emptyList())

@Serializable
data class StreamplaceRawLivestreamView(
    val uri: String = "",
    val cid: String = "",
    val author: StreamplaceRawAuthor = StreamplaceRawAuthor(),
    val record: com.mediaviewer.json.JsonObject? = null,
    val viewerCount: StreamplaceRawViewCounts? = null
)
