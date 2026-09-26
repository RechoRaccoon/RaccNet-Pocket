package com.mediaviewer.ui

import com.mediaviewer.util.rememberHapticTap
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import com.mediaviewer.model.AppMode
import com.mediaviewer.model.BskyFeedInfo
import com.mediaviewer.model.DownloadProgress
import com.mediaviewer.ui.theme.*
import com.mediaviewer.viewmodel.MainViewModel

// Item 5: which panel of the Hub is currently showing. This is purely local
// UI state for the sheet itself — separate from `appMode`, which tracks
// which content mode (Bluesky vs e621) is actually active for the feed
// behind the sheet. SETTINGS has no corresponding AppMode; AT_PROTOCOL/E621
// correspond to AppMode.BLUESKY/AppMode.E621, but bug fix (per feedback):
// merely browsing to the AT Protocol or e621 Hub page does NOT call
// onSwitchMode anymore — it used to, which meant just landing on (or
// accidentally swiping past) the e621 page immediately switched the active
// feed and triggered a load/refresh even if the user never actually swiped
// up into the feed itself. onSwitchMode is now only called from the
// swipe-up-to-feed handler below, at the moment the user actually leaves
// the Hub for the feed, based on whichever Hub page they're leaving from.
private enum class HubPage { SETTINGS, MAIN }

