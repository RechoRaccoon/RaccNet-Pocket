package com.mediaviewer.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mediaviewer.model.*
import com.mediaviewer.ui.theme.*
import com.mediaviewer.viewmodel.MainViewModel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.*

private val SWIPE_ANIM = tween<IntOffset>(200, easing = FastOutSlowInEasing)
private val FADE_ANIM  = tween<Float>(150)

private enum class QuickAction { TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, TOP_LEFT }

private fun getHoveredAction(pos: Offset, center: Offset): QuickAction? {
    val dx = pos.x - center.x; val dy = pos.y - center.y
    if (sqrt(dx * dx + dy * dy) < 40f) return null
    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) // -180..180, 0=right, 90=down
    return when {
        angle < -157.5 || angle >= 157.5 -> QuickAction.LEFT
        angle < -112.5                   -> QuickAction.TOP_LEFT
        angle < -67.5                    -> QuickAction.TOP
        angle < -22.5                    -> QuickAction.TOP_RIGHT
        angle < 22.5                     -> QuickAction.RIGHT
        angle < 67.5                     -> QuickAction.BOTTOM_RIGHT
        angle < 112.5                    -> QuickAction.BOTTOM
        else                              -> QuickAction.BOTTOM_LEFT
    }
}

// ─── Root ─────────────────────────────────────────────────────────────────────

