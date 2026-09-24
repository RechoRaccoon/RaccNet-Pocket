package com.mediaviewer.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import com.mediaviewer.util.rememberHapticTap

/**
 * Item 8: the phone's real front-camera cutout, turned into a button.
 *
 * Collapsed, it's a small glass-outlined bubble hugging the actual display
 * cutout — with a real gap between the outline and the cutout itself (see
 * [outlinePadding]) so it visibly reads as a tappable bubble rather than
 * just decoration drawn around the notch. Tapping it gives a deep haptic
 * tap and expands the bubble horizontally in *both* directions at once —
 * the notch's own position never moves, the outline just grows outward
 * around it — revealing a "Camera" text button on the left and a "VRM"
 * text button on the right. Both halves are the same fixed width and each
 * centers its own label within its own half, so the notch still reads as
 * dividing one continuous bubble into two separate buttons even though it's
 * visually one shape. Tapping the notch again, or anywhere else on screen,
 * smoothly collapses it back with no action taken.
 *
 * Positioning/sizing come from the device's *real* [android.view.DisplayCutout]
 * (via the root view's window insets) rather than a guessed constant, so the
 * bubble actually hugs whatever cutout shape/size this specific device has.
 * Devices with no cutout (most emulators, some tablets) fall back to a small
 * fixed size so the button still renders sensibly.
 */
@Composable
fun CameraNotchButton(
    liquidGlass: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    onOpenCamera: () -> Unit,
    onOpenVrm: () -> Unit
) {
    val tap = rememberHapticTap()
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val view = LocalView.current
    var cutoutWidth by remember { mutableStateOf(28.dp) }
    var cutoutHeight by remember { mutableStateOf(28.dp) }
    LaunchedEffect(view) {
        val rect = ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects?.firstOrNull()
        if (rect != null && rect.width() > 0 && rect.height() > 0) {
            with(density) {
                cutoutWidth = rect.width().toDp()
                cutoutHeight = rect.height().toDp()
            }
        }
    }

    val outlinePadding = 10.dp
    val sideWidth = 64.dp
    val bubbleHeight = cutoutHeight + outlinePadding * 2
    val collapsedWidth = cutoutWidth + outlinePadding * 2
    val expandedWidth = collapsedWidth + sideWidth * 2
    // "Smooth but snappy": a fast, slightly-overshooting spring rather than
    // a slow linear/eased width tween.
    val width by animateDpAsState(
        targetValue = if (expanded) expandedWidth else collapsedWidth,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "notchBubbleWidth"
    )
    val shape = RoundedCornerShape(bubbleHeight / 2)

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        // Tap-away catcher: only present while expanded, so collapsed this
        // is just a small bubble and everything else on screen behaves
        // completely normally. Claims the whole screen's pointer input so a
        // tap anywhere else closes the menu instead of reaching whatever's
        // underneath it.
        if (expanded) {
            Box(
                Modifier.fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = false }
            )
        }

        Box(
            Modifier.width(width).height(bubbleHeight).clip(shape)
                .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape) else Modifier.background(Color.White.copy(0.10f)))
        ) {
            if (!expanded) {
                Box(
                    Modifier.matchParentSize().clickable { tap(); expanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PhotoCamera, contentDescription = "Camera / VRM",
                        tint = Color.White.copy(0.55f), modifier = Modifier.height(cutoutHeight * 0.6f)
                    )
                }
            } else {
                Row(Modifier.matchParentSize()) {
                    Box(
                        Modifier.width(sideWidth).fillMaxHeight()
                            .clickable { tap(); expanded = false; onOpenCamera() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Camera", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    // The notch's own space, centered between the two labels —
                    // tapping it (same as tapping away) just closes the menu.
                    Box(
                        Modifier.width(collapsedWidth).fillMaxHeight()
                            .clickable { tap(); expanded = false }
                    )
                    Box(
                        Modifier.width(sideWidth).fillMaxHeight()
                            .clickable { tap(); expanded = false; onOpenVrm() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("VRM", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
