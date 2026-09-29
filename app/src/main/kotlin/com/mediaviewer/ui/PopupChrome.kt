package com.mediaviewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.ui.theme.OffBlack
import com.mediaviewer.util.rememberHapticTap

/**
 * Hosts one of the post popups (Share To, Quote Repost, Add To): fades and
 * gently scales in over the post while the post's own UI fades away (see
 * MainFeedScreen's popupOpen), and plays the same thing in reverse when it
 * closes — the last [target] is kept on screen while it fades out.
 */
@Composable
fun <T : Any> FadingPopupHost(
    target: T?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    // Plain (non-state) holder: remembers what to keep drawing while the
    // popup fades out after its target has already gone null.
    val holder = remember { arrayOfNulls<Any>(1) }
    if (target != null) holder[0] = target
    AnimatedVisibility(
        visible = target != null,
        enter = fadeIn(tween(240)) +
            scaleIn(tween(300, easing = FastOutSlowInEasing), initialScale = 0.94f) +
            slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 14 },
        exit = fadeOut(tween(190)) +
            scaleOut(tween(220, easing = FastOutSlowInEasing), targetScale = 0.96f) +
            slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it / 18 },
        modifier = modifier.fillMaxSize()
    ) {
        @Suppress("UNCHECKED_CAST")
        val shown = (target ?: holder[0]) as T?
        if (shown != null) content(shown)
    }
}

/** A popup sheet's surface: live-blurred glass in the post's own color, or
 *  the flat dark panel when Glass Theme is off. */
@Composable
fun PopupSheetSurface(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val absorb = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = modifier.then(absorb), shape = shape, tint = tint, backdrop = backdrop, content = content)
    } else {
        Box(
            modifier
                .clip(shape)
                .background(Brush.verticalGradient(listOf(lerp(OffBlack, tint, 0.14f), OffBlack)))
                .border(1.dp, lerp(tint, Color.White, 0.3f).copy(alpha = 0.5f), shape)
                .then(absorb),
            content = content
        )
    }
}

/** A soft, rounded dark backing behind text sitting on a popup's glass, so
 *  titles, names and handles stay readable over any post color. */
fun Modifier.popupTextShadow(shape: Shape = RoundedCornerShape(12.dp)): Modifier =
    this.clip(shape).background(Color.Black.copy(alpha = 0.32f))

/** The popup's title row: a plain round X on the left, the title centered
 *  on its own dimmed backing. */
@Composable
fun PopupSheetHeader(
    title: String,
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Box(modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 44.dp)
                .popupTextShadow().padding(horizontal = 14.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 1
            )
            if (subtitle != null) {
                Spacer(Modifier.size(6.dp))
                Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        PopupCloseBubble(liquidGlass, tint, backdrop, onClose, Modifier.align(Alignment.CenterStart))
    }
}

/** Round X that closes a popup — a plain dark bubble with a white X (not
 *  tinted), the same everywhere. */
@Composable
fun PopupCloseBubble(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tap = rememberHapticTap()
    Box(
        modifier.size(34.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.34f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable { tap(); onClose() },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/**
 * The message box shared by Share with and Quote Repost: the post's
 * thumbnail, a text field that grows as you type (up to [maxLines]), an
 * optional character counter inside on the right, and the send button.
 */
@Composable
fun PopupMessageBox(
    thumbUrl: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tint: Color,
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    counter: String? = null,
    counterOver: Boolean = false,
    maxLines: Int = 5,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null
) {
    val tap = rememberHapticTap()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .popupFieldWell(tint, RoundedCornerShape(25.dp))
            .padding(start = 7.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        if (thumbUrl.isNotBlank()) {
            coil.compose.AsyncImage(
                model = thumbUrl, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
            )
            Spacer(Modifier.width(10.dp))
        } else Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).heightIn(min = 38.dp), contentAlignment = Alignment.CenterStart) {
            androidx.compose.foundation.text.BasicTextField(
                value = value, onValueChange = onValueChange,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 19.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                maxLines = maxLines,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            )
            if (value.isEmpty()) Text(
                placeholder, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        if (counter != null) {
            Text(
                counter, color = if (counterOver) Color(0xFFFF6B8A) else Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 12.dp)
            )
        } else Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(lerp(tint, Color.White, 0.12f).copy(alpha = if (canSend) 0.95f else 0.3f))
                .clickable(enabled = canSend && !sending) { tap(); onSend() },
            contentAlignment = Alignment.Center
        ) {
            if (sending) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White.copy(alpha = if (canSend) 1f else 0.5f), modifier = Modifier.size(19.dp))
        }
    }
}

/** A text field's rounded well inside a popup, in the same style as the
 *  app's other inputs (dark well, colored rim). */
fun Modifier.popupFieldWell(tint: Color, shape: Shape = RoundedCornerShape(22.dp)): Modifier =
    this.clip(shape)
        .background(Color.Black.copy(alpha = 0.28f))
        .border(1.dp, lerp(tint, Color.White, 0.25f).copy(alpha = 0.7f), shape)
