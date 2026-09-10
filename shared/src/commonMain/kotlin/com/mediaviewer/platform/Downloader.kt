package com.mediaviewer.platform

/**
 * File downloads, replacing the legacy WorkManager-based DownloadWorker /
 * GifDownloadWorker pair.
 *
 * androidMain: downloads via OkHttp and inserts into MediaStore
 *              (DCIM/"RaccNet Pocket"), with progress notifications.
 * wasmJsMain:  in-page fetch with progress callback, then a Blob download
 *              (browser "Save as" flow). There is no background execution on
 *              the web — downloads only progress while the tab is open.
 *
 * Dedup contract: callers check [isAlreadyDownloaded] before starting and
 * call [markDownloaded] after success. On Android the record of truth is
 * MediaStore itself (markDownloaded is a no-op); on web the actual keeps a
 * small persisted set.
 */
expect class PlatformDownloader() {

    /**
     * Downloads [url] to a platform-appropriate location under [fileName].
     * Suspends until the download completes. Throws on failure.
     */
    suspend fun download(
        url: String,
        fileName: String,
        mimeType: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    )

    /** True if a download tagged with [postId] already completed. */
    fun isAlreadyDownloaded(postId: String): Boolean

    /** Records a completed download for [postId]. No-op where the platform
     *  itself is the record (Android MediaStore). */
    fun markDownloaded(postId: String)
}
