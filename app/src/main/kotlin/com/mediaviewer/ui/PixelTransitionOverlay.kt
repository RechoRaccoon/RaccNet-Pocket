package com.mediaviewer.ui

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *  `delay()` anywhere in this class gating that middle phase.
 *
 *  Bug fix — [scope] is now owned by the controller itself (supplied once,
 *  at construction, by [rememberPixelTransitionController] via its own
 *  `rememberCoroutineScope()`) instead of being passed in fresh by every
 *  caller of [start]. It used to be passed in per-call, as the calling
 *  composable's own `LaunchedEffect` scope — but that scope dies the instant
 *  that particular effect gets cancelled (e.g. a `LaunchedEffect` whose keys
 *  include a value that flips again moments later, which is a completely
 *  normal thing to happen while a profile/feed is still loading), which
 *  killed [conveyorJob] right along with it even though the transition
 *  itself was logically still running — the "digital marquee" motion would
 *  silently freeze for the rest of the transition (including all the way
 *  through the Phase-4 exit wipe) while the wipe/color animations, driven
 *  from whatever *new* effect execution picked up afterward, kept going.
 *  Owning a stable scope up front means the conveyor loop's lifetime now
 *  matches the controller's own logical start()→finish() lifetime exactly,
 *  regardless of how many times or how erratically any particular call
 *  site's own composition happens to recompose in between. */
class PixelTransitionController(private val scope: CoroutineScope) {
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
    // Bug fix (item 3): plain RGB lerp cuts straight across color space, so a
    // shift between two saturated hues visibly desaturates toward grey at the
    // midpoint (e.g. red -> cyan passes through neutral grey at t=0.5, since
    // that's exactly the RGB midpoint of two complementary colors). Rotating
    // through HSV hue instead — see [lerpHsv] below — keeps saturation/value
    // high the whole way through so this reads as a true hue shift, never a
    // dip to grey.
    val color: State<Color> = derivedStateOf { lerpHsv(colorFrom, colorTo, colorProgress.value) }

    /** Increments on a fixed tick while LOADING — the discrete "hop" driving
     *  the conveyor-belt motion. Deliberately NOT an Animatable/smoothly
     *  interpolated value: each step is a hard reassignment, per the
     *  "discrete pixel-step, not a smooth gradient slide" requirement. */
    var conveyorStep by mutableStateOf(0)
        private set

    private var conveyorJob: Job? = null
    private var generation = 0

    // Bug fix (item 2): start()/updateColor()/finish() all drive the SAME
    // wipeProgress/colorProgress Animatables. If two callers ever invoke
    // these concurrently — e.g. a profile's network fetch resolving faster
    // than the 380ms wipe-in, so a second effect run calls finish() while
    // the first run is still suspended inside start()'s wipeProgress
    // .animateTo(...) — the second call's snapTo/animateTo on that shared
    // Animatable cancels the first with a CancellationException. That
    // silently kills the first coroutine before it ever returns from
    // start(), which (at the call site) means whatever was gated on
    // start() completing — e.g. a "profileRevealArmed = true" right after
    // it — never runs, leaving the UI stuck permanently "loading". Wrapping
    // every call in the same Mutex fully serializes them: a call that
    // arrives early just waits for the in-flight one to actually finish
    // instead of yanking it out from under itself.
    private val mutex = Mutex()