@Composable
fun SettingsSheet(
    appMode: AppMode,
    // Feature (this session): drives the Hub's Return to Feed button's
    // label — see MainViewModel.hasVisitedFeed's doc comment for why this
    // is tracked centrally in the ViewModel rather than as local state
    // here (this whole sheet gets torn down and rebuilt across Hub/feed
    // screen switches, so any state kept only in this composable would
    // reset on every round-trip).
    hasVisitedFeed: Boolean,
    bskyLoggedIn: Boolean,
    e621LoggedIn: Boolean,
    bskyHandle: String,
    e621Username: String,
    availableFeeds: List<BskyFeedInfo>,
    selectedFeedUri: String?,
    authorFeedState: MainViewModel.AuthorFeedSavedState?,
    downloadOnLike: Boolean,
    downloadProgress: DownloadProgress?,
    reducedAnimations: Boolean,
    classicProfileTabRow: Boolean = false,
    onToggleClassicProfileTabRow: (Boolean) -> Unit = {},
    pinterestThreeColumns: Boolean = false,
    onTogglePinterestThreeColumns: (Boolean) -> Unit = {},
    hateFunBlurNsfw: Boolean = false,
    onToggleHateFunBlurNsfw: (Boolean) -> Unit = {},
    // Fix (per feedback): "Rounded grid tiles" — off by default (flat
    // square tiles with no outline in the profile square grid).
    squareGridRounded: Boolean = false,
    onToggleSquareGridRounded: (Boolean) -> Unit = {},
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
    // Item 26: 0f..1f blur/magnify strength dial, only meaningful while
    // liquidGlass (above) is on.
    liquidGlassIntensity: Float = 1f,
    onSetLiquidGlassIntensity: (Float) -> Unit = {},
    // Bug fix: independent rim/outline strength dial, split out from the
    // background dial above.
    glassRimIntensity: Float = 1f,
    onSetGlassRimIntensity: (Float) -> Unit = {},
    glassRimVibrantSecondary: Boolean = true,
    onToggleGlassRimVibrantSecondary: (Boolean) -> Unit = {},
    combineListsAndPacks: Boolean,
    e621SearchTags: String,
    isLoading: Boolean,
    onLoginBluesky: (String, String) -> Unit,
    onLogoutBluesky: () -> Unit,
    onSaveE621Credentials: (String, String) -> Unit,
    onLogoutE621: () -> Unit,
    onSelectFeed: (String?) -> Unit,
    onToggleDownloadOnLike: (Boolean) -> Unit,
    onDownloadAllLiked: () -> Unit,
    onCancelDownload: () -> Unit,
    // AI Tagging feature
    tagPostWhenLiked: Boolean,
    onToggleTagPostWhenLiked: (Boolean) -> Unit,
    taggingRunning: Boolean,
    taggingScanned: Int,
    taggingTagged: Int,
    onLocallyTagAllLiked: () -> Unit,
    onDeleteTaggedDatabase: () -> Unit = {},
    // Import/Export (item 4)
    importedDatasets: List<com.mediaviewer.tagging.TagDatabase.DatasetInfo> = emptyList(),
    onExportDataset: (String, android.net.Uri) -> Unit = { _, _ -> },
    onImportDataset: (android.net.Uri) -> Unit = {},
    onDeleteImportedDataset: (String) -> Unit = {},
    onShowLikes: () -> Unit,
    onShowFriends: () -> Unit,
    onShowE621Following: () -> Unit,
    onToggleReducedAnimations: (Boolean) -> Unit,
    onToggleCombineListsPacks: (Boolean) -> Unit,
    autoAddToOnFollow: Boolean,
    onToggleAutoAddToOnFollow: (Boolean) -> Unit,
    onSearchE621: (String) -> Unit,
    onShowE621Favorites: () -> Unit,
    onSwitchMode: (AppMode) -> Unit,
    onSwipeToFeed: () -> Unit,
    // Settings Update
    selfProfile: com.mediaviewer.model.ProfileData?,
    hideTextOnlyPosts: Boolean,
    onToggleHideTextOnlyPosts: (Boolean) -> Unit,
    onOpenOwnProfile: () -> Unit,
    onShowSaves: () -> Unit,
    onShowHistory: () -> Unit,
    onOpenDmInbox: () -> Unit,
    // Upload flow: the Hub's "+" -> "Post" bubble opens the Bluesky post
    // composer (see ComposePostScreen.kt). Default no-op keeps every other
    // existing call site of SettingsSheet compiling unchanged.
    onOpenComposePost: () -> Unit = {},
    // Item 7
    onOpenSearch: () -> Unit = {},
    // Phase 4 — on-device translation
    translationEnabled: Boolean = false,
    translationTargetLang: String = "en",
    onToggleTranslation: (Boolean) -> Unit = {},
    onSelectTranslationLanguage: (String) -> Unit = {},
    // Phase 4 — custom app-wide font pack
    customFontName: String? = null,
    onPickFontFile: (android.net.Uri) -> Unit = {},
    onResetFont: () -> Unit = {},
    // Item 1 (Phase 3): the post the user was last looking at, so Settings'
    // glass rims pick up its color the same way the in-post glass buttons do.
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    // Item 8: Friends section (Profiles/Reviews sub-tabs).
    dmConversations: List<com.mediaviewer.model.DmConversation> = emptyList(),
    dmConversationsLoading: Boolean = false,
    friendsReviews: List<com.mediaviewer.model.FriendPopfeedReview> = emptyList(),
    friendsReviewsLoading: Boolean = false,
    onLoadFriendsReviews: () -> Unit = {},
    onOpenProfile: (com.mediaviewer.model.AuthorInfo) -> Unit = {},
    onOpenReview: (com.mediaviewer.model.FriendPopfeedReview) -> Unit = {},
    // Item 8/19: Livestreams section.
    liveFriends: List<com.mediaviewer.model.StreamplaceLiveStream> = emptyList(),
    liveFriendsLoading: Boolean = false,
    onLoadLiveFriends: () -> Unit = {},
    blueskyLiveNow: List<com.mediaviewer.model.BlueskyLiveNowStream> = emptyList(),
    blueskyLiveNowLoading: Boolean = false,
    onLoadBlueskyLiveNow: () -> Unit = {},
    onOpenLivePlayer: (String, String, String) -> Unit = { _, _, _ -> },
    // Bug fix (this session): lets the Hub's AT Protocol page trigger a
    // Mutuals (dmConversations) load/retry itself on compose, the same way
    // it already does for friendsReviews/liveFriends — see the matching
    // comment on AtProtocolPageContent's LaunchedEffect below.
    onEnsureFriends: () -> Unit = {},
    // Hub Blogs section — mirrors Reviews above.
    friendsBlogs: List<com.mediaviewer.model.FriendLeafletBlog> = emptyList(),
    onOpenBlog: (com.mediaviewer.model.FriendLeafletBlog) -> Unit = {},
    // Item (this session): Hub refresh bubble.
    onRefreshHub: () -> Unit = {},
    // Feature (this session): the logged-in user's own avatar URL, so the
    // Hub's rims/background can reflect the user's own profile color
    // instead of whatever post they were last looking at (see below).
    selfAvatarUrl: String? = null,
    // Live Link widget feature: saved Twitch/YouTube channel URLs (Settings
    // input), the live state shared with the widget/periodic worker
    // (LiveLinkManager/PreferencesManager are the actual source of truth —
    // these are just read-outs of it for this composition), and the three
    // actions every one of the widget/Settings/Hub-row surfaces funnels
    // through the exact same way.
    liveTwitchUrl: String? = null,
    liveYoutubeUrl: String? = null,
    liveActivePlatform: com.mediaviewer.model.LiveNowPlatform? = null,
    onSaveLiveTwitchUrl: (String) -> Unit = {},
    onSaveLiveYoutubeUrl: (String) -> Unit = {},
    onCreateLiveLinkWidget: () -> Unit = {},
    onToggleLiveLink: (com.mediaviewer.model.LiveNowPlatform) -> Unit = {},
    onEndLiveLink: () -> Unit = {},
    // Reworked Settings page: multiple accounts, tagging-model download and
    // the e621 download button — see SettingsExtras.
    settingsExtras: SettingsExtras = SettingsExtras()
) {
    // Feature (this session): every rim/background tint throughout the Hub
    // (all three pages — Settings/AT Protocol/e621 — plus the background
    // gradient and the page-switcher chips at the top) used to reflect the
    // currently-viewed POST's dominant color, inherited from the same
    // `dominantColor` the feed/Grid/Comments screens use. Per feedback, the
    // Hub should instead reflect the logged-in user's OWN profile picture —
    // the same idea DmInboxOverlay already applies to "your" message
    // bubbles via `selfAvatarUrl` (see its `myTint`). Shadowing the
    // `dominantColor` parameter here, once, is what actually makes this
    // apply everywhere: every one of this file's `tint = dominantColor` /
    // `panelTint = dominantColor` call sites (in this composable and in the
    // three page-content composables it calls, which all just receive
    // whatever's passed in under that same parameter name) automatically
    // picks up the profile color with no per-call-site changes needed, and
    // no risk of missing one across a file this size. Falls back to the
    // post color if there's no avatar yet (e.g. profile hasn't loaded).
    val dominantColor = selfAvatarUrl?.let { rememberDominantColor(it) } ?: dominantColor
    var hubPage by remember { mutableStateOf(HubPage.MAIN) }
    // Settings/Credits switch at the right end of the bottom bar — only
    // meaningful while the Settings page is showing, and always starts on
    // Settings.
    var settingsTab by remember { mutableStateOf(SettingsTab.SETTINGS) }
    LaunchedEffect(hubPage) { if (hubPage != HubPage.SETTINGS) settingsTab = SettingsTab.SETTINGS }
    // Tracks the direction of the most recent page change (Settings <-> Main
    // via the More button / its own back action).
    var hubPageForward by remember { mutableStateOf(true) }
    val hubPages = remember { listOf(HubPage.SETTINGS, HubPage.MAIN) }
    // Bug fix (per feedback): this used to also call onSwitchMode(...) here,
    // meaning just navigating to (or swiping past) the AT Protocol/e621 Hub
    // page immediately flipped the active feed and triggered a load/refresh
    // — even if the user was just passing through and never actually
    // swiped up into that feed. Now this purely changes which Hub page is
    // showing; see onReturnToFeed below for where the actual mode switch
    // now happens.
    fun goToHubPage(target: HubPage, forward: Boolean = hubPages.indexOf(target) >= hubPages.indexOf(hubPage)) {
        hubPageForward = forward
        hubPage = target
    }
    // Item 14: the Hub is one page now (Settings/AT Protocol/e621 chips are
    // gone — e621's own navigation folded into this page, its login moved
    // to Settings, see AtProtocolPageContent/SettingsPageContent), so
    // there's only one feed mode a generic "Return to Feed" tap can mean
    // anymore: Bluesky. e621 Hot/Favorites/Following are self-contained
    // now — each one switches mode and jumps straight to the feed itself,
    // rather than deferring to this button (see AtProtocolPageContent's
    // onOpenE621* handlers).
    fun onReturnToFeed() {
        if (hubPage == HubPage.MAIN) onSwitchMode(AppMode.BLUESKY)
        onSwipeToFeed()
    }

    // Bug fix (item 5 — Hub upload bubbles need a genuine "cutout" look):
    // a dedicated layer that paints — and, when liquidGlass, live-records
    // into its own GraphicsLayer — *only* this plain background gradient,
    // with nothing else ever drawn into it. Every other bit of the Hub
    // (chips, cards, the scrollable page content) is a sibling drawn on top
    // of this, never inside it, so this layer's recorded pixels are always
    // just the flat gradient alone, never whatever happens to be visually
    // on top of it at that spot on screen. Sampling this as a GlassBackdrop
    // (see hubBackgroundBackdrop below) is what gives the new upload bubble
    // stack (HubUploadBubble) an actual "hole punched through to the
    // background" look even where it visually overlaps a card, instead of
    // just a translucent tint over that card's own sharp pixels — the same
    // effect ReturnToFeedBar gets "for free" by sitting below all the
    // scrollable content instead, where there's simply nothing else to
    // punch through in the first place.
    val hubBackgroundLayer = rememberGraphicsLayer()
    var hubBackgroundOrigin by remember { mutableStateOf(Offset.Zero) }
    val hubBackgroundBackdrop = remember(liquidGlass, hubBackgroundLayer) {
        if (liquidGlass) GlassBackdrop(hubBackgroundLayer) { hubBackgroundOrigin } else null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    // Item 3/11: the Hub's background gradient now reflects the
                    // currently-viewed post's own dominant color, same as the
                    // main feed's post background gradient, instead of a
                    // hardcoded neutral tint.
                    if (liquidGlass) Modifier
                        .background(postBackgroundBrush(dominantColor))
                        .onGloballyPositioned { hubBackgroundOrigin = it.positionInRoot() }
                        .drawWithContent {
                            hubBackgroundLayer.record { this@drawWithContent.drawContent() }
                            drawContent()
                        }
                    else Modifier.background(OledBlack)
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = rememberTopCutoutClearance()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The main page's search bar sits right under the camera cutout
            // (the same line profile banners start on); Settings keeps a gap.
            if (hubPage == HubPage.SETTINGS) Spacer(Modifier.height(8.dp))

            // Item 14: the Settings/AT Protocol/e621 chip row is gone — the
            // Hub is a single page now (this Column's own scroll content
            // starts with the search bar, per item 14's "search bar will
            // now be at the top"), reached by default, with Settings
            // reachable only via the new HubSettingsButton at the bottom (see
            // ReturnToFeedBar) instead of a top-level chip.

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = hubPage,
                    transitionSpec = {
                        val dir = if (hubPageForward) 1 else -1
                        (slideInHorizontally(animationSpec = tween(220)) { w -> dir * w })
                            .togetherWith(slideOutHorizontally(animationSpec = tween(220)) { w -> -dir * w })
                    },
                    label = "hubPage"
                ) { page ->
                    when (page) {
                        HubPage.SETTINGS -> Crossfade(
                            targetState = settingsTab,
                            animationSpec = tween(if (reducedAnimations) 0 else 200),
                            label = "settingsTab"
                        ) { tab ->
                            when (tab) {
                                SettingsTab.CREDITS -> CreditsPageContent()
                                SettingsTab.SETTINGS -> SettingsPageContent(
                                    reducedAnimations = reducedAnimations, onToggleReducedAnimations = onToggleReducedAnimations,
                                    hateFunBlurNsfw = hateFunBlurNsfw, onToggleHateFunBlurNsfw = onToggleHateFunBlurNsfw,
                                    squareGridRounded = squareGridRounded, onToggleSquareGridRounded = onToggleSquareGridRounded,
                                    followerScanState = followerScanState, onRescanFollowersFromScratch = onRescanFollowersFromScratch,
                                    hideTextOnlyPosts = hideTextOnlyPosts, onToggleHideTextOnlyPosts = onToggleHideTextOnlyPosts,
                                    liquidGlass = liquidGlass, onToggleLiquidGlass = onToggleLiquidGlass,
                                    liquidGlassIntensity = liquidGlassIntensity, onSetLiquidGlassIntensity = onSetLiquidGlassIntensity,
                                    glassRimIntensity = glassRimIntensity, onSetGlassRimIntensity = onSetGlassRimIntensity,
                                    glassRimVibrantSecondary = glassRimVibrantSecondary, onToggleGlassRimVibrantSecondary = onToggleGlassRimVibrantSecondary,
                                    translationEnabled = translationEnabled, translationTargetLang = translationTargetLang,
                                    onToggleTranslation = onToggleTranslation, onSelectTranslationLanguage = onSelectTranslationLanguage,
                                    customFontName = customFontName, onPickFontFile = onPickFontFile, onResetFont = onResetFont,
                                    bskyLoggedIn = bskyLoggedIn, bskyHandle = bskyHandle,
                                    isLoading = isLoading, onLoginBluesky = onLoginBluesky, onLogoutBluesky = onLogoutBluesky,
                                    e621LoggedIn = e621LoggedIn, e621Username = e621Username,
                                    onLoginE621 = { user, key -> onSaveE621Credentials(user, key) }, onLogoutE621 = onLogoutE621,
                                    downloadOnLike = downloadOnLike, onToggleDownloadOnLike = onToggleDownloadOnLike,
                                    downloadProgress = downloadProgress, onDownloadAllLiked = onDownloadAllLiked, onCancelDownload = onCancelDownload,
                                    tagPostWhenLiked = tagPostWhenLiked, onToggleTagPostWhenLiked = onToggleTagPostWhenLiked,
                                    taggingRunning = taggingRunning, taggingScanned = taggingScanned, taggingTagged = taggingTagged,
                                    onLocallyTagAllLiked = onLocallyTagAllLiked,
                                    onDeleteTaggedDatabase = onDeleteTaggedDatabase,
                                    importedDatasets = importedDatasets,
                                    onExportDataset = onExportDataset, onImportDataset = onImportDataset,
                                    onDeleteImportedDataset = onDeleteImportedDataset,
                                    combineListsAndPacks = combineListsAndPacks, onToggleCombineListsPacks = onToggleCombineListsPacks,
                                    autoAddToOnFollow = autoAddToOnFollow, onToggleAutoAddToOnFollow = onToggleAutoAddToOnFollow,
                                    extras = settingsExtras,
                                    dominantColor = dominantColor, backdrop = backdrop,
                                    liveTwitchUrl = liveTwitchUrl, liveYoutubeUrl = liveYoutubeUrl,
                                    onSaveLiveTwitchUrl = onSaveLiveTwitchUrl, onSaveLiveYoutubeUrl = onSaveLiveYoutubeUrl,
                                    onCreateLiveLinkWidget = onCreateLiveLinkWidget
                                )
                            }
                        }
                        HubPage.MAIN -> AtProtocolPageContent(
                            bskyLoggedIn = bskyLoggedIn, bskyHandle = bskyHandle,
                            availableFeeds = availableFeeds, selectedFeedUri = selectedFeedUri, authorFeedState = authorFeedState,
                            onShowLikes = onShowLikes, onShowFriends = onShowFriends,
                            selfProfile = selfProfile, onOpenOwnProfile = onOpenOwnProfile,
                            onShowSaves = onShowSaves, onShowHistory = onShowHistory, onOpenDmInbox = onOpenDmInbox,
                            onSelectFeed = onSelectFeed, isLoading = isLoading,
                            onLoginBluesky = onLoginBluesky, onOpenSearch = onOpenSearch,
                            liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = backdrop,
                            dmConversations = dmConversations, dmConversationsLoading = dmConversationsLoading,
                            friendsReviews = friendsReviews,
                            friendsReviewsLoading = friendsReviewsLoading, onLoadFriendsReviews = onLoadFriendsReviews,
                            onOpenProfile = onOpenProfile, onOpenReview = onOpenReview,
                            liveFriends = liveFriends, liveFriendsLoading = liveFriendsLoading,
                            onLoadLiveFriends = onLoadLiveFriends,
                            blueskyLiveNow = blueskyLiveNow, blueskyLiveNowLoading = blueskyLiveNowLoading,
                            onLoadBlueskyLiveNow = onLoadBlueskyLiveNow, onOpenLivePlayer = onOpenLivePlayer,
                            onEnsureFriends = onEnsureFriends,
                            friendsBlogs = friendsBlogs, onOpenBlog = onOpenBlog, selfDid = selfDid,
                            subscribedReviewDids = subscribedReviewDids, subscribedBlogDids = subscribedBlogDids,
                            followerScanState = followerScanState, followerScanCompletedOnce = followerScanCompletedOnce,
                            onStartFollowerScan = onStartFollowerScan, onDismissFollowerScanResult = onDismissFollowerScanResult,
                            onRefreshHub = onRefreshHub,
                            onReturnToFeed = { onReturnToFeed() },
                            hasVisitedFeed = hasVisitedFeed,
                            liveTwitchUrl = liveTwitchUrl, liveYoutubeUrl = liveYoutubeUrl,
                            liveActivePlatform = liveActivePlatform,
                            onToggleLiveLink = onToggleLiveLink, onEndLiveLink = onEndLiveLink,
                            // Item 14: e621's Hot/Favorites/Following quick
                            // buttons folded in here (were the standalone
                            // e621 page's whole reason to exist) — shown
                            // only once logged in, and each one now jumps
                            // straight to the feed itself (switching
                            // AppMode.E621 first) instead of relying on a
                            // separate page-aware "Return to Feed" tap, now
                            // that there's only one such button left and it
                            // always means Bluesky (see onReturnToFeed
                            // above).
                            otherAccounts = settingsExtras.otherBskyAccounts,
                            showSwitchAccountsRow = settingsExtras.showSwitchAccountsRow,
                            onSwitchAccount = settingsExtras.onSwitchBskyAccount,
                            e621LoggedIn = e621LoggedIn, e621SearchTags = e621SearchTags,
                            onOpenE621Hot = { onSwitchMode(AppMode.E621); onSearchE621("order:hot"); onSwipeToFeed() },
                            onOpenE621Search = { tags -> onSwitchMode(AppMode.E621); onSearchE621(tags); onSwipeToFeed() },
                            onOpenE621Favorites = { onSwitchMode(AppMode.E621); onShowE621Favorites(); onSwipeToFeed() },
                            onOpenE621Following = { onSwitchMode(AppMode.E621); onShowE621Following(); onSwipeToFeed() }
                        )
                    }
                }
            }

            // Item 11: the Refresh/Return to Feed/Upload bar now renders
            // once, here — outside and below the scrollable per-page
            // content (the `weight(1f)` Box above) — instead of being
            // pinned as an overlay layer on top of it inside each page.
            // This Column has no scroll container of its own, so this bar
            // sits on the exact same plain, un-scrolling background
            // gradient the "Created by Recho Raccoon" credit right below it
            // does — nothing can ever scroll behind it, which is why the
            // old opaque-backing workaround inside ReturnToFeedBar/
            // HubRefreshBubble/HubUploadBubble is gone too (see their own
            // comments). Hidden on the Settings page, same as before (it
            // has no corresponding feed mode to return to), and only shown
            // for a page once its account is actually logged in — matching
            // exactly what AtProtocolPageContent/E621PageContent used to
            // gate on internally.
            val showReturnBar = when (hubPage) {
                HubPage.MAIN -> bskyLoggedIn
                HubPage.SETTINGS -> false
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 6.dp)) {
                ReturnToFeedBar(
                    liquidGlass = liquidGlass, tint = dominantColor, backdrop = null,
                    uploadBackdrop = hubBackgroundBackdrop,
                    onReturnToFeed = { onReturnToFeed() },
                    onOpenSettings = { goToHubPage(if (hubPage == HubPage.SETTINGS) HubPage.MAIN else HubPage.SETTINGS) },
                    // Fix (per feedback): the More popup's Refresh bubble
                    // must always appear — ReturnToFeedBar only feeds this
                    // through to HubSettingsButton, so pass it unconditionally
                    // instead of nulling it on the Settings page.
                    onRefresh = onRefreshHub,
                    showPillAndUpload = showReturnBar,
                    hasVisitedFeed = hasVisitedFeed,
                    onOpenComposePost = onOpenComposePost,
                    settingsOpen = (hubPage == HubPage.SETTINGS),
                    settingsTab = settingsTab,
                    onSettingsTabChange = { settingsTab = it }
                )
            }

            Text(
                buildAnnotatedString {
                    append("Created by ")
                    withStyle(SpanStyle(color = Color(0xFF00FF07))) { append("Recho Raccoon") }
                },
                color = DimGray, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 20.dp, top = 2.dp)
            )
        }
    }
}

