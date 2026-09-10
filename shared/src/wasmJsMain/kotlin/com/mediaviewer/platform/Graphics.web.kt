package com.mediaviewer.platform

import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.asDynamic

/**
 * Renders textshot lines to PNG bytes via the canvas 2D API — best effort.
 *
 * Mirrors the Android renderer: a true 1:1 square, transparent background,
 * white centered text, font shrinking (108 -> 28 at the 1080px reference
 * size, scaled) until the block fits inside the padding margins, then
 * vertically centered. Canvas has no StaticLayout equivalent, so lines are
 * laid out manually with measureText().
 *
 * Synchronous by necessity: the contract returns [ByteArray] (not
 * suspend), so this uses the synchronous toDataURL() rather than the
 * async toBlob().
 */
@OptIn(ExperimentalEncodingApi::class)
actual fun renderTextshot(lines: List<TextshotLine>, widthPx: Int): ByteArray {
    val w = widthPx.coerceAtLeast(1)
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = w
    canvas.height = w
    val ctx = canvas.getContext("2d") as? CanvasRenderingContext2D
        ?: error("PORT: canvas 2d context unavailable")

    val scale = w / 1080.0
    val padding = 56.0 * scale
    val maxTextWidth = w - padding * 2
    val maxTextHeight = w - padding * 2

    fun fontFor(line: TextshotLine, sizePx: Double): String =
        "${if (line.isBold) "bold " else ""}${sizePx}px sans-serif"

    // Shrink-to-fit loop, mirroring the Android version's constants.
    var fontSize = 108.0 * scale
    val minFontSize = 28.0 * scale
    while (fontSize > minFontSize) {
        var widest = 0.0
        for (line in lines) {
            ctx.font = fontFor(line, fontSize)
            widest = maxOf(widest, ctx.measureText(line.text).width)
        }
        val blockHeight = fontSize * 1.1 * lines.size
        if (widest <= maxTextWidth && blockHeight <= maxTextHeight) break
        fontSize -= 4.0 * scale
    }

    // Use dynamic interop for canvas style properties: the typed bindings
    // type these as JsAny? and the CanvasTextAlign/Baseline enums don't
    // exist in this build.
    val dctx = ctx.asDynamic()
    dctx.fillStyle = "white"
    dctx.textAlign = "center"
    dctx.textBaseline = "middle"
    val lineHeight = fontSize * 1.1
    val blockHeight = lineHeight * lines.size
    // Vertically center the block (it may be shorter than the full height
    // even after the shrink loop), starting flush against the padding.
    var y = padding + (maxTextHeight - blockHeight) / 2 + lineHeight / 2
    for (line in lines) {
        ctx.font = fontFor(line, fontSize)
        ctx.fillText(line.text, w / 2.0, y)
        y += lineHeight
    }

    val dataUrl = canvas.toDataURL("image/png")
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isEmpty()) error("PORT: toDataURL did not return PNG data")
    return Base64.Default.decode(base64)
}

/**
 * Animated GIF encoding has no web equivalent in this codebase (the Android
 * build carries a hand-rolled GIF89a/NeuQuant encoder). Callers must handle
 * the failure — e.g. fall back to downloading the source video.
 */
actual suspend fun encodeGif(frames: List<ByteArray>, delayMs: Int): ByteArray =
    TODO("PORT: no GIF pipeline on web")
