package com.mediaviewer.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Renders textshot lines to PNG bytes.
 *
 * Ported from legacy util/TextshotRenderer.kt: transparent background,
 * white text, shrinking font size as the text grows so everything stays
 * visible once posted — and always a fixed, true 1:1 square (only the font
 * size adapts, never the canvas dimensions).
 *
 * Two adaptations for the shared contract: the canvas size comes from
 * [widthPx] (legacy hardcoded 1080 — constants scale proportionally, so
 * widthPx=1080 reproduces the legacy output exactly), and each
 * [TextshotLine] carries its own bold flag, applied via [StyleSpan] on a
 * [SpannableStringBuilder] (legacy only ever rendered one plain string).
 */
actual fun renderTextshot(lines: List<TextshotLine>, widthPx: Int): ByteArray {
    val bitmap = TextshotRenderer.render(lines, widthPx.coerceAtLeast(1))
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    return out.toByteArray()
}

internal object TextshotRenderer {
    // Legacy constants, defined at the 1080px reference size; render() scales
    // them by widthPx/1080 so other sizes keep the same proportions.
    private const val REF_SIZE = 1080
    private const val PADDING = 56
    private const val MAX_TEXT_SIZE = 108f
    private const val MIN_TEXT_SIZE = 28f

    fun render(lines: List<TextshotLine>, widthPx: Int): Bitmap {
        val scale = widthPx / REF_SIZE.toFloat()
        val padding = (PADDING * scale).toInt()
        val maxWidth = widthPx - padding * 2
        val maxLayoutHeight = widthPx - padding * 2

        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.WHITE
        }

        val spannable = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            val start = spannable.length
            spannable.append(line.text)
            if (line.isBold) {
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD), start, spannable.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (index < lines.lastIndex) spannable.append("\n")
        }

        var textSizePx = MAX_TEXT_SIZE * scale
        val minTextSizePx = MIN_TEXT_SIZE * scale
        var layout = buildLayout(spannable, paint, textSizePx, maxWidth)
        while (layout.height > maxLayoutHeight && textSizePx > minTextSizePx) {
            textSizePx -= 4f * scale
            layout = buildLayout(spannable, paint, textSizePx, maxWidth)
        }

        val bitmap = Bitmap.createBitmap(widthPx, widthPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap) // left fully transparent behind the text
        canvas.save()
        // Center the text block vertically within the square (it may be
        // shorter than the full height even after the shrink loop above),
        // while still starting flush against the padding horizontally.
        val verticalOffset = padding + ((maxLayoutHeight - layout.height) / 2f).coerceAtLeast(0f)
        canvas.translate(padding.toFloat(), verticalOffset)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    private fun buildLayout(
        text: CharSequence,
        paint: TextPaint,
        textSizePx: Float,
        maxWidth: Int
    ): StaticLayout {
        paint.textSize = textSizePx
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.1f)
            .build()
    }
}

/**
 * Encodes decoded image frames (PNG/JPEG bytes) into an animated GIF,
 * decoding each frame via BitmapFactory like the legacy GifDownloadWorker
 * did. Mirrors the legacy quality split: a single frame gets the
 * exhaustive high-quality palette pass (the old encodeImageAsGif path),
 * while multi-frame encodes use the faster cap (the old encodeVideoAsGif
 * path) to keep them tractable.
 */
actual suspend fun encodeGif(frames: List<ByteArray>, delayMs: Int): ByteArray =
    withContext(Dispatchers.Default) {
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out)
        encoder.setDelay(delayMs)
        encoder.start()
        frames.forEach { bytes ->
            val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("Decode failed")
            try {
                encoder.addFrame(bitmap, highQuality = frames.size == 1)
            } finally {
                bitmap.recycle()
            }
        }
        encoder.finish()
        out.toByteArray()
    }