@Composable
fun MainFeedScreen(
    mediaItems: List<MediaItem>,
    currentIndex: Int,
    currentItem: MediaItem?,
    screenState: ScreenState,
    appMode: AppMode,
    navDirection: Int,
    reducedAnimations: Boolean,
    liquidGlass: Boolean,
    onToggleLiquidGlass: (Boolean) -> Unit,
    availableFeeds: List<BskyFeedInfo>,
    selectedFeedUri: String?,
    authorFeedState: MainViewModel.AuthorFeedSavedState?,
    comments: List<CommentItem>,
    commentsLoading: Boolean,
    downloadOnLike: Boolean,
    downloadProgress: DownloadProgress?,
    e621SearchTags: String,
    isLoading: Boolean,
    bskyLoggedIn: Boolean,
    e621LoggedIn: Boolean,
    bskyHandle: String,
    e621Username: String,
    errorMessage: String?,
    onNavigateNext: () -> Unit,
    onNavigatePrev: () -> Unit,
    onNavigateTo: (Int) -> Unit,
    onSetScreen: (ScreenState) -> Unit,
    onToggleLike: () -> Unit,
    onToggleRepost: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleFollow: () -> Unit,
    onE621Vote: (Int) -> Unit,
    onPostComment: (String) -> Unit,
    onLikeComment: (CommentItem) -> Unit,
    onVoteComment: (CommentItem, Int) -> Unit,
    onSelectFeed: (String?) -> Unit,
    onToggleDownloadOnLike: (Boolean) -> Unit,
    onDownloadAllLiked: () -> Unit,
    onCancelDownload: () -> Unit,
    onShowLikes: () -> Unit,
    onShowFriends: () -> Unit,
    onShowE621Following: () -> Unit,
    onToggleReducedAnimations: (Boolean) -> Unit,
    combineListsAndPacks: Boolean,
    onToggleCombineListsPacks: (Boolean) -> Unit,
    autoAddToOnFollow: Boolean,
    onToggleAutoAddToOnFollow: (Boolean) -> Unit,
    onLoginBluesky: (String, String) -> Unit,
    onLogoutBluesky: () -> Unit,
    onSaveE621Credentials: (String, String) -> Unit,
    onLogoutE621: () -> Unit,
    onSearchE621: (String) -> Unit,
    onShowE621Favorites: () -> Unit,
    onSwipeToMode: (AppMode) -> Unit,
    onLoadMore: () -> Unit,
    onDownloadCurrent: () -> Unit,
    onRefresh: () -> Unit,
    onTapAuthor: (MediaItem) -> Unit,
    onTagClick: (String) -> Unit,
    onTagAdd: (String) -> Unit,
    onTagExclude: (String) -> Unit,
    onSendPost: () -> Unit,
    onQuoteRepost: () -> Unit,
    onBlockAccount: () -> Unit,
    onDownloadGif: () -> Unit,
    sentByExpanded: Boolean,
    onToggleSentByExpanded: () -> Unit,
    onOpenReplyToSender: () -> Unit,
    friendsFeedLoadingOverlay: Boolean
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Item 3: which sub-image each multi-image post was left on, keyed by post id.
    // Lives here (above the per-post AnimatedContent) so it survives navigating
    // away to another post and back — a plain remember(item.id) inside PostContent
    // was getting torn down and reset to 0 every time.
    val subImageIndices = remember { mutableStateMapOf<String, Int>() }
    // Big Update #2: whether each post's text bubble is showing full text (true,
    // the default/natural size) or collapsed to one ellipsized line (false).
    // Hoisted here for the same reason as subImageIndices above.
    val textExpandedIndices = remember { mutableStateMapOf<String, Boolean>() }

    Box(Modifier.fillMaxSize().background(OledBlack)) {
        // In landscape while viewing the feed: fullscreen media only, no UI chrome
        if (isLandscape && screenState == ScreenState.FEED) {
            LandscapeMediaView(
                mediaItems        = mediaItems,
                currentIndex      = currentIndex,
                currentItem       = currentItem,
                reducedAnimations = reducedAnimations,
                isLoading         = isLoading,
                onSwipeLeft       = onNavigateNext,
                onSwipeRight      = onNavigatePrev
            )
        } else {
            AnimatedContent(
                targetState = screenState,
                transitionSpec = {
                    if (reducedAnimations) EnterTransition.None togetherWith ExitTransition.None
                    else when {
                        targetState == ScreenState.SETTINGS ->
                            slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it } togetherWith
                            slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it }
                        initialState == ScreenState.SETTINGS ->
                            slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it } togetherWith
                            slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { -it }
                        targetState == ScreenState.COMMENTS ->
                            (fadeIn(tween(180)) + scaleIn(tween(220, easing = FastOutSlowInEasing),
                                initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 0f))) togetherWith
                            (fadeOut(tween(140)) + scaleOut(tween(180, easing = FastOutSlowInEasing),
                                targetScale = 0.85f, transformOrigin = TransformOrigin(0.5f, 0f)))
                        initialState == ScreenState.COMMENTS ->
                            (fadeIn(tween(180)) + scaleIn(tween(220, easing = FastOutSlowInEasing),
                                initialScale = 0.92f)) togetherWith
                            (fadeOut(tween(140)) + scaleOut(tween(180, easing = FastOutSlowInEasing),
                                targetScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 0f)))
                        else -> fadeIn(FADE_ANIM) togetherWith fadeOut(FADE_ANIM)
                    }
                },
                label = "screen"
            ) { state ->
                when (state) {
                    ScreenState.FEED -> FeedView(
                        mediaItems        = mediaItems,
                        currentIndex      = currentIndex,
                        currentItem       = currentItem,
                        appMode           = appMode,
                        isLoading         = isLoading,
                        reducedAnimations = reducedAnimations,
                        liquidGlass       = liquidGlass,
                        onSwipeLeft       = onNavigateNext,
                        onSwipeRight      = onNavigatePrev,
                        onSwipeUp         = { onSetScreen(ScreenState.COMMENTS) },
                        onSwipeDown       = { onSetScreen(ScreenState.SETTINGS) },
                        onPinchToGrid     = { onSetScreen(ScreenState.GRID) },
                        onDoubleTap       = { haptic(context); if (appMode == AppMode.BLUESKY) onToggleLike() else onToggleBookmark() },
                        onToggleLike      = onToggleLike,
                        onToggleRepost    = onToggleRepost,
                        onToggleBookmark  = onToggleBookmark,
                        onToggleFollow    = onToggleFollow,
                        onE621Vote        = onE621Vote,
                        onDownload        = onDownloadCurrent,
                        onTapAuthor       = onTapAuthor,
                        onSendPost        = onSendPost,
                        onQuoteRepost     = onQuoteRepost,
                        onBlockAccount    = onBlockAccount,
                        onDownloadGif     = onDownloadGif,
                        sentByExpanded         = sentByExpanded,
                        onToggleSentByExpanded = onToggleSentByExpanded,
                        onOpenReplyToSender    = onOpenReplyToSender,
                        subImageIndices        = subImageIndices,
                        textExpandedIndices     = textExpandedIndices
                    )
                    ScreenState.COMMENTS -> CommentsSheet(
                        currentItem     = currentItem,
                        comments        = comments,
                        commentsLoading = commentsLoading,
                        appMode         = appMode,
                        liquidGlass     = liquidGlass,
                        onPostComment   = onPostComment,
                        onLikeComment   = onLikeComment,
                        onVoteComment   = onVoteComment,
                        onSwipeDown     = { onSetScreen(ScreenState.FEED) },
                        onTagClick      = onTagClick,
                        onTagAdd        = onTagAdd,
                        onTagExclude    = onTagExclude
                    )
                    ScreenState.SETTINGS -> SettingsSheet(
                        appMode                   = appMode,
                        bskyLoggedIn              = bskyLoggedIn,
                        e621LoggedIn              = e621LoggedIn,
                        bskyHandle                = bskyHandle,
                        e621Username              = e621Username,
                        availableFeeds            = availableFeeds,
                        selectedFeedUri           = selectedFeedUri,
                        authorFeedState           = authorFeedState,
                        downloadOnLike            = downloadOnLike,
                        downloadProgress          = downloadProgress,
                        reducedAnimations         = reducedAnimations,
                        liquidGlass               = liquidGlass,
                        onToggleLiquidGlass       = onToggleLiquidGlass,
                        e621SearchTags            = e621SearchTags,
                        isLoading                 = isLoading,
                        onLoginBluesky            = onLoginBluesky,
                        onLogoutBluesky           = onLogoutBluesky,
                        onSaveE621Credentials     = onSaveE621Credentials,
                        onLogoutE621              = onLogoutE621,
                        onSelectFeed              = { uri -> onSelectFeed(uri); onSetScreen(ScreenState.FEED) },
                        onToggleDownloadOnLike    = onToggleDownloadOnLike,
                        onDownloadAllLiked        = onDownloadAllLiked,
                        onCancelDownload          = onCancelDownload,
                        onShowLikes               = { onShowLikes(); onSetScreen(ScreenState.FEED) },
                        onShowFriends             = { onShowFriends(); onSetScreen(ScreenState.FEED) },
                        onShowE621Following       = { onShowE621Following(); onSetScreen(ScreenState.FEED) },
                        onToggleReducedAnimations = onToggleReducedAnimations,
                        combineListsAndPacks      = combineListsAndPacks,
                        onToggleCombineListsPacks = onToggleCombineListsPacks,
                        autoAddToOnFollow         = autoAddToOnFollow,
                        onToggleAutoAddToOnFollow = onToggleAutoAddToOnFollow,
                        onSearchE621              = { tags -> onSearchE621(tags); onSetScreen(ScreenState.FEED) },
                        onShowE621Favorites       = { onShowE621Favorites(); onSetScreen(ScreenState.FEED) },
                        onSwitchMode              = onSwipeToMode,
                        onSwipeToFeed             = { onSetScreen(ScreenState.FEED) }
                    )
                    ScreenState.GRID -> GridScreen(
                        items           = mediaItems,
                        currentIndex    = currentIndex,
                        appMode         = appMode,
                        availableFeeds  = availableFeeds,
                        selectedFeedUri = selectedFeedUri,
                        authorFeedState = authorFeedState,
                        e621SearchTags  = e621SearchTags,
                        onItemClick     = { idx -> onNavigateTo(idx) },
                        onLoadMore      = onLoadMore,
                        onSelectFeed    = onSelectFeed,
                        onSearchE621    = onSearchE621,
                        onRefresh       = onRefresh
                    )
                }
            }
        }

        if (errorMessage != null) {
            Snackbar(
                modifier       = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = OffBlack,
                contentColor   = Color.White
            ) { Text(errorMessage, fontSize = 13.sp) }
        }

        // Item 2: shown only when the From Friends feed wasn't already warmed up
        // in the background — disappears the instant it finishes loading.
        if (friendsFeedLoadingOverlay) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(10f),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading From Friends feed…", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

