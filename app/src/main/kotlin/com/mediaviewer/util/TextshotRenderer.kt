package com.mediaviewer.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Renders Textshot mode's final post image — black background, white text.
 * ComposePostScreen's live preview calls this exact same function, so the
 * preview and the uploaded image can never drift apart.
 *
 * Fit (per feedback): the text's TIGHT INK bounds — the actual glyph pixels,
 * not the line boxes — are grown to the largest size that fits inside the
 * frame minus a small (~6%) pad. A single "R" therefore fills the square
 * edge-to-edge, and long text (even with a very long unbroken word, which
 * the simple break strategy wraps mid-word rather than shrinking the whole
 * block to fit it on one line) grows until it fills the frame. Whole words
 * still wrap whole; only a word longer than a full line ever splits. No
 * hyphenation. The ink is centered on both axes.
 */
object TextshotRenderer {
    private const val PAD_FRACTION = 0.06f
    private const val LINE_SPACING_MULT = 1.15f
    // Floor so pathologically long input never rounds to an invisible size.
    private const val MIN_TEXT_SIZE = 20f

    fun render(text: String, sizePx: Int = 1080): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        if (text.isBlank()) return bitmap

        val pad = sizePx * PAD_FRACTION
        val maxW = sizePx - 2 * pad
        val maxH = sizePx - 2 * pad

        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.WHITE
        }
        val lineBounds = Rect()

        fun buildLayout(): StaticLayout =
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, maxW.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, LINE_SPACING_MULT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

        /** Tight ink rect of [layout], in layout coordinates. */
        fun inkOf(layout: StaticLayout): Rect {
            val out = Rect()
            for (i in 0 until layout.lineCount) {
                val start = layout.getLineStart(i)
                val end = layout.getLineEnd(i)
                if (start >= end) continue
                paint.getTextBounds(text, start, end, lineBounds)
                if (lineBounds.isEmpty) continue
                val left = (layout.getLineLeft(i) + lineBounds.left).toInt()
                val top = (layout.getLineBaseline(i) + lineBounds.top).toInt()
                val right = (layout.getLineLeft(i) + lineBounds.right).toInt()
                val bottom = (layout.getLineBaseline(i) + lineBounds.bottom).toInt()
                if (out.isEmpty) out.set(left, top, right, bottom)
                else out.union(left, top, right, bottom)
            }
            return out
        }

        fun fits(size: Float): Boolean {
            paint.textSize = size
            val r = inkOf(buildLayout())
            return !r.isEmpty && r.width() <= maxW && r.height() <= maxH
        }

        // Grow the ceiling by doubling until the ink overflows (bounded), so
        // short text can reach edge-to-edge sizes far above the frame width.
        var lo = MIN_TEXT_SIZE
        var hi = MIN_TEXT_SIZE
        var guard = 0
        while (guard++ < 16) {
            val probe = hi * 2f
            if (probe > sizePx * 4f) break
            if (!fits(probe)) break
            hi = probe
        }
        // Binary-search the largest fitting size.
        repeat(18) {
            val mid = (lo + hi) / 2f
            if (fits(mid)) lo = mid else hi = mid
        }

        paint.textSize = lo
        val layout = buildLayout()
        val ink = inkOf(layout)
        if (ink.isEmpty) return bitmap

        canvas.save()
        canvas.translate(
            (sizePx - ink.width()) / 2f - ink.left,
            (sizePx - ink.height()) / 2f - ink.top
        )
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}
