package com.mediaviewer.ui

import com.mediaviewer.ui.compat.rememberPlatformView

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.BlockedAccount
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.rememberHapticTap

/**
 * Settings → Data and Privacy → Blocked Accounts → View. The same centred,
 * blurred-behind popup as the DM list's "New chat": a search bar over a
 * compact list of every account you're blocking, most recently blocked
 * first, each with an Unblock button on the far right.
 */
@Composable
fun BlockedAccountsDialog(
    accounts: List<BlockedAccount>,
    loading: Boolean,
    unblocking: Set<String>,
    tint: Color,
    onUnblock: (String) -> Unit,
    onClose: () -> Unit
) {
    val tap = rememberHapticTap()
    var query by remember { mutableStateOf("") }
    val q = query.trim().removePrefix("@")
    val shown = remember(accounts, q) {
        if (q.isEmpty()) accounts
        else accounts.filter {
            it.author.displayName.contains(q, ignoreCase = true) || it.author.handle.contains(q, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = com.mediaviewer.ui.compat.edgeToEdgeDialogProperties()
    ) {
        com.mediaviewer.ui.compat.DialogBlurBehind(radius = 48, dimAmount = 0.45f)
        BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(26.dp)
            val panel = lerp(Color(0xFF101014), tint, 0.16f)
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    .widthIn(max = 440.dp).fillMaxWidth()
                    .heightIn(max = maxHeight * 0.82f)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(lerp(panel, tint, 0.12f).copy(alpha = 0.97f), panel.copy(alpha = 0.97f))))
                    .border(1.2.dp, Brush.linearGradient(listOf(tint.copy(alpha = 0.9f), Color.White.copy(alpha = 0.25f), tint.copy(alpha = 0.6f))), shape)
            ) {
                // ── Header ──
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                            .clickable { tap(); onClose() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(19.dp)) }
                    Text(
                        "Blocked Accounts",
                        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(38.dp))
                }

                BlockedSearchField(query, tint) { query = it }

                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
                if (accounts.isNotEmpty()) {
                    Text(
                        if (q.isEmpty()) "${accounts.size} blocked · most recent first" else "${shown.size} of ${accounts.size}",
                        color = lerp(tint, Color.White, 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
                    )
                }

                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    when {
                        loading && accounts.isEmpty() -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                        }
                        shown.isEmpty() -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (accounts.isEmpty()) "You're not blocking anyone." else "No one found",
                                color = DimGray, fontSize = 13.sp
                            )
                        }
                        else -> LazyColumn(contentPadding = PaddingValues(top = 2.dp, bottom = 10.dp)) {
                            items(shown, key = { it.author.did }) { entry ->
                                BlockedRow(
                                    author = entry.author,
                                    tint = tint,
                                    busy = entry.author.did in unblocking,
                                    onUnblock = { tap(); onUnblock(entry.author.did) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedSearchField(value: String, tint: Color, onChange: (String) -> Unit) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().height(42.dp)
            .clip(RoundedCornerShape(21.dp)).background(Color.Black.copy(alpha = 0.28f))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(21.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = lerp(tint, Color.White, 0.5f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
                modifier = Modifier.fillMaxWidth()
            )
            if (value.isEmpty()) Text("Search blocked accounts", color = DimGray, fontSize = 14.sp)
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Default.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onChange("") }
            )
        }
    }
}

@Composable
private fun BlockedAvatar(author: AuthorInfo, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        if (author.avatarUrl != null) AsyncImage(
            model = author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape)
        ) else Text(author.displayName.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BlockedRow(author: AuthorInfo, tint: Color, busy: Boolean, onUnblock: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BlockedAvatar(author, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(author.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${author.handle}", color = DimGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.clip(RoundedCornerShape(14.dp))
                .background(lerp(tint, Color.Black, 0.35f).copy(alpha = 0.75f))
                .border(1.dp, lerp(tint, Color.White, 0.3f).copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                .clickable(enabled = !busy, onClick = onUnblock)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            // "Unblock" laid out invisibly keeps the button's width steady
            // while the spinner shows.
            Text("Unblock", color = if (busy) Color.Transparent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 1.5.dp)
        }
    }
}
