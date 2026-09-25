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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.util.rememberHapticTap

/**
 * Item 8: the phone's real front-camera cutout, turned into a button.
 *
 * Collapsed, it's a thin outline hugging the actual display cutout —
 * just a ring around the notch itself (see [outlinePadding]), so it
 * reads as part of the phone rather than a floating pill. Tapping it
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
 * Colors follow the [tint] the caller passes in: the feed passes its
 * current dominant color; the hub passes the logged-in user's own profile
 * color — the button itself doesn't decide, it just wears whatever the
 * surrounding UI is already using.
 */
@Composable
fun CameraNotchButton(
    liquidGlass: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    /** False = a passive ring: drawn, but untappable, and touches pass
     *  straight through to whatever is underneath. */
    interactive: Boolean = true,
    onOpenCamera: () -> Unit,
    onOpenVrm: () -> Unit
) {
    val tap = rememberHapticTap()
    var expanded by remember { mutableStateOf(false) }
    // Leaving the Hub/profile while expanded collapses it.
    androidx.compose.runtime.LaunchedEffect(interactive) { if (!interactive) expanded = false }
    val density = LocalDensity.current

    val view = LocalView.current
    val configuration = LocalConfiguration.current

    // Everything in raw window pixels (floats), converted once at draw time.
    var cutoutCenterPx by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var cutoutDiameterPx by remember { mutableStateOf<Float?>(null) }
    // Where this composable's own top-left sits in the window. The cutout is
    // reported in WINDOW coordinates; if anything above us in the layout is
    // offset/padded even slightly, subtracting this cancels it out.
    var originInWindow by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // ── Why it was slightly off ─────────────────────────────────────────
    // 1. It installed its OWN OnApplyWindowInsetsListener on the root
    //    ComposeView — the same single slot Compose's WindowInsets system
    //    uses. That silently replaced Compose's listener (and set it to
    //    null when the button left the screen, e.g. entering VRM mode), so
    //    inset-driven layout elsewhere could go stale. Now it only READS
    //    rootWindowInsets; it never installs a listener.
    // 2. It used DisplayCutout.boundingRects — integer rects that are
    //    rounded outward and can sit a pixel or so off the actual hole.
    //    On Android 12+ the cutout PATH (the real circle) is used instead.
    // 3. Position/size were rounded to whole pixels separately. Now the
    //    size is whole pixels and the leftover fraction is applied as a
    //    sub-pixel translation, so the ring's center is exact.
    fun readCutout() {
        val cutout = view.rootWindowInsets?.displayCutout ?: return
        var bounds: android.graphics.RectF? = null
        val rects = cutout.boundingRects.filter { it.width() > 0 && it.height() > 0 }
        if (android.os.Build.VERSION.SDK_INT >= 31 && rects.size <= 1) {
            cutout.cutoutPath?.let { path ->
                val r = android.graphics.RectF()
                @Suppress("DEPRECATION") path.computeBounds(r, true)
                if (r.width() > 0f && r.height() > 0f) bounds = r
            }
        }
        if (bounds == null) {
            // Smallest rect hugs a punch-hole best on multi-rect devices.
            rects.minByOrNull { it.width() * it.height() }?.let { bounds = android.graphics.RectF(it) }
        }
        val b = bounds?.takeIf { r ->
            // Reject nonsense (off-screen or a path in another coordinate
            // space): the ring would then be drawn where nobody can see it.
            val w = view.rootView.width.toFloat(); val h = view.rootView.height.toFloat()
            (w <= 0f || h <= 0f) || (r.centerX() in 0f..w && r.centerY() in 0f..h && r.width() < w / 2f)
        } ?: rects.minByOrNull { it.width() * it.height() }?.let { android.graphics.RectF(it) }
        if (b != null) {
            cutoutCenterPx = androidx.compose.ui.geometry.Offset(b.centerX(), b.centerY())
            cutoutDiameterPx = minOf(b.width(), b.height())
        } else if (cutout.safeInsetTop > 0) {
            // OEM skins with no rects/path: approximate from the safe inset.
            cutoutCenterPx = null
            cutoutDiameterPx = cutout.safeInsetTop * 0.6f
        }
    }
    // Re-read on rotation / fold / resize.
    androidx.compose.runtime.LaunchedEffect(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        readCutout()
    }

    // Collapsed: a circle hugging the lens, 2dp of outline outside the hole
    // (the hole itself hides anything drawn over it).
    val outlinePadding = 2.dp
    val sideWidth = 56.dp
    val outlinePaddingPx = with(density) { outlinePadding.toPx() }
    val maxRingPx = with(density) { 28.dp.toPx() }
    val ringPxFloat = ((cutoutDiameterPx ?: with(density) { 18.dp.toPx() }) + outlinePaddingPx * 2).coerceAtMost(maxRingPx)
    // Whole pixels for layout; exact centering handled by the translation below.
    val ringPx = kotlin.math.round(ringPxFloat).toInt().coerceAtLeast(1)
    val ringSize = with(density) { ringPx.toDp() }
    val collapsedWidth = ringSize
    val expandedWidth = collapsedWidth + sideWidth * 2
    val width by animateDpAsState(
        targetValue = if (expanded) expandedWidth else collapsedWidth,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
        label = "notchBubbleWidth"
    )
    val collapsedShape = CircleShape
    val expandedShape = RoundedCornerShape(50)
    val shape = if (expanded) expandedShape else collapsedShape
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    /** Exact (float) top-left of the bubble in THIS composable's space. */
    fun bubbleTopLeft(widthPx: Int, fallbackCenterX: Float, fallbackTop: Float): androidx.compose.ui.geometry.Offset {
        val center = cutoutCenterPx
        val cx = (center?.x ?: fallbackCenterX) - originInWindow.x
        val left = cx - widthPx / 2f
        val top = if (center != null) center.y - originInWindow.y - ringPx / 2f else fallbackTop
        return androidx.compose.ui.geometry.Offset(left, top)
    }

    // Always fillMaxSize so the coordinate system is the whole screen and
    // the tap-away catcher (expanded) genuinely covers everything.
    Box(
        modifier.then(Modifier.fillMaxSize())
            .then(Modifier.onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                if (pos != originInWindow) originInWindow = pos
                // Cheap, and catches insets that arrived after first layout.
                if (cutoutCenterPx == null) readCutout()
            }),
        contentAlignment = Alignment.TopStart
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
            Modifier
                .offset {
                    val tl = bubbleTopLeft(width.roundToPx(), screenWidth.toPx() / 2f, 12.dp.toPx())
                    androidx.compose.ui.unit.IntOffset(kotlin.math.floor(tl.x).toInt(), kotlin.math.floor(tl.y).toInt())
                }
                .graphicsLayer {
                    // Sub-pixel remainder, so the ring's center lands exactly
                    // on the lens center instead of up to 1px away.
                    val tl = bubbleTopLeft(width.roundToPx(), screenWidth.toPx() / 2f, 12.dp.toPx())
                    translationX = tl.x - kotlin.math.floor(tl.x)
                    translationY = tl.y - kotlin.math.floor(tl.y)
                }
                .width(width).height(ringSize).clip(shape)
                .then(
                    if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                    // Flat mode: a translucent tinted fill.
                    else Modifier.background(tint.copy(alpha = 0.28f))
                )
                // The ring itself — drawn in BOTH modes, independent of the
                // glass intensity/rim settings, so the collapsed notch is
                // always visible (a faint glass rim alone vanished over dark
                // screens like VRM mode). A light hairline under the tinted
                // one keeps it readable when the tint is itself very dark.
                .border(1.5.dp, Color.White.copy(alpha = 0.35f), shape)
                .border(1.dp, tint.copy(alpha = 0.9f), shape)
        ) {
            if (!expanded && interactive) {
                // Bare ring — the tappable area is the whole bubble, no
                // inner icon (see outlinePadding's comment).
                Box(
                    Modifier.matchParentSize().clickable { tap(); expanded = true }
                )
            } else if (expanded) {
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
