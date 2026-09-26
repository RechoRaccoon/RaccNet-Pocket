package com.mediaviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.viewmodel.MainViewModel

/**
 * Settings → App Functionality → "Debug Overlay". Sits in the strip beside
 * the camera cutout: frame rate on the far right, and — when "Tag Post When
 * Liked" is on — the like-tagging queue on the far left ("Activating
 * tagger…" while the model loads, then "Tagging N posts" counting down).
 * Drawn in the signed-in user's profile color; never takes touches.
 */
@Composable
fun DebugOverlay(
    tint: Color,
    taggingEnabled: Boolean,
    tagPhase: MainViewModel.LikeTagPhase,
    tagPending: Int,
    modifier: Modifier = Modifier
) {
    var fps by remember { mutableIntStateOf(0) }
    // Counts real frames (Choreographer ticks the UI actually gets to run)
    // over half-second windows — drops show up as soon as the main thread
    // is blocked or rendering falls behind.
    LaunchedEffect(Unit) {
        var frames = 0
        var windowStart = 0L
        while (true) {
            withFrameNanos { t ->
                if (windowStart == 0L) windowStart = t
                frames++
                val span = t - windowStart
                if (span >= 500_000_000L) {
                    fps = Math.round(frames * 1_000_000_000.0 / span).toInt()
                    frames = 0
                    windowStart = t
                }
            }
        }
    }
    val color = readableTint(tint)
    val style = TextStyle(
        color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 4f)
    )
    Box(modifier.fillMaxWidth().height(rememberTopCutoutClearance()).padding(horizontal = 14.dp)) {
        if (taggingEnabled) {
            val label = when (tagPhase) {
                MainViewModel.LikeTagPhase.ACTIVATING -> "Activating tagger…"
                MainViewModel.LikeTagPhase.TAGGING -> "Tagging $tagPending post" + if (tagPending == 1) "" else "s"
                MainViewModel.LikeTagPhase.IDLE -> "Tagger idle"
            }
            Text(label, style = style, modifier = Modifier.align(Alignment.CenterStart))
        }
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
