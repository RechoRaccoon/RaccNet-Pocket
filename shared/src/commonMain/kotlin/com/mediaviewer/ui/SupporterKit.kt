package com.mediaviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.util.StellarOfficial
import com.mediaviewer.util.Supporter
import com.mediaviewer.util.rememberHapticTap

/** The supporter pink (#FF4FA1). */
val SupporterPink = Color(StellarOfficial.SUPPORTER_COLOR)

/**
 * Paints whatever this is applied to (an icon, a label, a whole button's
 * content) in the supporter pink with the same highlight sweeping across it
 * as the profile's "Supporter" label — every supporter-colored button in
 * the app uses this, so they all shimmer alike.
 */
fun Modifier.supporterShine(
    enabled: Boolean = true,
    /** False: only the sweeping highlight is added, over content that is
     *  already pink (a switch's track, a filled button). */
    recolor: Boolean = true
): Modifier = if (!enabled) this else composed {
    val base = if (recolor) SupporterPink else Color.Transparent
    val sweep by rememberInfiniteTransition().animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart)
    )
    this
        // Its own layer, so the pink only lands on what was drawn here.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = size.width.coerceAtLeast(1f)
            drawRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(0f to base, 0.42f to base, 0.5f to Color.White.copy(alpha = if (recolor) 1f else 0.85f), 0.58f to base, 1f to base),
                    start = Offset((sweep - 1f) * w, 0f),
                    end = Offset((sweep + 1f) * w, w * 0.35f)
                ),
                blendMode = BlendMode.SrcAtop
            )
        }
}

/** Shimmering pink for everyone who isn't a supporter; untouched for supporters. */
@Composable
fun Modifier.supporterLocked(): Modifier = supporterShine(enabled = !Supporter.active)

/**
 * A centred glass popup in the app's style: dimmed backdrop (tap to close),
 * a title row with the round X, then [content]. Rises above the keyboard.
 */
@Composable
fun LocalPopup(
    title: String,
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 420.dp,
    maxHeight: Dp = 560.dp,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onClose)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 480f)) }
    Box(
        modifier.fillMaxSize()
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        PopupSheetSurface(
            liquidGlass = liquidGlass, tint = tint, backdrop = null,
            modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth().heightIn(max = maxHeight)
                .graphicsLayer {
                    val sc = 0.92f + 0.08f * appear.value
                    scaleX = sc; scaleY = sc
                }
        ) {
            // A little extra dark so text reads over any color.
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.28f)))
            Column(Modifier.fillMaxWidth()) {
                PopupSheetHeader(title = title, liquidGlass = liquidGlass, tint = tint, backdrop = null, onClose = onClose, subtitle = subtitle)
                Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 6.dp), content = content)
            }
        }
    }
}

/** A text field in a popup's rounded well. */
@Composable
fun LocalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tint: Color,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = 44.dp,
    maxHeight: Dp = Dp.Unspecified,
    focusRequester: FocusRequester? = null,
    fontSize: Int = 14,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences
) {
    Box(
        modifier.fillMaxWidth().heightIn(min = minHeight, max = maxHeight)
            .popupFieldWell(tint, RoundedCornerShape(if (singleLine) 22.dp else 16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
    ) {
        BasicTextField(
            value = value, onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = Color.White, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(capitalization = capitalization),
            modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        )
        if (value.isEmpty()) Text(
            placeholder, color = Color.White.copy(alpha = 0.45f), fontSize = fontSize.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** A pill button in the app's glass style. */
@Composable
fun LocalPillButton(
    label: String,
    liquidGlass: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    height: Dp = 40.dp,
    /** Shimmering pink label (a supporter-only action shown to a non-supporter). */
    locked: Boolean = false
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(height / 2)
    val color = if (destructive) Color(0xFFE0245E) else tint
    val textColor = (if (destructive) Color(0xFFFF6B8A) else Color.White).copy(alpha = if (enabled) 1f else 0.4f)
    val m = modifier.height(height).clip(shape).clickable(enabled = enabled) { tap(); onClick() }
    val text: @Composable () -> Unit = {
        Text(
            label, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp).supporterShine(locked)
        )
    }
    if (liquidGlass) {
        LiquidGlassSurface(m, shape = shape, tint = color, contentAlignment = Alignment.Center) { text() }
    } else {
        Box(
            m.background(color.copy(alpha = if (destructive) 0.22f else 0.16f))
                .border(1.dp, lerp(color, Color.White, 0.25f).copy(alpha = 0.55f), shape),
            contentAlignment = Alignment.Center
        ) { text() }
    }
}

/** A small on/off chip (Feed Builder's "include" options, tabs…). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LocalChip(
    label: String,
    selected: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(15.dp)
    Box(
        modifier.height(30.dp).clip(shape)
            .background(if (selected) lerp(tint, Color.White, 0.15f).copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.28f))
            .border(1.dp, lerp(tint, Color.White, 0.3f).copy(alpha = if (selected) 0.9f else 0.35f), shape)
            .combinedClickable(
                onClick = { tap(); onClick() },
                onLongClick = if (onLongClick != null) ({ tap(); onLongClick() }) else null
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = Color.White.copy(alpha = if (selected) 1f else 0.7f), fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1
        )
    }
}

/** Lays [content] out at its natural height and, if that's taller than the
 *  space available, scales it down (from the top) so it always fits on one
 *  screen instead of scrolling. */
@Composable
internal fun ScaleToFit(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.first().measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        )
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
        val scale = if (placeable.height > maxH && placeable.height > 0) maxH.toFloat() / placeable.height else 1f
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        layout(width, maxH.coerceAtLeast(0)) {
            placeable.placeWithLayer((width - placeable.width) / 2, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }
        }
    }
}