// ── AT Protocol page: login form, feed row, quick-access buttons, and every
// Bluesky-specific setting — item 5. ──────────────────────────────────────
@Composable
private fun AtProtocolPageContent(
    bskyLoggedIn: Boolean,
    bskyHandle: String,
    availableFeeds: List<BskyFeedInfo>,
    selectedFeedUri: String?,
    authorFeedState: MainViewModel.AuthorFeedSavedState?,
    onShowLikes: () -> Unit,
    onShowFriends: () -> Unit,
    selfProfile: com.mediaviewer.model.ProfileData?,
    onOpenOwnProfile: () -> Unit,
    onShowSaves: () -> Unit,
    onShowHistory: () -> Unit,
    onOpenDmInbox: () -> Unit,
    onSelectFeed: (String?) -> Unit,
    isLoading: Boolean,
    onLoginBluesky: (String, String) -> Unit,
    onOpenSearch: () -> Unit,
    liquidGlass: Boolean,
    dominantColor: Color,
    backdrop: GlassBackdrop?,
    // Item 8: Friends section (Profiles/Reviews sub-tabs).
    dmConversations: List<com.mediaviewer.model.DmConversation> = emptyList(),
    dmConversationsLoading: Boolean = false,
    friendsReviews: List<com.mediaviewer.model.FriendPopfeedReview> = emptyList(),
    friendsReviewsLoading: Boolean = false,
    onLoadFriendsReviews: () -> Unit = {},
    onOpenProfile: (com.mediaviewer.model.AuthorInfo) -> Unit = {},
    onOpenReview: (com.mediaviewer.model.FriendPopfeedReview) -> Unit = {},
    // Feature: auto-subscribe — the signed-in user is now auto-subscribed
    // to their own Reviews/Blogs too (so their reviews show up on a
    // title's page alongside everyone else's), but that means
    // friendsReviews/friendsBlogs can contain the user's own entries. Used
    // below to filter those back out of just the Hub preview rows, which
    // are meant to be "what your friends posted", not "what you posted".
    selfDid: String = "",
    subscribedReviewDids: Set<String> = emptySet(),
    subscribedBlogDids: Set<String> = emptySet(),
    followerScanState: MainViewModel.FollowerScanState = MainViewModel.FollowerScanState.Idle,
    followerScanCompletedOnce: Boolean = false,
    onStartFollowerScan: () -> Unit = {},
    onDismissFollowerScanResult: () -> Unit = {},
    // Item 8/19: Livestreams section.
    liveFriends: List<com.mediaviewer.model.StreamplaceLiveStream> = emptyList(),
    liveFriendsLoading: Boolean = false,
    onLoadLiveFriends: () -> Unit = {},
    blueskyLiveNow: List<com.mediaviewer.model.BlueskyLiveNowStream> = emptyList(),
    blueskyLiveNowLoading: Boolean = false,
    onLoadBlueskyLiveNow: () -> Unit = {},
    // Item (this session): generic over both Live sources — see
    // MainViewModel.PlayingLiveStream.
    onOpenLivePlayer: (String, String, String) -> Unit = { _, _, _ -> },
    onEnsureFriends: () -> Unit = {},
    // Hub Blogs section — mirrors Reviews.
    friendsBlogs: List<com.mediaviewer.model.FriendLeafletBlog> = emptyList(),
    onOpenBlog: (com.mediaviewer.model.FriendLeafletBlog) -> Unit = {},
    // Item (this session): Hub refresh bubble.
    onRefreshHub: () -> Unit = {},
    // Item (this session): replaces the removed swipe-up-to-feed gesture.
    onReturnToFeed: () -> Unit = {},
    hasVisitedFeed: Boolean = false,
    // Live Link widget feature: mirrors the widget's own toggle as the very
    // bottom row of this page, per the feature request — only rendered once
    // at least one channel URL is saved (see the bottom of this function).
    liveTwitchUrl: String? = null,
    liveYoutubeUrl: String? = null,
    liveActivePlatform: com.mediaviewer.model.LiveNowPlatform? = null,
    onToggleLiveLink: (com.mediaviewer.model.LiveNowPlatform) -> Unit = {},
    onEndLiveLink: () -> Unit = {},
    // Item 14: e621's Hot/Favorites/Following/tag-search quick access,
    // folded in here from the removed standalone e621 page — shown only
    // once logged in (login itself now lives in Settings).
    // Multiple accounts: every other signed-in AT Protocol account, for the
    // "Switch Accounts" row at the very bottom of this page.
    otherAccounts: List<com.mediaviewer.util.StoredBskyAccount> = emptyList(),
    showSwitchAccountsRow: Boolean = true,
    onSwitchAccount: (String) -> Unit = {},
    e621LoggedIn: Boolean = false,
    e621SearchTags: String = "",
    onOpenE621Hot: () -> Unit = {},
    onOpenE621Search: (String) -> Unit = {},
    onOpenE621Favorites: () -> Unit = {},
    onOpenE621Following: () -> Unit = {}
) {
    // Item 8: both of the new sections' fetches are lazy — kick them off once
    // when this page first composes rather than eagerly for every Hub visit
    // (they only matter once the person actually scrolls down to them, and
    // both no-op internally if already loading/loaded).
    // Bug fix (this session): the Mutuals avatar row used to rely entirely
    // on the app-launch background prefetch succeeding, with no retry if it
    // silently failed (see MainViewModel.ensureDmConversationsLoaded's
    // comment for the actual root cause) — onEnsureFriends() here gives it
    // the same "retry on every Hub visit if not loaded yet" self-healing
    // onLoadFriendsReviews/onLoadLiveFriends already had.
    LaunchedEffect(Unit) {
        onLoadFriendsReviews()
        onLoadLiveFriends()
        onLoadBlueskyLiveNow()
        onEnsureFriends()
    }
    if (!bskyLoggedIn) {
        // Bug fix (this session): this used to be the first child of a
        // Modifier.verticalScroll(...) Column below — a scrollable parent
        // measures its child with an unbounded height, so the child's own
        // fillMaxSize()+Arrangement.Center had no finite height to center
        // within and just wrapped to the top of the content instead. This
        // screen has nothing to scroll (two fields + a button), so it gets
        // its own non-scrolling, fillMaxSize Column instead, which centering
        // actually works inside of.
        var bskyId by remember { mutableStateOf("") }
        var bskyPw by remember { mutableStateOf("") }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            OutlinedTextField(value = bskyId, onValueChange = { bskyId = it },
                placeholder = { Text("handle or email", color = DimGray) },
                singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = bskyPw, onValueChange = { bskyPw = it },
                placeholder = { Text("app password", color = DimGray) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onLoginBluesky(bskyId.trim(), bskyPw) },
                enabled = bskyId.isNotBlank() && bskyPw.isNotBlank() && !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(46.dp)) {
                if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                else Text("Sign in to Bluesky", fontWeight = FontWeight.SemiBold)
            }
        }
        return
    }

    // Bug fix (revert per feedback — Hub is meant to scroll again): same
    // revert as the Settings page above — re-adding verticalScroll here too.
    // The "fit on one screen without scrolling" constraint that motivated
    // this session's card/skeleton sizing no longer applies once this is
    // reverted, but the sizing itself is left as-is (still reasonable, no
    // reason to churn it further).
    // Bug fix (item 11): the Refresh/Return to Feed/Upload bar no longer
    // lives inside this page's own Box at all — it used to be pinned here
    // as a second layer directly on top of this scrollable Column, which is
    // exactly why it needed a forced-opaque background to stay legible over
    // whatever was scrolling underneath it (see the old comment on
    // ReturnToFeedBar, now removed). It's rendered once, by the outer Hub
    // composable, below the whole page-switching area — see the call site
    // there — sitting on the same plain, un-scrolling background gradient
    // the "Created by Recho Raccoon" credit does, so nothing ever scrolls
    // behind it. This Column now just needs a small fixed bottom margin for
    // breathing room, not a height reserved to avoid an overlay.
    //
    // Feature: auto-subscribe — wrapped in a Box now (it used to be the
    // bare top-level content) purely so the follower-scan completion popup
    // below can layer on top of this scrollable Column instead of needing
    // its own separate screen/route.
    Box(Modifier.fillMaxSize()) {
    // The search bar is fixed (it no longer scrolls with the page); the rest
    // of the Hub scrolls underneath it and is clipped at its bottom edge —
    // the same way content stops at the Return to Feed bar below.
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val barShape = RoundedCornerShape(22.dp)
            @Composable
            fun SearchBarContent() {
                val tap = rememberHapticTap()
                Row(
                    Modifier.fillMaxSize().clickable { tap(); onOpenSearch() }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = DimGray, modifier = Modifier.size(16.dp))
                    Text("Search", color = DimGray, fontSize = 13.sp)
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(Modifier.weight(1f).height(44.dp), shape = barShape, tint = dominantColor, backdrop = backdrop) { SearchBarContent() }
            } else {
                Box(Modifier.weight(1f).height(44.dp).clip(barShape).background(Color.White.copy(0.06f))) { SearchBarContent() }
            }
            val circleShape = CircleShape
            @Composable
            fun SearchCircleContent() {
                Box(Modifier.fillMaxSize().clickable(onClick = onOpenSearch), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(Modifier.size(44.dp), shape = circleShape, tint = dominantColor, backdrop = backdrop) { SearchCircleContent() }
            } else {
                Box(Modifier.size(44.dp).clip(circleShape).background(Color.White.copy(0.06f))) { SearchCircleContent() }
            }
        }

        Spacer(Modifier.height(6.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .clipToBounds()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
            Text("Feeds", color = DimGray, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp))
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
        }
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val saved = authorFeedState
            if (saved != null) {
                AuthorChip(author = saved.author, liquidGlass = liquidGlass, dominantColor = dominantColor)
            }
            availableFeeds.forEach { feed ->
                FeedChip(feed.displayName, feed.avatarUrl,
                    selectedFeedUri == feed.uri && saved == null, liquidGlass = liquidGlass, dominantColor = dominantColor) { onSelectFeed(feed.uri) }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
            Text(
                selfProfile?.author?.displayName?.ifBlank { null } ?: bskyHandle,
                color = DimGray, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
        }
        Spacer(Modifier.height(6.dp))

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsGridButton("Liked Posts", Icons.Default.Favorite, LikeRed, liquidGlass, Modifier.weight(1f), onShowLikes, panelTint = dominantColor, backdrop = backdrop)
                ProfileGridButton(selfProfile, bskyHandle, liquidGlass, Modifier.weight(1f), onOpenOwnProfile, panelTint = dominantColor, backdrop = backdrop)
                SettingsGridButton("From Friends", Icons.Default.Send, Color.White, liquidGlass, Modifier.weight(1f), onShowFriends, panelTint = dominantColor, backdrop = backdrop)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsGridButton("Saves", Icons.Default.Star, BookmarkYellow, liquidGlass, Modifier.weight(1f), onShowSaves, panelTint = dominantColor, backdrop = backdrop)
                SettingsGridButton("History", Icons.Default.History, Color.White, liquidGlass, Modifier.weight(1f), onShowHistory, panelTint = dominantColor, backdrop = backdrop)
                SettingsGridButton("DMs", Icons.Default.Chat, Color.White, liquidGlass, Modifier.weight(1f), onOpenDmInbox, panelTint = dominantColor, backdrop = backdrop)
            }
        }

        // ── Item 8: Mutuals — quick-access avatar row (DM/mutual contacts) ──
        // Bug fix: renamed from "Friends" to "Mutuals" — this row is
        // specifically the mutual-follow set (see loadDmRecipients), and
        // "Friends" was ambiguous/confusing next to the "From Friends" grid
        // button above, which is a different, broader concept.
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
            Text("Mutuals", color = DimGray, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp))
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
        }
        Spacer(Modifier.height(8.dp))

        // Feature (this session): skeleton placeholders while loading,
        // YouTube-style, instead of a spinner/empty-state swap that used to
        // change this section's height depending on whether it had 0, a
        // few, or many results. Always render at least SKELETON_SLOTS slots
        // (enough to fill a row without scrolling on a typical phone
        // width); real avatars fill in from the front as they arrive, any
        // slots still loading show a pulsing placeholder, and any slots
        // left over once loading is done (genuinely fewer mutuals than
        // slots, or zero) go fully invisible but stay laid out — so the
        // row's height, and therefore the whole page's scroll position,
        // never jumps around loading or after it finishes.
        //
        // Bug fix: this used to filter to `convoId.isNotBlank()` (existing
        // DM threads only) — copied from the DM inbox picker, where that
        // filter is correct (you can't show "history" for a thread that
        // doesn't exist yet), but wrong here. dmConversations already
        // includes every mutual (see loadDmRecipients — mutuals without an
        // existing thread are included with a blank convoId, resolved
        // lazily at send time), so this quick-access row should show all of
        // them, not just people already messaged. Already sorted by most
        // recent interaction by loadDmRecipients.
        val friends = remember(dmConversations) { dmConversations.map { it.member } }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            for (i in 0 until maxOf(MUTUAL_SKELETON_SLOTS, friends.size)) {
                val friend = friends.getOrNull(i)
                val avatarShape = CircleShape
                Column(
                    Modifier.width(60.dp).then(if (friend != null) Modifier.clickable { onOpenProfile(friend) } else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when {
                        friend != null -> {
                            // Item 4: this avatar's own outline is tinted
                            // with its own picture's dominant color, not the
                            // Hub page's shared dominantColor (the signed-in
                            // account's own tint) — every mutual's outline
                            // should reflect the profile icon it's actually
                            // wrapped around.
                            val friendTint = if (friend.avatarUrl != null) rememberDominantColor(friend.avatarUrl) else dominantColor
                            Box(
                                Modifier.size(52.dp)
                                    .then(if (liquidGlass) Modifier.glassPanel(true, shape = avatarShape, tint = friendTint) else Modifier.clip(avatarShape).background(Color.White.copy(0.1f))),
                                contentAlignment = Alignment.Center
                            ) {
                                if (friend.avatarUrl != null) {
                                    AsyncImage(model = friend.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(46.dp).clip(avatarShape))
                                } else {
                                    Box(Modifier.size(46.dp).clip(avatarShape).background(Color.White.copy(0.15f)))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(friend.displayName, color = Color.White, fontSize = 10.sp, lineHeight = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        dmConversationsLoading -> {
                            ShimmerBox(avatarShape, Modifier.size(52.dp))
                            Spacer(Modifier.height(4.dp))
                            ShimmerBox(RoundedCornerShape(3.dp), Modifier.width(40.dp).height(9.dp))
                        }
                        else -> {
                            // Genuinely no more mutuals to show — invisible,
                            // same footprint, not removed (see comment above).
                            Box(Modifier.size(52.dp))
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.width(40.dp).height(9.dp))
                        }
                    }
                }
            }
        }

        // ── Item: Live / Reviews / Blogs — reorderable Hub sections ──
        // Sorted by most recent post, except Live: the moment someone
        // followed is live right now, Live jumps to the very front — a
        // live stream happening beats "posted 10 minutes ago" regardless
        // of its own recency, per feedback. When nobody's live, Live
        // doesn't really have a "most recent post" of its own to sort by,
        // so it just falls to the back rather than claiming one.
        val combinedLive: List<LiveCardSource> = remember(liveFriends, blueskyLiveNow) {
            liveFriends.map { LiveCardSource.Streamplace(it) } + blueskyLiveNow.map { LiveCardSource.BlueskyLive(it) }
        }
        val hasCurrentLive = combinedLive.isNotEmpty()
        val reviewsRecency = friendsReviews.firstOrNull { it.author.did != selfDid }?.review?.createdAt ?: ""
        val blogsRecency = friendsBlogs.firstOrNull { it.author.did != selfDid }?.blog?.createdAt ?: ""

        // ── Item 8/19: Livestreams — everyone the user follows, combining
        // two distinct sources: Streamplace (an AT-Protocol-native
        // streaming service) and Bluesky's own built-in "Live Now" profile
        // badge (an off-platform link to Twitch/YouTube/etc, added this
        // session — see BlueskyLiveNowStream in Models.kt and
        // MainViewModel.loadBlueskyLiveNowIfNeeded). Both render as the same
        // card shape in one merged, combined row so they read as one
        // section rather than two.
        // Item (this session): every section below only renders at all when
        // it actually has something to show — no skeleton placeholders, no
        // invisible empty-slot spacers reserving a row's worth of height for
        // nothing. A quiet Hub (no subscriptions yet, or subscribed
        // accounts with nothing new) just has fewer sections, not blank/
        // loading ones. Loading states are silent — the section simply
        // appears once the fetch resolves with results instead of showing
        // a spinner or shimmer first.
        @Composable
        fun LiveSectionContent() {
            if (combinedLive.isEmpty()) return
            // Bug fix (per feedback — too much space above whichever
            // section lands right under Mutuals): tightened from 14dp to
            // 6dp, matching the compact spacing used elsewhere between
            // stacked Hub elements now that the friend-name Text just above
            // (see the Mutuals row) also got its own explicit lineHeight
            // fix — this was partly compensating for that same "Text's
            // default line-height reserves more vertical space than its
            // visible glyphs need" pattern already fixed for pills earlier
            // this session.
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
                Text("Livestreams", color = DimGray, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp))
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                combinedLive.forEach { source -> LiveCard(source, liquidGlass, onOpenLivePlayer) }
            }
        }

        // ── Item 8: Latest Reviews — sourced from the profile-level
        // "Subscribe" list now (see the Reviews tab's sub-row in
        // ProfileOverlay.kt), not everyone followed.
        @Composable
        fun ReviewsSectionContent() {
            val friendOnlyReviews = friendsReviews.filter { it.author.did != selfDid }
            if (friendOnlyReviews.isEmpty()) return
            // Bug fix (per feedback): see LiveSectionContent's matching
            // comment just above — same tightened spacing applied here so
            // it's consistent no matter which section ends up first.
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
                Text("Reviews", color = DimGray, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp))
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                friendOnlyReviews.take(20).forEach { fr -> MutualReviewCard(fr, liquidGlass, onOpenReview, onOpenProfile) }
            }
        }

        // ── Item: Blogs — same Subscribe-list model as Reviews, its own
        // separate list (see ProfileOverlay.kt's Blogs sub-row).
        @Composable
        fun BlogsSectionContent() {
            val friendOnlyBlogs = friendsBlogs.filter { it.author.did != selfDid }
            if (friendOnlyBlogs.isEmpty()) return
            // Bug fix (per feedback): see LiveSectionContent's matching
            // comment above — same tightened spacing applied here so it's
            // consistent no matter which section ends up first.
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
                Text("Blogs", color = DimGray, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp))
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
            }
            Spacer(Modifier.height(8.dp))
            // Item 5: every Hub blog card now shares one HEIGHT
            // (HUB_BLOG_CARD_HEIGHT) instead of one width — each card's
            // width instead follows its own thumbnail's aspect ratio at
            // that fixed height (see BlogBubble's fixedHeight param), the
            // same way a plain Image auto-sizes when only one dimension is
            // constrained. Pills inside each card are also scaled down
            // (BlogBubble's compact mode) to actually look like a smaller
            // version of the profile card instead of an oversized one.
            // Each card also gets its own small author (icon + name) bubble
            // above it — the Hub mixes posts from many different accounts
            // in one row, unlike a profile's Blogs tab where the author is
            // implicit, so each card needs to say whose it is.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                friendOnlyBlogs.take(20).forEach { fb ->
                    // Bug fix (per feedback): the author bubble now (a)
                    // centers horizontally over its own blog card instead
                    // of hugging the card's left edge — Column defaults to
                    // Start alignment, which left the bubble stranded off
                    // to one side whenever it was narrower than the card
                    // below it — and (b) reflects that same blog's own
                    // thumbnail color (falling back to the author's avatar
                    // color) instead of the page-wide dominantColor, so it
                    // visually matches the card it belongs to.
                    val blogTint = rememberDominantColor(fb.blog.thumbnailUrl ?: fb.author.avatarUrl ?: "")
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Item 11: blog cards don't have one fixed width
                        // (it follows each thumbnail's own aspect ratio at
                        // HUB_BLOG_CARD_HEIGHT — see BlogBubble), so that
                        // height doubles as this bubble's width budget. It's
                        // exact for the common thumbnailless/square case and
                        // a reasonable cap for thumbnail cards otherwise.
                        HubAuthorBubble(displayName = fb.author.displayName, avatarUrl = fb.author.avatarUrl, liquidGlass = liquidGlass, tint = blogTint, cardWidth = HUB_BLOG_CARD_HEIGHT,
                            onClick = { onOpenProfile(fb.author) })
                        Spacer(Modifier.height(6.dp))
                        BlogBubble(
                            blog = fb.blog, liquidGlass = liquidGlass, fallbackAvatarUrl = fb.author.avatarUrl,
                            onOpenBlog = { onOpenBlog(fb) },
                            titleFontSize = 10.sp, fixedHeight = HUB_BLOG_CARD_HEIGHT
                        )
                    }
                }
            }
        }

        val notYetScanned = !followerScanCompletedOnce
        val isScanningNow = followerScanState is MainViewModel.FollowerScanState.Scanning
        val showScanIntro = notYetScanned && (isScanningNow || (subscribedReviewDids.isEmpty() && subscribedBlogDids.isEmpty()))

        val sectionOrder = remember(hasCurrentLive, reviewsRecency, blogsRecency) {
            val nonLive = listOf("reviews" to reviewsRecency, "blogs" to blogsRecency)
                .sortedByDescending { it.second }.map { it.first }
            if (hasCurrentLive) listOf("live") + nonLive else nonLive + listOf("live")
        }
        if (showScanIntro) {
            ReviewsBlogsScanIntroBubble(followerScanState, liquidGlass, dominantColor, backdrop, onStartFollowerScan)
        }
        sectionOrder.forEach { key ->
            when (key) {
                "live" -> LiveSectionContent()
                "reviews" -> if (!showScanIntro) ReviewsSectionContent()
                "blogs" -> if (!showScanIntro) BlogsSectionContent()
            }
        }

        // ── Live Link widget feature: mirrored row at the very bottom of
        // the Hub — per the feature request, only shown once at least one
        // channel link is saved, and shaped for a single full-width row
        // (side-by-side toggle buttons) rather than the widget's own
        // stacked top/bottom layout, which was sized for a small home-
        // screen bubble instead of the Hub's full page width. Every tap
        // here funnels through the exact same LiveLinkManager/prefs path
        // as the widget, so the two surfaces can never disagree about
        // whether a Live Link is currently active. ─────────────────────
        //
        // Gated behind FeatureFlags.LIVE_LINK_ENABLED — hidden for now,
        // left in place to pick back up later.
        if (com.mediaviewer.util.FeatureFlags.LIVE_LINK_ENABLED) {
        val hasTwitchLink = !liveTwitchUrl.isNullOrBlank()
        val hasYoutubeLink = !liveYoutubeUrl.isNullOrBlank()
        if (hasTwitchLink || hasYoutubeLink) {
            Spacer(Modifier.height(10.dp))
            val rowShape = RoundedCornerShape(20.dp)
            @Composable
            fun LiveLinkRowContent() {
                val tap = rememberHapticTap()
                if (liveActivePlatform != null) {
                    val label = if (liveActivePlatform == com.mediaviewer.model.LiveNowPlatform.TWITCH) "End Twitch Link" else "End YouTube Link"
                    val bg = if (liveActivePlatform == com.mediaviewer.model.LiveNowPlatform.TWITCH) TwitchPurple else YouTubeRed
                    Box(
                        Modifier.fillMaxSize().padding(4.dp).clip(RoundedCornerShape(16.dp))
                            .background(bg.copy(alpha = 0.85f)).clickable { tap(); onEndLiveLink() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                            Spacer(Modifier.width(6.dp))
                            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                } else {
                    Row(Modifier.fillMaxSize().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (hasTwitchLink) {
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                                    .background(TwitchPurple.copy(alpha = 0.85f))
                                    .clickable { tap(); onToggleLiveLink(com.mediaviewer.model.LiveNowPlatform.TWITCH) },
                                contentAlignment = Alignment.Center
                            ) { Text("Activate Twitch Link", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center) }
                        }
                        if (hasYoutubeLink) {
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                                    .background(YouTubeRed.copy(alpha = 0.85f))
                                    .clickable { tap(); onToggleLiveLink(com.mediaviewer.model.LiveNowPlatform.YOUTUBE) },
                                contentAlignment = Alignment.Center
                            ) { Text("Activate YouTube Link", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(Modifier.fillMaxWidth().height(52.dp), shape = rowShape, tint = dominantColor, backdrop = backdrop) { LiveLinkRowContent() }
            } else {
                Box(Modifier.fillMaxWidth().height(52.dp).clip(rowShape).background(Color.White.copy(0.06f))) { LiveLinkRowContent() }
            }
        }
        }

        // ── Switch Accounts — same avatar-row look as Mutuals, at the very
        // bottom of the Hub. Only present once another AT Protocol account is
        // signed in (and the person hasn't turned it off in Settings). Tapping
        // an account switches to it straight away; the whole app refreshes.
        if (showSwitchAccountsRow && otherAccounts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
                Text("Switch Accounts", color = DimGray, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp))
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                otherAccounts.forEach { account ->
                    val avatarShape = CircleShape
                    Column(
                        Modifier.width(60.dp).clickable { onSwitchAccount(account.did) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(52.dp)
                                .then(if (liquidGlass) Modifier.glassPanel(true, shape = avatarShape, tint = dominantColor) else Modifier.clip(avatarShape).background(Color.White.copy(0.1f))),
                            contentAlignment = Alignment.Center
                        ) {
                            if (account.avatarUrl != null) {
                                AsyncImage(model = account.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(46.dp).clip(avatarShape))
                            } else {
                                Box(Modifier.size(46.dp).clip(avatarShape).background(Color.White.copy(0.15f)))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(account.displayName.ifBlank { account.handle }, color = Color.White, fontSize = 10.sp, lineHeight = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    } // fixed search bar + scrolling content

    // Feature: auto-subscribe — the one-time follower scan's completion
    // popup, layered over everything else on this page while it's up.
    // Dismissing it (the centered close bubble) just clears the popup;
    // FOLLOWER_SCAN_COMPLETED stays set, so the intro bubble below doesn't
    // come back — a "no accounts added" result explains that a rescan is
    // always available from Settings instead of nagging again here.
    val completedScan = followerScanState as? MainViewModel.FollowerScanState.Completed
    if (completedScan != null) {
        FollowerScanCompletionPopup(completedScan, liquidGlass, dominantColor, backdrop, onDismissFollowerScanResult)
    }
    }
}

/** Feature: auto-subscribe — shown once [MainViewModel.startFollowerScan]
 *  finishes, summarizing what it found. A centered card over a dimmed
 *  scrim, dismissed only via its own close bubble (no scrim-tap-to-dismiss)
 *  since it's a one-shot informational result, not a sheet someone might
 *  want to swipe past. */
@Composable
private fun FollowerScanCompletionPopup(
    result: MainViewModel.FollowerScanState.Completed, liquidGlass: Boolean, dominantColor: Color,
    backdrop: GlassBackdrop?, onDismiss: () -> Unit
) {
    val foundNothing = result.reviewsFound == 0 && result.blogsFound == 0
    val tap = rememberHapticTap()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        val cardShape = RoundedCornerShape(20.dp)
        @Composable
        fun CardContent() {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Scan Complete", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Scanned ${result.accountsScanned} account${if (result.accountsScanned == 1) "" else "s"}.",
                    color = Color.White.copy(0.85f), fontSize = 13.sp, textAlign = TextAlign.Center
                )
                Text(
                    "${result.reviewsFound} had Reviews, ${result.blogsFound} had Blogs.",
                    color = Color.White.copy(0.85f), fontSize = 13.sp, textAlign = TextAlign.Center
                )
                if (foundNothing) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "None of the accounts you follow had reviews or blogs. You can retry this scan anytime from Settings.",
                        color = DimGray, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 16.sp
                    )
                }
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(0.15f))
                        .clickable { tap(); onDismiss() }
                        .padding(horizontal = 22.dp, vertical = 9.dp)
                ) {
                    Text("Close", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(Modifier.padding(horizontal = 32.dp), shape = cardShape, tint = dominantColor, backdrop = backdrop) { CardContent() }
        } else {
            Box(Modifier.padding(horizontal = 32.dp).clip(cardShape).background(OledBlack)) { CardContent() }
        }
    }
}

/** Feature: auto-subscribe — the Reviews/Blogs rows' empty-state
 *  replacement, shown (per spec) only to accounts with nothing in either
 *  subscribed list yet and who've never run the follower scan. Explains
 *  what the scan does and why it's paced/one-time, and doubles as the
 *  progress readout once [onStartFollowerScan] is tapped — same bubble,
 *  its copy just swaps to a running count while [scanState] is Scanning. */
@Composable
private fun ReviewsBlogsScanIntroBubble(
    scanState: MainViewModel.FollowerScanState, liquidGlass: Boolean, dominantColor: Color,
    backdrop: GlassBackdrop?, onStartScan: () -> Unit
) {
    val scanning = scanState as? MainViewModel.FollowerScanState.Scanning
    val shape = RoundedCornerShape(20.dp)
    val tap = rememberHapticTap()
    @Composable
    fun BubbleContent() {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (scanning != null) {
                Text("Scanning who you follow…", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${scanning.accountsScanned} checked · ${scanning.reviewsFound} Reviews · ${scanning.blogsFound} Blogs found so far",
                    color = DimGray, fontSize = 12.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 1.5.dp)
            } else {
                Text(
                    "This app combines multiple AT Proto apps into one, allowing you to write and view long-form blogs and title reviews. The blogs and reviews from the people you follow can show up here, but you'll need to initiate a one-time scan of who you follow to locally log which accounts post blogs and/or reviews so that the app can display their latest blogs/reviews here! Creating this on-device list helps avoid PDS rate limits.",
                    color = Color.White.copy(0.85f), fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(0.15f))
                        .clickable { tap(); onStartScan() }
                        .padding(horizontal = 22.dp, vertical = 9.dp)
                ) {
                    Text("Initiate Scan", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    if (liquidGlass) {
        LiquidGlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = shape, tint = dominantColor, backdrop = backdrop) { BubbleContent() }
    } else {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape).background(Color.White.copy(0.06f))) { BubbleContent() }
    }
}

// Feature (this session): skeleton-placeholder slot counts for the Hub's
// horizontally-scrolling sections — chosen to fill a typical phone-width
// row without scrolling, per feedback ("show enough to fill up the row").
// Mutuals avatars are narrow (60.dp incl. spacing) so 6 fit comfortably;
// the Review/Livestream cards are much wider (140-ish dp) so 3 is the
// realistic fill count without visibly overflowing on most screens.
private const val MUTUAL_SKELETON_SLOTS = 6
private const val REVIEW_SKELETON_SLOTS = 3
private const val LIVESTREAM_SKELETON_SLOTS = 3
private val REVIEW_CARD_WIDTH = 108.dp
// Item 5: every Hub blog card shares this one height — width instead
// follows each card's own thumbnail aspect ratio at that height (see
// BlogBubble's fixedHeight param), so cards no longer read as
// uniformly-wide-but-randomly-tall poster tiles.
private val HUB_BLOG_CARD_HEIGHT = 150.dp

// Platform brand colors for Bluesky "Live Now" cards/badges — Twitch and
// YouTube's own accent colors, so a glance at the card tint alone tells you
// which platform it links to before you even read the badge text.
private val TwitchPurple = Color(0xFF9146FF)
private val YouTubeRed = Color(0xFFFF0000)

/** One entry in the merged Livestreams row — either a Streamplace stream or
 *  a Bluesky-native "Live Now" badge. A sealed class instead of two parallel
 *  lists means the row can interleave/render them with one loop instead of
 *  duplicating the whole card block per source. */
private sealed class LiveCardSource {
    data class Streamplace(val stream: com.mediaviewer.model.StreamplaceLiveStream) : LiveCardSource()
    data class BlueskyLive(val stream: com.mediaviewer.model.BlueskyLiveNowStream) : LiveCardSource()
}

/** Renders one Livestreams card for either source. Both sources now open
 *  the same in-app WebView player (see LiveNowPlayerOverlay) instead of
 *  Streamplace bouncing out to the system browser — Bluesky Live Now still
 *  resolves an actual embeddable-player URL first (Twitch/YouTube), while
 *  Streamplace just loads its own stream.place page directly since there's
 *  no known embed format for it to build. */
@Composable
private fun LiveCard(source: LiveCardSource, liquidGlass: Boolean, onOpenLivePlayer: (String, String, String) -> Unit) {
    val cardShape = RoundedCornerShape(12.dp)
    // Bug fix (per feedback — tapping a live card opened a broken in-app
    // player UI instead of the actual stream): `onOpenLivePlayer` (still
    // kept as a param so callers/MainActivity wiring don't need touching)
    // is no longer called here at all — both sources now hand their real,
    // direct stream URL straight to the system via LocalUriHandler, the
    // same as any other outbound link in this app, opening in the user's
    // actual Twitch/YouTube/Streamplace app or browser instead of this
    // app's own WebView-based LiveNowPlayerOverlay. Bluesky Live Now
    // specifically uses the stream's own `uri` here (its real page) rather
    // than embedUrlFor's *embeddable-player* URL — that conversion only
    // ever made sense for loading the stream inside this app's WebView, not
    // for handing off to an external app/browser that already knows how to
    // open the real page correctly on its own.
    val uriHandler = LocalUriHandler.current
    val (thumbUrl, title, accountName, accountAvatarUrl, tint, badgeText, onClick) = when (source) {
        is LiveCardSource.Streamplace -> {
            val s = source.stream
            val name = s.authorDisplayName ?: s.authorHandle
            SevenTuple(s.thumbUrl, s.title.ifBlank { "Untitled stream" }, name, s.authorAvatarUrl, LikeRed, "LIVE") {
                uriHandler.openUri("https://stream.place/${s.authorHandle}")
            }
        }
        is LiveCardSource.BlueskyLive -> {
            val s = source.stream
            val tint = when (s.platform) {
                com.mediaviewer.model.LiveNowPlatform.TWITCH -> TwitchPurple
                com.mediaviewer.model.LiveNowPlatform.YOUTUBE -> YouTubeRed
                com.mediaviewer.model.LiveNowPlatform.OTHER -> LikeRed
            }
            val badge = when (s.platform) {
                com.mediaviewer.model.LiveNowPlatform.TWITCH -> "TWITCH"
                com.mediaviewer.model.LiveNowPlatform.YOUTUBE -> "YOUTUBE"
                com.mediaviewer.model.LiveNowPlatform.OTHER -> "LIVE"
            }
            SevenTuple(s.thumbUrl, s.title, s.author.displayName, s.author.avatarUrl, tint, badge) {
                uriHandler.openUri(s.uri)
            }
        }
    }
    // Bug fix (per feedback — matches Blogs/Reviews now): the author
    // icon+name used to float INSIDE the card as a BottomStart overlay,
    // reflective via its own per-card GraphicsLayer backdrop recording. It
    // now sits in its own small (non-reflective, same as Blogs/Reviews'
    // HubAuthorBubble) bubble above the card instead, so that recording
    // setup — nothing else in this card ever read from it — is gone too.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    HubAuthorBubble(displayName = accountName, avatarUrl = accountAvatarUrl, liquidGlass = liquidGlass, tint = tint, cardWidth = 140.dp)
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier.width(140.dp).height(140.dp)
            .then(if (liquidGlass) Modifier.glassPanel(true, shape = cardShape, tint = tint) else Modifier.clip(cardShape).background(tint.copy(0.18f)))
            .clickable(onClick = onClick)
    ) {
        if (thumbUrl != null) {
            AsyncImage(model = thumbUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(cardShape))
        } else {
            Box(Modifier.fillMaxSize().clip(cardShape).background(Color.White.copy(0.08f)))
        }
        Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(tint).padding(horizontal = 5.dp, vertical = 2.dp)) {
            Text(badgeText, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
    }
}

/** Tiny local helper so [LiveCard] can destructure the per-source card data
 *  in one `when` branch instead of a longer if/else with repeated fields. */
private data class SevenTuple(
    val thumbUrl: String?, val title: String, val accountName: String, val accountAvatarUrl: String?,
    val tint: Color, val badgeText: String, val onClick: () -> Unit
)

/** In-app WebView player for a live link — both Live sources (Streamplace
 *  and Bluesky Live Now) open this now, instead of Streamplace bouncing out
 *  to the system browser like it used to. Reuses this file's established
 *  overlay conventions: blockClicksBehind on the root so taps can't fall
 *  through to the feed behind it, and a close button in the same position/
 *  style other overlays use. `url` is either a resolved embeddable-player
 *  URL (Bluesky Live Now — see embedUrlFor) or the live page's own direct
 *  link (Streamplace — no known embed format to build one for, so this
 *  just loads its real page). */
@Composable
fun LiveNowPlayerOverlay(stream: com.mediaviewer.viewmodel.MainViewModel.PlayingLiveStream, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).blockClicksBehind(),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.fillMaxWidth().padding(top = rememberTopCutoutClearance())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stream.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stream.subtitle, color = DimGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(0.12f)).clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            // 16:9 embed player — most live platform embeds (Twitch, YouTube)
            // are widescreen regardless of the source stream's own aspect;
            // Streamplace's own page will just letterbox inside this if its
            // real layout isn't 16:9, same as any page loaded in a WebView.
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            webChromeClient = WebChromeClient()
                            webViewClient = WebViewClient()
                            loadUrl(stream.url)
                        }
                    },
                    update = { it.loadUrl(stream.url) }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Live via ${stream.url.substringAfter("://").substringBefore("/")}",
                color = DimGray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/** Builds the actual embeddable-player URL for a Bluesky Live Now link.
 *  Twitch's embed requires a `parent` query param naming the embedding
 *  page's host — Twitch only validates this as a string match, not real
 *  domain ownership, and third-party (non-browser) embedders commonly
 *  supply a placeholder value for exactly this reason since there's no real
 *  "page host" inside a native app's WebView. YouTube just needs the video
 *  ID out of any of its common URL shapes. Anything else (a platform this
 *  app doesn't have a known embed format for) falls back to loading the
 *  link directly, which will render its normal (non-embed) page in the
 *  WebView — not a true inline player, but still viewable without leaving
 *  the app. */
