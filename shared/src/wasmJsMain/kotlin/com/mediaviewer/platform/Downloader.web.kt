package com.mediaviewer.platform

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.js.asDynamic

// Web downloads: Ktor (Js engine) fetches the bytes with progress, then a
// Blob anchor download triggers the browser's "Save as" flow.
// No background execution on the web — downloads only progress while the tab
// is open, and the browser's own download UI is the progress surface.

/**
 * Downloads via Ktor, streaming the response channel so [onProgress] gets
 * real updates, then hands the bytes to the browser with a temporary anchor
 * click (the standard "Save as" flow).
 *
 * Dedup is a small localStorage string-set of postIds — there is no
 * MediaStore equivalent on the web.
 */
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
            val total = response.contentLength()
            val channel = response.bodyAsChannel()

            val chunks = mutableListOf<ByteArray>()
            var received = 0L
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(8192L)
                val bytes = packet.readBytes()
                if (bytes.isEmpty()) break
                chunks.add(bytes)
                received += bytes.size
                onProgress(received, total)
            }

            // Combine chunks into a single ByteArray
            val allBytes = ByteArray(received.toInt())
            var pos = 0
            for (c in chunks) {
                c.copyInto(allBytes, pos)
                pos += c.size
            }

            // Trigger the browser download on the main thread via dynamic
            // JS interop (typed DOM bindings for Blob/Uint8Array vary).
            withContext(Dispatchers.Main) {
                triggerAnchorDownload(allBytes, fileName, mimeType)
            }
        } finally {
            client.close()
        }
    }

    private fun triggerAnchorDownload(data: ByteArray, fileName: String, mimeType: String) {
        val w = window.asDynamic()
        // Build a JS Uint8Array from the Kotlin ByteArray
        val u8 = w.Uint8Array(data.size)
        for (i in data.indices) {
            u8[i] = data[i]
        }
        val opts = w.Object()
        opts.type = mimeType
        val blob = w.Blob(arrayOf(u8), opts)
        val objectUrl = w.URL.createObjectURL(blob) as String
        try {
            val anchor = w.document.createElement("a")
            anchor.href = objectUrl
            anchor.download = fileName
            w.document.body.appendChild(anchor)
            anchor.click()
            anchor.remove()
        } finally {
            w.URL.revokeObjectURL(objectUrl)
        }
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
