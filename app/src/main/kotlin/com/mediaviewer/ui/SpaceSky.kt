package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.mediaviewer.util.UiToggles
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The app-wide page background: a flat, dimmed version of the page's own
 * color (the profile color on the Hub, profiles, DMs, the composer…) with a
 * field of tiny, realistic, fixed-place stars over it — some twinkling —
 * and the occasional shooting star.
 *
 * Performance notes (this sits behind almost every page):
 *  - It's a leaf with its own graphics layer, so the per-tick redraw only
 *    re-records this one small display list (a rect + ~150 dots), never the
 *    page on top of it. Pages that record their background into a
 *    [GlassBackdrop] just reference this layer, so the glass above blurs
 *    the stars without the page itself being re-recorded.
 *  - Twinkling is slow, so the sky only ticks ~20×/s; it switches to every
 *    frame only while a shooting star is actually crossing it.
 *  - The star layout is generated once per size and cached.
 *  - [SpaceSkyControl.paused] freezes every sky (VRM mode sets it).
 *  - Settings → "Starry Background" turns the stars off entirely, leaving
 *    just the dim color.
 */
object SpaceSkyControl {
    /** True while VRM mode is open: every sky stops ticking. */
    var paused by mutableStateOf(false)
}

/** How much of the page color the background keeps (0 = black). */
const val SPACE_BACKGROUND_DIM = 0.26f

fun dimSpaceColor(c: Color, dim: Float = SPACE_BACKGROUND_DIM): Color =
    Color(red = c.red * dim, green = c.green * dim, blue = c.blue * dim, alpha = 1f)

// Fixed seed: the same sky everywhere, so moving between pages feels like
// looking at one continuous night sky rather than a new random one.
private const val SKY_SEED = 0x5747_11A3L

private class StarLayout(
    val x: FloatArray, val y: FloatArray, val r: FloatArray,
    val alpha: FloatArray, val color: IntArray,
    /** 0 = steady; otherwise twinkle depth (0..1). */
    val twinkleDepth: FloatArray, val twinkleSpeed: FloatArray, val twinklePhase: FloatArray
) {
    val count get() = x.size
}

private val STAR_COLORS = intArrayOf(
    0xFFFFFFFF.toInt(),  // white
    0xFFD9E6FF.toInt(),  // blue-white
    0xFFFFEFD6.toInt()   // warm
)

private fun buildStars(size: Size, density: Float): StarLayout {
    val rnd = Random(SKY_SEED)
    val areaDp = (size.width / density) * (size.height / density)
    val n = (areaDp / 1900f).toInt().coerceIn(30, 700)
    val x = FloatArray(n); val y = FloatArray(n); val r = FloatArray(n); val a = FloatArray(n)
    val col = IntArray(n); val td = FloatArray(n); val ts = FloatArray(n); val tp = FloatArray(n)
    for (i in 0 until n) {
        x[i] = rnd.nextFloat() * size.width
        y[i] = rnd.nextFloat() * size.height
        // Mostly pin-pricks, a few brighter ones — like a real sky.
        val s = rnd.nextFloat()
        r[i] = (0.28f + 0.95f * s * s * s) * density
        a[i] = 0.22f + 0.73f * rnd.nextFloat().pow(1.7f)
        val c = rnd.nextFloat()
        col[i] = when { c < 0.70f -> STAR_COLORS[0]; c < 0.88f -> STAR_COLORS[1]; else -> STAR_COLORS[2] }
        if (rnd.nextFloat() < 0.3f) {
            td[i] = 0.35f + 0.55f * rnd.nextFloat()
            ts[i] = (0.25f + 0.9f * rnd.nextFloat()) * 2f * PI.toFloat()
            tp[i] = rnd.nextFloat() * 2f * PI.toFloat()
        }
    }
    return StarLayout(x, y, r, a, col, td, ts, tp)
}

/** One surface's shooting-star schedule. Positions are normalised (0..1)
 *  so the clock can advance it without knowing the surface's size. */
private class ShootingStars(seed: Long) {
    private val rnd = Random(seed)
    var nextStart = 3f + rnd.nextFloat() * 9f
    var start = -1f
    var duration = 0f
    var x0 = 0f; var y0 = 0f
    /** Travel, in dp, over the whole streak. */
    var travelDp = 0f
    var tailDp = 0f
    var dirX = 0f; var dirY = 0f

