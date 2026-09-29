package com.mediaviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mediaviewer.model.DmConversation
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap

/**
 * Share To: send the current post to people in your DMs.
 *
 * Rendered in-place (never a separate Dialog window) so its glass can
 * live-blur the post behind it — the post's own UI fades away while this is
 * open (see MainFeedScreen's popupOpen), and the whole thing fades/scales in
 * and out via [FadingPopupHost] at the call site.
 *
 * Up to three people across, each with room for a two-line name and their
 * handle; the message box is one compact pill at the bottom of the sheet
 * (the post's thumbnail, the text, the send button), and the sheet rides up
 * on top of the keyboard when you type.
 */
@Composable
fun SendDmDialog(
    target: MediaItem?,
    conversations: List<DmConversation>,
    loading: Boolean,
    selected: Set<String>,
    sending: Boolean,
    liquidGlass: Boolean,
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    onToggleSelect: (String) -> Unit,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (target == null) return
    var message by remember(target.id) { mutableStateOf("") }
    val tap = rememberHapticTap()
    val thumbUrl = target.thumbUrl.ifBlank { target.mediaUrl }
    val tint = dominantColor

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier.fillMaxSize()
            // A light dim (heavier without glass) so the sheet stands out
            // from the post; tapping it closes the popup.
            .background(Color.Black.copy(alpha = if (liquidGlass) 0.22f else 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            // Top: just under the camera notch (where the Hub's search bar
            // sits). Bottom: above the navigation bar — or the keyboard,
            // which pushes it up while typing.
            .padding(top = rememberTopCutoutClearance())
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        PopupSheetSurface(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().fillMaxHeight()
        ) {
            Column(Modifier.fillMaxSize()) {
                PopupSheetHeader(
                    title = "Share with",
                    subtitle = if (selected.isNotEmpty()) "· ${selected.size}" else null,
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onClose = onDismiss
                )

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading && conversations.isEmpty() ->
                            CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp), color = Color.White, strokeWidth = 1.5.dp)
                        conversations.isEmpty() ->
                            Text("No conversations yet", color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.align(Alignment.Center).popupTextShadow().padding(horizontal = 12.dp, vertical = 6.dp))
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(conversations, key = { it.member.did }) { convo ->
                                RecipientCell(
                                    convo = convo,
                                    isSelected = selected.contains(convo.member.did),
                                    tint = tint,
                                    onTap = {
                                        tap()
                                        onToggleSelect(convo.member.did)
                                    }
                                )
                            }
                        }
                    }
                }

                PopupMessageBox(
                    thumbUrl = thumbUrl,
                    value = message, onValueChange = { message = it },
                    placeholder = if (selected.isEmpty()) "Pick someone, then add a message…" else "Add a message…",
                    tint = tint,
                    canSend = selected.isNotEmpty() && !sending,
                    sending = sending,
                    onSend = { if (selected.isNotEmpty() && !sending) onSend(message.trim()) },
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun RecipientCell(convo: DmConversation, isSelected: Boolean, tint: Color, onTap: () -> Unit) {
    val cellShape = RoundedCornerShape(18.dp)
    val pop by animateFloatAsState(
        if (isSelected) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 500f), label = "recipientPop"
    )
    // Their own profile color for the selection ring and check.
    val ringColor = if (isSelected && !convo.isGroup && convo.member.did.isNotBlank())
        lerp(rememberAuthorProfileTint(convo.member.did, convo.member.avatarUrl), Color.White, 0.3f)
    else lerp(tint, Color.White, 0.35f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(cellShape)
            .background(if (isSelected) ringColor.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onTap)
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            if (convo.isGroup) {
                DmConvoAvatar(convo, 66.dp)
            } else Box(Modifier.size(66.dp).clip(CircleShape).background(Color.White.copy(0.12f))) {
                if (convo.member.avatarUrl != null) {
                    AsyncImage(model = convo.member.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape))
                }
            }
            if (pop > 0.01f) {
                // Group chats' avatars are a cluster, not a circle — no ring
                // around them, just the check.
                if (!convo.isGroup) {
                    Box(
                        Modifier.size(74.dp)
                            .graphicsLayer { alpha = pop.coerceIn(0f, 1f); scaleX = 0.9f + 0.1f * pop; scaleY = 0.9f + 0.1f * pop }
                            .border(2.5.dp, ringColor, CircleShape)
                    )
                }
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .graphicsLayer { scaleX = pop; scaleY = pop }
                        .size(22.dp).clip(CircleShape).background(ringColor)
                        .border(1.5.dp, Color(0xFF101014), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF101014), modifier = Modifier.size(14.dp)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier.fillMaxWidth().popupTextShadow(RoundedCornerShape(10.dp)).padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                convo.member.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            val sub = if (convo.isGroup) "${convo.memberCount} people" else if (convo.member.handle.isNotBlank()) "@${convo.member.handle}" else ""
            if (sub.isNotBlank()) {
                Text(
                    sub, color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
