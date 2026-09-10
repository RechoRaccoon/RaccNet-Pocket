package com.mediaviewer.ui

import androidx.compose.ui.graphics.Color
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import kotlin.coroutines.resume

/**
 * wasmJsMain actuals for the GlassTheme expects (see commonMain
 * ui/GlassTheme.kt).
 *
 * [fetchDominantColor] loads the image into an `<img>`, draws a 16x16 copy
 * onto a canvas and averages its pixels — the same algorithm as the legacy
 * Android implementation. `crossOrigin = "anonymous"` keeps the canvas
 * readable for CORS-enabled CDNs; anything else (tainted canvas, load
 * error) falls back to 0xFF2A2A2E, same as Android.
 */

/** Compose Multiplatform implements blur on web; the API-31 no-op case doesn't apply. */
internal actual val CAN_BLUR: Boolean = true

actual suspend fun fetchDominantColor(url: String): Color {
    if (url.isBlank()) return Color(0xFF2A2A2E)
    return try {
        suspendCancellableCoroutine { cont ->
            val img = (document.createElement("img") as HTMLImageElement).apply {
                crossOrigin = "anonymous"
                onload = {
                    try {
                        val canvas = document.createElement("canvas") as HTMLCanvasElement
                        canvas.width = 16
                        canvas.height = 16
                        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
                        ctx.drawImage(img, 0.0, 0.0, 16.0, 16.0)
                        val data = ctx.getImageData(0.0, 0.0, 16.0, 16.0).data
                        var r = 0L; var g = 0L; var b = 0L; var n = 0
                        var i = 0
                        while (i < data.length) {
                            r += data[i].toInt() and 0xFF
                            g += data[i + 1].toInt() and 0xFF
                            b += data[i + 2].toInt() and 0xFF
                            n++
                            i += 4
                        }
                        cont.resume(
                            if (n > 0) Color(r.toFloat() / n / 255f, g.toFloat() / n / 255f, b.toFloat() / n / 255f, 1f)
                            else Color(0xFF2A2A2E)
                        )
                    } catch (_: Exception) {
                        // Tainted canvas (no CORS headers) or missing 2d context.
                        cont.resume(Color(0xFF2A2A2E))
                    }
                }
                onerror = { _, _, _, _, _ ->
                    cont.resume(Color(0xFF2A2A2E))
                    null
                }
                src = url
            }
            cont.invokeOnCancellation { img.src = "" }
        }
    } catch (_: Exception) {
        Color(0xFF2A2A2E)
    }
}
