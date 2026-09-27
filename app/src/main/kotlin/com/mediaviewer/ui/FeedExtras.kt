package com.mediaviewer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import coil.size.Scale
import com.mediaviewer.util.AudioVisualizerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Item 8: the feed's audio visualizer — a row of rounded vertical bars that
 * sits right on top of the interaction bar, in the post's own color, moving
 * to whatever music is playing on the phone (see [AudioVisualizerEngine]
 * for how it listens, and why calls don't move it). Flat and invisible
 * while nothing is playing.
 */
@Composable
fun AudioVisualizerBars(color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        AudioVisualizerEngine.acquire(context)
        onDispose { AudioVisualizerEngine.release() }
    }
    // Lighter than the post color so the bars read over dark media too.
    val barColor = remember(color) { lerp(color, Color.White, 0.35f) }
    Canvas(modifier) {
        // Read here, in the draw phase: each new spectrum (~20 a second)
        // only redraws this canvas instead of recomposing the whole post.
        val levels = AudioVisualizerEngine.levels
        val n = levels.size
        if (n == 0) return@Canvas
        val gap = 3.dp.toPx()
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val radius = CornerRadius(barW / 2f, barW / 2f)
        for (i in 0 until n) {
            val v = levels[i]
            if (v <= 0.01f) continue
            val h = (size.height * v).coerceAtLeast(barW)
            drawRoundRect(
                color = barColor.copy(alpha = 0.55f + 0.4f * v),
                topLeft = Offset(i * (barW + gap), size.height - h),
                size = Size(barW, h),
                cornerRadius = radius
            )
        }
    }
}
