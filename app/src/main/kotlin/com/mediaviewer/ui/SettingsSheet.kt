package com.mediaviewer.ui

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp
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
private enum class HubPage { SETTINGS, AT_PROTOCOL, E621 }

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
    selfAvatarUrl: String? = null
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
    var hubPage by remember {
        mutableStateOf(if (appMode == AppMode.BLUESKY) HubPage.AT_PROTOCOL else HubPage.E621)
    }
    // Tracks the direction of the most recent page change, since the same
    // pair of states can mean either direction once wraparound is involved
    // (e.g. Settings -> e621 is "forward" via a wrap-around swipe, but
    // "backward" if you just tapped the e621 chip directly) — inferring
    // direction from the state pair alone is ambiguous, so it's tracked
    // explicitly instead.
    var hubPageForward by remember { mutableStateOf(true) }
    val hubPages = remember { listOf(HubPage.SETTINGS, HubPage.AT_PROTOCOL, HubPage.E621) }
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
    // Bug fix (per feedback): the actual AppMode switch — and therefore any
    // feed load/refresh — now only happens right here, at the moment the
    // user actually leaves the Hub for the feed, based on whichever Hub
    // page they're leaving from. Browsing between Hub pages itself
    // (including landing on e621, even by accident) never touches the
    // feed. Settings has no corresponding mode, so returning to the feed
    // from Settings leaves whatever mode was already active untouched — it
    // was never changed just by visiting Settings in the first place.
    //
    // Item (this session): this used to fire from a swipe-up gesture
    // (detected both via a raw pointerInput drag on the outer Box and via
    // NestedScrollConnection so it kept working on scrollable pages). Per
    // feedback, both the swipe-up-to-feed gesture and the swipe-left/right
    // page-switch gesture have been removed entirely to avoid accidental
    // triggers — this same logic now runs from the explicit "Return to
    // Feed" button at the bottom of each Hub page instead (see
    // ReturnToFeedBar below). Hub-page switching is unaffected: it was
    // always also reachable via the HubChip taps at the top, which this
    // doesn't touch.
    fun onReturnToFeed() {
        when (hubPage) {
            HubPage.AT_PROTOCOL -> onSwitchMode(AppMode.BLUESKY)
            HubPage.E621         -> onSwitchMode(AppMode.E621)
            HubPage.SETTINGS     -> {}
        }
        onSwipeToFeed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                // Item 3/11: the Hub's background gradient now reflects the
                // currently-viewed post's own dominant color, same as the
                // main feed's post background gradient, instead of a
                // hardcoded neutral tint.
                if (liquidGlass) Modifier.background(postBackgroundBrush(dominantColor))
                else Modifier.background(OledBlack)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Hub header: 3-way page switcher — same visual language as the
            // 3-button quick-access row (always-visible glass rim, equal
            // width), differing only in that the active page's text is white.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Item 3/11: rims now reflect the post's own dominant color
                // (the same `dominantColor` every other glass surface in the
                // Hub uses), instead of a hardcoded neutral tint — keeps
                // every button/chip in the Hub visually consistent.
                HubChip("Settings", hubPage == HubPage.SETTINGS, liquidGlass, Modifier.weight(1f), dominantColor, backdrop) { goToHubPage(HubPage.SETTINGS) }
                HubChip("AT Protocol", hubPage == HubPage.AT_PROTOCOL, liquidGlass, Modifier.weight(1f), dominantColor, backdrop) { goToHubPage(HubPage.AT_PROTOCOL) }
                HubChip("e621", hubPage == HubPage.E621, liquidGlass, Modifier.weight(1f), dominantColor, backdrop) { goToHubPage(HubPage.E621) }
            }

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
                        HubPage.SETTINGS -> SettingsPageContent(
                            reducedAnimations = reducedAnimations, onToggleReducedAnimations = onToggleReducedAnimations,
                            hideTextOnlyPosts = hideTextOnlyPosts, onToggleHideTextOnlyPosts = onToggleHideTextOnlyPosts,
                            liquidGlass = liquidGlass, onToggleLiquidGlass = onToggleLiquidGlass,
                            liquidGlassIntensity = liquidGlassIntensity, onSetLiquidGlassIntensity = onSetLiquidGlassIntensity,
                            glassRimIntensity = glassRimIntensity, onSetGlassRimIntensity = onSetGlassRimIntensity,
                            translationEnabled = translationEnabled, translationTargetLang = translationTargetLang,
                            onToggleTranslation = onToggleTranslation, onSelectTranslationLanguage = onSelectTranslationLanguage,
                            customFontName = customFontName, onPickFontFile = onPickFontFile, onResetFont = onResetFont,
                            bskyLoggedIn = bskyLoggedIn, bskyHandle = bskyHandle,
                            e621LoggedIn = e621LoggedIn, e621Username = e621Username,
                            downloadOnLike = downloadOnLike, onToggleDownloadOnLike = onToggleDownloadOnLike,
                            downloadProgress = downloadProgress, onDownloadAllLiked = onDownloadAllLiked, onCancelDownload = onCancelDownload,
                            combineListsAndPacks = combineListsAndPacks, onToggleCombineListsPacks = onToggleCombineListsPacks,
                            autoAddToOnFollow = autoAddToOnFollow, onToggleAutoAddToOnFollow = onToggleAutoAddToOnFollow,
                            onLogoutBluesky = onLogoutBluesky, onLogoutE621 = onLogoutE621,
                            dominantColor = dominantColor, backdrop = backdrop
                        )
                        HubPage.AT_PROTOCOL -> AtProtocolPageContent(
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
                            friendsBlogs = friendsBlogs, onOpenBlog = onOpenBlog,
                            onRefreshHub = onRefreshHub,
                            onReturnToFeed = { onReturnToFeed() },
                            hasVisitedFeed = hasVisitedFeed
                        )
                        HubPage.E621 -> E621PageContent(
                            e621LoggedIn = e621LoggedIn, e621SearchTags = e621SearchTags,
                            onSearchE621 = onSearchE621,
                            onShowE621Favorites = onShowE621Favorites, onShowE621Following = onShowE621Following,
                            isLoading = isLoading, onSaveE621Credentials = onSaveE621Credentials,
                            liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = backdrop,
                            onReturnToFeed = { onReturnToFeed() },
                            hasVisitedFeed = hasVisitedFeed
                        )
                    }
                }
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

