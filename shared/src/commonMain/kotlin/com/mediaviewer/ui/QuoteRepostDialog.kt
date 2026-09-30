package com.mediaviewer.ui

import com.mediaviewer.ui.compat.navBarSpace

import com.mediaviewer.ui.compat.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap

private const val BSKY_POST_LIMIT = 300

/**
 * Quote Repost — same treatment as Share To: rendered in-place so its glass
 * live-blurs the post (whose own UI fades away meanwhile), fades/scales in
 * and out via [FadingPopupHost], sits at the bottom of the screen and rides
 * up on top of the keyboard while typing.
 */
@Composable
fun QuoteRepostDialog(
    target: MediaItem?,
    submitting: Boolean,
    liquidGlass: Boolean,
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (target == null) return
    var text by remember(target.id) { mutableStateOf("") }
    val tint = dominantColor
    val overLimit = text.length > BSKY_POST_LIMIT
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Opens straight into typing: focus the field and explicitly raise the
    // keyboard (requestFocus alone doesn't always show it on every IME).
    LaunchedEffect(target.id) {
        kotlinx.coroutines.delay(120)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    // The popup lives on top of the keyboard: once the keyboard has been up,
    // closing it (Back, the keyboard's own hide key, a swipe) closes the
    // popup too.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var keyboardWasShown by remember(target.id) { mutableStateOf(false) }
    LaunchedEffect(imeBottom > 0) {
        if (imeBottom > 0) keyboardWasShown = true
        else if (keyboardWasShown && !submitting) onDismiss()
    }
    // Safety net: if the keyboard never comes up at all (hardware keyboard,
    // unusual IME), Back still closes the popup.
    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = if (liquidGlass) 0.22f else 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(top = rememberTopCutoutClearance())
            // Sits right on top of the keyboard.
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navBarSpace))
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        PopupSheetSurface(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.fillMaxWidth()) {
                PopupSheetHeader(
                    title = "Quote Repost",
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onClose = onDismiss
                )
                PopupMessageBox(
                    thumbUrl = target.thumbUrl.ifBlank { target.mediaUrl },
                    value = text, onValueChange = { text = it },
                    placeholder = "Add a comment (optional)…",
                    tint = tint,
                    canSend = !overLimit && !submitting,
                    sending = submitting,
                    onSend = { if (!overLimit && !submitting) onSubmit(text.trim()) },
                    counter = "${text.length}/$BSKY_POST_LIMIT",
                    counterOver = overLimit,
                    maxLines = 8,
                    focusRequester = focus,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 4.dp)
                )
            }
        }
    }
}
