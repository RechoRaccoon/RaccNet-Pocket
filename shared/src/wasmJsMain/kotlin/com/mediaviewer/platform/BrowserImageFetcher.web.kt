package com.mediaviewer.platform

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
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
        // Perf: cdn.bsky.app never sends CORS headers, so the <img> attempt
        // below can never succeed for it — skip straight to the proxy and
        // save a wasted round-trip on every image from that host.
        val bytes = if (isKnownNoCorsHost(url)) {
            runCatching { loadViaProxy(url) }.getOrNull()
        } else {
            runCatching { loadViaImageElement(url) }.getOrNull()
                ?: runCatching { loadViaProxy(url) }.getOrNull()
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

    private fun isKnownNoCorsHost(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
        return host == "cdn.bsky.app"
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

        // Perf: draw the canvas at the size Coil actually asked for instead
        // of the source's natural size — re-encoding a multi-megapixel photo
        // to PNG just to display it at a few hundred px wastes CPU, memory,
        // and time on every image.
        val reqW = options.size.width.pxOrElse { 0 }
        val reqH = options.size.height.pxOrElse { 0 }
        val targetW: Int
        val targetH: Int
        if (reqW > 0 && reqH > 0) {
            val scale = minOf(reqW / width.toFloat(), reqH / height.toFloat(), 1f)
            targetW = maxOf(1, (width * scale).toInt())
            targetH = maxOf(1, (height * scale).toInt())
        } else {
            targetW = width
            targetH = height
        }

        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = targetW
        canvas.height = targetH
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.drawImage(img, 0.0, 0.0, targetW.toDouble(), targetH.toDouble())

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
        val proxyUrl = "$PROXY_BASE${encodeWeservUrlParam(stripped)}${proxySizeParams(url)}"
        val response = proxyHttpClient.get(proxyUrl)
        check(response.status.isSuccess()) { "Proxy request failed: ${response.status}" }
        return response.bodyAsBytes()
    }

    /**
     * Ask images.weserv.nl to downscale server-side to the size Coil asked
     * for, so a 96px avatar or a 1080px feed image downloads kilobytes
     * instead of the multi-MB original. Skipped when Coil didn't specify a
     * size (Size.ORIGINAL) or the image is animated — weserv would flatten a
     * GIF to its first frame.
     */
    private fun proxySizeParams(url: String): String {
        if (url.substringBefore("?").lowercase().endsWith(".gif")) return ""
        val w = options.size.width.pxOrElse { 0 }
        val h = options.size.height.pxOrElse { 0 }
        if (w <= 0 || h <= 0) return ""
        // fit=inside keeps the aspect ratio; we = don't enlarge small sources.
        return "&w=$w&h=$h&fit=inside&we"
    }

    /** Percent-encodes just the characters that would break out of
     *  images.weserv.nl's `url` query parameter (`?`, `&`, `#`, …) — a
     *  source URL containing its own query string would otherwise be parsed
     *  as weserv's own parameters. */
    private fun encodeWeservUrlParam(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("%26")
                '?' -> append("%3F")
                '#' -> append("%23")
                '%' -> append("%25")
                '+' -> append("%2B")
                ' ' -> append("%20")
                else -> append(c)
            }
        }
    }

    private companion object {
        const val PROXY_BASE = "https://images.weserv.nl/?url="

        val proxyHttpClient by lazy { HttpClient() }
    }
}
