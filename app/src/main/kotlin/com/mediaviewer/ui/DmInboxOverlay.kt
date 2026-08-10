package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
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
import com.mediaviewer.ui.theme.OledBlack
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
    onTapAuthor: (com.mediaviewer.model.AuthorInfo) -> Unit = {},
    // Item 12 follow-up: infinite-scroll-up for older messages.
    onLoadMoreMessages: () -> Unit = {},
    // Item 12 follow-up: tapping a shared-post card opens a feed made of
    // every post shared in this conversation.
    onOpenSharedPostsFeed: () -> Unit = {}
) {
    // Item 12 follow-up: the input box, send button, and shared-post cards
    // now sample a live recording of this page's own background — the same
    // technique SearchOverlay uses — instead of a flat rectangular fill.
    val backdropLayer = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    val dmBackdrop = remember(liquidGlass, backdropLayer) {
        if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
    }

    Box(
        Modifier.fillMaxSize()
            // Bug fix: same click-through-to-feed issue as SearchOverlay —
            // see blockClicksBehind() in GlassTheme.kt.
            .blockClicksBehind()
    ) {
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                .then(
                    if (liquidGlass) Modifier.background(postBackgroundBrush(NeutralGlassTint)).drawWithContent {
                        backdropLayer.record { this@drawWithContent.drawContent() }
                        drawContent()
                    } else Modifier.background(OledBlack)
                )
        )

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
                        .then(if (liquidGlass) Modifier.glassPanel(true, shape = shape, tint = NeutralGlassTint) else Modifier.clip(shape).background(Color.White.copy(0.14f)))
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
                DmThreadView(
                    thread = thread, liquidGlass = liquidGlass, selfAvatarUrl = selfAvatarUrl,
                    backdrop = dmBackdrop, onSendReply = onSendReply,
                    onLoadMoreMessages = onLoadMoreMessages, onOpenSharedPostsFeed = onOpenSharedPostsFeed
                )
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
                                .then(if (liquidGlass) Modifier.glassPanel(true, shape = shape, tint = NeutralGlassTint) else Modifier.clip(shape).background(Color.White.copy(0.06f)))
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
                                Text(convo.member.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun DmThreadView(
    thread: MainViewModel.DmThreadState,
    liquidGlass: Boolean,
    selfAvatarUrl: String?,
    backdrop: GlassBackdrop?,
    onSendReply: (String) -> Unit,
    onLoadMoreMessages: () -> Unit,
    onOpenSharedPostsFeed: () -> Unit
) {
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

                    // Bug fix: this used to unconditionally scroll to the
                    // bottom any time thread.messages.size changed — which
                    // also fired when *older* messages were prepended by
                    // scroll-up pagination below, yanking the view straight
                    // back to the bottom mid-scroll. Only auto-scroll when
                    // the *last* message actually changed (a real new
                    // message arrived/was sent), not when the list grew from
                    // the front.
                    var lastMessageId by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(thread.messages.lastOrNull()?.id) {
                        val newLastId = thread.messages.lastOrNull()?.id
                        if (newLastId != null && newLastId != lastMessageId && thread.messages.isNotEmpty()) {
                            listState.animateScrollToItem(thread.messages.size - 1)
                        }
                        lastMessageId = newLastId
                    }

                    // Item 12 follow-up: infinite-scroll-up for older DMs —
                    // ask for more once the user scrolls near the top of
                    // what's currently loaded, same "near the edge" pattern
                    // used elsewhere in the app (e.g. GridScreen's
                    // shouldLoadMore).
                    LaunchedEffect(listState, thread.cursor) {
                        snapshotFlow { listState.firstVisibleItemIndex }
                            .collect { firstVisible ->
                                if (firstVisible <= 2 && thread.cursor != null && !thread.loadingMore && !thread.loading) {
                                    onLoadMoreMessages()
                                }
                            }
                    }

                    LazyColumn(
                        Modifier.fillMaxSize(), state = listState,
                        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (thread.loadingMore) {
                            item(key = "loading_more") {
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 1.5.dp)
                                }
                            }
                        }
                        items(thread.messages, key = { it.id }) { msg ->
                            val isMine = msg.sender?.did != thread.convo.member.did
                            DmBubble(
                                msg, isMine = isMine, tint = if (isMine) myTint else theirTint,
                                liquidGlass = liquidGlass, embedded = thread.embeddedPosts[msg.id],
                                backdrop = backdrop, onOpenSharedPostsFeed = onOpenSharedPostsFeed
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)

        // Item 12 follow-up: the input row is now a floating glass pill (no
        // more flat rectangular fill behind it, no more Material's own
        // outlined-box styling) with a live backdrop reflection, the same
        // "Search"-bar pattern SearchOverlay uses — and sits with proper
        // clearance above the gesture bar via navigationBarsPadding, instead
        // of butting right up against it.
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val fieldShape = RoundedCornerShape(24.dp)
            @Composable
            fun MessageFieldContent() {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = text, onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (text.isNotBlank() && !thread.sending) { onSendReply(text.trim()); text = "" }
                        }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (text.isEmpty()) Text("Message…", color = DimGray, fontSize = 14.sp)
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(Modifier.weight(1f).height(46.dp), shape = fieldShape, tint = NeutralGlassTint, backdrop = backdrop) { MessageFieldContent() }
            } else {
                Box(Modifier.weight(1f).height(46.dp).clip(fieldShape).background(Color.White.copy(0.08f))) { MessageFieldContent() }
            }

            val sendShape = CircleShape
            @Composable
            fun SendButtonContent() {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (thread.sending) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 1.5.dp)
                    else Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            val sendModifier = Modifier.size(46.dp).clickable(enabled = text.isNotBlank() && !thread.sending) {
                onSendReply(text.trim()); text = ""
            }
            if (liquidGlass) {
                LiquidGlassSurface(sendModifier, shape = sendShape, tint = NeutralGlassTint, backdrop = backdrop) { SendButtonContent() }
            } else {
                Box(sendModifier.clip(sendShape).background(Color.White.copy(0.14f))) { SendButtonContent() }
            }
        }
    }
}

