package com.mediaviewer.util

import com.mediaviewer.platform.PlatformDeps
import com.mediaviewer.repository.BlueskyBlobResolver
import kotlinx.datetime.Clock

/**
 * Shared download orchestration — the commonMain half of the legacy
 * worker/DownloadWorker.kt + worker/GifDownloadWorker.kt pair.
 *
 * The legacy pair ran as WorkManager workers (background, retryable,
 * deduped by unique-work name); in the shared codebase the actual download
 * mechanics live behind [com.mediaviewer.platform.PlatformDownloader] and
 * this class keeps the orchestration that was shared across both workers:
 * dedup via [com.mediaviewer.platform.PlatformDownloader.isAlreadyDownloaded],
 * blob-URL resolution for Bluesky videos, filename/mime conventions, and
 * progress + completion notifications via
 * [com.mediaviewer.platform.PlatformNotifier].
 *
 * NOTE: [BlueskyBlobResolver] lives in com.mediaviewer.repository (ported
 * from the legacy worker/BlueskyBlobResolver.kt by the repository worker).
 */
class DownloadManager(private val deps: PlatformDeps) {

    companion object {
        // ALL downloads (images + videos) go to the same DCIM folder.
        // (Kept as a constant here for parity with the legacy workers; the
        // actual folder creation/placement is handled by the platform
        // actuals — see the androidMain PlatformDownloader.)
        const val FOLDER_NAME = "RaccNet Pocket"
    }

    /**
     * Enqueues (starts) a download of the file at [url]. Mirrors the legacy
     * DownloadWorker.enqueue(): skips when a download tagged [postId] already
     * exists, forwards byte progress to the notifier, records completion via
     * markDownloaded, and shows a completion notification. Throws on failure
     * (the legacy worker retried via WorkManager; callers that need retries
     * must implement them around this call).
     */
    suspend fun enqueue(url: String, filename: String, mimeType: String, postId: String = "") {
        // Legacy isAlreadyDownloaded(context, postId) returned false for a
        // blank postId, so nothing was ever skipped for un-tagged downloads.
        if (postId.isNotBlank() && deps.downloader.isAlreadyDownloaded(postId)) return
        val id = notificationId(postId)
        try {
            deps.downloader.download(url, filename, mimeType) { downloaded, total ->
                val progress = if (total != null && total > 0) downloaded.toFloat() / total else 0f
                deps.notifier.showDownloadProgress(id, filename, progress)
            }
            if (postId.isNotBlank()) deps.downloader.markDownloaded(postId)
            deps.notifier.showDownloadComplete(id, "Download complete", filename)
        } catch (e: Exception) {
            deps.notifier.cancel(id)
            throw e
        }
    }

    /**
     * Downloads the real original video blob for a Bluesky video post (see
     * BlueskyBlobResolver) instead of its HLS playlist URL. Mirrors the
     * legacy DownloadWorker.enqueueVideoBlob(), including the filename
     * convention `simpleOSFeed_<postId>_<timestamp>.mp4`.
     */
    suspend fun enqueueVideoBlob(did: String, cid: String, postId: String) {
        // NOTE: if the repository worker ports resolveBlobUrl as a suspend
        // function this call compiles unchanged; if it stays blocking, the
        // caller is already inside a suspend function so it also compiles.
        val url = BlueskyBlobResolver.resolveBlobUrl(did, cid)
        val filename = "simpleOSFeed_${postId}_${Clock.System.now().toEpochMilliseconds()}.mp4"
        enqueue(url, filename, "video/mp4", postId)
    }

    /**
     * GIF downloads (legacy GifDownloadWorker.enqueue) are deliberately NOT
     * ported here. Re-encoding a source image/video as a GIF requires
     * platform-only pieces — bitmap decoding and, for video sources,
     * MediaMetadataRetriever-style frame extraction — plus the legacy
     * GifEncoder. The shared seam for this is platform/Graphics.kt's
     * `encodeGif`, which is Android-only; the wasmJs actual is unimplemented
     * and its doc directs callers to fall back to downloading the source
     * media directly. When the platform worker lands the Android GIF
     * pipeline, this class can grow a `enqueueGif` that resolves the source
     * URL (direct or blob — same as [enqueueVideoBlob]) and delegates the
     * encoding to the platform; on web callers should just [enqueue] the
     * source media instead.
     */

    private fun notificationId(postId: String): Int = postId.hashCode()
}

/**
 * Ported from DownloadWorker.kt's top-level helper: builds the
 * (url, filename, mimeType) triple for a download.
 *
 * When the caller already knows this is a video (e.g. a Bluesky HLS
 * playlist URL that doesn't end in .mp4), don't trust the URL's
 * extension — force a real video mimetype/extension so it saves as a
 * playable video instead of being mis-typed as an image.
 */
fun urlToDownloadInfo(url: String, postId: String, isVideo: Boolean = false): Triple<String, String, String> {
    val rawExt = url.substringAfterLast('.', "jpg").lowercase().substringBefore('?')
    val ext = if (isVideo && rawExt !in listOf("mp4", "webm")) "mp4" else rawExt
    val mimeType = when {
        isVideo || ext == "mp4"  -> "video/mp4"
        ext == "webm"            -> "video/webm"
        ext == "jpg" || ext == "jpeg" -> "image/jpeg"
        ext == "png"              -> "image/png"
        ext == "gif"               -> "image/gif"
        ext == "webp"              -> "image/webp"
        else -> "image/jpeg"
    }
    return Triple(url, "simpleOSFeed_${postId}_${Clock.System.now().toEpochMilliseconds()}.$ext", mimeType)
}
