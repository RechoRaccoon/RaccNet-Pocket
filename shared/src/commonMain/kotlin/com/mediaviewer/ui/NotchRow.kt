package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.mediaviewer.ui.compat.rememberPlatformView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediaviewer.util.rememberHapticTap

/** The camera-notch bubble's collapsed size and vertical center, in
 *  window pixels, as last drawn by [CameraNotchButton] (0 = not drawn yet). */
object NotchGeometry {
    var ringSizePx by androidx.compose.runtime.mutableIntStateOf(0)
    var centerYPx by androidx.compose.runtime.mutableFloatStateOf(0f)
}

/** The notch bubble's own diameter (a sensible default until it's drawn). */
@Composable
fun rememberNotchBubbleSize(): Dp {
    val density = LocalDensity.current
    val px = NotchGeometry.ringSizePx
    return if (px > 0) with(density) { px.toDp() } else 26.dp
}

/** The vertical center of the camera-notch row (the real display cutout's
 *  center when there is one), so a button can sit level with the notch
 *  bubble. Falls back to half the usual top clearance. */
@Composable
fun rememberNotchCenterY(): Dp {
    val view = rememberPlatformView()
    val density = LocalDensity.current
    val clearance = rememberTopCutoutClearance()
    // The notch bubble's real center once it's been drawn.
    val drawn = NotchGeometry.centerYPx
    if (drawn > 0f) return with(density) { drawn.toDp() }
    return remember(view, density, clearance) {
        val cy = view.displayCutoutCenterYPx(with(density) { 120.dp.toPx() })
        if (cy != null) with(density) { cy.toDp() } else clearance / 2
    }
}

/** A round back button (glass, or a flat dark circle with Glass Theme off). */
@Composable
fun RoundBackButton(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val iconSize = (size * 0.55f).coerceIn(14.dp, 20.dp)
    val tap = rememberHapticTap()
    val m = modifier.size(size).clip(CircleShape).clickable { tap(); onClick() }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = m, shape = CircleShape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(iconSize))
        }
    } else {
        Box(m.background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(iconSize))
        }
    }
}
