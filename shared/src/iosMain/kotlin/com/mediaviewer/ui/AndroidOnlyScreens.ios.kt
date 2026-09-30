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
import com.mediaviewer.platform.PlatformUri

/** iOS has no camera ring (the Dynamic Island isn't a tappable cutout, and
 *  Camera/VRM mode are Android only). */
@Composable
actual fun CameraNotchButton(
    liquidGlass: Boolean,
    tint: Color,
    modifier: Modifier,
    interactive: Boolean,
    onOpenCamera: () -> Unit,
    onOpenVrm: () -> Unit
) {}

/** Frames per second, counted from Compose's own frame clock. */
@Composable
actual fun DebugOverlay(tint: Color, modifier: Modifier) {
    var fps by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var windowStart = withFrameNanos { it }
        var frames = 0
        while (true) {
            val now = withFrameNanos { it }
            frames++
            val elapsed = now - windowStart
            if (elapsed >= 500_000_000L) {
                fps = (frames * 1_000_000_000.0 / elapsed).toInt()
                frames = 0
                windowStart = now
            }
        }
    }
    val hsv = FloatArray(3)
    com.mediaviewer.ui.compat.PlatformColor.colorToHSV(tint.toArgb(), hsv)
    hsv[2] = hsv[2].coerceAtLeast(0.85f)
    val color = Color(com.mediaviewer.ui.compat.PlatformColor.HSVToColor(hsv))
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

/** Never opened on iOS (nothing leads here); closes straight away if it is. */
@Composable
actual fun VrmModeScreen(
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCapture: (imageUri: PlatformUri?, videoUri: PlatformUri?) -> Unit
) {
    LaunchedEffect(Unit) { onClose() }
}

@Composable
actual fun CameraModeScreen(
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCapture: (imageUri: PlatformUri?, videoUri: PlatformUri?) -> Unit
) {
    LaunchedEffect(Unit) { onClose() }
}

@Composable
actual fun CapturePreviewScreen(
    uri: PlatformUri,
    isVideo: Boolean,
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCreatePost: (PlatformUri) -> Unit
) {
    LaunchedEffect(Unit) { onClose() }
}
