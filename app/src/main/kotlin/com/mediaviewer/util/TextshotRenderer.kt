package com.mediaviewer.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

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
 *
 * Custom emoji (see [EmojiStore]): when an [emojiBitmap] resolver is given,
 * every emoji token character in the text is drawn as its picture, inline,
 * sized to the line like a normal emoji, and counted as ink for the fit —
 * so a Textshot of just one emoji fills the frame the same way an "R" does.
 * Emoji are also line-break opportunities, so a row of them wraps like text.
 */
object TextshotRenderer {
    /** Stand-in character the layout sees for an emoji (Object Replacement
     *  Character — the one line-breakers treat as a break opportunity). */
    private const val OBJ = '\uFFFC'

    private const val PAD_FRACTION = 0.06f
    private const val LINE_SPACING_MULT = 1.15f
    // Floor so pathologically long input never rounds to an invisible size.
    private const val MIN_TEXT_SIZE = 20f

    /** Draws one emoji picture inline, in a square as tall as the text's line
     *  (ascent to descent), aspect-fit and centered in it. */
    private class EmojiSpan(private val bitmap: Bitmap) : ReplacementSpan() {
        private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        private fun side(paint: Paint): Float {
            val fm = paint.fontMetrics
            return fm.descent - fm.ascent
        }

        /** Where the picture lands when the span starts at [x] on [baseline]. */
        fun rectAt(x: Float, baseline: Int, paint: Paint): RectF {
            val fm = paint.fontMetrics
            val box = side(paint)
            val scale = min(box / bitmap.width, box / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val cx = x + box / 2f
            val cy = baseline + (fm.ascent + fm.descent) / 2f
            return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            side(paint).roundToInt()

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            canvas.drawBitmap(bitmap, null, rectAt(x, y, paint), drawPaint)
        }
    }

    fun render(text: String, sizePx: Int = 1080, emojiBitmap: ((Char) -> Bitmap?)? = null): Bitmap {
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

        // Emoji tokens -> U+FFFC placeholders carrying an EmojiSpan. Indices
        // never shift (one char in, one char out), so [text] offsets stay valid.
        val chars = text.toCharArray()
        val spans = HashMap<Int, EmojiSpan>()
        if (emojiBitmap != null) {
            for (i in chars.indices) {
                if (!EmojiStore.isTokenChar(chars[i])) continue
                val bmp = emojiBitmap(chars[i]) ?: continue
                chars[i] = OBJ
                spans[i] = EmojiSpan(bmp)
            }
        }
        val plain = String(chars)
        val rendered: CharSequence =
            if (spans.isEmpty()) plain
            else SpannableStringBuilder(plain).also { sb ->
                for ((i, span) in spans) sb.setSpan(span, i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

        fun buildLayout(): StaticLayout =
            StaticLayout.Builder
                .obtain(rendered, 0, rendered.length, paint, maxW.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, LINE_SPACING_MULT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

        /** Tight ink rect of [layout], in layout coordinates: the glyph pixels
         *  of the text runs, plus each emoji picture's rect. */
        fun inkOf(layout: StaticLayout): Rect {
            val out = Rect()
            fun add(left: Int, top: Int, right: Int, bottom: Int) {
                if (out.isEmpty) out.set(left, top, right, bottom) else out.union(left, top, right, bottom)
            }
            for (i in 0 until layout.lineCount) {
                val start = layout.getLineStart(i)
                val end = layout.getLineEnd(i)
                if (start >= end) continue
                val baseline = layout.getLineBaseline(i)
                var runStart = start
                // Ink of the plain-text run [runStart, runEnd), positioned by
                // where the layout actually put its first character.
                fun flushRun(runEnd: Int) {
                    if (runEnd <= runStart) return
                    paint.getTextBounds(rendered, runStart, runEnd, lineBounds)
                    if (lineBounds.isEmpty) return
                    val x0 = layout.getPrimaryHorizontal(runStart)
                    add(
                        (x0 + lineBounds.left).toInt(), baseline + lineBounds.top,
                        (x0 + lineBounds.right).toInt(), baseline + lineBounds.bottom
                    )
                }
                for (k in start until end) {
                    val span = spans[k] ?: continue
                    flushRun(k)
                    val r = span.rectAt(layout.getPrimaryHorizontal(k), baseline, paint)
                    add(floor(r.left).toInt(), floor(r.top).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())
                    runStart = k + 1
                }
                flushRun(end)
            }
            return out
        }

        fun fits(size: Float): Boolean {
            paint.textSize = size
            val layout = buildLayout()
            // Never split a normal word mid-word: if the layout broke a word
            // of 25 chars or fewer across lines, this size is too big. Longer
            // tokens (pathological unbroken strings) are allowed to wrap
            // mid-word rather than shrinking the whole block tiny.
            for (i in 0 until layout.lineCount - 1) {
                val b = layout.getLineEnd(i)
                if (b <= 0 || b >= plain.length) continue
                // Breaking right next to an emoji is always fine.
                if (plain[b - 1] == OBJ || plain[b] == OBJ) continue
                if (!plain[b - 1].isWhitespace() && !plain[b].isWhitespace()) {
                    var s = b - 1
                    while (s > 0 && !plain[s - 1].isWhitespace()) s--
                    var e = b
                    while (e < plain.length && !plain[e].isWhitespace()) e++
                    if (e - s <= 25) return false
                }
            }
            val r = inkOf(layout)
            return !r.isEmpty && r.width() <= maxW && r.height() <= maxH
        }

        // Binary-search the largest fitting size, starting from a large
        // ceiling. (No doubling-up loop: fits() rejects sizes with
        // mid-word splits, and doubling would stop at the first such size
        // even when a smaller size fits fine — leaving text too small.)
        var lo = MIN_TEXT_SIZE
        var hi = sizePx * 2f
        repeat(24) {
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
