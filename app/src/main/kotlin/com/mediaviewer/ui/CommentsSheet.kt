package com.mediaviewer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.mediaviewer.model.AppMode
import com.mediaviewer.model.CommentItem
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap

/**
 * Comments, as a layer over the post instead of a separate screen: the post
 * stays where it is (blurred, its buttons faded — see FeedView's
 * `commentsFraction`), and this sheet slides up over it, starting right
 * under the camera cutout. The list scrolls; the input box sits at the
 * bottom exactly like the DM box and rides up on top of the keyboard.
 *
 * Closing follows the finger: pull down from the top of the list (or grab
 * the header anywhere) and the sheet tracks the drag, the blur behind it
 * easing off as it goes ([onDragFractionChanged]); let go past ~20% of the
 * screen (or flick down) and it closes, otherwise it springs back.
 */
@Composable
fun CommentsSheet(
    currentItem: MediaItem?,
    comments: List<CommentItem>,
    commentsLoading: Boolean,
    appMode: AppMode,
    liquidGlass: Boolean,
    onPostComment: (String, CommentItem?) -> Unit,
    onLikeComment: (CommentItem) -> Unit,
    onVoteComment: (CommentItem, Int) -> Unit,
    onSwipeDown: () -> Unit,
    onTagClick: (String) -> Unit,
    onTagAdd: (String) -> Unit,
    onTagExclude: (String) -> Unit,
    // Item 1 (Phase 3): the current post's color, so Comments' glass rims
    // reflect it the same way the in-post glass buttons do.
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    reducedAnimations: Boolean = false,
    /** 0 = fully open … 1 = dragged all the way down (for the blur behind). */
    onDragFractionChanged: (Float) -> Unit = {}
) {
    val tap = rememberHapticTap()
    var threadStack by remember(currentItem?.id) { mutableStateOf(listOf<CommentItem>()) }
    var commentText by remember { mutableStateOf("") }
    var showTags by remember(currentItem?.id) { mutableStateOf(false) }
    // Item 20: which comment (if any) is actually being replied to.
    var replyTarget by remember(currentItem?.id) { mutableStateOf<CommentItem?>(null) }

    // ── Drag-to-close ──
    val scope = rememberCoroutineScope()
    var sheetHeightPx by remember { mutableIntStateOf(1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    val onClose by rememberUpdatedState(onSwipeDown)
    LaunchedEffect(dragOffset, sheetHeightPx) {
        onDragFractionChanged((dragOffset / sheetHeightPx).coerceIn(0f, 1f))
    }
    DisposableEffect(Unit) { onDispose { onDragFractionChanged(0f) } }
    fun settle(velocityY: Float) {
        if (closing) return
        if (dragOffset > sheetHeightPx * 0.2f || (velocityY > 1600f && dragOffset > 0f)) {
            closing = true
            onClose()
        } else if (dragOffset > 0f) {
            scope.launch {
                androidx.compose.animation.core.animate(
                    dragOffset, 0f,
                    animationSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)
                ) { v, _ -> dragOffset = v }
            }
        }
    }
    val settleRef by rememberUpdatedState(::settle)
    val dragConnection = remember {
        object : NestedScrollConnection {
            // Pulled down, now pushing back up: shrink the pull before the
            // list scrolls.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && dragOffset > 0f) {
                    val used = maxOf(available.y, -dragOffset)
                    dragOffset += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
            // The list is at its top and the finger keeps going down: that
            // leftover movement pulls the whole sheet down.
            @Suppress("DEPRECATION")
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.Drag && !closing) {
                    dragOffset += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragOffset > 0f) {
                    settleRef(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    // Item 3 bug fix: Bluesky posts opened from the Liked search tab carry
    // the AI tagger's tags too, so the toggle isn't e621-only.
    val showTagsToggle = currentItem != null && (appMode == AppMode.E621 || currentItem.tags.isNotBlank())

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { sheetHeightPx = it.height.coerceAtLeast(1) }
            .graphicsLayer { translationY = dragOffset }
            .nestedScroll(dragConnection)
            .blockClicksBehind()
            // A soft dark wash over the blurred post so white text stays
            // readable on bright media.
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.5f))
                )
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── Header: grab it anywhere to drag the sheet ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                        state = rememberDraggableState { d -> if (!closing) dragOffset = (dragOffset + d).coerceAtLeast(0f) },
                        onDragStopped = { v -> settle(v) }
                    )
                    .padding(top = rememberTopCutoutClearance())
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp, bottom = 8.dp)
                        .size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f))
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Comments", color = if (!showTags) Color.White else DimGray,
                        fontSize = 15.sp, fontWeight = if (!showTags) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clickable(enabled = showTagsToggle) { tap(); showTags = false }
                    )
                    if (showTagsToggle) {
                        Text(
                            "Tags", color = if (showTags) Color.White else DimGray,
                            fontSize = 15.sp, fontWeight = if (showTags) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.clickable { tap(); showTags = true }
                        )
                    }
                }
            }

            // ── Body — swipe left/right toggles Comments <-> Tags ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .let { base ->
                        if (showTagsToggle && currentItem != null) {
                            base.pointerInput(currentItem.id) {
                                var totalX = 0f
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (totalX < -70f) showTags = true
                                        else if (totalX > 70f) showTags = false
                                        totalX = 0f
                                    },
                                    onDragCancel = { totalX = 0f }
                                ) { _, dragAmount -> totalX += dragAmount }
                            }
                        } else base
                    }
            ) {
                if (showTags && currentItem != null) {
                    // Per request: tags shown alphabetically.
                    val tags = currentItem.tags.split(" ").filter { it.isNotBlank() }.sortedBy { it.lowercase() }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                        if (tags.isEmpty()) {
                            item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("no tags", color = DimGray, fontSize = 14.sp) } }
                        } else {
                            items(tags) { tag -> TagRow(tag, onTagClick, onTagAdd, onTagExclude) }
                        }
                    }
                } else {
                    // Item 16: reply-chain navigation. The AnimatedContent's
                    // target IS the stack, and each page draws from the stack
                    // it was created with — so the page sliding out keeps
                    // showing the old level instead of both pages jumping to
                    // the new one first (the "jumps, then animates" jank).
                    AnimatedContent(
                        targetState = threadStack,
                        contentKey = { stack -> stack.size to (stack.lastOrNull()?.id ?: "") },
                        transitionSpec = {
                            if (reducedAnimations) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else if (targetState.size > initialState.size) {
                                (slideInHorizontally(animationSpec = tween(260)) { w -> w } + fadeIn(tween(200)))
                                    .togetherWith(slideOutHorizontally(animationSpec = tween(260)) { w -> -w } + fadeOut(tween(180)))
                            } else {
                                (slideInHorizontally(animationSpec = tween(260)) { w -> -w } + fadeIn(tween(200)))
                                    .togetherWith(slideOutHorizontally(animationSpec = tween(260)) { w -> w } + fadeOut(tween(180)))
                            }
                        },
                        label = "thread-nav"
                    ) { stack ->
                        val parent = stack.lastOrNull()
                        val displayedComments = parent?.replies ?: comments
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp)
                        ) {
                            if (parent != null) {
                                item(key = "parent-${parent.id}") {
                                    ThreadParentHeader(
                                        parent = parent, liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = backdrop,
                                        onBack = { threadStack = stack.dropLast(1) }
                                    )
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp,
                                        modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                            when {
                                parent == null && commentsLoading -> item(key = "loading") {
                                    Box(Modifier.fillParentMaxWidth().fillParentMaxHeight(0.8f), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = Color.White, strokeWidth = 1.5.dp)
                                    }
                                }
                                displayedComments.isEmpty() -> item(key = "empty") {
                                    Box(Modifier.fillParentMaxWidth().fillParentMaxHeight(0.8f), contentAlignment = Alignment.Center) {
                                        Text("no comments", color = DimGray, fontSize = 14.sp)
                                    }
                                }
                                else -> items(displayedComments, key = { it.id }) { comment ->
                                    CommentRow(
                                        comment, appMode, liquidGlass, onLikeComment, onVoteComment,
                                        onReplyToComment = { c -> replyTarget = c; commentText = "@${c.authorHandle} " },
                                        onOpenThread = { c -> if (c.replies.isNotEmpty()) threadStack = stack + c },
                                        dominantColor = dominantColor, backdrop = backdrop,
                                        indented = parent != null
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Input — same look and keyboard behaviour as the DM box ──
            if (!showTags) {
                Column(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    replyTarget?.let { target ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Replying to @${target.authorHandle}", color = DimGray, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.Close, contentDescription = "Cancel reply", tint = DimGray,
                                modifier = Modifier.size(16.dp).clickable {
                                    tap()
                                    replyTarget = null
                                    if (commentText == "@${target.authorHandle} ") commentText = ""
                                }
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val fieldShape = RoundedCornerShape(24.dp)
                        fun send() {
                            if (commentText.isNotBlank()) {
                                onPostComment(commentText.trim(), replyTarget)
                                commentText = ""; replyTarget = null
                            }
                        }
                        @Composable
                        fun FieldContent() {
                            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                                androidx.compose.foundation.text.BasicTextField(
                                    value = commentText, onValueChange = { commentText = it },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Send
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (commentText.isEmpty()) Text(if (replyTarget != null) "Reply…" else "Add a comment…", color = DimGray, fontSize = 14.sp)
                            }
                        }
                        if (liquidGlass) {
                            LiquidGlassSurface(Modifier.weight(1f).height(46.dp), shape = fieldShape, tint = dominantColor, backdrop = backdrop) { FieldContent() }
                        } else {
                            Box(Modifier.weight(1f).height(46.dp).clip(fieldShape).background(Color.White.copy(0.08f))) { FieldContent() }
                        }
                        val sendModifier = Modifier.size(46.dp).clickable(enabled = commentText.isNotBlank()) { tap(); send() }
                        @Composable
                        fun SendContent() {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Send, contentDescription = "Send",
                                    tint = if (commentText.isNotBlank()) Color.White else Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (liquidGlass) {
                            LiquidGlassSurface(sendModifier, shape = CircleShape, tint = dominantColor, backdrop = backdrop) { SendContent() }
                        } else {
                            Box(sendModifier.clip(CircleShape).background(Color.White.copy(0.14f))) { SendContent() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagRow(tag: String, onTagClick: (String) -> Unit, onTagAdd: (String) -> Unit, onTagExclude: (String) -> Unit) {
    val tap = rememberHapticTap()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            tag.replace('_', ' '),
            color    = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f).clickable { tap(); onTagClick(tag) }
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(VoteGreen.copy(alpha = 0.15f))
                .clickable { tap(); onTagAdd(tag) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add to search", tint = VoteGreen, modifier = Modifier.size(16.dp))
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(VoteRed.copy(alpha = 0.15f))
                .clickable { tap(); onTagExclude(tag) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Exclude from search", tint = VoteRed, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun CommentRow(
    comment: CommentItem,
    appMode: AppMode,
    liquidGlass: Boolean,
    onLike: (CommentItem) -> Unit,
    onVote: (CommentItem, Int) -> Unit,
    onReplyToComment: (CommentItem) -> Unit,
    onOpenThread: (CommentItem) -> Unit = {},
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    indented: Boolean = false
) {
    @Composable
    fun RowContent() {
        val tap = rememberHapticTap()
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = if (indented) 20.dp else 0.dp)
                .clickable(enabled = comment.replies.isNotEmpty()) { tap(); onOpenThread(comment) }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        if (comment.authorAvatarUrl != null) {
            AsyncImage(model = comment.authorAvatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(30.dp).clip(CircleShape))
        } else {
            Box(modifier = Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(0.1f)))
        }

        Column(modifier = Modifier.weight(1f)) {
            // Item 8: display name gets first claim on the row's width and shrinks
            // with an ellipsis before wrapping; the handle only gets whatever room
            // is left over and also ellipsizes — so long names/handles never wrap
            // onto a second line and break the row's height.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.authorDisplayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    "@${comment.authorHandle}", color = DimGray, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(comment.body, color = Color.White.copy(0.88f), fontSize = 13.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (appMode == AppMode.BLUESKY) {
                    // Item 16: no more raw like-count number next to the heart —
                    // just the heart itself, then a "replies: N" label (only shown
                    // when this comment actually has replies).
                    Icon(
                        imageVector = if (comment.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Like", tint = if (comment.isLiked) LikeRed else DimGray,
                        modifier = Modifier.size(14.dp).clickable { tap(); onLike(comment) }
                    )
                    if (comment.replyCount > 0) {
                        Text("replies: ${comment.replyCount}", color = DimGray, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Reply", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { tap(); onReplyToComment(comment) }
                    )
                } else {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Upvote",
                        tint = if (comment.e621UserVote == 1) VoteGreen else DimGray,
                        modifier = Modifier.size(14.dp).clickable { tap(); onVote(comment, 1) })
                    Text(comment.likeCount.toString(), color = DimGray, fontSize = 11.sp)
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Downvote",
                        tint = if (comment.e621UserVote == -1) VoteRed else DimGray,
                        modifier = Modifier.size(14.dp).clickable { tap(); onVote(comment, -1) })
                    if (comment.replyCount > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text("replies: ${comment.replyCount}", color = DimGray, fontSize = 11.sp)
                    }
                }
            }
        }
    }
    }
    if (liquidGlass) {
        LiquidGlassSurface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            shape = RoundedCornerShape(14.dp), tint = dominantColor.copy(alpha = 0.5f), backdrop = backdrop
        ) { RowContent() }
    } else {
        RowContent()
    }
}

// ─── Reply-chain: pinned parent header ─────────────────────────────────────────
// Occupies the top of the list whenever the user has drilled into a reply chain —
// the tapped comment "becomes" the header, with a separate curved Back bubble to
// its left. Tapping Back repeatedly walks back out one level at a time.
@Composable
private fun ThreadParentHeader(
    parent: CommentItem,
    liquidGlass: Boolean,
    dominantColor: Color,
    backdrop: GlassBackdrop?,
    onBack: () -> Unit
) {
    val tap = rememberHapticTap()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        @Composable
        fun BackIconContent() {
            Box(Modifier.fillMaxSize().clickable(onClick = { tap(); onBack() }), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(modifier = Modifier.size(40.dp), shape = CircleShape, tint = dominantColor, backdrop = backdrop) { BackIconContent() }
        } else {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(0.08f))) { BackIconContent() }
        }

        @Composable
        fun ParentPillContent() {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Item 21: the opened (top) comment in a thread was missing its
                // author's avatar, unlike every regular CommentRow below it.
                if (parent.authorAvatarUrl != null) {
                    AsyncImage(model = parent.authorAvatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(30.dp).clip(CircleShape))
                } else {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(0.1f)))
                }
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(parent.authorDisplayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text("@${parent.authorHandle}", color = DimGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(parent.body, color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), tint = dominantColor, backdrop = backdrop) { ParentPillContent() }
        } else {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(0.06f))) { ParentPillContent() }
        }
    }
}
