package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mediaviewer.model.BskyMessageView
import com.mediaviewer.model.DmConversation
import com.mediaviewer.model.DmEmbeddedPost
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.viewmodel.MainViewModel

/**
 * Settings Update — the "DMs" quick-access button opens this: pick someone
 * you have an existing conversation with, then see the full linear history
 * with them. Kept intentionally simple for now (per spec, a fuller DM
 * experience is planned as a later pass) — this is a picker plus a
 * read/reply thread view, nothing more.
 */
@Composable
fun DmInboxOverlay(
    conversations: List<DmConversation>,
    loading: Boolean,
    thread: MainViewModel.DmThreadState?,
    liquidGlass: Boolean,
    // Item 12: the logged-in user's own avatar, so "my" bubbles can be tinted
    // with the same dominant-color pattern used everywhere else in the app,
    // matching how the other person's bubbles are now colored too.
    selfAvatarUrl: String? = null,
    onSelectConvo: (DmConversation) -> Unit,
    onCloseThread: () -> Unit,
    onSendReply: (String) -> Unit,
    onClose: () -> Unit,
    // Item 27: tapping the other person's avatar/name in the thread header
    // should open their profile.
    onTapAuthor: (com.mediaviewer.model.AuthorInfo) -> Unit = {}
) {
    Box(
        Modifier.fillMaxSize()
            // Bug fix: same click-through-to-feed issue as SearchOverlay —
            // see blockClicksBehind() in GlassTheme.kt.
            .blockClicksBehind()
            .background(postBackgroundBrush(NeutralGlassTint))
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // ── Header ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val shape = CircleShape
                Box(
                    Modifier.size(32.dp)
                        .then(if (liquidGlass) Modifier.glassPanel(true, shape = shape) else Modifier.clip(shape).background(Color.White.copy(0.14f)))
                        .clickable(onClick = if (thread != null) onCloseThread else onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (thread != null) Icons.Default.ArrowBack else Icons.Default.Close,
                        contentDescription = if (thread != null) "Back" else "Close",
                        tint = Color.White, modifier = Modifier.size(17.dp)
                    )
                }
                if (thread != null) {
                    if (thread.convo.member.avatarUrl != null) {
                        AsyncImage(model = thread.convo.member.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onTapAuthor(thread.convo.member) })
                    }
                    Column(Modifier.clickable { onTapAuthor(thread.convo.member) }) {
                        Text(thread.convo.member.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${thread.convo.member.handle}", color = DimGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    // Item 12: there was a second, redundant Close button here on
                    // the right — the one at the far left already closes the inbox.
                    Text("Direct Messages", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Light)
                }
            }
            HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)

            if (thread == null) {
                DmConversationPicker(conversations = conversations, loading = loading, liquidGlass = liquidGlass, onSelectConvo = onSelectConvo)
            } else {
                DmThreadView(thread = thread, liquidGlass = liquidGlass, selfAvatarUrl = selfAvatarUrl, onSendReply = onSendReply)
            }
        }
    }
}