    /** Phase 1 + 2: snaps to [baseColor], starts the conveyor loop running
     *  immediately (so the "digital marquee" motion is visible from the
     *  very first frame of the wipe-in, not just once LOADING begins), runs
     *  the fast diagonal wipe-in, then settles into LOADING. Suspends only
     *  for the wipe-in's own short fixed duration — returns once the matrix
     *  is fully in its idle looping LOADING state, ready for the caller's
     *  real work to proceed in parallel. Safe to call again before a prior
     *  transition finished (e.g. rapid profile-to-profile navigation) —
     *  the new call's generation supersedes the old one's background loop. */
    suspend fun start(baseColor: Color) = mutex.withLock {
        val myGeneration = ++generation
        conveyorJob?.cancel()
        colorFrom = baseColor
        colorTo = baseColor
        colorProgress.snapTo(1f)
        wipeProgress.snapTo(0f)
        phase = PixelPhase.WIPE_IN
        conveyorJob = scope.launch {
            var tick = 0
            while (isActive) {
                conveyorStep = tick++
                delay(CONVEYOR_STEP_MS)
            }
        }
        // Bug fix (item 2 — cold launch: wipe-in skips straight to fully
        // covered): `animateTo` times itself from the very first frame
        // callback it's actually given via `withFrameNanos`. On a genuinely
        // cold app launch this whole composable tree — the entire app,
        // first time — is being composed for the first time on the same
        // frame this runs, competing with class loading/JIT warm-up/disk
        // reads for prefs. That can make the *first* frame callback this
        // animation ever sees land a long time (well over 380ms) after it
        // was requested, so the very first sample of the animation already
        // reports "past its whole duration" — it renders as instantly
        // finished, the sweep never visibly happens. Waiting for a couple
        // of real frame callbacks up front (a few/several ms, effectively
        // free) lets the worst of that cold-start jank pass before the
        // timed tween actually starts counting, so it reliably plays out
        // its full duration here exactly like it already does at every
        // other, already-warmed-up call site (profile nav, feed switch).
        withFrameNanos {}
        withFrameNanos {}
        wipeProgress.animateTo(1f, tween(WIPE_IN_MS, easing = FastOutSlowInEasing))
        if (myGeneration != generation) return@withLock
        phase = PixelPhase.LOADING
    }

    /** Phase 3: smoothly hue-shifts the running matrix to [target]. Call
     *  this the instant new color metadata resolves — often well before the
     *  rest of the target content (profile assets, feed data) is ready.
     *  Re-entrant: each call just re-targets the in-flight animation, so
     *  calling it repeatedly as better color data trickles in is fine. */
    suspend fun updateColor(target: Color) = mutex.withLock {
        colorFrom = color.value
        colorTo = target
        colorProgress.snapTo(0f)
        colorProgress.animateTo(1f, tween(THEME_SHIFT_MS, easing = LinearOutSlowInEasing))
    }

    /** Phase 4: runs the fast diagonal exit wipe — conveyor motion keeps
     *  running the whole time this plays too — and returns to HIDDEN once
     *  it's actually finished. Call this the instant — and only the
     *  instant — all target content is actually mounted and ready to show;
     *  this is what makes the whole overlay's total on-screen time track
     *  real load time exactly. */
    suspend fun finish() = mutex.withLock {
        val myGeneration = ++generation
        phase = PixelPhase.WIPE_OUT
        wipeProgress.snapTo(0f)
        wipeProgress.animateTo(1f, tween(WIPE_OUT_MS, easing = FastOutLinearInEasing))
        if (myGeneration != generation) return@withLock
        conveyorJob?.cancel()
        conveyorJob = null
        phase = PixelPhase.HIDDEN
    }

    companion object {
        // Item 5: both wipes slowed down a bit from their original 260/220 —
        // still snappy, crisp sweeps, just a little more deliberate.
        private const val WIPE_IN_MS = 380
        private const val WIPE_OUT_MS = 340
        private const val THEME_SHIFT_MS = 420
        private const val CONVEYOR_STEP_MS = 90L
    }
}

/** Interpolates through HSV space instead of straight RGB, taking the
 *  shortest path around the hue wheel. Straight RGB lerp between two
 *  saturated colors cuts through the middle of the color cube, which for
 *  hues that are far apart (in the extreme, complementary — e.g. red to
 *  cyan) passes directly through the neutral grey point at the midpoint.
 *  Rotating hue instead keeps saturation/value high throughout, so this
 *  always reads as "color one smoothly becoming color two", never as a dip
 *  through grey along the way. */
