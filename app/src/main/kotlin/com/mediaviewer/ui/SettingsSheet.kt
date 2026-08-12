package com.mediaviewer.ui

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mediaviewer.model.AppMode
import com.mediaviewer.model.BskyFeedInfo
import com.mediaviewer.model.DownloadProgress
import com.mediaviewer.ui.theme.*
import com.mediaviewer.viewmodel.MainViewModel
import kotlin.math.abs

// Item 5: which panel of the Hub is currently showing. This is purely local
// UI state for the sheet itself — separate from `appMode`, which tracks
// which content mode (Bluesky vs e621) is active behind the sheet. SETTINGS
// has no corresponding AppMode; AT_PROTOCOL/E621 mirror AppMode.BLUESKY/
// AppMode.E621 respectively and call onSwitchMode when selected so the rest
// of the app (the feed behind the sheet) stays in sync.
private enum class HubPage { SETTINGS, AT_PROTOCOL, E621 }

@Composable
fun SettingsSheet(
    appMode: AppMode,
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
    onOpenLiveNowPlayer: (com.mediaviewer.model.BlueskyLiveNowStream) -> Unit = {},
    // Bug fix (this session): lets the Hub's AT Protocol page trigger a
    // Mutuals (dmConversations) load/retry itself on compose, the same way
    // it already does for friendsReviews/liveFriends — see the matching
    // comment on AtProtocolPageContent's LaunchedEffect below.
    onEnsureFriends: () -> Unit = {},
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
    fun goToHubPage(target: HubPage, forward: Boolean = hubPages.indexOf(target) >= hubPages.indexOf(hubPage)) {
        hubPageForward = forward
        hubPage = target
        when (target) {
            HubPage.AT_PROTOCOL -> onSwitchMode(AppMode.BLUESKY)
            HubPage.E621         -> onSwitchMode(AppMode.E621)
            HubPage.SETTINGS     -> {}
        }
    }
    // Item 5: wraps around at either end — swiping past e621 lands back on
    // Settings, and swiping back past Settings lands on e621.
    fun advanceHubPage(forward: Boolean) {
        val idx = hubPages.indexOf(hubPage)
        val next = ((idx + if (forward) 1 else -1) + hubPages.size) % hubPages.size
        goToHubPage(hubPages[next], forward = forward)
    }

    // Item: swipe-up-to-feed needs to keep working even on pages that
    // scroll (Settings and, as of item 8, AT Protocol). A plain pointerInput
    // drag detector on the outer Box alone stops getting vertical drags
    // once a scrollable child exists — the child's own scroll gesture
    // claims them first — so it can't reach every page uniformly on its
    // own; NestedScrollConnection is what makes this work everywhere.
    //
    // Bug fix (regression): this used to key off onPostFling's leftover
    // *velocity*, which can't tell "a fast scroll's momentum happened to
    // run out right at the bottom of the list" apart from "the user is
    // deliberately dragging past the edge to leave" — both produce leftover
    // fling velocity, so ordinary scrolling on a scrollable page kept
    // punting the user back to the feed. The fix after that overcorrected
    // by disabling the gesture entirely on any scrollable page, which broke
    // swipe-up-to-feed there completely instead of just fixing the false
    // trigger.
    //
    // The actual fix: onPostScroll's `source` parameter says whether the
    // leftover delta came from an active drag (a finger still down on
    // screen, continuing to pull past the edge) or a fling (a momentum
    // animation coasting to a stop, which is what a fast scroll settling at
    // the bottom looks like). Only NestedScrollSource.Drag means "the user
    // is right now, deliberately, still dragging past the edge" — reacting
    // only to that and ignoring Fling leftover gives a gesture that fires
    // on a genuine pull-past-the-edge on every page, scrollable or not,
    // without ever mistaking momentum-from-scrolling for it.
    val nestedScrollConnection = remember(onSwipeToFeed) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y < -24f) onSwipeToFeed()
                return Offset.Zero
            }
        }
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
            .nestedScroll(nestedScrollConnection)
            .pointerInput(hubPage) {
                var totalX = 0f; var totalY = 0f
                detectDragGestures(
                    onDragStart  = { totalX = 0f; totalY = 0f },
                    onDragEnd    = {
                        when {
                            abs(totalY) > 80f && abs(totalY) > abs(totalX) * 1.2f && totalY < 0 -> onSwipeToFeed()
                            abs(totalX) > 80f && abs(totalX) > abs(totalY) * 1.2f -> advanceHubPage(forward = totalX < 0)
                        }
                    },
                    onDragCancel = { }
                ) { change, dragAmount ->
                    // Only claim horizontal-dominant moves here — vertical
                    // ones are left unconsumed so a scrollable page (the
                    // Settings page) can still scroll normally; the
                    // nestedScrollConnection above picks up swipe-up-to-feed
                    // for that case instead.
                    if (abs(dragAmount.x) > abs(dragAmount.y)) {
                        change.consume()
                    }
                    totalX += dragAmount.x; totalY += dragAmount.y
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))

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
                            onLoadBlueskyLiveNow = onLoadBlueskyLiveNow, onOpenLiveNowPlayer = onOpenLiveNowPlayer,
                            onEnsureFriends = onEnsureFriends
                        )
                        HubPage.E621 -> E621PageContent(
                            e621LoggedIn = e621LoggedIn, e621SearchTags = e621SearchTags,
                            onSearchE621 = onSearchE621,
                            onShowE621Favorites = onShowE621Favorites, onShowE621Following = onShowE621Following,
                            isLoading = isLoading, onSaveE621Credentials = onSaveE621Credentials,
                            liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = backdrop
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
                modifier = Modifier.padding(bottom = 20.dp, top = 4.dp)
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
        // Feature (this session): the Hub isn't supposed to scroll — a
        // scrollable inner Column was fighting with the swipe gesture above
        // (page-switch left/right, swipe-up-to-feed) for the same vertical
        // drag events, and the nestedScrollConnection above only existed to
        // patch around that conflict. Removing the scroll entirely removes
        // the conflict at the source instead of continuing to patch around
        // it — the swipe handling itself is untouched.
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
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

        Spacer(Modifier.height(8.dp))
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
    onOpenLiveNowPlayer: (com.mediaviewer.model.BlueskyLiveNowStream) -> Unit = {},
    onEnsureFriends: () -> Unit = {}
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
    var bskyId by remember { mutableStateOf("") }
    var bskyPw by remember { mutableStateOf("") }

    // Feature (this session): same fix as the Settings page above — no
    // scroll on this Column anymore, so it can't fight the swipe gesture
    // for vertical drag events. This does mean the Mutuals/Reviews/
    // Livestreams sections need to fit on one screen without scrolling —
    // the card/skeleton sizing this session was tightened up specifically
    // with that in mind.
    Column(Modifier.fillMaxSize()) {
        if (!bskyLoggedIn) {
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
            return@Column
        }

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
                            Text(friend.displayName, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

        // ── Item 8: Latest Reviews From Mutuals — its own section, and now
        // scoped specifically to Mutuals (not everyone followed) per
        // feedback: it reuses the exact same dmConversations list as the
        // row above (loaded once via ensureDmConversationsLoadedSuspend),
        // both for speed — mutuals are a much smaller set to fan out
        // review-lookups over than everyone followed — and because a
        // "Reviews from Friends"-style section reads better scoped to
        // people you actually talk to. See BlueskyRepository.
        // getPopfeedReviews for the other half of the speed fix (parallel +
        // cached collection-name lookup, replacing what used to be up to 3
        // sequential requests per account). ─────────────────────────────
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
            Text("Latest Reviews From Mutuals", color = DimGray, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp))
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
        }
        Spacer(Modifier.height(8.dp))
        // Feature (this session): review cards restyled to match the
        // Backlog tab's poster cards (see ProfileOverlay.kt's BacklogCard —
        // same poster aspect ratio, same title-below-poster layout), just
        // with the card's glass bubble extended upward to fit a small
        // author strip (avatar + display name) above the poster, since
        // unlike Backlog these are reviews from other people, not the
        // profile owner's own list — the author needs to be visible on the
        // card itself. Same skeleton-placeholder approach as Mutuals above.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val reviews = friendsReviews.take(20)
            for (i in 0 until maxOf(REVIEW_SKELETON_SLOTS, reviews.size)) {
                val fr = reviews.getOrNull(i)
                when {
                    fr != null -> MutualReviewCard(fr, liquidGlass, dominantColor, onOpenReview)
                    friendsReviewsLoading -> MutualReviewCardSkeleton(liquidGlass, dominantColor)
                    else -> Spacer(Modifier.width(REVIEW_CARD_WIDTH).height(REVIEW_CARD_HEIGHT))
                }
            }
        }

        // ── Item 8/19: Livestreams — everyone the user follows, combining
        // two distinct sources: Streamplace (an AT-Protocol-native
        // streaming service) and Bluesky's own built-in "Live Now" profile
        // badge (an off-platform link to Twitch/YouTube/etc, added this
        // session — see BlueskyLiveNowStream in Models.kt and
        // MainViewModel.loadBlueskyLiveNowIfNeeded). Both render as the same
        // card shape in one merged, combined row so they read as one
        // section rather than two. ─────────────────────────────────────────
        Spacer(Modifier.height(14.dp))
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
            val combined: List<LiveCardSource> =
                liveFriends.map { LiveCardSource.Streamplace(it) } + blueskyLiveNow.map { LiveCardSource.BlueskyLive(it) }
            val stillLoading = liveFriendsLoading || blueskyLiveNowLoading
            for (i in 0 until maxOf(LIVESTREAM_SKELETON_SLOTS, combined.size)) {
                val source = combined.getOrNull(i)
                when {
                    source != null -> LiveCard(source, liquidGlass, onOpenLiveNowPlayer)
                    stillLoading -> {
                        Column(Modifier.width(140.dp)) {
                            ShimmerBox(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp), Modifier.fillMaxWidth().height(78.dp))
                            Column(Modifier.padding(8.dp)) {
                                ShimmerBox(RoundedCornerShape(3.dp), Modifier.fillMaxWidth(0.8f).height(11.dp))
                                Spacer(Modifier.height(5.dp))
                                ShimmerBox(RoundedCornerShape(3.dp), Modifier.fillMaxWidth(0.5f).height(9.dp))
                            }
                        }
                    }
                    else -> {
                        // Honest empty state (same philosophy as Search's
                        // Lists tab) — most people you follow won't be
                        // Streamplace users at any given moment, this isn't
                        // an error. Invisible, same footprint as a real
                        // card, not removed, so the row's height stays
                        // constant whether it ends up with 0 or several.
                        Spacer(Modifier.width(140.dp).height(78.dp + 8.dp + 11.dp + 5.dp + 9.dp + 16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
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
private val REVIEW_CARD_HEIGHT = 206.dp

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

/** Renders one Livestreams card for either source. Streamplace keeps its
 *  existing "open in browser" behavior (its HLS format isn't mapped out for
 *  inline playback); Bluesky Live Now cards open the new inline embed
 *  player overlay instead (see LiveNowPlayerOverlay below) — this is the
 *  literal "embed" this feature was asked for, not just a link out. */
@Composable
private fun LiveCard(source: LiveCardSource, liquidGlass: Boolean, onOpenLiveNowPlayer: (com.mediaviewer.model.BlueskyLiveNowStream) -> Unit) {
    val cardShape = RoundedCornerShape(12.dp)
    val uriHandler = LocalUriHandler.current
    val (thumbUrl, title, subtitle, tint, badgeText, onClick) = when (source) {
        is LiveCardSource.Streamplace -> {
            val s = source.stream
            SixTuple(s.thumbUrl, s.title.ifBlank { "Untitled stream" }, s.authorDisplayName ?: s.authorHandle,
                LikeRed, "LIVE") { uriHandler.openUri("https://stream.place/${s.authorHandle}") }
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
            SixTuple(s.thumbUrl, s.title, s.author.displayName, tint, badge) { onOpenLiveNowPlayer(s) }
        }
    }
    Column(
        Modifier.width(140.dp)
            .then(if (liquidGlass) Modifier.glassPanel(true, shape = cardShape, tint = tint) else Modifier.clip(cardShape).background(tint.copy(0.18f)))
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(78.dp)) {
            if (thumbUrl != null) {
                AsyncImage(model = thumbUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)))
            } else {
                Box(Modifier.fillMaxSize().background(Color.White.copy(0.08f)))
            }
            Box(Modifier.padding(6.dp).clip(RoundedCornerShape(4.dp)).background(tint).padding(horizontal = 5.dp, vertical = 2.dp)) {
                Text(badgeText, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.padding(8.dp)) {
            Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = DimGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Tiny local helper so [LiveCard] can destructure the per-source card data
 *  in one `when` branch instead of a longer if/else with repeated fields. */
private data class SixTuple(
    val thumbUrl: String?, val title: String, val subtitle: String,
    val tint: Color, val badgeText: String, val onClick: () -> Unit
)

/** Feature (this session): the actual "embed" for a Bluesky Live Now
 *  stream — a full-screen glass overlay hosting a WebView that loads the
 *  platform's own embeddable player (Twitch/YouTube), rather than just
 *  bouncing out to the browser like Streamplace still does. Reuses this
 *  file's established overlay conventions: blockClicksBehind on the root so
 *  taps can't fall through to the feed behind it, and a close button in the
 *  same position/style other overlays use. */
@Composable
fun LiveNowPlayerOverlay(stream: com.mediaviewer.model.BlueskyLiveNowStream, onClose: () -> Unit) {
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
                    Text(stream.author.displayName, color = DimGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(0.12f)).clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            // 16:9 embed player — most live platform embeds (Twitch, YouTube)
            // are widescreen regardless of the source stream's own aspect.
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
                            loadUrl(embedUrlFor(stream))
                        }
                    },
                    update = { it.loadUrl(embedUrlFor(stream)) }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Live via ${stream.uri.substringAfter("://").substringBefore("/")}",
                color = DimGray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/** Builds the actual embeddable-player URL for a Live Now link. Twitch's
 *  embed requires a `parent` query param naming the embedding page's host —
 *  Twitch only validates this as a string match, not real domain ownership,
 *  and third-party (non-browser) embedders commonly supply a placeholder
 *  value for exactly this reason since there's no real "page host" inside a
 *  native app's WebView. YouTube just needs the video ID out of any of its
 *  common URL shapes. Anything else (a platform this app doesn't have a
 *  known embed format for) falls back to loading the link directly, which
 *  will render its normal (non-embed) page in the WebView — not a true
 *  inline player, but still viewable without leaving the app. */
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

/** Latest-Reviews-From-Mutuals card: same poster-card layout as the
 *  profile's Backlog tab (see ProfileOverlay.kt's BacklogCard — poster
 *  image, title below it), with the glass bubble extended upward to fit a
 *  small author avatar + display name strip above the poster, since (unlike
 *  Backlog, which is always the profile owner's own list) each of these
 *  reviews is from a different mutual and needs to show whose it is.
 *  Feature (this session): tapping a card now opens the review's own detail
 *  overlay (via onOpenReview -> MainViewModel.openMutualReview), the same
 *  overlay you'd land on by opening that person's profile and tapping the
 *  review there, instead of just opening their profile. The star rating
 *  moved off the title row entirely into a small StarRatingPill (the exact
 *  same one profiles' own Reviews tab uses — see ProfileOverlay.kt) layered
 *  on the poster's top-right corner, and both the author strip and title
 *  row were tightened up considerably per feedback (the strip was reading
 *  as two lines when it only needs one; the title row had room for a
 *  rating line it no longer needs). */
@Composable
private fun MutualReviewCard(
    fr: com.mediaviewer.model.FriendPopfeedReview,
    liquidGlass: Boolean,
    dominantColor: Color,
    onOpenReview: (com.mediaviewer.model.FriendPopfeedReview) -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(REVIEW_CARD_WIDTH)
            .then(reviewCardBackground(liquidGlass, dominantColor, shape))
            .clickable { onOpenReview(fr) }
    ) {
        // Author strip — tightened to a single line's worth of height (was
        // reading as two lines before): smaller avatar, minimal padding.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val avatarShape = CircleShape
            Box(Modifier.size(14.dp).clip(avatarShape).background(Color.White.copy(0.15f))) {
                if (fr.author.avatarUrl != null) {
                    AsyncImage(model = fr.author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(avatarShape))
                }
            }
            Text(fr.author.displayName, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        // Poster — same aspect ratio as Backlog's poster tiles, with the
        // star rating now living here as a glass pill in the top-right
        // corner instead of as a text line below.
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            if (fr.review.mediaImageUrl != null) {
                AsyncImage(model = fr.review.mediaImageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(Color.White.copy(0.10f)))
            }
            StarRatingPill(
                rating = fr.review.ratingOutOf5, liquidGlass = liquidGlass, tint = dominantColor,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
            )
        }
        // Title only, below the poster — no rating line (moved above).
        Text(
            fr.review.mediaTitle, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** Shared background modifier for [MutualReviewCard] and its skeleton twin —
 *  factored out so the skeleton can never visually drift from the real
 *  card's shape/clip/glass treatment again (this was the cause of the
 *  "placeholder doesn't match the real card's shape" report: the skeleton
 *  was using a flat clip+background while the real card used glassPanel,
 *  which renders its own distinct bounds/corner treatment). */
@Composable
private fun reviewCardBackground(liquidGlass: Boolean, dominantColor: Color, shape: RoundedCornerShape): Modifier =
    if (liquidGlass) Modifier.glassPanel(true, tint = dominantColor, shape = shape)
    else Modifier.clip(shape).background(Color.White.copy(0.06f))

/** Skeleton twin of [MutualReviewCard] — same background treatment (see
 *  [reviewCardBackground]) and the same three-region layout/sizing, so the
 *  row's height/width/shape never shifts when real cards arrive. */
@Composable
private fun MutualReviewCardSkeleton(liquidGlass: Boolean, dominantColor: Color) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(REVIEW_CARD_WIDTH).then(reviewCardBackground(liquidGlass, dominantColor, shape))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ShimmerBox(CircleShape, Modifier.size(14.dp))
            ShimmerBox(RoundedCornerShape(3.dp), Modifier.weight(1f).height(9.dp))
        }
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            ShimmerBox(RoundedCornerShape(0.dp), Modifier.matchParentSize())
        }
        ShimmerBox(RoundedCornerShape(3.dp), Modifier.fillMaxWidth(0.75f).height(11.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp))
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
    backdrop: GlassBackdrop?
) {
    var e621User by remember { mutableStateOf("") }
    var e621Key by remember { mutableStateOf("") }
    var localE621Tags by remember(e621SearchTags) { mutableStateOf(e621SearchTags) }

    Column(Modifier.fillMaxSize()) {
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
