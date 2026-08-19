package com.mediaviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** State machine phases for [PixelTransitionController]. HIDDEN renders
 *  nothing at all — [PixelMatrixOverlay] early-returns for it so a fully
 *  finished transition costs zero draw calls. */
enum class PixelPhase { HIDDEN, WIPE_IN, LOADING, WIPE_OUT }

/** Drives the pixel-matrix transition/loading overlay's state machine —
 *  see the module doc comment on [PixelMatrixOverlay] for the full visual
 *  spec this implements. One controller instance is meant to be shared
 *  (via [rememberPixelTransitionController]) across every place in the app
 *  that wants this transition: cold launch, profile navigation, and any
 *  future tab-open transition — callers just drive [start]/[updateColor]/
 *  [finish] around their own real async work; nothing here ever guesses at
 *  how long that work will take.
 *
 *  Zero-artificial-latency contract: the only fixed-duration animations in
 *  this whole class are the enter/exit wipes themselves (Phase 1 and Phase
 *  4 — genuinely fast, crisp visual sweeps, not stand-ins for loading time).
 *  The LOADING phase's actual on-screen duration is entirely a function of
 *  how long the caller takes to call [updateColor]/[finish] — there is no
 *  `delay()` anywhere in this class gating that middle phase. */
class PixelTransitionController {
    var phase by mutableStateOf(PixelPhase.HIDDEN)
        private set

    /** 0f..1f progress of whichever wipe (enter or exit) is currently
     *  running, along the bottom-left → top-right diagonal axis. */
    val wipeProgress = Animatable(0f)

    /** The color currently painted across the whole matrix. [updateColor]
     *  re-targets this with a real (non-instant) animateTo, producing the
     *  "smoothly hue-shift" requirement whenever new theme data resolves —
     *  including mid-wipe or mid-loading, at whatever moment that happens.
     *
     *  Implementation note: this is a manual from/to Color lerp driven by a
     *  plain Float Animatable rather than `Animatable<Color, ...>` —
     *  Compose's built-in Color vector converter isn't a plain property
     *  (`Color.Companion.VectorConverter` requires a ColorSpace argument),
     *  so this sidesteps that entirely while behaving identically from the
     *  caller's point of view. */
    private var colorFrom by mutableStateOf(Color.White)
    private var colorTo by mutableStateOf(Color.White)
    private val colorProgress = Animatable(1f)
    val color: State<Color> = derivedStateOf { lerp(colorFrom, colorTo, colorProgress.value) }

    /** Increments on a fixed tick while LOADING — the discrete "hop" driving
     *  the conveyor-belt motion. Deliberately NOT an Animatable/smoothly
     *  interpolated value: each step is a hard reassignment, per the
     *  "discrete pixel-step, not a smooth gradient slide" requirement. */
    var conveyorStep by mutableStateOf(0)
        private set

    private var conveyorJob: Job? = null
    private var generation = 0

    /** Phase 1 + 2: snaps to [baseColor], runs the fast diagonal wipe-in,
     *  then settles into the LOADING conveyor loop. Suspends only for the
     *  wipe-in's own short fixed duration — returns once the matrix is
     *  fully in its idle looping LOADING state, ready for the caller's real
     *  work to proceed in parallel. Safe to call again before a prior
     *  transition finished (e.g. rapid profile-to-profile navigation) —
     *  the new call's generation supersedes the old one's background loop. */
    suspend fun start(scope: CoroutineScope, baseColor: Color) {
        val myGeneration = ++generation
        conveyorJob?.cancel()
        colorFrom = baseColor
        colorTo = baseColor
        colorProgress.snapTo(1f)
        wipeProgress.snapTo(0f)
        phase = PixelPhase.WIPE_IN
        wipeProgress.animateTo(1f, tween(WIPE_IN_MS, easing = FastOutSlowInEasing))
        if (myGeneration != generation) return
        phase = PixelPhase.LOADING
        conveyorJob = scope.launch {
            var tick = 0
            while (isActive) {
                conveyorStep = tick++
                delay(CONVEYOR_STEP_MS)
            }
        }
    }

    /** Phase 3: smoothly hue-shifts the running matrix to [target]. Call
     *  this the instant new color metadata resolves — often well before the
     *  rest of the target content (profile assets, feed data) is ready.
     *  Re-entrant: each call just re-targets the in-flight animation, so
     *  calling it repeatedly as better color data trickles in is fine. */
    suspend fun updateColor(target: Color) {
        colorFrom = color.value
        colorTo = target
        colorProgress.snapTo(0f)
        colorProgress.animateTo(1f, tween(THEME_SHIFT_MS, easing = LinearOutSlowInEasing))
    }

    /** Phase 4: runs the fast diagonal exit wipe and returns to HIDDEN. Call
     *  this the instant — and only the instant — all target content is
     *  actually mounted and ready to show; this is what makes the whole
     *  overlay's total on-screen time track real load time exactly. */
    suspend fun finish() {
        val myGeneration = ++generation
        conveyorJob?.cancel()
        conveyorJob = null
        phase = PixelPhase.WIPE_OUT
        wipeProgress.snapTo(0f)
        wipeProgress.animateTo(1f, tween(WIPE_OUT_MS, easing = FastOutLinearInEasing))
        if (myGeneration != generation) return
        phase = PixelPhase.HIDDEN
    }

