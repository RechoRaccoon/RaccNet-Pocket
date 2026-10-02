package com.mediaviewer.ui

import com.mediaviewer.ui.compat.navBarSpace

import com.mediaviewer.ui.compat.coilContext

import coil3.request.crossfade

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.ui.compat.Build
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import com.mediaviewer.ui.compat.LocalContext
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
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mediaviewer.model.*
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.*

private val SWIPE_ANIM = tween<IntOffset>(200, easing = FastOutSlowInEasing)

// Item 11 follow-up: threshold used to decide whether the video transport
// controls (bar + buttons) should be visible/interactive at all, and
// whether a tap on the video pauses/plays instead of toggling them. Kept
// just barely above `1f` rather than exactly `1f` purely to absorb
// floating-point noise in the pinch math — in practice this behaves as "any
// zoom at all" hides the UI, not a real deadzone the way the old 1.05f
// threshold was.
private const val VIDEO_UI_ZOOM_EPSILON = 1.001f
private val FADE_ANIM  = tween<Float>(150)

private enum class QuickAction { TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, TOP_LEFT }

private fun getHoveredAction(pos: Offset, center: Offset): QuickAction? {
    val dx = pos.x - center.x; val dy = pos.y - center.y
    if (sqrt(dx * dx + dy * dy) < 40f) return null
    val angle = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / kotlin.math.PI) // -180..180, 0=right, 90=down
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
    // Feature request #8: hoisted up to MainActivity so a tap from
    // ProfileOverlay's grid can pre-seed which sub-image a multi-image
    // post's pager should open on (see MainActivity's call site). Nullable
    // with an internal fallback so every other existing call site (previews,
    // tests, etc.) keeps working unchanged.
    subImageIndices: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>? = null,
    mediaItems: List<MediaItem>,
    currentIndex: Int,
    currentItem: MediaItem?,
    screenState: ScreenState,
    // Item 12: true for exactly the one SETTINGS -> FEED switch triggered by
    // picking a feed from the Feeds row (already covered by the pixel
    // curtain), false for every other transition including the explicit
    // "Return to Feed" button — which should keep its normal slide.
    skipFeedEntryAnim: Boolean = false,
    hasVisitedFeed: Boolean,
    appMode: AppMode,
    navDirection: Int,
    reducedAnimations: Boolean,
    classicProfileTabRow: Boolean = false,
    onToggleClassicProfileTabRow: (Boolean) -> Unit = {},
    squareGridRounded: Boolean = false,
    onToggleSquareGridRounded: (Boolean) -> Unit = {},
    // Feature: auto-subscribe — used by the Hub's Reviews/Blogs rows to
    // exclude the signed-in user's own reviews/blogs from that preview row
    // (their own account is auto-subscribed too, but only so their reviews
    // show up on the matching title's page alongside everyone else's, not
    // so they see their own name in their own "friends" row).
    selfDid: String = "",
    subscribedReviewDids: Set<String> = emptySet(),
    subscribedBlogDids: Set<String> = emptySet(),
    followerScanState: MainViewModel.FollowerScanState = MainViewModel.FollowerScanState.Idle,
    followerScanCompletedOnce: Boolean = false,
    onStartFollowerScan: () -> Unit = {},
    onRescanFollowersFromScratch: () -> Unit = {},
    onDismissFollowerScanResult: () -> Unit = {},
    liquidGlass: Boolean,
    onToggleLiquidGlass: (Boolean) -> Unit,
    liquidGlassIntensity: Float = 1f,
    onSetLiquidGlassIntensity: (Float) -> Unit = {},
    glassRimIntensity: Float = 1f,
    onSetGlassRimIntensity: (Float) -> Unit = {},
    glassRimVibrantSecondary: Boolean = true,
    onToggleGlassRimVibrantSecondary: (Boolean) -> Unit = {},
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
    // Live Link widget feature
    liveLinkState: com.mediaviewer.model.LiveLinkState = com.mediaviewer.model.LiveLinkState(),
    onSaveLiveTwitchUrl: (String) -> Unit = {},
    onSaveLiveYoutubeUrl: (String) -> Unit = {},
    onCreateLiveLinkWidget: () -> Unit = {},
    onToggleLiveLink: (com.mediaviewer.model.LiveNowPlatform) -> Unit = {},
    onEndLiveLink: () -> Unit = {},
    onMoveFeed: (Int, Int) -> Unit = { _, _ -> },
    onRemoveFeed: (String) -> Unit = {},
    /** Explore mode pinched in (back to the profile a feed was opened from). */
    onGridPinchIn: () -> Unit = {},
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
    /** Hub Timeline/Explore after picking a feed: open it in the timeline
     *  (explore = false) or Explore mode (true). */
    onOpenFeed: (uri: String?, explore: Boolean) -> Unit = { uri, _ -> onSelectFeed(uri) },
    onToggleDownloadOnLike: (Boolean) -> Unit,
    onDownloadAllLiked: () -> Unit,
    onCancelDownload: () -> Unit,
    // AI Tagging feature
    tagPostWhenLiked: Boolean = false,
    onToggleTagPostWhenLiked: (Boolean) -> Unit = {},
    taggingRunning: Boolean = false,
    taggingScanned: Int = 0,
    taggingTagged: Int = 0,
    onLocallyTagAllLiked: () -> Unit = {},
    onDeleteTaggedDatabase: () -> Unit = {},
    settingsExtras: SettingsExtras = SettingsExtras(),
    importedDatasets: List<com.mediaviewer.tagging.TagDatasetInfo> = emptyList(),
    onExportDataset: (String, com.mediaviewer.platform.PlatformUri) -> Unit = { _, _ -> },
    onImportDataset: (com.mediaviewer.platform.PlatformUri) -> Unit = {},
    onDeleteImportedDataset: (String) -> Unit = {},
    onShowLikes: () -> Unit,
    onShowFriends: () -> Unit,
    onShowE621Following: () -> Unit,
    /** The Hub's Return to Feed — grid or timeline, whichever was last used. */
    onReturnToFeed: () -> Unit = { onSetScreen(ScreenState.FEED) },
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
    // Item 4: "More" menu actions on the interaction bar.
    onReportPost: () -> Unit = {},
    onShowMoreLikeThis: () -> Unit = {},
    onShowLessLikeThis: () -> Unit = {},
    onAddAccountToList: () -> Unit = {},
    // Own posts only: "Delete" at the top of the More menu.
    onDeletePost: () -> Unit = {},
    // Item 9: only a real feed-generator-backed feed can act on the
    // "Show more/less like this" signal — gates whether those two menu
    // items appear at all.
    supportsFeedInteractions: Boolean = false,
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
    onOpenInbox: () -> Unit = {},
    inboxUnreadCount: Int = 0,
    dmUnreadCount: Int = 0,
    onOpenComposePost: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    // Phase 4 — on-device translation
    translationEnabled: Boolean = false,
    translationTargetLang: String = "en",
    onToggleTranslation: (Boolean) -> Unit = {},
    onSelectTranslationLanguage: (String) -> Unit = {},
    // Phase 4 — custom font pack
    customFontName: String? = null,
    onPickFontFile: (com.mediaviewer.platform.PlatformUri) -> Unit = {},
    onResetFont: () -> Unit = {},
    // Feature request #8: "I hate fun" — the boolean is used directly by
    // FeedView/PostContent below (the actual blur); onToggleHateFunBlurNsfw
    // is only needed to forward into this screen's own internal Settings
    // sheet (ScreenState.SETTINGS branch below), same as
    // onToggleClassicProfileTabRow already is.
    hateFunBlurNsfw: Boolean = false,
    onToggleHateFunBlurNsfw: (Boolean) -> Unit = {},
    // Feature request #7: only used by the Settings sheet below (the
    // Pinterest-grid column count is read directly by ProfileOverlay, which
    // MainActivity provides separately — this screen has no Pinterest grid
    // of its own to apply it to).
    pinterestThreeColumns: Boolean = false,
    onTogglePinterestThreeColumns: (Boolean) -> Unit = {},
    // Share To / Quote Repost / Add To are open over the post: the post's
    // own UI fades away while they're up (and back when they close).
    popupOpen: Boolean = false,
    // Tag Media When Liked's queue, shown as a status bubble under the
    // author row (Settings → Show Tagging Status).
    likeTagPhase: MainViewModel.LikeTagPhase = MainViewModel.LikeTagPhase.IDLE,
    likeTagPending: Int = 0,
    /** The More menu opened on a post: look up the author's list
     *  memberships early so Add To opens with its + / − ready. */
    onPrefetchListMemberships: (String) -> Unit = {},
    /** Hub → "Return to Profile" (the feed selector's picked profile). */
    onReturnToProfile: () -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = com.mediaviewer.ui.compat.rememberScreenSizeDp()
    val taggingStatusLabel: String? =
        if (!tagPostWhenLiked || !com.mediaviewer.util.UiToggles.showTaggingStatus) null
        else when (likeTagPhase) {
            MainViewModel.LikeTagPhase.ACTIVATING -> "Activating tagger…"
            MainViewModel.LikeTagPhase.TAGGING -> "Tagging $likeTagPending post" + if (likeTagPending == 1) "" else "s"
            MainViewModel.LikeTagPhase.IDLE -> null
        }
    val isLandscape = configuration.width > configuration.height
    // Item 3: which sub-image each multi-image post was left on, keyed by post id.
    // Lives here (above the per-post AnimatedContent) so it survives navigating
    // away to another post and back — a plain remember(item.id) inside PostContent
    // was getting torn down and reset to 0 every time.
    //
    // Feature request #8: now optionally supplied by the caller (see the
    // `subImageIndices` parameter) so ProfileOverlay's Pinterest/All grid can
    // pre-seed a post's starting sub-image before handing off to this screen
    // to show it — falls back to an internally-owned map when no caller
    // needs to seed it, same as before.
    val subImageIndices = subImageIndices ?: remember { mutableStateMapOf<String, Int>() }
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
    // Landscape only: the round fullscreen button's own "UI hidden" (it
    // never changes portrait). Landscape starts from portrait's uiHidden.
    var landscapeFullscreen by remember { mutableStateOf(false) }
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
    // Comments slide up OVER the post (which stays put, blurred, its UI
    // faded) rather than replacing it. commentsOpenAnim drives the blur/fade;
    // the sheet's own drag-to-close eases it back off as it's pulled down.
    val commentsOpen = screenState == ScreenState.COMMENTS
    val commentsOpenAnim by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (commentsOpen) 1f else 0f,
        animationSpec = if (reducedAnimations) androidx.compose.animation.core.snap() else tween(320, easing = FastOutSlowInEasing),
        label = "commentsOpen"
    )
    var commentsDragFraction by remember { mutableFloatStateOf(0f) }
    val commentsFraction = (commentsOpenAnim * (1f - commentsDragFraction)).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize().background(OledBlack)) {
        // Sideways, the timeline is just the normal page rotated (the old
        // media-only landscape view is no longer used).
        AnimatedContent(
            targetState = screenState,
            // FEED and COMMENTS are the same page now — the comments
            // sheet is an overlay (below), so switching between them
            // must not tear the post down or animate it.
            contentKey = { if (it == ScreenState.COMMENTS) ScreenState.FEED else it },
            transitionSpec = {
                if (reducedAnimations) EnterTransition.None togetherWith ExitTransition.None
                else when {
                    targetState == ScreenState.SETTINGS ->
                        slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it } togetherWith
                        slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it }
                    // Item 12: picking a feed from the Feeds row switches
                    // SETTINGS -> FEED while the pixel curtain already
                    // fully covers the screen, so the normal slide here
                    // would just be silent wasted motion (or worse,
                    // bleed through the wipe) — skip it for just this
                    // one switch. The explicit "Return to Feed" button
                    // never sets this flag, so it keeps the slide below.
                    initialState == ScreenState.SETTINGS && (targetState == ScreenState.FEED || targetState == ScreenState.GRID) && skipFeedEntryAnim ->
                        EnterTransition.None togetherWith ExitTransition.None
                    initialState == ScreenState.SETTINGS ->
                        slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it } togetherWith
                        slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { -it }
                    else -> fadeIn(FADE_ANIM) togetherWith fadeOut(FADE_ANIM)
                }
            },
            label = "screen"
        ) { state ->
            when (state) {
                ScreenState.FEED, ScreenState.COMMENTS -> FeedView(
                    commentsFraction  = commentsFraction,
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
                    onReportPost      = onReportPost,
                    onDownloadGif     = onDownloadGif,
                    onShowMoreLikeThis = onShowMoreLikeThis,
                    onShowLessLikeThis = onShowLessLikeThis,
                    onAddAccountToList = onAddAccountToList,
                    onDeletePost      = onDeletePost,
                    selfDid           = selfDid,
                    supportsFeedInteractions = supportsFeedInteractions,
                    sentByExpanded         = sentByExpanded,
                    onToggleSentByExpanded = onToggleSentByExpanded,
                    onOpenReplyToSender    = onOpenReplyToSender,
                    onTapSentByAuthor      = onTapSentByAuthor,
                    subImageIndices        = subImageIndices,
                    textExpanded           = textExpanded,
                    onToggleTextExpanded    = { textExpanded = !textExpanded },
                    uiHidden               = uiHidden || (isLandscape && landscapeFullscreen),
                    onSetUiHidden           = { hidden -> if (!hidden) landscapeFullscreen = false; uiHidden = hidden },
                    landscape              = isLandscape,
                    onLandscapeFullscreen  = { landscapeFullscreen = true },
                    onRevealUi             = { landscapeFullscreen = false; uiHidden = false },
                    translationEnabled      = translationEnabled,
                    translationTargetLang   = translationTargetLang,
                    translationStates       = translationStates,
                    popupOpen               = popupOpen,
                    taggingStatusLabel      = taggingStatusLabel,
                    onPrefetchListMemberships = onPrefetchListMemberships,
                    onBackdropChanged      = { backdrop, color ->
                        lastDominantColor = color
                        lastBackdrop = backdrop
                        onCurrentBackdropChanged(backdrop, color)
                    },
                    hateFunBlurNsfw        = hateFunBlurNsfw
                )
                ScreenState.SETTINGS -> SettingsSheet(
                    appMode                   = appMode,
                    hasVisitedFeed            = hasVisitedFeed,
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
                    glassRimVibrantSecondary  = glassRimVibrantSecondary,
                    onToggleGlassRimVibrantSecondary = onToggleGlassRimVibrantSecondary,
                    e621SearchTags            = e621SearchTags,
                    isLoading                 = isLoading,
                    onLoginBluesky            = onLoginBluesky,
                    onLogoutBluesky           = onLogoutBluesky,
                    onSaveE621Credentials     = onSaveE621Credentials,
                    onLogoutE621              = onLogoutE621,
                    // Bug fix (item 4 — feed opened before it had
                    // loaded): this used to call onSetScreen(FEED)
                    // immediately, scrolling into the feed pager before
                    // any of its data existed. onSelectFeed (wired from
                    // AppRoot as handleSelectFeed) now owns the whole
                    // sequence itself — start the pixel transition,
                    // load, THEN switch to FEED once that's actually
                    // done — so this is just a passthrough now.
                    onSelectFeed              = onSelectFeed,
                    onToggleDownloadOnLike    = onToggleDownloadOnLike,
                    onDownloadAllLiked        = onDownloadAllLiked,
                    onCancelDownload          = onCancelDownload,
                    tagPostWhenLiked          = tagPostWhenLiked,
                    onToggleTagPostWhenLiked  = onToggleTagPostWhenLiked,
                    taggingRunning            = taggingRunning,
                    taggingScanned            = taggingScanned,
                    taggingTagged             = taggingTagged,
                    onLocallyTagAllLiked      = onLocallyTagAllLiked,
                    onDeleteTaggedDatabase    = onDeleteTaggedDatabase,
                    settingsExtras            = settingsExtras,
                    importedDatasets          = importedDatasets,
                    onExportDataset           = onExportDataset,
                    onImportDataset           = onImportDataset,
                    onDeleteImportedDataset   = onDeleteImportedDataset,
                    // Opens your own profile's Likes tab (an overlay), so
                    // the Hub stays underneath it.
                    onShowLikes               = onShowLikes,
                    onShowFriends             = onShowFriends, // opens straight into Explore (grid) mode itself
                    onShowE621Following       = { onShowE621Following(); onSetScreen(ScreenState.FEED) },
                    onToggleReducedAnimations = onToggleReducedAnimations,
                    classicProfileTabRow      = classicProfileTabRow,
                    onToggleClassicProfileTabRow = onToggleClassicProfileTabRow,
                    squareGridRounded         = squareGridRounded,
                    onToggleSquareGridRounded = onToggleSquareGridRounded,
                    pinterestThreeColumns     = pinterestThreeColumns,
                    onTogglePinterestThreeColumns = onTogglePinterestThreeColumns,
                    hateFunBlurNsfw           = hateFunBlurNsfw,
                    onToggleHateFunBlurNsfw   = onToggleHateFunBlurNsfw,
                    selfDid                   = selfDid,
                    subscribedReviewDids      = subscribedReviewDids,
                    subscribedBlogDids        = subscribedBlogDids,
                    followerScanState         = followerScanState,
                    followerScanCompletedOnce = followerScanCompletedOnce,
                    onStartFollowerScan       = onStartFollowerScan,
                    onRescanFollowersFromScratch = onRescanFollowersFromScratch,
                    onDismissFollowerScanResult = onDismissFollowerScanResult,
                    combineListsAndPacks      = combineListsAndPacks,
                    onToggleCombineListsPacks = onToggleCombineListsPacks,
                    autoAddToOnFollow         = autoAddToOnFollow,
                    onToggleAutoAddToOnFollow = onToggleAutoAddToOnFollow,
                    onSearchE621              = { tags -> onSearchE621(tags); onSetScreen(ScreenState.FEED) },
                    onShowE621Favorites       = { onShowE621Favorites(); onSetScreen(ScreenState.FEED) },
                    onSwitchMode              = onSwipeToMode,
                    onSwipeToFeed             = onReturnToFeed,
                    onReturnToProfile         = onReturnToProfile,
                    onOpenFeed                = onOpenFeed,
                    onEnterFeedView           = { explore -> onSetScreen(if (explore) ScreenState.GRID else ScreenState.FEED) },
                    selfProfile               = selfProfile,
                    hideTextOnlyPosts         = hideTextOnlyPosts,
                    onToggleHideTextOnlyPosts = onToggleHideTextOnlyPosts,
                    onOpenOwnProfile          = onOpenOwnProfile,
                    onShowSaves               = { onShowSaves(); onSetScreen(ScreenState.GRID) },
                    onShowHistory             = { onShowHistory(); onSetScreen(ScreenState.FEED) },
                    onOpenDmInbox             = onOpenDmInbox,
                    onOpenInbox               = onOpenInbox,
                    inboxUnreadCount          = inboxUnreadCount,
                    dmUnreadCount             = dmUnreadCount,
                    onOpenComposePost         = onOpenComposePost,
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
                    selfAvatarUrl             = selfAvatarUrl,
                    liveTwitchUrl             = liveLinkState.twitchUrl,
                    liveYoutubeUrl            = liveLinkState.youtubeUrl,
                    liveActivePlatform        = liveLinkState.activePlatform,
                    onSaveLiveTwitchUrl       = onSaveLiveTwitchUrl,
                    onSaveLiveYoutubeUrl      = onSaveLiveYoutubeUrl,
                    onCreateLiveLinkWidget    = onCreateLiveLinkWidget,
                    onToggleLiveLink          = onToggleLiveLink,
                    onEndLiveLink             = onEndLiveLink,
                    onMoveFeed                = onMoveFeed,
                    onRemoveFeed              = onRemoveFeed
                )
                ScreenState.GRID -> GridScreen(
                    items           = mediaItems,
                    currentIndex    = currentIndex,
                    appMode         = appMode,
                    // Feeds built on this device follow the saved ones.
                    availableFeeds  = availableFeeds + com.mediaviewer.util.LocalData.localFeeds.map {
                        BskyFeedInfo(uri = it.uri, displayName = it.name.ifBlank { "My Feed" })
                    },
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
                    onRefresh       = onRefresh,
                    selfAvatarUrl   = selfAvatarUrl,
                    isLoading       = isLoading,
                    roundedGridTiles = squareGridRounded,
                    reducedAnimations = reducedAnimations,
                    // Pulling down while already at the top opens the Hub.
                    onSwipeDown     = { onSetScreen(ScreenState.SETTINGS) },
                    onPinchIn       = onGridPinchIn
                )
            }
        }


        // The dark wash behind the comments: the whole screen at once, fading
        // in together with the blur (it used to be painted on the sheet and
        // slide up with it). Drag-to-close fades it back out with the finger.
        // Item 5: no full-screen dark wash or media blur behind the comments
        // anymore — the post stays exactly as it was, and only the comment
        // bubbles themselves blur what's behind each of them.

        // The comments sheet, over the (blurred) post.
        AnimatedVisibility(
            visible = commentsOpen,
            enter = if (reducedAnimations) EnterTransition.None
                else slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it },
            exit = if (reducedAnimations) ExitTransition.None
                else slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(220)),
            modifier = Modifier.fillMaxSize().zIndex(5f)
        ) {
            CommentsSheet(
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
                reducedAnimations = reducedAnimations,
                onDragFractionChanged = { commentsDragFraction = it }
            )
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
                        var lcPlayerRef by remember(item.id) { mutableStateOf<VideoController?>(null) }
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
                            model = ImageRequest.Builder(coil3.compose.LocalPlatformContext.current).data(item.mediaUrl).crossfade(true).build(),
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
    // Item 4: "More" menu actions on the interaction bar.
    onReportPost: () -> Unit = {},
    onShowMoreLikeThis: () -> Unit = {},
    onShowLessLikeThis: () -> Unit = {},
    onAddAccountToList: () -> Unit = {},
    onDeletePost: () -> Unit = {},
    selfDid: String = "",
    supportsFeedInteractions: Boolean = false,
    sentByExpanded: Boolean,
    onToggleSentByExpanded: () -> Unit,
    onOpenReplyToSender: () -> Unit,
    onTapSentByAuthor: (AuthorInfo) -> Unit = {},
    subImageIndices: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>,
    textExpanded: Boolean,
    onToggleTextExpanded: () -> Unit,
    uiHidden: Boolean = false,
    onSetUiHidden: (Boolean) -> Unit = {},
    landscape: Boolean = false,
    onLandscapeFullscreen: () -> Unit = {},
    onRevealUi: () -> Unit = {},
    translationEnabled: Boolean = false,
    translationTargetLang: String = "en",
    translationStates: androidx.compose.runtime.snapshots.SnapshotStateMap<String, TranslationState> = remember { mutableStateMapOf() },
    onBackdropChanged: (GlassBackdrop?, Color) -> Unit = { _, _ -> },
    externallyPaused: Boolean = false,
    hateFunBlurNsfw: Boolean = false,
    /** 0 = no comments; 1 = comments fully open (post blurred, UI faded). */
    commentsFraction: Float = 0f,
    popupOpen: Boolean = false,
    taggingStatusLabel: String? = null,
    onPrefetchListMemberships: (String) -> Unit = {}
) {
    val context     = LocalContext.current
    // The app-wide loader (not a private one): prefetches land in the same
    // memory cache the posts draw from, so the next post's image/poster is
    // on screen the moment it's swiped to.
    val imageLoader = remember { coil3.SingletonImageLoader.get(context.coilContext) }

    LaunchedEffect(currentIndex, mediaItems.size) {
        // Videos: current, next two, previous — prepared in paused players.
        val videoWindow = listOf(0, 1, 2, -1).mapNotNull { mediaItems.getOrNull(currentIndex + it) }
            .filter { it.isVideo && !it.isBlocked }
            .mapNotNull { it.videoPlaylistUrl }
        FeedVideos.preload(context, videoWindow)
        // Images: this post and the next few — every image of a multi-image
        // post (grid thumbnails and the full-size versions the viewer
        // opens), not just the first.
        (0..4).mapNotNull { mediaItems.getOrNull(currentIndex + it) }.forEach { item ->
            val urls = buildList {
                if (item.mediaGroup.size > 1) item.mediaGroup.forEach { g -> add(g.thumbUrl); add(g.mediaUrl) }
                else { if (!item.isVideo) add(item.mediaUrl); add(item.thumbUrl) }
            }
            urls.filter { it.isNotBlank() }.distinct().forEach { url ->
                imageLoader.enqueue(ImageRequest.Builder(context.coilContext).data(url).build())
            }
        }
        // Multi-image posts outline their tiles in the author's profile
        // color, which needs that account's banner + avatar sampled. Work
        // those out ahead of time for the upcoming (and previous) posts, so
        // the outlines are already there the moment a post is swiped to
        // instead of popping in a second later.
        (-2..6).mapNotNull { mediaItems.getOrNull(currentIndex + it) }
            .filter { it.mediaGroup.size > 1 && it.author.did.isNotBlank() }
            .distinctBy { it.author.did }
            .filter { ProfileColorStore.get(it.author.did) == null }
            .forEach { item ->
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching {
                        fetchProfileColors(context, item.author.did, item.author.avatarUrl, null, bannerKnown = false)
                    }
                }
            }
    }
    DisposableEffect(Unit) { onDispose { FeedVideos.releaseIdle() } }

    Box(
        Modifier.fillMaxSize().background(OledBlack)

    ) {
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
                    onReportPost     = onReportPost,
                    onDownloadGif    = onDownloadGif,
                    onShowMoreLikeThis = onShowMoreLikeThis,
                    onShowLessLikeThis = onShowLessLikeThis,
                    onAddAccountToList = onAddAccountToList,
                    onDeletePost       = onDeletePost,
                    isOwnPost          = appMode == AppMode.BLUESKY && selfDid.isNotBlank() && item.author.did == selfDid,
                    supportsFeedInteractions = supportsFeedInteractions,
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
                    uiHidden               = uiHidden || commentsFraction > 0.02f || popupOpen,
                    taggingStatusLabel     = taggingStatusLabel,
                    onPrefetchListMemberships = onPrefetchListMemberships,
                    onSetUiHidden          = onSetUiHidden,
                    landscape              = landscape,
                    // Hidden by the user (not by comments/popups): a tap anywhere brings it back.
                    tapToRevealUi          = landscape && uiHidden && commentsFraction <= 0.02f && !popupOpen,
                    onLandscapeFullscreen  = onLandscapeFullscreen,
                    onRevealUi             = onRevealUi,
                    translationEnabled     = translationEnabled,
                    translationTargetLang  = translationTargetLang,
                    translationState       = translationStates[item.id],
                    onSetTranslationState  = { translationStates[item.id] = it },
                    onBackdropChanged      = onBackdropChanged,
                    externallyPaused       = externallyPaused,
                    hateFunBlurNsfw        = hateFunBlurNsfw
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
    // Item 4: "More" menu actions on the interaction bar.
    onReportPost: () -> Unit = {},
    onShowMoreLikeThis: () -> Unit = {},
    onShowLessLikeThis: () -> Unit = {},
    onAddAccountToList: () -> Unit = {},
    onDeletePost: () -> Unit = {},
    isOwnPost: Boolean = false,
    // Item 9: only a real feed-generator-backed feed can act on the
    // "Show more/less like this" interaction signal (chronological
    // Following, profile grids, and search results have no feed generator
    // to proxy it to) — gates whether those two menu items even appear.
    supportsFeedInteractions: Boolean = false,
    sentByExpanded: Boolean, onToggleSentByExpanded: () -> Unit,
    onOpenReplyToSender: () -> Unit,
    onTapSentByAuthor: (AuthorInfo) -> Unit = {},
    subImageIndex: Int, getSubImageIndex: () -> Int, onSetSubImageIndex: (Int) -> Unit,
    textExpanded: Boolean, onToggleTextExpanded: () -> Unit,
    reducedAnimations: Boolean,
    uiHidden: Boolean = false,
    onSetUiHidden: (Boolean) -> Unit = {},
    /** Sideways: smaller UI, bottom cluster a compact width on the left,
     *  author row at the very top, and a round fullscreen button. */
    landscape: Boolean = false,
    tapToRevealUi: Boolean = false,
    onLandscapeFullscreen: () -> Unit = {},
    onRevealUi: () -> Unit = {},
    translationEnabled: Boolean = false,
    translationTargetLang: String = "en",
    translationState: TranslationState? = null,
    onSetTranslationState: (TranslationState) -> Unit = {},
    onBackdropChanged: (GlassBackdrop?, Color) -> Unit = { _, _ -> },
    externallyPaused: Boolean = false,
    // Feature request #8: "I hate fun" — Settings toggle (Bluesky mode
    // only; item.isNsfwLabeled is always false for other modes anyway
    // since only Bluesky posts ever populate MediaItem.labels).
    hateFunBlurNsfw: Boolean = false,
    taggingStatusLabel: String? = null,
    onPrefetchListMemberships: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(item.id) { scale = 1f; offset = Offset.Zero }

    // Feature request #8: `remember(item.id)` — not hoisted any higher, so
    // this naturally resets to unrevealed once the pager scrolls this post
    // far enough away that Compose disposes its page and back again later
    // (a fresh composition), matching the spec ("unblurs...until the user
    // scrolls off and on it again") without needing to hand-roll any
    // "did we scroll away" detection of our own.
    var nsfwRevealed by remember(item.id) { mutableStateOf(false) }
    val nsfwBlurred = hateFunBlurNsfw && item.isNsfwLabeled && !item.isBlocked && !nsfwRevealed

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
    var videoPlayerRef       by remember(item.id) { mutableStateOf<VideoController?>(null) }
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

    // Items 5-8: the "More" menu's own state, hoisted up here (out of
    // ActionRow) for the same reason as the indicator pill's Next/Previous
    // buttons above — it needs to render as a sibling of the recorded box,
    // anchored to the action bar's own root-relative bounds, rather than as
    // a Popup (a Popup opens its own separate Android window, whose
    // positionInRoot() is relative to THAT window, not this one — which is
    // exactly why its live blur backdrop never lined up correctly; see
    // MoreBubbleMenu's own doc comment).
    var moreMenuExpanded by remember(item.id) { mutableStateOf(false) }
    var actionBarOrigin  by remember(item.id) { mutableStateOf<Offset?>(null) }
    var actionBarSize    by remember(item.id) { mutableStateOf(IntSize.Zero) }
    // The More button's own bounds: the More bubbles stand right on it.
    var moreButtonOrigin by remember(item.id) { mutableStateOf<Offset?>(null) }
    var moreButtonSize   by remember(item.id) { mutableStateOf(IntSize.Zero) }

    // ── Multi-image posts: every image at once as a grid, tap one to zoom
    // it to full screen (then swipe between them / pick from the selector
    // row above the interaction bar). ──
    val isImageGrid = !item.isVideo && !item.isTextOnly && item.mediaGroup.size >= 2
    // The author has blocked the signed-in user (see MediaItem.authorBlocksViewer).
    val authorBlocksViewer = appMode == AppMode.BLUESKY &&
        (item.authorBlocksViewer || com.mediaviewer.util.BlockedAccounts.isBlockedBy(item.author.did))
    // Non-null while the fullscreen viewer is open (or animating in/out).
    var viewerIndex by remember(item.id) { mutableStateOf<Int?>(null) }
    var viewerClosing by remember(item.id) { mutableStateOf(false) }
    // 0 = the image sits in its grid tile, 1 = fullscreen.
    val viewerAnim = remember(item.id) { Animatable(0f) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { item.mediaGroup.size.coerceAtLeast(1) })
    // Each grid tile's on-screen bounds (root coordinates) — where the zoom starts/ends.
    val tileRects = remember(item.id) { mutableStateMapOf<Int, Rect>() }
    // Each image's width/height, learned as it loads (or from the post).
    val imageAspects = remember(item.id) { mutableStateMapOf<Int, Float>() }
    // The text bubble above the interaction bar: compact, or grown upward
    // to show the whole text.
    var textBubbleExpanded by remember(item.id) { mutableStateOf(false) }
    val postScope = rememberCoroutineScope()
    val viewerSpringIn = spring<Float>(dampingRatio = 0.8f, stiffness = 280f)
    val viewerSpringOut = spring<Float>(dampingRatio = 0.92f, stiffness = 380f)

    fun openViewer(index: Int) {
        if (viewerIndex != null || item.mediaGroup.isEmpty()) return
        val i = index.coerceIn(0, item.mediaGroup.size - 1)
        haptic(context)
        viewerIndex = i
        viewerClosing = false
        textBubbleExpanded = false
        postScope.launch {
            pagerState.scrollToPage(i)
            onSetSubImageIndex(i)
            viewerAnim.snapTo(0f)
            if (reducedAnimations) viewerAnim.snapTo(1f) else viewerAnim.animateTo(1f, viewerSpringIn)
        }
    }

    fun closeViewer() {
        if (viewerIndex == null || viewerClosing) return
        viewerClosing = true
        scale = 1f; offset = Offset.Zero
        postScope.launch {
            if (reducedAnimations) viewerAnim.snapTo(0f) else viewerAnim.animateTo(0f, viewerSpringOut)
            viewerIndex = null
            viewerClosing = false
        }
    }

    // Back closes the fullscreen image first.
    com.mediaviewer.ui.compat.BackHandler(enabled = viewerIndex != null) { closeViewer() }
    LaunchedEffect(pagerState, item.id) {
        snapshotFlow { pagerState.currentPage }.collect { page -> if (viewerIndex != null) onSetSubImageIndex(page) }
    }

    // Big Update #1: sampled average color of the current post's media — feeds
    // the liquid-glass panels so their tint/"reflection" shifts with whatever
    // is on screen, per post.
    val glassBackdropUrl = item.thumbUrl.ifBlank { item.mediaUrl }
    // Text-only posts have no media to take a color from: they wear the
    // uploader's own profile color (banner/avatar blend) instead.
    val authorTint = if (liquidGlass && item.isTextOnly && item.author.did.isNotBlank())
        rememberAuthorProfileTint(item.author.did, item.author.avatarUrl) else null
    val dominantColor = authorTint ?: if (liquidGlass) rememberDominantColor(glassBackdropUrl) else Color.White

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
                // (No haptic when a translation finishes any more.)
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
        // Multi-image posts show every image at once now (grid), so a swipe
        // always moves to the next/previous post; inside the fullscreen
        // viewer the pager itself takes horizontal swipes.
        if (totalDx < 0) onSwipeLeft() else onSwipeRight()
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
                        val downTime = com.mediaviewer.platform.currentTimeMillis()
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
                            val elapsed = com.mediaviewer.platform.currentTimeMillis() - downTime
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
                                        abs(dy) > 80f && abs(dy) > abs(dx) * 1.2f ->
                                            if (dy < 0) onSwipeUp()
                                            else if (viewerIndex != null) closeViewer()
                                            else onSwipeDown()
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
                                            scale = 1f; offset = Offset.Zero
                                            // In the fullscreen image viewer, pinching in
                                            // zooms back out to the grid instead.
                                            if (viewerIndex != null) closeViewer() else onPinchToGrid()
                                            break
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
                                        if (!item.isTextOnly && !(isImageGrid && viewerIndex == null)) {
                                            val oldScale = scale
                                            val newScale = rawNew.coerceIn(1f, 8f)
                                            scale = newScale
                                            // Bug fix (item 10): this used to just add the pinch
                                            // centroid's frame-to-frame movement to `offset` — a
                                            // pure "follow the fingers" pan. It never corrected for
                                            // the fact that graphicsLayer scales around the
                                            // component's own center by default, so growth from
                                            // any change in `scale` always visually expanded
                                            // outward from dead center regardless of where the
                                            // pinch actually was or where the user had since
                                            // panned to. The `ratio`/`containerCenter` terms below
                                            // solve for the translation that keeps the point
                                            // currently under the fingers (`centroid`) fixed on
                                            // screen as scale changes from oldScale to newScale —
                                            // on top of the existing finger-follow pan, which still
                                            // handles panning while scale itself stays constant.
                                            offset = if (newScale > 1.02f) {
                                                val containerCenter = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                                val ratio = if (oldScale != 0f) newScale / oldScale else 1f
                                                val pivotCorrected = offset * ratio + (centroid - containerCenter) * (1f - ratio)
                                                clampOffset(pivotCorrected + (centroid - prevCentroid), newScale)
                                            } else Offset.Zero
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
            // The post's background: its own color, dimmed, under the stars.
            if (liquidGlass) SpaceSky(dominantColor, Modifier.matchParentSize())
            val mediaModifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }.let { if (item.isBlocked || nsfwBlurred) it.blur(90.dp) else it }
            if (item.isTextOnly) {
                // Big Update #3: text-only posts get a liquid-glass card shaped
                // like a piece of media, centered where an image would sit.
                if (item.isPoll && PollFormat.parse(item.text) != null) {
                    // A Stellar poll: tappable answers with live percentages.
                    PollCard(item, dominantColor, liquidGlass)
                } else TextOnlyPostCard(
                    item.text, dominantColor, translationState, onToggleTranslationView,
                    emojiImageUrl = item.textshotImageUrl, emojiAspectRatio = item.aspectRatio
                )
            } else if (item.isVideo && item.videoPlaylistUrl != null) {
                VideoPlayer(
                    item.videoPlaylistUrl, mediaModifier,
                    controlsVisible = videoControlsVisible,
                    // Item 11 follow-up: was gated at `scale > 1.05f`, which
                    // let a small window of zoom (1.0–1.05) through where the
                    // controls were still visible but already stale/
                    // mispositioned (the exact bug this item exists to fix)
                    // before finally snapping hidden. Tightened to
                    // `VIDEO_UI_ZOOM_EPSILON` (effectively "any zoom at all")
                    // so the hide and the tap-to-pause switchover both kick
                    // in together, right at the start of a pinch.
                    onToggleControls = {
                        if (scale > VIDEO_UI_ZOOM_EPSILON) {
                            videoPlayerRef?.let { p -> if (p.isPlaying) p.pause() else p.play() }
                        } else {
                            videoControlsVisible = !videoControlsVisible
                        }
                    },
                    isBlocked = item.isBlocked,
                    thumbUrl = item.thumbUrl,
                    onPlayerReady = { videoPlayerRef = it },
                    onPlaybackState = { playing, pos, dur -> videoIsPlaying = playing; videoPositionMs = pos; videoDurationMs = dur },
                    onBoundsChanged = { origin, size -> videoBoundsOrigin = origin; videoBoundsSize = size },
                    externallyPaused = externallyPaused
                )
            } else if (isImageGrid) {
                val gridBlurred = item.isBlocked || nsfwBlurred
                val progress = viewerAnim.value
                val openIdx = viewerIndex
                // The image that flies between its tile and full screen: the
                // one tapped while opening, whichever is showing while closing.
                val flyPage = if (viewerClosing || openIdx == null) pagerState.currentPage else openIdx
                val outline = rememberAuthorProfileTint(item.author.did, item.author.avatarUrl)
                MultiImageGrid(
                    images = item.mediaGroup,
                    outline = outline,
                    blurred = gridBlurred,
                    // The rest of the grid fades away as the picked image
                    // flies up to full screen (and back as it returns).
                    alpha = if (openIdx == null) 1f else (1f - progress).coerceIn(0f, 1f),
                    hiddenIndex = if (openIdx == null) null else minOf(flyPage, 8),
                    onTileBounds = { i, r -> if (tileRects[i] != r) tileRects[i] = r },
                    onAspect = { i, a -> if (!imageAspects.containsKey(i)) imageAspects[i] = a },
                    onTap = { i -> if (menuCenter == null && !gridBlurred) openViewer(i) }
                )
                if (openIdx != null) {
                    val tile = tileRects[minOf(flyPage, 8)]
                    MultiImageViewer(
                        images = item.mediaGroup,
                        pagerState = pagerState,
                        flyPage = flyPage,
                        progress = progress,
                        // The uncropped pager takes over once the zoom has
                        // fully come to rest (the spring may overshoot a hair).
                        settled = !viewerClosing && !viewerAnim.isRunning && progress >= 0.99f,
                        fromRect = tile?.translate(-postBoxRootOrigin),
                        aspectFor = { i -> imageAspects[i] ?: item.mediaGroup.getOrNull(i)?.aspectRatio },
                        zoomScale = scale,
                        zoomOffset = offset,
                        interactive = !viewerClosing,
                        onAspect = { i, a -> if (imageAspects[i] != a) imageAspects[i] = a },
                        onTap = { closeViewer() },
                        onOverscrollNext = onSwipeLeft,
                        onOverscrollPrev = onSwipeRight
                    )
                }
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
                                model = ImageRequest.Builder(coil3.compose.LocalPlatformContext.current).data(displayFull).crossfade(true).build(),
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
            } else if (nsfwBlurred) {
                // Feature request #8: tap-to-reveal — only shows in the
                // feed/pager (this composable), not in profile tab grids,
                // per spec ("The unblur button should only appear when a
                // post is selected/while in the feed/timeline page").
                Box(
                    Modifier.align(Alignment.Center)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { nsfwRevealed = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text("Show NSFW Content", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
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
        // Item 11: also gated on `scale <= 1.05f` — while zoomed in, this bar's
        // position was computed from the video's *unzoomed* on-screen bounds
        // (videoBoundsOrigin/Size, reported by VideoPlayer's own
        // onGloballyPositioned before the zoom graphicsLayer transform is
        // applied to it), so once the video itself was scaled/panned via
        // pinch, this bar just sat at its old, now-wrong position — reading
        // as "the UI breaks and moves off screen". Simplest correct fix:
        // don't show it at all while zoomed (matches the tap-to-pause
        // replacement above), rather than trying to keep a second transform
        // in sync with the first.
        // Item 11 follow-up: same tightened threshold as the tap-to-pause
        // switchover above — was `scale <= 1.05f`, which is why the bar
        // used to visibly slide/glitch off to the side for the first bit of
        // a pinch before finally disappearing (its position is computed
        // from the video's *unzoomed* bounds and doesn't track the zoom
        // transform, so any zoom at all makes it stale). Now it disappears
        // at the very start of a pinch instead of partway through one.
        if (item.isVideo && !item.isBlocked && scale <= VIDEO_UI_ZOOM_EPSILON && videoControlsVisible && videoBoundsSize != IntSize.Zero) {
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
            LandscapeChromeScale(landscape) {
            Column(
                Modifier.fillMaxWidth()
                    // Sideways the camera cutout is at a side: keep clear of it.
                    .then(if (landscape) Modifier.landscapeSideSafe() else Modifier)
            ) {
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
                            .padding(top = if (landscape) 6.dp else rememberTopCutoutClearance())
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
                            // Sideways: right at the very top (no notch row there).
                            .then(if (item.sentByAuthor == null) Modifier.padding(top = if (landscape) 6.dp else rememberTopCutoutClearance()) else Modifier),
                        liquidGlass = liquidGlass,
                        dominantColor = dominantColor,
                        backdrop = glassBackdrop,
                        onHorizontalSwipe = handleHorizontalSwipe,
                        followEnabled = !authorBlocksViewer
                    )
                    // Status bubbles, centered right under the author row:
                    // translation (Settings → Show Translation Status),
                    // like-tagging (Settings → Show Tagging Status) and
                    // "This user has you blocked". Each one grows in and
                    // shrinks back out on its own.
                    val showTranslateStatus = translationEnabled && com.mediaviewer.util.UiToggles.showTranslationStatus &&
                        item.text.isNotBlank() && translationState != null && translationState.status != TranslationStatus.IDLE
                    PostStatusRow(
                        translation = if (showTranslateStatus) translationState else null,
                        taggingLabel = taggingStatusLabel,
                        blockedByAuthor = authorBlocksViewer,
                        liquidGlass = liquidGlass, tint = dominantColor, backdrop = glassBackdrop,
                        reducedAnimations = reducedAnimations,
                        onToggleTranslationView = onToggleTranslationView,
                        edited = !item.editedAt.isNullOrBlank(),
                        onOpenEditHistory = { LocalOverlays.editHistoryFor = item }
                    )
                }
            }
            }
        }

        // Four extra quick-shortcuts (item 3) now live as diagonal buttons in the
        // long-press radial menu above (see QuickActionMenu / getHoveredAction) —
        // they are Bluesky-only, matching the existing radial menu's action set.

        // Bottom cluster, bottom to top: the interaction bar; right above it
        // the post's text bubble (or, while a multi-image post's fullscreen
        // viewer is open, the image selector row in its place); and the audio
        // visualizer resting on top of whichever of those is showing.
        AnimatedVisibility(
            visible = !uiHidden,
            enter = if (reducedAnimations) EnterTransition.None else fadeIn(FADE_ANIM),
            exit = if (reducedAnimations) ExitTransition.None else fadeOut(FADE_ANIM),
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).zIndex(2f)
        ) {
            LandscapeChromeScale(landscape) {
            Box(Modifier.fillMaxWidth()) {
            Column(
                // Sideways: a compact fixed width at the bottom left
                // (portrait: edge to edge, as always).
                modifier = if (landscape) Modifier
                    .align(Alignment.BottomStart)
                    .landscapeSideSafe()
                    .width(landscapeClusterWidth(appMode, liquidGlass))
                else Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // The visualizer takes up no layout space at all (drawn upward
                // from a zero-height slot), so turning it on never moves anything.
                if ((com.mediaviewer.util.UiToggles.audioVisualizer && !com.mediaviewer.util.LocalData.batterySaverActive)) {
                    val barsColor = if (liquidGlass) dominantColor else rememberDominantColor(glassBackdropUrl)
                    AudioVisualizerBars(
                        color = barsColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .layout { measurable, constraints ->
                                val h = 34.dp.roundToPx()
                                val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                                layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
                            }
                            .padding(horizontal = 20.dp)
                    )
                }
                val viewerShowing = isImageGrid && viewerIndex != null && !viewerClosing
                // Text posts show their text on the card itself — except a
                // Textshot's own caption (the regular post text typed
                // alongside the Textshot), which gets the normal bubble.
                val originalBubbleText = when {
                    item.isBlocked -> ""
                    item.isTextOnly -> item.captionText.orEmpty()
                    else -> item.text
                }
                val postBubbleText = if (!item.isTextOnly && translationState?.status == TranslationStatus.DONE && translationState.showingTranslated && originalBubbleText.isNotBlank())
                    translationState!!.translatedText else originalBubbleText
                // Bookmark folders (supporters): right after a post is saved,
                // its text bubble reads "Tap to Add Saved Post to Folder" for
                // a few seconds (collapsing first if it was expanded), then
                // goes back to the post's text.
                var folderPrompt by remember(item.id) { mutableStateOf(false) }
                var wasBookmarked by remember(item.id) { mutableStateOf(item.isBookmarked) }
                LaunchedEffect(item.isBookmarked) {
                    val justSaved = item.isBookmarked && !wasBookmarked
                    wasBookmarked = item.isBookmarked
                    if (!item.isBookmarked) folderPrompt = false
                    else if (justSaved && appMode == AppMode.BLUESKY && com.mediaviewer.util.Supporter.active && item.postUri.isNotBlank()) {
                        textBubbleExpanded = false
                        folderPrompt = true
                        kotlinx.coroutines.delay(4500)
                        folderPrompt = false
                    }
                }
                val bubbleText = if (folderPrompt) "Tap to Add Saved Post to Folder" else postBubbleText
                AnimatedContent(
                    targetState = viewerShowing,
                    transitionSpec = {
                        if (reducedAnimations) EnterTransition.None togetherWith ExitTransition.None
                        else ((fadeIn(tween(240, delayMillis = 60)) + scaleIn(tween(240, delayMillis = 60), initialScale = 0.96f)) togetherWith
                            fadeOut(tween(160))) using SizeTransform(clip = false) { _, _ -> snap() }
                    },
                    contentAlignment = Alignment.BottomCenter,
                    label = "bubbleOrSelector"
                ) { showSelector ->
                    if (showSelector) {
                        ImageSelectorRow(
                            images = item.mediaGroup,
                            currentPage = pagerState.currentPage,
                            outline = rememberAuthorProfileTint(item.author.did, item.author.avatarUrl),
                            liquidGlass = liquidGlass, tint = dominantColor, backdrop = glassBackdrop,
                            onSelect = { i, animate ->
                                postScope.launch { if (animate) pagerState.animateScrollToPage(i) else pagerState.scrollToPage(i) }
                            },
                            onPrevPost = { haptic(context); onSwipeRight() },
                            onNextPost = { haptic(context); onSwipeLeft() },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                        )
                    } else if (bubbleText.isNotBlank()) {
                        PostTextBubble(
                            text = bubbleText,
                            expanded = textBubbleExpanded,
                            onToggle = {
                                if (folderPrompt) {
                                    folderPrompt = false
                                    LocalOverlays.bookmarkFolderFor = item
                                } else textBubbleExpanded = !textBubbleExpanded
                            },
                            liquidGlass = liquidGlass, tint = dominantColor, backdrop = glassBackdrop,
                            reducedAnimations = reducedAnimations,
                            onHorizontalSwipe = handleHorizontalSwipe,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp),
                            centeredText = "Tap to Add Saved Post to Folder"
                        )
                    } else {
                        Spacer(Modifier.fillMaxWidth().height(0.dp))
                    }
                }
                ActionRow(item, appMode, onToggleLike, onToggleRepost, onToggleBookmark, onE621Vote,
                    onQuoteRepost, onDownload, onDownloadGif, onBlockAccount, onSendPost,
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navBarSpace)
                        .height(if (liquidGlass) 60.dp else 52.dp),
                    liquidGlass = liquidGlass,
                    dominantColor = dominantColor,
                    backdrop = glassBackdrop,
                    moreMenuExpanded = moreMenuExpanded,
                    onToggleMoreMenu = {
                        moreMenuExpanded = !moreMenuExpanded
                        if (moreMenuExpanded && appMode == AppMode.BLUESKY) onPrefetchListMemberships(item.author.did)
                    },
                    onVisibleBoundsChanged = { origin, size -> actionBarOrigin = origin; actionBarSize = size },
                    onMoreButtonBounds = { origin, size -> moreButtonOrigin = origin; moreButtonSize = size },
                    interactionsBlocked = authorBlocksViewer
                )
            }
            if (landscape) {
                // Round fullscreen button, bottom right: hides the UI
                // (landscape only); a tap anywhere brings it back.
                LandscapeFullscreenButton(
                    liquidGlass = liquidGlass, tint = dominantColor, backdrop = glassBackdrop,
                    onClick = { moreMenuExpanded = false; onLandscapeFullscreen() },
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.navBarSpace)
                        .landscapeSideSafe()
                        .padding(end = 12.dp, bottom = if (liquidGlass) 8.dp else 4.dp)
                )
            }
            }
            }
        }

        // Landscape with the UI hidden: any tap brings it back (watched
        // without consuming, so swipes, double-tap-to-like etc still work).
        if (tapToRevealUi) {
            val revealLatest by rememberUpdatedState(onRevealUi)
            Box(
                Modifier.fillMaxSize().zIndex(7f).pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val startMs = down.uptimeMillis
                        var moved = false
                        var multi = false
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.size > 1) multi = true
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (!c.pressed) {
                                if (!moved && !multi && c.uptimeMillis - startMs < 350) revealLatest()
                                break
                            }
                        }
                    }
                }
            )
        }

        // Items 5-8: rendered as a sibling of the recorded backdrop box (same
        // reasoning as PostIndicatorNavButtons above) so its live blur reads
        // the same real-time backdrop the rest of the UI does, and so it's a
        // completely normal composable — not a system Popup — meaning taps
        // outside its own small footprint fall straight through to whatever
        // is really there (swiping to another post, tapping other buttons,
        // etc.) instead of being silently swallowed. It closes only via its
        // own onToggleMoreMenu (the X button) or by this whole PostContent
        // being disposed — which happens automatically the moment the user
        // swipes to a different post, or navigates to the hub, comments, or
        // grid — never from an outside tap.
        val barOrigin = actionBarOrigin
        val moreOrigin = moreButtonOrigin
        if (barOrigin != null && moreOrigin != null) {
            MoreBubbleMenu(
                visible = moreMenuExpanded && !uiHidden,
                anchorOriginRoot = moreOrigin,
                anchorSize = moreButtonSize,
                barTopRoot = barOrigin.y,
                containerRootOrigin = postBoxRootOrigin,
                onDismissRequest = { moreMenuExpanded = false },
                liquidGlass = liquidGlass, tint = dominantColor, backdrop = glassBackdrop,
                onShowMoreLikeThis = onShowMoreLikeThis,
                onShowLessLikeThis = onShowLessLikeThis,
                onAddAccountToList = onAddAccountToList,
                onBlock = onBlockAccount,
                onReport = onReportPost,
                supportsFeedInteractions = supportsFeedInteractions,
                isOwnPost = isOwnPost,
                onDelete = onDeletePost
            )
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
        Triple(QuickAction.TOP,          Icons.Filled.Favorite,  if (item.isLiked) vividAccent(dominantColor) else Color.White),
        Triple(QuickAction.TOP_RIGHT,    Icons.Default.Send,     Color.White),
        Triple(QuickAction.RIGHT,        Icons.Default.Repeat,   if (item.isReposted) vividAccent(dominantColor) else Color.White),
        Triple(QuickAction.BOTTOM_RIGHT, Icons.Default.EditNote, if (item.isQuoteReposted) vividAccent(dominantColor) else Color.White),
        Triple(QuickAction.BOTTOM,       Icons.Default.Download, if (item.isDownloaded) vividAccent(dominantColor) else Color.White),
        Triple(QuickAction.BOTTOM_LEFT,  Icons.Default.Block,    if (item.isBlocked) Color(0xFFE0245E) else Color.White),
        Triple(QuickAction.LEFT,         Icons.Filled.Bookmark,  if (item.isBookmarked) vividAccent(dominantColor) else Color.White),
        Triple(QuickAction.TOP_LEFT,     Icons.Default.Download, if (item.isGifDownloaded) vividAccent(dominantColor) else Color.White) // rendered as "GIF" text, see below
    ) else listOf(
        Triple(QuickAction.TOP,    Icons.Default.ArrowUpward,   if (item.e621UserVote == 1) vividAccent(dominantColor) else Color.White),
        Triple(QuickAction.RIGHT,  Icons.Filled.Star,           if (item.isBookmarked) vividAccent(dominantColor) else Color.White),
        Triple(QuickAction.BOTTOM, Icons.Default.ArrowDownward, if (item.e621UserVote == -1) vividAccent(dominantColor) else Color.White),
        Triple(QuickAction.LEFT,   Icons.Default.Download,      if (item.isDownloaded) vividAccent(dominantColor) else Color.White)
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
                            Text(
                                "GIF", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = if (com.mediaviewer.platform.PlatformFeature.GIF_EXPORT.isAvailable) Modifier else Modifier.grayedOut()
                            )
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
                        Text(
                            "GIF", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = if (com.mediaviewer.platform.PlatformFeature.GIF_EXPORT.isAvailable) Modifier else Modifier.grayedOut()
                        )
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

// ─── Translation indicator (Phase 4) ───────────────────────────────────────────

/** Phase 4 — sits in the same bottom-cluster spot as the "1/N" image-count
 *  pill (see the bottom AnimatedVisibility block in PostContent), styled the
 *  same way. Shows a spinner + "Translating…" while in flight, then
 *  "Translated (source) to (target)" once done — permanently, not just a
 *  toast, per spec — and stays tappable afterward to flip the post's text
 *  between original and translated. */
@Composable
private fun TranslationIndicatorPill(
    state: TranslationState, liquidGlass: Boolean, dominantColor: Color, backdrop: GlassBackdrop?, onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when (state.status) {
        TranslationStatus.TRANSLATING -> "Translating…"
        TranslationStatus.DONE ->
            if (state.showingTranslated) "Translated ${state.sourceLangLabel} to ${state.targetLangLabel}"
            else "Showing original · tap to translate"
        TranslationStatus.IDLE -> ""
    }
    StatusPill(
        label = label,
        spinning = state.status == TranslationStatus.TRANSLATING,
        liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
        onClick = if (state.status == TranslationStatus.DONE) onClick else null,
        modifier = modifier
    )
}

/** One of the small status bubbles under the author row (translation,
 *  like-tagging): an optional spinner and a one-line label. */
@Composable
private fun StatusPill(
    label: String, spinning: Boolean, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)

    @Composable
    fun PillBody() {
        Row(
            modifier = Modifier
                .then(
                    if (onClick != null)
                        Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
                    else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (spinning) {
                CircularProgressIndicator(Modifier.size(11.dp), color = Color.White, strokeWidth = 1.5.dp)
            }
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    if (liquidGlass) {
        LiquidGlassSurface(modifier = modifier.widthIn(max = 300.dp), shape = shape, tint = tint, backdrop = backdrop) { PillBody() }
    } else {
        Box(modifier.widthIn(max = 300.dp).clip(shape).background(Color.Black.copy(0.5f))) { PillBody() }
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
    onToggleTranslationView: () -> Unit = {},
    // A Textshot with custom emoji can't be shown as text (the emoji would
    // vanish), so it's shown as its posted picture on the same glass card,
    // shaped to the picture's aspect ratio.
    emojiImageUrl: String = "",
    emojiAspectRatio: Float? = null
) {
    if (emojiImageUrl.isNotBlank()) {
        LiquidGlassSurface(
            modifier = Modifier.fillMaxWidth(0.94f).aspectRatio((emojiAspectRatio ?: 1f).coerceIn(0.66f, 2f)),
            shape = RoundedCornerShape(28.dp),
            tint = dominantColor
        ) {
            TextshotEmojiImage(emojiImageUrl, cornerRadius = 28.dp, modifier = Modifier.fillMaxSize())
        }
        return
    }
    val showTranslated = translationState?.status == TranslationStatus.DONE && translationState.showingTranslated
    val displayText = (if (showTranslated) translationState!!.translatedText else text).ifBlank { " " }
    val toggleable = translationState?.status == TranslationStatus.DONE
    // The whole text always shows (a long Textshot used to be cut off at 14
    // lines here, while the profile's Text Posts tab showed all of it): the
    // card grows into the space between the author row and the bottom
    // bubbles, the text shrinks until it fits, and only if it still doesn't
    // fit at the smallest size does the card scroll.
    // Sized to fit between the author row (top) and the text + interaction
    // bars (bottom), but centered on the screen itself — only nudged if
    // centering would run it into either (same rule as multi-image grids).
    val topClear = 104.dp
    val bottomClear = 176.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val baseStyle = LocalTextStyle.current
        val cardWidth = maxWidth * 0.94f
        val maxCardHeight = (maxHeight - topClear - bottomClear).coerceAtLeast(160.dp)
        val topClearPx = with(density) { topClear.roundToPx() }
        val bottomClearPx = with(density) { bottomClear.roundToPx() }
        val hPad = 18.dp
        val vPad = 26.dp
        val (fontSizeSp, fits) = remember(displayText, cardWidth, maxCardHeight, baseStyle) {
            val widthPx = with(density) { (cardWidth - hPad * 2).roundToPx() }.coerceAtLeast(1)
            val heightPx = with(density) { (maxCardHeight - vPad * 2).toPx() }
            var size = 19f
            var fitsAtSize = false
            while (size >= 11f) {
                val result = measurer.measure(
                    androidx.compose.ui.text.AnnotatedString(displayText),
                    style = baseStyle.merge(androidx.compose.ui.text.TextStyle(
                        fontSize = size.sp, lineHeight = (size * 1.37f).sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
                    )),
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = widthPx)
                )
                if (result.size.height <= heightPx) { fitsAtSize = true; break }
                if (size <= 11f) break
                size -= 1f
            }
            size to fitsAtSize
        }
        LiquidGlassSurface(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val h = constraints.maxHeight
                    val centered = (h - placeable.height) / 2
                    val maxTop = (h - bottomClearPx - placeable.height).coerceAtLeast(topClearPx)
                    val top = centered.coerceIn(topClearPx, maxTop)
                    layout(constraints.maxWidth, h) {
                        placeable.place((constraints.maxWidth - placeable.width) / 2, top)
                    }
                }
                .width(cardWidth)
                .heightIn(min = 160.dp, max = maxCardHeight)
                .wrapContentHeight(),
            shape = RoundedCornerShape(28.dp),
            tint = dominantColor
        ) {
            Box(
                // Scrolls only when even the smallest size can't fit it all,
                // so normal text posts keep their swipe-up/down gestures.
                Modifier.then(if (fits) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = hPad, vertical = vPad).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    displayText,
                    color = Color.White, fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.37f).sp,
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
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
}

// ─── Author Row ───────────────────────────────────────────────────────────────

/** Top of the post: one bubble with the author's icon, display name and
 *  handle (the post text now lives in its own bubble above the interaction
 *  bar — see [PostTextBubble]), and the Follow button, both the same height. */
@Composable
private fun AuthorRow(
    item: MediaItem, appMode: AppMode, onToggleFollow: () -> Unit, onTapAuthor: () -> Unit,
    modifier: Modifier, liquidGlass: Boolean, dominantColor: Color, backdrop: GlassBackdrop?,
    onHorizontalSwipe: (Float) -> Unit = {},
    followEnabled: Boolean = true
) {
    val author = item.author
    val pillShape = RoundedCornerShape(14.dp)

    @Composable
    fun PillContent() {
        Row(
            modifier = Modifier.fillMaxHeight().padding(start = 4.dp, end = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (author.avatarUrl != null) {
                AsyncImage(model = author.avatarUrl, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.size(22.dp).clip(CircleShape))
            } else {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White.copy(0.12f)))
            }
            Text(author.displayName, color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp))
            if (appMode == AppMode.BLUESKY) {
                Text("@${author.handle}", color = if (liquidGlass) Color.White.copy(0.75f) else DimGray,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    Row(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // This bubble sits above (not inside) the post's own gesture area, so
        // a horizontal swipe that starts on it is handled here the same way.
        val pillModifier = Modifier
            .weight(1f, fill = false)
            .fillMaxHeight()
            .heightIn(min = 30.dp)
            .clip(pillShape)
            .horizontalSwipeWatcher(onHorizontalSwipe)
            .clickable(
                indication = null, interactionSource = remember { MutableInteractionSource() },
                onClick = onTapAuthor
            )
        if (liquidGlass) {
            LiquidGlassSurface(modifier = pillModifier, shape = pillShape, tint = dominantColor, backdrop = backdrop) { PillContent() }
        } else {
            Box(pillModifier.background(Color.Black.copy(alpha = 0.55f))) { PillContent() }
        }

        Spacer(Modifier.width(8.dp))

        // Shared with the profile page's follow button (see FollowButton in
        // GlassTheme.kt) so both look and behave identically.
        FollowButton(
            isFollowing = author.isFollowing,
            liquidGlass = liquidGlass,
            tint = dominantColor,
            backdrop = backdrop,
            onClick = onToggleFollow,
            modifier = Modifier.fillMaxHeight().heightIn(min = 30.dp),
            enabled = followEnabled
        )
    }
}

/** Watches a touch on a bubble that sits above the post's own gesture area
 *  (so the post never sees it): a clear horizontal swipe is claimed — so the
 *  bubble's own tap/scroll don't also fire — and handed to [onSwipe] with its
 *  total distance, exactly like a swipe anywhere else on the post. */
private fun Modifier.horizontalSwipeWatcher(onSwipe: (Float) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var totalX = 0f; var totalY = 0f
        var claimed = false
        var vertical = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val d = change.positionChange()
            totalX += d.x; totalY += d.y
            if (!claimed && !vertical) {
                if (abs(totalX) > viewConfiguration.touchSlop && abs(totalX) > abs(totalY) * 1.2f) claimed = true
                else if (abs(totalY) > viewConfiguration.touchSlop) vertical = true
            }
            if (claimed) change.consume()
        }
        if (claimed && abs(totalX) > 80f) onSwipe(totalX)
    }
}

/** A tap that ignores drags (unlike detectTapGestures, which still fires
 *  after a swipe that stayed inside the element) and long presses. Nothing
 *  is consumed, so the post's own gestures (double-tap to like, swipes)
 *  keep working over it. */
private fun Modifier.quickTap(key: Any?, onTap: () -> Unit): Modifier = this.pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        var upAt = -1L
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) moved = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (!change.pressed) { upAt = change.uptimeMillis; break }
        }
        if (!moved && upAt >= 0 && upAt - down.uptimeMillis < 400L) onTap()
    }
}