private fun embedUrlFor(stream: com.mediaviewer.model.BlueskyLiveNowStream): String {
    val uri = stream.uri
    return when (stream.platform) {
        com.mediaviewer.model.LiveNowPlatform.TWITCH -> {
            val channel = uri.trimEnd('/').substringAfterLast('/')
            "https://player.twitch.tv/?channel=$channel&parent=raccnetlite.app&muted=false"
        }
        com.mediaviewer.model.LiveNowPlatform.YOUTUBE -> {
            val videoId = Regex("(?:v=|youtu\\.be/|embed/|live/)([A-Za-z0-9_-]{6,})").find(uri)?.groupValues?.get(1)
            if (videoId != null) "https://www.youtube.com/embed/$videoId?autoplay=1" else uri
        }
        com.mediaviewer.model.LiveNowPlatform.OTHER -> uri
    }
}

/** Master switch for the Hub's Refresh bubble. While false (current), the
 *  Hub's left-hand button is just a plain Settings button. Flip to true to
 *  bring back the old "More" behavior: a ⋮ button that pops open Settings +
 *  Refresh bubbles above it. All of that code is still in [HubSettingsButton]
 *  and the `onRefresh` wiring is still passed all the way down, so nothing
 *  else needs to change. */
private const val HUB_REFRESH_IN_UI = false