// ── Settings page (item 5/6): universal, mode-independent settings. Its own
// scroll container, isolated from the AT Protocol/e621 pages' feed rows —
// item 5's "independently scrollable, can't be scrolled up into a feed" is
// satisfied structurally: this page simply never contains any feed content.
// Item 6: rows here are more compact than the mode pages' — no description
// subtext, tighter padding — and the two translation settings are merged
// into a single bubble with an internal divider. ──────────────────────────
@Composable
private fun SettingsPageContent(
    reducedAnimations: Boolean,
    onToggleReducedAnimations: (Boolean) -> Unit,
    hideTextOnlyPosts: Boolean,
    onToggleHideTextOnlyPosts: (Boolean) -> Unit,
    liquidGlass: Boolean,
    onToggleLiquidGlass: (Boolean) -> Unit,
    liquidGlassIntensity: Float,
    onSetLiquidGlassIntensity: (Float) -> Unit,
    // Bug fix: independent rim/outline strength dial, split out from the
    // background dial above.
    glassRimIntensity: Float,
    onSetGlassRimIntensity: (Float) -> Unit,
    translationEnabled: Boolean,
    translationTargetLang: String,
    onToggleTranslation: (Boolean) -> Unit,
    onSelectTranslationLanguage: (String) -> Unit,
    customFontName: String?,
    onPickFontFile: (android.net.Uri) -> Unit,
    onResetFont: () -> Unit,
    // Item: every setting that used to live on the AT Protocol/e621 pages
    // (below their 6-button/3-button grids) now lives here instead, grouped
    // under their own compact section dividers, alongside the universal
    // "App Settings" above.
    bskyLoggedIn: Boolean,
    bskyHandle: String,
    e621LoggedIn: Boolean,
    e621Username: String,
    downloadOnLike: Boolean,
    onToggleDownloadOnLike: (Boolean) -> Unit,
    downloadProgress: DownloadProgress?,
    onDownloadAllLiked: () -> Unit,
    onCancelDownload: () -> Unit,
    combineListsAndPacks: Boolean,
    onToggleCombineListsPacks: (Boolean) -> Unit,
    autoAddToOnFollow: Boolean,
    onToggleAutoAddToOnFollow: (Boolean) -> Unit,
    onLogoutBluesky: () -> Unit,
    onLogoutE621: () -> Unit,
    dominantColor: Color,
    backdrop: GlassBackdrop?
) {
    @Composable
    fun CompactRow(content: @Composable RowScope.() -> Unit) {
        // Item 5: half the previous vertical padding — the switch rows were
        // taller than they needed to be.
        val rowModifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
        if (liquidGlass) {
            LiquidGlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), tint = dominantColor, backdrop = backdrop) {
                Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = content)
            }
        } else {
            Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = content)
        }
    }

    // Item 5: Material3's Switch has no compact size variant, so this fixes
    // the switch's actual layout footprint to roughly two-thirds its default
    // size via an outer fixed-size Box, then visually scales the real Switch
    // down to fit inside it — constraining the outer Box (not just visually
    // scaling the Switch itself) is what actually shrinks the row, since
    // Modifier.scale alone only affects drawing, not the space reserved
    // during layout.
    @Composable
    fun CompactSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Box(modifier = Modifier.size(width = 36.dp, height = 22.dp), contentAlignment = Alignment.Center) {
            Switch(
                checked = checked, onCheckedChange = onCheckedChange,
                modifier = Modifier.scale(0.7f),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = VoteGreen,
                    uncheckedThumbColor = DimGray, uncheckedTrackColor = Color.White.copy(0.1f))
            )
        }
    }

    // Item 4/5: Material3's default Slider reserves a large (48dp)
    // accessibility touch target around its thumb — that reserved space,
    // not the vertical padding around it, was what inflated the
    // Background/Outline rows well past the height of the surrounding
    // toggle rows. Supplying fully custom thumb/track composables (instead
    // of the default Slider overload, which always draws its thumb inside
    // that reserved touch box) removes it entirely; the row's height then
    // just follows the same tight padding every other compact row uses.
    @Composable
    fun CompactSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
        Slider(
            value = value, onValueChange = onValueChange, valueRange = 0f..1f,
            modifier = modifier.height(20.dp),
            thumb = {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White))
            },
            track = { sliderState ->
                Box(
                    Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth(fraction = sliderState.value.coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(2.dp))
                            .background(VoteGreen)
                    )
                }
            }
        )
    }

    // Item: compact section divider — smaller/tighter than the Hub header's
    // own divider-with-label rows, since this separates settings sub-groups
    // within a single already-scrollable page rather than distinct Hub pages.
    @Composable
    fun SectionDivider(label: String) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.1f))
            Text(label, color = DimGray, fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp))
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.1f))
        }
    }

    Column(
        // Bug fix (revert per feedback — Hub is meant to scroll again): this
        // used to be a non-scrolling Column with a comment explaining that
        // scroll had been removed to avoid fighting the swipe gesture. The
        // user has since reconsidered and wants the Hub scrollable again.
        // Item (this session): the swipe-up-to-feed/swipe-to-switch-page
        // gestures this comment used to reference are gone entirely now (see
        // onReturnToFeed above), so this scrollable Column no longer needs
        // to coexist with anything competing for the same drag gestures.
        // Order matters: scroll comes before padding so the padding scrolls
        // with the content rather than staying fixed.
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionDivider("App Settings")

        CompactRow {
            Text("Hide Text Only Posts", color = Color.White, fontSize = 14.sp)
            CompactSwitch(checked = hideTextOnlyPosts, onCheckedChange = onToggleHideTextOnlyPosts)
        }

        CompactRow {
            Text("Reduced Animations", color = Color.White, fontSize = 14.sp)
            CompactSwitch(checked = reducedAnimations, onCheckedChange = onToggleReducedAnimations)
        }

        // Item 4: Glass Theme + the Background/Outline intensity sliders are
        // merged into a single bubble with internal dividers, the same way
        // Translate Post Text + Translate To are — instead of separate
        // bubbles for the on/off toggle and each slider.
        val glassShape = RoundedCornerShape(14.dp)
        @Composable
        fun GlassBubbleContent() {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Glass Theme", color = Color.White, fontSize = 14.sp)
                    CompactSwitch(checked = liquidGlass, onCheckedChange = onToggleLiquidGlass)
                }
                if (liquidGlass) {
                    // Item 4: "Background" controls the blur/magnify/tint
                    // behind a panel; "Outline" controls the colored rim
                    // border around it — split into two independent dials
                    // instead of one slider affecting both.
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Bug fix: a fixed 76.dp width wasn't wide enough for
                        // "Background" in the app's (wider, pixel-style)
                        // custom font, so it wrapped to two lines and blew
                        // out the row's height. widthIn(min=) instead of a
                        // hard width lets the label grow just enough to fit
                        // on one line without wrapping, while still lining
                        // up with "Outline" below it.
                        Text("Background", color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false,
                            modifier = Modifier.widthIn(min = 74.dp))
                        CompactSlider(value = liquidGlassIntensity, onValueChange = onSetLiquidGlassIntensity, modifier = Modifier.weight(1f))
                        Text("${(liquidGlassIntensity * 100).toInt()}%", color = DimGray, fontSize = 12.sp,
                            modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Outline", color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false,
                            modifier = Modifier.widthIn(min = 74.dp))
                        CompactSlider(value = glassRimIntensity, onValueChange = onSetGlassRimIntensity, modifier = Modifier.weight(1f))
                        Text("${(glassRimIntensity * 100).toInt()}%", color = DimGray, fontSize = 12.sp,
                            modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                    }
                }
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(modifier = Modifier.fillMaxWidth(), shape = glassShape, tint = dominantColor, backdrop = backdrop) { GlassBubbleContent() }
        } else {
            Box(Modifier.fillMaxWidth().clip(glassShape).background(Color.White.copy(0.04f))) { GlassBubbleContent() }
        }

        // Item 6: Translate Post Text + Translate To merged into one bubble
        // with an internal divider, instead of two separate ones.
        val translateShape = RoundedCornerShape(14.dp)
        @Composable
        fun TranslateBubbleContent() {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Translate Post Text", color = Color.White, fontSize = 14.sp)
                    CompactSwitch(checked = translationEnabled, onCheckedChange = onToggleTranslation)
                }
                if (translationEnabled) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    var langMenuExpanded by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Translate To", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Box {
                            Row(
                                modifier = Modifier.clickable { langMenuExpanded = true },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    com.mediaviewer.util.TranslationManager.SUPPORTED_LANGUAGES
                                        .firstOrNull { it.first == translationTargetLang }?.second
                                        ?: com.mediaviewer.util.TranslationManager.displayNameFor(translationTargetLang),
                                    color = VoteGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                )
                            }
                            DropdownMenu(expanded = langMenuExpanded, onDismissRequest = { langMenuExpanded = false }) {
                                com.mediaviewer.util.TranslationManager.SUPPORTED_LANGUAGES.forEach { (tag, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = { onSelectTranslationLanguage(tag); langMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(modifier = Modifier.fillMaxWidth(), shape = translateShape, tint = dominantColor, backdrop = backdrop) { TranslateBubbleContent() }
        } else {
            Box(Modifier.fillMaxWidth().clip(translateShape).background(Color.White.copy(0.04f))) { TranslateBubbleContent() }
        }

        // Item 6: App Font — the label stays put ("App Font") instead of
        // being replaced by the imported font's name; when a custom font is
        // active, its name shows on its own row below a divider, the same
        // bubble-with-divider pattern as Translate Post Text/Translate To
        // and Glass Theme/Background/Outline above.
        val fontShape = RoundedCornerShape(14.dp)
        run {
            val fontPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) onPickFontFile(uri)
            }
            @Composable
            fun FontBubbleContent() {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("App Font", color = Color.White, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (customFontName != null) {
                                Text("Reset", color = Color(0xFFEF5350), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clickable(onClick = onResetFont))
                            }
                            Text("Choose File", color = VoteGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { fontPickerLauncher.launch("*/*") })
                        }
                    }
                    if (customFontName != null) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Font: $customFontName", color = DimGray, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(modifier = Modifier.fillMaxWidth(), shape = fontShape, tint = dominantColor, backdrop = backdrop) { FontBubbleContent() }
            } else {
                Box(Modifier.fillMaxWidth().clip(fontShape).background(Color.White.copy(0.04f))) { FontBubbleContent() }
            }
        }

        // ── AT Protocol Settings (moved from the AT Protocol page) ──────
        if (bskyLoggedIn) {
            SectionDivider("AT Protocol Settings")

            CompactRow {
                Text("Download When Liked", color = Color.White, fontSize = 14.sp)
                CompactSwitch(checked = downloadOnLike, onCheckedChange = onToggleDownloadOnLike)
            }
            CompactRow {
                Text("Merge Lists & Starter Packs", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
                CompactSwitch(checked = combineListsAndPacks, onCheckedChange = onToggleCombineListsPacks)
            }
            CompactRow {
                Text("Show \"Add To\" After Following", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
                CompactSwitch(checked = autoAddToOnFollow, onCheckedChange = onToggleAutoAddToOnFollow)
            }

            val progBsky = downloadProgress
            @Composable
            fun DownloadAllLikedContent() {
                Box(Modifier.fillMaxSize().clickable { if (progBsky?.isRunning != true) onDownloadAllLiked() }, contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            progBsky?.isRunning == true        -> "Downloading… ${progBsky.count} queued"
                            progBsky != null && progBsky.count > 0 -> "Done — ${progBsky.count} queued"
                            else                                -> "Download All Liked Media"
                        },
                        color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                    if (progBsky?.isRunning == true) {
                        IconButton(onClick = onCancelDownload, modifier = Modifier.align(Alignment.CenterEnd).size(40.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = DimGray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(Modifier.fillMaxWidth().height(44.dp), tint = dominantColor, backdrop = backdrop) { DownloadAllLikedContent() }
            } else {
                Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(0.08f))) { DownloadAllLikedContent() }
            }

            CompactRow {
                Text("Logged in as @$bskyHandle", color = DimGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Logout", color = Color(0xFFEF5350), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onLogoutBluesky))
            }
        }

        // ── e621 Settings (moved from the e621 page) ─────────────────────
        if (e621LoggedIn) {
            SectionDivider("e621 Settings")

            CompactRow {
                Text("Download When Favorited", color = Color.White, fontSize = 14.sp)
                CompactSwitch(checked = downloadOnLike, onCheckedChange = onToggleDownloadOnLike)
            }

            val progE621 = downloadProgress
            @Composable
            fun DownloadAllSavedContent() {
                Box(Modifier.fillMaxSize().clickable { if (progE621?.isRunning != true) onDownloadAllLiked() }, contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            progE621?.isRunning == true            -> "Downloading… ${progE621.count} queued"
                            progE621 != null && progE621.count > 0 -> "Done — ${progE621.count} queued"
                            else                                    -> "Download All Saved Media"
                        },
                        color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                    if (progE621?.isRunning == true) {
                        IconButton(onClick = onCancelDownload, modifier = Modifier.align(Alignment.CenterEnd).size(40.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = DimGray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(Modifier.fillMaxWidth().height(44.dp), tint = dominantColor, backdrop = backdrop) { DownloadAllSavedContent() }
            } else {
                Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(0.08f))) { DownloadAllSavedContent() }
            }

            CompactRow {
                Text("Logged in as @$e621Username", color = DimGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Logout", color = Color(0xFFEF5350), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onLogoutE621))
            }
        }

        Spacer(Modifier.height(16.dp))
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
    hasVisitedFeed: Boolean = false
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
    // Bug fix (item 1): "Return to Feed" used to be the last child of this
    // same scrolling Column, so it scrolled away with everything else
    // instead of staying reachable — it's meant to replace a swipe-up
    // gesture, which should work from anywhere on the page, not just the
    // very bottom of a long scroll. The scrollable content now lives in
    // its own Box layer with bottom padding reserved for the bar's height,
    // and the bar itself is a second Box layer pinned to BottomCenter,
    // fixed in place on screen the way the screenshot expects.
    // Bug fix (per feedback — Return to Feed/Refresh should read as a plain
    // card like the Settings/AT Protocol/e621 chips above, not a live
    // reflective panel): this bar used to read from its own live,
    // every-frame-recorded backdrop layer (mirroring whatever was actually
    // scrolling underneath it), which made it look and feel distinct from
    // those chips. It's now passed `backdrop = null` at both call sites
    // below, same as those chips effectively render — a still frosted tint
    // + rim, no live capture — so the whole Hub reads as one consistent
    // "card" visual language. The recording machinery that used to feed it
    // is gone along with it.

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = RETURN_TO_FEED_BAR_RESERVED_HEIGHT)
    ) {

        // Item: every setting that used to live below the 6-button grid
        // (Download When Liked, Merge Lists & Packs, Show Add To After
        // Following, Download All Liked Media, Logged in/Logout) has moved
        // into the Settings page's "AT Protocol Settings" section — this
        // page holds navigation: the feed row, quick-access buttons, and
        // (item 8) the Friends and Livestreams sections below them.
        // Item 7: round search bar + a separate circular glass search
        // button, both open the full-screen SearchOverlay — this app has no
        // inline search of its own, it's purely an entry point.
        // Item 1: a "Search" section divider above it, matching the "Feeds"
        // divider's own style below, instead of dropping straight into the
        // search row with no label.
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
            Text("Search", color = DimGray, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp))
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val barShape = RoundedCornerShape(22.dp)
            @Composable
            fun SearchBarContent() {
                Row(
                    Modifier.fillMaxSize().clickable(onClick = onOpenSearch).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = DimGray, modifier = Modifier.size(16.dp))
                    // Item 1: just "Search" — the "Bluesky" text is redundant
                    // now that the section divider above already labels it.
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
                            Box(
                                Modifier.size(52.dp)
                                    .then(if (liquidGlass) Modifier.glassPanel(true, shape = avatarShape, tint = dominantColor) else Modifier.clip(avatarShape).background(Color.White.copy(0.1f))),
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
        val reviewsRecency = friendsReviews.firstOrNull()?.review?.createdAt ?: ""
        val blogsRecency = friendsBlogs.firstOrNull()?.blog?.createdAt ?: ""

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
            if (friendsReviews.isEmpty()) return
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
                friendsReviews.take(20).forEach { fr -> MutualReviewCard(fr, liquidGlass, onOpenReview) }
            }
        }

        // ── Item: Blogs — same Subscribe-list model as Reviews, its own
        // separate list (see ProfileOverlay.kt's Blogs sub-row).
        @Composable
        fun BlogsSectionContent() {
            if (friendsBlogs.isEmpty()) return
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
                friendsBlogs.take(20).forEach { fb ->
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
                        HubAuthorBubble(displayName = fb.author.displayName, avatarUrl = fb.author.avatarUrl, liquidGlass = liquidGlass, tint = blogTint, cardWidth = HUB_BLOG_CARD_HEIGHT)
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

        val sectionOrder = remember(hasCurrentLive, reviewsRecency, blogsRecency) {
            val nonLive = listOf("reviews" to reviewsRecency, "blogs" to blogsRecency)
                .sortedByDescending { it.second }.map { it.first }
            if (hasCurrentLive) listOf("live") + nonLive else nonLive + listOf("live")
        }
        sectionOrder.forEach { key ->
            when (key) {
                "live" -> LiveSectionContent()
                "reviews" -> ReviewsSectionContent()
                "blogs" -> BlogsSectionContent()
            }
        }

        Spacer(Modifier.height(8.dp))
    }
    // Bug fix (item 1): pinned in its own Box layer above the scroll
    // content instead of scrolling away with it — see the matching comment
    // on this function's outer Box/Column split above.
    // Bug fix (per feedback — Return to Feed/Refresh should sit flush with
    // the Settings/AT Protocol/e621 chips above, not further inset): this
    // Box used to have no horizontal padding of its own, so it spanned the
    // literal screen edge — LESS inset than the header chip row, which
    // uses 14.dp (see the HubChip Row above) — leaving the two rows
    // visually misaligned. Matching that same 14.dp here lines this bar's
    // left/right edges up exactly with the chips'.
    // Bug fix (per feedback — too much air between this bar and the
    // "Created by" credit below it, more than between that credit and the
    // gesture bar): this used to sit 8.dp off the bottom of the page's own
    // fillMaxSize() Box, which — combined with the credit's own top
    // padding — read as a bigger gap above the credit than below it.
    // Dropped to 2.dp so the bar sits a little lower, right up against the
    // credit, matching (rather than exceeding) the credit's own bottom
    // margin.
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 2.dp)) {
        ReturnToFeedBar(liquidGlass = liquidGlass, tint = dominantColor, backdrop = null,
            onReturnToFeed = onReturnToFeed, onRefresh = onRefreshHub, hasVisitedFeed = hasVisitedFeed)
    }
    }
}

// Bug fix (item 1): the scrolling content's bottom padding reserved for the
// pinned ReturnToFeedBar — kept a little larger than the bar's own visual
// height so the last real section never sits flush against/behind it.
private val RETURN_TO_FEED_BAR_RESERVED_HEIGHT = 72.dp

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
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
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

/** Circular liquid-glass refresh button, now living beside the "Return to
 *  Feed" bar at the bottom of the AT Protocol page (moved down from the
 *  "Mutuals" divider) — re-checks Mutuals, Reviews, and Blogs against the
 *  network on tap. Spins briefly on tap for feedback since the underlying
 *  fetch has no progress/loading state surfaced up to this button
 *  specifically. [size] lets it be matched to whatever it's sitting next to
 *  (the Return to Feed bar's own height). */
@Composable
private fun HubRefreshBubble(
    liquidGlass: Boolean, tint: Color, onRefresh: () -> Unit, size: androidx.compose.ui.unit.Dp = 26.dp,
    modifier: Modifier = Modifier,
    // Item 2: no longer reflective — callers now pass `null` here (same as
    // the Return to Feed bar beside it and the HubUploadBubble on its other
    // side), so this reads as a plain glass card matching the Settings/AT
    // Protocol/e621 chips above rather than a live-reflection panel.
    backdrop: GlassBackdrop? = null
) {
    val scope = rememberCoroutineScope()
    val rotation = remember { Animatable(0f) }
    val shape = CircleShape
    val clickModifier = Modifier
        .size(size)
        .clickable {
            onRefresh()
            scope.launch {
                rotation.snapTo(0f)
                rotation.animateTo(360f, animationSpec = tween(600, easing = LinearEasing))
            }
        }

    @Composable
    fun IconContent() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh Hub", tint = Color.White,
                modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation.value })
        }
    }

    if (liquidGlass) {
        LiquidGlassSurface(modifier.then(clickModifier), shape = shape, tint = tint, backdrop = backdrop) { IconContent() }
    } else {
        Box(modifier.then(clickModifier).clip(shape).background(Color.White.copy(0.10f))) { IconContent() }
    }
}

