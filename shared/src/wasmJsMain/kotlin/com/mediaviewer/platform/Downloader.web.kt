package com.mediaviewer.platform

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLAnchorElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Web downloads: Ktor (Js engine) fetches the bytes with progress, then a
// data-URL anchor download triggers the browser's "Save as" flow.
// No background execution on the web — downloads only progress while the tab
// is open, and the browser's own download UI is the progress surface.
//
// NOTE: Uses a base64 data URL instead of Blob/URL.createObjectURL because
// the typed Blob/Uint8Array bindings don't resolve on Kotlin 2.2.0 wasmJs,
// and asDynamic() is deprecated. Data URLs work for the file sizes this app
// downloads (images/video clips).
//
// Dedup is a small localStorage string-set of postIds — there is no
// MediaStore equivalent on the web.

/**
 * Downloads via Ktor, reading the response channel in chunks so [onProgress]
 * gets real updates, then hands the bytes to the browser with a temporary
 * anchor click (the standard "Save as" flow).
 *
 * Dedup is a small localStorage string-set of postIds — there is no
 * MediaStore equivalent on the web.
 */
@OptIn(ExperimentalEncodingApi::class)
actual class PlatformDownloader actual constructor() {

    actual suspend fun download(
        url: String,
        fileName: String,
        mimeType: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val client = HttpClient()
        try {
            val response = client.get(url)
            if (!response.status.isSuccess()) error("HTTP ${response.status} downloading $url")
            val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val channel = response.bodyAsChannel()

            val chunks = mutableListOf<ByteArray>()
            var received = 0L
            val buffer = ByteArray(8192)
            while (!channel.isClosedForRead) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n == -1) break
                if (n > 0) {
                    chunks.add(buffer.copyOf(n))
                    received += n
                    onProgress(received, total)
                }
            }

            // Combine chunks into a single ByteArray
            val allBytes = ByteArray(received.toInt())
            var pos = 0
            for (c in chunks) {
                c.copyInto(allBytes, pos)
                pos += c.size
            }

            triggerAnchorDownload(allBytes, fileName, mimeType)
        } finally {
            client.close()
        }
    }

    private fun triggerAnchorDownload(data: ByteArray, fileName: String, mimeType: String) {
        // Data URL avoids Blob/Uint8Array/URL which lack typed bindings.
        val base64 = Base64.Default.encode(data)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = "data:$mimeType;base64,$base64"
        anchor.download = fileName
        document.body?.appendChild(anchor)
        anchor.click()
        document.body?.removeChild(anchor)
    }

    private fun readIds(): MutableSet<String> = try {
        val raw = localStorage.getItem(STORAGE_KEY) ?: return mutableSetOf()
        Json.decodeFromString<List<String>>(raw).toMutableSet()
    } catch (_: Exception) {
        mutableSetOf()
    }

    actual fun isAlreadyDownloaded(postId: String): Boolean =
        postId.isNotBlank() && readIds().contains(postId)

    actual fun markDownloaded(postId: String) {
        if (postId.isBlank()) return
        val ids = readIds()
        if (ids.add(postId)) {
            localStorage.setItem(STORAGE_KEY, Json.encodeToString(ids.toList()))
        }
    }

    companion object {
        private const val STORAGE_KEY = "raccnet_downloaded_post_ids"
    }
}
