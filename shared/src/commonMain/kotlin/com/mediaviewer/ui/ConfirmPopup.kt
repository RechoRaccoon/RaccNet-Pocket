package com.mediaviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.theme.OffBlack
import com.mediaviewer.util.rememberHapticTap

/**
 * The app's confirmation popup — the same one the composer shows for
 * "Remove this image?": centred glass in the page's color that blurs
 * whatever is behind it, popping in with a haptic, with Cancel and a red
 * confirm button. Shared so every "are you sure?" in the app looks and
 * feels the same (deleting a list, Follow All, Block All …).
 *
 * Place it as the last child of a full-size Box; tapping outside, Cancel or
 * Back dismisses it.
 */
@Composable
fun ConfirmPopup(
    title: String,
    message: String,
    confirmLabel: String,
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** An image shown above the title (what's being removed). */
    preview: Any? = null,
    cancelLabel: String = "Cancel",
    /** Red confirm button (delete/remove/block) or the page's own color. */
    destructive: Boolean = true,
    /** The confirm action is running: shows a spinner, taps are ignored. */
    busy: Boolean = false
) {
    BackHandler(onBack = { if (!busy) onDismiss() })
    val tap = rememberHapticTap()
    val view = com.mediaviewer.ui.compat.rememberPlatformView()
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CONTEXT_CLICK)
        appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 520f))
    }
    val shape = RoundedCornerShape(22.dp)
    val confirmColor = if (destructive) Color(0xFFE0245E) else tint
    val confirmText = if (destructive) Color(0xFFFF6B8A) else Color.White
    Box(
        modifier.fillMaxSize()
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!busy) onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        val cardModifier = Modifier
            .padding(horizontal = 36.dp).widthIn(max = 360.dp).fillMaxWidth()
            .graphicsLayer {
                val sc = 0.88f + 0.12f * appear.value
                scaleX = sc; scaleY = sc
            }
            // Swallow taps on the card itself so only the scrim dismisses.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
        val content: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (preview != null) {
                    AsyncImage(
                        model = preview, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
                            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(message, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pill = RoundedCornerShape(19.dp)
                    val cancelMod = Modifier.weight(1f).height(38.dp).clip(pill).clickable(enabled = !busy) { tap(); onDismiss() }
                    val confirmMod = Modifier.weight(1f).height(38.dp).clip(pill).clickable(enabled = !busy) {
                        view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.LONG_PRESS)
                        onConfirm()
                    }
                    val confirmContent: @Composable () -> Unit = {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = confirmText, strokeWidth = 1.5.dp)
                        else Text(confirmLabel, color = confirmText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    if (liquidGlass) {
                        LiquidGlassSurface(cancelMod, shape = pill, tint = tint, contentAlignment = Alignment.Center) {
                            Text(cancelLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        LiquidGlassSurface(confirmMod, shape = pill, tint = confirmColor, contentAlignment = Alignment.Center) { confirmContent() }
                    } else {
                        Box(cancelMod.background(Color.White.copy(0.10f)).border(1.dp, Color.White.copy(0.18f), pill), contentAlignment = Alignment.Center) {
                            Text(cancelLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(confirmMod.background(confirmColor.copy(0.22f)).border(1.dp, confirmColor.copy(0.6f), pill), contentAlignment = Alignment.Center) { confirmContent() }
                    }
                }
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(cardModifier, shape = shape, tint = tint, backdrop = backdrop) {
                // A little extra dark behind the text, like the app's other
                // popups, so it reads over any photo.
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.3f)))
                content()
            }
        } else {
            Box(
                cardModifier.clip(shape)
                    .background(androidx.compose.ui.graphics.lerp(OffBlack, tint, 0.14f))
                    .border(1.dp, androidx.compose.ui.graphics.lerp(tint, Color.White, 0.3f).copy(alpha = 0.5f), shape)
            ) { content() }
        }
    }
}
