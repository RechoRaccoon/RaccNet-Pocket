package com.mediaviewer.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.mediaviewer.model.DmConversation
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*

// Item 10: the whole Share page now uses the same liquid-glass language as
// the rest of the app (when the Glass Theme setting is on) — the sheet
// itself, the message bar, and each recipient's selection ring.
@Composable
fun SendDmDialog(
    target: MediaItem?,
    conversations: List<DmConversation>,
    loading: Boolean,
    selected: Set<String>,
    sending: Boolean,
    liquidGlass: Boolean,
    onToggleSelect: (String) -> Unit,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (target == null) return
    var message by remember(target.id) { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val backdropUrl = target.thumbUrl.ifBlank { target.mediaUrl }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .then(
                    if (liquidGlass) Modifier.background(postBackgroundBrush(NeutralGlassTint))
                    else Modifier.background(Color.Black.copy(alpha = 0.65f))
                )
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val sheetModifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }

            @Composable
            fun SheetContent() {
                Column(Modifier.fillMaxSize()) {
                    Text("Send", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)

                    // Scrollable, infinitely-growing recipient grid — 3 across, roomy enough
                    // for the selection outline to show clearly between icons.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            loading && conversations.isEmpty() ->
                                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 1.5.dp)
                            conversations.isEmpty() ->
                                Text("No conversations yet", color = DimGray, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                            else -> LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(conversations, key = { it.member.did }) { convo ->
                                    RecipientCell(
                                        convo = convo,
                                        isSelected = selected.contains(convo.member.did),
                                        onTap = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onToggleSelect(convo.member.did)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (!liquidGlass) HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)

                    // Message input — separate from the scrollable recipient grid.
                    // Grows exactly like the comment box: no minLines, capped at 3 rows.
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = if (liquidGlass) 10.dp else 0.dp, vertical = if (liquidGlass) 8.dp else 0.dp)
                            .then(
                                if (liquidGlass) Modifier.glassPanel(true, tint = NeutralGlassTint, shape = RoundedCornerShape(20.dp))
                                else Modifier.background(OffBlack)
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Small preview of the post being shared, far left
                        AsyncImage(
                            model = backdropUrl, contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = message, onValueChange = { message = it },
                            placeholder = { Text("Say something…", color = DimGray, fontSize = 13.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                                cursorColor = Color.White, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent
                            ),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp), maxLines = 3,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { if (selected.isNotEmpty() && !sending) onSend(message.trim()) },
                            enabled = selected.isNotEmpty() && !sending,
                            modifier = Modifier.size(34.dp)
                        ) {
                            if (sending) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            else Icon(Icons.Default.Send, contentDescription = "Send",
                                tint = if (selected.isNotEmpty()) Color.White else DimGray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (liquidGlass) {
                LiquidGlassSurface(
                    modifier = sheetModifier, shape = RoundedCornerShape(20.dp),
                    tint = NeutralGlassTint, backdropUrl = backdropUrl
                ) { SheetContent() }
            } else {
                Box(sheetModifier.clip(RoundedCornerShape(20.dp)).background(OffBlack)) { SheetContent() }
            }
        }
    }
}

@Composable
private fun RecipientCell(convo: DmConversation, isSelected: Boolean, onTap: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onTap)
    ) {
        // Item 6: outer box reserves room for the ring so it draws OUTSIDE the
        // avatar rather than inset into it — the avatar itself stays a constant
        // 64dp regardless of selection state, instead of shrinking when selected.
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape)
            ) {
                if (convo.member.avatarUrl != null) {
                    AsyncImage(model = convo.member.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape))
                } else {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.White.copy(0.12f)))
                }
            }
            if (isSelected) {
                Box(Modifier.size(70.dp).border(2.5.dp, Color.White, CircleShape))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            convo.member.displayName, color = Color.White, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 72.dp)
        )
    }
}
