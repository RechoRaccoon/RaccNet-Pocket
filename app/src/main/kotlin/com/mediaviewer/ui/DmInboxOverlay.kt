package com.mediaviewer.ui

import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
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
import com.mediaviewer.util.rememberHapticTap
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
    /** Text, and the id of the message it replies to (null = not a reply). */
    onSendReply: (String, String?) -> Unit,
    onClose: () -> Unit,
    // Item 27: tapping the other person's avatar/name in the thread header
    // should open their profile.
    onTapAuthor: (com.mediaviewer.model.AuthorInfo) -> Unit = {},
    // Item 12 follow-up: infinite-scroll-up for older messages.
    onLoadMoreMessages: () -> Unit = {},
    // Item 12 follow-up: tapping a shared-post card opens a feed made of
    // every post shared in this conversation.
    onOpenSharedPostsFeed: () -> Unit = {},
    /** Long-press emoji reactions: (messageId, emoji) toggles yours. */
    onToggleReaction: (String, String) -> Unit = { _, _ -> },
    /** The signed-in account (whose reactions/messages are "mine"). */
    selfDid: String = ""
) {
    val tap = rememberHapticTap()
    // Item 8: background/chrome now reflect the logged-in user's own
    // profile color, the same pattern the Hub uses (see SettingsSheet's
    // `dominantColor` shadow) — instead of the flat NeutralGlassTint this
    // page used everywhere before. DmThreadView's own `myTint` already did
    // this for "my" message bubbles specifically; this extends the same
    // color to the page background and every other glass surface here.
    val profileTint = rememberSelfTint(selfAvatarUrl, NeutralGlassTint)

    // Item 11: while a specific thread is open, the header (back button +
    // the other person's avatar/name) reflects *their* color instead of the
    // logged-in user's own — same dominant-color pattern, just sourced from
    // the other side of the conversation. Falls back to profileTint at the
    // conversation-picker screen, where there's no single "other person"
    // yet, and to NeutralGlassTint if the other person has no avatar.
    val theirTint = if (thread != null) {
        if (thread.convo.member.avatarUrl != null) rememberDominantColor(thread.convo.member.avatarUrl!!) else NeutralGlassTint
    } else profileTint
    val headerTint = if (thread != null) theirTint else profileTint

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
                    // Item 11: two-tone (mine-at-bottom, theirs-at-top)
                    // gradient while a thread is open; the plain single-tint
                    // gradient everywhere else (no "other person" yet on the
                    // conversation picker).
                    if (liquidGlass) Modifier.drawWithContent {
                        backdropLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(backdropLayer)
                    } else Modifier.background(OledBlack)
                )
        ) {
            if (liquidGlass) {
                if (thread != null) SpaceSky(theirTint, Modifier.matchParentSize(), bottomColor = profileTint)
                else SpaceSky(profileTint, Modifier.matchParentSize())
            }
        }

        Column(Modifier.fillMaxSize().padding(top = rememberTopCutoutClearance())) {
            // ── Header ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val shape = CircleShape
                Box(
                    Modifier.size(32.dp)
                        .then(if (liquidGlass) Modifier.glassPanel(true, shape = shape, tint = headerTint) else Modifier.clip(shape).background(Color.White.copy(0.14f)))
                        .clickable(onClick = { tap(); if (thread != null) onCloseThread() else onClose() }),
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
                            modifier = Modifier.size(28.dp).clip(CircleShape).clickable { tap(); onTapAuthor(thread.convo.member) })
                    }
                    Column(Modifier.clickable { tap(); onTapAuthor(thread.convo.member) }) {
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
                DmConversationPicker(conversations = conversations, loading = loading, liquidGlass = liquidGlass, tint = profileTint, onSelectConvo = onSelectConvo)
            } else {
                DmThreadView(
                    thread = thread, liquidGlass = liquidGlass, selfAvatarUrl = selfAvatarUrl,
                    profileTint = profileTint,
                    backdrop = dmBackdrop, onSendReply = onSendReply,
                    onLoadMoreMessages = onLoadMoreMessages, onOpenSharedPostsFeed = onOpenSharedPostsFeed,
                    onToggleReaction = onToggleReaction, selfDid = selfDid
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
    tint: Color = NeutralGlassTint,
    onSelectConvo: (DmConversation) -> Unit
) {
    val tap = rememberHapticTap()
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
                                .then(if (liquidGlass) Modifier.glassPanel(true, shape = shape, tint = tint) else Modifier.clip(shape).background(Color.White.copy(0.06f)))
                                .clickable { tap(); onSelectConvo(convo) }
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
    // Item 8: page-wide profile color (see DmInboxOverlay's own doc
    // comment) — used for the message field/send button glass, distinct
    // from `myTint` below which stays specific to "my" chat bubbles.
    profileTint: Color = NeutralGlassTint,
    backdrop: GlassBackdrop?,
    onSendReply: (String, String?) -> Unit,
    onLoadMoreMessages: () -> Unit,
    onOpenSharedPostsFeed: () -> Unit,
    onToggleReaction: (String, String) -> Unit,
    selfDid: String
) {
    val tap = rememberHapticTap()
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    val myDid = selfDid.ifBlank { thread.messages.firstOrNull { it.sender?.did != thread.convo.member.did }?.sender?.did ?: "" }

    // Item 12: dominant colors for both sides of the conversation, the same
    // pattern used everywhere else in the app for tinting glass to a
    // subject's own palette. Falls back to the shared defaults below when
    // an avatar isn't available (e.g. no self avatar yet, or the other
    // person has none set).
    val myTint = rememberSelfTint(selfAvatarUrl, VoteGreenTint)
    val theirTint = if (thread.convo.member.avatarUrl != null) rememberDominantColor(thread.convo.member.avatarUrl!!) else NeutralGlassTint
    fun isMine(m: BskyMessageView) = m.sender?.did != thread.convo.member.did
    fun nameOf(m: BskyMessageView) = if (isMine(m)) "yourself" else thread.convo.member.displayName.ifBlank { "@" + thread.convo.member.handle }

    // Swipe a message to reply to it (Bluesky's own DM replies).
    var replyTarget by remember(thread.convo.convoId) { mutableStateOf<BskyMessageView?>(null) }
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun startReply(m: BskyMessageView) {
        replyTarget = m
        scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { inputFocus.requestFocus() }
            keyboard?.show()
        }
    }
    fun send() {
        if (text.isBlank() || thread.sending) return
        onSendReply(text.trim(), replyTarget?.id)
        text = ""
        replyTarget = null
    }

    // Long press: the emoji reaction picker, anchored to the pressed bubble.
    var picker by remember { mutableStateOf<Pair<BskyMessageView, androidx.compose.ui.geometry.Rect>?>(null) }
    val listBlur by androidx.compose.animation.core.animateDpAsState(if (picker != null) 8.dp else 0.dp, label = "dmBlur")
    // Tapping a reply's quote scrolls to (and briefly lights up) the original.
    var highlightId by remember { mutableStateOf<String?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().then(if (listBlur > 0.dp) Modifier.blur(listBlur) else Modifier)) {
            when {
                thread.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                }
                thread.messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages yet", color = DimGray, fontSize = 13.sp)
                }
                else -> {
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
                            listState.animateScrollToItem(thread.messages.size - 1 + if (thread.loadingMore) 1 else 0)
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
                    LaunchedEffect(highlightId) {
                        if (highlightId != null) { kotlinx.coroutines.delay(1400); highlightId = null }
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
                            val mine = isMine(msg)
                            DmBubble(
                                msg, isMine = mine, tint = if (mine) myTint else theirTint,
                                liquidGlass = liquidGlass, embedded = thread.embeddedPosts[msg.id],
                                backdrop = backdrop, onOpenSharedPostsFeed = onOpenSharedPostsFeed,
                                myDid = myDid,
                                replyName = msg.replyTo?.let { r -> if (r.sender?.did == thread.convo.member.did) thread.convo.member.displayName.ifBlank { "@" + thread.convo.member.handle } else "You" },
                                replyTint = msg.replyTo?.let { r -> if (r.sender?.did == thread.convo.member.did) theirTint else myTint } ?: Color.White,
                                highlighted = highlightId == msg.id,
                                onReply = { startReply(msg) },
                                onLongPress = { bounds -> picker = msg to bounds },
                                onToggleReaction = { emoji -> onToggleReaction(msg.id, emoji) },
                                onJumpToReply = { id ->
                                    val idx = thread.messages.indexOfFirst { it.id == id }
                                    if (idx >= 0) {
                                        scope.launch { listState.animateScrollToItem(idx + if (thread.loadingMore) 1 else 0) }
                                        highlightId = id
                                    }
                                }
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
        //
        // Bug fix: when the keyboard opens, this row now rides up to sit
        // directly on top of it instead of staying pinned to the bottom of
        // the screen underneath it. `WindowInsets.ime.union(...navigationBars)`
        // (rather than stacking two separate `.imePadding()` /
        // `.navigationBarsPadding()` modifiers, which would add both insets
        // together and leave a gap above the keyboard on 3-button nav)
        // takes whichever of the two is currently larger.
        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            // "Replying to …" — slides in above the field while a reply is set.
            androidx.compose.animation.AnimatedVisibility(
                visible = replyTarget != null,
                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(stiffness = 700f)) +
                    androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                // Holds the last target through the exit animation.
                var shown by remember { mutableStateOf<BskyMessageView?>(null) }
                replyTarget?.let { shown = it }
                val target = shown
                if (target != null) {
                    val accent = if (isMine(target)) myTint else theirTint
                    ReplyPreviewBar(
                        name = nameOf(target),
                        snippet = target.text.ifBlank { if (thread.embeddedPosts[target.id] != null) "Shared a post" else "Message" },
                        accent = accent, liquidGlass = liquidGlass, tint = profileTint, backdrop = backdrop,
                        onCancel = { tap(); replyTarget = null }
                    )
                }
            }
        Row(
            Modifier.fillMaxWidth(),
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
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(inputFocus)
                    )
                    if (text.isEmpty()) Text(if (replyTarget != null) "Reply…" else "Message…", color = DimGray, fontSize = 14.sp)
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(Modifier.weight(1f).height(46.dp), shape = fieldShape, tint = profileTint, backdrop = backdrop) { MessageFieldContent() }
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
                tap(); send()
            }
            if (liquidGlass) {
                LiquidGlassSurface(sendModifier, shape = sendShape, tint = profileTint, backdrop = backdrop) { SendButtonContent() }
            } else {
                Box(sendModifier.clip(sendShape).background(Color.White.copy(0.14f))) { SendButtonContent() }
            }
        }
        }
    }

    // ── Reaction picker (long press) ──
    val current = picker
    if (current != null) {
        val (msg, bounds) = current
        val mine = isMine(msg)
        ReactionPickerOverlay(
            anchor = bounds,
            alignEnd = mine,
            myReactions = msg.reactions.orEmpty().filter { it.sender?.did == myDid }.map { it.value }.toSet(),
            tint = if (mine) myTint else theirTint,
            liquidGlass = liquidGlass, backdrop = backdrop,
            onPick = { emoji ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                onToggleReaction(msg.id, emoji)
                picker = null
            },
            onReply = { tap(); picker = null; startReply(msg) },
            onCopy = if (msg.text.isNotBlank()) ({
                tap(); clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.text)); picker = null
            }) else null,
            onDismiss = { picker = null }
        )
    }
    }
}

