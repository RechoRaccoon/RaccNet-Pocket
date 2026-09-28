package com.mediaviewer.ui

import androidx.activity.compose.BackHandler
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
import coil.compose.AsyncImage
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
    val tap = rememberHapticTap()
    val tint = dominantColor
    val overLimit = text.length > BSKY_POST_LIMIT
    val focus = remember { FocusRequester() }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = if (liquidGlass) 0.22f else 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .imePadding()
            .padding(top = rememberTopCutoutClearance() + 8.dp)
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        PopupSheetSurface(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                PopupSheetHeader(
                    title = "Quote Repost",
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onClose = onDismiss
                )

                // The post being quoted.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val thumb = target.thumbUrl.ifBlank { target.mediaUrl }
                    if (thumb.isNotBlank()) {
                        AsyncImage(
                            model = thumb, contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(target.author.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${target.author.handle}", color = DimGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (target.text.isNotBlank()) {
                            Text(target.text, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, lineHeight = 15.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }

                // Your comment — the same dark, colored-rim well as the Share To box.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .heightIn(min = 90.dp)
                        .popupFieldWell(tint, RoundedCornerShape(18.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            runCatching { focus.requestFocus() }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    BasicTextField(
                        value = text, onValueChange = { text = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(Color.White),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                    if (text.isEmpty()) Text("Add a comment (optional)…", color = DimGray, fontSize = 15.sp)
                }

                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${text.length}/$BSKY_POST_LIMIT",
                        color = if (overLimit) Color(0xFFE0245E) else DimGray,
                        fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                    val enabled = !overLimit && !submitting
                    Box(
                        Modifier
                            .height(42.dp)
                            .widthIn(min = 110.dp)
                            .clip(RoundedCornerShape(21.dp))
                            .background(
                                if (enabled) lerp(RepostGreen, tint, 0.25f)
                                else Color.White.copy(alpha = 0.08f)
                            )
                            .clickable(enabled = enabled) { tap(); onSubmit(text.trim()) }
                            .padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (submitting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Post", color = if (enabled) Color.White else DimGray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
