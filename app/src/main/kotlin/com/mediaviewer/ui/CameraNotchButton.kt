package com.mediaviewer.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mediaviewer.util.rememberHapticTap

/**
 * Item 8: the phone's real front-camera cutout, turned into a button.
 *
 * Collapsed, it's a small bubble hugging the actual display cutout — a
 * tight outline (see [outlinePadding]) so it visibly reads as a tappable
 * bubble rather than just decoration drawn around the notch. Tapping it
 * gives a deep haptic tap and expands the bubble horizontally in *both*
 * directions at once — the notch's own position never moves, the outline
 * just grows outward around it — revealing a "Camera" text button on the
 * left and a "VRM" text button on the right. Both halves are the same
 * fixed width and each centers its own label within its own half, so the
 * notch still reads as dividing one continuous bubble into two separate
 * buttons even though it's visually one shape. Tapping the notch again,
 * or anywhere else on screen, smoothly collapses it back with no action
 * taken.
 *
 * Positioning/sizing come from the device's *real* [android.view.DisplayCutout]
 * (via the root view's window insets) rather than a guessed constant: the
 * bubble is offset so its center sits on the cutout rect's own center, so
 * it actually hugs whatever cutout shape/size/position this specific
 * device has — not just the screen's top-center. Devices with no cutout
 * (most emulators, some tablets) fall back to a small fixed size near the
 * top-center so the button still renders sensibly.
 *
 * Colors follow the app's adaptive [tint] (the feed's current dominant
 * color): the bubble fill and outline are tinted in both glass and flat
 * modes, so it reads as part of the adaptive UI rather than a floating
 * white pill.
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
    // No-cutout fallback — small and near the very top, since it should
    // only ever be seen on a device/emulator that genuinely has no cutout
    // to hug at all.
    var cutoutWidth by remember { mutableStateOf(20.dp) }
    var cutoutHeight by remember { mutableStateOf(20.dp) }
    var cutoutCenterX by remember { mutableStateOf<Dp?>(null) }
    var cutoutCenterY by remember { mutableStateOf<Dp?>(null) }
    // Was a one-shot LaunchedEffect reading ViewCompat.getRootWindowInsets
    // exactly once: on real devices the very first composition frequently
    // runs *before* the system has dispatched WindowInsets to this view at
    // all, so that single read came back null and the button was stuck on
    // the no-cutout fallback forever. A persistent
    // OnApplyWindowInsetsListener (plus an explicit requestApplyInsets() to
    // make sure one dispatch actually happens) instead keeps picking up
    // the real cutout whenever insets do arrive/change — first layout,
    // rotation, fold, etc. — not just once.
    DisposableEffect(view) {
        fun applyFrom(insets: WindowInsetsCompat?) {
            val cutout = insets?.displayCutout ?: return
            val rect = cutout.boundingRects.firstOrNull { it.width() > 0 && it.height() > 0 }
            if (rect != null) {
                with(density) {
                    cutoutWidth = rect.width().toDp()
                    cutoutHeight = rect.height().toDp()
                    cutoutCenterX = rect.centerX().toDp()
                    cutoutCenterY = rect.centerY().toDp()
                }
                return
            }
            // Some OEM skins report an empty boundingRects list for a
            // punch-hole front camera even though the cutout genuinely
            // exists — DisplayCutout's safe-inset fields are a second,
            // independent way the platform exposes the same cutout and
            // are worth trying before giving up and falling back to a
            // guessed size/position entirely.
            val safeTop = cutout.safeInsetTop
            val safeLeft = cutout.safeInsetLeft
            val safeRight = cutout.safeInsetRight
            if (safeTop > 0 && (safeLeft > 0 || safeRight > 0)) {
                with(density) {
                    cutoutHeight = safeTop.toDp()
                    cutoutWidth = safeTop.toDp() // no width signal from safe insets alone — approximate as square, closer than the generic no-cutout fallback
                    cutoutCenterY = (safeTop / 2).toDp()
                    cutoutCenterX = null // still unknown — stays screen-center horizontally
                }
            }
        }
        // Covers the case insets are already available by now.
        applyFrom(ViewCompat.getRootWindowInsets(view))
        val listener = OnApplyWindowInsetsListener { _, insets ->
            applyFrom(insets)
            insets // don't consume — other views still need the real insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(view, listener)
        view.requestApplyInsets()
        onDispose { ViewCompat.setOnApplyWindowInsetsListener(view, null) }
    }

    val outlinePadding = 4.dp
    val sideWidth = 56.dp
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

    // Offset from top-center so the bubble's center lands on the cutout's
    // own center — the cutout is rarely at the exact horizontal middle on
    // real phones, and never at y=0 vertically. No-cutout fallback keeps
    // the old top-center spot with a small top margin.
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val xOffset = cutoutCenterX?.let { it - screenWidth / 2 } ?: 0.dp
    val yOffset = cutoutCenterY?.let { it - bubbleHeight / 2 } ?: 12.dp

    // Expanded, this container takes the whole screen so the tap-away
    // catcher below genuinely covers everything; collapsed it's just the
    // bubble's own strip.
    Box(
        modifier.then(if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth()),
        contentAlignment = Alignment.TopCenter
    ) {
        // Tap-away catcher: only present while expanded. Claims the whole
        // screen's pointer input so a tap anywhere else closes the menu
        // instead of reaching whatever's underneath it.
        if (expanded) {
            Box(
                Modifier.fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = false }
            )
        }

        Box(
            Modifier.offset(x = xOffset, y = yOffset).width(width).height(bubbleHeight).clip(shape)
                .then(
                    if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                    // Bumped up from 0.22f/0.55f — at the old alphas the
                    // tint was hard to actually see against most feed
                    // backgrounds, which likely reads as "not reflecting
                    // the profile color" even though the same tint value
                    // every other adaptive-color element uses is wired
                    // through correctly here too.
                    else Modifier.background(tint.copy(alpha = 0.32f)).border(1.5.dp, tint.copy(alpha = 0.75f), shape)
                )
        ) {
            if (!expanded) {
                Box(
                    Modifier.matchParentSize().clickable { tap(); expanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PhotoCamera, contentDescription = "Camera / VRM",
                        tint = Color.White.copy(0.85f), modifier = Modifier.height(cutoutHeight * 0.55f)
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
