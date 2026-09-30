package com.mediaviewer.ui

import com.mediaviewer.ui.compat.navBarSpace

import com.mediaviewer.ui.compat.rememberPlatformView

import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
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
import coil3.compose.AsyncImage
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
    selfDid: String = "",
    /** The big + button: start a new chat or group. */
    onNewChat: () -> Unit = {},
    /** 1:1 chat header: start a group with this person. */
    onNewGroupWith: (com.mediaviewer.model.AuthorInfo) -> Unit = {}
) {
    val tap = rememberHapticTap()
    // Back: an open chat returns to the DM list; the list returns to the Hub.
    com.mediaviewer.ui.compat.BackHandler { if (thread != null) onCloseThread() else onClose() }
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
    // Group chats have no single "other person": they keep your colors.
    val isGroup = thread?.convo?.isGroup == true
    val theirTint = if (thread != null && !isGroup) {
        rememberAuthorProfileTint(thread.convo.member.did, thread.convo.member.avatarUrl)
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
                // The two-tone fade into the other person's color is for 1:1
                // chats only; group chats stay in your own colors.
                if (thread != null && !isGroup) SpaceSky(theirTint, Modifier.matchParentSize(), bottomColor = profileTint)
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
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (thread != null) "Back" else "Close",
                        tint = Color.White, modifier = Modifier.size(17.dp)
                    )
                }
                if (thread != null && isGroup) {
                    DmConvoAvatar(thread.convo, 30.dp)
                    Column(Modifier.weight(1f)) {
                        Text(thread.convo.member.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Bluesky's own member count (you included); the
                        // lists are only a fallback, and `members` already
                        // includes you — counting it +1 again made a group
                        // of 4 read "5".
                        val count = thread.convo.memberCount.takeIf { it > 0 }
                            ?: (thread.members.keys + thread.convo.groupMembers.map { it.did } + selfDid).filter { it.isNotBlank() }.toSet().size
                        Text("$count members", color = DimGray, fontSize = 11.sp, maxLines = 1)
                    }
                } else if (thread != null) {
                    if (thread.convo.member.avatarUrl != null) {
                        AsyncImage(model = thread.convo.member.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(28.dp).clip(CircleShape).clickable { tap(); onTapAuthor(thread.convo.member) })
                    }
                    Column(Modifier.weight(1f).clickable { tap(); onTapAuthor(thread.convo.member) }) {
                        Text(thread.convo.member.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${thread.convo.member.handle}", color = DimGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Start a group chat with this person.
                    Box(
                        Modifier.size(32.dp)
                            .then(if (liquidGlass) Modifier.glassPanel(true, shape = CircleShape, tint = headerTint) else Modifier.clip(CircleShape).background(Color.White.copy(0.14f)))
                            .clickable { tap(); onNewGroupWith(thread.convo.member) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = "New group with ${thread.convo.member.displayName}", tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                } else {
                    // Item 12: there was a second, redundant Close button here on
                    // the right — the one at the far left already closes the inbox.
                    Text(
                        "Direct Messages", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Light,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                    // Balances the back button so the title sits dead centre.
                    Spacer(Modifier.size(32.dp))
                }
            }
            HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)

            if (thread == null) {
                // The chat list is recorded (over the page background) so
                // the + button can blur whatever chats scroll behind it.
                val pickerLayer = rememberGraphicsLayer()
                var pickerOrigin by remember { mutableStateOf(Offset.Zero) }
                val pickerBackdrop = remember(liquidGlass, pickerLayer) {
                    if (liquidGlass) GlassBackdrop(pickerLayer) { pickerOrigin } else null
                }
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxSize()
                            .onGloballyPositioned { pickerOrigin = it.positionInRoot() }
                            .drawWithContent {
                                if (liquidGlass) pickerLayer.record {
                                    translate(backdropOrigin.x - pickerOrigin.x, backdropOrigin.y - pickerOrigin.y) {
                                        drawLayer(backdropLayer)
                                    }
                                    this@drawWithContent.drawContent()
                                }
                                drawContent()
                            }
                    ) {
                        DmConversationPicker(conversations = conversations, loading = loading, liquidGlass = liquidGlass, tint = profileTint, onSelectConvo = onSelectConvo)
                    }
                    // New chat / new group.
                    val fabShape = CircleShape
                    val fabModifier = Modifier.align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.navBarSpace).padding(end = 18.dp, bottom = 18.dp)
                        .size(60.dp)
                    val fabContent: @Composable () -> Unit = {
                        Box(Modifier.fillMaxSize().clickable { tap(); onNewChat() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = "New chat", tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                    }
                    if (liquidGlass) {
                        LiquidGlassSurface(fabModifier, shape = fabShape, tint = androidx.compose.ui.graphics.lerp(profileTint, Color.White, 0.1f), backdrop = pickerBackdrop) { fabContent() }
                    } else {
                        Box(fabModifier.clip(fabShape).background(androidx.compose.ui.graphics.lerp(profileTint, Color.Black, 0.3f))) { fabContent() }
                    }
                }
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
                        DmConvoRow(convo, liquidGlass, tint, onClick = { tap(); onSelectConvo(convo) })
                    }
                    // Room for the + button.
                    item(key = "fab_space") { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

/**
 * One chat in the DM list, wearing the other person's own profile colors
 * (group chats: yours). Top row: name, then their handle; bottom row: the
 * newest message/activity, and "(x) unread" when there's anything new.
 */
@Composable
private fun DmConvoRow(convo: DmConversation, liquidGlass: Boolean, selfTint: Color, onClick: () -> Unit) {
    val rowTint = if (convo.isGroup || convo.member.did.isBlank()) selfTint
        else rememberAuthorProfileTint(convo.member.did, convo.member.avatarUrl)
    val unread = convo.unreadCount
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (liquidGlass) Modifier.glassPanel(true, shape = shape, tint = rowTint)
                else Modifier.clip(shape).background(androidx.compose.ui.graphics.lerp(Color(0xFF16161B), rowTint, 0.18f))
            )
            .then(if (unread > 0) Modifier.border(1.2.dp, rowTint.copy(alpha = 0.9f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DmConvoAvatar(convo, 44.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    convo.member.displayName, color = Color.White, fontSize = 14.sp,
                    fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (!convo.isGroup && convo.member.handle.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "@${convo.member.handle}", color = DimGray, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val preview = convo.lastMessageText.ifBlank {
                    if (convo.isGroup) "${maxOf(convo.memberCount, convo.groupMembers.size + 1)} members" else ""
                }
                Text(
                    preview, color = if (unread > 0) Color.White else Color.White.copy(alpha = 0.62f), fontSize = 12.sp,
                    fontWeight = if (unread > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "(${if (unread > 99) "99+" else unread}) unread",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(9.dp))
                            .background(androidx.compose.ui.graphics.lerp(rowTint, Color.Black, 0.15f).copy(alpha = 0.85f))
                            .border(1.dp, androidx.compose.ui.graphics.lerp(rowTint, Color.White, 0.45f).copy(alpha = 0.8f), RoundedCornerShape(9.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
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
    val view = com.mediaviewer.ui.compat.rememberPlatformView()
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    val isGroup = thread.convo.isGroup
    val myDid = selfDid.ifBlank {
        if (isGroup) "" else thread.messages.firstOrNull { it.sender?.did != thread.convo.member.did }?.sender?.did ?: ""
    }

    // Item 12: dominant colors for both sides of the conversation, the same
    // pattern used everywhere else in the app for tinting glass to a
    // subject's own palette. Falls back to the shared defaults below when
    // an avatar isn't available (e.g. no self avatar yet, or the other
    // person has none set).
    val myTint = rememberSelfTint(selfAvatarUrl, VoteGreenTint)
    // 1:1: the other person's profile colors. Group chats: every sender
    // wears their own (see senderTint).
    val theirTint = if (isGroup) profileTint else rememberAuthorProfileTint(thread.convo.member.did, thread.convo.member.avatarUrl)
    fun isMine(m: BskyMessageView) =
        if (myDid.isNotBlank()) m.sender?.did == myDid else m.sender?.did != thread.convo.member.did
    /** Who sent [m] (group chats: looked up among the members). */
    fun senderOf(m: BskyMessageView): com.mediaviewer.model.AuthorInfo {
        if (!isGroup) return thread.convo.member
        val did = m.sender?.did.orEmpty()
        return thread.members[did] ?: thread.convo.groupMembers.firstOrNull { it.did == did }
            ?: com.mediaviewer.model.AuthorInfo(did = did, handle = did, displayName = "Member", avatarUrl = null)
    }
    fun displayNameOf(a: com.mediaviewer.model.AuthorInfo) = a.displayName.ifBlank { "@" + a.handle }
    fun nameOf(m: BskyMessageView) = if (isMine(m)) "yourself" else displayNameOf(senderOf(m))
    // Group chats: each sender's profile color, worked out once per sender.
    val groupTints = remember(thread.convo.convoId) { androidx.compose.runtime.mutableStateMapOf<String, Color>() }
    fun tintOf(m: BskyMessageView): Color = when {
        isMine(m) -> myTint
        !isGroup -> theirTint
        else -> groupTints[m.sender?.did.orEmpty()] ?: profileTint
    }
    if (isGroup) {
        // Each member's profile colors (banner + avatar blend), the same
        // colors their profile page wears.
        val senders = remember(thread.messages, thread.members) {
            thread.messages.mapNotNull { it.sender?.did }.distinct().filter { it != myDid }
        }
        senders.forEach { did ->
            androidx.compose.runtime.key(did) {
                val a = thread.members[did]
                val t = rememberAuthorProfileTint(did, a?.avatarUrl)
                androidx.compose.runtime.SideEffect { groupTints[did] = t }
            }
        }
    }

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
                    // The thread opens already sitting on the newest message
                    // (an instant jump, not an animated scroll down through
                    // everything); older messages only load once the user
                    // actually scrolls up after that.
                    var positioned by remember(thread.convo.convoId) { mutableStateOf(false) }
                    LaunchedEffect(thread.messages.lastOrNull()?.id) {
                        val newLastId = thread.messages.lastOrNull()?.id
                        if (newLastId != null && newLastId != lastMessageId && thread.messages.isNotEmpty()) {
                            val target = thread.messages.size - 1 + if (thread.loadingMore) 1 else 0
                            if (!positioned) listState.scrollToItem(target) else listState.animateScrollToItem(target)
                            positioned = true
                        }
                        lastMessageId = newLastId
                    }

                    // Item 12 follow-up: infinite-scroll-up for older DMs —
                    // ask for more once the user scrolls near the top of
                    // what's currently loaded, same "near the edge" pattern
                    // used elsewhere in the app (e.g. GridScreen's
                    // shouldLoadMore).
                    LaunchedEffect(listState, thread.cursor, positioned) {
                        if (!positioned) return@LaunchedEffect
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
                        itemsIndexed(thread.messages, key = { _, m -> m.id }) { index, msg ->
                            if (msg.isSystem) {
                                // Group chats: "X added Y" etc., as a small
                                // centered note rather than a bubble.
                                DmSystemNote(msg, thread.members, myDid)
                                return@itemsIndexed
                            }
                            val mine = isMine(msg)
                            val sender = if (isGroup && !mine) senderOf(msg) else null
                            val prev = thread.messages.getOrNull(index - 1)
                            val firstOfRun = prev == null || prev.isSystem || prev.sender?.did != msg.sender?.did
                            Column(Modifier.fillMaxWidth()) {
                            if (sender != null && firstOfRun) {
                                // The sender's name above the first message of a run.
                                Text(
                                    displayNameOf(sender), color = androidx.compose.ui.graphics.lerp(tintOf(msg), Color.White, 0.5f),
                                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 40.dp, top = 4.dp, bottom = 2.dp)
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            if (sender != null) {
                                // Group chats: the sender's avatar beside each of their messages.
                                val ring = tintOf(msg)
                                Box(
                                    Modifier.padding(end = 6.dp, bottom = 2.dp).size(30.dp).clip(CircleShape)
                                        .background(ring.copy(alpha = 0.35f)).border(1.dp, ring.copy(alpha = 0.8f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (sender.avatarUrl != null) AsyncImage(
                                        model = sender.avatarUrl, contentDescription = displayNameOf(sender),
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    ) else Text(displayNameOf(sender).take(1).uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            DmBubble(
                                msg, isMine = mine, tint = tintOf(msg), modifier = Modifier.weight(1f),
                                liquidGlass = liquidGlass, embedded = thread.embeddedPosts[msg.id],
                                backdrop = backdrop, onOpenSharedPostsFeed = onOpenSharedPostsFeed,
                                myDid = myDid,
                                replyName = msg.replyTo?.let { r -> if (isMine(r)) "You" else displayNameOf(senderOf(r)) },
                                replyTint = msg.replyTo?.let { r -> tintOf(r) } ?: Color.White,
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
        // `.windowInsetsPadding(WindowInsets.navBarSpace)` modifiers, which would add both insets
        // together and leave a gap above the keyboard on 3-button nav)
        // takes whichever of the two is currently larger.
        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navBarSpace))
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
                    val accent = tintOf(target)
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
            tint = tintOf(msg),
            liquidGlass = liquidGlass, backdrop = backdrop,
            onPick = { emoji ->
                view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CONFIRM)
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
    onJumpToReply: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tap = rememberHapticTap()
    val view = com.mediaviewer.ui.compat.rememberPlatformView()
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
        modifier.fillMaxWidth().pointerInput(msg.id, isMine) {
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
                    if (nowArmed) view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CONTEXT_CLICK)
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
                                    view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CLOCK_TICK)
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
    com.mediaviewer.ui.compat.BackHandler(onBack = onDismiss)
    val view = com.mediaviewer.ui.compat.rememberPlatformView()
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
                                    view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CLOCK_TICK)
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


/**
 * A conversation's picture: the person's avatar for a 1:1 chat, or — for a
 * group chat — small avatars of its members arranged to fill the same
 * circle: two overlapping, three in a triangle, four in a square, and for
 * bigger groups three faces plus a "+N" for everyone else.
 */
@Composable
internal fun DmConvoAvatar(convo: DmConversation, size: androidx.compose.ui.unit.Dp) {
    if (!convo.isGroup) {
        if (convo.member.avatarUrl != null) {
            AsyncImage(model = convo.member.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape))
        } else {
            Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(0.15f)))
        }
        return
    }
    val members = convo.groupMembers
    val total = maxOf(convo.memberCount - 1, members.size) // everyone but you
    // (x, y, diameter) as fractions of [size]; x/y are the circle's top-left.
    val slots: List<Triple<Float, Float, Float>> = when {
        total <= 1 -> listOf(Triple(0f, 0f, 1f))
        total == 2 -> listOf(Triple(0f, 0f, 0.64f), Triple(0.36f, 0.36f, 0.64f))
        total == 3 -> listOf(Triple(0.24f, 0f, 0.52f), Triple(0f, 0.46f, 0.52f), Triple(0.48f, 0.46f, 0.52f))
        else -> listOf(Triple(0f, 0f, 0.5f), Triple(0.5f, 0f, 0.5f), Triple(0f, 0.5f, 0.5f), Triple(0.5f, 0.5f, 0.5f))
    }
    val shown = if (total > 4) 3 else slots.size
    val extra = total - shown
    Box(Modifier.size(size)) {
        slots.forEachIndexed { i, (fx, fy, fd) ->
            val d = size * fd
            val m = Modifier.offset(x = size * fx, y = size * fy).size(d)
                .clip(CircleShape).background(Color(0xFF1C1C22))
                .border(maxOf(1.dp, size * 0.03f), Color(0xFF0E0E12), CircleShape)
            if (i >= shown) {
                // "+N": everyone who doesn't fit.
                Box(m.background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Text(
                        "+${if (extra > 99) 99 else extra}", color = Color.White,
                        fontSize = (size.value * fd * 0.36f).sp, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
            } else {
                val a = members.getOrNull(i)
                Box(m, contentAlignment = Alignment.Center) {
                    if (a?.avatarUrl != null) AsyncImage(
                        model = a.avatarUrl, contentDescription = a.displayName, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    ) else Text(
                        (a?.displayName ?: "?").take(1).uppercase(), color = Color.White,
                        fontSize = (size.value * fd * 0.4f).sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** A group chat's system notice ("Alex added Sam", "Group renamed …"). */
@Composable
private fun DmSystemNote(msg: BskyMessageView, members: Map<String, com.mediaviewer.model.AuthorInfo>, myDid: String) {
    val text = remember(msg.id, members) { describeSystemMessage(msg, members, myDid) }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.25f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

private fun describeSystemMessage(msg: BskyMessageView, members: Map<String, com.mediaviewer.model.AuthorInfo>, myDid: String): String {
    val data = msg.data?.takeIf { it.isJsonObject }?.asJsonObject ?: return "Group updated"
    val type = runCatching { data.get("\$type")?.asString }.getOrNull().orEmpty().substringAfter('#')
    fun who(key: String): String {
        val did = runCatching { data.getAsJsonObject(key)?.get("did")?.asString }.getOrNull() ?: return "Someone"
        if (did == myDid) return "You"
        return members[did]?.displayName?.takeIf { it.isNotBlank() } ?: members[did]?.handle ?: "Someone"
    }
    fun str(key: String) = runCatching { data.get(key)?.asString }.getOrNull().orEmpty()
    return when (type) {
        "systemMessageDataAddMember" -> "${who("addedBy")} added ${who("member")}"
        "systemMessageDataRemoveMember" -> "${who("removedBy")} removed ${who("member")}"
        "systemMessageDataMemberJoin" -> "${who("member")} joined"
        "systemMessageDataMemberLeave" -> "${who("member")} left"
        "systemMessageDataLockConvo", "systemMessageDataLockConvoPermanently" -> "${who("lockedBy")} locked the group"
        "systemMessageDataUnlockConvo" -> "${who("unlockedBy")} unlocked the group"
        "systemMessageDataEditGroup" -> str("newName").takeIf { it.isNotBlank() }?.let { "Group renamed to \"$it\"" } ?: "Group edited"
        "systemMessageDataCreateJoinLink" -> "Invite link created"
        "systemMessageDataEditJoinLink" -> "Invite link edited"
        "systemMessageDataEnableJoinLink" -> "Invite link turned on"
        "systemMessageDataDisableJoinLink" -> "Invite link turned off"
        else -> "Group updated"
    }
}