/** Item 3/5: the Hub's own upload placeholder — moved here from the feed's
 *  interaction bar (that bar's old center [UploadPlaceholderButton] is gone;
 *  see ActionRow in MainFeedScreen.kt). A circular "+" bubble, matching
 *  [HubRefreshBubble]'s sizing/shape so the pair reads as symmetric anchors
 *  on either end of the Return to Feed pill. Tapping it opens the same
 *  [GlassDropdownMenu] the interaction bar's "More" button uses, with
 *  upload-flavored placeholder entries (item 5: Post/Blog/Review/Record/Go
 *  Live — none wired to real functionality yet). The "+" itself rotates 45°
 *  clockwise while the menu is open (and back on close) as an open/close
 *  affordance, same idea as a standard "+" -> "x" FAB transform. */
@Composable
private fun HubUploadBubble(
    liquidGlass: Boolean, tint: Color, size: androidx.compose.ui.unit.Dp = 26.dp,
    modifier: Modifier = Modifier, backdrop: GlassBackdrop? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 45f else 0f, animationSpec = tween(220), label = "uploadPlusRotation")
    val shape = CircleShape
    val clickModifier = Modifier.size(size).clickable { expanded = !expanded }

    @Composable
    fun IconContent() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, contentDescription = "Upload", tint = Color.White,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation })
        }
    }

    Box {
        if (liquidGlass) {
            LiquidGlassSurface(modifier.then(clickModifier), shape = shape, tint = tint, backdrop = backdrop) { IconContent() }
        } else {
            Box(modifier.then(clickModifier).clip(shape).background(Color.White.copy(0.10f))) { IconContent() }
        }
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = listOf(
                GlassMenuItem("Post") {},
                GlassMenuItem("Blog") {},
                GlassMenuItem("Review") {},
                GlassMenuItem("Record") {},
                GlassMenuItem("Go Live") {}
            ),
            liquidGlass = liquidGlass, tint = tint
        )
    }
}

