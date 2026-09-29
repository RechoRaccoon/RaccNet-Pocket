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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediaviewer.util.rememberHapticTap

/** The vertical center of the camera-notch row (the real display cutout's
 *  center when there is one), so a button can sit level with the notch
 *  bubble. Falls back to half the usual top clearance. */
@Composable
fun rememberNotchCenterY(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val clearance = rememberTopCutoutClearance()
    return remember(view, density, clearance) {
        val rect = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            view.rootWindowInsets?.displayCutout?.boundingRects
                ?.firstOrNull { it.height() > 0 && it.top < with(density) { 120.dp.toPx() } }
        } else null
        if (rect != null) with(density) { rect.exactCenterY().toDp() } else clearance / 2
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
    val tap = rememberHapticTap()
    val m = modifier.size(size).clip(CircleShape).clickable { tap(); onClick() }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = m, shape = CircleShape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    } else {
        Box(m.background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
