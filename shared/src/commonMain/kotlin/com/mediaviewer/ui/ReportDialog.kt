package com.mediaviewer.ui

import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.foundation.layout.windowInsetsPadding

import com.mediaviewer.ui.compat.navBarSpace

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.model.ReportReason
import com.mediaviewer.model.ReportTarget
import com.mediaviewer.util.rememberHapticTap

/**
 * Report a post or an account to Bluesky's moderation team — the same kind
 * of centered, compact glass popup as Share with / Add To: it live-blurs
 * what's behind it in that post's (or profile's) own color, fades and
 * scales in via [FadingPopupHost] at the call site, and closes with the X,
 * Back, or a tap outside.
 *
 * Pick a reason (Bluesky's own report categories), optionally add details,
 * then send with the round button in the message box.
 */
@Composable
fun ReportDialog(
    target: ReportTarget,
    submitting: Boolean,
    liquidGlass: Boolean,
    tint: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    onSubmit: (ReportReason, String) -> Unit,
    onDismiss: () -> Unit
) {
    var reason by remember(target) { mutableStateOf<ReportReason?>(null) }
    var details by remember(target) { mutableStateOf("") }
    val tap = rememberHapticTap()
    val handle = target.author.handle.ifBlank { target.author.did }

    com.mediaviewer.ui.compat.BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = if (liquidGlass) 0.22f else 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(top = rememberTopCutoutClearance())
            .imePadding()
            .windowInsetsPadding(WindowInsets.navBarSpace)
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        PopupSheetSurface(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                PopupSheetHeader(
                    title = if (target.isPost) "Report post" else "Report account",
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onClose = onDismiss
                )
                // Who / what is being reported.
                Text(
                    if (target.isPost) "Post by @$handle" else "@$handle",
                    color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp, bottom = 8.dp)
                        .popupTextShadow().padding(horizontal = 10.dp, vertical = 3.dp)
                )

                Column(
                    // Shrinks (and scrolls) when the keyboard is up, so the
                    // message box always stays on top of the keyboard.
                    Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReportReason.entries.forEach { r ->
                        ReasonRow(
                            reason = r, selected = r == reason, tint = tint,
                            onTap = { tap(); reason = if (reason == r) null else r }
                        )
                    }
                }

                PopupMessageBox(
                    thumbUrl = if (target.isPost) target.postThumbUrl else target.author.avatarUrl.orEmpty(),
                    value = details, onValueChange = { if (it.length <= 2000) details = it },
                    placeholder = if (reason == null) "Pick a reason first…" else "Add details (optional)…",
                    tint = tint,
                    canSend = reason != null && !submitting,
                    sending = submitting,
                    onSend = { reason?.let { onSubmit(it, details.trim()) } },
                    maxLines = 4,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp)
                )
                Text(
                    "Sent to Bluesky's moderation team.",
                    color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 10.dp)
                        .popupTextShadow().padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/** One report category: a compact rounded row (title + one-line
 *  description), ringed in the popup's color with a check when picked. */
@Composable
private fun ReasonRow(reason: ReportReason, selected: Boolean, tint: Color, onTap: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val ring = lerp(tint, Color.White, 0.35f)
    val pop by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "reportReason")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) ring.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.22f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) ring else Color.White.copy(alpha = 0.12f), shape)
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(reason.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                reason.description, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(22.dp)
                .graphicsLayer { alpha = 0.35f + 0.65f * pop }
                .clip(CircleShape)
                .background(if (selected) ring else Color.Transparent)
                .border(1.5.dp, if (selected) ring else Color.White.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(
                Icons.Default.Check, contentDescription = null, tint = Color(0xFF101014),
                modifier = Modifier.size(14.dp).graphicsLayer { scaleX = pop; scaleY = pop }
            )
        }
    }
}