/** Bottom-of-page control group that replaces the removed swipe-up-to-feed
 *  gesture: a centered "Return to Feed" glass pill that does exactly what
 *  swiping up used to. When [onRefresh] is supplied (the AT Protocol page,
 *  which owns the Hub refresh action), a refresh bubble sits at its left
 *  edge and the upload bubble (item 3/5) sits at its right edge — both
 *  height-matched to the pill, so the group reads as one centered control
 *  rather than several separate ones. */
@Composable
private fun ReturnToFeedBar(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onReturnToFeed: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    // Feature (this session): "Open Feed" the very first time (before the
    // person has ever been to the feed this session — the app now opens
    // straight on the Hub, see MainViewModel's init{} change), "Return to
    // Feed" from then on — see MainViewModel.hasVisitedFeed's own doc
    // comment for why this is tracked centrally rather than as local
    // per-button state.
    hasVisitedFeed: Boolean = false
) {
    val barHeight = 40.dp
    val shape = RoundedCornerShape(20.dp)
    val label = if (hasVisitedFeed) "Return to Feed" else "Open Feed"
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
    // Item 3: refresh moved from the right side to the left, and a new
    // circular upload placeholder (item 5) now sits on the right in its
    // place — both reserved independently since a caller could in theory
    // supply one without the other (only [onRefresh] is actually optional
    // today; the upload bubble always shows).
    val refreshReserve = if (onRefresh != null) (barHeight + 10.dp) else 0.dp
    val uploadReserve = barHeight + 10.dp

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        if (liquidGlass) {
            LiquidGlassSurface(
                Modifier.fillMaxWidth().padding(start = refreshReserve, end = uploadReserve).height(barHeight).clickable(onClick = onReturnToFeed),
                shape = shape, tint = tint, backdrop = backdrop
            ) {}
        } else {
            Box(
                Modifier.fillMaxWidth().padding(start = refreshReserve, end = uploadReserve).height(barHeight)
                    .clip(shape).background(Color.White.copy(0.08f)).clickable(onClick = onReturnToFeed)
            )
        }
        Text(
            label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center)
        )
        if (onRefresh != null) {
            HubRefreshBubble(liquidGlass, tint, onRefresh, size = barHeight, modifier = Modifier.align(Alignment.CenterStart), backdrop = backdrop)
        }
        HubUploadBubble(liquidGlass, tint, size = barHeight, modifier = Modifier.align(Alignment.CenterEnd), backdrop = backdrop)
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
    onOpenReview: (com.mediaviewer.model.FriendPopfeedReview) -> Unit
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
        HubAuthorBubble(displayName = fr.author.displayName, avatarUrl = fr.author.avatarUrl, liquidGlass = liquidGlass, tint = tint, cardWidth = REVIEW_CARD_WIDTH)
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
private fun HubAuthorBubble(displayName: String, avatarUrl: String?, liquidGlass: Boolean, tint: Color, cardWidth: Dp) {
    val shape = RoundedCornerShape(10.dp)
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

// ── e621 page: login form, search bar, and every e621-specific setting —
// item 5. ──────────────────────────────────────────────────────────────────
@Composable
private fun E621PageContent(
    e621LoggedIn: Boolean,
    e621SearchTags: String,
    onSearchE621: (String) -> Unit,
    onShowE621Favorites: () -> Unit,
    onShowE621Following: () -> Unit,
    isLoading: Boolean,
    onSaveE621Credentials: (String, String) -> Unit,
    liquidGlass: Boolean,
    dominantColor: Color,
    backdrop: GlassBackdrop?,
    // Item (this session): replaces the removed swipe-up-to-feed gesture.
    onReturnToFeed: () -> Unit = {},
    hasVisitedFeed: Boolean = false
) {
    var e621User by remember { mutableStateOf("") }
    var e621Key by remember { mutableStateOf("") }
    var localE621Tags by remember(e621SearchTags) { mutableStateOf(e621SearchTags) }

    // Bug fix (per feedback — Return to Feed/Refresh should read as a plain
    // card like the Settings/AT Protocol/e621 chips above): same fix as
    // AtProtocolPageContent's — the bar's live per-frame backdrop recording
    // is gone; it's passed `backdrop = null` at the call site below instead,
    // matching the chips' still-card look. This page's Hot/Favorites/
    // Following buttons are unaffected — they keep using the feed-level
    // `backdrop` param as before.
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
    ) {
        if (!e621LoggedIn) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                OutlinedTextField(value = e621User, onValueChange = { e621User = it },
                    placeholder = { Text("Username", color = DimGray) },
                    singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = e621Key, onValueChange = { e621Key = it },
                    placeholder = { Text("API Key", color = DimGray) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onSaveE621Credentials(e621User, e621Key) },
                    enabled = e621User.isNotBlank() && e621Key.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    Text("Sign in to e621", fontWeight = FontWeight.SemiBold)
                }
            }
            return@Column
        }

        // Item: Download When Favorited, Download All Saved Media, and the
        // Logged in/Logout row all moved into the Settings page's "e621
        // Settings" section — this page now only holds navigation: search
        // and the three quick-access buttons. Short enough to never need
        // its own scroll, so swipe-up-to-feed keeps working the same simple
        // way it always did here.
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(value = localE621Tags, onValueChange = { localE621Tags = it },
                placeholder = { Text("Search tags…", color = DimGray, fontSize = 13.sp) },
                singleLine = true, colors = fieldColors(),
                modifier = Modifier.weight(1f).height(56.dp))
            Button(onClick = { onSearchE621(localE621Tags) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White),
                modifier = Modifier.height(56.dp)) { Text("Search") }
        }

        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            @Composable
            fun HotContent() {
                Row(
                    modifier = Modifier.fillMaxSize().clickable(onClick = { onSearchE621("order:hot") }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("\uD83D\uDD25", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Hot", color = Color.White, fontSize = 13.sp)
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(Modifier.fillMaxWidth().height(46.dp), tint = dominantColor, backdrop = backdrop) { HotContent() }
            } else {
                Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.08f))) { HotContent() }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable
                fun FavoritesContent() {
                    Row(
                        modifier = Modifier.fillMaxSize().clickable(onClick = onShowE621Favorites),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = BookmarkYellow, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Favorites", color = Color.White, fontSize = 13.sp)
                    }
                }
                if (liquidGlass) {
                    LiquidGlassSurface(Modifier.weight(1f).height(46.dp), tint = dominantColor, backdrop = backdrop) { FavoritesContent() }
                } else {
                    Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.08f))) { FavoritesContent() }
                }
                @Composable
                fun FollowingContent() {
                    Row(
                        modifier = Modifier.fillMaxSize().clickable(onClick = onShowE621Following),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, tint = VoteGreen, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Following", color = Color.White, fontSize = 13.sp)
                    }
                }
                if (liquidGlass) {
                    LiquidGlassSurface(Modifier.weight(1f).height(46.dp), tint = dominantColor, backdrop = backdrop) { FollowingContent() }
                } else {
                    Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.08f))) { FollowingContent() }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
    if (e621LoggedIn) {
        // Bug fix: same edge-alignment fix as the AT Protocol page's
        // ReturnToFeedBar above — 14.dp matches the header chip row.
        // Bug fix: same lower-placement fix as the AT Protocol page's bar above.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 2.dp)) {
            ReturnToFeedBar(liquidGlass = liquidGlass, tint = dominantColor, backdrop = null, onReturnToFeed = onReturnToFeed, hasVisitedFeed = hasVisitedFeed)
        }
    }
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
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Color.White.copy(0.3f), unfocusedBorderColor = Color.White.copy(0.1f),
    cursorColor = Color.White, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent
)