// ─── Landscape-only fullscreen media view ─────────────────────────────────────

@Composable
private fun LandscapeMediaView(
    mediaItems: List<MediaItem>,
    currentIndex: Int,
    currentItem: MediaItem?,
    reducedAnimations: Boolean,
    isLoading: Boolean,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isLoading && currentItem == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 1.5.dp)
        } else {
            AnimatedContent(
                targetState = currentIndex,
                transitionSpec = {
                    if (reducedAnimations) EnterTransition.None togetherWith ExitTransition.None
                    else {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(SWIPE_ANIM) { it * dir } + fadeIn(FADE_ANIM)) togetherWith
                        (slideOutHorizontally(SWIPE_ANIM) { -it * dir } + fadeOut(FADE_ANIM))
                    }
                },
                label = "landscape"
            ) { idx ->
                val item = mediaItems.getOrNull(idx) ?: return@AnimatedContent
                var dx by remember { mutableFloatStateOf(0f) }
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(idx) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                dx = 0f
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Main)
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) {
                                        if (dx < -80f) onSwipeLeft()
                                        else if (dx > 80f) onSwipeRight()
                                        break
                                    }
                                    dx += pressed[0].positionChange().x
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (item.isVideo && item.videoPlaylistUrl != null) {
                        VideoPlayer(item.videoPlaylistUrl, Modifier.fillMaxSize())
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(item.mediaUrl).crossfade(true).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

// ─── Feed View ────────────────────────────────────────────────────────────────

@Composable
private fun FeedView(
    mediaItems: List<MediaItem>,
    currentIndex: Int,
    currentItem: MediaItem?,
    appMode: AppMode,
    isLoading: Boolean,
    reducedAnimations: Boolean,
    liquidGlass: Boolean,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onPinchToGrid: () -> Unit,
    onDoubleTap: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleRepost: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleFollow: () -> Unit,
    onE621Vote: (Int) -> Unit,
    onDownload: () -> Unit,
    onTapAuthor: (MediaItem) -> Unit,
    onSendPost: () -> Unit,
    onQuoteRepost: () -> Unit,
    onBlockAccount: () -> Unit,
    onDownloadGif: () -> Unit,
    sentByExpanded: Boolean,
    onToggleSentByExpanded: () -> Unit,
    onOpenReplyToSender: () -> Unit,
    subImageIndices: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>,
    textExpandedIndices: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>
) {
    val context     = LocalContext.current
    val imageLoader = remember { ImageLoader(context) }

    LaunchedEffect(currentIndex) {
        (1..3).mapNotNull { mediaItems.getOrNull(currentIndex + it) }.forEach { item ->
            if (!item.isVideo && item.mediaUrl.isNotBlank())
                imageLoader.enqueue(ImageRequest.Builder(context).data(item.mediaUrl).build())
            if (item.thumbUrl.isNotBlank())
                imageLoader.enqueue(ImageRequest.Builder(context).data(item.thumbUrl).build())
        }
    }

    Box(Modifier.fillMaxSize().background(OledBlack)) {
        if (isLoading && currentItem == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 1.5.dp)
        } else {
            AnimatedContent(
                targetState = currentIndex,
                transitionSpec = {
                    if (reducedAnimations) EnterTransition.None togetherWith ExitTransition.None
                    else {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(SWIPE_ANIM) { it * dir } + fadeIn(FADE_ANIM)) togetherWith
                        (slideOutHorizontally(SWIPE_ANIM) { -it * dir } + fadeOut(FADE_ANIM))
                    }
                },
                label = "post"
            ) { idx ->
                val item = mediaItems.getOrNull(idx) ?: return@AnimatedContent
                PostContent(
                    item             = item,
                    appMode          = appMode,
                    liquidGlass      = liquidGlass,
                    onSwipeLeft      = onSwipeLeft,
                    onSwipeRight     = onSwipeRight,
                    onSwipeUp        = onSwipeUp,
                    onSwipeDown      = onSwipeDown,
                    onPinchToGrid    = onPinchToGrid,
                    onDoubleTap      = onDoubleTap,
                    onToggleLike     = onToggleLike,
                    onToggleRepost   = onToggleRepost,
                    onToggleBookmark = onToggleBookmark,
                    onToggleFollow   = onToggleFollow,
                    onE621Vote       = onE621Vote,
                    onDownload       = onDownload,
                    onTapAuthor      = { onTapAuthor(item) },
                    onSendPost       = onSendPost,
                    onQuoteRepost    = onQuoteRepost,
                    onBlockAccount   = onBlockAccount,
                    onDownloadGif    = onDownloadGif,
                    sentByExpanded         = sentByExpanded,
                    onToggleSentByExpanded = onToggleSentByExpanded,
                    onOpenReplyToSender    = onOpenReplyToSender,
                    subImageIndex          = subImageIndices[item.id] ?: 0,
                    getSubImageIndex       = { subImageIndices[item.id] ?: 0 },
                    onSetSubImageIndex     = { subImageIndices[item.id] = it },
                    textExpanded            = textExpandedIndices[item.id] ?: true,
                    onToggleTextExpanded    = { textExpandedIndices[item.id] = !(textExpandedIndices[item.id] ?: true) },
                    reducedAnimations      = reducedAnimations
                )
            }
        }
    }
}

// ─── Post Content ─────────────────────────────────────────────────────────────

