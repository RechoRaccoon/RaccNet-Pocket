package com.mediaviewer.ui

import androidx.compose.ui.graphics.Color
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.DataView
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * wasmJsMain actuals for the GlassTheme expects (see commonMain
 * ui/GlassTheme.kt).
 *
 * [fetchDominantColor] mirrors the Android implementation's algorithm
 * (downscale to 16x16, average the pixels, fall back to 0xFF2A2A2E), but
 * does it through the browser's `<img>` + `<canvas>` pipeline instead of
 * Coil, so it needs no image-loader context and works from any suspend
 * call site.
 *
 * CORS: canvas pixel reads throw a SecurityError when the image's server
 * omits `Access-Control-Allow-Origin` (e.g. `cdn.bsky.app`). To keep reads
 * origin-clean we route through the same CORS proxy the image fetcher
 * uses (`images.weserv.nl`, which sends `Access-Control-Allow-Origin: *`),
 * and ask it to downscale server-side (`w=16&h=16`) so we download a few
 * hundred bytes instead of the full image. Results are cached in-memory
 * per URL for the session.
 */

/** Compose Multiplatform implements blur on web; the API-31 no-op case doesn't apply. */
internal actual val CAN_BLUR: Boolean = true

private val dominantColorCache = mutableMapOf<String, Color>()
private const val FALLBACK = 0xFF2A2A2E

actual suspend fun fetchDominantColor(url: String): Color {
    if (url.isBlank()) return Color(FALLBACK)
    dominantColorCache[url]?.let { return it }
    val color = try {
        sampleViaCanvas(url)
    } catch (_: Exception) {
        Color(FALLBACK)
    }
    // Only cache successful samples; failures retry next time in case the
    // network hiccup was transient.
    if (color != Color(FALLBACK)) dominantColorCache[url] = color
    return color
}

private suspend fun sampleViaCanvas(url: String): Color {
    val stripped = url.removePrefix("https://").removePrefix("http://")
    // weserv downscales server-side: tiny download, CORS-clean pixels. The
    // source URL is encoded so a `?`/`&` in it can't be parsed as weserv's
    // own parameters.
    val proxied = "https://images.weserv.nl/?url=${encodeWeservUrlParam(stripped)}&w=16&h=16&fit=cover"

    val img = suspendCancellableCoroutine<HTMLImageElement> { cont ->
        val element = document.createElement("img") as HTMLImageElement
        element.crossOrigin = "anonymous"
        element.addEventListener("load", { _ -> cont.resume(element) })
        element.addEventListener("error", { _ ->
            cont.resumeWithException(IllegalStateException("Dominant-color load failed: $url"))
        })
        element.src = proxied
        cont.invokeOnCancellation { element.src = "" }
    }

    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = 16
    canvas.height = 16
    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    ctx.drawImage(img, 0.0, 0.0, 16.0, 16.0)

    // Throws SecurityError if the canvas is tainted; caller maps to fallback.
    // Read via DataView: Uint8ClampedArray's indexed access doesn't expose a
    // Kotlin-visible numeric type on this target, but DataView.getUint8()
    // returns Short which converts cleanly.
    val view = DataView(ctx.getImageData(0.0, 0.0, 16.0, 16.0).data.buffer)
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0
    var i = 0
    while (i + 3 < view.byteLength) {
        // getImageData returns un-premultiplied RGBA bytes.
        r += view.getUint8(i).toInt()
        g += view.getUint8(i + 1).toInt()
        b += view.getUint8(i + 2).toInt()
        n++
        i += 4
    }
    if (n == 0) return Color(FALLBACK)
    return Color(r.toFloat() / n / 255f, g.toFloat() / n / 255f, b.toFloat() / n / 255f, 1f)
}

/** Percent-encodes just the characters that would break out of
 *  images.weserv.nl's `url` query parameter (`?`, `&`, `#`, …). */
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