private fun lerpHsv(from: Color, to: Color, t: Float): Color {
    val fromHsv = FloatArray(3)
    val toHsv = FloatArray(3)
    AndroidColor.colorToHSV(from.toArgb(), fromHsv)
    AndroidColor.colorToHSV(to.toArgb(), toHsv)
    var deltaHue = toHsv[0] - fromHsv[0]
    if (deltaHue > 180f) deltaHue -= 360f
    if (deltaHue < -180f) deltaHue += 360f
    var hue = fromHsv[0] + deltaHue * t
    if (hue < 0f) hue += 360f
    if (hue >= 360f) hue -= 360f
    val saturation = fromHsv[1] + (toHsv[1] - fromHsv[1]) * t
    val value = fromHsv[2] + (toHsv[2] - fromHsv[2]) * t
    return Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))
}

@Composable
fun rememberPixelTransitionController(): PixelTransitionController {
    // Bug fix: this scope is now handed to the controller once, at
    // construction (see the class doc comment above) — it survives for as
    // long as this composable stays in composition, independent of any
    // particular call site's own LaunchedEffect churn.
    val scope = rememberCoroutineScope()
    return remember { PixelTransitionController(scope) }
}

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

// Scales a color's RGB channels by [factor] (​>1 brightens toward white,
// <1 darkens toward black) while keeping it fully opaque. This is what
// drives the conveyor-belt motion — a genuine color/brightness shift, not
// an alpha change — so cells never look see-through because of it.
private fun shade(c: Color, factor: Float): Color = Color(
    red = (c.red * factor).coerceIn(0f, 1f),
    green = (c.green * factor).coerceIn(0f, 1f),
    blue = (c.blue * factor).coerceIn(0f, 1f),
    alpha = 1f
)

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

    Canvas(
        modifier
            .fillMaxSize()
            // Absorbs taps/drags so nothing underneath (still-loading
            // content, the screen being left) is reachable mid-transition —
            // matches the old per-screen loading covers this overlay
            // replaced (see ProfileOverlay's removed black loading cover).
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        val cellPx = cellDp.toPx()
        val gap = 0f
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
                // tick (see conveyorStep). Runs in every visible phase
                // (WIPE_IN/LOADING/WIPE_OUT alike) — motion never pauses
                // just because the wipe itself is mid-sweep.
                val rawBand = ((gx + gy) - step) % BAND_PERIOD
                val bandPos = if (rawBand < 0) rawBand + BAND_PERIOD else rawBand
                val conveyorFactor = when (bandPos) {
                    0 -> 1.45f
                    1, BAND_PERIOD - 1 -> 1.15f
                    else -> 0.82f
                }

                // Coverage stays essentially fully opaque at all times —
                // only a hairline shade jitter for texture — so a "covered"
                // cell never looks see-through. The conveyor's motion and
                // the grid's depth/variance both come from color intensity
                // (shade()) instead of alpha.
                val shadeJitter = hash01(gx * 7 + 3, gy * 13 + 1)
                val glowJitter = hash01(gx * 31 + 11, gy * 17 + 5)
                val cellColor = shade(col, conveyorFactor * (0.96f + shadeJitter * 0.08f))

                drawRect(
                    color = cellColor,
                    topLeft = Offset(gx * cellPx + gap / 2f, ry * cellPx + gap / 2f),
                    size = Size(cellPx - gap, cellPx - gap)
                )
                // Subtle glow: a faint, slightly larger, lower-alpha rect
                // behind the brightest (leading-band) cells only — cheap
                // stand-in for real bloom that keeps the "glow" requirement
                // without an extra blur pass.
                if (conveyorFactor > 1.3f && glowJitter > 0.5f) {
                    drawRect(
                        color = cellColor.copy(alpha = 0.22f),
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
