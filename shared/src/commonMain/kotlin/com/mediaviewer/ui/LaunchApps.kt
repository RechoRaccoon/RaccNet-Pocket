package com.mediaviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.platform.LocalPlatform
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.compat.HapticFeedbackConstants
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.ui.compat.rememberPlatformView
import com.mediaviewer.util.CalendarEvent
import com.mediaviewer.util.CalendarMath
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.TimerSound
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ── Shared page chrome ──────────────────────────────────────────────────

/** A full-screen Launchpad app page in Stellar's style: the dim profile
 *  color and stars behind, a back bubble and the title up top, and
 *  [content] filling the rest. */
@Composable
internal fun LaunchAppPage(
    title: String,
    tint: Color,
    liquidGlass: Boolean,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(dimSpaceColor(tint)).blockClicksBehind()) {
        SpaceSky(tint, Modifier.matchParentSize())
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navBarSpace).imePadding()
                .padding(horizontal = 14.dp)
        ) {
            Spacer(Modifier.height(rememberTopCutoutClearance() + 6.dp))
            Box(Modifier.fillMaxWidth().height(40.dp)) {
                AppBubble(Icons.AutoMirrored.Filled.ArrowBack, "Back", liquidGlass, tint, onClose, Modifier.align(Alignment.CenterStart))
                Text(
                    title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
                Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
            Spacer(Modifier.height(10.dp))
            content()
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** A round glass button. */
@Composable
internal fun AppBubble(
    icon: ImageVector, description: String, liquidGlass: Boolean, tint: Color,
    onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 38.dp
) {
    val tap = rememberHapticTap()
    val m = modifier.size(size).clip(CircleShape).clickable { tap(); onClick() }
    if (liquidGlass) {
        LiquidGlassSurface(m, shape = CircleShape, tint = tint, contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
    } else {
        Box(m.background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** A glass tile holding [content] (a key, a day, a panel). */
@Composable
internal fun AppTile(
    liquidGlass: Boolean, tint: Color, modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    strong: Boolean = false,
    content: @Composable () -> Unit
) {
    if (liquidGlass) {
        LiquidGlassSurface(modifier, shape = shape, tint = if (strong) vividAccent(tint) else tint, contentAlignment = Alignment.Center) { content() }
    } else {
        Box(
            modifier.clip(shape).background(if (strong) lerp(tint, Color.White, 0.1f).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f))
                .border(1.dp, lerp(tint, Color.White, 0.3f).copy(alpha = if (strong) 0.8f else 0.3f), shape),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

// ── Calculator ──────────────────────────────────────────────────────────

/** Evaluates a keypad expression: + − × ÷ with the usual precedence,
 *  unary minus and % (as "divide by 100"). Null when it isn't complete. */
internal fun evaluateExpression(expr: String): Double? {
    val s = expr.replace('×', '*').replace('÷', '/').replace('−', '-').replace(" ", "")
    if (s.isEmpty()) return null
    var pos = 0
    fun peek(): Char? = s.getOrNull(pos)
    fun number(): Double? {
        val start = pos
        while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
        if (start == pos) return null
        var v = s.substring(start, pos).toDoubleOrNull() ?: return null
        while (peek() == '%') { v /= 100.0; pos++ }
        return v
    }
    fun factor(): Double? {
        if (peek() == '-') { pos++; return factor()?.let { -it } }
        if (peek() == '+') { pos++; return factor() }
        return number()
    }
    fun term(): Double? {
        var v = factor() ?: return null
        while (true) {
            when (peek()) {
                '*' -> { pos++; v *= factor() ?: return null }
                '/' -> { pos++; val d = factor() ?: return null; if (d == 0.0) return null; v /= d }
                else -> return v
            }
        }
    }
    var v = term() ?: return null
    while (true) {
        when (peek()) {
            '+' -> { pos++; v += term() ?: return null }
            '-' -> { pos++; v -= term() ?: return null }
            null -> return v
            else -> return null
        }
    }
}

internal fun formatNumber(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "Error"
    if (abs(v) >= 1e15) return v.toString()
    val rounded = kotlin.math.round(v * 1e10) / 1e10
    if (rounded == kotlin.math.floor(rounded) && abs(rounded) < 1e15) return rounded.toLong().toString()
    return rounded.toString().trimEnd('0').trimEnd('.')
}

/** Launchpad → Calculator. The keys fill the lower part of the screen (in
 *  thumb's reach); swipe left on the display to delete, tap it to clear. */
@Composable
fun CalculatorPage(tint: Color, liquidGlass: Boolean, onClose: () -> Unit) {
    val view = rememberPlatformView()
    var expr by remember { mutableStateOf("") }
    var justEvaluated by remember { mutableStateOf(false) }
    val preview = remember(expr) { if (expr.any { it in "+−×÷%" }) evaluateExpression(expr)?.let { formatNumber(it) } else null }

    fun press(key: String) {
        runCatching { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        val ops = "+−×÷"
        when (key) {
            "C" -> { expr = ""; justEvaluated = false }
            "⌫" -> { expr = expr.dropLast(1); justEvaluated = false }
            "=" -> {
                val r = evaluateExpression(expr)
                if (r != null) { expr = formatNumber(r).replace('-', '−'); justEvaluated = true }
            }
            "±" -> {
                // Flips the sign of the number being typed.
                val i = expr.indexOfLast { it in ops }
                val head = if (i >= 0) expr.substring(0, i + 1) else ""
                val tail = if (i >= 0) expr.substring(i + 1) else expr
                expr = when {
                    i == 0 && expr.startsWith("−") -> tail
                    i < 0 && tail.isNotEmpty() -> "−$tail"
                    head.endsWith("+") -> head.dropLast(1) + "−" + tail
                    head.endsWith("−") && head.length > 1 -> head.dropLast(1) + "+" + tail
                    else -> expr
                }
            }
            in listOf("+", "−", "×", "÷") -> {
                justEvaluated = false
                expr = when {
                    expr.isEmpty() -> if (key == "−") key else ""
                    expr.last() in ops -> if (expr.length == 1) (if (key == "−") key else "") else expr.dropLast(1) + key
                    expr.last() == '.' -> expr.dropLast(1) + key
                    else -> expr + key
                }
            }
            "%" -> if (expr.isNotEmpty() && (expr.last().isDigit() || expr.last() == '%')) expr += "%"
            "." -> {
                if (justEvaluated) { expr = "0."; justEvaluated = false }
                else {
                    val current = expr.takeLastWhile { it !in ops }
                    if (!current.contains('.') && !current.endsWith("%")) expr += if (current.isEmpty()) "0." else "."
                }
            }
            else -> {
                if (justEvaluated) { expr = key; justEvaluated = false }
                else if (!expr.endsWith("%") && expr.length < 40) expr += key
            }
        }
    }

    LaunchAppPage("Calculator", tint, liquidGlass, onClose) {
        // Display: the expression, and what it comes to so far.
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragged += amount
                            // Every ~28dp of swiping left deletes one character.
                            val stepPx = 28.dp.toPx()
                            while (dragged <= -stepPx) { dragged += stepPx; press("⌫") }
                        }
                    )
                }
                .pointerInput(Unit) { detectTapGestures(onLongPress = { press("C") }) },
            verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.End
        ) {
            Text(
                expr.ifEmpty { "0" }, color = Color.White,
                fontSize = if (expr.length > 16) 30.sp else if (expr.length > 10) 40.sp else 54.sp,
                fontWeight = FontWeight.Light, maxLines = 2, textAlign = TextAlign.End,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()
            )
            Text(
                preview?.let { "= $it" } ?: " ", color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp,
                maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            Text(
                "Swipe left to delete · hold to clear", color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        val rows = listOf(
            listOf("C", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("±", "0", ".", "=")
        )
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { key ->
                        val isOp = key in listOf("÷", "×", "−", "+", "=")
                        AppTile(
                            liquidGlass, tint,
                            Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(20.dp)).clickable { press(key) },
                            shape = RoundedCornerShape(20.dp), strong = isOp
                        ) {
                            Text(
                                key, color = Color.White.copy(alpha = if (key in listOf("C", "⌫", "%", "±")) 0.8f else 1f),
                                fontSize = 22.sp, fontWeight = if (isOp) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Timer ───────────────────────────────────────────────────────────────

/**
 * The Timer's state. Lives outside the page so a running timer keeps going
 * (and rings) after the page is closed — [LocalOverlayHost] watches it.
 */
object TimerEngine {
    /** What the dial is set to. */
    var setSeconds by mutableIntStateOf(300)
    /** When the running timer ends (ms), or 0. */
    var endsAt by mutableLongStateOf(0L)
    /** Time left on a paused timer (ms), or -1. */
    var pausedLeft by mutableLongStateOf(-1L)
    /** The alarm is sounding. */
    var ringing by mutableStateOf(false)

    val running: Boolean get() = endsAt > 0L
    val idle: Boolean get() = endsAt == 0L && pausedLeft < 0L && !ringing

    fun start() {
        if (setSeconds <= 0) return
        endsAt = currentTimeMillis() + setSeconds * 1000L
        pausedLeft = -1L
    }
    fun pause() {
        if (!running) return
        pausedLeft = (endsAt - currentTimeMillis()).coerceAtLeast(0L)
        endsAt = 0L
    }
    fun resume() {
        if (pausedLeft < 0L) return
        endsAt = currentTimeMillis() + pausedLeft
        pausedLeft = -1L
    }
    fun reset() {
        endsAt = 0L; pausedLeft = -1L
        if (ringing) { ringing = false; LocalPlatform.stopSound() }
    }
    fun leftMs(now: Long): Long = when {
        running -> (endsAt - now).coerceAtLeast(0L)
        pausedLeft >= 0L -> pausedLeft
        else -> setSeconds * 1000L
    }
}

/** Runs for as long as the app is on screen: rings the alarm when the
 *  timer reaches zero, wherever in the app you are. */
@Composable
internal fun TimerWatcher() {
    val context = LocalContext.current
    val view = rememberPlatformView()
    val endsAt = TimerEngine.endsAt
    LaunchedEffect(endsAt) {
        if (endsAt <= 0L) return@LaunchedEffect
        delay((endsAt - currentTimeMillis()).coerceAtLeast(0L))
        if (TimerEngine.endsAt != endsAt) return@LaunchedEffect
        TimerEngine.endsAt = 0L
        TimerEngine.pausedLeft = -1L
        TimerEngine.ringing = true
        // A fresh alarm every time: generated from the clock as its seed.
        val wav = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { TimerSound.wav(currentTimeMillis()) }
        if (TimerEngine.ringing) LocalPlatform.playLoopingWav(context, wav)
        // Stops by itself after two minutes.
        var buzz = 0
        while (TimerEngine.ringing && buzz < 240) {
            runCatching { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
            delay(500)
            buzz++
        }
        if (TimerEngine.ringing) TimerEngine.reset()
    }
}

/**
 * Launchpad → Timer. All gestures, on the dial itself:
 *  • drag around the ring to set the time (a full turn is an hour; keep
 *    turning for more),
 *  • swipe up / down inside it to nudge by a minute,
 *  • tap to start, pause and resume,
 *  • hold to reset.
 */
@Composable
fun TimerPage(tint: Color, liquidGlass: Boolean, onClose: () -> Unit) {
    val view = rememberPlatformView()
    val engine = TimerEngine
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(engine.endsAt, engine.ringing) {
        while (engine.running || engine.ringing) { now = currentTimeMillis(); delay(100) }
        now = currentTimeMillis()
    }
    val left = engine.leftMs(now)
    val totalMs = (engine.setSeconds * 1000L).coerceAtLeast(1L)
    val accent = vividAccent(tint)
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(engine.ringing) {
        while (engine.ringing) {
            pulse.animateTo(1.06f, spring(dampingRatio = 0.4f, stiffness = 300f))
            pulse.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f))
        }
        pulse.snapTo(1f)
    }
    fun tick() { runCatching { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }

    LaunchAppPage("Timer", tint, liquidGlass, onClose) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            BoxWithConstraints(Modifier.fillMaxWidth(0.92f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val sizePx = with(density) { maxWidth.toPx() }
                Canvas(
                    Modifier.fillMaxSize()
                        .graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    runCatching { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                                    when {
                                        engine.ringing -> engine.reset()
                                        engine.running -> engine.pause()
                                        engine.pausedLeft >= 0L -> engine.resume()
                                        else -> engine.start()
                                    }
                                },
                                onLongPress = {
                                    runCatching { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                                    engine.reset()
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            var lastAngle = 0f
                            var onRing = false
                            var carry = 0f
                            detectDragGestures(
                                onDragStart = { p ->
                                    val c = Offset(size.width / 2f, size.height / 2f)
                                    val d = (p - c).getDistance()
                                    // The outer third is the ring; inside it, swipes nudge.
                                    onRing = d > size.width * 0.33f
                                    lastAngle = kotlin.math.atan2(p.y - c.y, p.x - c.x)
                                    carry = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (!engine.idle) return@detectDragGestures
                                    if (onRing) {
                                        val c = Offset(size.width / 2f, size.height / 2f)
                                        val a = kotlin.math.atan2(change.position.y - c.y, change.position.x - c.x)
                                        var da = a - lastAngle
                                        if (da > PI) da -= (2 * PI).toFloat()
                                        if (da < -PI) da += (2 * PI).toFloat()
                                        lastAngle = a
                                        // A full turn = 60 minutes; snaps to 15 seconds.
                                        carry += da / (2 * PI).toFloat() * 3600f
                                        val steps = (carry / 15f).toInt()
                                        if (steps != 0) {
                                            carry -= steps * 15f
                                            val next = (engine.setSeconds + steps * 15).coerceIn(0, 12 * 3600)
                                            if (next != engine.setSeconds) { engine.setSeconds = next; tick() }
                                        }
                                    } else {
                                        // Up = more, down = less: a minute per ~22dp.
                                        carry += -amount.y
                                        val stepPx = 22.dp.toPx()
                                        val steps = (carry / stepPx).toInt()
                                        if (steps != 0) {
                                            carry -= steps * stepPx
                                            val next = (engine.setSeconds + steps * 60).coerceIn(0, 12 * 3600)
                                            if (next != engine.setSeconds) { engine.setSeconds = next; tick() }
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    val stroke = size.minDimension * 0.055f
                    val inset = stroke / 2 + 4f
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(
                        color = Color.White.copy(alpha = 0.10f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke)
                    )
                    // While setting: the ring shows minutes within the hour.
                    // While running: how much of the set time is left.
                    val frac = if (engine.idle) ((engine.setSeconds % 3600) / 3600f).let { if (it == 0f && engine.setSeconds > 0) 1f else it }
                        else (left.toFloat() / totalMs).coerceIn(0f, 1f)
                    drawArc(
                        color = if (engine.ringing) Color(0xFFFF4FA1) else accent,
                        startAngle = -90f, sweepAngle = 360f * frac, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                    // Minute ticks, like stars around an orbit.
                    val r = size.minDimension / 2 - stroke - 14f
                    for (i in 0 until 60) {
                        val ang = (i / 60f) * 2 * PI.toFloat() - PI.toFloat() / 2
                        val big = i % 5 == 0
                        drawCircle(
                            Color.White.copy(alpha = if (big) 0.55f else 0.2f), radius = if (big) 2.6f else 1.4f,
                            center = Offset(size.width / 2 + cos(ang) * r, size.height / 2 + sin(ang) * r)
                        )
                    }
                    // The handle at the end of the arc.
                    val hAng = frac * 2 * PI.toFloat() - PI.toFloat() / 2
                    val hr = (size.minDimension - inset * 2) / 2
                    drawCircle(Color.White, radius = stroke * 0.42f, center = Offset(size.width / 2 + cos(hAng) * hr, size.height / 2 + sin(hAng) * hr))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val totalSec = ((left + 999) / 1000).toInt()
                    val h = totalSec / 3600
                    val m = (totalSec % 3600) / 60
                    val s = totalSec % 60
                    fun two(n: Int) = if (n < 10) "0$n" else n.toString()
                    Text(
                        if (engine.ringing) "Time's up" else if (h > 0) "$h:${two(m)}:${two(s)}" else "${two(m)}:${two(s)}",
                        color = Color.White, fontSize = if (engine.ringing) 34.sp else if (sizePx > 0 && h > 0) 46.sp else 58.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text(
                        when {
                            engine.ringing -> "Tap to stop"
                            engine.running -> "Tap to pause · hold to reset"
                            engine.pausedLeft >= 0L -> "Paused · tap to resume · hold to reset"
                            else -> "Tap to start"
                        },
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        Text(
            "Drag around the ring to set the time · swipe up or down inside it for a minute more or less",
            color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(10.dp))
        // Quick presets, within thumb's reach.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60 to "1m", 300 to "5m", 600 to "10m", 1500 to "25m", 3600 to "1h").forEach { (secs, label) ->
                AppTile(
                    liquidGlass, tint,
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = engine.idle) {
                        tick(); engine.setSeconds = secs
                    },
                    shape = RoundedCornerShape(16.dp), strong = engine.idle && engine.setSeconds == secs
                ) {
                    Text(label, color = Color.White.copy(alpha = if (engine.idle) 1f else 0.4f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ── Calendar ────────────────────────────────────────────────────────────

/** Launchpad → Calendar: one month on screen (swipe sideways for the
 *  next/previous), the picked day's events under it, and a row to add one.
 *  Events live only on this device. */
@Composable
fun CalendarPage(tint: Color, liquidGlass: Boolean, onClose: () -> Unit) {
    val tap = rememberHapticTap()
    val view = rememberPlatformView()
    val today = remember { CalendarMath.todayKey() }
    var year by remember { mutableIntStateOf(today / 10000) }
    var month by remember { mutableIntStateOf(today / 100 % 100) }
    var selected by remember { mutableIntStateOf(today) }
    var title by remember { mutableStateOf("") }
    var timed by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(12) }
    var minute by remember { mutableIntStateOf(0) }
    val events = LocalData.calendarEvents
    val eventDays = remember(events) { events.mapTo(HashSet()) { it.day } }
    val dayEvents = remember(events, selected) { events.filter { it.day == selected }.sortedBy { it.minute } }
    val accent = vividAccent(tint)

    fun shift(by: Int) {
        var m = month + by
        var y = year
        while (m < 1) { m += 12; y-- }
        while (m > 12) { m -= 12; y++ }
        month = m; year = y
        runCatching { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }
    fun timeText(min: Int): String {
        if (min < 0) return "All day"
        val h = min / 60
        val mm = min % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${if (mm < 10) "0$mm" else mm} ${if (h < 12) "AM" else "PM"}"
    }

    LaunchAppPage("Calendar", tint, liquidGlass, onClose) {
        // Month header: swipe (or tap the arrows) to change month; tap the
        // name to come back to today.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable { shift(-1) }.padding(horizontal = 14.dp))
            Text(
                "${CalendarMath.monthNames[month - 1]} $year", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable {
                    tap(); year = today / 10000; month = today / 100 % 100; selected = today
                }.padding(vertical = 4.dp)
            )
            Text("›", color = Color.White, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable { shift(1) }.padding(horizontal = 14.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        // The month grid takes whatever height is left over.
        val first = CalendarMath.daysFromCivil(year, month, 1)
        val lead = CalendarMath.weekday(first)
        val count = CalendarMath.daysInMonth(year, month)
        val weeks = (lead + count + 6) / 7
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)
                .pointerInput(Unit) {
                    var dragged = 0f
                    var fired = false
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f; fired = false },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragged += amount
                            if (!fired && abs(dragged) > 56.dp.toPx()) { fired = true; shift(if (dragged < 0) 1 else -1) }
                        }
                    )
                },
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            for (w in 0 until weeks) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (d in 0 until 7) {
                        val day = w * 7 + d - lead + 1
                        if (day < 1 || day > count) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            val key = CalendarMath.key(year, month, day)
                            val isSel = key == selected
                            val isToday = key == today
                            val shape = RoundedCornerShape(14.dp)
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(shape)
                                    .background(
                                        when {
                                            isSel -> accent.copy(alpha = 0.55f)
                                            isToday -> Color.White.copy(alpha = 0.14f)
                                            else -> Color.Black.copy(alpha = 0.18f)
                                        }
                                    )
                                    .border(1.dp, if (isSel || isToday) lerp(accent, Color.White, 0.3f).copy(alpha = 0.9f) else Color.White.copy(alpha = 0.06f), shape)
                                    .clickable { tap(); selected = key },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(day.toString(), color = Color.White, fontSize = 15.sp, fontWeight = if (isSel || isToday) FontWeight.Bold else FontWeight.Medium)
                                if (key in eventDays) Box(
                                    Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(5.dp).clip(CircleShape)
                                        .background(if (isSel) Color.White else accent)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // The picked day's events (up to three shown; the rest are counted).
        val (_, sm, sd) = Triple(selected / 10000, selected / 100 % 100, selected % 100)
        Text(
            "${CalendarMath.monthNames[(sm - 1).coerceIn(0, 11)]} $sd" + if (dayEvents.size > 3) " · ${dayEvents.size} events" else "",
            color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
        Column(Modifier.fillMaxWidth().height(108.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (dayEvents.isEmpty()) Text("Nothing planned.", color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            dayEvents.take(3).forEach { ev -> EventRow(ev, timeText(ev.minute), tint) }
        }
        // Add an event: a title, and optionally a time (tap the time to
        // switch between "All day" and a time; its − / + set it).
        Row(verticalAlignment = Alignment.CenterVertically) {
            LocalTextField(title, { title = it.take(80) }, "New event", tint, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            LocalPillButton(
                "Add", liquidGlass, tint,
                { LocalData.addCalendarEvent(selected, if (timed) hour * 60 + minute else -1, title); title = "" },
                enabled = title.isNotBlank(), height = 44.dp
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LocalChip(if (timed) timeText(hour * 60 + minute) else "All day", timed, tint, { timed = !timed })
            if (timed) {
                LocalChip("− hour", false, tint, { hour = (hour + 23) % 24 })
                LocalChip("+ hour", false, tint, { hour = (hour + 1) % 24 })
                LocalChip("− 5m", false, tint, { minute = (minute + 55) % 60 })
                LocalChip("+ 5m", false, tint, { minute = (minute + 5) % 60 })
            }
        }
    }
}

@Composable
private fun EventRow(ev: CalendarEvent, time: String, tint: Color) {
    val tap = rememberHapticTap()
    var armed by remember(ev.id) { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
    Row(
        Modifier.fillMaxWidth().height(31.dp).clip(RoundedCornerShape(11.dp)).background(Color.Black.copy(alpha = 0.26f))
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(time, color = lerp(tint, Color.White, 0.55f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp), maxLines = 1)
        Text(ev.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier.clip(RoundedCornerShape(9.dp))
                .background(if (armed) Color(0xFFE0245E).copy(alpha = 0.3f) else Color.Transparent)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    tap(); if (armed) LocalData.deleteCalendarEvent(ev.id) else armed = true
                }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            if (armed) Text("Delete?", color = Color(0xFFFF6B8A), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            else Icon(Icons.Default.Close, contentDescription = "Delete event", tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(13.dp))
        }
    }
}
