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
            // Prefer the SMALLEST non-empty rect — some devices report a
            // loose rect that covers the whole notch area; the smallest
            // one hugs the actual camera lens best.
            val rect = cutout.boundingRects
                .filter { it.width() > 0 && it.height() > 0 }
                .minByOrNull { it.width() * it.height() }
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

    // Collapsed, this is a bare outline ring. It's always a CIRCLE (never
    // an oval): diameter = the larger cutout dimension + 1dp per side,
    // Ring diameter: use the SMALLER cutout dimension + padding. For a
    // punch-hole the rect is square so it doesn't matter; for a wide
    // notch, the height (not the width) approximates the lens diameter.
    // Using maxOf() here made the ring huge on wide cutouts. Capped at
    // 28dp so a pathological rect can't blow it up — the cap is a safety
    // bound, not a device tune; the size still comes from the system's
    // own measurement.
    val outlinePadding = 1.dp
    val sideWidth = 56.dp
    val ringSize = minOf(minOf(cutoutWidth, cutoutHeight) + outlinePadding * 2, 28.dp)
    val collapsedWidth = ringSize
    val expandedWidth = collapsedWidth + sideWidth * 2
    // Smooth but snappy, no bounce: a fast non-bouncy spring rather than
    // the old overshooting one.
    val width by animateDpAsState(
        targetValue = if (expanded) expandedWidth else collapsedWidth,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
        label = "notchBubbleWidth"
    )
    // Collapsed the ring is a circle; expanded it stretches into a pill.
    // CircleShape keeps the collapsed state perfectly round regardless of
    // the cap — no oval.
    val collapsedShape = CircleShape
    val expandedShape = RoundedCornerShape(50)
    val shape = if (expanded) expandedShape else collapsedShape

    // Absolute positioning from the screen's top-left corner — NOT relative
    // to a centered parent. The cutout rect is already in screen/pixel
    // coordinates (edge-to-edge window, so window == screen), and this
    // container is always fillMaxSize with TopStart alignment, so
    // offset(x, y) lands the ring's center exactly on the cutout's center.
    // No other UI element's position, padding, or alignment can push it.
    //
    // NO manual nudge: the ring centers on the system's reported cutout
    // rect, whatever it is on this device. A hardcoded dp offset would be
    // tuned for one phone and wrong on others — the rect is the platform's
    // own measurement of where the cutout is, so trusting it is the only
    // device-agnostic positioning.
    // Position: center the ring on the system's cutout rect center.
    // No manual nudge — the rect is the platform's own measurement of
    // where the cutout is. A hardcoded dp offset would be tuned for one
    // device and wrong on others. If the rect is slightly off, that's the
    // system's data; trusting it is the only device-agnostic positioning.
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val yAbsolute = cutoutCenterY?.let { it - ringSize / 2 } ?: 12.dp
    // The bubble is ALWAYS centered on the cutout's center X, using the
    // CURRENT ANIMATED width — not a switched target. This keeps x and
    // width in sync during the animation so the pill grows symmetrically
    // outward from the ring instead of jumping sideways. (The old code
    // switched x instantly while width animated, which read as a
    // different bubble sliding in from the left.)
    val centerX = cutoutCenterX ?: screenWidth / 2
    val xAbsolute = centerX - width / 2

    // Always fillMaxSize so the coordinate system is the whole screen and
    // the tap-away catcher (expanded) genuinely covers everything.
    Box(
        modifier.then(Modifier.fillMaxSize()),
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
            Modifier.offset(
                x = xAbsolute,
                y = yAbsolute
            ).width(width).height(ringSize).clip(shape)
                .then(
                    if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                    // Flat mode: a translucent tinted fill under a thin
                    // (1dp) tinted ring — the ring is the whole visual in
                    // the collapsed state, so it stays hairline rather than
                    // chunky.
                    else Modifier.background(tint.copy(alpha = 0.28f)).border(1.dp, tint.copy(alpha = 0.8f), shape)
                )
        ) {
            if (!expanded) {
                // Bare ring — the tappable area is the whole bubble, no
                // inner icon (see outlinePadding's comment).
                Box(
                    Modifier.matchParentSize().clickable { tap(); expanded = true }
                )
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
