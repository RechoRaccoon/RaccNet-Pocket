package com.mediaviewer.ui

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mediaviewer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * "Space" loading animation (Settings → Loading Animation → Space; the
 * default).
 *
 *  1. [start]: a screenshot of the window is laid over the app — exactly
 *     like Shatter — so the page you were on stays put while the new one
 *     starts loading underneath it straight away. That screenshot then
 *     cross-fades into deep space: the Stellar logo floating a little above
 *     centre, with three parallax layers of stars drifting to the right at
 *     different speeds.
 *  2. [finish] (the new page is ready): the whole thing fades away,
 *     revealing the loaded page.
 *
 * Cheap to run: the screenshot is dropped the moment the fade into space
 * completes, the starfield is ~250 dots drawn in one layer, and nothing
 * recomposes per frame (the frame time is only read in the draw phase).
 */
class SpaceTransitionController(private val scope: CoroutineScope) {
    var phase by mutableStateOf(PixelPhase.HIDDEN)
        private set

    /** True while anything of the overlay is on screen. */
    internal var visible by mutableStateOf(false)
        private set
    internal var image by mutableStateOf<ImageBitmap?>(null)
        private set
    /** 0 = only the screenshot, 1 = only space. */
    internal val spaceAlpha = Animatable(0f)
    /** The whole overlay's opacity (fades to 0 in [finish]). */
    internal val overlayAlpha = Animatable(1f)

    internal var view: View? = null

    private val mutex = Mutex()
    private var fadeInJob: Job? = null
    private var spaceShownAtMs = 0L

    /** [fromBlack] skips the screenshot (cold launch: there's nothing on
     *  screen yet worth capturing). */
    suspend fun start(fromBlack: Boolean = false) = mutex.withLock {
        if (visible && phase == PixelPhase.LOADING) {
            // Already covering the screen (e.g. a profile opened from a
            // profile that's still loading) — just keep going.
            return@withLock
        }
        val v = view
        phase = PixelPhase.WIPE_IN
        overlayAlpha.snapTo(1f)
        if (fromBlack || v == null) {
            image = null
            spaceAlpha.snapTo(1f)
        } else {
            spaceAlpha.snapTo(0f)
            image = runCatching { captureWindow(v).asImageBitmap() }.getOrNull()
            if (image == null) spaceAlpha.snapTo(1f)
        }
        visible = true
        // Let the cover actually reach the screen before the caller swaps
        // the page underneath it.
        withFrameNanos { }
        withFrameNanos { }
        phase = PixelPhase.LOADING
        if (spaceAlpha.value < 1f) {
            fadeInJob = scope.launch {
                spaceAlpha.animateTo(1f, tween(FADE_IN_MS, easing = FastOutSlowInEasing))
                // Fully in space: the screenshot is invisible now, so free it.
                image = null
                spaceShownAtMs = System.currentTimeMillis()
            }
        } else {
            spaceShownAtMs = System.currentTimeMillis()
        }
    }

    suspend fun finish() = mutex.withLock {
        if (!visible) { phase = PixelPhase.HIDDEN; return@withLock }
        fadeInJob?.join()
        fadeInJob = null
        // A short beat on the logo so a very fast load doesn't read as a
        // flicker.
        val shown = System.currentTimeMillis() - spaceShownAtMs
        if (shown < MIN_HOLD_MS) delay(MIN_HOLD_MS - shown)
        phase = PixelPhase.WIPE_OUT
        overlayAlpha.animateTo(0f, tween(FADE_OUT_MS, easing = LinearEasing))
        visible = false
        image = null
        spaceAlpha.snapTo(0f)
        overlayAlpha.snapTo(1f)
        phase = PixelPhase.HIDDEN
    }

    companion object {
        private const val FADE_IN_MS = 360
        private const val FADE_OUT_MS = 380
        private const val MIN_HOLD_MS = 260L
    }
}

@Composable
fun rememberSpaceTransitionController(): SpaceTransitionController {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    return remember { SpaceTransitionController(scope) }.also { it.view = view }
}

// ── Parallax starfield ─────────────────────────────────────────────────────

private class ParallaxLayer(
    val x: FloatArray, val y: FloatArray,
    /** dp per second, to the right. */
    val speedDp: Float,
    val radiusDp: Float,
    val alpha: FloatArray,
    /** Motion-streak length in dp (0 = plain dot). */
    val streakDp: Float
)

