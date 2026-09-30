package com.mediaviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.mediaviewer.util.UiToggles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Shatter" loading animation (Settings → Loading Animation → Shatter).
 *
 *  1. [start]: a screenshot of the whole window is laid over the app, so it
 *     still looks like the page you were on while the new one loads
 *     underneath. Cracks race out from where you last tapped, with a
 *     crunchy haptic.
 *  2. [finish] (the new page is ready): after a short beat, the glass
 *     breaks — every shard tumbles and falls away under gravity,
 *     revealing the new page.
 *
 * Same start/updateColor/finish/phase shape as the pixel controller, so the
 * app's transition call sites drive either one (see [LoadingTransition]).
 */
class ShatterTransitionController(private val scope: CoroutineScope) {
    var phase by mutableStateOf(PixelPhase.HIDDEN)
        private set

    internal var image by mutableStateOf<ImageBitmap?>(null)
        private set
    internal var shards by mutableStateOf<List<Shard>>(emptyList())
        private set
    internal var cracks by mutableStateOf<List<Crack>>(emptyList())
        private set
    internal var origin by mutableStateOf(Offset.Zero)
        private set
    /** Crack reach (px from the tap); grows while the glass cracks. */
    internal val crackRadius = androidx.compose.animation.core.Animatable(0f)
    /** Seconds since the pieces started falling (-1 = not falling). */
    internal var fallTime by mutableFloatStateOf(-1f)
        private set

    internal var view: com.mediaviewer.ui.compat.PlatformView? = null
    /** The last place the user touched, in window coordinates. */
    var lastTap: Offset? = null

    private val mutex = Mutex()
    private var crackJob: Job? = null
    private var crackDoneAtMs = 0L

    suspend fun start(@Suppress("UNUSED_PARAMETER") baseColor: Color) = mutex.withLock {
        val v = view ?: return@withLock
        phase = PixelPhase.WIPE_IN
        fallTime = -1f
        val bmp = v.captureScreen()
        if (bmp == null) { phase = PixelPhase.HIDDEN; return@withLock }
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val tap = lastTap?.takeIf { it.x in 0f..w && it.y in 0f..h } ?: Offset(w / 2f, h / 2f)
        lastTap = null
        val pattern = buildPattern(tap, w, h, Random(com.mediaviewer.platform.nanoTime()))
        origin = tap
        shards = pattern.first
        cracks = pattern.second
        crackRadius.snapTo(0f)
        image = bmp
        // Let the overlay actually reach the screen before the caller swaps
        // the page underneath it.
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        phase = PixelPhase.LOADING
        val reach = hypot(max(tap.x, w - tap.x), max(tap.y, h - tap.y)) * 1.05f
        crackJob = scope.launch {
            runCatching { v.crunchHaptic() }
            crackRadius.animateTo(
                reach,
                androidx.compose.animation.core.tween(340, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            )
            crackDoneAtMs = com.mediaviewer.platform.currentTimeMillis()
        }
    }

    /** Colors don't apply to glass — kept so both controllers share one API. */
    suspend fun updateColor(@Suppress("UNUSED_PARAMETER") target: Color) {}

    suspend fun finish() = mutex.withLock {
        if (image == null) { phase = PixelPhase.HIDDEN; return@withLock }
        crackJob?.join()
        // "Wait a quick moment" after the cracks, then let go.
        val sinceCrack = com.mediaviewer.platform.currentTimeMillis() - crackDoneAtMs
        if (sinceCrack < PAUSE_MS) delay(PAUSE_MS - sinceCrack)
        phase = PixelPhase.WIPE_OUT
        view?.let { runCatching { it.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.VIRTUAL_KEY) } }
        val startNs = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val t = (androidx.compose.runtime.withFrameNanos { it } - startNs) / 1_000_000_000f
            fallTime = t
            if (t > FALL_SECONDS) break
        }
        image = null
        shards = emptyList()
        cracks = emptyList()
        fallTime = -1f
        phase = PixelPhase.HIDDEN
    }

    companion object {
        private const val PAUSE_MS = 240L
        internal const val FALL_SECONDS = 1.15f
    }
}

// ── Geometry ───────────────────────────────────────────────────────────────

internal class Shard(
    val path: Path,
    val outline: Path,
    val centroid: Offset,
    /** Seconds after the fall starts before this piece lets go. */
    val delay: Float,
    val vx: Float,
    val vy: Float,
    val spin: Float,
    val sheen: Float
)

/** One crack segment: [a] nearer the impact than [b]. */
internal class Crack(val a: Offset, val b: Offset, val da: Float, val db: Float, val width: Float)

