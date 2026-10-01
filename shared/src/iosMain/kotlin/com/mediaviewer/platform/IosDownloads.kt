package com.mediaviewer.platform

import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.isSuccessful
import com.mediaviewer.worker.BlueskyBlobResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.*
import platform.Photos.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Saving media to the Photos library (the iOS counterpart of Android's
 * DownloadWorker). Needs NSPhotoLibraryAddUsageDescription in Info.plist.
 *
 * Fixes from the first device test:
 *  - The file name came from the URL, and Bluesky CDN URLs have no real
 *    extension (".../bafk…@jpeg"), so the "name" contained slashes and the
 *    temporary file could never be written. Files are now named from the
 *    post id + the MIME type's extension.
 *  - Photos access is asked for first (add-only), and "Saved to Photos" is
 *    only shown once Photos actually accepted the file.
 */
object IosDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun save(url: String, filename: String, mimeType: String) {
        scope.launch {
            val isVideo = mimeType.startsWith("video")
            val result = runCatching {
                if (!ensureAccess()) error("Allow Stellar to add to Photos in Settings")
                val resp = PlainHttp.get(url)
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                if (resp.body.isEmpty()) error("Empty file")
                val path = IosPaths.cacheDir() + "/" + safeName(filename, mimeType)
                if (!writeLocalFile(path, resp.body)) error("Couldn't write the file")
                try { saveFileToPhotos(path, isVideo) } finally { deleteLocalFile(path) }
            }
            AppEvents.postMessage(
                if (result.isSuccess) "Saved to Photos"
                else "Couldn't save: ${result.exceptionOrNull()?.message ?: "unknown error"}"
            )
        }
    }

    fun saveVideoBlob(did: String, cid: String) {
        scope.launch {
            val url = runCatching { BlueskyBlobResolver.resolveBlobUrl(did, cid) }.getOrNull()
            if (url == null) { AppEvents.postMessage("Couldn't find that video"); return@launch }
            save(url, "stellar_${cid.takeLast(12)}.mp4", "video/mp4")
        }
    }

    /** A plain file name (no slashes or URL junk) with an extension Photos
     *  recognises. */
    private fun safeName(filename: String, mimeType: String): String {
        val ext = when (mimeType) {
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            else -> "jpg"
        }
        val base = filename.substringAfterLast('/').substringBeforeLast('.')
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(80)
            .ifBlank { "stellar_" + currentTimeMillis() }
        return "$base.$ext"
    }

    /** Add-only Photos permission (asks once, the system remembers). */
    private suspend fun ensureAccess(): Boolean {
        val current = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)
        if (current == PHAuthorizationStatusAuthorized || current == PHAuthorizationStatusLimited) return true
        if (current != PHAuthorizationStatusNotDetermined) return false
        return suspendCoroutine { cont ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
                cont.resume(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited)
            }
        }
    }

    private suspend fun saveFileToPhotos(path: String, isVideo: Boolean) {
        val fileUrl = NSURL.fileURLWithPath(path)
        val error: String? = suspendCoroutine { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges({
                if (isVideo) PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(fileUrl)
                else PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(fileUrl)
            }, completionHandler = { ok, err ->
                cont.resume(if (ok) null else (err?.localizedDescription ?: "Photos refused the file"))
            })
        }
        if (error != null) error(error)
    }
}
