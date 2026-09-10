package com.mediaviewer.platform

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Buffer
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A Coil [Fetcher] for web (wasmJs) that loads http/https images without
 * depending on the server sending CORS headers.
 *
 * Background: Ktor's JS engine downloads via `fetch()`, which the browser
 * blocks when the server omits `Access-Control-Allow-Origin` (e.g.
 * `cdn.bsky.app`). This fetcher tries two strategies in order:
 *
 * 1. **Browser `<img>` element** with `crossOrigin="anonymous"`. When the
 *    server *does* send CORS headers the image loads, the canvas stays
 *    origin-clean, and we re-encode to PNG bytes via `toDataURL()`.
 * 2. **CORS proxy fallback** (`images.weserv.nl`, which returns
 *    `Access-Control-Allow-Origin: *`). Used when the `<img>` load fails
 *    (no CORS headers) or the canvas is tainted. Fetches the *original*
 *    bytes via Ktor so format/quality/animation are preserved.
 *
 * Returns `null` when both strategies fail so Coil falls through to the
 * next registered fetcher (Ktor's direct fetcher).
 */
class BrowserImageFetcherFactory : Fetcher.Factory<Uri> {

    override fun create(
        data: Uri,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher? {
        val scheme = data.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        return BrowserImageFetcher(data.toString(), options)
    }
}

private class BrowserImageFetcher(
    private val url: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = try {
            loadViaImageElement(url)
        } catch (e: Exception) {
            null
        } ?: try {
            loadViaProxy(url)
        } catch (e: Exception) {
            null
        } ?: return null

        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().apply { write(bytes) },
                fileSystem = options.fileSystem,
            ),
            // Null lets Coil sniff the format from the bytes (PNG from the
            // canvas path, original format from the proxy path).
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    /**
     * Loads [url] through a browser `<img>` element and re-encodes it to PNG
     * bytes via a `<canvas>`. Throws when the server doesn't send CORS
     * headers (image fails to load with `crossOrigin="anonymous"`) or when
     * the canvas is tainted.
     */
    private suspend fun loadViaImageElement(url: String): ByteArray {
        val img = suspendCancellableCoroutine<HTMLImageElement> { cont ->
            val element = document.createElement("img") as HTMLImageElement
            element.crossOrigin = "anonymous"
            element.addEventListener("load", { _ -> cont.resume(element) })
            element.addEventListener("error", { _ ->
                cont.resumeWithException(IllegalStateException("Image load failed: $url"))
            })
            element.src = url
            cont.invokeOnCancellation { element.src = "" }
        }

        val width = img.naturalWidth
        val height = img.naturalHeight
        check(width > 0 && height > 0) { "Image has no intrinsic size: $url" }

        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = width
        canvas.height = height
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.drawImage(img, 0.0, 0.0)

        // Throws SecurityError when the canvas is tainted (no CORS headers).
        val dataUrl = canvas.toDataURL("image/png")
        val base64 = dataUrl.substringAfter(";base64,")
        check(base64 != dataUrl) { "Unexpected data URL format" }

        @OptIn(ExperimentalEncodingApi::class)
        return Base64.decode(base64)
    }

    /**
     * Fetches the original bytes through a CORS proxy. `images.weserv.nl`
     * returns `Access-Control-Allow-Origin: *`, so Ktor's `fetch()`-based
     * download succeeds even for hosts like `cdn.bsky.app` that send no CORS
     * headers themselves.
     */
    private suspend fun loadViaProxy(url: String): ByteArray {
        val stripped = url.removePrefix("https://").removePrefix("http://")
        val proxyUrl = "$PROXY_BASE$stripped"
        val response = proxyHttpClient.get(proxyUrl)
        check(response.status.isSuccess()) { "Proxy request failed: ${response.status}" }
        return response.bodyAsBytes()
    }

    private companion object {
        const val PROXY_BASE = "https://images.weserv.nl/?url="

        val proxyHttpClient by lazy { HttpClient() }
    }
}
