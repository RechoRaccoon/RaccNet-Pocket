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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** The popup's title row: title centered, a round X on the right. */
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
    Box(modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = 44.dp)) {
            Text(
                title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 1
            )
            if (subtitle != null) {
                Spacer(Modifier.size(6.dp))
                Text(
                    subtitle, color = lerp(tint, Color.White, 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
        PopupCloseBubble(liquidGlass, tint, backdrop, onClose, Modifier.align(Alignment.CenterEnd))
    }
}

/** Round X that closes a popup. */
@Composable
fun PopupCloseBubble(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tap = rememberHapticTap()
    val m = modifier.size(34.dp).clip(CircleShape).clickable { tap(); onClose() }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = m, shape = CircleShape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    } else {
        Box(m.background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** A text field's rounded well inside a popup, in the same style as the
 *  app's other inputs (dark well, colored rim). */
fun Modifier.popupFieldWell(tint: Color, shape: Shape = RoundedCornerShape(22.dp)): Modifier =
    this.clip(shape)
        .background(Color.Black.copy(alpha = 0.28f))
        .border(1.dp, lerp(tint, Color.White, 0.25f).copy(alpha = 0.7f), shape)