    fun advance(t: Float) {
        if (start >= 0f && t <= start + duration) return
        if (t >= nextStart) {
            start = t
            duration = 0.55f + rnd.nextFloat() * 0.45f
            x0 = 0.1f + rnd.nextFloat() * 0.8f
            y0 = 0.04f + rnd.nextFloat() * 0.5f
            val angle = (15f + rnd.nextFloat() * 25f) * (PI.toFloat() / 180f)
            val sign = if (rnd.nextBoolean()) 1f else -1f
            dirX = cos(angle) * sign; dirY = sin(angle)
            travelDp = 220f + rnd.nextFloat() * 220f
            tailDp = 70f + rnd.nextFloat() * 80f
            nextStart = start + duration + 7f + rnd.nextFloat() * 17f
        } else start = -1f
    }

    fun activeOrImminent(t: Float) = (start >= 0f && t <= start + duration) || t >= nextStart - 0.05f
}

/** [bottomColor]: a second color the background fades into toward the
 *  bottom (a DM thread: theirs at the top, yours at the bottom). */
@Composable
fun SpaceSky(color: Color, modifier: Modifier = Modifier, dim: Float = SPACE_BACKGROUND_DIM, bottomColor: Color? = null) {
    val base = remember(color, dim) { dimSpaceColor(color, dim) }
    val baseBottom = remember(bottomColor, dim) { bottomColor?.let { dimSpaceColor(it, dim) } }
    if (!UiToggles.starryBackground) {
        Box(if (baseBottom != null) modifier.background(Brush.verticalGradient(listOf(base, baseBottom))) else modifier.background(base))
        return
    }
    val shooting = remember { ShootingStars(System.nanoTime()) }
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(shooting) {
        val t0 = System.nanoTime()
        while (true) {
            if (SpaceSkyControl.paused) { delay(400); continue }
            val t = (System.nanoTime() - t0) / 1_000_000_000f
            shooting.advance(t)
            if (shooting.activeOrImminent(t)) withFrameNanos { } else delay(50)
            time.floatValue = (System.nanoTime() - t0) / 1_000_000_000f
        }
    }
    Box(
        modifier
            // Own layer: see the performance notes above.
            .graphicsLayer { }
            .drawWithCache {
                val stars = buildStars(size, density)
                val fill = baseBottom?.let { Brush.verticalGradient(listOf(base, it)) }
                onDrawBehind {
                    if (fill != null) drawRect(fill) else drawRect(base)
                    drawStars(stars, time.floatValue)
                    drawShootingStar(shooting, time.floatValue)
                }
            }
    )
}

private fun DrawScope.drawStars(s: StarLayout, t: Float) {
    for (i in 0 until s.count) {
        var a = s.alpha[i]
        val depth = s.twinkleDepth[i]
        if (depth > 0f) {
            // Two slightly detuned waves read as atmospheric scintillation
            // rather than a mechanical blink.
            val w = 0.5f + 0.35f * sin(t * s.twinkleSpeed[i] + s.twinklePhase[i]) +
                0.15f * sin(t * s.twinkleSpeed[i] * 2.3f + s.twinklePhase[i] * 1.7f)
            a *= (1f - depth * w).coerceIn(0.05f, 1f)
        }
        val c = Color(s.color[i])
        val center = Offset(s.x[i], s.y[i])
        val r = s.r[i]
        if (r > 0.95f * density) {
            // The brighter stars get a faint halo.
            drawCircle(c.copy(alpha = a * 0.13f), radius = r * 3.4f, center = center)
        }
        drawCircle(c.copy(alpha = a), radius = r, center = center)
    }
}

private fun DrawScope.drawShootingStar(s: ShootingStars, t: Float) {
    if (s.start < 0f) return
    val p = (t - s.start) / s.duration
    if (p < 0f || p > 1f) return
    val envelope = sqrt(sin(PI.toFloat() * p).coerceAtLeast(0f))
    val travel = s.travelDp * density
    val head = Offset(s.x0 * size.width + s.dirX * travel * p, s.y0 * size.height + s.dirY * travel * p)
    // The tail grows in, then shrinks as the meteor burns out.
    val tailLen = s.tailDp * density * (0.35f + 0.65f * envelope)
    val tail = Offset(head.x - s.dirX * tailLen, head.y - s.dirY * tailLen)
    drawLine(
        brush = Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0f), Color(0xFFE6EEFF).copy(alpha = 0.55f * envelope), Color.White.copy(alpha = 0.95f * envelope)),
            start = tail, end = head
        ),
        start = tail, end = head, strokeWidth = 1.3f * density, cap = StrokeCap.Round
    )
    drawCircle(Color.White.copy(alpha = 0.25f * envelope), radius = 3.2f * density, center = head)
    drawCircle(Color.White.copy(alpha = envelope), radius = 1.1f * density, center = head)
}