@Composable
private fun DmBubble(
    msg: BskyMessageView, isMine: Boolean, tint: Color, liquidGlass: Boolean, embedded: DmEmbeddedPost?,
    backdrop: GlassBackdrop?, onOpenSharedPostsFeed: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start) {
        val shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isMine) 16.dp else 4.dp, bottomEnd = if (isMine) 4.dp else 16.dp
        )
        Column(
            // Item 12 follow-up: shared-post cards are bigger/more prominent
            // now, so this bubble needs more room to show them well —
            // widened from 260.dp.
            Modifier.widthIn(max = 300.dp)
                .then(
                    if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                    else Modifier.clip(shape).background(tint.copy(alpha = if (isMine) 0.55f else 0.35f))
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (msg.text.isNotBlank()) {
                Text(msg.text, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp)
            }
            // Item 12 follow-up: the shared-post card is now bigger (larger
            // thumbnail, more breathing room) and tappable — tapping it
            // opens a feed made of every post shared in this conversation,
            // same as the "From Friends" feed but scoped to just this thread.
            if (embedded != null) {
                if (msg.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                val innerShape = RoundedCornerShape(12.dp)
                Column(
                    Modifier.fillMaxWidth().clip(innerShape)
                        .background(Color.Black.copy(0.22f))
                        .clickable(onClick = onOpenSharedPostsFeed)
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (embedded.author.avatarUrl != null) {
                            AsyncImage(
                                model = embedded.author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(16.dp).clip(CircleShape)
                            )
                        }
                        Text(
                            embedded.author.displayName, color = Color.White.copy(0.9f), fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (embedded.thumbUrl != null) {
                        Spacer(Modifier.height(6.dp))
                        AsyncImage(
                            model = embedded.thumbUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 180.dp).clip(RoundedCornerShape(8.dp))
                        )
                    }
                    if (embedded.text.isNotBlank()) {
                        Text(
                            embedded.text, color = Color.White.copy(0.75f), fontSize = 12.sp, lineHeight = 15.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Text(
                        "View shared posts", color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

private val VoteGreenTint = Color(0xFF3E9B57)