/** The Hub's left-hand bottom-bar button: a circular Settings (gear) button
 *  — visually identical to the feed interaction bar's own round buttons.
 *  While [HUB_REFRESH_IN_UI] is true it doubles as the old "More" button
 *  (Settings + Refresh popup stack); otherwise it goes straight to Settings. */
@Composable
private fun HubSettingsButton(
    liquidGlass: Boolean, tint: Color, onOpenSettings: () -> Unit, onRefresh: () -> Unit = {},
    size: Dp = 26.dp, modifier: Modifier = Modifier, backdrop: GlassBackdrop? = null,
    // True while the Hub is showing the Settings page — the button then
    // reads as "back to Hub" (right arrow) instead of the gear, and tapping
    // it calls onOpenSettings() (which the caller wires to toggle back to
    // MAIN).
    settingsOpen: Boolean = false
) {
    val stackEnabled = HUB_REFRESH_IN_UI
    var expanded by remember { mutableStateOf(false) }
    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = CircleShape
    // Item 8: haptic tap on opening/closing the stack.
    val tap = rememberHapticTap()
    // If the Settings page opens while the popup is expanded, collapse it —
    // the button is a back-arrow there, not a menu.
    LaunchedEffect(settingsOpen) { if (settingsOpen) expanded = false }


    // Item 7: fixed-size root so the expanded popups (drawn above via
    // negative offsets, unclipped) can never change this Box's measured
    // height — without this, opening the popup stretched the whole bottom
    // bar upward. Each bubble is its own AnimatedVisibility with its own
    // upward offset (no shared Column that a fixed-size parent can clip),
    // so both always render: Settings above, Refresh below.
    Box(modifier.size(size)) {
        if (stackEnabled) {
        // Settings bubble — two slots above the button.
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.8f, transformOrigin = TransformOrigin(0f, 1f)),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f, transformOrigin = TransformOrigin(0f, 1f)),
            modifier = Modifier.align(Alignment.BottomStart).offset(y = -(size * 2 + 16.dp))
        ) {
            val settingsMod = Modifier.size(size).clickable { tap(); expanded = false; onOpenSettings() }
            if (liquidGlass) {
                LiquidGlassSurface(settingsMod, shape = shape, tint = tint, backdrop = backdrop) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Settings, "Settings", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            } else {
                Box(settingsMod.clip(shape).background(Color.White.copy(0.10f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Settings, "Settings", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        // Refresh bubble — one slot above the button.
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.8f, transformOrigin = TransformOrigin(0f, 1f)),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f, transformOrigin = TransformOrigin(0f, 1f)),
            modifier = Modifier.align(Alignment.BottomStart).offset(y = -(size + 8.dp))
        ) {
            val refreshMod = Modifier.size(size).clickable {
                tap()
                expanded = false
                onRefresh()
                scope.launch { rotation.snapTo(0f); rotation.animateTo(360f, animationSpec = tween(600, easing = LinearEasing)) }
            }
            if (liquidGlass) {
                LiquidGlassSurface(refreshMod, shape = shape, tint = tint, backdrop = backdrop) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Refresh, "Refresh", tint = Color.White,
                            modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation.value })
                    }
                }
            } else {
                Box(refreshMod.clip(shape).background(Color.White.copy(0.10f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Refresh, "Refresh", tint = Color.White,
                        modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation.value })
                }
            }
        }
        }

        val clickModifier = Modifier.size(size).clickable {
            tap()
            // On the Settings page this button is a back-to-Hub arrow, and
            // with the Refresh stack disabled it's a plain Settings button:
            // either way tapping just toggles via onOpenSettings().
            if (settingsOpen || !stackEnabled) onOpenSettings() else expanded = !expanded
        }
        @Composable
        fun ButtonIconContent() {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val icon = when {
                    settingsOpen -> Icons.AutoMirrored.Filled.ArrowForward
                    !stackEnabled -> Icons.Filled.Settings
                    expanded -> Icons.Default.Close
                    else -> Icons.Default.MoreVert
                }
                Icon(icon, contentDescription = if (stackEnabled) "More" else "Settings",
                    tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(clickModifier, shape = shape, tint = tint, backdrop = backdrop) { ButtonIconContent() }
        } else {
            Box(clickModifier.clip(shape).background(Color.White.copy(0.10f))) { ButtonIconContent() }
        }
    }
}

