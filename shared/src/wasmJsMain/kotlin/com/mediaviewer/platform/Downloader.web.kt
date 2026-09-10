package com.mediaviewer.platform

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.fetch.fetch
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

// Web downloads: in-page fetch with progress, then a Blob anchor download.
// No background execution on the web — downloads only progress while the tab
// is open, and the browser's own download UI is the progress surface.

/**
 * Downloads via fetch(), streaming the ReadableStream so [onProgress] gets
 * real updates, then hands the assembled [Blob] to the browser with a
 * temporary anchor click (the standard "Save as" flow).
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
        // Top-level fetch() from org.w3c.fetch (imported above); on the wasmJs
        // DOM bindings it is not a member of `window`.
        // await() on JsPromise returns a nullable result in coroutines 1.10.x.
        val response = fetch(url).await() ?: error("Fetch failed: $url")
        if (!response.ok) error("HTTP ${response.status} downloading $url")
        val total = response.headers.get("content-length")?.toLongOrNull()
        val stream = response.body ?: error("Empty body downloading $url")
        val reader = stream.getReader()

        val chunks = mutableListOf<ByteArray>()
        var received = 0L
        try {
            while (true) {
                val result = reader.read().await() ?: break
                if (result.done) break
                val chunk = result.value as? Uint8Array ?: continue
                val bytes = ByteArray(chunk.length) { i -> chunk[i] }
                chunks.add(bytes)
                received += bytes.size
                onProgress(received, total)
            }
        } finally {
            reader.releaseLock()
        }

        val blob = Blob(chunks.toTypedArray(), BlobPropertyBag(type = mimeType))
        val objectUrl = URL.createObjectURL(blob)
        try {
            val anchor = document.createElement("a") as HTMLAnchorElement
            anchor.href = objectUrl
            anchor.download = fileName
            // Must be in the document for the click to trigger a download
            // in all browsers.
            document.body?.appendChild(anchor)
            anchor.click()
            anchor.remove()
        } finally {
            URL.revokeObjectURL(objectUrl)
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
