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
 * Item 8: the old version always produced a fixed 1080x1080 square, which
 * left a lot of empty canvas around anything short enough not to need every
 * pixel of that square. The image height now hugs the text instead — only
 * [PADDING] worth of margin on every side — so the text reads edge to edge
 * regardless of how much or little of it there is. [MAX_HEIGHT] just keeps a
 * pathologically long post from producing an unreasonably tall image; it
 * shrinks the font all the way down to [MIN_TEXT_SIZE] first, and only caps
 * the canvas (letting the last bit of text run off the bottom) if it truly
 * still doesn't fit even then.
 */
object TextshotRenderer {
    private const val WIDTH = 1080
    private const val PADDING = 56
    private const val MAX_TEXT_SIZE = 108f
    private const val MIN_TEXT_SIZE = 28f
    private const val MAX_HEIGHT = 1920

    fun render(text: String): Bitmap {
        val maxWidth = WIDTH - PADDING * 2
        val maxLayoutHeight = MAX_HEIGHT - PADDING * 2

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

        val height = (layout.height + PADDING * 2).coerceIn(PADDING * 2 + 1, MAX_HEIGHT)
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap) // left fully transparent behind the text
        canvas.save()
        canvas.translate(PADDING.toFloat(), PADDING.toFloat())
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