    companion object {
        private const val WIPE_IN_MS = 260
        private const val WIPE_OUT_MS = 220
        private const val THEME_SHIFT_MS = 420
        private const val CONVEYOR_STEP_MS = 90L
    }
}

@Composable
fun rememberPixelTransitionController(): PixelTransitionController = remember { PixelTransitionController() }

// Stable, allocation-free pseudo-random hash — same cell coordinates always
// produce the same jitter, so the "noisy scatter" pattern doesn't crawl/
// flicker from one recomposition to the next, only from an actual state
// change (wipe progress, conveyor step).
private fun hash01(x: Int, y: Int): Float {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h shr 13)) * 1274126177
    h = h xor (h shr 16)
    return (h and 0x7fffffff) / 2147483647f
}

/** Full-screen retro-digital pixel matrix used for both the app's cold-boot
 *  splash and profile-navigation transitions (see the two Scenario flows
 *  described in the design spec this implements):
 *
 *  - A medium-pixel grid sweeps in/out diagonally from bottom-left to
 *    top-right, with a noisy/scattered leading (or trailing) edge and a
 *    solid fill immediately behind it.
 *  - While idle between the two wipes, the grid runs a discrete, stepped
 *    "conveyor belt" brightness wave along that same diagonal.
 *  - The base color live-updates (smooth hue shift, not a hard cut) as the
 *    caller's real data resolves.
 *
 *  Renders nothing when [controller].phase is HIDDEN. */
@Composable
fun PixelMatrixOverlay(controller: PixelTransitionController, modifier: Modifier = Modifier) {
    if (controller.phase == PixelPhase.HIDDEN) return

    val cellDp = 24.dp
    val col = controller.color.value
    val wipe = controller.wipeProgress.value
    val phase = controller.phase
    val step = controller.conveyorStep

    Canvas(modifier.fillMaxSize()) {
        val cellPx = cellDp.toPx()
        val gap = cellPx * 0.08f
        val cols = ceil(size.width / cellPx).toInt() + 1
        val rows = ceil(size.height / cellPx).toInt() + 1
        val maxDiag = ((cols - 1) + (rows - 1)).coerceAtLeast(1)

        for (ry in 0 until rows) {
            // Diagonal axis is bottom-left → top-right, so row 0 (screen
            // top) must map to the HIGH end of the vertical component.
            val gy = rows - 1 - ry
            for (gx in 0 until cols) {
                val diag = (gx + gy).toFloat() / maxDiag

                var visible: Boolean
                when (phase) {
                    PixelPhase.WIPE_IN -> {
                        val dist = diag - wipe // >0: wave hasn't reached this cell yet
                        visible = when {
                            dist > EDGE_BAND -> false
                            dist > -EDGE_BAND * 0.4f -> hash01(gx, gy) < (1f - (dist + EDGE_BAND) / (EDGE_BAND * 1.4f))
                            else -> true
                        }
                    }
                    PixelPhase.WIPE_OUT -> {
                        val dist = diag - wipe // <0: wave has already dissolved this cell
                        visible = when {
                            dist < -EDGE_BAND -> false
                            dist < EDGE_BAND * 0.4f -> hash01(gx, gy) < ((dist + EDGE_BAND) / (EDGE_BAND * 1.4f))
                            else -> true
                        }
                    }
                    else -> visible = true
                }
                if (!visible) continue

                // Discrete conveyor-belt hop: a hard-edged triangular
                // brightness band that steps diagonally one grid cell per
                // tick (see conveyorStep) rather than sliding continuously.
                val rawBand = ((gx + gy) - step) % BAND_PERIOD
                val bandPos = if (rawBand < 0) rawBand + BAND_PERIOD else rawBand
                val conveyorGlow = when {
                    phase != PixelPhase.LOADING -> 1f
                    bandPos == 0 -> 1f
                    bandPos == 1 || bandPos == BAND_PERIOD - 1 -> 0.62f
                    else -> 0.26f
                }

                val shadeJitter = hash01(gx * 7 + 3, gy * 13 + 1)
                val glowJitter = hash01(gx * 31 + 11, gy * 17 + 5)
                val baseAlpha = 0.45f + shadeJitter * 0.4f
                val a = (baseAlpha * conveyorGlow).coerceIn(0.05f, 1f)

                drawRect(
                    color = col.copy(alpha = a),
                    topLeft = Offset(gx * cellPx + gap / 2f, ry * cellPx + gap / 2f),
                    size = Size(cellPx - gap, cellPx - gap)
                )
                // Subtle glow: a faint, slightly larger, lower-alpha rect
                // behind lively (bright-band) cells only — cheap stand-in
                // for real bloom that keeps the "glow" requirement without
                // an extra blur pass.
                if (conveyorGlow > 0.9f && glowJitter > 0.5f) {
                    drawRect(
                        color = col.copy(alpha = (a * 0.25f).coerceAtMost(0.18f)),
                        topLeft = Offset(gx * cellPx - gap, ry * cellPx - gap),
                        size = Size(cellPx + gap * 2f, cellPx + gap * 2f)
                    )
                }
            }
        }
    }
}

private const val EDGE_BAND = 0.05f
private const val BAND_PERIOD = 6
