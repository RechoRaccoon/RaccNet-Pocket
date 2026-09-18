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
 * Item 10 rewrite: Textshot is edge-to-edge, auto-sized text, and it never
 * auto-wraps — the person's own newlines are the *only* thing that ever
 * creates a new line. The whole canvas is always a fixed 1:1 square (see the
 * older doc comment on that, still true), but sizing is now a genuine
 * "fit the content to the frame" scale rather than a fixed max-size-then-
 * shrink-only-if-too-tall loop:
 *  - Text is split solely on "\n" — [lines]. A StaticLayout is still used
 *    (for correct glyph shaping/metrics/centering), but built wide enough
 *    that it can never wrap on its own.
 *  - Two independent scale factors are computed at a large reference size:
 *    one that would make the *widest* line exactly span the available
 *    width, one that would make the *whole block* (all lines stacked)
 *    exactly span the available height. Whichever is smaller wins and is
 *    applied to the canvas itself (canvas.scale), so there's only one
 *    source of truth for "how big is the text" — no separate re-measure
 *    pass at a different size that could drift from what's drawn.
 *  - That's the entire spec, worked out directly from the scale rule: one
 *    glyph has comparable width/height scale ceilings, so it grows until it
 *    fills close to the whole square. A single long unbroken line has a
 *    tiny height (one line is nowhere near canvas-height-tall), so its
 *    height-scale ceiling is huge and the width-scale ceiling wins instead —
 *    the line runs edge to edge horizontally and sits vertically centered
 *    with room to spare, exactly because nothing forced it to also fill the
 *    height. Extra manual newlines shrink the height-scale ceiling as they
 *    stack up, so height eventually becomes the binding constraint once
 *    there are enough of them.
 */
object TextshotRenderer {
    private const val WIDTH = 1080
    private const val HEIGHT = 1080
    // Item 10: "edge to edge, with a very very small gap" — was 56 (~5% of
    // the frame each side).
    private const val PADDING = 16
    private const val REFERENCE_SIZE = 300f
    private const val LINE_SPACING_MULT = 1.05f
    // Not a visual ceiling like the old MAX/MIN_TEXT_SIZE — just a floor so
    // pathologically long single-line input never rounds all the way to an
    // invisible/zero-size draw.
    private const val MIN_TEXT_SIZE = 4f

    fun render(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap) // left fully transparent behind the text
        if (text.isEmpty()) return bitmap

        val maxWidth = (WIDTH - PADDING * 2).toFloat()
        val maxHeight = (HEIGHT - PADDING * 2).toFloat()

        // Item 10: ONLY the person's own explicit newlines ever start a new
        // line — Textshot never auto-wraps. A very wide single line stays a
        // single line and is handled purely by the shrink-to-fit-width
        // scale below, never by breaking it up on its own.
        val lines = text.split("\n")

        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = REFERENCE_SIZE
        }

        // Pass 1: measure each line's natural (reference-size) width so we
        // know how wide a layout to build in pass 2 — wide enough that no
        // line can ever hit StaticLayout's own auto-wrap.
        val maxLineWidthRef = lines.maxOf { paint.measureText(it) }.coerceAtLeast(1f)

        // Pass 2: the real layout, built at exactly the widest line's own
        // width (plus a hairline epsilon against float rounding) — this is
        // what makes ALIGN_CENTER center every shorter line relative to the
        // actual content's own width, not an arbitrary declared width, while
        // guaranteeing the layout itself never wraps.
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, kotlin.math.ceil(maxLineWidthRef).toInt() + 2)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, LINE_SPACING_MULT)
            .build()

        val blockHeightRef = layout.height.toFloat().coerceAtLeast(1f)

        val widthScale = maxWidth / maxLineWidthRef
        val heightScale = maxHeight / blockHeightRef
        val scale = minOf(widthScale, heightScale).coerceAtLeast(MIN_TEXT_SIZE / REFERENCE_SIZE)

        val scaledWidth = maxLineWidthRef * scale
        val scaledHeight = blockHeightRef * scale
        val offsetX = PADDING + ((maxWidth - scaledWidth) / 2f).coerceAtLeast(0f)
        val offsetY = PADDING + ((maxHeight - scaledHeight) / 2f).coerceAtLeast(0f)

        canvas.save()
        // Order matters: translate first, then scale, so a point p in the
        // layout's own (reference-size) coordinates lands at
        // (offset + scale * p) in real canvas pixels — see the class doc
        // comment above for why that's the fit-to-frame rule this whole
        // function implements.
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}