// ─── Post text bubble ─────────────────────────────────────────────────────────

/** The post's text, in its own edge-to-edge bubble right above the
 *  interaction bar: one line (ending in "…" when there's more) until tapped,
 *  then it grows upward — its bottom edge never moves — to show all of it;
 *  tap again to shrink it back down. */
@Composable
private fun PostTextBubble(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    reducedAnimations: Boolean,
    onHorizontalSwipe: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** Text that should sit centered (the "add to folder" prompt). */
    centeredText: String? = null
) {
    val shape = RoundedCornerShape(18.dp)
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        val target = if (expanded) 1f else 0f
        if (reducedAnimations) progress.snapTo(target)
        else progress.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 340f))
    }
    val bubbleModifier = modifier
        .clip(shape)
        .horizontalSwipeWatcher(onHorizontalSwipe)
        .clickable(
            indication = null, interactionSource = remember { MutableInteractionSource() },
            onClick = onToggle
        )

    @Composable
    fun Body() {
        val style = androidx.compose.ui.text.TextStyle(color = Color.White.copy(alpha = 0.95f), fontSize = 13.sp, lineHeight = 18.sp)
        // Both versions are laid out; the bubble's height slides between the
        // one-line height and the full height (the column it sits in is
        // anchored at the bottom, so it grows upward) while the two
        // cross-fade.
        Layout(
            content = {
                // Cross-fades when the text itself changes (the "Tap to Add
                // Saved Post to Folder" prompt and back) — the bubble keeps
                // its shape.
                Crossfade(text, animationSpec = tween(260), label = "bubbleTextOne") { t ->
                    Text(
                        t, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = if (t == centeredText) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Crossfade(text, animationSpec = tween(260), label = "bubbleTextFull") { t ->
                    Text(
                        t, style = style,
                        textAlign = if (t == centeredText) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
        ) { measurables, constraints ->
            val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
            val one = measurables[0].measure(loose)
            val full = measurables[1].measure(loose)
            val p = progress.value.coerceIn(0f, 1.1f)
            val h = (one.height + (full.height - one.height) * p).roundToInt().coerceAtLeast(one.height)
            layout(constraints.maxWidth, h) {
                one.placeWithLayer(0, 0) { alpha = (1f - p * 4f).coerceIn(0f, 1f) }
                full.placeWithLayer(0, 0) { alpha = ((p - 0.08f) * 4f).coerceIn(0f, 1f) }
            }
        }
    }

    if (liquidGlass) {
        LiquidGlassSurface(modifier = bubbleModifier, shape = shape, tint = tint, backdrop = backdrop) { Body() }
    } else {
        Box(bubbleModifier.background(Color.Black.copy(alpha = 0.55f))) { Body() }
    }
}

// ─── Post status bubbles ─────────────────────────────────────────────────────

/** A status bubble that grows in when [value] appears (including every time
 *  its post comes on screen) and shrinks smoothly back out when it goes —
 *  still showing its last contents while it does. */
@Composable
private fun <T : Any> RowScope.AnimatedStatus(value: T?, reducedAnimations: Boolean, content: @Composable (T) -> Unit) {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(value != null) { state.targetState = value != null }
    AnimatedVisibility(
        visibleState = state,
        enter = if (reducedAnimations) EnterTransition.None
            else fadeIn(tween(220)) + expandIn(tween(260, easing = FastOutSlowInEasing), expandFrom = Alignment.TopCenter) + scaleIn(tween(260), initialScale = 0.85f),
        exit = if (reducedAnimations) ExitTransition.None
            else fadeOut(tween(180)) + shrinkOut(tween(240, easing = FastOutSlowInEasing), shrinkTowards = Alignment.TopCenter) + scaleOut(tween(240), targetScale = 0.85f)
    ) {
        @Suppress("UNCHECKED_CAST")
        val shown = (value ?: holder[0]) as T?
        if (shown != null) content(shown)
    }
}

@Composable
private fun PostStatusRow(
    translation: TranslationState?,
    taggingLabel: String?,
    blockedByAuthor: Boolean,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    reducedAnimations: Boolean,
    onToggleTranslationView: () -> Unit,
    /** The post was edited in Stellar: an "Edited" bubble that opens its
     *  version history. */
    edited: Boolean = false,
    onOpenEditHistory: () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top
    ) {
        AnimatedStatus(if (edited) "edited" else null, reducedAnimations) {
            StatusPill(
                label = "Edited", spinning = false, liquidGlass = liquidGlass,
                tint = tint, backdrop = backdrop, onClick = onOpenEditHistory,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        AnimatedStatus(if (blockedByAuthor) "blocked" else null, reducedAnimations) {
            StatusPill(
                label = "This user has you blocked", spinning = false, liquidGlass = liquidGlass,
                tint = tint, backdrop = backdrop, onClick = null,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        AnimatedStatus(translation, reducedAnimations) { t ->
            TranslationIndicatorPill(
                state = t, liquidGlass = liquidGlass, dominantColor = tint, backdrop = backdrop,
                onClick = onToggleTranslationView, modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        AnimatedStatus(taggingLabel, reducedAnimations) { label ->
            StatusPill(
                label = label, spinning = true, liquidGlass = liquidGlass,
                tint = tint, backdrop = backdrop, onClick = null,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

// ─── Multi-image posts ────────────────────────────────────────────────────────

/** How many tiles go in each row for [count] images (2 → 2; 3 → 2·1;
 *  4 → 2·2; 5 → 2·2·1; 6 → 2·2·2; 7/8/9 → 3·3·(1/2/3); 10+ → 3·3·3, the
 *  9th showing "+N" for the rest). */
private fun gridRowsFor(count: Int): List<Int> = when {
    count <= 1 -> listOf(1)
    count == 2 -> listOf(2)
    count == 3 -> listOf(2, 1)
    count == 4 -> listOf(2, 2)
    count == 5 -> listOf(2, 2, 1)
    count == 6 -> listOf(2, 2, 2)
    count == 7 -> listOf(3, 3, 1)
    count == 8 -> listOf(3, 3, 2)
    else -> listOf(3, 3, 3)
}

/** Every image of a multi-image post at once: rounded squares with the
 *  author's profile color as their outline, centered in the space between
 *  the author row and the bottom bubbles. */
@Composable
private fun MultiImageGrid(
    images: List<MediaGroupItem>,
    outline: Color,
    blurred: Boolean,
    alpha: Float,
    hiddenIndex: Int?,
    onTileBounds: (Int, Rect) -> Unit,
    onAspect: (Int, Float) -> Unit,
    onTap: (Int) -> Unit
) {
    val context = LocalContext.current
    val count = images.size
    val rows = remember(count) { gridRowsFor(count) }
    val starts = remember(rows) { rows.runningFold(0) { acc, n -> acc + n } }
    val cols = rows.maxOrNull() ?: 1
    val shape = RoundedCornerShape(16.dp)
    val rim = remember(outline) {
        Brush.linearGradient(listOf(lerp(outline, Color.White, 0.35f), outline, lerp(outline, Color.White, 0.2f)))
    }
    // The tiles are sized to fit between the author bubble (top) and the
    // text + interaction bars (bottom), but the group itself sits centered
    // on the screen — only nudged up/down if centering would run it into
    // either of those.
    val topClear = 104.dp
    val bottomClear = 176.dp
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val gap = 8.dp
        val cellW = (maxWidth - gap * (cols - 1)) / cols
        val cellH = (maxHeight - topClear - bottomClear - gap * (rows.size - 1)) / rows.size
        val cell = minOf(cellW, cellH).coerceAtLeast(48.dp)
        val groupH = cell * rows.size + gap * (rows.size - 1)
        val centeredTop = (maxHeight - groupH) / 2
        val maxTop = (maxHeight - bottomClear - groupH).coerceAtLeast(topClear)
        val groupTop = centeredTop.coerceIn(topClear, maxTop)
        Column(
            verticalArrangement = Arrangement.spacedBy(gap),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = groupTop)
                .graphicsLayer { this.alpha = alpha }
                .then(if (blurred) Modifier.blur(90.dp) else Modifier)
        ) {
            rows.forEachIndexed { r, n ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (k in 0 until n) {
                        val i = starts[r] + k
                        val img = images.getOrNull(i) ?: continue
                        Box(
                            Modifier
                                .size(cell)
                                .onGloballyPositioned { onTileBounds(i, it.boundsInRoot()) }
                                .graphicsLayer { this.alpha = if (hiddenIndex == i) 0f else 1f }
                                .clip(shape)
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(2.dp, rim, shape)
                                .quickTap(i) { onTap(i) }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context.coilContext).data(img.thumbUrl.ifBlank { img.mediaUrl }).crossfade(true).build(),
                                contentDescription = img.altText.ifBlank { null },
                                contentScale = ContentScale.Crop,
                                onSuccess = { st ->
                                    val d = st.result.image
                                    if (d.width > 0 && d.height > 0) onAspect(i, d.width.toFloat() / d.height)
                                },
                                modifier = Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(14.dp))
                            )
                            if (i == 8 && count > 9) {
                                Box(
                                    Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("+${count - 9}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The biggest rect of aspect [aspect] (w/h) that fits a [w]×[h] box, centered. */
private fun fitRect(aspect: Float, w: Float, h: Float): Rect {
    val a = if (aspect.isFinite() && aspect > 0f) aspect else 1f
    var fw = w
    var fh = w / a
    if (fh > h) { fh = h; fw = h * a }
    val left = (w - fw) / 2f
    val top = (h - fh) / 2f
    return Rect(left, top, left + fw, top + fh)
}

/** A multi-image post's fullscreen viewer: the picked image zooms from its
 *  grid tile to full screen (uncropped), then the images can be swiped
 *  through. [progress] 0 = in its tile, 1 = fullscreen — the zoom flies a
 *  cropped copy between the tile's rect and the image's fitted rect, so it
 *  lands exactly where the uncropped pager page takes over. */
@Composable
private fun MultiImageViewer(
    images: List<MediaGroupItem>,
    pagerState: PagerState,
    flyPage: Int,
    progress: Float,
    settled: Boolean,
    fromRect: Rect?,
    aspectFor: (Int) -> Float?,
    zoomScale: Float,
    zoomOffset: Offset,
    interactive: Boolean,
    onAspect: (Int, Float) -> Unit,
    onTap: () -> Unit,
    /** Swiping past the last image: on to the next post. */
    onOverscrollNext: () -> Unit = {},
    /** Swiping back past the first image: the previous post. */
    onOverscrollPrev: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var pendingClose by remember { mutableStateOf<Job?>(null) }
    val latestZoom = rememberUpdatedState(zoomScale)
    val latestNext = rememberUpdatedState(onOverscrollNext)
    val latestPrev = rememberUpdatedState(onOverscrollPrev)
    BoxWithConstraints(
        Modifier.fillMaxSize()
            // Scrolling past either end of the images moves on to the
            // next/previous post. Watched passively (Initial pass, nothing
            // consumed) so the pager's own swiping is untouched; only a
            // clearly horizontal drag that starts on the first/last image
            // and pushes further out past it counts.
            .pointerInput(interactive, images.size) {
                if (!interactive) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPage = pagerState.currentPage
                    val atStart = startPage == 0 && abs(pagerState.currentPageOffsetFraction) < 0.02f
                    val atEnd = startPage == images.lastIndex && abs(pagerState.currentPageOffsetFraction) < 0.02f
                    var dx = 0f; var dy = 0f
                    var multi = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } > 1) multi = true
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.positionChange()
                        dx += delta.x; dy += delta.y
                        if (!change.pressed) break
                    }
                    if (multi || latestZoom.value > 1.02f) return@awaitEachGesture
                    val threshold = 72.dp.toPx()
                    if (abs(dx) > threshold && abs(dx) > abs(dy) * 1.3f) {
                        if (dx < 0 && atEnd && pagerState.currentPage == images.lastIndex) latestNext.value()
                        else if (dx > 0 && atStart && pagerState.currentPage == 0) latestPrev.value()
                    }
                }
            }
            // A single tap goes back to the grid — held back briefly so a
            // double-tap (like) doesn't also close it.
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pendingClose?.cancel(); pendingClose = null
                    var moved = false
                    var upAt = -1L
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) moved = true
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (!change.pressed) { upAt = change.uptimeMillis; break }
                    }
                    if (!moved && upAt >= 0 && upAt - down.uptimeMillis < 400L && zoomScale <= 1.02f) {
                        pendingClose = scope.launch { delay(280); onTap() }
                    }
                }
            }
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (settled) 1f else 0f },
            userScrollEnabled = settled && interactive && zoomScale <= 1.02f
        ) { page ->
            val img = images[page]
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    if (page == pagerState.currentPage) {
                        scaleX = zoomScale; scaleY = zoomScale
                        translationX = zoomOffset.x; translationY = zoomOffset.y
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(model = img.thumbUrl.ifBlank { img.mediaUrl }, contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (img.mediaUrl.isNotBlank() && img.mediaUrl != img.thumbUrl) {
                    AsyncImage(
                        model = ImageRequest.Builder(context.coilContext).data(img.mediaUrl).crossfade(true).build(),
                        contentDescription = img.altText.ifBlank { null },
                        contentScale = ContentScale.Fit,
                        onSuccess = { st ->
                            val d = st.result.image
                            if (d.width > 0 && d.height > 0) onAspect(page, d.width.toFloat() / d.height)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        if (!settled && fromRect != null && w > 0f && h > 0f) {
            val page = flyPage.coerceIn(0, images.size - 1)
            val img = images[page]
            val tileAspect = if (fromRect.height > 0f) fromRect.width / fromRect.height else 1f
            val end = fitRect(aspectFor(page) ?: tileAspect, w, h)
            val p = progress.coerceIn(0f, 1.2f)
            val left = fromRect.left + (end.left - fromRect.left) * p
            val top = fromRect.top + (end.top - fromRect.top) * p
            val rw = (fromRect.width + (end.width - fromRect.width) * p).coerceAtLeast(1f)
            val rh = (fromRect.height + (end.height - fromRect.height) * p).coerceAtLeast(1f)
            val corner = (16f * (1f - p.coerceIn(0f, 1f))).dp
            Box(
                Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { rw.toDp() }, with(density) { rh.toDp() })
                    .clip(RoundedCornerShape(corner))
            ) {
                AsyncImage(model = img.thumbUrl.ifBlank { img.mediaUrl }, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (img.mediaUrl.isNotBlank() && img.mediaUrl != img.thumbUrl) {
                    AsyncImage(model = img.mediaUrl, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Replaces the text bubble while a multi-image post's fullscreen viewer is
 *  open: every image as a small square (the current one outlined), tap one
 *  to jump to it or hold and drag along the row to scrub through them. The
 *  arrows at the screen's edges skip to the previous/next post. */
@Composable
private fun ImageSelectorRow(
    images: List<MediaGroupItem>,
    currentPage: Int,
    outline: Color,
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onSelect: (Int, Boolean) -> Unit,
    onPrevPost: () -> Unit,
    onNextPost: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val n = images.size
    val arrowSize = 40.dp
    Row(modifier.padding(horizontal = 6.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        SelectorArrow(Icons.Default.ChevronLeft, "Previous post", liquidGlass, tint, backdrop, arrowSize, onPrevPost)
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            val gap = 5.dp
            val thumb = ((maxWidth - gap * (n - 1)) / n).coerceIn(20.dp, 44.dp)
            val stepPx = with(density) { (thumb + gap).toPx() }
            val latestPage = rememberUpdatedState(currentPage)
            val latestSelect = rememberUpdatedState(onSelect)
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .pointerInput(n, stepPx) {
                        // Tap = jump there; hold (or drag) = scrub along the row.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun indexAt(x: Float) = (x / stepPx).toInt().coerceIn(0, n - 1)
                            var scrubbing = false
                            var last = indexAt(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!scrubbing) latestSelect.value(last, true)
                                    break
                                }
                                val held = change.uptimeMillis - down.uptimeMillis > 220L
                                val dragged = abs(change.position.x - down.position.x) > viewConfiguration.touchSlop
                                if (!scrubbing && (held || dragged)) {
                                    scrubbing = true
                                    if (last != latestPage.value) latestSelect.value(last, false)
                                    haptic(context)
                                }
                                if (scrubbing) {
                                    val i = indexAt(change.position.x)
                                    if (i != last) {
                                        last = i
                                        latestSelect.value(i, false)
                                        haptic(context)
                                    }
                                    change.consume()
                                }
                            }
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                images.forEachIndexed { i, img ->
                    val selected = i == currentPage
                    val thumbScale by animateFloatAsState(if (selected) 1f else 0.86f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "thumbScale")
                    val shape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .size(thumb)
                            .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale; alpha = if (selected) 1f else 0.6f }
                            .clip(shape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) lerp(outline, Color.White, 0.3f) else Color.White.copy(alpha = 0.25f),
                                shape
                            )
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context.coilContext).data(img.thumbUrl.ifBlank { img.mediaUrl }).build(),
                            contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        SelectorArrow(Icons.Default.ChevronRight, "Next post", liquidGlass, tint, backdrop, arrowSize, onNextPost)
    }
}

@Composable
private fun SelectorArrow(
    icon: ImageVector, description: String, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    size: Dp, onClick: () -> Unit
) {
    val m = Modifier.size(size).clip(CircleShape).clickable(onClick = onClick)
    if (liquidGlass) {
        LiquidGlassSurface(modifier = m, shape = CircleShape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    } else {
        Box(m.background(Color.Black.copy(0.55f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(26.dp))
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
    modifier: Modifier, liquidGlass: Boolean, dominantColor: Color, backdrop: GlassBackdrop?,
    // Items 5-8/1: the "More" menu's expanded/collapsed state and its toggle
    // now live in the caller (PostContent) instead of here — see the doc
    // comment on MoreBubbleMenu for why it's rendered as a sibling there
    // rather than nested inside this composable's own clipped surface.
    moreMenuExpanded: Boolean = false,
    onToggleMoreMenu: () -> Unit = {},
    // Bug fix (item 4 — More stack's right edge didn't line up with the
    // bar's own right edge): the caller used to track this bar's position
    // via `onGloballyPositioned` attached to the *outside* of `modifier`,
    // before the 12dp horizontal / 8dp vertical padding this composable
    // then applies internally around the actual glass pill (in the
    // liquidGlass branch only — see below). That reported the row's full,
    // un-padded bounds, 12dp wider on the right than where the visible
    // glass pill actually ends — exactly the gap the More stack's right
    // edge was visibly missing by. Reporting bounds from *inside*, after
    // that padding's been applied, gives the caller the pill's true visible
    // edge to align against instead.
    onVisibleBoundsChanged: (Offset, IntSize) -> Unit = { _, _ -> },
    /** The More button's own bounds (the More bubbles stand on it). */
    onMoreButtonBounds: (Offset, IntSize) -> Unit = { _, _ -> },
    /** The author has blocked you: like / repost / quote are greyed out. */
    interactionsBlocked: Boolean = false
) {
    val blockedTint = Color.White.copy(alpha = 0.28f)
    @Composable
    fun RowContent() {
        if (appMode == AppMode.BLUESKY) {
            // Item 9: blocking collapses the whole bar down to a single
            // centered "Unblock" button — every normal action is hidden
            // while an account is blocked, since none of them apply to a
            // blocked account's posts. Tapping anywhere on the bar unblocks.
            if (item.isBlocked) {
                Box(
                    Modifier.fillMaxSize().clickable(onClick = onBlockAccount),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Unblock", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                return
            }
            // Item 3: with the old upload placeholder removed from this bar
            // (it moved to the Hub's Return to Feed bar — see
            // ReturnToFeedBar/HubUploadBubble in SettingsSheet.kt), every
            // remaining button is just spread evenly across the full row —
            // the same single flat SpaceEvenly Row the e621 branch below
            // already used, now with Like as the first anchor and the new
            // "More" button (item 4) as the last.
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                horizontalArrangement = if (LocalCompactActionRow.current) Arrangement.SpaceBetween else Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                // Toggled-on actions light up in the post author's own
                // profile color (brightened to read on the glass) instead
                // of fixed red/green/yellow — like the rest of the UI.
                val activeTint = vividAccent(dominantColor)
                ActionButton(if (item.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    if (interactionsBlocked) blockedTint else if (item.isLiked) activeTint else Color.White, null, onToggleLike)
                ActionButton(if (item.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    if (item.isBookmarked) activeTint else Color.White, null, onToggleBookmark)
                ActionButton(Icons.Default.Repeat,
                    if (interactionsBlocked) blockedTint else if (item.isReposted) activeTint else Color.White, null, onToggleRepost)
                ActionButton(Icons.Default.EditNote, if (interactionsBlocked) blockedTint else if (item.isQuoteReposted) activeTint else Color.White, null, onQuoteRepost)
                ActionButton(Icons.Default.Download, if (item.isDownloaded) activeTint else Color.White, null, onDownload)
                GifActionButton(onDownloadGif, if (item.isGifDownloaded) activeTint else Color.White)
                ActionButton(Icons.Default.Send, Color.White, null, onShare)
                // Item 6: the hamburger icon flips to an X while the menu is
                // up, and back again once it closes — same button, same
                // tap target, just toggling moreMenuExpanded either way.
                Box(Modifier.onGloballyPositioned { onMoreButtonBounds(it.positionInRoot(), it.size) }) {
                    ActionButton(if (moreMenuExpanded) Icons.Default.Close else Icons.Default.Menu, Color.White, null, onToggleMoreMenu)
                }
            }
        } else {
            // Item 15: matches the AT Protocol bar's layout language — no raw score
            // numbers cluttering the row, everything evenly spaced across the full
            // width instead of packed/scrolling on the left with dead space on the right.
            // Item 10: e621 mode has no upload action, so the bar is just the
            // four remaining buttons spread evenly across the full width.
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                horizontalArrangement = if (LocalCompactActionRow.current) Arrangement.SpaceBetween else Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                val activeTint = vividAccent(dominantColor)
                ActionButton(Icons.Default.ArrowUpward, if (item.e621UserVote == 1) activeTint else Color.White, null) { onE621Vote(1) }
                ActionButton(Icons.Default.ArrowDownward, if (item.e621UserVote == -1) activeTint else Color.White, null) { onE621Vote(-1) }
                ActionButton(if (item.isBookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    if (item.isBookmarked) activeTint else Color.White, null, onToggleBookmark)
                ActionButton(Icons.Default.Download, if (item.isDownloaded) activeTint else Color.White, null, onDownload)
                GifActionButton(onDownloadGif, if (item.isGifDownloaded) activeTint else Color.White)
            }
        }
    }

    val content: @Composable () -> Unit = { RowContent() }
    val boundsModifier = Modifier.onGloballyPositioned { coords ->
        onVisibleBoundsChanged(coords.positionInRoot(), coords.size)
    }

    if (liquidGlass) {
        val shape = RoundedCornerShape(26.dp)
        LiquidGlassSurface(
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp).then(boundsModifier),
            shape = shape, tint = dominantColor, backdrop = backdrop
        ) { content() }
    } else {
        Box(modifier.then(boundsModifier).background(Color.Black.copy(0.55f))) { content() }
    }
}

/** Items 1/4-9: the interaction bar's "More" menu — a hamburger-style icon
 *  (toggling to an X while open, item 6) whose menu is a stack of
 *  individually separate pill bubbles (not one continuous panel like
 *  [GlassDropdownMenu] in GlassTheme.kt, which is still used as-is for the
 *  Hub's unrelated upload button). "Add account to list" opens the app's
 *  existing Add To sheet; "Show more/less like this" sends Bluesky's own
 *  feed-personalization interaction signal (see
 *  MainViewModel.sendShowMoreLikeThis/sendShowLessLikeThis) and only
 *  appears when [supportsFeedInteractions] is true, i.e. the current view
 *  is actually backed by a feed generator that can act on the signal
 *  (item 9); "Block" is the same block/unblock action the old fixed button
 *  used to trigger directly.
 *
 *  Bug fix (items 7/8): this used to be a [Popup]. A Popup opens its own
 *  separate Android window, and `positionInRoot()` for anything inside it
 *  is relative to THAT window — not the main content window the rest of
 *  the UI (and the live backdrop layer's own tracked origin) is measured
 *  in. That mismatch is exactly why the bubbles' live blur never lined up
 *  right (item 7). A focusable Popup also captures every touch anywhere on
 *  screen just to detect outside-taps, which both closed the menu on any
 *  stray tap AND silently ate gestures (like swiping to the next post)
 *  meant for the content underneath (item 8). Rendering this as a plain
 *  sibling composable instead — positioned with a manual offset computed
 *  from the action bar's own tracked root-relative bounds, the same
 *  pattern [PostIndicatorNavButtons] already uses — fixes both: it shares
 *  the same coordinate space as the real backdrop layer (so the blur lines
 *  up), and it has no special touch-interception behavior at all, so
 *  anything outside its own small footprint hits whatever's really there.
 *  A blanket `clickable` with no visual indication on the wrapping Column
 *  absorbs taps that land in the gaps *between* bubbles so those don't
 *  fall through either, without closing the menu — it only closes via
 *  [onDismissRequest] (wired to the X button) or by this whole composable
 *  being disposed, which happens automatically on swiping to another post
 *  or navigating to the hub, comments, or grid.
 *
 *  Item 5: bubbles are twice as tall as before (40dp vs the old 20dp), and
 *  use the exact same 26dp corner radius as the interaction bar itself
 *  (rather than a height-relative stadium radius) so they read as "just as
 *  round" as the bar, not rounder or flatter. */
@Composable
private fun MoreBubbleMenu(
    visible: Boolean,
    anchorOriginRoot: Offset,
    anchorSize: IntSize,
    /** Top of the interaction bar: the bubbles start just above it. */
    barTopRoot: Float,
    containerRootOrigin: Offset,
    onDismissRequest: () -> Unit,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    onShowMoreLikeThis: () -> Unit, onShowLessLikeThis: () -> Unit,
    onAddAccountToList: () -> Unit, onBlock: () -> Unit,
    onReport: () -> Unit = {},
    supportsFeedInteractions: Boolean,
    isOwnPost: Boolean = false,
    onDelete: () -> Unit = {}
) {
    // Delete is two taps: the first turns the bubble red (the menu stays
    // open), the second deletes.
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (!visible) confirmDelete = false }
    val supporter = com.mediaviewer.util.Supporter.active
    val actions = buildList {
        if (isOwnPost) {
            // Edit (supporters): the same pen as editing your own profile.
            // Everyone else sees it in the supporter pink; tapping it opens
            // the Support page.
            add(BubbleAction(
                "Edit",
                iconContent = { m, c ->
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = c, modifier = m.supporterShine(!supporter))
                }
            ) { if (supporter) LocalOverlays.editWarningOpen = true else com.mediaviewer.util.Supporter.openPage() })
            add(BubbleAction(
                if (confirmDelete) "Tap again to delete" else "Delete",
                icon = if (confirmDelete) Icons.Default.DeleteForever else Icons.Default.Delete,
                iconTint = if (confirmDelete) Color(0xFFFF5A7A) else null,
                keepOpen = !confirmDelete
            ) { if (confirmDelete) onDelete() else confirmDelete = true })
        }
        if (supportsFeedInteractions) {
            add(BubbleAction("Show more like this", icon = Icons.Default.ThumbUp) { onShowMoreLikeThis() })
            add(BubbleAction("Show less like this", icon = Icons.Default.ThumbDown) { onShowLessLikeThis() })
        }
        add(BubbleAction("Add account to list", icon = Icons.Filled.PlaylistAdd) { onAddAccountToList() })
        if (!isOwnPost) {
            add(BubbleAction("Report", icon = Icons.Default.Flag) { onReport() })
            add(BubbleAction("Block", icon = Icons.Default.Block) { onBlock() })
        }
    }
    BubbleActionStack(
        visible = visible,
        anchorOriginRoot = Offset(anchorOriginRoot.x, barTopRoot),
        anchorSize = anchorSize,
        containerRootOrigin = containerRootOrigin,
        actions = actions,
        liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
        onDismissRequest = onDismissRequest,
        gapAboveAnchor = 6.dp
    )
}

@Composable
private fun GifActionButton(onClick: () -> Unit, tint: Color = Color.White) {
    val tap = rememberHapticTap()
    Box(
        // No ripple/"square shadow" press effect on the interaction bar.
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { tap(); onClick() })
            .padding(horizontal = if (LocalCompactActionRow.current) ACTION_COMPACT_PAD else 8.dp, vertical = 10.dp)
            .size(width = 30.dp, height = 26.dp),
        contentAlignment = Alignment.Center
    ) {
        // Android only: grayed out elsewhere (a tap explains why).
        val gifModifier = if (com.mediaviewer.platform.PlatformFeature.GIF_EXPORT.isAvailable) Modifier else Modifier.grayedOut()
        Text("GIF", color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = gifModifier)
    }
}

@Composable
private fun ActionButton(icon: ImageVector, tint: Color, label: String? = null, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    val compact = LocalCompactActionRow.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { tap(); onClick() })
            .padding(horizontal = if (compact) ACTION_COMPACT_PAD else 8.dp, vertical = 10.dp)) {
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
    onPlayerReady: (VideoController) -> Unit = {},
    onPlaybackState: (isPlaying: Boolean, positionMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
    onBoundsChanged: (originInRoot: Offset, size: IntSize) -> Unit = { _, _ -> },
    externallyPaused: Boolean = false
) {
    val context = LocalContext.current
    // Instant start: the player comes from FeedVideoPool, which usually
    // already prepared this exact video (playlist parsed, first seconds
    // buffered) while the previous post was on screen — see its doc.
    val player = remember(url) { FeedVideos.acquire(context, url) }
    DisposableEffect(player) { onDispose { FeedVideos.recycle(url, player) } }
    // Item 5: the transport controls are drawn over whatever bounds PlayerView
    // is given — so instead of stretching PlayerView across the whole screen
    // (which spreads controls across empty letterboxed space and lets them
    // overlap the rest of the UI), size it to the video's own aspect ratio and
    // center it, the same way the image posts are fit.
    var aspectRatio by remember(url) {
        mutableStateOf(player.aspectRatio.takeIf { it > 0f } ?: 1f)
    }
    // Only reset via `d > 0` writes below — keeps the last known duration
    // visible instead of flickering back to 0 between polls/readiness checks.
    var lastKnownDuration by remember(url) { mutableStateOf(0L) }
    // Phase 3 "thumbnail instead of black screen" item: images already had a
    // thumb-then-full crossfade (see the sub-image branch above); video never
    // had an equivalent and just showed nothing until the first frame decoded.
    // These two drive a poster-image layer (fades out once real video pixels
    // are on screen) and a small buffering spinner over it.
    var firstFrameRendered by remember(url) { mutableStateOf(false) }
    var isBuffering by remember(url) {
        mutableStateOf(player.isBuffering)
    }

    LaunchedEffect(player) { onPlayerReady(player) }

    DisposableEffect(player) {
        val listener = object : FeedVideoListener {
            override fun onAspectRatio(ratio: Float) {
                aspectRatio = ratio
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                val d = player.duration
                if (d > 0) lastKnownDuration = d
                onPlaybackState(playing, player.currentPosition.coerceAtLeast(0L), lastKnownDuration)
            }
            override fun onFirstFrame() { firstFrameRendered = true }
            override fun onBuffering(buffering: Boolean) {
                isBuffering = buffering
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) { if (!isBlocked && !externallyPaused) player.play() }
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
            FeedVideoView(player, Modifier.matchParentSize())
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
internal fun VideoSeekBar(
    liquidGlass: Boolean,
    dominantColor: Color,
    backdrop: GlassBackdrop?,
    positionMs: Long,
    durationMs: Long,
    onSeeking: (Long) -> Unit,
    onSeekFinish: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    // Item 12: a fully custom track/thumb, replacing Material3's Slider.
    // Slider's built-in thumb reserves extra horizontal touch-target
    // padding on both ends — the reported gaps flanking the visible track
    // inside this bar — always paints a second, lower-alpha "inactive"
    // track spanning the remaining duration, and draws a small
    // stop-indicator dot at the track's far end. This custom version has
    // none of that: the track genuinely spans this bar's full inner width
    // (no reserved end padding), only the watched portion draws anything
    // (no grey inactive segment), and the thumb marks the *current*
    // position rather than a fixed track endpoint (no end dot).
    //
    // Bug fix: the thumb is a vertical bar (matching Slider's own default
    // thumb shape, which this is otherwise a drop-in replacement for) —
    // not the round dot an earlier pass here mistakenly used. And the tap
    // and drag handling below is now a single gesture recognizer instead of
    // two independent `pointerInput` blocks each separately calling
    // `detectTapGestures`/`detectDragGestures` — those were competing to
    // consume the same pointer events, which is what made the bar
    // unreliable to tap or drag. One `awaitEachGesture` loop tracks a
    // finger from first touch to release: it seeks immediately on touch
    // down (covering a plain tap), keeps updating continuously if the
    // finger moves (covering a drag), and commits via `onSeekFinish` once
    // on release either way.
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val committedProgress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val shownProgress = dragProgress ?: committedProgress
    var trackWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    // Bug fix (follow-up — only the grab zone and the thumb should grow,
    // not the whole bar): the visible bar itself is back to its original
    // footprint (20dp content height, 3dp track). The thumb — the "current
    // point" marker the person actually needs to see/grab — is just a
    // little bigger than the original (6dp x 18dp, up from 4dp x 16dp),
    // not the much bigger 10dp x 22dp from the previous pass.
    val thumbWidthPx = with(density) { 6.dp.roundToPx() }

    val barContent: @Composable BoxScope.() -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .onSizeChanged { trackWidthPx = it.width },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shownProgress)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.9f))
            )
            Box(
                Modifier
                    .size(width = 6.dp, height = 18.dp)
                    .offset { IntOffset((shownProgress * trackWidthPx).toInt() - thumbWidthPx / 2, 0) }
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White)
            )
        }
    }
    Box {
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
        // Bug fix (follow-up — bar very difficult to grab): a separate,
        // invisible, genuinely bigger touch target laid on top of the
        // visible bar rather than baked into it — this is what makes the
        // bar easy to grab without changing how big it looks. Its
        // horizontal inset (10dp glass padding + 14dp inner padding = 24dp
        // each side) is deliberately the same inset the visible track above
        // uses, so this box's width always matches `trackWidthPx` — a touch
        // anywhere in it lands on exactly the position it visually lines up
        // with, never off by the difference between two different insets.
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
                .pointerInput(durationMs) {
                    if (durationMs <= 0) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var frac = (down.position.x / size.width).coerceIn(0f, 1f)
                        dragProgress = frac
                        onSeeking((frac * durationMs).toLong())
                        var pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull() ?: break
                            pointerId = change.id
                            if (!change.pressed) break
                            change.consume()
                            frac = (change.position.x / size.width).coerceIn(0f, 1f)
                            dragProgress = frac
                            onSeeking((frac * durationMs).toLong())
                        }
                        onSeekFinish()
                        dragProgress = null
                    }
                }
        )
    }
}

/** Play/pause + ±10s skip as three separate, bigger glass "bubble" buttons in
 *  the middle of the video, instead of sharing one bar with the seek bar. */
@Composable
internal fun VideoTransportButtons(
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
    val tap = rememberHapticTap()
    val shape = CircleShape
    if (liquidGlass) {
        LiquidGlassSurface(
            modifier = Modifier.size(size).clip(shape).clickable(onClick = { tap(); onClick() }),
            shape = shape, tint = dominantColor, backdrop = backdrop
        ) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
            }
        }
    } else {
        Box(
            Modifier.size(size).clip(shape).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = { tap(); onClick() }),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
        }
    }
}

private fun haptic(context: PlatformContext) {
    try {
        com.mediaviewer.ui.compat.vibrateOneShot(context, 38)
    } catch (_: Exception) {}
}

/** Landscape: the interaction bar's buttons sit close together (see
 *  [landscapeClusterWidth]); portrait spreads them across the full width. */
private val LocalCompactActionRow = androidx.compose.runtime.staticCompositionLocalOf { false }
private val ACTION_COMPACT_PAD = 5.dp

/** Landscape bottom cluster width: just enough for the interaction bar's
 *  buttons packed close (text bubble and visualizer match it). */
private fun landscapeClusterWidth(appMode: AppMode, liquidGlass: Boolean): Dp {
    val iconButton = 26.dp + ACTION_COMPACT_PAD * 2
    val gifButton = 30.dp + ACTION_COMPACT_PAD * 2
    val (icons, gifs) = if (appMode == AppMode.BLUESKY) 7 to 1 else 4 to 1
    val buttons = iconButton * icons + gifButton * gifs
    val gaps = 4.dp * (icons + gifs - 1)
    return buttons + gaps + 20.dp + (if (liquidGlass) 24.dp else 0.dp)
}

/** Landscape: the post's UI drawn smaller (everything scaled by the same
 *  factor, so it stays proportional) to leave the picture more room. */
@Composable
private fun LandscapeChromeScale(landscape: Boolean, content: @Composable () -> Unit) {
    if (!landscape) { content(); return }
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * 0.78f, d.fontScale),
        LocalCompactActionRow provides true,
        content = content
    )
}

/** Landscape side margin: the camera cutout's width, applied to BOTH sides
 *  so the UI sits evenly (and clear of rounded screen corners) whichever
 *  way the phone is turned. */
@Composable
private fun Modifier.landscapeSideSafe(): Modifier {
    val cutout = WindowInsets.displayCutout
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
    val side = with(density) {
        maxOf(cutout.getLeft(this, dir), cutout.getRight(this, dir)).toDp()
    }.coerceAtLeast(16.dp)
    return this.padding(horizontal = side)
}

/** Landscape's round fullscreen button (bottom right). */
@Composable
private fun LandscapeFullscreenButton(
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    onClick: () -> Unit, modifier: Modifier = Modifier
) {
    val tap = rememberHapticTap()
    val m = modifier.size(46.dp).clip(CircleShape).clickable { tap(); onClick() }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = m, shape = CircleShape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    } else {
        Box(m.background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}
