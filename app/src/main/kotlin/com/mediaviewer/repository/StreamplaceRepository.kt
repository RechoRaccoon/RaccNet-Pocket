package com.mediaviewer.repository

import com.mediaviewer.model.StreamplaceLiveStream
import com.mediaviewer.model.StreamplaceVideoView
import com.mediaviewer.network.NetworkClient
import com.mediaviewer.network.StreamplaceRawLivestreamView
import com.mediaviewer.network.StreamplaceRawVideoView

/** Item 19: fetches a Bluesky user's VODs from Streamplace (stream.place),
 *  a separate AT Protocol service with its own place.stream.* lexicons —
 *  see StreamplaceApi.kt. Streamplace VOD publishing is a newer feature of
 *  theirs; most accounts simply won't have any, which is expected (not an
 *  error) and just means an empty Vods tab. */
class StreamplaceRepository {

    private val api = NetworkClient.buildStreamplaceApi()

    suspend fun getVods(handleOrDid: String, cursor: String? = null, limit: Int = 25)
        : Result<Pair<List<StreamplaceVideoView>, String?>> = runCatching {
        val resp = api.getVideoList(repo = handleOrDid, limit = limit, cursor = cursor)
        val body = resp.body() ?: error("Streamplace video list failed: ${resp.code()}")
        // Item 18/13-style defensive parsing: one malformed VOD (e.g. a
        // missing thumb blob ref) shouldn't blank the whole tab.
        val videos = body.videos.mapNotNull { v -> runCatching { v.toVideoView() }.getOrNull() }
        Pair(videos, body.cursor)
    }

    /** Item 8/19: every currently-live stream, filtered down to just the
     *  given friend DIDs. `place.stream.live.getLiveUsers` has no
     *  per-follows/per-user filter of its own (it's the whole platform's
     *  live list), so filtering to "is this person one of my friends" is
     *  done client-side after the fetch. */
    suspend fun getLiveFriends(friendDids: Set<String>): Result<List<StreamplaceLiveStream>> = runCatching {
        if (friendDids.isEmpty()) return@runCatching emptyList()
        val resp = api.getLiveUsers(limit = 100)
        val body = resp.body() ?: error("Streamplace live users failed: ${resp.code()}")
        body.streams
            .filter { it.author.did in friendDids }
            .mapNotNull { v -> runCatching { v.toLiveStream() }.getOrNull() }
    }

    private fun StreamplaceRawLivestreamView.toLiveStream(): StreamplaceLiveStream {
        // `record` is lexicon-typed "unknown" (the raw place.stream.livestream
        // record) — title is its one required field; thumb is an optional
        // blob ref in the same {ref: {$link: cid}} shape VODs use.
        val title = record?.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val thumbCid = record?.getAsJsonObject("thumb")?.getAsJsonObject("ref")?.get("\$link")?.takeIf { it.isJsonPrimitive }?.asString
        return StreamplaceLiveStream(
            uri = uri, cid = cid, authorDid = author.did, authorHandle = author.handle,
            authorDisplayName = author.displayName, authorAvatarUrl = author.avatar,
            title = title,
            thumbUrl = thumbCid?.let { "https://stream.place/xrpc/place.stream.playback.getVideoBlob?did=${author.did}&cid=$it" },
            viewerCount = viewerCount?.count ?: 0
        )
    }

    private fun StreamplaceRawVideoView.toVideoView(): StreamplaceVideoView {
        val thumbCid = record.thumb?.ref?.link
        return StreamplaceVideoView(
            uri = uri,
            cid = cid,
            authorDid = author.did,
            authorHandle = author.handle,
            authorDisplayName = author.displayName,
            authorAvatarUrl = author.avatar,
            title = record.title,
            description = record.description,
            durationMs = record.durationMs,
            createdAt = record.createdAt,
            thumbUrl = thumbCid?.let {
                "https://stream.place/xrpc/place.stream.playback.getVideoBlob?did=${author.did}&cid=$it"
            },
            likeCount = likeCount,
            viewCount = viewCounts?.count ?: 0
        )
    }
}
