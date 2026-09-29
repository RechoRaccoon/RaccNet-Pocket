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

/** Saving media to the Photos library (the iOS counterpart of Android's
 *  DownloadWorker). Needs NSPhotoLibraryAddUsageDescription in Info.plist. */
object IosDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun save(url: String, filename: String, mimeType: String) {
        scope.launch {
            val result = runCatching {
                val resp = PlainHttp.get(url)
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val path = IosPaths.cacheDir() + "/" + filename
                if (!writeLocalFile(path, resp.body)) error("Couldn't write the file")
                saveFileToPhotos(path, isVideo = mimeType.startsWith("video"))
            }
            AppEvents.postMessage(if (result.isSuccess) "Saved to Photos" else "Couldn't save: ${result.exceptionOrNull()?.message}")
        }
    }

    fun saveVideoBlob(did: String, cid: String) {
        scope.launch {
            val url = runCatching { BlueskyBlobResolver.resolveBlobUrl(did, cid) }.getOrNull()
            if (url == null) { AppEvents.postMessage("Couldn't find that video"); return@launch }
            save(url, "stellar_${cid.takeLast(12)}.mp4", "video/mp4")
        }
    }

    private fun saveFileToPhotos(path: String, isVideo: Boolean) {
        val fileUrl = NSURL.fileURLWithPath(path)
        PHPhotoLibrary.sharedPhotoLibrary().performChanges({
            if (isVideo) PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(fileUrl)
            else PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(fileUrl)
        }, completionHandler = { _, _ -> })
    }
}
