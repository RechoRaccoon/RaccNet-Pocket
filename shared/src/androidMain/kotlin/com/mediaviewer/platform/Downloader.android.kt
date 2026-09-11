package com.mediaviewer.platform

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads via Ktor (OkHttp engine — the KMP replacement for the legacy
 * DownloadWorker's raw OkHttp call) and inserts into MediaStore under
 * DCIM/"RaccNet Pocket", ported from legacy worker/DownloadWorker.kt's
 * downloadFile/isAlreadyDownloaded.
 *
 * The legacy WorkManager wrapper (constraints, retries, unique-work dedup)
 * is gone — orchestration (dedup checks, progress notifications) now lives
 * in the shared DownloadManager, which calls this directly.
 */
actual class PlatformDownloader actual constructor() {

    companion object {
        /** Must match the filename pattern downloaders use, so [isAlreadyDownloaded]
         *  keeps working across app updates. */
        const val FILENAME_PREFIX = "simpleOSFeed_"
        // ALL downloads (images + videos) go to the same DCIM folder,
        // same as the legacy worker.
        const val FOLDER_NAME = "RaccNet Pocket"
    }

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            // No total request timeout — large media on a slow link must
            // not be killed mid-download. Per-read socket timeout still
            // aborts genuinely stalled connections.
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

    actual suspend fun download(
        url: String,
        fileName: String,
        mimeType: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) error("HTTP ${response.status.value} downloading $url")
        val channel = response.bodyAsChannel()
        val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()

        val isVideo = mimeType.startsWith("video")
        // Both images AND videos go to DCIM/RaccNet Pocket — same folder in
        // the gallery (legacy behavior).
        val (collection, relPath) = if (isVideo)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DCIM + "/$FOLDER_NAME"
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DCIM + "/$FOLDER_NAME"

        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = AndroidAppContext.app.contentResolver
        val itemUri: Uri = resolver.insert(collection, cv) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, total)
                }
            } ?: error("MediaStore openOutputStream failed")
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, cv, null, null)
        } catch (e: Exception) {
            // Don't leave a half-written pending row behind in the gallery.
            resolver.delete(itemUri, null, null)
            throw e
        }
    }

    /**
     * Dedup check ported from the legacy worker: true if any row in either
     * the Images or Video store already has this post's filename prefix.
     */
    actual fun isAlreadyDownloaded(postId: String): Boolean {
        if (postId.isBlank()) return false
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("${FILENAME_PREFIX}${postId}_%")
        val resolver = AndroidAppContext.app.contentResolver
        return listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).any { uri ->
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                ?.use { it.count > 0 } ?: false
        }
    }

    actual fun markDownloaded(postId: String) {
        // No-op on Android: MediaStore itself is the record of truth.
    }
}
