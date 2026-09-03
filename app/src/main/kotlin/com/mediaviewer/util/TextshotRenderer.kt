package com.mediaviewer.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Renders Textshot mode's final post image — transparent background, white
 * text, shrinking font size as the text grows so everything stays visible
 * once posted. Mirrors the shrink-to-fit layout ComposePostScreen's live
 * preview uses (see TextshotPreview there), just at real upload resolution
 * instead of a small on-screen preview.
 */
object TextshotRenderer {
    private const val SIZE = 1080
    private const val PADDING = 80

    fun render(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap) // left fully transparent behind the text
        val maxWidth = SIZE - PADDING * 2
        val maxHeight = SIZE - PADDING * 2

        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.WHITE
        }

        var textSizePx = 120f
        var layout = buildLayout(text, paint, textSizePx, maxWidth)
        while (layout.height > maxHeight && textSizePx > 28f) {
            textSizePx -= 6f
            layout = buildLayout(text, paint, textSizePx, maxWidth)
        }

        val verticalOffset = ((maxHeight - layout.height).coerceAtLeast(0)) / 2f
        canvas.save()
        canvas.translate(PADDING.toFloat(), PADDING + verticalOffset)
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