/** Item 3/5: the Hub's own upload placeholder — moved here from the feed's
 *  interaction bar (that bar's old center [UploadPlaceholderButton] is gone;
 *  see ActionRow in MainFeedScreen.kt). A circular "+" bubble, matching
 *  [HubRefreshBubble]'s sizing/shape so the pair reads as symmetric anchors
 *  on either end of the Return to Feed pill.
 *
 *  Bug fix (this session): this used to pop open a "Post"/"Review"/"Go
 *  Live" stack of bubbles (and before that, a single [GlassDropdownMenu]
 *  panel) — "Review" and "Go Live" were always placeholders with no
 *  functionality behind them, so the whole picker step was just friction.
 *  Tapping "+" now jumps straight to the post composer, no intermediate
 *  menu and (since nothing ever animates open) no "+" -> "x" rotation
 *  affordance either — both removed outright rather than kept around
 *  unused. */
@Composable
private fun HubUploadBubble(
    liquidGlass: Boolean, tint: Color, size: androidx.compose.ui.unit.Dp = 26.dp,
    modifier: Modifier = Modifier, backdrop: GlassBackdrop? = null,
    // Item 5 (rework): see ReturnToFeedBar's own doc comment on
    // `uploadBackdrop` — kept as a parameter for source compatibility with
    // existing callers even though this bubble no longer pops open a menu
    // that would need it.
    menuBackdrop: GlassBackdrop? = null,
    // Upload flow: tapping this bubble now opens the Bluesky post composer
    // (ComposePostScreen.kt) directly.
    onOpenComposePost: () -> Unit = {}
) {
    val circleShape = CircleShape
    val tap = rememberHapticTap()
    val clickModifier = Modifier.size(size).clickable { tap(); onOpenComposePost() }

    @Composable
    fun IconContent() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, contentDescription = "Upload", tint = Color.White,
                modifier = Modifier.size(16.dp))
        }
    }

    Box(modifier.size(size)) {
        if (liquidGlass) {
            LiquidGlassSurface(clickModifier, shape = circleShape, tint = tint, backdrop = backdrop) { IconContent() }
        } else {
            Box(clickModifier.clip(circleShape).background(Color.White.copy(0.10f))) { IconContent() }
        }
    }
}

