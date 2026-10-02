package com.mediaviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** A little celebration a DM can set off. */
enum class DmEffect { CONFETTI, SNOW, FIREWORKS, BATS }

/** The effect [text] sets off, if it says one of the magic phrases
 *  (any capitalisation). */
fun dmEffectFor(text: String): DmEffect? {
    val t = text.lowercase()
    return when {
        t.contains("happy birthday") -> DmEffect.CONFETTI
        t.contains("merry christmas") -> DmEffect.SNOW
        t.contains("happy new year") -> DmEffect.FIREWORKS
        t.contains("happy halloween") -> DmEffect.BATS
        else -> null
    }
}

/**
 * Plays [effect] once over the chat for each new [playKey]. Everything is
 * drawn from scratch with shapes and a fresh random seed each time — no
 * images, no borrowed artwork. Touches pass straight through.
 */
@Composable
fun DmEffectLayer(effect: DmEffect, playKey: Int, modifier: Modifier = Modifier) {
    if (effect == DmEffect.CONFETTI) {
        // The same confetti as a supporter's profile.
        SupporterConfetti(playKey = playKey, modifier = modifier.fillMaxSize())
        return
    }
    val duration = when (effect) {
        DmEffect.SNOW -> 11f
        DmEffect.FIREWORKS -> 5.6f
        else -> 5.4f
    }
    var time by remember(playKey) { mutableFloatStateOf(0f) }
    var done by remember(playKey) { androidx.compose.runtime.mutableStateOf(false) }
    val seed = remember(playKey) { com.mediaviewer.platform.nanoTime() xor (playKey.toLong() shl 20) }
    LaunchedEffect(playKey) {
        val start = withFrameNanos { it }
        while (time < duration) {
            withFrameNanos { now -> time = (now - start) / 1_000_000_000f }
        }
        done = true
    }
    if (done) return
    Canvas(modifier.fillMaxSize()) {
        when (effect) {
            DmEffect.SNOW -> drawSnow(time, seed)
            DmEffect.FIREWORKS -> drawFireworks(time, seed)
            else -> drawBats(time, seed)
        }
    }
}

/** Snow: flakes drift down slowly, a few at first, then thinning out. */
private fun DrawScope.drawSnow(t: Float, seed: Long) {
    val rnd = Random(seed)
    val unit = size.width / 400f
    repeat(80) {
        val x0 = rnd.nextFloat()
        val delay = rnd.nextFloat() * 4.2f
        val fall = 4.5f + rnd.nextFloat() * 2.3f
        val sway = (8f + rnd.nextFloat() * 22f) * unit
        val freq = 0.5f + rnd.nextFloat() * 1.1f
        val phase = rnd.nextFloat() * 6.28f
        val r = (1.4f + rnd.nextFloat() * 2.6f) * unit
        val p = (t - delay) / fall
        if (p in 0f..1f) {
            val x = x0 * size.width + sin(t * freq + phase) * sway
            val y = -10f * unit + p * (size.height + 20f * unit)
            // Fades in at the top and out near the bottom.
            val a = (p * 6f).coerceAtMost(1f) * ((1f - p) * 5f).coerceAtMost(1f)
            drawCircle(Color.White.copy(alpha = 0.85f * a), radius = r, center = Offset(x, y))
            drawCircle(Color.White.copy(alpha = 0.18f * a), radius = r * 2.2f, center = Offset(x, y))
        }
    }
}

/** Fireworks: rockets climb from the bottom and burst into falling sparks. */
private fun DrawScope.drawFireworks(t: Float, seed: Long) {
    val rnd = Random(seed)
    val unit = size.width / 400f
    val palette = listOf(
        Color(0xFFFF4FA1), Color(0xFFFFD166), Color(0xFF4FC3F7), Color(0xFF7CFFB2), Color(0xFFB388FF), Color(0xFFFF8A65)
    )
    repeat(7) { b ->
        val launch = b * 0.5f + rnd.nextFloat() * 0.3f
        val x = (0.14f + rnd.nextFloat() * 0.72f) * size.width
        val peak = (0.16f + rnd.nextFloat() * 0.3f) * size.height
        val color = palette[rnd.nextInt(palette.size)]
        val second = palette[rnd.nextInt(palette.size)]
        val rise = 0.75f
        val power = (95f + rnd.nextFloat() * 70f) * unit
        val sparks = 34
        // (Every spark's direction is drawn from the seed even while the
        // rocket is still climbing, so the sequence stays the same each frame.)
        val angles = FloatArray(sparks) { rnd.nextFloat() * 6.2832f }
        val speeds = FloatArray(sparks) { 0.45f + rnd.nextFloat() * 0.55f }
        val local = t - launch
        if (local < 0f) return@repeat
        if (local < rise) {
            // Climbing: eases out as it nears the top, with a short tail.
            val p = local / rise
            val e = 1f - (1f - p) * (1f - p)
            val y = size.height + (peak - size.height) * e
            drawLine(
                Color.White.copy(alpha = 0.55f), Offset(x, y + 26f * unit * (1f - p)), Offset(x, y),
                strokeWidth = 2.2f * unit
            )
            drawCircle(Color.White, radius = 2.4f * unit, center = Offset(x, y))
        } else {
            val tau = local - rise
            val life = 1.7f
            if (tau < life) {
                val fade = (1f - tau / life)
                // Fast at first, slowing with drag; gravity pulls them down.
                val reach = (1f - exp(-3.2f * tau)) / 3.2f * 3.2f
                val drop = 46f * unit * tau * tau
                for (i in 0 until sparks) {
                    val d = power * speeds[i] * reach
                    val px = x + cos(angles[i]) * d
                    val py = peak + sin(angles[i]) * d + drop
                    val c = if (i % 3 == 0) second else color
                    drawCircle(c.copy(alpha = fade), radius = (1.2f + 1.6f * fade) * unit, center = Offset(px, py))
                }
                // The flash at the moment it bursts.
                if (tau < 0.18f) drawCircle(Color.White.copy(alpha = (1f - tau / 0.18f) * 0.7f), radius = 30f * unit * (tau / 0.18f + 0.3f), center = Offset(x, peak))
            }
        }
    }
}

