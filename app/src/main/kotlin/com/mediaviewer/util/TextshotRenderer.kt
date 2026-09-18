package com.mediaviewer.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Renders Textshot mode's final post image — black background, white text.
 * ComposePostScreen's live preview (see TextshotPreview there) calls this
 * exact same function to build what it shows on screen, so the preview and
 * the actual uploaded image can never drift apart — there's only one
 * rendering path.
 *
 * Fix (per feedback): the old fit math mis-centered single characters and
 * could split a word's letters across rows (visible in the screenshots:
 * text not edge-to-edge/centered, single letters on their own rows). The
 * renderer now:
 *  - wraps by WHOLE WORDS only — a pre-pass shrinks the size ceiling until
 *    the longest whitespace-delimited word fits the line width, so no
 *    word's letters can ever be split across rows;
 *  - fills the square edge-to-edge — binary-searches the largest text size
 *    whose wrapped layout fits inside the frame minus a small (~6%) pad;
 *  - centers on both axes with a plain canvas.translate + layout.draw —
 *    no pivot-scale tricks, so single characters land dead-center too.
 */
object TextshotRenderer {
    // Item 10: "edge to edge, with a very very small gap" — ~6% of the
    // frame each side.
    private const val PAD_FRACTION = 0.06f
    private const val LINE_SPACING_MULT = 1.15f
    // Not a visual ceiling — just a floor so pathologically long input never
    // rounds all the way to an invisible/zero-size draw.
    private const val MIN_TEXT_SIZE = 20f

    fun render(text: String, sizePx: Int = 1080): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        // Blank text would crash StaticLayout — render a single space.
        val safeText = if (text.isBlank()) " " else text

        val pad = sizePx * PAD_FRACTION
        val maxWidth = sizePx - 2 * pad
        val maxHeight = sizePx - 2 * pad

        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.WHITE
        }

        fun buildLayout(): StaticLayout =
            StaticLayout.Builder
                .obtain(safeText, 0, safeText.length, paint, maxWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, LINE_SPACING_MULT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

        fun longestWordWidth(): Float =
            safeText.split("\\s+".toRegex())
                .maxOfOrNull { paint.measureText(it) } ?: 0f

        // Pre-pass: shrink the ceiling until even the longest single word
        // fits the line width — with BREAK_STRATEGY_SIMPLE and no
        // hyphenation, no word's letters can then be split across rows.
        var hi = sizePx * 0.5f
        paint.textSize = hi
        while (hi > MIN_TEXT_SIZE && longestWordWidth() > maxWidth) {
            hi *= 0.9f
            paint.textSize = hi
        }

        // Binary-search the largest size whose wrapped block fits the frame.
        var lo = MIN_TEXT_SIZE
        repeat(20) {
            val mid = (lo + hi) / 2f
            paint.textSize = mid
            if (buildLayout().height <= maxHeight) lo = mid else hi = mid
        }
        paint.textSize = lo
        val layout = buildLayout()

        canvas.save()
        canvas.translate((sizePx - maxWidth) / 2f, (sizePx - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}