@Composable
private fun DmConversationPicker(
    conversations: List<DmConversation>,
    loading: Boolean,
    liquidGlass: Boolean,
    onSelectConvo: (DmConversation) -> Unit
) {
    // Only accounts we actually have history with — a mutual with no convo yet
    // has nothing to show in a linear-history view.
    val withHistory = remember(conversations) { conversations.filter { it.convoId.isNotBlank() } }

    Box(Modifier.fillMaxSize()) {
        when {
            loading && withHistory.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                }
            }
            withHistory.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No conversations yet", color = DimGray, fontSize = 13.sp)
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(withHistory, key = { it.convoId }) { convo ->
                        val shape = RoundedCornerShape(14.dp)
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (liquidGlass) Modifier.glassPanel(true, shape = shape) else Modifier.clip(shape).background(Color.White.copy(0.06f)))
                                .clickable { onSelectConvo(convo) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (convo.member.avatarUrl != null) {
                                AsyncImage(model = convo.member.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(40.dp).clip(CircleShape))
                            } else {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(0.15f)))
                            }
                            Column {
                                Text(convo.member.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("@${convo.member.handle}", color = DimGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DmThreadView(thread: MainViewModel.DmThreadState, liquidGlass: Boolean, selfAvatarUrl: String?, onSendReply: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val myDid = thread.messages.firstOrNull { it.sender?.did != thread.convo.member.did }?.sender?.did

    // Item 12: dominant colors for both sides of the conversation, the same
    // pattern used everywhere else in the app for tinting glass to a
    // subject's own palette. Falls back to the shared defaults below when
    // an avatar isn't available (e.g. no self avatar yet, or the other
    // person has none set).
    val myTint = if (selfAvatarUrl != null) rememberDominantColor(selfAvatarUrl) else VoteGreenTint
    val theirTint = if (thread.convo.member.avatarUrl != null) rememberDominantColor(thread.convo.member.avatarUrl!!) else NeutralGlassTint

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                thread.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                }
                thread.messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages yet", color = DimGray, fontSize = 13.sp)
                }
                else -> {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LaunchedEffect(thread.messages.size) {
                        if (thread.messages.isNotEmpty()) listState.animateScrollToItem(thread.messages.size - 1)
                    }
                    LazyColumn(
                        Modifier.fillMaxSize(), state = listState,
                        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(thread.messages, key = { it.id }) { msg ->
                            val isMine = msg.sender?.did != thread.convo.member.did
                            DmBubble(
                                msg, isMine = isMine, tint = if (isMine) myTint else theirTint,
                                liquidGlass = liquidGlass, embedded = thread.embeddedPosts[msg.id]
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Message…", color = DimGray, fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                // Item 12: the input box needs a fully rounded (pill) shape.
                shape = RoundedCornerShape(25.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White.copy(0.3f), unfocusedBorderColor = Color.White.copy(0.1f),
                    cursorColor = Color.White, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White
                ),
                modifier = Modifier.weight(1f).height(50.dp)
            )
            val shape = CircleShape
            Box(
                Modifier.size(42.dp)
                    .then(if (liquidGlass) Modifier.glassPanel(true, shape = shape) else Modifier.clip(shape).background(Color.White.copy(0.14f)))
                    .clickable(enabled = text.isNotBlank() && !thread.sending) {
                        onSendReply(text.trim()); text = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                if (thread.sending) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 1.5.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DmBubble(msg: BskyMessageView, isMine: Boolean, tint: Color, liquidGlass: Boolean, embedded: DmEmbeddedPost?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start) {
        val shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isMine) 16.dp else 4.dp, bottomEnd = if (isMine) 4.dp else 16.dp
        )
        Column(
            Modifier.widthIn(max = 260.dp)
                .then(
                    if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                    else Modifier.clip(shape).background(tint.copy(alpha = if (isMine) 0.55f else 0.35f))
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (msg.text.isNotBlank()) {
                Text(msg.text, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp)
            }
            // Item 12: a shared post rendered inline as a compact card, the
            // same way a quote-repost is shown in the main feed — small
            // avatar/handle row, a preview thumbnail if the post has media,
            // and a couple lines of its own text.
            if (embedded != null) {
                if (msg.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                val innerShape = RoundedCornerShape(10.dp)
                Row(
                    Modifier.fillMaxWidth().clip(innerShape).background(Color.Black.copy(0.18f))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    if (embedded.thumbUrl != null) {
                        AsyncImage(
                            model = embedded.thumbUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (embedded.author.avatarUrl != null) {
                                AsyncImage(
                                    model = embedded.author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(14.dp).clip(CircleShape)
                                )
                            }
                            Text(
                                embedded.author.displayName, color = Color.White.copy(0.85f), fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (embedded.text.isNotBlank()) {
                            Text(
                                embedded.text, color = Color.White.copy(0.7f), fontSize = 11.sp, lineHeight = 14.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private val VoteGreenTint = Color(0xFF3E9B57)
