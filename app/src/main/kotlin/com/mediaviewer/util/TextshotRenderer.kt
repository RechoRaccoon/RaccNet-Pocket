package com.mediaviewer.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Renders Textshot mode's final post image — transparent background, white
 * text, shrinking font size as the text grows so everything stays visible
 * once posted. ComposePostScreen's live preview (see TextshotPreview there)
 * calls this exact same function to build what it shows on screen, so the
 * preview and the actual uploaded image can never drift apart (item 9) —
 * there's only one rendering path.
 *
 * Bug fix: the final posted image is always a fixed, true 1:1 square — the
 * canvas dimensions never change with how much text there is. Only the font
 * size adapts: it shrinks (down to [MIN_TEXT_SIZE]) just enough to keep the
 * text within the square's [PADDING] margins, so short posts still read
 * edge-to-edge instead of leaving a lot of empty canvas around them, and
 * long posts shrink instead of overflowing. Because ComposePostScreen's
 * live preview (see TextshotPreview there) calls this exact same function,
 * keeping the canvas square here is what keeps that preview an accurate
 * square match for the real upload, instead of drifting into a tall
 * rectangle whenever the text happened to need more lines.
 */
object TextshotRenderer {
    private const val WIDTH = 1080
    private const val HEIGHT = 1080
    private const val PADDING = 56
    private const val MAX_TEXT_SIZE = 108f
    private const val MIN_TEXT_SIZE = 28f

    fun render(text: String): Bitmap {
        val maxWidth = WIDTH - PADDING * 2
        val maxLayoutHeight = HEIGHT - PADDING * 2

        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.WHITE
        }

        var textSizePx = MAX_TEXT_SIZE
        var layout = buildLayout(text, paint, textSizePx, maxWidth)
        while (layout.height > maxLayoutHeight && textSizePx > MIN_TEXT_SIZE) {
            textSizePx -= 4f
            layout = buildLayout(text, paint, textSizePx, maxWidth)
        }

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap) // left fully transparent behind the text
        canvas.save()
        // Center the text block vertically within the square (it may be
        // shorter than the full height even after the shrink loop above),
        // while still starting flush against PADDING horizontally.
        val verticalOffset = PADDING + ((maxLayoutHeight - layout.height) / 2f).coerceAtLeast(0f)
        canvas.translate(PADDING.toFloat(), verticalOffset)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    private fun buildLayout(text: String, paint: TextPaint, textSizePx: Float, maxWidth: Int): StaticLayout {
        paint.textSize = textSizePx
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.1f)
            .build()
    }
}
