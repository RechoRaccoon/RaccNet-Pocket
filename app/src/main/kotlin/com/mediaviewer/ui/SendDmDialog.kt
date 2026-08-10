package com.mediaviewer.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.mediaviewer.model.DmConversation
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*

// Item 10 / Big Update #10: the whole Share page uses the same liquid-glass
// language as the rest of the app (when the Glass Theme setting is on) — the
// sheet itself, the message bar, and each recipient's selection ring.
//
// This is no longer wrapped in a separate `Dialog` (its own Android window):
// a glass panel's "reflection" is a live sample of the post's own GraphicsLayer,
// and that layer only exists in — and can only be drawn from — the same window
// it was recorded in. Rendered in-place instead (as an overlay directly in the
// same composition as the post, exactly like the long-press quick-shortcuts
// menu), it can share that same backdrop, and in Glass mode there's no outer
// scrim at all, so the post is genuinely visible behind the popup, not just
// dimmed toward it.
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
    val haptic = LocalHapticFeedback.current
    val backdropUrl = target.thumbUrl.ifBlank { target.mediaUrl }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier.fillMaxSize().zIndex(10f)
            .then(
                // Non-glass mode keeps a real dimming scrim since there's no
                // glass surface underneath to separate the popup from the post.
                if (liquidGlass) Modifier else Modifier.background(Color.Black.copy(alpha = 0.65f))
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        // Item 12: full width minus the same 12dp side margin the main page's other
        // glass bubbles (e.g. the interaction bar) use, instead of a narrower 92%
        // fraction — keeps this sheet's edges visually aligned with them.
        val sheetModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
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

                    // Message input — separate from the scrollable recipient grid.
                    // Grows exactly like the comment box: no minLines, capped at 3 rows.
                    @Composable
                    fun MessageBarContent() {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
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
                            // Item 12: a plain clickable box instead of IconButton — Material's
                            // default IconButton draws a bounded ripple that showed as a flat
                            // black square flashing over the clear glass. Icon is bigger too.
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        enabled = selected.isNotEmpty() && !sending,
                                        onClick = { if (selected.isNotEmpty() && !sending) onSend(message.trim()) }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (sending) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                else Icon(Icons.Default.Send, contentDescription = "Send",
                                    tint = if (selected.isNotEmpty()) Color.White else DimGray, modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    if (liquidGlass) {
                        LiquidGlassSurface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(20.dp), tint = dominantColor, backdrop = backdrop
                        ) { MessageBarContent() }
                    } else {
                        HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)
                        Box(Modifier.fillMaxWidth().background(OffBlack)) { MessageBarContent() }
                    }
                }
            }

            // Big Update #10 / item 3: the sheet itself is the only thing drawn —
            // the outer Box above has no background in Glass mode, so the post is
            // genuinely visible around it — and its own "glass" is a live reflection
            // of that same post (via [backdrop]), exactly like the main glass buttons.
            if (liquidGlass) {
                LiquidGlassSurface(
                    modifier = sheetModifier, shape = RoundedCornerShape(20.dp),
                    tint = dominantColor, backdrop = backdrop
                ) { SheetContent() }
            } else {
                Box(sheetModifier.clip(RoundedCornerShape(20.dp)).background(OffBlack)) { SheetContent() }
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