/**
 * A radial "spider-web" break around [tap]: jittered spokes crossed by
 * jittered rings, so each cell is a slightly irregular quad (the ones
 * touching the impact are triangles), some split again on a diagonal. The
 * outermost ring lies past every screen corner, so the shards tile the
 * whole screen with no gaps.
 */
private fun buildPattern(tap: Offset, w: Float, h: Float, rnd: Random): Pair<List<Shard>, List<Crack>> {
    val spokes = 15
    val maxR = hypot(max(tap.x, w - tap.x), max(tap.y, h - tap.y)) * 1.25f
    val radii = ArrayList<Float>()
    var r = 34f + rnd.nextFloat() * 16f
    while (r < maxR) { radii += r; r *= 1.55f + rnd.nextFloat() * 0.25f }
    radii += maxR
    val baseAngles = FloatArray(spokes) { i -> (2 * PI * i / spokes).toFloat() + (rnd.nextFloat() - 0.5f) * 0.28f }
    // pts[ring][spoke]
    val pts = Array(radii.size) { k ->
        Array(spokes) { i ->
            val a = baseAngles[i] + (rnd.nextFloat() - 0.5f) * 0.18f
            val rr = radii[k] * (0.9f + rnd.nextFloat() * 0.2f)
            Offset(tap.x + cos(a) * rr, tap.y + sin(a) * rr)
        }
    }
    fun dist(p: Offset) = hypot(p.x - tap.x, p.y - tap.y)
    val shards = ArrayList<Shard>()
    fun addShard(poly: List<Offset>) {
        val path = Path().apply {
            moveTo(poly[0].x, poly[0].y)
            for (j in 1 until poly.size) lineTo(poly[j].x, poly[j].y)
            close()
        }
        var cx = 0f; var cy = 0f
        poly.forEach { cx += it.x; cy += it.y }
        val c = Offset(cx / poly.size, cy / poly.size)
        val d = dist(c)
        val ang = atan2(c.y - tap.y, c.x - tap.x)
        val push = 220f + rnd.nextFloat() * 380f
        shards += Shard(
            path = path, outline = path, centroid = c,
            delay = (d / 2600f) + rnd.nextFloat() * 0.07f,
            vx = cos(ang) * push * (0.35f + rnd.nextFloat() * 0.65f),
            vy = sin(ang) * push * 0.35f - 160f - rnd.nextFloat() * 260f,
            spin = (rnd.nextFloat() - 0.5f) * 520f,
            sheen = 0.02f + rnd.nextFloat() * 0.07f
        )
    }
    // Centre triangles.
    for (i in 0 until spokes) {
        val j = (i + 1) % spokes
        addShard(listOf(tap, pts[0][i], pts[0][j]))
    }
    for (k in 0 until radii.size - 1) {
        for (i in 0 until spokes) {
            val j = (i + 1) % spokes
            val a = pts[k][i]; val b = pts[k][j]; val c = pts[k + 1][j]; val d = pts[k + 1][i]
            if (rnd.nextFloat() < 0.4f) {
                if (rnd.nextBoolean()) { addShard(listOf(a, b, c)); addShard(listOf(a, c, d)) }
                else { addShard(listOf(a, b, d)); addShard(listOf(b, c, d)) }
            } else addShard(listOf(a, b, c, d))
        }
    }
    val cracks = ArrayList<Crack>()
    // Spokes: impact outward.
    for (i in 0 until spokes) {
        var prev = tap
        for (k in radii.indices) {
            val p = pts[k][i]
            cracks += Crack(prev, p, dist(prev), dist(p), 1.6f - 0.8f * (k.toFloat() / radii.size))
            prev = p
        }
    }
    // Rings.
    for (k in radii.indices) {
        for (i in 0 until spokes) {
            val a = pts[k][i]; val b = pts[k][(i + 1) % spokes]
            val da = dist(a); val db = dist(b)
            cracks += if (da <= db) Crack(a, b, da, db, 1.1f) else Crack(b, a, db, da, 1.1f)
        }
    }
    return shards to cracks
}

// ── Drawing ────────────────────────────────────────────────────────────────

@Composable
fun rememberShatterTransitionController(): ShatterTransitionController {
    val scope = rememberCoroutineScope()
    val view = com.mediaviewer.ui.compat.rememberPlatformView()
    return remember { ShatterTransitionController(scope) }.also { it.view = view }
}