/** The strip above the message field while replying: an accent bar in the
 *  replied-to sender's color, "Replying to …", the message, and an X. */
@Composable
private fun ReplyPreviewBar(
    name: String, snippet: String, accent: Color,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    onCancel: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Replying to $name", color = accent.copy(alpha = 1f).let { androidx.compose.ui.graphics.lerp(it, Color.White, 0.45f) },
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(snippet, color = Color.White.copy(0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, contentDescription = "Cancel reply", tint = Color.White.copy(0.8f), modifier = Modifier.size(16.dp))
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (liquidGlass) LiquidGlassSurface(Modifier.fillMaxWidth(), shape = shape, tint = tint, backdrop = backdrop) { content() }
        else Box(Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(0.08f))) { content() }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DmBubble(
    msg: BskyMessageView, isMine: Boolean, tint: Color, liquidGlass: Boolean, embedded: DmEmbeddedPost?,
    backdrop: GlassBackdrop?, onOpenSharedPostsFeed: () -> Unit,
    myDid: String = "",
    replyName: String? = null,
    replyTint: Color = Color.White,
    highlighted: Boolean = false,
    onReply: () -> Unit = {},
    onLongPress: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onToggleReaction: (String) -> Unit = {},
    onJumpToReply: (String) -> Unit = {}
) {
    val tap = rememberHapticTap()
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Swipe to reply: their messages (left side) are pulled right, your own
    // (right side) are pulled left. The bubble follows the finger (with a
    // little resistance), a reply arrow grows in on the side it's leaving,
    // and crossing the threshold ticks — let go past it to reply.
    val dragX = remember { androidx.compose.animation.core.Animatable(0f) }
    val thresholdPx = with(density) { 64.dp.toPx() }
    val maxPx = with(density) { 96.dp.toPx() }
    var armed by remember { mutableStateOf(false) }
    val latestOnReply by rememberUpdatedState(onReply)
    val bubbleBounds = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    val flash by androidx.compose.animation.core.animateFloatAsState(if (highlighted) 1f else 0f, androidx.compose.animation.core.tween(350), label = "replyFlash")

    Box(
        Modifier.fillMaxWidth().pointerInput(msg.id, isMine) {
            // +1 = pull right (their messages), -1 = pull left (yours).
            val dir = if (isMine) -1f else 1f
            var raw = 0f
            detectHorizontalDragGestures(
                onDragStart = { raw = 0f },
                onDragEnd = {
                    if (armed) latestOnReply()
                    armed = false
                    scope.launch { dragX.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f)) }
                },
                onDragCancel = {
                    armed = false
                    scope.launch { dragX.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f)) }
                }
            ) { change, amount ->
                raw = (raw + amount * dir).coerceAtLeast(0f)
                if (raw > 0f) change.consume()
                // Rubber-band: follows 1:1 at first, then stiffens.
                val shown = if (raw <= thresholdPx) raw else thresholdPx + (raw - thresholdPx) * 0.35f
                scope.launch { dragX.snapTo(shown.coerceAtMost(maxPx) * dir) }
                val nowArmed = raw >= thresholdPx
                if (nowArmed != armed) {
                    armed = nowArmed
                    if (nowArmed) view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                }
            }
        }
    ) {
        // The reply arrow, revealed behind the bubble as it slides.
        val progress = (kotlin.math.abs(dragX.value) / thresholdPx).coerceIn(0f, 1f)
        if (progress > 0f) {
            Box(
                Modifier.align(if (isMine) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(start = 4.dp, end = 4.dp).size(30.dp)
                    .graphicsLayer {
                        alpha = progress
                        // Your own messages slide the other way, so their
                        // arrow is mirrored to point back at the bubble.
                        scaleX = (0.5f + 0.5f * progress) * (if (isMine) -1f else 1f)
                        scaleY = 0.5f + 0.5f * progress
                    }
                    .clip(CircleShape).background(tint.copy(alpha = if (armed) 0.65f else 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
        }
    Column(
        Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(dragX.value.toInt(), 0) },
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        val shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isMine) 16.dp else 4.dp, bottomEnd = if (isMine) 4.dp else 16.dp
        )
        Column(
            // Item 12 follow-up: shared-post cards are bigger/more prominent
            // now, so this bubble needs more room to show them well —
            // widened from 260.dp.
            Modifier.widthIn(max = 300.dp)
                .onGloballyPositioned { bubbleBounds[0] = it.boundsInRoot() }
                .then(
                    if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                    else Modifier.clip(shape).background(tint.copy(alpha = if (isMine) 0.55f else 0.35f))
                )
                .then(if (flash > 0f) Modifier.background(Color.White.copy(alpha = 0.18f * flash)) else Modifier)
                .combinedClickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    // (combinedClickable gives the long-press haptic itself.)
                    onLongClick = { bubbleBounds[0]?.let(onLongPress) }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // A reply: a compact quote of the message it answers — tap it to
            // jump there.
            val quoted = msg.replyTo
            if (quoted != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.22f))
                        .clickable { tap(); if (!quoted.isDeleted) onJumpToReply(quoted.id) }
                        .padding(end = 10.dp)
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(replyTint))
                    Column(Modifier.padding(start = 8.dp, top = 5.dp, bottom = 5.dp)) {
                        Text(
                            replyName ?: "Reply", color = androidx.compose.ui.graphics.lerp(replyTint, Color.White, 0.5f),
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                        )
                        Text(
                            if (quoted.isDeleted) "Deleted message" else quoted.text.ifBlank { "Shared a post" },
                            color = Color.White.copy(0.7f), fontSize = 12.sp, lineHeight = 15.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
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
                        .clickable(onClick = { tap(); onOpenSharedPostsFeed() })
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
        // Reactions: one chip per emoji with its count; yours are outlined
        // in your color. Tap a chip to add/remove your own.
        val reactions = msg.reactions.orEmpty()
        if (reactions.isNotEmpty()) {
            val grouped = remember(reactions, myDid) {
                reactions.groupBy { it.value }.map { (emoji, list) -> Triple(emoji, list.size, list.any { it.sender?.did == myDid }) }
            }
            Row(
                Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp).animateContentSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for ((emoji, count, mine) in grouped) {
                    androidx.compose.runtime.key(emoji) {
                        val pop = remember { androidx.compose.animation.core.Animatable(0.6f) }
                        LaunchedEffect(Unit) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 600f)) }
                        val chipShape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                                .clip(chipShape)
                                .background(if (mine) tint.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.35f))
                                .border(1.dp, if (mine) androidx.compose.ui.graphics.lerp(tint, Color.White, 0.35f) else Color.White.copy(0.15f), chipShape)
                                .clickable {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                    onToggleReaction(emoji)
                                }
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(emoji, fontSize = 13.sp)
                            if (count > 1) {
                                Spacer(Modifier.width(3.dp))
                                Text("$count", color = Color.White.copy(0.85f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** Quick reactions, then the full set behind "+". Every value is a single
 *  emoji (one grapheme), which is all Bluesky's reactions accept. */
private val QUICK_REACTIONS = listOf("❤️", "😂", "😮", "😢", "😡", "👍", "🔥")
private val ALL_REACTIONS = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩",
    "😘", "😗", "😚", "😙", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨",
    "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "😮‍💨", "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒",
    "🤕", "🤢", "🤮", "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳", "😎", "🤓", "🧐", "😕", "😟", "🙁",
    "😮", "😯", "😲", "😳", "🥺", "🥹", "😦", "😧", "😨", "😰", "😥", "😢", "😭", "😱", "😖", "😣",
    "😞", "😓", "😩", "😫", "🥱", "😤", "😡", "😠", "🤬", "😈", "👿", "💀", "☠️", "💩", "🤡", "👻",
    "👽", "🤖", "😺", "😸", "😹", "😻", "😼", "😽", "🙀", "😿", "😾", "🙈", "🙉", "🙊",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❤️‍🔥", "💕", "💞", "💓", "💗", "💖",
    "💘", "💝", "💯", "💢", "💥", "💫", "💦", "💨", "💬", "💭", "💤",
    "👍", "👎", "👏", "🙌", "👐", "🤲", "🤝", "🙏", "✌️", "🤞", "🤟", "🤘", "👌", "🤌", "🤏", "👈",
    "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "🤙", "💪", "🫶", "🫡", "🫠", "🫣", "🫢",
    "👀", "👁️", "🧠", "🫀", "🦷", "👅", "👄",
    "🔥", "✨", "🌟", "⭐", "⚡", "🌈", "☀️", "🌙", "☁️", "❄️", "🌊", "🌸", "🌹", "🌻", "🍀", "🌵",
    "🦝", "🐶", "🐱", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔", "🐧", "🐦",
    "🦄", "🐝", "🦋", "🐢", "🐍", "🐙", "🦈", "🐬", "🐳", "🦖", "🐉",
    "🍕", "🍔", "🍟", "🌮", "🍣", "🍩", "🍪", "🎂", "🍰", "🍫", "🍿", "🍎", "🍓", "🍑", "🍒", "🥑",
    "☕", "🍵", "🧋", "🍺", "🍷", "🥂",
    "🎉", "🎊", "🎈", "🎁", "🏆", "🥇", "🎮", "🎧", "🎵", "🎶", "🎨", "📸", "🎬", "📚", "💡", "💎",
    "💰", "🚀", "✈️", "🚗", "🏠", "⏰", "📌", "🔒", "🔑",
    "✅", "❌", "❓", "❗", "‼️", "⁉️", "⚠️", "🚫", "💤", "🆗", "🆒", "🆕", "🔝", "♻️", "➕", "➖"
).distinct()

/** Long-press menu for a DM: a row of quick emoji reactions (a "+" opens
 *  every emoji), plus Reply and Copy. Glass in the message's own color,
 *  popping in from the pressed bubble over the softly blurred thread. */
@Composable
private fun ReactionPickerOverlay(
    anchor: androidx.compose.ui.geometry.Rect,
    alignEnd: Boolean,
    myReactions: Set<String>,
    tint: Color,
    liquidGlass: Boolean,
    backdrop: GlassBackdrop?,
    onPick: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var cardSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 480f)) }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot(); boxSize = it.size }
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onDismiss
            )
    ) {
        // Above the bubble when there's room, otherwise just below it.
        val gap = with(density) { 8.dp.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val aboveY = anchor.top - origin.y - cardSize.height - gap
        val y = if (aboveY >= margin) aboveY else (anchor.bottom - origin.y + gap)
            .coerceAtMost((boxSize.height - cardSize.height - margin).coerceAtLeast(margin))
        val x = if (alignEnd) (anchor.right - origin.x - cardSize.width).coerceAtLeast(margin)
            else (anchor.left - origin.x).coerceIn(margin, (boxSize.width - cardSize.width - margin).coerceAtLeast(margin))
        val shape = RoundedCornerShape(24.dp)
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }
                .onSizeChanged { cardSize = it }
                .widthIn(max = 340.dp)
                .graphicsLayer {
                    val sc = 0.8f + 0.2f * appear.value
                    scaleX = sc; scaleY = sc
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (alignEnd) 1f else 0f, if (aboveY >= margin) 1f else 0f)
                }
                // Swallow taps on the card itself.
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { }
        ) {
            val content: @Composable () -> Unit = {
                Column(Modifier.animateContentSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QUICK_REACTIONS.forEachIndexed { i, emoji ->
                            // A little staggered pop for each emoji.
                            val pop = remember { androidx.compose.animation.core.Animatable(0.4f) }
                            LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(25L * i)
                                pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 700f))
                            }
                            ReactionEmojiCell(emoji, selected = emoji in myReactions, tint = tint, size = 38.dp, scale = pop.value) { onPick(emoji) }
                        }
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(0.12f))
                                .clickable {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                    expanded = !expanded
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (expanded) Icons.Default.Close else Icons.Default.Add,
                                contentDescription = if (expanded) "Fewer emoji" else "More emoji",
                                tint = Color.White, modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (expanded) {
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(8),
                            modifier = Modifier.padding(top = 6.dp).width(304.dp).height(220.dp)
                        ) {
                            items(ALL_REACTIONS.size) { i ->
                                val emoji = ALL_REACTIONS[i]
                                ReactionEmojiCell(emoji, selected = emoji in myReactions, tint = tint, size = 38.dp) { onPick(emoji) }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color.White.copy(0.1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PickerAction("Reply", Icons.AutoMirrored.Filled.Reply, onReply)
                        if (onCopy != null) PickerAction("Copy", Icons.Default.ContentCopy, onCopy)
                    }
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(Modifier, shape = shape, tint = tint, backdrop = backdrop) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.3f)))
                    content()
                }
            } else {
                Box(
                    Modifier.clip(shape).background(androidx.compose.ui.graphics.lerp(Color(0xFF141418), tint, 0.25f))
                        .border(1.dp, tint.copy(alpha = 0.5f), shape)
                ) { content() }
            }
        }
    }
}

@Composable
private fun ReactionEmojiCell(emoji: String, selected: Boolean, tint: Color, size: androidx.compose.ui.unit.Dp, scale: Float = 1f, onClick: () -> Unit) {
    Box(
        Modifier.size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .then(if (selected) Modifier.background(tint.copy(alpha = 0.5f)).border(1.dp, androidx.compose.ui.graphics.lerp(tint, Color.White, 0.4f), CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = (size.value * 0.52f).sp)
    }
}

@Composable
private fun PickerAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(0.1f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val VoteGreenTint = Color(0xFF3E9B57)
