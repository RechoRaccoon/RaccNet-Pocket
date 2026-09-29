package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.platform.PlatformFeature

/** The small pill shown on features this device can't use. */
@Composable
fun AndroidOnlyTag(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Text(
        text = "ANDROID ONLY",
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = modifier
            .background(Brush.horizontalGradient(listOf(Color(0xFF3DDC84), Color(0xFF1FA463))), shape)
            .border(0.5.dp, Color.White.copy(alpha = 0.35f), shape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

/**
 * Wraps a control for [feature]. Where the feature works, [content] is shown
 * untouched. Elsewhere it's grayed out (colour removed, softened), swallows
 * taps, and wears the "Android only" tag in its top-end corner.
 */
@Composable
fun PlatformFeatureGate(
    feature: PlatformFeature,
    modifier: Modifier = Modifier,
    tagAlignment: Alignment = Alignment.TopEnd,
    content: @Composable BoxScope.() -> Unit,
) {
    if (feature.isAvailable) {
        Box(modifier) { content() }
        return
    }
    Box(modifier) {
        Box(
            Modifier
                .grayedOut()
                // Consume every touch before the control sees it.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
        ) { content() }
        AndroidOnlyTag(Modifier.align(tagAlignment).padding(4.dp))
    }
}

/** Draws the content in grayscale at reduced opacity. */
fun Modifier.grayedOut(): Modifier = this
    .alpha(0.55f)
    .drawWithCache {
        val paint = Paint().apply {
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        }
        onDrawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
                drawContent()
                canvas.restore()
            }
        }
    }