/** The "Settings / Credits" tab switch at the right end of the Hub's bottom
 *  bar (visible only on the Settings page): a glass pill split into two
 *  segments, the selected one filled in. */
@Composable
private fun SettingsCreditsSwitch(
    selected: SettingsTab, onSelect: (SettingsTab) -> Unit,
    liquidGlass: Boolean, tint: Color, height: Dp, modifier: Modifier = Modifier
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(20.dp)
    @Composable
    fun Segments() {
        Row(Modifier.padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(SettingsTab.SETTINGS to "Settings", SettingsTab.CREDITS to "Credits").forEach { (tab, label) ->
                val isSelected = tab == selected
                Box(
                    Modifier
                        .height(height - 6.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (isSelected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { if (!isSelected) { tap(); onSelect(tab) } }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label, color = if (isSelected) Color.White else DimGray,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false
                    )
                }
            }
        }
    }
    if (liquidGlass) {
        LiquidGlassSurface(modifier.height(height), shape = shape, tint = tint, backdrop = null) { Segments() }
    } else {
        Box(modifier.height(height).clip(shape).background(Color.White.copy(0.10f))) { Segments() }
    }
}

/** Bottom-of-page control group that replaces the removed swipe-up-to-feed
 *  gesture: a centered "Return to Feed" glass pill, with the Hub's
 *  Settings button (see [HubSettingsButton]) at its left
 *  edge and the upload bubble (item 3/5) at its right edge — both
 *  height-matched to the pill, so the group reads as one centered control
 *  rather than several separate ones. The More button (and its left-edge
 *  slot) always renders, even when [showPillAndUpload] is false — Settings
 *  has to stay reachable before the person has logged into anything, when
 *  there's no feed to return to and nothing to refresh yet. */
@Composable
private fun ReturnToFeedBar(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onReturnToFeed: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit = {},
    // Item 14: false pre-login (nothing to return to / refresh yet, only
    // Settings needs to be reachable) — the pill/label/upload bubble are
    // skipped entirely and only the More button shows, still left-aligned
    // in its usual spot.
    showPillAndUpload: Boolean = true,
    // Item 5 (rework): the dedicated background-only backdrop the upload
    // bubble stack uses for its "cutout" look — see the doc comment where
    // this is built, on the Hub's root `hubBackgroundLayer`. Deliberately a
    // separate parameter from [backdrop] above: [backdrop] is `null`
    // everywhere on this bar today (see its own comment), while this one
    // is real and only ever feeds the upload menu's popped-open bubbles,
    // which — unlike this bar itself — can end up visually overlapping the
    // scrollable card content above when expanded.
    uploadBackdrop: GlassBackdrop? = null,
    // Feature (this session): "Open Feed" the very first time (before the
    // person has ever been to the feed this session — the app now opens
    // straight on the Hub, see MainViewModel's init{} change), "Return to
    // Feed" from then on — see MainViewModel.hasVisitedFeed's own doc
    // comment for why this is tracked centrally rather than as local
    // per-button state.
    hasVisitedFeed: Boolean = false,
    // Upload flow: forwarded down to HubUploadBubble's "Post" entry.
    onOpenComposePost: () -> Unit = {},
    // Fix (per feedback): forwarded to HubSettingsButton — true while the Hub
    // is showing the Settings page, turning the Settings button into a
    // right-arrow "back to Hub" button.
    settingsOpen: Boolean = false,
    // Settings/Credits switch shown at the right end of the bar while the
    // Settings page is open.
    settingsTab: SettingsTab = SettingsTab.SETTINGS,
    onSettingsTabChange: (SettingsTab) -> Unit = {}
) {
    val barHeight = 40.dp
    val shape = RoundedCornerShape(20.dp)
    val label = if (hasVisitedFeed) "Return to Feed" else "Open Feed"
    // Item 8: haptic tap when leaving the Hub back to the feed.
    val tap = rememberHapticTap()
    val onReturnToFeedHaptic = { tap(); onReturnToFeed() }
    // Bug fix (per feedback): the pill used to shrink-wrap its own text
    // and sit centered as a small standalone group with the refresh bubble
    // — not the wide, left-anchored bar it used to be. The pill itself now
    // spans the full row again (from the actual left edge), just with its
    // sides trimmed back by the refresh/upload bubbles' own width so
    // neither ever overlaps it; the two bubbles are siblings pinned to
    // CenterStart/CenterEnd instead of trailing after the pill in a Row.
    // The label text is a third sibling, aligned to Center of this *whole*
    // Box (the full row width) rather than centered within the pill's own
    // — trimmed, therefore off-center — bounds, so it reads as centered on
    // the screen the way a plain "Return to Feed" button always did,
    // regardless of how much room the bubbles eat out of either side.
    //
    // Item 14: the left slot is always reserved now — it's the Hub's More
    // button (Settings + Refresh), which always shows, not the old
    // conditionally-optional refresh bubble.
    val moreReserve = barHeight + 10.dp
    val uploadReserve = barHeight + 10.dp

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        if (showPillAndUpload) {
        // Bug fix (item 11): this used to force an opaque
        // Color.Black.copy(alpha = 0.62f) backing under the glass tint,
        // because this bar used to be layered on top of the same busy
        // scrolling content the page's cards live in — the extra opacity
        // was a workaround to keep it legible over whatever happened to be
        // scrolling underneath. Per feedback, that's undone here: this bar
        // is no longer drawn as an overlay on top of scrolling content at
        // all — the caller (see the outer Hub composable) now renders it
        // below the scrollable page area entirely, over the same plain
        // background gradient the "Created by Recho Raccoon" credit sits
        // on, so there's nothing to visually fight with underneath it and
        // no reason to force extra opacity. It now uses a completely
        // normal LiquidGlassSurface, same as the HubChip row above it.
        if (liquidGlass) {
            LiquidGlassSurface(
                Modifier.fillMaxWidth().padding(start = moreReserve, end = uploadReserve).height(barHeight)
                    .clickable(onClick = onReturnToFeedHaptic),
                shape = shape, tint = tint, backdrop = backdrop
            ) {}
        } else {
            Box(
                Modifier.fillMaxWidth().padding(start = moreReserve, end = uploadReserve).height(barHeight)
                    .clip(shape).background(Color.White.copy(0.08f)).clickable(onClick = onReturnToFeedHaptic)
            )
        }
        Text(
            label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center)
        )
        HubUploadBubble(
            liquidGlass, tint, size = barHeight, modifier = Modifier.align(Alignment.CenterEnd),
            backdrop = backdrop, menuBackdrop = uploadBackdrop,
            onOpenComposePost = onOpenComposePost
        )
        }
        if (settingsOpen) {
            SettingsCreditsSwitch(
                selected = settingsTab, onSelect = onSettingsTabChange,
                liquidGlass = liquidGlass, tint = tint, height = barHeight,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        // Item 14: always shown, in the same left slot, whether or not the
        // pill/upload bubble above are — Settings must stay reachable even
        // pre-login.
        HubSettingsButton(
            liquidGlass, tint, onOpenSettings = onOpenSettings, onRefresh = onRefresh,
            size = barHeight, modifier = Modifier.align(Alignment.CenterStart), backdrop = backdrop,
            // Fix (per feedback): on the Settings page the More button
            // becomes a right-arrow "back to Hub" button.
            settingsOpen = settingsOpen
        )
    }
}

/** A single pulsing placeholder block — the Hub's YouTube-style loading
 *  skeleton primitive, reused for every section's placeholder slots. */
@Composable
private fun ShimmerBox(shape: androidx.compose.ui.graphics.Shape, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "hubShimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.05f, targetValue = 0.15f,
        animationSpec = infiniteRepeatable(animation = tween(700, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "hubShimmerAlpha"
    )
    Box(modifier.clip(shape).background(Color.White.copy(alpha = alpha)))
}

/** Latest-Reviews-From-Subscribed-Accounts card. Item (this session): the
 *  old author-strip-above/title-row-below layout is gone — the poster now
 *  fills the entire card, with author (avatar + name) and title each as
 *  their own small glass bubble layered directly on the artwork, same
 *  visual language as the star-rating pill already used — author bubble
 *  top-left, title bubble bottom-left.
 *
 *  Bug fix (this session): the rating pill used to sit at TopEnd, which on
 *  this card's narrow width overlapped the author bubble at TopStart once
 *  the author's name pushed it wide enough — the two were laid out
 *  independently with no awareness of each other. The rating pill now lives
 *  directly under the author bubble in the same top-left-anchored Column, so
 *  it's a second row instead of a competing corner. */
/** Item 7: review cards' glass rim/background reflect the review's own
 *  thumbnail color (falling back to the reviewing account's avatar color if
 *  the review has no media image) instead of one shared [dominantColor] for
 *  every card in the row — matching how a post's own glass panels reflect
 *  its own dominant color rather than some page-wide constant. */
@Composable
private fun MutualReviewCard(
    fr: com.mediaviewer.model.FriendPopfeedReview,
    liquidGlass: Boolean,
    onOpenReview: (com.mediaviewer.model.FriendPopfeedReview) -> Unit,
    onOpenProfile: (com.mediaviewer.model.AuthorInfo) -> Unit = {}
) {
    val shape = RoundedCornerShape(14.dp)
    val tint = rememberDominantColor(fr.review.mediaImageUrl ?: fr.author.avatarUrl ?: "")
    // Bug fix (per feedback): the author icon+name used to float INSIDE the
    // card as a TopStart overlay, competing for the same corner as the star
    // rating pill — the two routinely overlapped on this card's narrow
    // (REVIEW_CARD_WIDTH) width. It now sits in its own small bubble above
    // the card, exactly like Blogs' HubAuthorBubble treatment, and the star
    // rating moved into the room that freed up at the card's top-right.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HubAuthorBubble(displayName = fr.author.displayName, avatarUrl = fr.author.avatarUrl, liquidGlass = liquidGlass, tint = tint, cardWidth = REVIEW_CARD_WIDTH,
            onClick = { onOpenProfile(fr.author) })
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.width(REVIEW_CARD_WIDTH).aspectRatio(2f / 3f)
                .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape) else Modifier.clip(shape).background(Color.White.copy(0.06f)))
                .clickable { onOpenReview(fr) }
        ) {
            if (fr.review.mediaImageUrl != null) {
                AsyncImage(model = fr.review.mediaImageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(shape))
            } else {
                Box(Modifier.fillMaxSize().clip(shape).background(Color.White.copy(0.10f)))
            }
            StarRatingPill(
                rating = fr.review.ratingOutOf5, liquidGlass = liquidGlass, tint = tint,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
            )
            Box(
                Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = RoundedCornerShape(10.dp)) else Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(0.55f)))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    fr.review.mediaTitle, color = Color.White, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 92.dp)
                )
            }
        }
    }
}

