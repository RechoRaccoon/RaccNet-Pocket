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
import coil.compose.AsyncImage
import com.mediaviewer.model.AppMode
import com.mediaviewer.model.CommentItem
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*

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
    reducedAnimations: Boolean = false
) {
    var threadStack by remember(currentItem?.id) { mutableStateOf(listOf<CommentItem>()) }
    var commentText by remember { mutableStateOf("") }
    var attachedUri by remember { mutableStateOf<Uri?>(null) }
    var showTags by remember(currentItem?.id) { mutableStateOf(false) }
    // Item 20: which comment (if any) is actually being replied to. Previously
    // "Reply" only stuffed an "@handle" into the text box and posted as a
    // top-level reply to the post — this makes it a real threaded reply.
    var replyTarget by remember(currentItem?.id) { mutableStateOf<CommentItem?>(null) }

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> attachedUri = uri }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                // Item 14: reuse the post's own dominant-color gradient instead
                // of a fixed neutral tint, so Comments matches the post it's on.
                if (liquidGlass) Modifier.background(postBackgroundBrush(dominantColor))
                else Modifier.background(OledBlack)
            )
    ) {
        // ── Shrunk media preview (swipe down here also returns to feed) ────────
        currentItem?.let { item ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        var totalY = 0f
                        detectDragGestures(
                            onDragStart = { totalY = 0f },
                            onDragEnd   = { if (totalY > 60f) onSwipeDown() },
                            onDragCancel = { }
                        ) { change, dragAmount ->
                            totalY += dragAmount.y
                            change.consume()
                        }
                    }
            ) {
                AsyncImage(
                    model              = item.thumbUrl.ifBlank { item.mediaUrl },
                    contentDescription = null,
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize()
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)

        if (appMode == AppMode.E621 && currentItem != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OffBlack)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Comments", color = if (!showTags) Color.White else DimGray,
                    fontSize = 13.sp, fontWeight = if (!showTags) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clickable { showTags = false })
                Text("Tags", color = if (showTags) Color.White else DimGray,
                    fontSize = 13.sp, fontWeight = if (showTags) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clickable { showTags = true })
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
        }

        // ── Body — swipe left/right here (anywhere) toggles Comments <-> Tags ──
        // Uses the orientation-aware horizontal detector so it only claims clearly
        // horizontal motion, leaving vertical drags free for the list to scroll.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .let { base ->
                    if (appMode == AppMode.E621 && currentItem != null) {
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
                val tags = currentItem.tags.split(" ").filter { it.isNotBlank() }
                val tagListState = rememberLazyListState()
                if (tags.isEmpty()) {
                    Text("no tags", color = DimGray, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
                } else {
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            observeBoundarySwipeDown(tagListState, onSwipeDown)
                        }
                    ) {
                        LazyColumn(
                            state = tagListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(tags) { tag -> TagRow(tag, onTagClick, onTagAdd, onTagExclude) }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (attachedUri != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().background(OffBlack).padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Attachment: ${attachedUri?.lastPathSegment ?: "file"}", color = DimGray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { attachedUri = null }) { Text("Remove", color = Color(0xFFEF5350), fontSize = 11.sp) }
                        }
                    }

                    @Composable
                    fun InputBarContent() {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Item 20: shows which comment is being replied to,
                            // with a way to cancel back to a top-level comment.
                            replyTarget?.let { target ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Replying to @${target.authorHandle}", color = DimGray, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        Icons.Default.Close, contentDescription = "Cancel reply", tint = DimGray,
                                        modifier = Modifier.size(14.dp).clickable {
                                            replyTarget = null
                                            if (commentText == "@${target.authorHandle} ") commentText = ""
                                        }
                                    )
                                }
                            }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { mediaPicker.launch("image/* video/*") }, modifier = Modifier.size(34.dp)) {
                                // Item 10: a photo/media icon (not a paperclip) — this
                                // attaches an image or video to the comment.
                                Icon(Icons.Default.Image, contentDescription = "Attach media", tint = DimGray, modifier = Modifier.size(18.dp))
                            }
                            OutlinedTextField(
                                value = commentText, onValueChange = { commentText = it },
                                placeholder = { Text("Add a comment…", color = DimGray, fontSize = 13.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                                    cursorColor = Color.White, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent
                                ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp), maxLines = 3,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    if (commentText.isNotBlank()) {
                                        onPostComment(commentText.trim(), replyTarget)
                                        commentText = ""; attachedUri = null; replyTarget = null
                                    }
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send", tint = if (commentText.isNotBlank()) Color.White else DimGray, modifier = Modifier.size(18.dp))
                            }
                        }
                        }
                    }
                    if (liquidGlass) {
                        // Item 4: same horizontal inset as CommentRow below (8.dp) so
                        // the comment box is exactly as wide as the comments.
                        LiquidGlassSurface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(24.dp), tint = dominantColor, backdrop = backdrop
                        ) { InputBarContent() }
                    } else {
                        Box(Modifier.fillMaxWidth().background(OffBlack)) { InputBarContent() }
                    }

                    if (!liquidGlass) HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            commentsLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 1.5.dp)
                            comments.isEmpty() -> Text("no comments", color = DimGray, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
                            else -> {
                                // Item 16: reply-chain navigation. `threadStack` is the
                                // path of parent comments drilled into so far — empty
                                // means we're looking at the top-level comments. Each
                                // push/pop is driven entirely from data already fetched
                                // up front (see CommentItem.replies), so no new network
                                // calls are needed to walk into a chain.
                                val navKey = threadStack.size to (threadStack.lastOrNull()?.id ?: "")
                                AnimatedContent(
                                    targetState = navKey,
                                    transitionSpec = {
                                        if (reducedAnimations) {
                                            EnterTransition.None togetherWith ExitTransition.None
                                        } else if (targetState.first > initialState.first) {
                                            // Pushing deeper: new page slides in from the
                                            // right, old page exits to the left.
                                            (slideInHorizontally(animationSpec = tween(260)) { w -> w } + fadeIn(tween(200)))
                                                .togetherWith(slideOutHorizontally(animationSpec = tween(260)) { w -> -w } + fadeOut(tween(180)))
                                        } else {
                                            // Backing out: previous page slides back in
                                            // from the left, current page exits right.
                                            (slideInHorizontally(animationSpec = tween(260)) { w -> -w } + fadeIn(tween(200)))
                                                .togetherWith(slideOutHorizontally(animationSpec = tween(260)) { w -> w } + fadeOut(tween(180)))
                                        }
                                    },
                                    label = "thread-nav"
                                ) { _ ->
                                    val parent = threadStack.lastOrNull()
                                    val displayedComments = parent?.replies ?: comments
                                    val commentListState = rememberLazyListState()
                                    Box(
                                        Modifier.fillMaxSize().pointerInput(parent?.id) {
                                            observeBoundarySwipeDown(commentListState) {
                                                if (parent != null) threadStack = threadStack.dropLast(1) else onSwipeDown()
                                            }
                                        }
                                    ) {
                                        LazyColumn(
                                            state = commentListState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(vertical = 8.dp)
                                        ) {
                                            if (parent != null) {
                                                item(key = "parent-${parent.id}") {
                                                    ThreadParentHeader(
                                                        parent = parent, liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = backdrop,
                                                        onBack = { threadStack = threadStack.dropLast(1) }
                                                    )
                                                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp,
                                                        modifier = Modifier.padding(vertical = 4.dp))
                                                }
                                            }
                                            items(displayedComments, key = { it.id }) { comment ->
                                                CommentRow(
                                                    comment, appMode, liquidGlass, onLikeComment, onVoteComment,
                                                    onReplyToComment = { c -> replyTarget = c; commentText = "@${c.authorHandle} " },
                                                    onOpenThread = { c -> if (c.replies.isNotEmpty()) threadStack = threadStack + c },
                                                    dominantColor = dominantColor, backdrop = backdrop,
                                                    // Item 16: a slight indent on every row while inside a
                                                    // thread page, to show they're all replies to the
                                                    // pinned parent above.
                                                    indented = parent != null
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagRow(tag: String, onTagClick: (String) -> Unit, onTagAdd: (String) -> Unit, onTagExclude: (String) -> Unit) {
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
            modifier = Modifier.weight(1f).clickable { onTagClick(tag) }
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(VoteGreen.copy(alpha = 0.15f))
                .clickable { onTagAdd(tag) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add to search", tint = VoteGreen, modifier = Modifier.size(16.dp))
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(VoteRed.copy(alpha = 0.15f))
                .clickable { onTagExclude(tag) },
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
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = if (indented) 20.dp else 0.dp)
                .clickable(enabled = comment.replies.isNotEmpty()) { onOpenThread(comment) }
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
                        modifier = Modifier.size(14.dp).clickable { onLike(comment) }
                    )
                    if (comment.replyCount > 0) {
                        Text("replies: ${comment.replyCount}", color = DimGray, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Reply", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onReplyToComment(comment) }
                    )
                } else {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Upvote",
                        tint = if (comment.e621UserVote == 1) VoteGreen else DimGray,
                        modifier = Modifier.size(14.dp).clickable { onVote(comment, 1) })
                    Text(comment.likeCount.toString(), color = DimGray, fontSize = 11.sp)
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Downvote",
                        tint = if (comment.e621UserVote == -1) VoteRed else DimGray,
                        modifier = Modifier.size(14.dp).clickable { onVote(comment, -1) })
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        @Composable
        fun BackIconContent() {
            Box(Modifier.fillMaxSize().clickable(onClick = onBack), contentAlignment = Alignment.Center) {
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

// ─── Boundary swipe-down observer ──────────────────────────────────────────────
// Watches raw touch movement on the Initial pass (before the list's own scrolling
// consumes it) without ever calling consume() itself, so normal scrolling inside
// the list is completely unaffected. If the list was already scrolled to the very
// top at the moment the gesture began, and the finger then moves down past a small
// threshold, we treat that as "swipe down to go back".
private suspend fun PointerInputScope.observeBoundarySwipeDown(
    listState: LazyListState,
    onSwipeDown: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val wasAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        var dy = 0f
        var dx = 0f

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                if (wasAtTop && dy > 55f && dy > kotlin.math.abs(dx) * 1.2f) {
                    onSwipeDown()
                }
                break
            }
            val change = pressed[0]
            dy += change.positionChange().y
            dx += change.positionChange().x
        }
    }
}
