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
    val levels = AudioVisualizerEngine.levels
    // Lighter than the post color so the bars read over dark media too.
    val barColor = remember(color) { lerp(color, Color.White, 0.35f) }
    Canvas(modifier) {
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

/**
 * Item 10: "Ambient Light". Behind a letterboxed image or video, the
 * media's own top row of pixels is stretched up to the top of the screen
 * and its bottom row down to the bottom, so the picture seems to spill
 * past its edges instead of sitting in black bars. Uses a small copy of
 * [url] (the image, or a video's thumbnail); draws nothing for media that
 * already fills the screen's height.
 */
@Composable
fun AmbientEdgeFill(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        image = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url)
                    .size(160).scale(Scale.FIT).allowHardware(false).build()
                val drawable = coil.Coil.imageLoader(context).execute(request).drawable
                (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    ?.copy(Bitmap.Config.ARGB_8888, false)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val img = image ?: return
    Canvas(modifier) {
        if (img.width <= 1 || img.height <= 1) return@Canvas
        val imgAspect = img.width.toFloat() / img.height
        val boxAspect = size.width / size.height
        if (boxAspect > imgAspect) return@Canvas // pillarboxed: no top/bottom bars to fill
        val shownH = size.width / imgAspect
        val top = (size.height - shownH) / 2f
        if (top < 1f) return@Canvas
        val w = size.width.toInt().coerceAtLeast(1)
        // A two-pixel band from each edge (one pixel can be a JPEG seam).
        drawImage(
            img, srcOffset = IntOffset(0, 1), srcSize = IntSize(img.width, 1),
            dstOffset = IntOffset(0, 0), dstSize = IntSize(w, (top + 1f).toInt())
        )
        drawImage(
            img, srcOffset = IntOffset(0, img.height - 2), srcSize = IntSize(img.width, 1),
            dstOffset = IntOffset(0, (top + shownH - 1f).toInt()), dstSize = IntSize(w, (top + 2f).toInt())
        )
    }
}