/** Item 5: the small author (icon + name) bubble the Hub shows above each
 *  Blog/Review/Livestream card — the Hub mixes cards from many different
 *  accounts in one row, unlike a profile's own tabs where the author is
 *  implicit from context, so each card needs its own "whose is this" label.
 *  Deliberately plain rather than tinted per-author (unlike the cards
 *  themselves) so the row of little author pills reads as one consistent
 *  strip rather than a row of mismatched colors. Takes plain strings rather
 *  than a whole AuthorInfo so non-Bluesky sources (e.g. a livestream's
 *  platform-native account name) can use it too.
 *
 *  [cardWidth] is the exact width of the card this bubble sits above (the
 *  same value each call site already uses to size that card) — item 11:
 *  rather than a fixed max-width truncating long names with an ellipsis,
 *  the name's own font size now shrinks (down to a sane floor) until the
 *  whole bubble fits within that width, so its rounded ends land flush
 *  with the card's own edges instead of overhanging them. Short names are
 *  unaffected — they just render at the normal size, narrower than the
 *  card, exactly as before. */
@Composable
private fun HubAuthorBubble(
    displayName: String, avatarUrl: String?, liquidGlass: Boolean, tint: Color, cardWidth: Dp,
    // Tapping the bubble opens that person's profile.
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(10.dp)
    val tap = rememberHapticTap()
    val avatarSize = 14.dp
    val spacing = 5.dp
    val horizontalPad = 6.dp
    // Budget left for the name text alone once the avatar, the gap between
    // it and the text, and the bubble's own left/right padding are all
    // subtracted from the card's width — this is what actually gets
    // measured/shrunk against, not the bubble's total outer width.
    val textBudget = (cardWidth - avatarSize - spacing - horizontalPad * 2).coerceAtLeast(20.dp)
    var fontSizeSp by remember(displayName, cardWidth) { mutableStateOf(10f) }
    Row(
        Modifier
            .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape) else Modifier.clip(shape).background(Color.Black.copy(0.55f)))
            .then(if (onClick != null) Modifier.clickable { tap(); onClick() } else Modifier)
            .padding(horizontal = horizontalPad, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        Box(Modifier.size(avatarSize).clip(CircleShape).background(Color.White.copy(0.15f))) {
            if (avatarUrl != null) {
                AsyncImage(model = avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape))
            }
        }
        Text(
            displayName, color = Color.White, fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp + 1f).sp,
            fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            modifier = Modifier.widthIn(max = textBudget),
            onTextLayout = { result ->
                // Item 11: one step down per overflowing layout pass — each
                // shrink triggers a fresh measure/onTextLayout call, so this
                // settles within a handful of frames rather than needing an
                // explicit measuring loop of its own. 6sp floor keeps it
                // legible instead of shrinking to nothing for pathologically
                // long names.
                if (result.didOverflowWidth && fontSizeSp > 6f) fontSizeSp = (fontSizeSp - 0.5f).coerceAtLeast(6f)
            }
        )
    }
}


// ── Settings Update: quick-access button grid ────────────────────────────────

@Composable
private fun SettingsGridButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    liquidGlass: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    panelTint: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null
) {
    val shape = RoundedCornerShape(12.dp)

    @Composable
    fun ButtonContent() {
        // Item 1: half the previous height, icon and label share one row
        // with the icon on the right instead of stacked icon-over-label.
        Row(
            Modifier.fillMaxSize().clickable(onClick = onClick).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
            Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(16.dp))
        }
    }

    if (liquidGlass) {
        LiquidGlassSurface(modifier = modifier.height(36.dp), shape = shape, tint = panelTint, backdrop = backdrop) { ButtonContent() }
    } else {
        Box(modifier.height(36.dp).clip(shape).background(Color.White.copy(0.06f))) { ButtonContent() }
    }
}

/** The "Profile" quick-access button — shows the user's own avatar big on the
 *  left, "Profile" on the centered right, their banner blurred into the glass
 *  background, and a rim that reflects the avatar/banner's own colors, same
 *  as every other glass surface in the app. */
@Composable
private fun ProfileGridButton(
    profile: com.mediaviewer.model.ProfileData?,
    fallbackHandle: String,
    liquidGlass: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    // Item 3/11: the rim reflects the currently-viewed post's own dominant
    // color, same as every other Hub button (SettingsGridButton etc.),
    // instead of this button's own avatar/banner color — keeps every button
    // in the grid visually consistent instead of each picking its own tint.
    // The banner image itself still shows blurred through the glass behind
    // the rim; only the rim/tint color changed source.
    panelTint: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null
) {
    val shape = RoundedCornerShape(12.dp)
    val avatarUrl = profile?.author?.avatarUrl
    val bannerUrl = profile?.bannerUrl
    val tint = panelTint

    Box(
        modifier
            .height(36.dp)
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        // Item: the banner should be blurred/magnified into the glass the
        // same way every other liquid-glass panel treats its live backdrop
        // (see LiquidGlassSurface) — previously this just painted the banner
        // crisp and dropped a static tint over it, so nothing was actually
        // "reflecting" through the glass.
        // Item 26: scaled by the same intensity dial as everything else.
        val glassIntensity = LocalGlassIntensity.current
        if (bannerUrl != null) {
            if (liquidGlass && CAN_BLUR && glassIntensity > 0.01f) {
                AsyncImage(
                    model = bannerUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                        .graphicsLayer { scaleX = 1f + 0.3f * glassIntensity; scaleY = 1f + 0.3f * glassIntensity }
                        .blur(22.dp * glassIntensity)
                )
            } else {
                AsyncImage(model = bannerUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
        } else {
            Box(Modifier.matchParentSize().background(tint.copy(alpha = 0.4f)))
        }
        // The glass reflecting treatment blurs/tints the banner underneath and
        // gives the rim the avatar/banner's own dominant color.
        if (liquidGlass) {
            Box(Modifier.matchParentSize().glassPanel(true, tint = tint, shape = shape))
        } else {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        // Item 1: half height, and the avatar (this button's "icon") sits to
        // the right of the label instead of the left.
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Profile", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White.copy(0.15f))) {
                if (avatarUrl != null) {
                    AsyncImage(model = avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape))
                }
            }
        }
    }
}

// ── Shared feed-row chip composables ─────────────────────────────────────────

@Composable
fun AuthorChip(author: com.mediaviewer.model.AuthorInfo, liquidGlass: Boolean = false, dominantColor: Color = NeutralGlassTint) {
    // Always shown as "selected" since we're currently viewing this author's posts.
    // Tapping it is intentionally a no-op — to leave, tap a real feed chip.
    Row(
        modifier = Modifier
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = dominantColor, shape = RoundedCornerShape(20.dp))
                else Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.18f))
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (author.displayName == "From Friends") {
            Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        } else if (author.displayName == "Liked Posts") {
            Icon(Icons.Default.Favorite, contentDescription = null, tint = LikeRed, modifier = Modifier.size(16.dp))
        } else if (author.avatarUrl != null) {
            AsyncImage(model = author.avatarUrl, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(16.dp).clip(CircleShape))
        } else {
            Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White.copy(0.2f)))
        }
        Text(author.displayName.take(16), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FeedChip(name: String, avatarUrl: String?, isSelected: Boolean, liquidGlass: Boolean = false, dominantColor: Color = NeutralGlassTint, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .then(
                if (liquidGlass) Modifier.glassPanel(
                    true, tint = if (isSelected) dominantColor else dominantColor.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(20.dp)
                )
                else Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) Color.White.copy(0.15f) else Color.White.copy(0.06f))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (avatarUrl != null) {
            AsyncImage(model = avatarUrl, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(16.dp).clip(CircleShape))
        }
        Text(name, color = if (isSelected) Color.White else DimGray, fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun HubChip(
    label: String, active: Boolean, liquidGlass: Boolean, modifier: Modifier = Modifier,
    // Item 3/11: tint the rim with the post's own dominant color, same as
    // every other glass surface in the Hub, instead of a hardcoded neutral
    // tint — keeps the whole Hub visually consistent.
    dominantColor: Color = NeutralGlassTint, backdrop: GlassBackdrop? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    @Composable
    fun ChipContent() {
        Box(Modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(
                label, color = if (active) Color.White else DimGray, fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = modifier.height(36.dp), shape = shape, tint = dominantColor, backdrop = backdrop) { ChipContent() }
    } else {
        Box(modifier.height(36.dp).clip(shape).background(Color.White.copy(0.06f))) { ChipContent() }
    }
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Color.White.copy(0.3f), unfocusedBorderColor = Color.White.copy(0.1f),
    cursorColor = Color.White, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent
)

/** Item 4 (Import/Export): naming prompt shown when tapping Settings'
 *  "Export" button — the name typed here becomes both the suggested
 *  filename and the dataset's display name once someone else imports the
 *  resulting file (see MainViewModel.DatasetFile/exportDataset). Deliberately
 *  minimal — a title, one text field, Cancel/Export — mirroring
 *  ReplyDialog's own scaffold rather than introducing a different dialog
 *  shape into the app. */
@Composable
internal fun ExportDatasetNameDialog(
    liquidGlass: Boolean,
    dominantColor: Color,
    backdrop: GlassBackdrop?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val shape = RoundedCornerShape(20.dp)
            @Composable
            fun DialogContent() {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "Name This Dataset", color = Color.White, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Shown to anyone you share this file with when they import it.",
                        color = DimGray, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        placeholder = { Text("e.g. My Tagged Posts", color = DimGray, fontSize = 13.sp) },
                        singleLine = true,
                        colors = fieldColors(),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            modifier = Modifier.weight(1f).height(46.dp)
                        ) { Text("Cancel") }
                        Button(
                            onClick = { onConfirm(name.trim()) },
                            colors = ButtonDefaults.buttonColors(containerColor = dominantColor),
                            modifier = Modifier.weight(1f).height(46.dp)
                        ) { Text("Export", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(modifier = Modifier.fillMaxWidth(0.88f), shape = shape, tint = dominantColor, backdrop = backdrop) { DialogContent() }
            } else {
                Box(Modifier.fillMaxWidth(0.88f).clip(shape).background(OffBlack)) { DialogContent() }
            }
        }
    }
}