@Composable
private fun PostContent(
    item: MediaItem, appMode: AppMode, liquidGlass: Boolean,
    onSwipeLeft: () -> Unit, onSwipeRight: () -> Unit,
    onSwipeUp: () -> Unit, onSwipeDown: () -> Unit,
    onPinchToGrid: () -> Unit, onDoubleTap: () -> Unit,
    onToggleLike: () -> Unit, onToggleRepost: () -> Unit,
    onToggleBookmark: () -> Unit, onToggleFollow: () -> Unit,
    onE621Vote: (Int) -> Unit, onDownload: () -> Unit,
    onTapAuthor: () -> Unit,
    onSendPost: () -> Unit, onQuoteRepost: () -> Unit,
    onBlockAccount: () -> Unit, onDownloadGif: () -> Unit,
    sentByExpanded: Boolean, onToggleSentByExpanded: () -> Unit,
    onOpenReplyToSender: () -> Unit,
    subImageIndex: Int, getSubImageIndex: () -> Int, onSetSubImageIndex: (Int) -> Unit,
    textExpanded: Boolean, onToggleTextExpanded: () -> Unit,
    reducedAnimations: Boolean
) {
    val context = LocalContext.current
    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(item.id) { scale = 1f; offset = Offset.Zero }

    var menuCenter    by remember { mutableStateOf<Offset?>(null) }
    var hoveredAction by remember { mutableStateOf<QuickAction?>(null) }

    // Big Update #1: sampled average color of the current post's media — feeds
    // the liquid-glass panels so their tint/"reflection" shifts with whatever
    // is on screen, per post.
    val glassBackdropUrl = item.thumbUrl.ifBlank { item.mediaUrl }
    val dominantColor = if (liquidGlass) rememberDominantColor(glassBackdropUrl) else Color.White

    fun clampOffset(raw: Offset, s: Float): Offset {
        if (s <= 1.001f || containerSize == IntSize.Zero) return Offset.Zero
        val maxX = containerSize.width  * (s - 1f) / 2f
        val maxY = containerSize.height * (s - 1f) / 2f
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier.fillMaxSize()
            .then(if (liquidGlass) Modifier.background(postBackgroundBrush(dominantColor)) else Modifier)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
                .pointerInput(item.id) {
                    var lastTapMs = 0L
                    var lastTapPos: Offset? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPos = down.position
                        val downTime = System.currentTimeMillis()
                        val prevPos = lastTapPos
                        val isNearLastTap = prevPos != null && run {
                            val ddx = downPos.x - prevPos.x; val ddy = downPos.y - prevPos.y
                            kotlin.math.sqrt(ddx * ddx + ddy * ddy) < 60.dp.toPx()
                        }
                        if (downTime - lastTapMs < 280L && isNearLastTap && scale <= 1.05f) {
                            onDoubleTap(); down.consume(); lastTapMs = 0L; lastTapPos = null; return@awaitEachGesture
                        }
                        lastTapMs = downTime
                        lastTapPos = downPos

                        var dx = 0f; var dy = 0f
                        var menuOpen = false; var longPressFired = false
                        var prevPinchDist = -1f; var prevCentroid = downPos
                        var pointerCountEverTwo = false
                        var gridArmDist = -1f; var gridArmed = false
                        // Item 6: once a sibling composable (e.g. the text
                        // bubble's own swipe-to-expand gesture) consumes this
                        // pointer, this outer gesture stops tracking it —
                        // no page swipe, no long-press menu — for the rest
                        // of this touch.
                        var externallyClaimed = false

                        while (true) {
                            val elapsed = System.currentTimeMillis() - downTime
                            if (!menuOpen && !longPressFired && !pointerCountEverTwo && !externallyClaimed &&
                                elapsed >= 450L && abs(dx) < 28f && abs(dy) < 28f && scale <= 1.05f) {
                                longPressFired = true; haptic(context)
                                menuCenter = downPos; menuOpen = true
                            }
                            val result = withTimeoutOrNull(16L) { awaitPointerEvent(PointerEventPass.Main) }
                            val event = result ?: continue
                            val pressed = event.changes.filter { it.pressed }

                            if (pressed.isEmpty()) {
                                if (menuOpen) {
                                    haptic(context)
                                    when (hoveredAction) {
                                        QuickAction.TOP          -> if (appMode == AppMode.BLUESKY) onToggleLike()     else onE621Vote(1)
                                        QuickAction.BOTTOM       -> if (appMode == AppMode.BLUESKY) onDownload()       else onE621Vote(-1)
                                        QuickAction.LEFT         -> if (appMode == AppMode.BLUESKY) onToggleBookmark() else onDownload()
                                        QuickAction.RIGHT        -> if (appMode == AppMode.BLUESKY) onToggleRepost()   else onToggleBookmark()
                                        QuickAction.TOP_RIGHT    -> if (appMode == AppMode.BLUESKY) onSendPost()
                                        QuickAction.BOTTOM_RIGHT -> if (appMode == AppMode.BLUESKY) onQuoteRepost()
                                        QuickAction.BOTTOM_LEFT  -> if (appMode == AppMode.BLUESKY) onBlockAccount()
                                        QuickAction.TOP_LEFT     -> if (appMode == AppMode.BLUESKY) onDownloadGif()
                                        null -> {}
                                    }
                                    menuCenter = null; hoveredAction = null
                                } else if (scale <= 1.05f && !externallyClaimed) {
                                    when {
                                        abs(dx) > 80f && abs(dx) > abs(dy) * 1.2f -> {
                                            val groupSize = item.mediaGroup.size
                                            // Read fresh here — this pointerInput block only
                                            // relaunches when item.id changes, so a captured
                                            // subImageIndex value would go stale after the
                                            // first swipe and cause exactly this kind of
                                            // stuck/skipping behavior.
                                            val curSubIdx = getSubImageIndex()
                                            if (dx < 0) {
                                                // swiping toward "next"
                                                if (groupSize > 1 && curSubIdx < groupSize - 1) onSetSubImageIndex(curSubIdx + 1)
                                                else onSwipeLeft()
                                            } else {
                                                // swiping toward "previous"
                                                if (groupSize > 1 && curSubIdx > 0) onSetSubImageIndex(curSubIdx - 1)
                                                else onSwipeRight()
                                            }
                                        }
                                        abs(dy) > 80f && abs(dy) > abs(dx) * 1.2f -> if (dy < 0) onSwipeUp() else onSwipeDown()
                                    }
                                }
                                if (scale <= 1.02f) { scale = 1f; offset = Offset.Zero }
                                break
                            }
                            if (pressed.size >= 2) {
                                pointerCountEverTwo = true; menuOpen = false; menuCenter = null; hoveredAction = null
                                val p1 = pressed[0].position; val p2 = pressed[1].position
                                val dist = (p1 - p2).getDistance(); val centroid = (p1 + p2) / 2f
                                if (gridArmDist < 0f) { gridArmDist = dist; gridArmed = scale <= 1.01f }
                                if (prevPinchDist > 0f) {
                                    val rawNew = scale * dist / prevPinchDist
                                    if (gridArmed && dist < gridArmDist * 0.7f) {
                                        scale = 1f; offset = Offset.Zero; onPinchToGrid(); break
                                    }
                                    val newScale = rawNew.coerceIn(1f, 8f)
                                    scale = newScale
                                    offset = clampOffset(if (newScale > 1.02f) offset + (centroid - prevCentroid) else Offset.Zero, newScale)
                                }
                                prevPinchDist = dist; prevCentroid = centroid
                                pressed.forEach { it.consume() }
                            } else {
                                prevPinchDist = -1f
                                val ch = pressed[0]; val delta = ch.positionChange()
                                if (menuOpen) { hoveredAction = getHoveredAction(ch.position, menuCenter!!); ch.consume() }
                                else if (scale > 1.05f) { offset = clampOffset(offset + delta, scale); ch.consume() }
                                else if (ch.isConsumed) { externallyClaimed = true }
                                else {
                                    dx += delta.x; dy += delta.y
                                    if (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop) longPressFired = true
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val mediaModifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }.let { if (item.isBlocked) it.blur(28.dp) else it }
            if (item.isTextOnly) {
                // Big Update #3: text-only posts get a liquid-glass card shaped
                // like a piece of media, centered where an image would sit.
                TextOnlyPostCard(item.text, dominantColor)
            } else if (item.isVideo && item.videoPlaylistUrl != null) {
                VideoPlayer(item.videoPlaylistUrl, mediaModifier)
            } else {
                // Item 3: sub-image switches animate with the same slide+fade the
                // outer post-to-post transition uses, instead of an instant cut.
                AnimatedContent(
                    targetState = subImageIndex,
                    transitionSpec = {
                        if (reducedAnimations) EnterTransition.None togetherWith ExitTransition.None
                        else {
                            val dir = if (targetState > initialState) 1 else -1
                            (slideInHorizontally(SWIPE_ANIM) { it * dir } + fadeIn(FADE_ANIM)) togetherWith
                            (slideOutHorizontally(SWIPE_ANIM) { -it * dir } + fadeOut(FADE_ANIM))
                        }
                    },
                    label = "subImage"
                ) { idx ->
                    val currentImage = item.mediaGroup.getOrNull(idx)
                    val displayThumb = currentImage?.thumbUrl ?: item.thumbUrl
                    val displayFull  = currentImage?.mediaUrl ?: item.mediaUrl
                    val displayAlt   = currentImage?.altText ?: item.altText
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AsyncImage(model = displayThumb.ifBlank { displayFull }, contentDescription = null,
                            contentScale = ContentScale.Fit, modifier = mediaModifier)
                        if (displayFull != displayThumb && displayFull.isNotBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(displayFull).crossfade(true).build(),
                                contentDescription = displayAlt.ifBlank { null },
                                contentScale = ContentScale.Fit, modifier = mediaModifier
                            )
                        }
                    }
                }
            }
            if (item.isBlocked) {
                Text("Blocked", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center))
            }
            val mc = menuCenter
            if (mc != null) QuickActionMenu(center = mc, hoveredAction = hoveredAction, appMode = appMode, item = item, liquidGlass = liquidGlass, dominantColor = dominantColor, backdropUrl = glassBackdropUrl)
        }

        // Author and action rows on top (zIndex ensures they're tappable over the media)
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).zIndex(2f)) {
            // Item 7: "Sent by" header shown above the regular post header for DM-shared posts
            item.sentByAuthor?.let { sender ->
                SentByHeader(
                    sender = sender, message = item.sentByMessage,
                    expanded = sentByExpanded, onToggleExpanded = onToggleSentByExpanded,
                    onReply = onOpenReplyToSender,
                    modifier = Modifier.fillMaxWidth()
                        .background(Color.Black.copy(0.55f))
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            AuthorRow(item, appMode, onToggleFollow, onTapAuthor,
                Modifier.fillMaxWidth()
                    .then(if (item.sentByAuthor == null) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier),
                liquidGlass = liquidGlass,
                dominantColor = dominantColor,
                backdropUrl = glassBackdropUrl,
                isTextOnly = item.isTextOnly,
                textExpanded = textExpanded,
                onToggleTextExpanded = onToggleTextExpanded
            )
        }

        // Four extra quick-shortcuts (item 3) now live as diagonal buttons in the
        // long-press radial menu above (see QuickActionMenu / getHoveredAction) —
        // they are Bluesky-only, matching the existing radial menu's action set.

        // Bottom cluster: the "1/4" style page indicator (only for multi-image
        // posts) now sits as its own small liquid-glass pill, centered directly
        // above the upload button, with a bit of breathing room before the
        // interaction bar underneath it.
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).zIndex(2f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (item.mediaGroup.size > 1) {
                val pageText = "${subImageIndex + 1}/${item.mediaGroup.size}"
                val pageShape = RoundedCornerShape(14.dp)
                if (liquidGlass) {
                    LiquidGlassSurface(
                        modifier = Modifier.padding(bottom = 8.dp),
                        shape = pageShape, tint = dominantColor, backdropUrl = glassBackdropUrl
                    ) {
                        Text(
                            pageText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    Box(
                        Modifier.padding(bottom = 8.dp).clip(pageShape).background(Color.Black.copy(0.5f))
                    ) {
                        Text(
                            pageText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            ActionRow(item, appMode, onToggleLike, onToggleRepost, onToggleBookmark, onE621Vote,
                onQuoteRepost, onDownload, onDownloadGif, onBlockAccount, onSendPost,
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(if (liquidGlass) 60.dp else 52.dp),
                liquidGlass = liquidGlass,
                dominantColor = dominantColor,
                backdropUrl = glassBackdropUrl
            )
        }
    }
}

// ─── "Sent by" header (item 7) ────────────────────────────────────────────────

@Composable
private fun SentByHeader(
    sender: AuthorInfo, message: String, expanded: Boolean,
    onToggleExpanded: () -> Unit, onReply: () -> Unit, modifier: Modifier = Modifier
) {
    // Item 3: name + message are built as one flowing, wrapping Text instead of
    // two fixed rows, so the message starts right after the ":" and only spills
    // onto its own line once it's actually long enough to need it.
    val avatarContentId = "sentByAvatar"
    val annotated = remember(sender, message) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                append("Sent by ")
            }
            if (sender.avatarUrl != null) {
                appendInlineContent(avatarContentId, "[avatar]")
                append(" ")
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                append(sender.displayName)
                append(":")
            }
            if (message.isNotBlank()) {
                withStyle(SpanStyle(color = Color.White.copy(alpha = 0.9f))) {
                    append(" ")
                    append(message)
                }
            }
        }
    }
    val inlineContent = remember(sender.avatarUrl) {
        mapOf(
            avatarContentId to InlineTextContent(
                Placeholder(width = 16.sp, height = 16.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter)
            ) {
                AsyncImage(model = sender.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape))
            }
        )
    }

    // Reply always sits flush against the far right edge — it only drops to its
    // own line below once the last line of text is actually long enough that
    // there's no room left for it up there. "Reply" is a fixed, known label at a
    // fixed size, so its width is reserved as a constant rather than re-measured
    // per post (which would otherwise cause a one-frame layout flash every time
    // a new post scrolls into view).
    val density = LocalDensity.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fitsInline = remember(layoutResult) {
        val lr = layoutResult
        if (lr == null) false
        else {
            val reservedPx = with(density) { 54.dp.roundToPx() } // "Reply" label + gap
            val lastLine = lr.lineCount - 1
            (lr.size.width - lr.getLineRight(lastLine)) >= reservedPx
        }
    }

    Column(modifier = modifier) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                text = annotated,
                fontSize = 13.sp,
                inlineContent = inlineContent,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layoutResult = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggleExpanded() }
            )
            if (fitsInline) {
                val lr = layoutResult!!
                val lastLineTop = lr.getLineTop(lr.lineCount - 1)
                Text(
                    "Reply", color = VoteGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = with(density) { lastLineTop.toDp() })
                        .clickable(onClick = onReply)
                )
            }
        }
        if (!fitsInline) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End) {
                Text("Reply", color = VoteGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onReply))
            }
        }
    }
}

