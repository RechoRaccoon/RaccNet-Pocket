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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
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
import kotlinx.coroutines.delay
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

// Phase 4 — on-device translation UI state for a single post. Hoisted at
// MainFeedScreen level (see translationStates there) rather than living inside
// PostContent, for the same reason subImageIndices is hoisted: PostContent is
// torn down and recreated every time AnimatedContent swaps to a different
// index, so any state that needs to survive navigating away and back has to
// live above it.
private enum class TranslationStatus { IDLE, TRANSLATING, DONE }
private data class TranslationState(
    val status: TranslationStatus = TranslationStatus.IDLE,
    val translatedText: String = "",
    val sourceLangLabel: String = "",
    val targetLangLabel: String = "",
    // Which target language this result is for — if the user changes their
    // preferred language in Settings, a cached DONE state for the old
    // language is stale and needs to be redone, not reused.
    val targetLangTag: String = "",
    val showingTranslated: Boolean = true
)

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
    liquidGlassIntensity: Float = 1f,
    onSetLiquidGlassIntensity: (Float) -> Unit = {},
    glassRimIntensity: Float = 1f,
    onSetGlassRimIntensity: (Float) -> Unit = {},
    // Item 8: Friends/Livestreams Hub sections.
    dmConversations: List<com.mediaviewer.model.DmConversation> = emptyList(),
    dmConversationsLoading: Boolean = false,
    friendsReviews: List<com.mediaviewer.model.FriendPopfeedReview> = emptyList(),
    friendsReviewsLoading: Boolean = false,
    onLoadFriendsReviews: () -> Unit = {},
    onOpenProfile: (com.mediaviewer.model.AuthorInfo) -> Unit = {},
    onOpenReview: (com.mediaviewer.model.FriendPopfeedReview) -> Unit = {},
    // Hub Blogs section (mirrors Reviews above).
    friendsBlogs: List<com.mediaviewer.model.FriendLeafletBlog> = emptyList(),
    onOpenBlog: (com.mediaviewer.model.FriendLeafletBlog) -> Unit = {},
    // Item (this session): Hub refresh bubble — re-checks Mutuals/Reviews/Blogs.
    onRefreshHub: () -> Unit = {},
    liveFriends: List<com.mediaviewer.model.StreamplaceLiveStream> = emptyList(),
    liveFriendsLoading: Boolean = false,
    onLoadLiveFriends: () -> Unit = {},
    blueskyLiveNow: List<com.mediaviewer.model.BlueskyLiveNowStream> = emptyList(),
    blueskyLiveNowLoading: Boolean = false,
    onLoadBlueskyLiveNow: () -> Unit = {},
    // Item (this session): generic over both Live sources now — see
    // MainViewModel.PlayingLiveStream.
    onOpenLivePlayer: (String, String, String) -> Unit = { _, _, _ -> },
    onEnsureFriends: () -> Unit = {},
    selfAvatarUrl: String? = null,
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
    onPostComment: (String, CommentItem?) -> Unit,
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
    // Pinch navigation: replaces the old unconditional "always jump to the
    // generic grid" behavior. The ViewModel decides whether a pinch-in
    // should instead resurrect a hidden profile (see pinchInFromPost()).
    onPinchIn: () -> Unit,
    // Item 1: pinching away to the grid or to a profile shouldn't leave a
    // video quietly playing behind it. Grid already handles this on its own
    // (AnimatedContent between screenState values tears the FEED branch's
    // VideoPlayer down entirely, releasing the ExoPlayer instance). A profile
    // is different — it's layered on top by MainActivity independently of
    // screenState (see ProfileOverlayState.hidden), so the pager underneath
    // stays fully composed and would otherwise keep playing right through it.
    externallyPaused: Boolean = false,
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
    onTapSentByAuthor: (AuthorInfo) -> Unit = {},
    friendsFeedLoadingOverlay: Boolean,
    onCurrentBackdropChanged: (GlassBackdrop?, Color) -> Unit = { _, _ -> },
    // Settings Update
    selfProfile: ProfileData? = null,
    hideTextOnlyPosts: Boolean = false,
    onToggleHideTextOnlyPosts: (Boolean) -> Unit = {},
    onOpenOwnProfile: () -> Unit = {},
    onShowSaves: () -> Unit = {},
    onShowHistory: () -> Unit = {},
    onOpenDmInbox: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    // Phase 4 — on-device translation
    translationEnabled: Boolean = false,
    translationTargetLang: String = "en",
    onToggleTranslation: (Boolean) -> Unit = {},
    onSelectTranslationLanguage: (String) -> Unit = {},
    // Phase 4 — custom font pack
    customFontName: String? = null,
    onPickFontFile: (android.net.Uri) -> Unit = {},
    onResetFont: () -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Item 3: which sub-image each multi-image post was left on, keyed by post id.
    // Lives here (above the per-post AnimatedContent) so it survives navigating
    // away to another post and back — a plain remember(item.id) inside PostContent
    // was getting torn down and reset to 0 every time.
    val subImageIndices = remember { mutableStateMapOf<String, Int>() }
    // Whether the text bubble is showing full text (true, the default/natural
    // size) or collapsed to one ellipsized line (false). This is a single global
    // flag, not per-post: swiping the bubble up/down on any post collapses or
    // expands the bubble on every post, so the user can keep them all compressed
    // or all fully out at once, instead of having to redo it post by post.
    var textExpanded by remember { mutableStateOf(true) }
    // Phase 4 — "3-finger pinch out hides UI, reverse shows it again": a single
    // global flag (same reasoning as textExpanded above) rather than per-post,
    // so the chrome stays hidden/shown consistently as the user swipes between
    // posts instead of resetting on every navigation.
    var uiHidden by remember { mutableStateOf(false) }
    // Phase 4 — on-device translation: cached per post-id (not per-composition,
    // same reasoning as subImageIndices above) so re-visiting an already-
    // translated post doesn't re-run the translator, and so the "showing
    // translated vs. original" toggle survives navigating away and back.
    val translationStates = remember { mutableStateMapOf<String, TranslationState>() }
    // Item 1 fix: Comments/Settings are rendered at this (MainFeedScreen) level,
    // not inside PostContent where dominantColor/backdrop are actually computed,
    // so we mirror the latest reported values here via onBackdropChanged below.
    var lastDominantColor by remember { mutableStateOf(NeutralGlassTint) }
    var lastBackdrop by remember { mutableStateOf<GlassBackdrop?>(null) }

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
                onSwipeRight      = onNavigatePrev,
                externallyPaused  = externallyPaused,
                navDirection      = navDirection
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
                        navDirection      = navDirection,
                        onSwipeLeft       = onNavigateNext,
                        onSwipeRight      = onNavigatePrev,
                        onSwipeUp         = { onSetScreen(ScreenState.COMMENTS) },
                        onSwipeDown       = { onSetScreen(ScreenState.SETTINGS) },
                        onPinchToGrid     = onPinchIn,
                        externallyPaused  = externallyPaused,
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
                        onTapSentByAuthor      = onTapSentByAuthor,
                        subImageIndices        = subImageIndices,
                        textExpanded           = textExpanded,
                        onToggleTextExpanded    = { textExpanded = !textExpanded },
                        uiHidden               = uiHidden,
                        onSetUiHidden           = { uiHidden = it },
                        translationEnabled      = translationEnabled,
                        translationTargetLang   = translationTargetLang,
                        translationStates       = translationStates,
                        onBackdropChanged      = { backdrop, color ->
                            lastDominantColor = color
                            lastBackdrop = backdrop
                            onCurrentBackdropChanged(backdrop, color)
                        }
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
                        onTagExclude    = onTagExclude,
                        dominantColor   = lastDominantColor,
                        backdrop        = lastBackdrop,
                        reducedAnimations = reducedAnimations
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
                        liquidGlassIntensity      = liquidGlassIntensity,
                        onSetLiquidGlassIntensity = onSetLiquidGlassIntensity,
                        glassRimIntensity         = glassRimIntensity,
                        onSetGlassRimIntensity    = onSetGlassRimIntensity,
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
                        onSwipeToFeed             = { onSetScreen(ScreenState.FEED) },
                        selfProfile               = selfProfile,
                        hideTextOnlyPosts         = hideTextOnlyPosts,
                        onToggleHideTextOnlyPosts = onToggleHideTextOnlyPosts,
                        onOpenOwnProfile          = onOpenOwnProfile,
                        onShowSaves               = { onShowSaves(); onSetScreen(ScreenState.FEED) },
                        onShowHistory             = { onShowHistory(); onSetScreen(ScreenState.FEED) },
                        onOpenDmInbox             = onOpenDmInbox,
                        onOpenSearch              = onOpenSearch,
                        translationEnabled          = translationEnabled,
                        translationTargetLang       = translationTargetLang,
                        onToggleTranslation         = onToggleTranslation,
                        onSelectTranslationLanguage = onSelectTranslationLanguage,
                        customFontName              = customFontName,
                        onPickFontFile              = onPickFontFile,
                        onResetFont                 = onResetFont,
                        dominantColor             = lastDominantColor,
                        backdrop                  = lastBackdrop,
                        dmConversations           = dmConversations,
                        dmConversationsLoading    = dmConversationsLoading,
                        friendsReviews            = friendsReviews,
                        friendsReviewsLoading     = friendsReviewsLoading,
                        onLoadFriendsReviews      = onLoadFriendsReviews,
                        onOpenReview              = onOpenReview,
                        onOpenProfile             = onOpenProfile,
                        friendsBlogs              = friendsBlogs,
                        onOpenBlog                = onOpenBlog,
                        onRefreshHub              = onRefreshHub,
                        liveFriends               = liveFriends,
                        liveFriendsLoading        = liveFriendsLoading,
                        onLoadLiveFriends         = onLoadLiveFriends,
                        blueskyLiveNow            = blueskyLiveNow,
                        blueskyLiveNowLoading     = blueskyLiveNowLoading,
                        onLoadBlueskyLiveNow      = onLoadBlueskyLiveNow,
                        onOpenLivePlayer          = onOpenLivePlayer,
                        onEnsureFriends           = onEnsureFriends,
                        selfAvatarUrl             = selfAvatarUrl
                    )
                    ScreenState.GRID -> GridScreen(
                        items           = mediaItems,
                        currentIndex    = currentIndex,
                        appMode         = appMode,
                        availableFeeds  = availableFeeds,
                        selectedFeedUri = selectedFeedUri,
                        authorFeedState = authorFeedState,
                        e621SearchTags  = e621SearchTags,
                        liquidGlass     = liquidGlass,
                        onItemClick     = { idx, subIdx ->
                            // Bug fix: seed the target sub-image index before
                            // navigating, so a tap on (say) the 2nd of 4 grid
                            // cells for a post opens directly to that image
                            // instead of always index 0. -1 (pinch-out back to
                            // whatever was already showing) leaves it alone.
                            if (subIdx >= 0) {
                                mediaItems.getOrNull(idx)?.let { subImageIndices[it.id] = subIdx }
                            }
                            onNavigateTo(idx)
                        },
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
    onSwipeRight: () -> Unit,
    externallyPaused: Boolean = false,
    // Item 16: 0 means "this index jump isn't a swipe" (e.g. tapping a post
    // from a profile grid) — skip the slide entirely so it feels seamless
    // instead of playing a left/right transition for an unrelated jump.
    navDirection: Int = 0
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isLoading && currentItem == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 1.5.dp)
        } else {
            AnimatedContent(
                targetState = currentIndex,
                transitionSpec = {
                    if (reducedAnimations || navDirection == 0) EnterTransition.None togetherWith ExitTransition.None
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
                        var lcPlayerRef by remember(item.id) { mutableStateOf<Player?>(null) }
                        var lcControlsVisible by remember(item.id) { mutableStateOf(false) }
                        var lcIsPlaying by remember(item.id) { mutableStateOf(true) }
                        var lcPositionMs by remember(item.id) { mutableStateOf(0L) }
                        var lcDurationMs by remember(item.id) { mutableStateOf(0L) }
                        var lcIsSeeking by remember(item.id) { mutableStateOf(false) }
                        var lcSeekPreviewMs by remember(item.id) { mutableStateOf(0L) }
                        // No live-backdrop plumbing in this simpler landscape view (it isn't
                        // part of any recorded backdrop layer, so there's also no risk of the
                        // self-referencing-layer crash the main pager has to work around),
                        // so the bar can live as a normal sibling here with backdrop = null —
                        // LiquidGlassSurface degrades gracefully to a flat tint in that case.
                        VideoPlayer(
                            item.videoPlaylistUrl, Modifier.fillMaxSize(),
                            controlsVisible = lcControlsVisible,
                            onToggleControls = { lcControlsVisible = !lcControlsVisible },
                            isBlocked = item.isBlocked,
                            thumbUrl = item.thumbUrl,
                            onPlayerReady = { lcPlayerRef = it },
                            onPlaybackState = { playing, pos, dur -> lcIsPlaying = playing; lcPositionMs = pos; lcDurationMs = dur },
                            onBoundsChanged = { _, _ -> },
                            externallyPaused = externallyPaused
                        )
                        AnimatedVisibility(
                            visible = lcControlsVisible && !item.isBlocked,
                            enter = fadeIn(FADE_ANIM), exit = fadeOut(FADE_ANIM),
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            VideoTransportButtons(
                                liquidGlass = true, dominantColor = NeutralGlassTint, backdrop = null,
                                isPlaying = lcIsPlaying,
                                onPlayPause = { lcPlayerRef?.let { p -> if (p.isPlaying) p.pause() else p.play() } },
                                onSkip = { delta ->
                                    lcPlayerRef?.let { p ->
                                        val target = (p.currentPosition + delta)
                                            .coerceIn(0L, if (lcDurationMs > 0) lcDurationMs else Long.MAX_VALUE)
                                        p.seekTo(target)
                                    }
                                }
                            )
                        }
                        AnimatedVisibility(
                            visible = lcControlsVisible && !item.isBlocked,
                            enter = fadeIn(FADE_ANIM), exit = fadeOut(FADE_ANIM),
                            modifier = Modifier.align(Alignment.BottomCenter)
                        ) {
                            VideoSeekBar(
                                liquidGlass = true, dominantColor = NeutralGlassTint, backdrop = null,
                                positionMs = if (lcIsSeeking) lcSeekPreviewMs else lcPositionMs,
                                durationMs = lcDurationMs,
                                onSeeking = { ms -> lcIsSeeking = true; lcSeekPreviewMs = ms },
                                onSeekFinish = { lcIsSeeking = false; lcPlayerRef?.seekTo(lcSeekPreviewMs) }
                            )
                        }
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
    // Item 16: see LandscapeMediaView's identical param — 0 skips the
    // slide/fade transition entirely for a profile-tab post jump.
    navDirection: Int = 0,
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
    onTapSentByAuthor: (AuthorInfo) -> Unit = {},
    subImageIndices: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>,
    textExpanded: Boolean,
    onToggleTextExpanded: () -> Unit,
    uiHidden: Boolean = false,
    onSetUiHidden: (Boolean) -> Unit = {},
    translationEnabled: Boolean = false,
    translationTargetLang: String = "en",
    translationStates: androidx.compose.runtime.snapshots.SnapshotStateMap<String, TranslationState> = remember { mutableStateMapOf() },
    onBackdropChanged: (GlassBackdrop?, Color) -> Unit = { _, _ -> },
    externallyPaused: Boolean = false
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
                    if (reducedAnimations || navDirection == 0) EnterTransition.None togetherWith ExitTransition.None
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
                    onTapSentByAuthor      = onTapSentByAuthor,
                    subImageIndex          = subImageIndices[item.id] ?: 0,
                    getSubImageIndex       = { subImageIndices[item.id] ?: 0 },
                    onSetSubImageIndex     = { subImageIndices[item.id] = it },
                    textExpanded            = textExpanded,
                    onToggleTextExpanded    = onToggleTextExpanded,
                    reducedAnimations      = reducedAnimations,
                    uiHidden               = uiHidden,
                    onSetUiHidden          = onSetUiHidden,
                    translationEnabled     = translationEnabled,
                    translationTargetLang  = translationTargetLang,
                    translationState       = translationStates[item.id],
                    onSetTranslationState  = { translationStates[item.id] = it },
                    onBackdropChanged      = onBackdropChanged,
                    externallyPaused       = externallyPaused
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
    onTapSentByAuthor: (AuthorInfo) -> Unit = {},
    subImageIndex: Int, getSubImageIndex: () -> Int, onSetSubImageIndex: (Int) -> Unit,
    textExpanded: Boolean, onToggleTextExpanded: () -> Unit,
    reducedAnimations: Boolean,
    uiHidden: Boolean = false,
    onSetUiHidden: (Boolean) -> Unit = {},
    translationEnabled: Boolean = false,
    translationTargetLang: String = "en",
    translationState: TranslationState? = null,
    onSetTranslationState: (TranslationState) -> Unit = {},
    onBackdropChanged: (GlassBackdrop?, Color) -> Unit = { _, _ -> },
    externallyPaused: Boolean = false
) {
    val context = LocalContext.current
    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(item.id) { scale = 1f; offset = Offset.Zero }

    var menuCenter    by remember { mutableStateOf<Offset?>(null) }
    var hoveredAction by remember { mutableStateOf<QuickAction?>(null) }

    // Video controls (Phase 3 "video player UI" item): this state is hoisted
    // up out of VideoPlayer because the glass controls bar needs to render
    // *outside* the recorded backdrop Box below (same reason QuickActionMenu
    // lives outside it, see the comment on that composable) — a LiquidGlassSurface
    // that reads `backdrop` while itself being drawn as part of what `backdrop`
    // is currently recording is a layer drawing itself mid-recording, which
    // Compose throws on. VideoPlayer keeps owning the actual ExoPlayer/AndroidView
    // (those pixels DO need to be inside the recorded box, since the glass
    // "reflection" should include whatever the video is showing) and just
    // reports state up through these.
    var videoPlayerRef       by remember(item.id) { mutableStateOf<Player?>(null) }
    var videoControlsVisible by remember(item.id) { mutableStateOf(false) }
    var videoIsPlaying       by remember(item.id) { mutableStateOf(true) }
    var videoPositionMs      by remember(item.id) { mutableStateOf(0L) }
    var videoDurationMs      by remember(item.id) { mutableStateOf(0L) }
    var videoIsSeeking       by remember(item.id) { mutableStateOf(false) }
    var videoSeekPreviewMs   by remember(item.id) { mutableStateOf(0L) }
    var videoBoundsOrigin    by remember(item.id) { mutableStateOf(Offset.Zero) }
    var videoBoundsSize      by remember(item.id) { mutableStateOf(IntSize.Zero) }
    // Root-relative origin of this PostContent's own outer Box — the reference
    // frame the video controls bar (and, if added later, anything else that
    // needs to sit outside the recorded box but aligned to something inside
    // it) converts root-space coordinates back into local placement offsets with.
    var postBoxRootOrigin    by remember { mutableStateOf(Offset.Zero) }

    // Press-and-hold post indicator → Next/Previous buttons: same press-hold-
    // drag-release pattern as the quick shortcuts. `indicatorPillOrigin`/`Size`
    // are the "1/N" pill's own root-relative bounds (tracked via
    // onGloballyPositioned below); `indicatorHoverSide` is -1 while the drag is
    // held over the left (Previous) side, +1 over the right (Next) side, 0
    // otherwise. The two buttons render as their own sibling below (same
    // outside-the-recorded-box reasoning as QuickActionMenu/video controls),
    // anchored to the pill's position rather than to a live press point, since
    // the pill itself doesn't move around.
    var indicatorPillOrigin  by remember(item.id) { mutableStateOf<Offset?>(null) }
    var indicatorPillSize    by remember(item.id) { mutableStateOf(IntSize.Zero) }
    var indicatorHoldActive  by remember(item.id) { mutableStateOf(false) }
    var indicatorHoverSide   by remember(item.id) { mutableStateOf(0) }

    // Big Update #1: sampled average color of the current post's media — feeds
    // the liquid-glass panels so their tint/"reflection" shifts with whatever
    // is on screen, per post.
    val glassBackdropUrl = item.thumbUrl.ifBlank { item.mediaUrl }
    val dominantColor = if (liquidGlass) rememberDominantColor(glassBackdropUrl) else Color.White

    // Big Update #4: a single shared layer this post re-records every frame
    // with its actual rendered pixels (background gradient + media + quick
    // action menu) — never a separate static picture — so every glass panel
    // on this post can sample a live, real-time backdrop of whatever is
    // really behind it, the same way real glass would.
    val backdropLayer = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    // Remembered (not rebuilt every recomposition) so its identity is stable — it's
    // reported upward via onBackdropChanged so overlays living outside the pager
    // (Share, Add To) can reflect this same post, and a stable identity keeps that
    // reporting from looping back into new recompositions of its own.
    val glassBackdrop = remember(liquidGlass, backdropLayer) {
        if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
    }
    // Big Update #10: report this post's live backdrop + dominant color upward so
    // the Share and Add To overlays — which live above the whole pager, not inside
    // it — can show the same real-time reflection the in-post glass panels do.
    SideEffect { onBackdropChanged(glassBackdrop, dominantColor) }

    // Phase 4 — on-device translation: fires when this post first appears with
    // translation on, and re-fires if the user flips the setting on for a post
    // already on screen, or changes their preferred target language. Skips
    // outright if there's already a cached result for this exact target
    // language (see translationState's targetLangTag) — including a cached
    // "nothing to translate" (Skipped/IDLE) result, so a post that's already
    // in the target language doesn't get re-checked every time it's revisited.
    LaunchedEffect(item.id, translationEnabled, translationTargetLang, item.text) {
        if (!translationEnabled || item.text.isBlank()) return@LaunchedEffect
        val cached = translationState
        if (cached != null && cached.targetLangTag == translationTargetLang) return@LaunchedEffect
        onSetTranslationState(TranslationState(status = TranslationStatus.TRANSLATING, targetLangTag = translationTargetLang))
        when (val outcome = com.mediaviewer.util.TranslationManager.translate(item.text, translationTargetLang)) {
            is com.mediaviewer.util.TranslationManager.Outcome.Success -> {
                onSetTranslationState(
                    TranslationState(
                        status = TranslationStatus.DONE,
                        translatedText = outcome.translatedText,
                        sourceLangLabel = outcome.sourceLanguageDisplayName,
                        targetLangLabel = com.mediaviewer.util.TranslationManager.displayNameFor(translationTargetLang),
                        targetLangTag = translationTargetLang,
                        showingTranslated = true
                    )
                )
                haptic(context)
            }
            is com.mediaviewer.util.TranslationManager.Outcome.Skipped,
            is com.mediaviewer.util.TranslationManager.Outcome.Failure ->
                onSetTranslationState(TranslationState(status = TranslationStatus.IDLE, targetLangTag = translationTargetLang))
        }
    }

    fun clampOffset(raw: Offset, s: Float): Offset {
        if (s <= 1.001f || containerSize == IntSize.Zero) return Offset.Zero
        val maxX = containerSize.width  * (s - 1f) / 2f
        val maxY = containerSize.height * (s - 1f) / 2f
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    // Bug fix: AuthorRow's text pill is a sibling of the box below (not a
    // descendant of it) and draws on top via its own zIndex — so a touch that
    // starts on the pill hit-tests exclusively to the pill and the outer
    // gesture below never sees it at all, regardless of what the pill does or
    // doesn't consume. The pill's own gesture only understood vertical drags
    // (expand/collapse), so a horizontal swipe that started there used to just
    // go nowhere — not blocked on purpose, just never handled by anything.
    // Both the outer gesture and AuthorRow now call this same resolver so a
    // swipe does the same thing (cycle sub-images, then fall through to next/
    // previous post) no matter where on the post it starts.
    val handleHorizontalSwipe: (Float) -> Unit = { totalDx ->
        val groupSize = item.mediaGroup.size
        // Read fresh here — this can fire from either gesture, potentially on
        // different recompositions, so a captured subImageIndex value would
        // go stale after the first swipe and cause stuck/skipping behavior.
        val curSubIdx = getSubImageIndex()
        if (totalDx < 0) {
            // swiping toward "next"
            if (groupSize > 1 && curSubIdx < groupSize - 1) onSetSubImageIndex(curSubIdx + 1)
            else onSwipeLeft()
        } else {
            // swiping toward "previous"
            if (groupSize > 1 && curSubIdx > 0) onSetSubImageIndex(curSubIdx - 1)
            else onSwipeRight()
        }
    }

    // Phase 4 — "Tapping the indicator or the translated text toggles original
    // <-> translated": only meaningful once a translation has actually finished
    // (no-op while still translating, or if translation was skipped/never ran).
    val onToggleTranslationView: () -> Unit = {
        val cur = translationState
        if (cur != null && cur.status == TranslationStatus.DONE) {
            onSetTranslationState(cur.copy(showingTranslated = !cur.showingTranslated))
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { postBoxRootOrigin = it.positionInRoot() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (liquidGlass) Modifier.background(postBackgroundBrush(dominantColor)) else Modifier)
                .onSizeChanged { containerSize = it }
                .then(if (liquidGlass) Modifier.onGloballyPositioned { backdropOrigin = it.positionInRoot() } else Modifier)
                .then(
                    if (liquidGlass) Modifier.drawWithContent {
                        // Re-record this frame's real pixels into the shared layer,
                        // then draw them to the actual screen as normal.
                        backdropLayer.record { this@drawWithContent.drawContent() }
                        drawContent()
                    } else Modifier
                )
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
                        // Interactions-while-zoomed item: double-tap now fires
                        // regardless of zoom level, same as unzoomed — it already
                        // decides purely from tap timing/proximity at the down
                        // event, before any drag could happen, so there's no new
                        // risk of misfiring mid-pan by dropping the scale check here.
                        if (downTime - lastTapMs < 280L && isNearLastTap) {
                            onDoubleTap(); down.consume(); lastTapMs = 0L; lastTapPos = null; return@awaitEachGesture
                        }
                        lastTapMs = downTime
                        lastTapPos = downPos

                        var dx = 0f; var dy = 0f
                        // Interactions-while-zoomed item: dx/dy above only
                        // accumulate in the unzoomed branch below (they're used
                        // for swipe-threshold detection, which stays disabled
                        // while zoomed — panning shouldn't also trigger a page
                        // swipe). stillDx/stillDy accumulate in BOTH branches
                        // instead, purely to answer "has this finger actually
                        // moved," so the long-press-for-quick-shortcuts gate
                        // below can work the same way whether zoomed or not,
                        // rather than being blanket-disabled by zoom level.
                        var stillDx = 0f; var stillDy = 0f
                        var menuOpen = false; var longPressFired = false
                        var prevPinchDist = -1f; var prevCentroid = downPos
                        var pointerCountEverTwo = false
                        var gridArmDist = -1f; var gridArmed = false
                        // Phase 4 — "3-finger pinch out hides UI, reverse shows it
                        // again": start3Dist is fixed at the first 3-finger reading
                        // for this gesture (unlike prevPinchDist above, which
                        // updates every frame for continuous 2-finger zoom) — this
                        // is a one-shot threshold crossing, not a continuous
                        // gesture, so it's compared against where the fingers
                        // started, not frame-to-frame.
                        var start3Dist = -1f
                        var ui3Fired = false
                        // Item 4 fix: once the 3-finger UI-hide gesture has
                        // engaged, it's possible for a finger to lift back down
                        // to 2 while the rest are still releasing — without this
                        // flag, that transient 2-finger reading could satisfy the
                        // pinch-to-grid distance check below using gridArmDist/
                        // prevPinchDist state left over from earlier in the same
                        // gesture, accidentally opening the grid right after
                        // hiding the UI. Once true, the 2-finger branch below is
                        // fully disabled for the rest of this gesture.
                        var threeFingerEngaged = false
                        // Item 6: once a sibling composable (e.g. the text
                        // bubble's own swipe-to-expand gesture) consumes this
                        // pointer, this outer gesture stops tracking it —
                        // no page swipe, no long-press menu — for the rest
                        // of this touch.
                        var externallyClaimed = false

                        while (true) {
                            val elapsed = System.currentTimeMillis() - downTime
                            if (!menuOpen && !longPressFired && !pointerCountEverTwo && !externallyClaimed &&
                                elapsed >= 450L && abs(stillDx) < 28f && abs(stillDy) < 28f) {
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
                                        abs(dx) > 80f && abs(dx) > abs(dy) * 1.2f -> handleHorizontalSwipe(dx)
                                        abs(dy) > 80f && abs(dy) > abs(dx) * 1.2f -> if (dy < 0) onSwipeUp() else onSwipeDown()
                                    }
                                }
                                if (scale <= 1.02f) { scale = 1f; offset = Offset.Zero }
                                break
                            }
                            if (pressed.size >= 3) {
                                // Phase 4 — 3-finger pinch to hide/show UI. Takes
                                // priority over the 2-finger pinch-to-grid/zoom
                                // handling below (a third finger touching down
                                // mid-2-finger-pinch just upgrades it to this
                                // instead). One-shot: fires at most once per
                                // gesture, comparing the current average spread
                                // against the spread when the third finger first
                                // touched down, not frame-to-frame.
                                pointerCountEverTwo = true; menuOpen = false; menuCenter = null; hoveredAction = null
                                prevPinchDist = -1f
                                threeFingerEngaged = true; gridArmed = false
                                val q1 = pressed[0].position; val q2 = pressed[1].position; val q3 = pressed[2].position
                                val centroid3 = Offset((q1.x + q2.x + q3.x) / 3f, (q1.y + q2.y + q3.y) / 3f)
                                val avgDist3 = ((q1 - centroid3).getDistance() + (q2 - centroid3).getDistance() + (q3 - centroid3).getDistance()) / 3f
                                if (start3Dist < 0f) {
                                    start3Dist = avgDist3
                                } else if (!ui3Fired) {
                                    val ratio = avgDist3 / start3Dist
                                    if (ratio > 1.35f) {
                                        ui3Fired = true; haptic(context); onSetUiHidden(true)
                                    } else if (ratio < 0.7f) {
                                        ui3Fired = true; haptic(context); onSetUiHidden(false)
                                    }
                                }
                                pressed.forEach { it.consume() }
                            } else if (pressed.size == 2) {
                                if (!threeFingerEngaged) {
                                    pointerCountEverTwo = true; menuOpen = false; menuCenter = null; hoveredAction = null
                                    val p1 = pressed[0].position; val p2 = pressed[1].position
                                    val dist = (p1 - p2).getDistance(); val centroid = (p1 + p2) / 2f
                                    if (gridArmDist < 0f) { gridArmDist = dist; gridArmed = scale <= 1.01f }
                                    if (prevPinchDist > 0f) {
                                        val rawNew = scale * dist / prevPinchDist
                                        if (gridArmed && dist < gridArmDist * 0.7f) {
                                            scale = 1f; offset = Offset.Zero; onPinchToGrid(); break
                                        }
                                        // Item 3 fix: a text-only post has no image/video to
                                        // magnify — letting scale/offset change anyway used to
                                        // leave it stuck zoomed in with no visible content to pan,
                                        // which permanently routed every single-finger drag into
                                        // the "pan while zoomed" branch further down instead of
                                        // the normal swipe-to-next-post handling, making the post
                                        // look frozen (couldn't scroll in any direction). Pinch-to-
                                        // grid detection above still works on text posts — only
                                        // the magnification itself is disabled.
                                        if (!item.isTextOnly) {
                                            val newScale = rawNew.coerceIn(1f, 8f)
                                            scale = newScale
                                            offset = clampOffset(if (newScale > 1.02f) offset + (centroid - prevCentroid) else Offset.Zero, newScale)
                                        }
                                    }
                                    prevPinchDist = dist; prevCentroid = centroid
                                }
                                pressed.forEach { it.consume() }
                            } else {
                                prevPinchDist = -1f
                                val ch = pressed[0]; val delta = ch.positionChange()
                                if (menuOpen) { hoveredAction = getHoveredAction(ch.position, menuCenter!!); ch.consume() }
                                else if (scale > 1.05f) {
                                    stillDx += delta.x; stillDy += delta.y
                                    offset = clampOffset(offset + delta, scale); ch.consume()
                                }
                                else if (ch.isConsumed) { externallyClaimed = true }
                                else {
                                    dx += delta.x; dy += delta.y
                                    stillDx += delta.x; stillDy += delta.y
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
                TextOnlyPostCard(item.text, dominantColor, translationState, onToggleTranslationView)
            } else if (item.isVideo && item.videoPlaylistUrl != null) {
                VideoPlayer(
                    item.videoPlaylistUrl, mediaModifier,
                    controlsVisible = videoControlsVisible,
                    onToggleControls = { videoControlsVisible = !videoControlsVisible },
                    isBlocked = item.isBlocked,
                    thumbUrl = item.thumbUrl,
                    onPlayerReady = { videoPlayerRef = it },
                    onPlaybackState = { playing, pos, dur -> videoIsPlaying = playing; videoPositionMs = pos; videoDurationMs = dur },
                    onBoundsChanged = { origin, size -> videoBoundsOrigin = origin; videoBoundsSize = size },
                    externallyPaused = externallyPaused
                )
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
        }

        // The long-press radial menu is a SIBLING of the recorded box above, not a
        // child of it. It used to be drawn inside that box, but that box re-records
        // its own drawn pixels into backdropLayer every frame (`backdropLayer.record
        // { drawContent() }`) — and the menu's own glass buttons draw FROM that same
        // backdropLayer (via LiquidGlassSurface's `drawLayer(backdrop.layer)`). Drawing
        // a GraphicsLayer while it's in the middle of being recorded is a layer
        // drawing itself, which Compose throws on — that was the long-press crash.
        // Living outside the recorded box (but still sized/positioned identically,
        // since both are plain fillMaxSize with no offset) keeps `menuCenter`'s
        // coordinates valid while no longer feeding back into its own source layer.
        val mc = menuCenter
        if (mc != null) QuickActionMenu(center = mc, hoveredAction = hoveredAction, appMode = appMode, item = item, liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = glassBackdrop)

        // Video controls bar: same crash-avoidance reasoning as QuickActionMenu
        // above — it reads the live `glassBackdrop`, so it has to live outside
        // the recorded box too (see the doc comment on VideoPlayer). Positioned
        // by converting the video's reported root-relative bounds back into this
        // Box's own local coordinate space via `postBoxRootOrigin`.
        if (item.isVideo && !item.isBlocked && videoControlsVisible && videoBoundsSize != IntSize.Zero) {
            val density = LocalDensity.current
            val localOrigin = videoBoundsOrigin - postBoxRootOrigin
            val vbx = with(density) { localOrigin.x.toDp() }
            val vby = with(density) { localOrigin.y.toDp() }
            val vbw = with(density) { videoBoundsSize.width.toDp() }
            val vbh = with(density) { videoBoundsSize.height.toDp() }
            Box(Modifier.offset(x = vbx, y = vby).size(width = vbw, height = vbh).zIndex(2.5f)) {
                AnimatedVisibility(
                    visible = videoControlsVisible,
                    enter = fadeIn(FADE_ANIM), exit = fadeOut(FADE_ANIM),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    VideoTransportButtons(
                        liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = glassBackdrop,
                        isPlaying = videoIsPlaying,
                        onPlayPause = { videoPlayerRef?.let { p -> if (p.isPlaying) p.pause() else p.play() } },
                        onSkip = { deltaMs ->
                            videoPlayerRef?.let { p ->
                                val target = (p.currentPosition + deltaMs)
                                    .coerceIn(0L, if (videoDurationMs > 0) videoDurationMs else Long.MAX_VALUE)
                                p.seekTo(target)
                            }
                        }
                    )
                }
                AnimatedVisibility(
                    visible = videoControlsVisible,
                    enter = fadeIn(FADE_ANIM), exit = fadeOut(FADE_ANIM),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    VideoSeekBar(
                        liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = glassBackdrop,
                        positionMs = if (videoIsSeeking) videoSeekPreviewMs else videoPositionMs,
                        durationMs = videoDurationMs,
                        onSeeking = { ms -> videoIsSeeking = true; videoSeekPreviewMs = ms },
                        onSeekFinish = { videoIsSeeking = false; videoPlayerRef?.seekTo(videoSeekPreviewMs) }
                    )
                }
            }
        }

        // Press-and-hold post indicator's Next/Previous buttons: rendered as a
        // sibling for the same reason as QuickActionMenu/the video controls bar
        // above — they're LiquidGlassSurface panels reading the live backdrop,
        // and would self-reference the recorded layer if drawn from inside it.
        // Unlike QuickActionMenu, these are anchored to the indicator pill's own
        // fixed position rather than a live press point (the pill doesn't move).
        val pillOrigin = indicatorPillOrigin
        if (indicatorHoldActive && pillOrigin != null) {
            PostIndicatorNavButtons(
                pillCenterRoot = pillOrigin + Offset(indicatorPillSize.width / 2f, indicatorPillSize.height / 2f),
                containerRootOrigin = postBoxRootOrigin,
                hoveredSide = indicatorHoverSide,
                liquidGlass = liquidGlass,
                dominantColor = dominantColor,
                backdrop = glassBackdrop
            )
        }

        // Author and action rows on top (zIndex ensures they're tappable over the media).
        // Phase 4 — "3-finger pinch out hides UI": wrapped in AnimatedVisibility so
        // it fades away/back rather than cutting instantly, matching the rest of
        // the app's animation conventions (skipped when reducedAnimations is on).
        AnimatedVisibility(
            visible = !uiHidden,
            enter = if (reducedAnimations) EnterTransition.None else fadeIn(FADE_ANIM),
            exit = if (reducedAnimations) ExitTransition.None else fadeOut(FADE_ANIM),
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).zIndex(2f)
        ) {
            Column(Modifier.fillMaxWidth()) {
                // Item 7: "Sent by" header shown above the regular post header for DM-shared posts
                item.sentByAuthor?.let { sender ->
                    SentByHeader(
                        sender = sender, message = item.sentByMessage,
                        expanded = sentByExpanded, onToggleExpanded = onToggleSentByExpanded,
                        onReply = onOpenReplyToSender,
                        onTapAuthor = onTapSentByAuthor,
                        leadingLabel = if (item.sentByIsRepost) null else "Sent by ",
                        verb = if (item.sentByIsRepost) " reposted:" else ":",
                        showReply = !item.sentByIsRepost,
                        modifier = Modifier.fillMaxWidth()
                            .background(Color.Black.copy(0.55f))
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                // Item 5: blocking hides the username/text bubble and follow
                // bubble along with the media blur/"Blocked" overlay — nothing
                // identifying or interactive should remain visible for a
                // blocked account. Unblocking (item.isBlocked flips back to
                // false) reveals it again automatically since this is driven
                // straight off that same state.
                if (!item.isBlocked) {
                    AuthorRow(item, appMode, onToggleFollow, onTapAuthor,
                        Modifier.fillMaxWidth()
                            .then(if (item.sentByAuthor == null) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier),
                        liquidGlass = liquidGlass,
                        dominantColor = dominantColor,
                        backdrop = glassBackdrop,
                        isTextOnly = item.isTextOnly,
                        textExpanded = textExpanded,
                        onToggleTextExpanded = onToggleTextExpanded,
                        reducedAnimations = reducedAnimations,
                        onHorizontalSwipe = handleHorizontalSwipe,
                        translationState = translationState,
                        onToggleTranslationView = onToggleTranslationView
                    )
                }
            }
        }

        // Four extra quick-shortcuts (item 3) now live as diagonal buttons in the
        // long-press radial menu above (see QuickActionMenu / getHoveredAction) —
        // they are Bluesky-only, matching the existing radial menu's action set.

        // Bottom cluster: the "1/4" style page indicator (only for multi-image
        // posts) and, per Phase 4, the translation status pill both sit here as
        // small liquid-glass pills. When both are present they're centered
        // together as one row — translation on the left, page indicator on the
        // right — rather than each independently centering itself.
        AnimatedVisibility(
            visible = !uiHidden,
            enter = if (reducedAnimations) EnterTransition.None else fadeIn(FADE_ANIM),
            exit = if (reducedAnimations) ExitTransition.None else fadeOut(FADE_ANIM),
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).zIndex(2f)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val showTranslateIndicator = translationEnabled && item.text.isNotBlank() &&
                    translationState != null && translationState.status != TranslationStatus.IDLE
                if (showTranslateIndicator || item.mediaGroup.size > 1) {
                    Row(
                        modifier = Modifier.padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showTranslateIndicator) {
                            TranslationIndicatorPill(
                                state = translationState!!, liquidGlass = liquidGlass,
                                dominantColor = dominantColor, backdrop = glassBackdrop,
                                onClick = onToggleTranslationView
                            )
                        }
                        if (item.mediaGroup.size > 1) {
                            val pageText = "${subImageIndex + 1}/${item.mediaGroup.size}"
                            val pageShape = RoundedCornerShape(14.dp)
                            Box(
                                modifier = Modifier
                                    .onGloballyPositioned { coords -> indicatorPillOrigin = coords.positionInRoot(); indicatorPillSize = coords.size }
                                    // Press-and-hold post indicator (Phase 3): same press-hold-
                                    // drag-release pattern the quick shortcuts use, just with two
                                    // options (left = Previous, right = Next) instead of eight.
                                    // This pill sits inside a multi-image post, where a horizontal
                                    // swipe on the media itself is already claimed for cycling
                                    // sub-images first — so this is the direct way to skip straight
                                    // to the next/previous post from here without swiping through
                                    // every sub-image first.
                                    .pointerInput(item.id) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val downPos = down.position
                                            var longPressFired = false
                                            var hoverSide = 0
                                            // Item 15: the Next/Previous arrows should appear the instant
                                            // a finger touches the indicator, not after a hold delay.
                                            longPressFired = true
                                            haptic(context)
                                            indicatorHoldActive = true
                                            while (true) {
                                                val result = withTimeoutOrNull(16L) { awaitPointerEvent(PointerEventPass.Main) }
                                                val event = result ?: continue
                                                val pressed = event.changes.filter { it.pressed }
                                                if (pressed.isEmpty()) {
                                                    if (longPressFired && hoverSide != 0) {
                                                        haptic(context)
                                                        // Left = Previous (onSwipeRight = onNavigatePrev),
                                                        // Right = Next (onSwipeLeft = onNavigateNext) — see
                                                        // the callback wiring in FeedView.
                                                        if (hoverSide < 0) onSwipeRight() else onSwipeLeft()
                                                    }
                                                    indicatorHoldActive = false
                                                    indicatorHoverSide = 0
                                                    break
                                                }
                                                if (longPressFired) {
                                                    val dx = pressed[0].position.x - downPos.x
                                                    val newSide = when { dx < -36f -> -1; dx > 36f -> 1; else -> 0 }
                                                    if (newSide != hoverSide) {
                                                        hoverSide = newSide; indicatorHoverSide = newSide
                                                        if (newSide != 0) haptic(context)
                                                    }
                                                    pressed[0].consume()
                                                }
                                            }
                                        }
                                    }
                            ) {
                                if (liquidGlass) {
                                    LiquidGlassSurface(shape = pageShape, tint = dominantColor, backdrop = glassBackdrop) {
                                        Text(
                                            pageText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                } else {
                                    Box(Modifier.clip(pageShape).background(Color.Black.copy(0.5f))) {
                                        Text(
                                            pageText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
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
                    backdrop = glassBackdrop
                )
            }
        }
    }
}

// ─── "Sent by" header (item 7) ────────────────────────────────────────────────

@Composable
private fun SentByHeader(
    sender: AuthorInfo, message: String, expanded: Boolean,
    onToggleExpanded: () -> Unit, onReply: () -> Unit, modifier: Modifier = Modifier,
    // Quote-repost attribution reuses this exact composable (see
    // sentByIsRepost on MediaItem) but reads "<name> reposted: ..." instead
    // of "Sent by <name>: ...", and has no Reply action since there's no DM
    // to reply to in that case.
    leadingLabel: String? = "Sent by ",
    verb: String = ":",
    showReply: Boolean = true,
    // Item 27: tapping the sender's avatar should open their profile instead
    // of just toggling the expanded/collapsed text.
    onTapAuthor: (AuthorInfo) -> Unit = {}
) {
    // Item 3: name + message are built as one flowing, wrapping Text instead of
    // two fixed rows, so the message starts right after the ":" and only spills
    // onto its own line once it's actually long enough to need it.
    val avatarContentId = "sentByAvatar"
    // Item 27: character offset the avatar placeholder sits at, so a tap can
    // be resolved against it separately from a tap anywhere else in the text.
    // Computed together with the annotated string (same remember keys) so it
    // stays in sync and survives recompositions where the string is cached.
    val (annotated, avatarCharIndex) = remember(sender, message, leadingLabel, verb) {
        var charIndex = -1
        val text = buildAnnotatedString {
            if (leadingLabel != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                    append(leadingLabel)
                }
            }
            if (sender.avatarUrl != null) {
                charIndex = length
                appendInlineContent(avatarContentId, "[avatar]")
                append(" ")
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                append(sender.displayName)
                append(verb)
            }
            if (message.isNotBlank()) {
                withStyle(SpanStyle(color = Color.White.copy(alpha = 0.9f))) {
                    append(" ")
                    append(message)
                }
            }
        }
        text to charIndex
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
        if (lr == null || !showReply) false
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
                    .pointerInput(avatarCharIndex, sender) {
                        detectTapGestures { pos ->
                            val lr = layoutResult
                            val hitAvatar = avatarCharIndex >= 0 && lr != null &&
                                lr.getOffsetForPosition(pos) in avatarCharIndex..(avatarCharIndex + 1)
                            if (hitAvatar) onTapAuthor(sender) else onToggleExpanded()
                        }
                    }
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
        if (!fitsInline && showReply) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End) {
                Text("Reply", color = VoteGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onReply))
            }
        }
    }
}

// ─── Quick Action Menu ────────────────────────────────────────────────────────

@Composable
private fun QuickActionMenu(center: Offset, hoveredAction: QuickAction?, appMode: AppMode, item: MediaItem, liquidGlass: Boolean, dominantColor: Color, backdrop: GlassBackdrop?) {
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
            // Item 4 fix: this button's exact root-relative resting position,
            // computed analytically instead of tracked through the animated
            // scale — see the staticOrigin doc comment on LiquidGlassSurface.
            val baseRootOrigin = backdrop?.originInRoot?.invoke() ?: Offset.Zero
            val buttonStaticOrigin = with(density) { baseRootOrigin + Offset(bx.toPx(), by.toPx()) }
            if (liquidGlass) {
                LiquidGlassSurface(
                    modifier = Modifier.offset(x = bx, y = by).scale(menuScale * btnScale).size(48.dp),
                    shape = CircleShape,
                    tint = if (isHovered) Color.White.copy(alpha = 0.6f) else dominantColor,
                    backdrop = backdrop,
                    staticOrigin = buttonStaticOrigin
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

/** Phase 3 "press-and-hold post indicator" item: two glass buttons — Previous
 *  (left) and Next (right) — that pop in beside the "1/N" sub-image pill on a
 *  long-press, following it purely from the pill's own fixed position (unlike
 *  [QuickActionMenu], which centers on wherever the press happened — this pill
 *  doesn't move, so there's no need to track a live press point for placement,
 *  only for which side is currently hovered). Uses the same
 *  [LiquidGlassSurface.staticOrigin] analytic-position trick QuickActionMenu's
 *  buttons use, for the same reason: the bouncy pop-in/hover scale is a
 *  draw-only transform that doesn't reliably re-fire `onGloballyPositioned`,
 *  so a tracked origin could go stale mid-bounce. */
@Composable
private fun PostIndicatorNavButtons(
    pillCenterRoot: Offset,
    containerRootOrigin: Offset,
    hoveredSide: Int,
    liquidGlass: Boolean,
    dominantColor: Color,
    backdrop: GlassBackdrop?
) {
    val density = LocalDensity.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val popScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "indicatorNavPop"
    )
    val buttonSize = 44.dp
    val sideDx = with(density) { 60.dp.toPx() }
    val halfPx = with(density) { (buttonSize / 2).toPx() }
    Box(Modifier.fillMaxSize().zIndex(3f)) {
        listOf(-1, 1).forEach { side ->
            val centerRoot = pillCenterRoot + Offset(sideDx * side, 0f)
            val topLeftRoot = Offset(centerRoot.x - halfPx, centerRoot.y - halfPx)
            val topLeftLocal = topLeftRoot - containerRootOrigin
            val bx = with(density) { topLeftLocal.x.toDp() }
            val by = with(density) { topLeftLocal.y.toDp() }
            val isHovered = hoveredSide == side
            val btnScale by animateFloatAsState(
                targetValue = if (isHovered) 1.3f else 1f,
                animationSpec = spring(Spring.DampingRatioMediumBouncy), label = "indicatorNavBtn"
            )
            val icon = if (side < 0) Icons.Default.ChevronLeft else Icons.Default.ChevronRight
            val label = if (side < 0) "Previous post" else "Next post"
            if (liquidGlass) {
                LiquidGlassSurface(
                    modifier = Modifier.offset(x = bx, y = by).scale(popScale * btnScale).size(buttonSize),
                    shape = CircleShape,
                    tint = if (isHovered) Color.White.copy(alpha = 0.6f) else dominantColor,
                    backdrop = backdrop,
                    staticOrigin = topLeftRoot
                ) {
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            } else {
                Box(
                    modifier = Modifier.offset(x = bx, y = by).scale(popScale * btnScale)
                        .size(buttonSize).clip(CircleShape).background(if (isHovered) Color.White.copy(0.25f) else Color(0xFF1C1C1C)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

// ─── Liquid Glass primitives (Big Update #1) ───────────────────────────────────
// rememberDominantColor / LiquidGlassSurface / UploadPlaceholderButton now live
// in GlassTheme.kt so Settings, Comments, and the quick-action menu can share
// them too.

// ─── Translation indicator (Phase 4) ───────────────────────────────────────────

/** Phase 4 — sits in the same bottom-cluster spot as the "1/N" image-count
 *  pill (see the bottom AnimatedVisibility block in PostContent), styled the
 *  same way. Shows a spinner + "Translating…" while in flight, then
 *  "Translated (source) to (target)" once done — permanently, not just a
 *  toast, per spec — and stays tappable afterward to flip the post's text
 *  between original and translated. */
@Composable
private fun TranslationIndicatorPill(
    state: TranslationState, liquidGlass: Boolean, dominantColor: Color, backdrop: GlassBackdrop?, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val label = when (state.status) {
        TranslationStatus.TRANSLATING -> "Translating…"
        TranslationStatus.DONE -> "Translated ${state.sourceLangLabel} to ${state.targetLangLabel}"
        TranslationStatus.IDLE -> ""
    }

    @Composable
    fun PillBody() {
        Row(
            modifier = Modifier
                .then(
                    if (state.status == TranslationStatus.DONE)
                        Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
                    else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (state.status == TranslationStatus.TRANSLATING) {
                CircularProgressIndicator(Modifier.size(11.dp), color = Color.White, strokeWidth = 1.5.dp)
            }
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    if (liquidGlass) {
        LiquidGlassSurface(shape = shape, tint = dominantColor, backdrop = backdrop) { PillBody() }
    } else {
        Box(Modifier.clip(shape).background(Color.Black.copy(0.5f))) { PillBody() }
    }
}

/** Big Update #3: text-only posts (no image/video) render as a centered,
 *  media-shaped liquid glass card holding just the post text.
 *  Phase 4: when [translationState] has a finished translation, the card shows
 *  either the original or translated text depending on [TranslationState.showingTranslated] —
 *  tapping the text (once a translation exists) calls [onToggleTranslationView]
 *  to flip between them, per the Phase 4 spec ("tapping ... the translated
 *  text toggles original <-> translated"). Note this Text is a *descendant* of
 *  PostContent's main gesture-owning Box (unlike AuthorRow's pill, which is a
 *  sibling — see the sibling zIndex hit-testing note near the top of this
 *  file), so its clickable here does consume taps on it; that's harmless
 *  since the outer gesture's own tap handling (double-tap-to-like, long-press)
 *  is resolved independently at pointer-down time, before any child gets a
 *  chance to consume anything. */
@Composable
private fun TextOnlyPostCard(
    text: String,
    dominantColor: Color,
    translationState: TranslationState? = null,
    onToggleTranslationView: () -> Unit = {}
) {
    val showTranslated = translationState?.status == TranslationStatus.DONE && translationState.showingTranslated
    val displayText = if (showTranslated) translationState!!.translatedText else text
    val toggleable = translationState?.status == TranslationStatus.DONE
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
                displayText.ifBlank { " " },
                color = Color.White, fontSize = 19.sp, lineHeight = 26.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis, maxLines = 14,
                modifier = if (toggleable) {
                    Modifier.clickable(
                        indication = null, interactionSource = remember { MutableInteractionSource() },
                        onClick = onToggleTranslationView
                    )
                } else Modifier
            )
        }
    }
}

// ─── Author Row ───────────────────────────────────────────────────────────────

@Composable
private fun AuthorRow(
    item: MediaItem, appMode: AppMode, onToggleFollow: () -> Unit, onTapAuthor: () -> Unit,
    modifier: Modifier, liquidGlass: Boolean, dominantColor: Color, backdrop: GlassBackdrop?,
    isTextOnly: Boolean,
    textExpanded: Boolean, onToggleTextExpanded: () -> Unit,
    reducedAnimations: Boolean = false,
    onHorizontalSwipe: (Float) -> Unit = {},
    // Phase 4 — "media posts' text bubbles" get the same translate-in-place
    // toggle as text-only posts' big card (see TextOnlyPostCard's doc comment).
    translationState: TranslationState? = null,
    onToggleTranslationView: () -> Unit = {}
) {
    val author = item.author
    // Item 7: text-only posts already show their full text as their own big
    // card in the middle of the screen — showing it a second time in this
    // pill is redundant, so the pill only shows it for posts that have media.
    val originalPostText = if (isTextOnly) "" else item.text
    val showTranslated = !isTextOnly && translationState?.status == TranslationStatus.DONE && translationState.showingTranslated
    val postText = if (showTranslated) translationState!!.translatedText else originalPostText
    val postTextToggleable = !isTextOnly && translationState?.status == TranslationStatus.DONE
    val pillShape = RoundedCornerShape(16.dp)

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
                    modifier = Modifier.padding(top = 4.dp).let {
                        // Phase 4: tapping the (already-translated) text toggles
                        // original <-> translated. This Text sits inside the
                        // pill's own manual pointerInput below, but that gesture
                        // only ever *claims* pointers on a clear drag (see
                        // `claimed` in that pointerInput) — a plain tap is never
                        // claimed, so it still reaches this clickable normally,
                        // the same way onTapAuthor's clickable above already does.
                        if (postTextToggleable) it.clickable(
                            indication = null, interactionSource = remember { MutableInteractionSource() },
                            onClick = onToggleTranslationView
                        ) else it
                    }
                )
            }
        }
    }

    Row(modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top) {

        // Item 6: the bubble opens/closes on a vertical swipe, not a tap — and
        // it claims (consumes) only clear vertical drags. But this pill is a
        // sibling of the box that owns the page's swipe gesture below, not a
        // descendant of it, and draws on top via its own zIndex — so a touch
        // that starts here hit-tests exclusively to the pill; the outer
        // gesture never sees it at all, consumed or not. A plain tap still
        // falls through naturally (the Row's own clickable above handles it),
        // but a horizontal drag has nothing else to fall through to — so it's
        // resolved right here, via the same shared function the outer gesture
        // uses, instead of silently doing nothing.
        val pillModifier = Modifier
            .weight(1f)
            // Item (Phase 3 "text bubble" item): the expand/collapse used to be
            // an instant cut (maxLines flipping with no size animation at all).
            // animateContentSize picks up that height change automatically.
            // Bug fix: this used to use a medium-bouncy spring, which read as
            // jittery/overshooting on this particular bubble — switched to a
            // plain tween (smooth ease, no overshoot) instead, or an instant
            // snap when reduced-motion is on.
            .animateContentSize(
                animationSpec = if (reducedAnimations) snap()
                else tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
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
                        if (totalY > 60f && !textExpanded) onToggleTextExpanded()
                        else if (totalY < -60f && textExpanded) onToggleTextExpanded()
                    } else if (abs(totalX) > 80f && abs(totalX) > abs(totalY) * 1.2f) {
                        // Same threshold the outer page gesture uses, so a
                        // swipe feels identical whether it starts on the pill
                        // or anywhere else on the post.
                        onHorizontalSwipe(totalX)
                    }
                }
            }

        if (liquidGlass) {
            LiquidGlassSurface(modifier = pillModifier, shape = pillShape, tint = dominantColor, backdrop = backdrop) { PillContent() }
        } else {
            Box(pillModifier.background(Color.Black.copy(alpha = 0.55f))) { PillContent() }
        }

        Spacer(Modifier.width(8.dp))

        // Follow button — its own separate rounded pill. Pinned to the TOP of
        // the row (not vertically centered) so it stays put at the top-right
        // even as the pill beside it grows taller to show post text.
        // Shared with the profile page's follow button (see FollowButton in
        // GlassTheme.kt) so both look and behave identically.
        FollowButton(
            isFollowing = author.isFollowing,
            liquidGlass = liquidGlass,
            tint = dominantColor,
            backdrop = backdrop,
            onClick = onToggleFollow,
            modifier = Modifier.align(Alignment.Top)
        )
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
    modifier: Modifier, liquidGlass: Boolean, dominantColor: Color, backdrop: GlassBackdrop?
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
                UploadPlaceholderButton(liquidGlass, dominantColor = dominantColor, backdrop = backdrop)
                Row(Modifier.weight(1f).fillMaxHeight(),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(Icons.Default.Send, Color.White, null, onShare)
                    ActionButton(Icons.Default.Download, if (item.isDownloaded) BookmarkYellow else Color.White, null, onDownload)
                    GifActionButton(onDownloadGif, if (item.isGifDownloaded) BookmarkYellow else Color.White)
                }
                ActionButton(Icons.Default.Block, if (item.isBlocked) Color(0xFFE0245E) else Color.White, null, onBlockAccount)
            }
        } else {
            // Item 15: matches the AT Protocol bar's layout language — no raw score
            // numbers cluttering the row, everything evenly spaced across the full
            // width instead of packed/scrolling on the left with dead space on the right.
            // Item 10: e621 mode has no upload action, so the bar is just the
            // four remaining buttons spread evenly across the full width.
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                ActionButton(Icons.Default.ArrowUpward, if (item.e621UserVote == 1) VoteGreen else Color.White, null) { onE621Vote(1) }
                ActionButton(Icons.Default.ArrowDownward, if (item.e621UserVote == -1) VoteRed else Color.White, null) { onE621Vote(-1) }
                ActionButton(if (item.isBookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    if (item.isBookmarked) BookmarkYellow else Color.White, null, onToggleBookmark)
                ActionButton(Icons.Default.Download, if (item.isDownloaded) BookmarkYellow else Color.White, null, onDownload)
                GifActionButton(onDownloadGif, if (item.isGifDownloaded) BookmarkYellow else Color.White)
            }
        }
    }

    val content: @Composable () -> Unit = { RowContent() }

    if (liquidGlass) {
        val shape = RoundedCornerShape(26.dp)
        LiquidGlassSurface(
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            shape = shape, tint = dominantColor, backdrop = backdrop
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

/** Phase 3 "Video player UI" item: the transport UI is drawn by Compose, not
 *  PlayerView's native Android controller. `useController = true` used to make
 *  PlayerView flash its own controls briefly the moment a video became visible
 *  — the exact "auto-shown on scroll" behavior the spec asked to avoid — and
 *  those native controls also couldn't pick up the app's glass theme.
 *  `useController` is now always false, and a single tap toggles the bar.
 *
 *  Bug fix: the transport bar itself is NOT rendered by this composable
 *  anymore. It used to be, using [LiquidGlassSurface] with the post's live
 *  `backdrop` — but in the main pager this composable is called *inside* the
 *  same Box that's re-recorded every frame into that exact backdrop layer
 *  (see `backdropLayer.record { ... }` in `PostContent`), so drawing a glass
 *  panel that reads that layer from in here means the layer is being asked to
 *  draw itself in the middle of its own recording. Compose throws on that —
 *  it's the same class of bug the "long-press crash" (QuickActionMenu) hit
 *  and was fixed by moving that composable outside the recorded box; the
 *  video controls bar needed the same treatment. This composable now just
 *  owns the ExoPlayer/AndroidView and reports state up via callbacks so the
 *  caller can render [VideoTransportButtons]/[VideoSeekBar] from a sibling location outside the
 *  recorded box (see the `videoControlsVisible` etc. state + the sibling
 *  render block in `PostContent`, and the equivalent one in the landscape
 *  view, which never had this problem since it isn't part of any recorded
 *  layer to begin with). */
@Composable
private fun VideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    isBlocked: Boolean = false,
    thumbUrl: String = "",
    onPlayerReady: (Player) -> Unit = {},
    onPlaybackState: (isPlaying: Boolean, positionMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
    onBoundsChanged: (originInRoot: Offset, size: IntSize) -> Unit = { _, _ -> },
    externallyPaused: Boolean = false
) {
    val context = LocalContext.current
    val player  = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE; volume = 1f } }
    // Item 5: the transport controls are drawn over whatever bounds PlayerView
    // is given — so instead of stretching PlayerView across the whole screen
    // (which spreads controls across empty letterboxed space and lets them
    // overlap the rest of the UI), size it to the video's own aspect ratio and
    // center it, the same way the image posts are fit.
    var aspectRatio by remember(url) { mutableStateOf(1f) }
    // Only reset via `d > 0` writes below — keeps the last known duration
    // visible instead of flickering back to 0 between polls/readiness checks.
    var lastKnownDuration by remember(url) { mutableStateOf(0L) }
    // Phase 3 "thumbnail instead of black screen" item: images already had a
    // thumb-then-full crossfade (see the sub-image branch above); video never
    // had an equivalent and just showed nothing until the first frame decoded.
    // These two drive a poster-image layer (fades out once real video pixels
    // are on screen) and a small buffering spinner over it.
    var firstFrameRendered by remember(url) { mutableStateOf(false) }
    var isBuffering by remember(url) { mutableStateOf(true) }

    LaunchedEffect(player) { onPlayerReady(player) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                val d = player.duration
                if (d > 0) lastKnownDuration = d
                onPlaybackState(playing, player.currentPosition.coerceAtLeast(0L), lastKnownDuration)
            }
            override fun onRenderedFirstFrame() { firstFrameRendered = true }
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(url) { player.setMediaItem(ExoMediaItem.fromUri(url)); player.prepare(); if (!isBlocked) player.play() }
    // Blocking a post mid-playback (or scrolling to one that's already blocked)
    // should pause it immediately rather than letting it keep playing silently
    // behind the "Blocked"/blur overlay.
    LaunchedEffect(isBlocked) { if (isBlocked) player.pause() }
    // Item 25: same idea, for whenever something else (the grid, a profile)
    // covers this video instead of the block overlay. Pinching in pauses it;
    // pinching back out should resume it too (unless it's blocked, which
    // stays paused regardless).
    LaunchedEffect(externallyPaused) {
        if (externallyPaused) player.pause() else if (!isBlocked) player.play()
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Only poll playback position while the bar is actually visible — no
    // sense burning a coroutine tick for a seek bar nobody can see.
    LaunchedEffect(controlsVisible, player) {
        while (controlsVisible) {
            val d = player.duration
            if (d > 0) lastKnownDuration = d
            onPlaybackState(player.isPlaying, player.currentPosition.coerceAtLeast(0L), lastKnownDuration)
            delay(200L)
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(aspectRatio)
                .onGloballyPositioned { coords -> onBoundsChanged(coords.positionInRoot(), coords.size) }
                // Item (video controls): a tap toggles the transport bar. This
                // sibling pointerInput consumes its own down/up, same pattern
                // the text bubble's swipe-to-expand gesture already uses (see
                // the `externallyClaimed`/`ch.isConsumed` handling in the
                // outer post gesture loop above) — so a tap here doesn't also
                // register as a page-swipe attempt on the surrounding post.
                // Skipped entirely when blocked, so a blocked video's player
                // UI can never be summoned in the first place.
                .pointerInput(url, isBlocked) {
                    if (isBlocked) return@pointerInput
                    detectTapGestures(onTap = { onToggleControls() })
                }
        ) {
            if (thumbUrl.isNotBlank()) {
                val posterAlpha by animateFloatAsState(
                    targetValue = if (firstFrameRendered) 0f else 1f,
                    animationSpec = tween(250), label = "posterFade"
                )
                AsyncImage(
                    model = thumbUrl, contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = posterAlpha }
                )
            }
            AndroidView(
                factory = { ctx ->
                    // Item 4: inflated from XML (see res/layout/player_view_texture.xml)
                    // because PlayerView's surface type can only be set via XML/AttributeSet
                    // at construction — there's no runtime setter for it — and it must be
                    // TextureView (not the default SurfaceView) for the video to actually
                    // show up in the live backdrop-blur capture.
                    (android.view.LayoutInflater.from(ctx).inflate(com.mediaviewer.R.layout.player_view_texture, null) as PlayerView).apply {
                        this.player = player; useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                modifier = Modifier.matchParentSize()
            )
            if (isBuffering && !isBlocked) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp).align(Alignment.Center),
                    color = Color.White, strokeWidth = 2.5.dp
                )
            }
        }
    }
}

/** The glass seek bar — sits at the bottom of the video's own bounds, per the
 *  "within post shape" spec. Split out from the transport buttons (below)
 *  since those now render as their own bigger individual glass bubbles in
 *  the middle rather than sharing one bottom bar. */
@Composable
private fun VideoSeekBar(
    liquidGlass: Boolean,
    dominantColor: Color,
    backdrop: GlassBackdrop?,
    positionMs: Long,
    durationMs: Long,
    onSeeking: (Long) -> Unit,
    onSeekFinish: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val barContent: @Composable BoxScope.() -> Unit = {
        Slider(
            value = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
            onValueChange = { frac -> onSeeking((frac * durationMs).toLong()) },
            onValueChangeFinished = onSeekFinish,
            enabled = durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.9f),
                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            ),
            modifier = Modifier.fillMaxWidth().height(20.dp).padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
    if (liquidGlass) {
        LiquidGlassSurface(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            shape = shape, tint = dominantColor, backdrop = backdrop,
            content = barContent
        )
    } else {
        Box(
            Modifier.fillMaxWidth().padding(10.dp).clip(shape).background(Color.Black.copy(alpha = 0.55f)),
            content = barContent
        )
    }
}

/** Play/pause + ±10s skip as three separate, bigger glass "bubble" buttons in
 *  the middle of the video, instead of sharing one bar with the seek bar. */
@Composable
private fun VideoTransportButtons(
    liquidGlass: Boolean,
    dominantColor: Color,
    backdrop: GlassBackdrop?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSkip: (Long) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        TransportBubble(
            icon = Icons.Default.Replay10, contentDescription = "Back 10 seconds",
            size = 56.dp, iconSize = 30.dp,
            liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = backdrop,
            onClick = { onSkip(-10_000L) }
        )
        TransportBubble(
            icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            size = 78.dp, iconSize = 40.dp,
            liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = backdrop,
            onClick = onPlayPause
        )
        TransportBubble(
            icon = Icons.Default.Forward10, contentDescription = "Forward 10 seconds",
            size = 56.dp, iconSize = 30.dp,
            liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = backdrop,
            onClick = { onSkip(10_000L) }
        )
    }
}

/** A single round glass transport button. Plain clickable circle rather than
 *  Material's IconButton — same Item-12-style fix reused throughout this
 *  file, since IconButton draws a bounded ripple that shows as a flat black
 *  square over clear glass. */
@Composable
private fun TransportBubble(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    liquidGlass: Boolean,
    dominantColor: Color,
    backdrop: GlassBackdrop?,
    onClick: () -> Unit
) {
    val shape = CircleShape
    if (liquidGlass) {
        LiquidGlassSurface(
            modifier = Modifier.size(size).clip(shape).clickable(onClick = onClick),
            shape = shape, tint = dominantColor, backdrop = backdrop
        ) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
            }
        }
    } else {
        Box(
            Modifier.size(size).clip(shape).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
        }
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
