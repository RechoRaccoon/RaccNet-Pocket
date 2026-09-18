package com.mediaviewer.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Renders Textshot mode's final post image — transparent background, white
 * text. ComposePostScreen's live preview (see TextshotPreview there) calls
 * this exact same function to build what it shows on screen, so the preview
 * and the actual uploaded image can never drift apart (item 9) — there's
 * only one rendering path.
 *
 * Fix (per feedback): text is no longer one long horizontal line until the
 * person manually breaks it. The layout now word-wraps automatically at the
 * frame's full width (the person's own "\n" still force line breaks too),
 * and the text size is auto-fitted so the block fills the whole 1:1 square:
 *  - At a large reference size, a wrapping StaticLayout is built across the
 *    full available width. Two scale ceilings are computed from it: one
 *    that would make the *widest* wrapped line span exactly the available
 *    width (edge-to-edge), one that would make the *whole block* span
 *    exactly the available height. Whichever is smaller wins.
 *  - The layout is rebuilt at that fitted size (a bounded shrink loop
 *    guards against line-wrap shifts pushing the block back over the
 *    height), then drawn centered in the square.
 *  - Short text grows until its widest line runs edge to edge (or the block
 *    fills the height); long text shrinks until the whole block fits — so
 *    the square is always as full as the content allows, never overflowing.
 */
object TextshotRenderer {
    private const val WIDTH = 1080
    private const val HEIGHT = 1080
    // Item 10: "edge to edge, with a very very small gap" — was 56 (~5% of
    // the frame each side).
    private const val PADDING = 16
    private const val REFERENCE_SIZE = 300f
    private const val LINE_SPACING_MULT = 1.05f
    // Not a visual ceiling — just a floor so pathologically long input never
    // rounds all the way to an invisible/zero-size draw.
    private const val MIN_TEXT_SIZE = 4f

    fun render(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap) // left fully transparent behind the text
        if (text.isEmpty()) return bitmap

        val maxWidth = (WIDTH - PADDING * 2).toFloat()
        val maxHeight = (HEIGHT - PADDING * 2).toFloat()

        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.WHITE
        }

        // Builds a word-wrapping layout at the paint's current text size —
        // StaticLayout breaks lines automatically at maxWidth, so text is
        // never one long horizontal line; the person's own "\n" still force
        // breaks on top of that.
        fun buildLayout(): StaticLayout =
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, maxWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, LINE_SPACING_MULT)
                .build()

        // Measure at the reference size, then take the smaller of the two
        // fit ceilings: widest wrapped line -> full width (edge-to-edge),
        // whole block -> full height (fills the square).
        paint.textSize = REFERENCE_SIZE
        val ref = buildLayout()
        val widestLine = (0 until ref.lineCount).maxOf { ref.getLineWidth(it) }.coerceAtLeast(1f)
        val blockHeight = ref.height.toFloat().coerceAtLeast(1f)
        val scale = minOf(maxWidth / widestLine, maxHeight / blockHeight)
            .coerceAtLeast(MIN_TEXT_SIZE / REFERENCE_SIZE)

        // Rebuild at the fitted size; if wrap shifts push the block back
        // over the height, shrink until it fits (bounded — always ends).
        var textSize = (REFERENCE_SIZE * scale).coerceAtLeast(MIN_TEXT_SIZE)
        var layout: StaticLayout
        var guard = 0
        while (true) {
            paint.textSize = textSize
            layout = buildLayout()
            if (layout.height <= maxHeight || textSize <= MIN_TEXT_SIZE || guard++ >= 8) break
            textSize *= 0.92f
        }

        val drawnWidth = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        val drawnHeight = layout.height.toFloat()
        val offsetX = PADDING + ((maxWidth - drawnWidth) / 2f).coerceAtLeast(0f)
        val offsetY = PADDING + ((maxHeight - drawnHeight) / 2f).coerceAtLeast(0f)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}