// ─── Quick Action Menu ────────────────────────────────────────────────────────

@Composable
private fun QuickActionMenu(center: Offset, hoveredAction: QuickAction?, appMode: AppMode, item: MediaItem, liquidGlass: Boolean, dominantColor: Color, backdropUrl: String) {
    val density = LocalDensity.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val menuScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "ms"
    )
    val cx = with(density) { center.x.toDp() }; val cy = with(density) { center.y.toDp() }
    val radius = 70.dp
    val diag = radius * 0.7071f // equal distance from center on the diagonals
    val actions = if (appMode == AppMode.BLUESKY) listOf(
        Triple(QuickAction.TOP,          Icons.Filled.Favorite,  if (item.isLiked) LikeRed else Color.White),
        Triple(QuickAction.TOP_RIGHT,    Icons.Default.Send,     Color.White),
        Triple(QuickAction.RIGHT,        Icons.Default.Repeat,   if (item.isReposted) RepostGreen else Color.White),
        Triple(QuickAction.BOTTOM_RIGHT, Icons.Default.EditNote, if (item.isQuoteReposted) RepostGreen else Color.White),
        Triple(QuickAction.BOTTOM,       Icons.Default.Download, if (item.isDownloaded) BookmarkYellow else Color.White),
        Triple(QuickAction.BOTTOM_LEFT,  Icons.Default.Block,    if (item.isBlocked) Color(0xFFE0245E) else Color.White),
        Triple(QuickAction.LEFT,         Icons.Filled.Bookmark,  if (item.isBookmarked) BookmarkYellow else Color.White),
        Triple(QuickAction.TOP_LEFT,     Icons.Default.Download, if (item.isGifDownloaded) BookmarkYellow else Color.White) // rendered as "GIF" text, see below
    ) else listOf(
        Triple(QuickAction.TOP,    Icons.Default.ArrowUpward,   if (item.e621UserVote == 1) VoteGreen else Color.White),
        Triple(QuickAction.RIGHT,  Icons.Filled.Star,           if (item.isBookmarked) BookmarkYellow else Color.White),
        Triple(QuickAction.BOTTOM, Icons.Default.ArrowDownward, if (item.e621UserVote == -1) VoteRed else Color.White),
        Triple(QuickAction.LEFT,   Icons.Default.Download,      if (item.isDownloaded) BookmarkYellow else Color.White)
    )
    Box(Modifier.fillMaxSize().zIndex(3f)) {
        actions.forEach { (action, icon, tint) ->
            val (bx, by) = when (action) {
                QuickAction.TOP          -> Pair(cx - 24.dp, cy - radius - 24.dp)
                QuickAction.BOTTOM       -> Pair(cx - 24.dp, cy + radius - 24.dp)
                QuickAction.LEFT         -> Pair(cx - radius - 24.dp, cy - 24.dp)
                QuickAction.RIGHT        -> Pair(cx + radius - 24.dp, cy - 24.dp)
                QuickAction.TOP_LEFT     -> Pair(cx - diag - 24.dp, cy - diag - 24.dp)
                QuickAction.TOP_RIGHT    -> Pair(cx + diag - 24.dp, cy - diag - 24.dp)
                QuickAction.BOTTOM_LEFT  -> Pair(cx - diag - 24.dp, cy + diag - 24.dp)
                QuickAction.BOTTOM_RIGHT -> Pair(cx + diag - 24.dp, cy + diag - 24.dp)
            }
            val isHovered = hoveredAction == action
            val btnScale by animateFloatAsState(
                targetValue = if (isHovered) 1.3f else 1f,
                animationSpec = spring(Spring.DampingRatioMediumBouncy), label = "btn"
            )
            if (liquidGlass) {
                LiquidGlassSurface(
                    modifier = Modifier.offset(x = bx, y = by).scale(menuScale * btnScale).size(48.dp),
                    shape = CircleShape,
                    tint = if (isHovered) Color.White.copy(alpha = 0.6f) else dominantColor,
                    backdropUrl = backdropUrl
                ) {
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                        if (action == QuickAction.TOP_LEFT && appMode == AppMode.BLUESKY) {
                            Text("GIF", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.offset(x = bx, y = by).scale(menuScale * btnScale)
                        .size(48.dp).clip(CircleShape).background(if (isHovered) Color.White.copy(0.25f) else Color(0xFF1C1C1C)),
                    contentAlignment = Alignment.Center
                ) {
                    if (action == QuickAction.TOP_LEFT && appMode == AppMode.BLUESKY) {
                        Text("GIF", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

// ─── Liquid Glass primitives (Big Update #1) ───────────────────────────────────
// rememberDominantColor / LiquidGlassSurface / UploadPlaceholderButton now live
// in GlassTheme.kt so Settings, Comments, and the quick-action menu can share
// them too.

/** Big Update #3: text-only posts (no image/video) render as a centered,
 *  media-shaped liquid glass card holding just the post text. */
@Composable
private fun TextOnlyPostCard(text: String, dominantColor: Color) {
    LiquidGlassSurface(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .heightIn(min = 160.dp, max = 520.dp)
            .wrapContentHeight(),
        shape = RoundedCornerShape(28.dp),
        tint = dominantColor
    ) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 26.dp), contentAlignment = Alignment.Center) {
            Text(
                text.ifBlank { " " },
                color = Color.White, fontSize = 19.sp, lineHeight = 26.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis, maxLines = 14
            )
        }
    }
}

// ─── Author Row ───────────────────────────────────────────────────────────────

@Composable
private fun AuthorRow(
    item: MediaItem, appMode: AppMode, onToggleFollow: () -> Unit, onTapAuthor: () -> Unit,
    modifier: Modifier, liquidGlass: Boolean, dominantColor: Color, backdropUrl: String,
    isTextOnly: Boolean,
    textExpanded: Boolean, onToggleTextExpanded: () -> Unit
) {
    val author = item.author
    // Item 7: text-only posts already show their full text as their own big
    // card in the middle of the screen — showing it a second time in this
    // pill is redundant, so the pill only shows it for posts that have media.
    val postText = if (isTextOnly) "" else item.text
    val pillShape = RoundedCornerShape(16.dp)
    val followShape = RoundedCornerShape(14.dp)

    // Big Update #2: username/display-name pill grows downward to show the
    // post's own text. Default is fully expanded (natural size for however
    // many lines the text needs); swiping up/down on the bubble (item 6)
    // collapses it to a single ellipsized line or restores it.
    @Composable
    fun PillContent() {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(
                modifier = Modifier.clickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() },
                    onClick = onTapAuthor
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (author.avatarUrl != null) {
                    AsyncImage(model = author.avatarUrl, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.size(24.dp).clip(CircleShape))
                } else {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(0.12f)))
                }
                Text(author.displayName, color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp))
                if (appMode == AppMode.BLUESKY) {
                    Text("@${author.handle}", color = if (liquidGlass) Color.White.copy(0.75f) else DimGray,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (postText.isNotBlank()) {
                Text(
                    postText,
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = if (textExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    Row(modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top) {

        // Item 6: the bubble opens/closes on a vertical swipe, not a tap —
        // and it claims ONLY clear vertical drags. A plain tap or a
        // horizontal drag is never consumed here, so it falls straight
        // through to the page's own gesture handling: swiping left/right
        // between posts and double-tapping to like both keep working
        // exactly as if this bubble weren't there.
        val pillModifier = Modifier
            .weight(1f)
            .clip(pillShape)
            .pointerInput(item.id, postText, textExpanded) {
                if (postText.isBlank()) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var totalX = 0f; var totalY = 0f; var claimed = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val change = pressed[0]
                        val delta = change.positionChange()
                        totalX += delta.x; totalY += delta.y
                        if (!claimed && abs(totalY) > 24f && abs(totalY) > abs(totalX) * 1.3f) claimed = true
                        if (claimed) change.consume()
                    }
                    if (claimed) {
                        if (totalY < -60f && !textExpanded) onToggleTextExpanded()
                        else if (totalY > 60f && textExpanded) onToggleTextExpanded()
                    }
                    // Horizontal swipes and plain taps are never consumed —
                    // they pass through to the page underneath untouched.
                }
            }

        if (liquidGlass) {
            LiquidGlassSurface(modifier = pillModifier, shape = pillShape, tint = dominantColor, backdropUrl = backdropUrl) { PillContent() }
        } else {
            Box(pillModifier.background(Color.Black.copy(alpha = 0.55f))) { PillContent() }
        }

        Spacer(Modifier.width(8.dp))

        // Follow button — its own separate rounded pill. Pinned to the TOP of
        // the row (not vertically centered) so it stays put at the top-right
        // even as the pill beside it grows taller to show post text.
        val following = author.isFollowing
        val followModifier = Modifier
            .align(Alignment.Top)
            .clip(followShape)
            .clickable(onClick = onToggleFollow)

        @Composable
        fun FollowLabel() {
            // Fixed-width label: "Following" (the longer word) is laid out
            // invisibly to reserve the button's width, and the real label is
            // drawn centered on top — so switching between "Follow" and
            // "Following" never resizes the button (and never pushes/resizes
            // the text pill next to it).
            Box(contentAlignment = Alignment.Center) {
                Text("Following", color = Color.Transparent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text(if (following) "Following" else "Follow",
                    color = if (liquidGlass) Color.White.copy(alpha = if (following) 0.65f else 1f)
                            else if (following) DimGray else Color.White,
                    fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }

        if (liquidGlass) {
            // Item 2: the rim now always reacts to the post's own media color,
            // exactly like every other glass button — "Following" is no
            // longer a flat white rim, it's shown via the dimmer label text
            // above instead, so the rim itself stays consistent.
            LiquidGlassSurface(
                modifier = followModifier, shape = followShape,
                tint = dominantColor, backdropUrl = backdropUrl
            ) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) { FollowLabel() }
            }
        } else {
            Box(
                followModifier
                    .background(if (following) Color.White.copy(0.07f) else Color.White.copy(0.14f))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) { FollowLabel() }
        }
    }
}

// ─── Action Row ───────────────────────────────────────────────────────────────

@Composable
private fun ActionRow(
    item: MediaItem, appMode: AppMode,
    onToggleLike: () -> Unit, onToggleRepost: () -> Unit,
    onToggleBookmark: () -> Unit, onE621Vote: (Int) -> Unit,
    onQuoteRepost: () -> Unit, onDownload: () -> Unit,
    onDownloadGif: () -> Unit, onBlockAccount: () -> Unit, onShare: () -> Unit,
    modifier: Modifier, liquidGlass: Boolean, dominantColor: Color, backdropUrl: String
) {
    @Composable
    fun RowContent() {
        if (appMode == AppMode.BLUESKY) {
            // Item 1: like / upload / block are the three fixed anchors —
            // left, middle, right. The two triples in between (save/repost/
            // quote-repost, and share/download/GIF) each live in their own
            // equal-weight Row with SpaceEvenly, so they automatically
            // distribute themselves across whatever room is left between the
            // anchors instead of ever overlapping — and since both weighted
            // Rows get identical width (equal weight, and Like/Block are the
            // same fixed size), the upload button naturally lands dead-center.
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ActionButton(if (item.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    if (item.isLiked) LikeRed else Color.White, null, onToggleLike)
                Row(Modifier.weight(1f).fillMaxHeight(),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(if (item.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        if (item.isBookmarked) BookmarkYellow else Color.White, null, onToggleBookmark)
                    ActionButton(Icons.Default.Repeat,
                        if (item.isReposted) RepostGreen else Color.White, null, onToggleRepost)
                    ActionButton(Icons.Default.EditNote, if (item.isQuoteReposted) RepostGreen else Color.White, null, onQuoteRepost)
                }
                UploadPlaceholderButton(liquidGlass)
                Row(Modifier.weight(1f).fillMaxHeight(),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(Icons.Default.Share, Color.White, null, onShare)
                    ActionButton(Icons.Default.Download, if (item.isDownloaded) BookmarkYellow else Color.White, null, onDownload)
                    GifActionButton(onDownloadGif, if (item.isGifDownloaded) BookmarkYellow else Color.White)
                }
                ActionButton(Icons.Default.Block, if (item.isBlocked) Color(0xFFE0245E) else Color.White, null, onBlockAccount)
            }
        } else {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionButton(Icons.Default.ArrowUpward, if (item.e621UserVote == 1) VoteGreen else Color.White, null) { onE621Vote(1) }
                if (item.e621Score != 0)
                    Text(if (item.e621Score > 0) "+${item.e621Score}" else "${item.e621Score}",
                        color = if (item.e621Score > 0) VoteGreen else VoteRed, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                ActionButton(Icons.Default.ArrowDownward, if (item.e621UserVote == -1) VoteRed else Color.White, null) { onE621Vote(-1) }
                Spacer(Modifier.width(48.dp)) // reserved space so content doesn't sit under the centered upload button
                ActionButton(if (item.isBookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    if (item.isBookmarked) BookmarkYellow else Color.White, null, onToggleBookmark)
                ActionButton(Icons.Default.Download, if (item.isDownloaded) BookmarkYellow else Color.White, null, onDownload)
                GifActionButton(onDownloadGif, if (item.isGifDownloaded) BookmarkYellow else Color.White)
            }
        }
    }

    @Composable
    fun RowWithCenteredUploadForE621() {
        Box(Modifier.fillMaxSize()) {
            RowContent()
            UploadPlaceholderButton(liquidGlass, modifier = Modifier.align(Alignment.Center))
        }
    }

    val content: @Composable () -> Unit = if (appMode == AppMode.BLUESKY) { { RowContent() } } else { { RowWithCenteredUploadForE621() } }

    if (liquidGlass) {
        val shape = RoundedCornerShape(26.dp)
        LiquidGlassSurface(
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            shape = shape, tint = dominantColor, backdropUrl = backdropUrl
        ) { content() }
    } else {
        Box(modifier.background(Color.Black.copy(0.55f))) { content() }
    }
}

@Composable
private fun GifActionButton(onClick: () -> Unit, tint: Color = Color.White) {
    Box(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp)
            .size(width = 30.dp, height = 26.dp),
        contentAlignment = Alignment.Center
    ) { Text("GIF", color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ActionButton(icon: ImageVector, tint: Color, label: String? = null, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        if (label != null) Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ─── Video Player ─────────────────────────────────────────────────────────────

@Composable
private fun VideoPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player  = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE; volume = 1f } }
    // Item 5: the transport controls are drawn by PlayerView itself, over
    // whatever bounds it's given — so instead of stretching PlayerView across
    // the whole screen (which spreads the controls across empty letterboxed
    // space and lets them overlap the rest of the UI), size it to the video's
    // own aspect ratio and center it, the same way the image posts are fit.
    var aspectRatio by remember(url) { mutableStateOf(1f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(url) { player.setMediaItem(ExoMediaItem.fromUri(url)); player.prepare(); player.play() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply {
                this.player = player; useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }},
            modifier = Modifier.aspectRatio(aspectRatio)
        )
    }
}

private fun haptic(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator.vibrate(VibrationEffect.createOneShot(38, VibrationEffect.DEFAULT_AMPLITUDE))
        else {
            @Suppress("DEPRECATION") val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(38, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(38)
        }
    } catch (_: Exception) {}
}