private fun buildParallax(): List<ParallaxLayer> {
    val rnd = Random(0x57A2L)
    fun layer(n: Int, speed: Float, radius: Float, aMin: Float, aMax: Float, streak: Float) = ParallaxLayer(
        x = FloatArray(n) { rnd.nextFloat() }, y = FloatArray(n) { rnd.nextFloat() },
        speedDp = speed, radiusDp = radius,
        alpha = FloatArray(n) { aMin + (aMax - aMin) * rnd.nextFloat() },
        streakDp = streak
    )
    return listOf(
        layer(150, 14f, 0.55f, 0.25f, 0.6f, 0f),   // far
        layer(70, 38f, 0.95f, 0.45f, 0.85f, 0f),   // middle
        layer(28, 95f, 1.45f, 0.7f, 1f, 7f)        // near (slight streak)
    )
}

@Composable
fun SpaceOverlay(controller: SpaceTransitionController, modifier: Modifier = Modifier) {
    if (!controller.visible) return
    val layers = remember { buildParallax() }
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
    }
    val img = controller.image
    Box(
        modifier
            .graphicsLayer { alpha = controller.overlayAlpha.value }
            // Nothing underneath is touchable while the cover is up.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    do {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                }
            }
    ) {
        if (img != null) {
            Box(
                Modifier.fillMaxSize().drawWithCache {
                    val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    onDrawBehind { drawImage(img, dstSize = dst) }
                }
            )
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().graphicsLayer { alpha = controller.spaceAlpha.value }
        ) {
            // Stars: their own layer, redrawn every frame from the time
            // state read in the draw phase only.
            Box(
                Modifier.fillMaxSize().graphicsLayer { }.drawWithCache {
                    onDrawBehind {
                        drawRect(Color.Black)
                        val t = time.floatValue
                        val w = size.width; val h = size.height
                        for (l in layers) {
                            val shift = l.speedDp * density * t
                            val r = l.radiusDp * density
                            val streak = l.streakDp * density
                            val span = w + streak + r * 2f
                            for (i in l.x.indices) {
                                // Wrap around so the field never runs out.
                                val px = ((l.x[i] * span + shift) % span) - streak - r
                                val py = l.y[i] * h
                                val c = Color.White.copy(alpha = l.alpha[i])
                                if (streak > 0f) {
                                    drawLine(
                                        c.copy(alpha = l.alpha[i] * 0.35f), Offset(px - streak, py), Offset(px, py),
                                        strokeWidth = r * 1.2f, cap = StrokeCap.Round
                                    )
                                }
                                drawCircle(c, radius = r, center = Offset(px, py))
                            }
                        }
                    }
                }
            )
            // The logo lockup ("Stellar" + "Created by Recho Raccoon"):
            // fairly big, well above centre, with a soft glow and a very
            // slow breathe.
            val logoWidth = (maxWidth * 0.74f).coerceAtMost(400.dp)
            val lift = -(maxHeight * 0.12f)
            val breathe by remember { derivedBreathe(time) }
            Box(Modifier.align(Alignment.Center).offset(y = lift), contentAlignment = Alignment.Center) {
                // The glow: a blurred copy of the logo. A blur can only
                // spread within its own layer, so the layer gets a wide
                // transparent margin — without it the glow stopped dead at
                // the logo's rectangle, in a hard flat-edged box.
                val glowMargin = 48.dp
                Box(
                    Modifier.graphicsLayer {
                        scaleX = 1.04f; scaleY = 1.12f; alpha = 0.5f + 0.2f * breathe
                    }.blur(14.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                        .padding(glowMargin)
                ) {
                    Image(
                        painter = painterResource(R.drawable.stellar_logo_vector),
                        contentDescription = null,
                        modifier = Modifier.width(logoWidth)
                    )
                }
                Image(
                    painter = painterResource(R.drawable.stellar_logo_vector),
                    contentDescription = "Stellar",
                    modifier = Modifier.width(logoWidth).graphicsLayer {
                        val s = 1f + 0.015f * breathe
                        scaleX = s; scaleY = s
                    }
                )
            }
        }
    }
}

/** 0..1, a slow sine off the overlay's own clock (read in graphicsLayer
 *  blocks only, so it never recomposes). */
private fun derivedBreathe(time: androidx.compose.runtime.MutableFloatState) =
    androidx.compose.runtime.derivedStateOf {
        0.5f + 0.5f * kotlin.math.sin(time.floatValue * 2.1f)
    }
