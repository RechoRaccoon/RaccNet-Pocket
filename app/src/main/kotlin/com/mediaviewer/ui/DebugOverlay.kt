package com.mediaviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings → App Functionality → "FPS Overlay": the frame rate, in the strip
 * beside the camera cutout, lined up with the right edge of the Hub's own
 * UI (16dp in). Drawn in whatever color the current page wears (the post's
 * color on the timeline, your profile color on the Hub, a profile's own
 * color on its page…); never takes touches.
 */
@Composable
fun DebugOverlay(
    tint: Color,
    modifier: Modifier = Modifier
) {
    var fps by remember { mutableIntStateOf(0) }
    // Counts frames the app actually draws (FrameMetrics), rather than
    // asking for a frame every refresh to count them — which is what the
    // old counter did, and that alone kept the screen from ever dropping to
    // its lower idle refresh rate.
    val view = androidx.compose.ui.platform.LocalView.current
    val drawn = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    DisposableEffect(view) {
        var ctx: android.content.Context? = view.context
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) ctx = ctx.baseContext
        val window = (ctx as? android.app.Activity)?.window
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, _, _ -> drawn.incrementAndGet() }
        runCatching { window?.addOnFrameMetricsAvailableListener(listener, android.os.Handler(android.os.Looper.getMainLooper())) }
        onDispose { runCatching { window?.removeOnFrameMetricsAvailableListener(listener) } }
    }
    LaunchedEffect(Unit) {
        var last = android.os.SystemClock.uptimeMillis()
        while (true) {
            kotlinx.coroutines.delay(500)
            val now = android.os.SystemClock.uptimeMillis()
            val frames = drawn.getAndSet(0)
            val next = Math.round(frames * 1000.0 / (now - last).coerceAtLeast(1L)).toInt()
            last = now
            if (next != fps) fps = next
        }
    }
    val color = readableTint(tint)
    val style = TextStyle(
        color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 4f)
    )
    Box(modifier.fillMaxWidth().height(rememberTopCutoutClearance()).padding(horizontal = 16.dp)) {
        Row(Modifier.align(Alignment.CenterEnd)) {
            Text("$fps FPS", style = style)
        }
    }
}

/** The profile color, brightened enough to read over anything. */
private fun readableTint(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[2] = hsv[2].coerceAtLeast(0.85f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