@Composable
fun ShatterOverlay(controller: ShatterTransitionController, modifier: Modifier = Modifier) {
    val img = controller.image ?: return
    val brush = remember(img) { ShaderBrush(ImageShader(img)) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Box(
        modifier
            // Nothing underneath is touchable while the glass is up.
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
        Canvas(Modifier.fillMaxSize()) {
            val t = controller.fallTime
            if (t < 0f) {
                // Whole pane, cracks spreading from the impact.
                drawImage(img)
                val reach = controller.crackRadius.value
                if (reach > 0f) {
                    for (c in controller.cracks) {
                        if (c.da >= reach) continue
                        val end = if (c.db <= reach) c.b else {
                            val f = ((reach - c.da) / (c.db - c.da).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            Offset(c.a.x + (c.b.x - c.a.x) * f, c.a.y + (c.b.y - c.a.y) * f)
                        }
                        // A dark hairline with a bright edge beside it reads as a crack in glass.
                        drawLine(Color.Black.copy(alpha = 0.45f), c.a + Offset(0.8f * density, 0.8f * density), end + Offset(0.8f * density, 0.8f * density), strokeWidth = c.width * density)
                        drawLine(Color.White.copy(alpha = 0.85f), c.a, end, strokeWidth = c.width * 0.8f * density)
                    }
                    // A little bright chip at the point of impact.
                    drawCircle(Color.White.copy(alpha = 0.55f), radius = 5f * density, center = controller.origin)
                }
            } else {
                val g = 5200f
                for (s in controller.shards) {
                    val lt = t - s.delay
                    if (lt <= 0f) {
                        drawPath(s.path, brush)
                        drawPath(s.outline, Color.White.copy(alpha = 0.35f), style = Stroke(width = 0.8f * density))
                        continue
                    }
                    val dx = s.vx * lt
                    val dy = s.vy * lt + 0.5f * g * lt * lt
                    if (s.centroid.y + dy > size.height + 900f) continue
                    val angle = s.spin * lt
                    val fade = (1f - ((lt - 0.55f) / 0.5f)).coerceIn(0f, 1f)
                    translate(dx, dy) {
                        rotate(angle, pivot = s.centroid) {
                            drawPath(s.path, brush, alpha = fade)
                            drawPath(s.path, Color.White.copy(alpha = s.sheen * fade))
                            drawPath(s.outline, Color.White.copy(alpha = 0.45f * fade), style = Stroke(width = 1f * density))
                        }
                    }
                }
            }
        }
    }
}

// ── Picks Pixels or Shatter per the setting ───────────────────────────────

/**
 * The app's one loading-transition handle. Each [start] picks whichever
 * animation Settings → Loading Animation currently selects; [updateColor]
 * and [finish] go to the one that started. (With "None" selected the call
 * sites skip transitions entirely — see UiToggles.loadingScreens.)
 */
class LoadingTransition(
    val pixels: PixelTransitionController,
    val shatter: ShatterTransitionController,
    val space: SpaceTransitionController
) {
    private var active by mutableStateOf(UiToggles.LoadingAnimation.PIXELS)

    val phase: PixelPhase get() = when (active) {
        UiToggles.LoadingAnimation.SHATTER -> shatter.phase
        UiToggles.LoadingAnimation.SPACE -> space.phase
        else -> pixels.phase
    }

    /** [fromBlack]: cold launch — Space skips its screenshot (there's
     *  nothing on screen yet); the others ignore it. */
    suspend fun start(baseColor: Color, fromBlack: Boolean = false) {
        // Don't switch animations mid-transition (a second start while one
        // is still covering the screen continues that same one).
        if (phase == PixelPhase.HIDDEN) {
            active = UiToggles.loadingAnimation.takeIf { it != UiToggles.LoadingAnimation.NONE }
                ?: UiToggles.LoadingAnimation.SPACE
        }
        when (active) {
            UiToggles.LoadingAnimation.SHATTER -> shatter.start(baseColor)
            UiToggles.LoadingAnimation.SPACE -> space.start(fromBlack)
            else -> pixels.start(baseColor)
        }
    }

    suspend fun updateColor(target: Color) {
        when (active) {
            UiToggles.LoadingAnimation.SHATTER -> shatter.updateColor(target)
            UiToggles.LoadingAnimation.SPACE -> Unit
            else -> pixels.updateColor(target)
        }
    }

    suspend fun finish() {
        when (active) {
            UiToggles.LoadingAnimation.SHATTER -> shatter.finish()
            UiToggles.LoadingAnimation.SPACE -> space.finish()
            else -> pixels.finish()
        }
    }
}

@Composable
fun rememberLoadingTransition(): LoadingTransition {
    val pixels = rememberPixelTransitionController()
    val shatter = rememberShatterTransitionController()
    val space = rememberSpaceTransitionController()
    return remember(pixels, shatter, space) { LoadingTransition(pixels, shatter, space) }
}

/** Remembers where the user last touched (for Shatter's point of impact),
 *  watching without consuming anything. */
fun Modifier.recordLastTap(controller: ShatterTransitionController): Modifier = this.pointerInput(controller) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        controller.lastTap = down.position
    }
}