/** Bats: a small flock flaps across the screen, each on its own wavy path. */
private fun DrawScope.drawBats(t: Float, seed: Long) {
    val rnd = Random(seed)
    val unit = size.width / 400f
    repeat(10) {
        val delay = rnd.nextFloat() * 1.7f
        val cross = 2.3f + rnd.nextFloat() * 1.3f
        val leftToRight = rnd.nextBoolean()
        val y0 = (0.1f + rnd.nextFloat() * 0.6f) * size.height
        val wobble = (14f + rnd.nextFloat() * 26f) * unit
        val wobbleFreq = 2f + rnd.nextFloat() * 2.5f
        val scale = (0.7f + rnd.nextFloat() * 0.9f) * unit
        val flapFreq = 9f + rnd.nextFloat() * 5f
        val phase = rnd.nextFloat() * 6.28f
        val p = (t - delay) / cross
        if (p in 0f..1f) {
            val span = size.width + 120f * unit
            val x = if (leftToRight) -60f * unit + p * span else size.width + 60f * unit - p * span
            val y = y0 + sin(t * wobbleFreq + phase) * wobble - p * 30f * unit
            // Wings beat up and down.
            val flap = sin(t * flapFreq + phase)
            drawBat(Offset(x, y), scale, flap, if (leftToRight) 1f else -1f)
        }
    }
}

private fun DrawScope.drawBat(c: Offset, s: Float, flap: Float, dir: Float) {
    val body = Color(0xFF1B1026)
    val edge = Color(0xFFB388FF).copy(alpha = 0.55f)
    val tipY = -9f * s * flap          // wing tips rise and fall
    val path = Path().apply {
        // Head and ears.
        moveTo(c.x - 3f * s, c.y - 5f * s)
        lineTo(c.x - 2.2f * s, c.y - 9f * s)
        lineTo(c.x, c.y - 6f * s)
        lineTo(c.x + 2.2f * s, c.y - 9f * s)
        lineTo(c.x + 3f * s, c.y - 5f * s)
        // Right wing: out to the tip, then the scalloped trailing edge back in.
        quadraticTo(c.x + 12f * s, c.y - 8f * s + tipY * 0.6f, c.x + 22f * s, c.y - 2f * s + tipY)
        quadraticTo(c.x + 17f * s, c.y + 1f * s + tipY * 0.6f, c.x + 14f * s, c.y + 5f * s + tipY * 0.5f)
        quadraticTo(c.x + 10f * s, c.y + 1f * s + tipY * 0.3f, c.x + 7f * s, c.y + 5f * s + tipY * 0.2f)
        quadraticTo(c.x + 4f * s, c.y + 2f * s, c.x + 2.5f * s, c.y + 6f * s)
        // Tail.
        lineTo(c.x, c.y + 8f * s)
        lineTo(c.x - 2.5f * s, c.y + 6f * s)
        // Left wing, mirrored.
        quadraticTo(c.x - 4f * s, c.y + 2f * s, c.x - 7f * s, c.y + 5f * s + tipY * 0.2f)
        quadraticTo(c.x - 10f * s, c.y + 1f * s + tipY * 0.3f, c.x - 14f * s, c.y + 5f * s + tipY * 0.5f)
        quadraticTo(c.x - 17f * s, c.y + 1f * s + tipY * 0.6f, c.x - 22f * s, c.y - 2f * s + tipY)
        quadraticTo(c.x - 12f * s, c.y - 8f * s + tipY * 0.6f, c.x - 3f * s, c.y - 5f * s)
        close()
    }
    drawPath(path, body)
    drawPath(path, edge, style = Stroke(width = 0.9f * s))
    // Two tiny eyes, looking the way it's flying.
    drawCircle(Color(0xFFFFD166), radius = 0.7f * s, center = Offset(c.x - 1.2f * s + dir * 0.4f * s, c.y - 4.6f * s))
    drawCircle(Color(0xFFFFD166), radius = 0.7f * s, center = Offset(c.x + 1.2f * s + dir * 0.4f * s, c.y - 4.6f * s))
}
