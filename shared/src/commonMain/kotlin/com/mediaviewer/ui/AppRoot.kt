package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.mediaviewer.model.AppMode
import com.mediaviewer.model.ScreenState
import com.mediaviewer.ui.GlassBackdrop
import com.mediaviewer.ui.LocalGlassIntensity
import com.mediaviewer.ui.LocalGlassRimIntensity
import com.mediaviewer.ui.LocalGlassRimVibrantSecondary
import com.mediaviewer.ui.NeutralGlassTint
import com.mediaviewer.ui.rememberDominantColor
import com.mediaviewer.ui.DmInboxOverlay
import com.mediaviewer.ui.ListPickerDialog
import com.mediaviewer.ui.LiveNowPlayerOverlay
import com.mediaviewer.ui.MainFeedScreen
import com.mediaviewer.ui.CameraNotchButton
import com.mediaviewer.ui.VrmModeScreen
import com.mediaviewer.ui.PixelMatrixOverlay
import com.mediaviewer.ui.PixelPhase
import com.mediaviewer.ui.ProfileOverlay
import com.mediaviewer.ui.fetchDominantColor
import com.mediaviewer.ui.rememberLoadingTransition
import com.mediaviewer.ui.ShatterOverlay
import com.mediaviewer.ui.blockClicksBehind
import com.mediaviewer.ui.recordLastTap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.mediaviewer.ui.QuoteRepostDialog
import com.mediaviewer.ui.ReplyDialog
import com.mediaviewer.ui.SearchOverlay
import com.mediaviewer.ui.SendDmDialog
import com.mediaviewer.ui.SettingsExtras
import com.mediaviewer.ui.TaggingOverlay
import com.mediaviewer.ui.theme.MediaViewerTheme
import com.mediaviewer.viewmodel.MainViewModel
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.ui.compat.ActivityResultContracts

/** Longest the first frame waits for your profile color. */
internal const val PROFILE_COLOR_WAIT_MS = 1500L

/**
 * The whole app: every screen, overlay and transition, driven by
 * [MainViewModel]. Android hosts it in MainActivity, iOS in
 * MainViewController.
 */
@Composable
fun AppRoot(viewModel: MainViewModel, pendingProfileLink: String? = null, onProfileLinkHandled: () -> Unit = {}) {
    val context            = com.mediaviewer.ui.compat.LocalContext.current
    val mediaItems         by viewModel.mediaItems.collectAsState()
    val currentIndex       by viewModel.currentIndex.collectAsState()
    val currentItem        by viewModel.currentItem.collectAsState()
    val screenState        by viewModel.screenState.collectAsState()
    val hasVisitedFeed     by viewModel.hasVisitedFeed.collectAsState()
    val appMode            by viewModel.appMode.collectAsState()
    val navDirection       by viewModel.navDirection.collectAsState()
    val reducedAnimations  by viewModel.reducedAnimations.collectAsState()
    val classicProfileTabRow by viewModel.classicProfileTabRow.collectAsState()
    val squareGridRounded by viewModel.squareGridRounded.collectAsState()
    val pinterestThreeColumns by viewModel.pinterestThreeColumns.collectAsState()
    val hateFunBlurNsfw by viewModel.hateFunBlurNsfw.collectAsState()
    val liquidGlass        by viewModel.liquidGlass.collectAsState()
    val liquidGlassIntensity by viewModel.liquidGlassIntensity.collectAsState()
    val glassRimIntensity  by viewModel.glassRimIntensity.collectAsState()
    val glassRimVibrantSecondary by viewModel.glassRimVibrantSecondary.collectAsState()
    val availableFeeds     by viewModel.availableFeeds.collectAsState()
    val selectedFeed       by viewModel.selectedFeedUri.collectAsState()
    val authorFeedState    by viewModel.authorFeedState.collectAsState()
    // Item 9: gates the More menu's "Show more/less like this" to only
    // feeds that can actually act on the interaction signal.
    val supportsFeedInteractions by viewModel.supportsFeedInteractions.collectAsState()
    val comments           by viewModel.comments.collectAsState()
    val commentsLoad       by viewModel.commentsLoading.collectAsState()
    val downloadOnLike     by viewModel.downloadOnLike.collectAsState()
    val downloadProgress   by viewModel.downloadProgress.collectAsState()
    val e621Tags           by viewModel.e621SearchTags.collectAsState()
    val isLoading          by viewModel.isLoading.collectAsState()
    val bskyLoggedIn       by viewModel.bskyLoggedIn.collectAsState()
    val e621LoggedInRaw    by viewModel.e621LoggedIn.collectAsState()
    // iOS leaves e621 out entirely (see FeatureFlags.E621_ENABLED).
    val e621LoggedIn = e621LoggedInRaw && com.mediaviewer.util.FeatureFlags.E621_ENABLED
    val errorMessage       by viewModel.errorMessage.collectAsState()
    val listPickerDid      by viewModel.listPickerTargetDid.collectAsState()
    val reportTarget       by viewModel.reportTarget.collectAsState()
    val reportSubmitting   by viewModel.reportSubmitting.collectAsState()
    val userLists          by viewModel.userLists.collectAsState()
    val userStarterPacks   by viewModel.userStarterPacks.collectAsState()
    val userListsLoading   by viewModel.userListsLoading.collectAsState()
    val lastPickerTab      by viewModel.lastPickerTab.collectAsState()
    val combineListsPacks  by viewModel.combineListsAndPacks.collectAsState()
    val autoAddToOnFollow  by viewModel.autoAddToOnFollow.collectAsState()
    val dmConversations       by viewModel.dmConversations.collectAsState()
    val dmConversationsLoading by viewModel.dmConversationsLoading.collectAsState()
    val sendPopupTarget       by viewModel.sendPopupTarget.collectAsState()
    val sendPopupSelected     by viewModel.sendPopupSelected.collectAsState()
    val sendPopupSending      by viewModel.sendPopupSending.collectAsState()
    val quoteRepostTarget     by viewModel.quoteRepostTarget.collectAsState()
    val quoteRepostSubmitting by viewModel.quoteRepostSubmitting.collectAsState()
    val replyToConvo          by viewModel.replyToConvo.collectAsState()
    val sentByExpanded        by viewModel.sentByExpanded.collectAsState()
    val friendsFeedLoadingOverlay by viewModel.friendsFeedLoadingOverlay.collectAsState()
    val profileOverlay         by viewModel.profileOverlay.collectAsState()
    val selfProfile            by viewModel.selfProfile.collectAsState()
    val appInitialized         by viewModel.appInitialized.collectAsState()
    val hideTextOnlyPosts      by viewModel.hideTextOnlyPosts.collectAsState()
    val bskyDid                by viewModel.bskyDid.collectAsState()
    val dmInboxOpen            by viewModel.dmInboxOpen.collectAsState()
    val dmThread               by viewModel.dmThread.collectAsState()
    val inboxUnreadCount       by viewModel.inboxUnreadCount.collectAsState()
    val inboxOpen              by viewModel.inboxOpen.collectAsState()
    val newChatState           by viewModel.newChatState.collectAsState()
    val composePostOpen        by viewModel.composePostOpen.collectAsState()
    val composePostSubmitting  by viewModel.composePostSubmitting.collectAsState()
    val reviewComposeTarget    by viewModel.reviewComposeTarget.collectAsState()
    val blogEditDraft          by viewModel.blogEditDraft.collectAsState()
    // Item 8: camera-notch button — pending capture + VRM mode.
    val initialComposeImageUri by viewModel.initialComposeImageUri.collectAsState()
    val initialComposeVideoUri by viewModel.initialComposeVideoUri.collectAsState()
    val vrmModeOpen            by viewModel.vrmModeOpen.collectAsState()
    val cameraModeOpen         by viewModel.cameraModeOpen.collectAsState()
    val capturePreview         by viewModel.capturePreview.collectAsState()
    // Item 12 follow-up: DM-thread "shared posts" feed loading overlay.
    val dmFeedLoadingOverlay   by viewModel.dmFeedLoadingOverlay.collectAsState()
    // Item 8: Hub Friends/Livestreams sections.
    val friendsReviews        by viewModel.friendsReviews.collectAsState()
    val friendsReviewsLoading by viewModel.friendsReviewsLoading.collectAsState()
    // Item 12: same cache, handed straight to ProfileOverlay/TitleDetailOverlay
    // for the "who's reviewed this title" tab strip — plain alias here just
    // to keep the ProfileOverlay call site's own param name self-explanatory.
    val friendsReviewsForTitles = friendsReviews
    val reviewSocial           by viewModel.reviewSocial.collectAsState()
    val friendsBlogs           by viewModel.friendsBlogs.collectAsState()

    val liveFriends           by viewModel.liveFriends.collectAsState()
    val liveLinkState         by viewModel.liveLinkState.collectAsState()
    val liveFriendsLoading    by viewModel.liveFriendsLoading.collectAsState()
    val blueskyLiveNow        by viewModel.blueskyLiveNow.collectAsState()
    val blueskyLiveNowLoading by viewModel.blueskyLiveNowLoading.collectAsState()
    val playingLive           by viewModel.playingLive.collectAsState()
    val subscribedReviewDids  by viewModel.subscribedReviewDids.collectAsState()
    val subscribedBlogDids    by viewModel.subscribedBlogDids.collectAsState()
    val followerScanState     by viewModel.followerScanState.collectAsState()
    val followerScanCompletedOnce by viewModel.followerScanCompletedOnce.collectAsState()
    val searchOpen             by viewModel.searchOpen.collectAsState()
    val searchState            by viewModel.searchState.collectAsState()
    // AI Tagging feature
    val taggingOverlayOpen     by viewModel.taggingOverlayOpen.collectAsState()
    val taggingUiState         by viewModel.taggingUiState.collectAsState()
    val hasTaggedDataset       by viewModel.hasTaggedDataset.collectAsState()
    val likedTagSearchResults  by viewModel.likedTagSearchResults.collectAsState()
    val tagSuggestions         by viewModel.tagSuggestions.collectAsState()
    val tagPostWhenLiked       by viewModel.tagPostWhenLiked.collectAsState()
    val importedDatasets       by viewModel.importedDatasets.collectAsState()
    // Reworked Settings page
    val otherBskyAccounts      by viewModel.otherBskyAccounts.collectAsState()
    val showSwitchAccountsRow  by viewModel.showSwitchAccountsRow.collectAsState()
    val accountSwitching       by viewModel.accountSwitching.collectAsState()
    val taggerModelReady       by viewModel.taggerModelReady.collectAsState()
    val taggerModelDownloading by viewModel.taggerModelDownloading.collectAsState()
    val downloadIsE621         by viewModel.downloadIsE621.collectAsState()
    // Phase 4
    val translationEnabled     by viewModel.translationEnabled.collectAsState()
    val translationTargetLang  by viewModel.translationTargetLang.collectAsState()
    val customFontName         by viewModel.customFontName.collectAsState()
    val likeTagPhase           by viewModel.likeTagPhase.collectAsState()
    val likeTagPending         by viewModel.likeTagPending.collectAsState()
    val datasetExportState     by viewModel.datasetExportState.collectAsState()
    // Customize Hub: each Hub list row's loaded members/posts.
    val hubLists               by viewModel.hubLists.collectAsState()
    val titleBacklog           by viewModel.titleBacklog.collectAsState()
    // Settings → UI Customization → loading screens on/off (see UiToggles).
    val loadingScreens = com.mediaviewer.util.UiToggles.loadingScreens

    // Big Update #10: the currently-on-screen post's live backdrop + dominant
    // color, reported up from inside the pager (see PostContent's onBackdropChanged)
    // so overlays that live above the whole pager — Share, Add To — can show the
    // same real-time reflection the in-post glass panels do, instead of a plain
    // static tint.
    var currentBackdrop by remember { mutableStateOf<GlassBackdrop?>(null) }
    // The open profile page's own backdrop/color — Add To opened from a
    // profile blurs the profile, not the post hidden behind it.
    var profileBackdrop by remember { mutableStateOf<GlassBackdrop?>(null) }
    var profileTint by remember { mutableStateOf(NeutralGlassTint) }
    // Profile QR code page: (account, its banner) while open.
    var qrTarget by remember { mutableStateOf<Pair<com.mediaviewer.model.AuthorInfo, String?>?>(null) }
    // Re-reads block state whenever it changes anywhere in the app.
    val blockVersion by com.mediaviewer.util.BlockedAccounts.version.collectAsState()
    // Item 16: "your color" everywhere = the same banner/avatar blend your
    // profile page uses.
    SideEffect {
        com.mediaviewer.ui.SelfProfileColors.bannerUrl = selfProfile?.bannerUrl
        com.mediaviewer.ui.SelfProfileColors.did = selfProfile?.author?.did ?: bskyDid.takeIf { it.isNotBlank() }
        com.mediaviewer.ui.SelfProfileColors.loaded = selfProfile != null
    }
    // Lets profile colors look up an account's banner when only its DID
    // and avatar are known (blog/review cards) — see ProfileColorStore.
    LaunchedEffect(Unit) {
        com.mediaviewer.ui.ProfileColorStore.bannerResolver = { did -> viewModel.fetchBannerUrl(did) }
    }
    // Back from Timeline/Explore returns to the Hub. Registered early, so
    // anything opened on top (a profile, DMs, search, comments…) handles
    // Back first.
    com.mediaviewer.ui.compat.BackHandler(
        enabled = screenState == ScreenState.FEED || screenState == ScreenState.GRID
    ) { viewModel.backFromFeed() }
    val selfProfileTint = com.mediaviewer.ui.rememberSelfTint(selfProfile?.author?.avatarUrl, NeutralGlassTint)
    var currentDominantColor by remember { mutableStateOf(NeutralGlassTint) }
    // The starry page backgrounds stop ticking while VRM mode is open (it
    // needs every bit of CPU/GPU it can get).
    SideEffect { com.mediaviewer.ui.SpaceSkyControl.paused = vrmModeOpen || cameraModeOpen }
    // A bsky.app/profile/... link Stellar was opened with: open that
    // profile once the app has started up (and you're signed in).
    LaunchedEffect(pendingProfileLink, appInitialized, bskyLoggedIn) {
        val actor = pendingProfileLink ?: return@LaunchedEffect
        if (!appInitialized) return@LaunchedEffect
        if (bskyLoggedIn) viewModel.openProfileFromLink(actor.substringBefore('|'), actor.substringAfter('|', "").ifBlank { null })
        onProfileLinkHandled()
    }
    // Reduced Animations → every Compose animation (see util/AppMotion.kt).
    // (Only once the saved settings have loaded, so the launch default
    // can't briefly overwrite the real choice.)
    LaunchedEffect(reducedAnimations, appInitialized) {
        if (appInitialized) com.mediaviewer.ui.compat.applyReducedAnimations(context, reducedAnimations)
    }

    // The audio visualizer is on by default but needs the microphone
    // permission to hear what the phone is playing (nothing is recorded):
    // asked once, the first time the feed is opened.
    val visualizerPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) com.mediaviewer.util.UiToggles.updateAudioVisualizer(false)
        else com.mediaviewer.util.AudioVisualizerEngine.watchPlayerSessions(context)
    }
    LaunchedEffect(screenState) {
        if (screenState == ScreenState.FEED &&
            com.mediaviewer.platform.PlatformFeature.AUDIO_VISUALIZER.isAvailable &&
            com.mediaviewer.util.UiToggles.audioVisualizer &&
            !com.mediaviewer.util.UiToggles.visualizerPermissionAsked &&
            !com.mediaviewer.util.AudioVisualizerEngine.hasPermission(context)
        ) {
            com.mediaviewer.util.UiToggles.markVisualizerPermissionAsked()
            runCatching { visualizerPermission.launch("android.permission.RECORD_AUDIO") }
        }
    }

    // ── Retro pixel-matrix transition/loading overlay ──────────────────────
    // One shared controller drives every scenario described in the design
    // spec: the cold-boot splash, profile-navigation transitions, and
    // opening a feed from the Feeds row. See PixelTransitionOverlay.kt for
    // the state machine and rendering; everything below is just real app
    // events (never artificial timers) driving it.
    // Pixels or Shatter, per Settings → Loading Animation (see LoadingTransition).
    val pixelController = rememberLoadingTransition()
    val rootScope = rememberCoroutineScope()

    // Bug fix (item 3 — Login page/real UI flashing before the loading
    // animation even starts): the real UI (MainFeedScreen, which shows the
    // Login page until auth-restore from prefs finishes) used to be visible,
    // uncovered, for however many frames elapsed between first composition
    // and the pixel overlay's own LaunchedEffect(Unit) actually getting to
    // run — plus however much further into the wipe-in sweep it takes for
    // the pixel grid to reach full coverage (the sweep itself starts nearly
    // empty and fills in gradually, so real content is still visible through
    // its gaps for a portion of that animation too). This scrim is `true`
    // from the very first frame with no LaunchedEffect required to set an
    // initial value — nothing under it is ever reachable — and flips false
    // exactly once, the moment the very first wipe-in genuinely finishes
    // covering the whole screen (phase advancing past WIPE_IN), at which
    // point the pixel grid's own full-opacity LOADING coverage takes over
    // seamlessly with no gap in between.
    var coldLaunchCovered by remember { mutableStateOf(com.mediaviewer.util.UiToggles.loadingScreens) }
    LaunchedEffect(pixelController.phase) {
        if (coldLaunchCovered && pixelController.phase != PixelPhase.HIDDEN && pixelController.phase != PixelPhase.WIPE_IN) {
            coldLaunchCovered = false
        }
    }

    // Scenario A — cold boot: wipe in black the instant the app launches,
    // hue-shift to the logged-in user's own color the instant it's fetched,
    // then wipe out only once BOTH the auth-restore/init sequence has
    // actually finished (appInitialized) AND that color fetch has actually
    // resolved and been applied — not before either one.
    //
    // Bug fix (per feedback — transition used to end way too early / "seems
    // to instantly stop after starting"): `appInitialized` flips true the
    // moment local prefs have merely been *read* and the real loads kicked
    // off (see MainViewModel's init{}) — well before those loads, including
    // `loadSelfProfile()`, have actually finished. This used to gate
    // `selfColorReady` on `appInitialized` directly and treat whatever
    // `selfProfile` happened to be at that exact instant (usually still
    // null) as "no avatar to fetch," unblocking the transition immediately
    // instead of waiting for the real fetch. It now waits for `selfProfile`
    // itself to genuinely settle — populated by startHubBackgroundWarmup's
    // retry loop — before deciding one way or the other; a logged-out
    // session (which never expects a selfProfile at all) still unblocks
    // immediately once appInitialized, since there's truly nothing to wait
    // for there.
    var selfThemeColor by remember { mutableStateOf(Color.Black) }
    var selfColorReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Bug fix (item 1): this used to start from Color.Black. The wipe-in
        // grid is drawn on top of an opaque black cold-launch scrim (see
        // `coldLaunchCovered` below), so a black-on-black wipe is completely
        // invisible — the screen just sits there looking static for the
        // whole WIPE_IN duration, and by the time anything is visible the
        // wipe has already silently finished. Starting from white instead
        // means the "swiping in and covering it with white pixels" motion
        // is actually visible against the black scrim, before it hue-shifts
        // into the user's profile color once that's fetched.
        if (com.mediaviewer.util.UiToggles.loadingScreens) pixelController.start(Color.White, fromBlack = true)
    }
    LaunchedEffect(appInitialized, bskyLoggedIn, selfProfile) {
        if (!appInitialized) return@LaunchedEffect
        if (!bskyLoggedIn) { selfColorReady = true; return@LaunchedEffect }
        val profile = selfProfile ?: return@LaunchedEffect // still loading — wait for the real fetch
        val url = profile.author.avatarUrl
        if (!url.isNullOrBlank()) {
            // The real profile color (banner + avatar blend), not the
            // avatar alone — see ProfileColorStore.
            val c = com.mediaviewer.ui.fetchProfileColors(
                context, profile.author.did, url, profile.bannerUrl, bannerKnown = true
            ).blended
            selfThemeColor = c
            if (pixelController.phase == PixelPhase.WIPE_IN || pixelController.phase == PixelPhase.LOADING) {
                pixelController.updateColor(c)
            }
        }
        selfColorReady = true
    }
    LaunchedEffect(appInitialized, selfColorReady) {
        if (appInitialized && selfColorReady && pixelController.phase != PixelPhase.HIDDEN) pixelController.finish()
    }

    // Dev Tools' loading-animation preview: starts with the intro, in your
    // own colors (see the tap-to-advance layer near the end of AppRoot).
    var devPreviewStep by remember { mutableIntStateOf(0) }
    val devLoadingPreview = com.mediaviewer.util.UiToggles.devLoadingPreview
    LaunchedEffect(devLoadingPreview) {
        if (!devLoadingPreview) return@LaunchedEffect
        devPreviewStep = 0
        rootScope.launch {
            if (pixelController.phase == PixelPhase.HIDDEN) pixelController.start(selfThemeColor)
        }
    }

    // Scenario B — profile navigation: the instant a *new* profile overlay
    // opens, wipe in using the viewer's own theme color; hue-shift to the
    // target profile's color as soon as its avatar resolves; wipe out the
    // instant that profile's data has actually finished loading
    // (loadingProfile flips false).
    //
    // Bug fix (per feedback — playing on an already-loaded profile): a
    // profile overlay is also reused, hidden rather than torn down, when
    // the user pinches into a post from it (see ProfileOverlayState.hidden)
    // — un-hiding it to go back is instant, nothing to load, so it must NOT
    // replay the transition. This used to reset `trackedProfileDid` to null
    // any time the overlay was hidden, which made un-hiding the SAME
    // profile look identical to opening a brand new one next time this
    // effect ran. It's now left untouched while hidden, and this effect
    // exits immediately whenever hidden is true, so the transition only
    // ever plays for a `did` that's genuinely never been tracked before.
    var trackedProfileDid by remember { mutableStateOf<String?>(null) }
    // Bug fix (item 3 — profile flashes on screen, then shows the hub
    // again, before the wipe-in curtain has covered it): this used to be a
    // plain `mutableStateOf(true)` boolean, flipped to `false` and back to
    // `true` from *inside* the LaunchedEffect below. That effect's body only
    // runs *after* Compose has already completed the composition where
    // `profileOverlay` first became non-null — so for exactly that first
    // frame (and every frame until the effect's own `rootScope.launch` gets
    // scheduled and actually runs), the flag was still sitting at its old
    // value (`true`), so the Box below rendered the brand-new, still-loading
    // profile at full size immediately. Only a moment later did the effect
    // finally flip it to `false` (hiding it again, revealing the hub
    // underneath) before the wipe curtain caught up and it reappeared for
    // good — exactly the flash → hub → wipe → profile sequence reported.
    //
    // Fixed by making "armed" a synchronous, pure computation instead of an
    // effect-driven one: a `did` is armed once it's in this set, and set
    // membership is checked directly during composition — so the very first
    // composition that ever sees a new `did` already computes "not armed"
    // and renders at zero size, with no window for a flash. Cleared back to
    // empty whenever the overlay fully closes (mirroring `trackedProfileDid`
    // above) so reopening the same profile later replays the transition
    // instead of skipping it.
    var revealedProfileDids by remember { mutableStateOf<Set<String>>(emptySet()) }
    val profileRevealArmed = profileOverlay?.author?.did?.let { it in revealedProfileDids } ?: true
    // Bug fix (item 4 — color visibly detours through a dull blue-grey
    // before settling on the profile's real color): this used to read
    // `rememberDominantColor(...)`, a separately memoized Composable whose
    // state resets to a hardcoded dark blue-grey placeholder (0xFF2A2A2E)
    // the instant the avatar URL key changes, then updates asynchronously
    // once its own fetch resolves. This effect below runs off the SAME
    // recomposition as that reset and, being a plain state read rather than
    // a suspend call, had no way to wait for the real fetch — it would
    // usually still be showing that placeholder at the exact moment this
    // effect captured `targetColor` and fired `pixelController.updateColor`
    // with it. The genuinely correct color would only arrive later via a
    // second, unrelated recomposition (when `loadingProfile` itself flips,
    // re-running this same effect) — giving the on-screen sequence "viewer
    // color -> blue-grey placeholder -> real color" instead of a single
    // clean hue shift straight to the real color. Fetching the color
    // directly with the same suspend function Scenario A/C already use,
    // right here inside the coroutine that's about to consume it, removes
    // the placeholder step entirely.
    //
    // Bug fix (per feedback — the loading animation stops moving partway
    // through the color-change/swipe-away step): start()/updateColor()/
    // finish() used to be suspended directly inside this LaunchedEffect's
    // own body — but its key list includes `loadingProfile`, which flips
    // false the instant the profile's data actually finishes loading. That
    // is a change to one of THIS effect's own keys, so Compose cancels
    // whichever call happened to be suspended at that exact moment (often
    // exactly the wipe-out) and restarts the effect from scratch, visibly
    // freezing the animation wherever it got cut off. Dispatching the real
    // work onto the stable `rootScope` instead means this effect's body only
    // ever makes a quick, synchronous decision and returns — nothing it
    // kicks off can be cancelled by its own key changing underneath it.
    LaunchedEffect(profileOverlay?.author?.did, profileOverlay?.loadingProfile, profileOverlay?.hidden) {
        val overlay = profileOverlay
        if (overlay == null) { trackedProfileDid = null; revealedProfileDids = emptySet(); return@LaunchedEffect }
        if (overlay.hidden) return@LaunchedEffect
        val isNewProfile = overlay.author.did != trackedProfileDid
        if (isNewProfile) trackedProfileDid = overlay.author.did
        val avatarUrl = overlay.author.avatarUrl
        val stillLoading = overlay.loadingProfile
        rootScope.launch {
            if (!com.mediaviewer.util.UiToggles.loadingScreens) {
                // Loading screens off: show the profile right away; it fills
                // in as its data arrives.
                revealedProfileDids = revealedProfileDids + overlay.author.did
                return@launch
            }
            if (isNewProfile) {
                pixelController.start(selfThemeColor)
                // Wipe-in has now genuinely reached full coverage (start()
                // only returns once phase has advanced past WIPE_IN) —
                // safe to swap the real profile in behind it.
                revealedProfileDids = revealedProfileDids + overlay.author.did
            }
            // The profile's real color (banner + avatar blend, the same one
            // the page itself uses). Until its banner is known that's the
            // color remembered from last time — never the avatar-only guess
            // that the page would then visibly shift away from.
            val loadedProfile = overlay.profile
            val targetColor = if (loadedProfile != null) {
                com.mediaviewer.ui.fetchProfileColors(
                    context, overlay.author.did, avatarUrl ?: loadedProfile.author.avatarUrl,
                    loadedProfile.bannerUrl, bannerKnown = true
                ).blended
            } else com.mediaviewer.ui.ProfileColorStore.get(overlay.author.did)?.blended
                ?: if (!stillLoading && !avatarUrl.isNullOrBlank()) fetchDominantColor(context, avatarUrl) else null
            if (targetColor != null) pixelController.updateColor(targetColor)
            if (!stillLoading && pixelController.phase != PixelPhase.HIDDEN) pixelController.finish()
        }
    }

    // Item 12: set true right before handleSelectFeed switches to FEED, so
    // MainFeedScreen's screenState AnimatedContent can skip its normal
    // SETTINGS -> FEED slide transition for just that one switch (the pixel
    // curtain is already covering the whole screen at that point, so a
    // slide underneath it is pure redundant motion). Reset back to false
    // once FEED has actually been reached, so the next genuine "Return to
    // Feed" tap gets its slide animation back.
    var skipFeedEntryAnim by remember { mutableStateOf(false) }
    LaunchedEffect(screenState) {
        if (screenState == ScreenState.FEED || screenState == ScreenState.GRID) skipFeedEntryAnim = false
    }

    // Scenario C — opening a feed from the Feeds row (item 4/7): tapping a
    // *different* feed chip plays the same transition while the feed
    // actually loads, then hue-shifts to that feed's own first post before
    // revealing it — and, per feedback, the screen no longer switches to
    // FEED until that load has genuinely finished (it used to switch
    // immediately, scrolling into a feed that hadn't loaded yet). Tapping
    // "Return to Feed"/swipe-up (onSwipeToFeed, wired separately in
    // MainFeedScreen — never routes through this function) is deliberately
    // NOT wrapped here: that's just scrolling into an already-loaded feed
    // and should stay instant.
    // The Hub's Timeline/Explore buttons open a picked feed in that view;
    // picking a feed from Explore mode's own tab row stays in Explore.
    // Feeds open instantly — no loading transition (those are for profiles
    // and app launch now). selectFeedFromAnyContext clears the previous
    // feed's posts synchronously when it has to load a new one, so the
    // feed/grid shows placeholders instead of the old feed's content until
    // the new posts arrive (see MainViewModel.selectFeed).
    val handleOpenFeed: (String?, ScreenState) -> Unit = { uri, targetScreen ->
        viewModel.selectFeedFromAnyContext(uri)
        viewModel.setScreen(targetScreen)
    }
    val handleSelectFeed: (String?) -> Unit = { uri -> handleOpenFeed(uri, ScreenState.GRID) }

    // Item 26: makes the glass-intensity dial reach every LiquidGlassSurface/
    // glassPanel below without threading a Float through every composable's
    // parameter list.
    CompositionLocalProvider(
        LocalGlassIntensity provides liquidGlassIntensity,
        LocalGlassRimIntensity provides glassRimIntensity,
        LocalGlassRimVibrantSecondary provides glassRimVibrantSecondary,
        // "I Hate Fun": every tile, grid, search result and page reads this.
        com.mediaviewer.ui.LocalHateFunBlurNsfw provides hateFunBlurNsfw
    ) {
    Box(Modifier.fillMaxSize().recordLastTap(pixelController.shatter)) {
        // Feature request #8: lifted out of MainFeedScreen so a multi-image
        // grid tile in ProfileOverlay's Pinterest/All layout can seed which
        // image within a post's group the pager should open on, before
        // handing off to MainFeedScreen to actually show it (see
        // onSeedSubImageIndex below and ProfileOverlay.onSeedSubImageIndex).
        val subImageIndices = remember { mutableStateMapOf<String, Int>() }
        // Item: VRM mode should "cut all processing from the rest of the
        // app" while it's open, not just visually cover it. Before this,
        // MainFeedScreen stayed composed (and therefore its image/GIF
        // loaders, any autoplaying video, and its own recomposition loop
        // all kept running) the entire time VrmModeScreen was drawn on
        // top of it — competing with VRM mode's own camera + three
        // MediaPipe landmarkers + Filament rendering for CPU, GPU, and
        // memory the whole time, and a real contributor to the OOMs and
        // jank VRM mode was seeing. Unmounting it entirely while VRM mode
        // is open (rather than just hiding it) lets Compose actually
        // cancel its in-flight image loads and dispose its players
        // instead of leaving them running off-screen. Scroll position
        // inside MainFeedScreen isn't preserved across this — an
        // acceptable trade for a screen whose whole point is to run VRM
        // tracking/rendering as smoothly as possible.
        if (!vrmModeOpen) {
        MainFeedScreen(
            subImageIndices           = subImageIndices,
            mediaItems                = mediaItems,
            currentIndex              = currentIndex,
            currentItem               = currentItem,
            screenState               = screenState,
            skipFeedEntryAnim         = skipFeedEntryAnim,
            hasVisitedFeed            = hasVisitedFeed,
            appMode                   = appMode,
            navDirection              = navDirection,
            reducedAnimations         = reducedAnimations,
            liquidGlass               = liquidGlass,
            onToggleLiquidGlass       = viewModel::setLiquidGlass,
            liquidGlassIntensity      = liquidGlassIntensity,
            onSetLiquidGlassIntensity = viewModel::setLiquidGlassIntensity,
            glassRimIntensity         = glassRimIntensity,
            onSetGlassRimIntensity    = viewModel::setGlassRimIntensity,
            glassRimVibrantSecondary  = glassRimVibrantSecondary,
            onToggleGlassRimVibrantSecondary = viewModel::setGlassRimVibrantSecondary,
            dmConversations           = dmConversations,
            dmConversationsLoading    = dmConversationsLoading,
            friendsReviews            = friendsReviews,
            friendsReviewsLoading     = friendsReviewsLoading,
            onLoadFriendsReviews      = viewModel::loadFriendsReviewsIfNeeded,
            onOpenReview              = viewModel::openMutualReview,
            onOpenProfile             = { author -> viewModel.openProfile(author) },
            friendsBlogs              = friendsBlogs,
            onOpenBlog                = viewModel::openMutualBlog,
            onRefreshHub              = viewModel::refreshHub,
            liveFriends               = liveFriends,
            liveFriendsLoading        = liveFriendsLoading,
            onLoadLiveFriends         = viewModel::loadLiveFriendsIfNeeded,
            blueskyLiveNow            = blueskyLiveNow,
            blueskyLiveNowLoading     = blueskyLiveNowLoading,
            onLoadBlueskyLiveNow      = viewModel::loadBlueskyLiveNowIfNeeded,
            onOpenLivePlayer          = viewModel::openLivePlayer,
            onEnsureFriends           = viewModel::ensureDmConversationsLoaded,
            selfAvatarUrl             = selfProfile?.author?.avatarUrl,
            liveLinkState             = liveLinkState,
            onSaveLiveTwitchUrl       = viewModel::saveLiveTwitchUrl,
            onSaveLiveYoutubeUrl      = viewModel::saveLiveYoutubeUrl,
            onCreateLiveLinkWidget    = viewModel::createLiveLinkWidget,
            onToggleLiveLink          = viewModel::toggleLiveLink,
            onEndLiveLink             = viewModel::endLiveLink,
            onMoveFeed                = viewModel::moveFeed,
            onRemoveFeed              = viewModel::removeFeed,
            availableFeeds            = availableFeeds,
            selectedFeedUri           = selectedFeed,
            authorFeedState           = authorFeedState,
            comments                  = comments,
            commentsLoading           = commentsLoad,
            downloadOnLike            = downloadOnLike,
            downloadProgress          = downloadProgress,
            e621SearchTags            = e621Tags,
            isLoading                 = isLoading,
            bskyLoggedIn              = bskyLoggedIn,
            e621LoggedIn              = e621LoggedIn,
            bskyHandle                = viewModel.bskyHandle,
            e621Username              = viewModel.e621Username,
            errorMessage              = errorMessage,
            onNavigateNext            = viewModel::navigateNext,
            onNavigatePrev            = viewModel::navigatePrev,
            onNavigateTo              = viewModel::navigateTo,
            onSetScreen               = viewModel::setScreen,
            onToggleLike              = viewModel::toggleLike,
            onToggleRepost            = viewModel::toggleRepost,
            onToggleBookmark          = viewModel::toggleBookmark,
            onToggleFollow            = viewModel::toggleFollow,
            onE621Vote                = viewModel::e621Vote,
            onPostComment             = { text, replyTo -> viewModel.postComment(text, replyTo) },
            onLikeComment             = viewModel::likeComment,
            onVoteComment             = viewModel::voteComment,
            // All feed-chip selections route through selectFeedFromAnyContext so that
            // selecting the previous feed while in an author overlay restores scroll position
            onSelectFeed              = handleSelectFeed,
            onOpenFeed                = { uri, explore -> handleOpenFeed(uri, if (explore) ScreenState.GRID else ScreenState.FEED) },
            onToggleDownloadOnLike    = viewModel::setDownloadOnLike,
            onDownloadAllLiked        = viewModel::downloadAllBskyLikedMedia,
            settingsExtras            = SettingsExtras(
                otherBskyAccounts = otherBskyAccounts,
                showSwitchAccountsRow = showSwitchAccountsRow,
                accountSwitching = accountSwitching,
                taggerModelReady = taggerModelReady,
                taggerModelDownloading = taggerModelDownloading,
                downloadIsE621 = downloadIsE621,
                onToggleShowSwitchAccountsRow = viewModel::setShowSwitchAccountsRow,
                onAddBskyAccount = viewModel::addBskyAccount,
                onSwitchBskyAccount = viewModel::switchBskyAccount,
                onRemoveBskyAccount = viewModel::removeBskyAccount,
                onDownloadTaggerModel = viewModel::downloadTaggerModel,
                onDownloadAllE621Saved = viewModel::downloadAllE621SavedMedia,
                onOpenBlockedAccounts = viewModel::openBlockedAccounts,
                datasetExportState = datasetExportState,
                userLists = userLists,
                userListsLoading = userListsLoading,
                onEnsureUserLists = viewModel::ensureUserListsLoaded,
                onAddHubList = viewModel::addHubList,
                onAddHubListFromUrl = viewModel::addHubListFromUrl,
                hubLists = hubLists,
                onLoadHubList = { uri -> viewModel.loadHubListIfNeeded(uri) },
                onLoadMoreHubList = viewModel::loadMoreHubList,
                onOpenHubListPost = viewModel::openHubListPost,
                onForceRefreshHub = viewModel::forceRefreshHub
            ),
            onCancelDownload          = viewModel::cancelDownloadAll,
            tagPostWhenLiked          = tagPostWhenLiked,
            onToggleTagPostWhenLiked  = viewModel::setTagPostWhenLiked,
            taggingRunning            = taggingUiState.isRunning,
            taggingScanned            = taggingUiState.scanned,
            taggingTagged             = taggingUiState.tagged,
            onLocallyTagAllLiked      = viewModel::startTaggingAllLiked,
            onDeleteTaggedDatabase    = viewModel::deleteTaggedDatabase,
            importedDatasets          = importedDatasets,
            onExportDataset           = viewModel::exportDataset,
            onImportDataset           = viewModel::importDatasetFromUri,
            onDeleteImportedDataset   = viewModel::deleteImportedDataset,
            // Hub "Liked Posts" opens your own profile on its Likes tab.
            onShowLikes               = viewModel::openOwnLikes,
            onReturnToFeed            = viewModel::returnToFeed,
            onShowFriends             = viewModel::showFriendsFeed,
            onShowE621Following       = viewModel::searchFollowingE621,
            onToggleReducedAnimations = viewModel::setReducedAnimations,
            classicProfileTabRow      = classicProfileTabRow,
            onToggleClassicProfileTabRow = viewModel::setClassicProfileTabRow,
            squareGridRounded        = squareGridRounded,
            onToggleSquareGridRounded = viewModel::setSquareGridRounded,
            selfDid                   = bskyDid,
            subscribedReviewDids      = subscribedReviewDids,
            subscribedBlogDids        = subscribedBlogDids,
            followerScanState         = followerScanState,
            followerScanCompletedOnce = followerScanCompletedOnce,
            onStartFollowerScan       = { viewModel.startFollowerScan() },
            onRescanFollowersFromScratch = { viewModel.startFollowerScan(resume = false) },
            onDismissFollowerScanResult = viewModel::dismissFollowerScanResult,
            combineListsAndPacks      = combineListsPacks,
            onToggleCombineListsPacks = viewModel::setCombineListsAndPacks,
            autoAddToOnFollow         = autoAddToOnFollow,
            onToggleAutoAddToOnFollow = viewModel::setAutoAddToOnFollow,
            onLoginBluesky            = viewModel::loginBluesky,
            onLogoutBluesky           = viewModel::logoutBluesky,
            onSaveE621Credentials     = viewModel::saveE621Credentials,
            onLogoutE621              = viewModel::logoutE621,
            onSearchE621              = { tags -> viewModel.setE621SearchTags(tags); viewModel.searchE621() },
            onShowE621Favorites       = viewModel::showE621Favorites,
            onSwipeToMode             = viewModel::setMode,
            onLoadMore                = viewModel::loadMore,
            onDownloadCurrent         = viewModel::downloadCurrentItem,
            onRefresh                 = { viewModel.loadFeed(reset = true) },
            // Profile Overhaul: tapping an account now opens the full Profile
            // Overlay instead of swapping the pager to their feed directly.
            // e621 has no notion of an account profile, so tapping an artist
            // there keeps the old behavior of searching that artist's tag.
            // Profile "Posts" tab redesign: Media and Text Posts are now one
            // "Posts" tab (with a sub-filter row for type), so there's no
            // separate tab to route into by item type anymore — always open
            // straight into Posts.
            onTapAuthor               = { item ->
                if (appMode == AppMode.BLUESKY) {
                    viewModel.openProfile(item.author, initialTab = MainViewModel.ProfileTab.POSTS)
                } else viewModel.showAuthorFeed(item)
            },
            onPinchIn                 = viewModel::pinchInFromPost,
            // Item 1: pause whatever's playing behind a visible (non-hidden)
            // profile overlay — see the doc comment on this param in
            // MainFeedScreen for why the grid case doesn't need this too.
            externallyPaused           = profileOverlay?.hidden == false,
            onTagClick                = { tag -> viewModel.searchSingleTag(tag) },
            onTagAdd                  = { tag -> viewModel.addTagToSearch(tag, exclude = false) },
            onTagExclude              = { tag -> viewModel.addTagToSearch(tag, exclude = true) },
            onSendPost                = viewModel::openSendPopup,
            onQuoteRepost             = viewModel::openQuoteRepost,
            onBlockAccount            = viewModel::toggleBlockCurrentAuthor,
            onReportPost              = viewModel::openReportForCurrentPost,
            onDeletePost              = viewModel::deleteCurrentPost,
            onDownloadGif             = viewModel::downloadCurrentItemAsGif,
            // Item 4: "More" menu on the interaction bar.
            onShowMoreLikeThis        = viewModel::sendShowMoreLikeThisForCurrentItem,
            onShowLessLikeThis        = viewModel::sendShowLessLikeThisForCurrentItem,
            onAddAccountToList        = viewModel::openListPickerForCurrentAuthor,
            supportsFeedInteractions  = supportsFeedInteractions,
            sentByExpanded            = sentByExpanded,
            onToggleSentByExpanded    = viewModel::toggleSentByExpanded,
            onOpenReplyToSender       = viewModel::openReplyToSender,
            // Item 27: tapping the sender's avatar in the "Sent by" header
            // (From Friends feed) opens their profile.
            onTapSentByAuthor         = { author -> viewModel.openProfile(author) },
            friendsFeedLoadingOverlay = friendsFeedLoadingOverlay && loadingScreens,
            onCurrentBackdropChanged  = { backdrop, color -> currentBackdrop = backdrop; currentDominantColor = color },
            selfProfile               = selfProfile,
            hideTextOnlyPosts         = hideTextOnlyPosts,
            onToggleHideTextOnlyPosts = viewModel::setHideTextOnlyPosts,
            onOpenOwnProfile          = viewModel::openOwnProfile,
            onShowSaves               = viewModel::showSaves,
            onShowHistory             = viewModel::showHistory,
            onOpenDmInbox             = viewModel::openDmInbox,
            onOpenInbox               = viewModel::openInbox,
            inboxUnreadCount          = inboxUnreadCount,
            dmUnreadCount             = dmConversations.sumOf { it.unreadCount },
            onOpenComposePost         = viewModel::openComposePost,
            onOpenSearch              = viewModel::openSearch,
            translationEnabled        = translationEnabled,
            translationTargetLang     = translationTargetLang,
            onToggleTranslation       = viewModel::setTranslationEnabled,
            onSelectTranslationLanguage = viewModel::setTranslationTargetLang,
            customFontName            = customFontName,
            onPickFontFile            = viewModel::setCustomFontFromUri,
            onResetFont               = viewModel::resetCustomFont,
            hateFunBlurNsfw           = hateFunBlurNsfw,
            onToggleHateFunBlurNsfw   = viewModel::setHateFunBlurNsfw,
            pinterestThreeColumns     = pinterestThreeColumns,
            onTogglePinterestThreeColumns = viewModel::setPinterestThreeColumns,
            // Share To / Quote Repost / Add To fade the post's own UI away.
            popupOpen                 = sendPopupTarget != null || quoteRepostTarget != null || listPickerDid != null || (reportTarget != null && reportTarget?.fromProfile != true),
            likeTagPhase              = likeTagPhase,
            likeTagPending            = likeTagPending,
            onPrefetchListMemberships = viewModel::prefetchListMemberships,
            onReturnToProfile         = viewModel::returnToProfile
        )
        } // if (!vrmModeOpen) — see the comment above this call

        if (composePostOpen) {
            com.mediaviewer.ui.ComposePostScreen(
                selfProfile    = selfProfile?.author,
                liquidGlass    = liquidGlass,
                dominantColor  = currentDominantColor,
                submitting     = composePostSubmitting,
                reviewTarget   = reviewComposeTarget,
                initialImageUri = initialComposeImageUri,
                initialVideoUri = initialComposeVideoUri,
                editBlog       = blogEditDraft,
                onClose        = viewModel::closeComposePost,
                onSubmit       = viewModel::submitComposePost
            )
        }

        if (vrmModeOpen) {
            // VRM UI wears the user's own profile color (same rule as the
            // notch button over the Hub) so the debug text, X button, and
            // bottom bar match the rest of the app instead of generic green.
            val vrmTint = run {
                val selfAvatar = selfProfile?.author?.avatarUrl
                com.mediaviewer.ui.rememberSelfTint(selfAvatar, currentDominantColor)
            }
            VrmModeScreen(
                liquidGlass = liquidGlass,
                tint = vrmTint,
                onClose = viewModel::closeVrmMode,
                onCapture = { imageUri, videoUri ->
                    // Item 13: captures go to their own review page first
                    // (VRM mode closes to free the camera/GPU) instead of
                    // straight into the composer.
                    val uri = videoUri ?: imageUri
                    if (uri != null) viewModel.openCapturePreview(uri, isVideo = videoUri != null)
                    else viewModel.closeVrmMode()
                }
            )
        }

        if (cameraModeOpen) {
            // The notch bubble's Camera page — above the posting page (10.5)
            // when opened from it, below the notch bubble itself (11).
            val cameraTint = run {
                val selfAvatar = selfProfile?.author?.avatarUrl
                com.mediaviewer.ui.rememberSelfTint(selfAvatar, currentDominantColor)
            }
            Box(Modifier.fillMaxSize().zIndex(10.8f)) {
                com.mediaviewer.ui.CameraModeScreen(
                    liquidGlass = liquidGlass,
                    tint = cameraTint,
                    onClose = viewModel::closeCameraMode,
                    onCapture = { imageUri, videoUri ->
                        val uri = videoUri ?: imageUri
                        if (uri != null) viewModel.onCameraCapture(uri, isVideo = videoUri != null)
                        else viewModel.closeCameraMode()
                    }
                )
            }
        }

        val currentCapturePreview = capturePreview
        if (currentCapturePreview != null) {
            val selfAvatar = selfProfile?.author?.avatarUrl
            com.mediaviewer.ui.CapturePreviewScreen(
                uri = currentCapturePreview.uri,
                isVideo = currentCapturePreview.isVideo,
                liquidGlass = liquidGlass,
                tint = selfProfileTint,
                onClose = viewModel::returnToVrmFromPreview,
                onCreatePost = { uri -> viewModel.createPostFromPreview(uri, currentCapturePreview.isVideo) }
            )
        }

        // Item 12 follow-up: shown only while fetching a DM thread's shared-
        // posts feed — same pattern as the "From Friends" loading overlay.
        if (dmFeedLoadingOverlay && loadingScreens) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(10f),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading Shared Posts…", color = Color.White, fontSize = 15.sp)
            }
        }

        if (searchOpen) {
            SearchOverlay(
                state              = searchState,
                liquidGlass        = liquidGlass,
                selfAvatarUrl      = selfProfile?.author?.avatarUrl,
                hasTaggedDataset   = hasTaggedDataset,
                likedTagResults    = likedTagSearchResults,
                onOpenLikedPost    = viewModel::openLikedPostFromSearch,
                tagSuggestions     = tagSuggestions,
                onQueryChange      = viewModel::runSearch,
                onLikedQueryTextChange = viewModel::updateLikedQueryText,
                onLikedSearchSubmit    = viewModel::submitLikedSearch,
                e621LoggedIn       = e621LoggedIn,
                onE621SearchSubmit = viewModel::submitE621SearchFromOverlay,
                onTagSuggestionSelected = viewModel::applyTagSuggestion,
                onSelectFilter     = viewModel::setSearchFilter,
                onOpenPost         = viewModel::openPostFromSearch,
                onOpenAccount      = { author -> viewModel.closeSearch(); viewModel.openProfile(author) },
                onAddFeed          = viewModel::addSavedFeedFromSearch,
                onClose            = viewModel::closeSearch
            )
        }

        // AI Tagging feature: full-screen "tagging in progress / complete"
        // overlay — opened by either the Search page's "Start Tagging"
        // button or Settings' "Locally Tag All Liked Posts" row, both of
        // which just call startTaggingAllLiked(). Layered like every other
        // full-screen overlay (Search, DM inbox, Live player) below.
        if (taggingOverlayOpen) {
            TaggingOverlay(
                state          = taggingUiState,
                liquidGlass    = liquidGlass,
                selfAvatarUrl  = selfProfile?.author?.avatarUrl,
                onDismiss      = viewModel::dismissTaggingOverlay,
                onSearchLiked  = {
                    viewModel.dismissTaggingOverlay()
                    viewModel.openSearch()
                    viewModel.setSearchFilter(MainViewModel.SearchFilter.LIKED_TAGS)
                }
            )
        }

        // Item (this session): both Live sources (Streamplace + Bluesky Live
        // Now) open this now, not just Bluesky's — layered the same way
        // every other full-screen overlay in this app is (DM inbox, Search),
        // on top of everything else.
        val currentPlayingLive = playingLive
        if (currentPlayingLive != null) {
            LiveNowPlayerOverlay(stream = currentPlayingLive, onClose = viewModel::closeLivePlayer)
        }

        val currentProfileOverlay = profileOverlay
        if (currentProfileOverlay != null) {
            // Pinch navigation: a "hidden" profile (tapped a post from inside
            // it — see openPostFromProfileTab) stays fully composed at zero
            // size instead of being removed, so its LazyListState (scroll
            // position), loaded tabs, etc. survive untouched. Zero size means
            // it can't be seen or hit-test any touches, so the pager
            // underneath is fully interactive again — pinching back in
            // (pinchInFromPost) just flips this back to full size.
            Box(if (currentProfileOverlay.hidden || !profileRevealArmed) Modifier.size(0.dp) else Modifier.fillMaxSize()) {
                ProfileOverlay(
                    state             = currentProfileOverlay,
                    liquidGlass       = liquidGlass,
                    reducedAnimations = reducedAnimations,
                    classicProfileTabRow = classicProfileTabRow,
                    roundedGridTiles     = squareGridRounded,
                    selfDid           = bskyDid,
                    onClose           = viewModel::closeProfile,
                    onSelectTab       = viewModel::selectProfileTab,
                    onLoadMore        = viewModel::loadMoreProfileTab,
                    onToggleFollow    = viewModel::toggleProfileFollow,
                    onTapItem         = viewModel::openPostFromProfileTab,
                    onSeedSubImageIndex = { postId, idx -> subImageIndices[postId] = idx },
                    onOpenBlog        = viewModel::openProfileBlog,
                    onCloseBlog       = viewModel::closeProfileBlog,
                    onEditBlog        = viewModel::openBlogEditor,
                    onDeleteBlog      = viewModel::deleteBlog,
                    onOpenReview      = viewModel::openProfileReview,
                    onCloseReview     = viewModel::closeProfileReview,
                    onOpenTitle       = viewModel::openProfileTitle,
                    onCloseTitle      = viewModel::closeProfileTitle,
                    onOpenReviewCompose = viewModel::openReviewCompose,
                    friendsReviews    = friendsReviewsForTitles,
                    reviewSocial      = reviewSocial,
                    onLoadReviewSocial  = viewModel::loadReviewSocial,
                    onToggleReviewLike  = viewModel::toggleReviewLike,
                    onPostReviewComment = viewModel::postReviewComment,
                    onDeleteReview      = viewModel::deleteReview,
                    onPinchOut        = viewModel::pinchOutFromProfile,
                    onSaveScroll      = viewModel::saveProfileScrollPosition,
                    isReviewSubscribed = currentProfileOverlay.author.did in subscribedReviewDids,
                    isBlogSubscribed   = currentProfileOverlay.author.did in subscribedBlogDids,
                    onToggleReviewSubscribe = { viewModel.toggleReviewSubscription(currentProfileOverlay.author) },
                    onToggleBlogSubscribe   = { viewModel.toggleBlogSubscription(currentProfileOverlay.author) },
                    onOpenAddTo       = viewModel::openListPickerForProfile,
                    onOpenDm          = viewModel::openDmWithProfile,
                    onNewGroupWith    = { author -> viewModel.openNewChat(group = true, preselected = listOf(author)) },
                    existingDmDids    = remember(dmConversations) { dmConversations.filter { it.convoId.isNotBlank() && !it.isGroup }.map { it.member.did }.toSet() },
                    onRefresh         = viewModel::refreshProfile,
                    onSelectPostKindFilter = viewModel::selectPostKindFilter,
                    onSelectReviewKindFilter = viewModel::selectReviewKindFilter,
                    onSelectMusicYear = viewModel::selectMusicYear,
                    pinterestThreeColumns = pinterestThreeColumns,
                    hateFunBlurNsfw   = hateFunBlurNsfw,
                    onSaveOwnProfile  = viewModel::updateOwnProfile,
                    onBackdropChanged = { b, c -> profileBackdrop = b; profileTint = c },
                    isBlocking = blockVersion.let { com.mediaviewer.util.BlockedAccounts.isBlocking(currentProfileOverlay.author.did) },
                    onToggleBlock = viewModel::toggleBlockProfile,
                    onReportAccount = viewModel::openReportForProfile,
                    onOpenQr = { author, banner -> qrTarget = author to banner },
                    titleBacklog = titleBacklog,
                    onCheckTitleBacklog = viewModel::checkTitleBacklog,
                    onToggleTitleBacklog = viewModel::toggleTitleBacklog
                )
            }
        }

        // A profile's QR code page (profile interaction bar → QR button).
        qrTarget?.let { (qrAuthor, qrBanner) ->
            com.mediaviewer.ui.ProfileQrScreen(author = qrAuthor, bannerUrl = qrBanner, onClose = { qrTarget = null })
        }

        // Item 3: this used to render *before* ProfileOverlay below, so a
        // Bluesky opened from a profile's DM button visibly built its
        // thread "behind" the still-composed profile page (Compose draws
        // later Box children on top of earlier ones — ProfileOverlay was
        // the later child). Rendering it after ProfileOverlay instead (but
        // still before every dialog/popup below it) puts it back on top,
        // matching the "layered on top of everything else" comment this
        // block used to sit under.
        if (dmInboxOpen) {
            DmInboxOverlay(
                conversations   = dmConversations,
                loading         = dmConversationsLoading,
                thread          = dmThread,
                liquidGlass     = liquidGlass,
                selfAvatarUrl   = selfProfile?.author?.avatarUrl,
                onSelectConvo   = viewModel::openDmThread,
                onCloseThread   = viewModel::closeDmThread,
                onSendReply     = viewModel::sendDmThreadReply,
                onClose         = viewModel::closeDmInbox,
                onTapAuthor     = { author -> viewModel.closeDmInbox(); viewModel.openProfile(author) },
                onLoadMoreMessages   = viewModel::loadMoreDmMessages,
                onOpenSharedPostsFeed = viewModel::openDmThreadSharedPostsFeed,
                onToggleReaction     = viewModel::toggleDmReaction,
                selfDid              = bskyDid,
                onNewChat            = { viewModel.openNewChat() },
                onNewGroupWith       = { author -> viewModel.openNewChat(group = true, preselected = listOf(author)) }
            )
        }

        // The Hub's Inbox: Bluesky notifications (likes, follows, replies…).
        if (inboxOpen) {
            val inboxItems by viewModel.inboxItems.collectAsState()
            val inboxLoading by viewModel.inboxLoading.collectAsState()
            com.mediaviewer.ui.InboxOverlay(
                items = inboxItems,
                loading = inboxLoading,
                liquidGlass = liquidGlass,
                selfAvatarUrl = selfProfile?.author?.avatarUrl,
                onLoadMore = viewModel::loadMoreInbox,
                onOpenPost = viewModel::openInboxPost,
                onOpenProfile = { author -> viewModel.closeInbox(); viewModel.openProfile(author) },
                onClose = viewModel::closeInbox
            )
        }

        // New chat / new group chat popup (DM list's + button, a 1:1 chat's
        // group button, or a profile's DM button held down).
        newChatState?.let { ncs ->
            val candidates by viewModel.chatCandidates.collectAsState()
            val searching by viewModel.chatSearching.collectAsState()
            val creating by viewModel.creatingChat.collectAsState()
            com.mediaviewer.ui.NewChatDialog(
                state = ncs,
                candidates = candidates,
                searching = searching,
                creating = creating,
                tint = com.mediaviewer.ui.rememberSelfTint(selfProfile?.author?.avatarUrl, NeutralGlassTint),
                onSearch = viewModel::searchChatCandidates,
                onSetGroupMode = viewModel::setNewChatGroupMode,
                onStartChat = viewModel::startChatWith,
                onCreateGroup = viewModel::createGroupChat,
                onClose = viewModel::closeNewChat,
                onLoadMoreSuggestions = viewModel::loadMoreChatSuggestions
            )
        }

        // Settings → Data and Privacy → Blocked Accounts.
        val blockedAccountsOpen by viewModel.blockedAccountsOpen.collectAsState()
        if (blockedAccountsOpen) {
            val blockedAccounts by viewModel.blockedAccounts.collectAsState()
            val blockedLoading by viewModel.blockedAccountsLoading.collectAsState()
            val unblocking by viewModel.unblockingDids.collectAsState()
            com.mediaviewer.ui.BlockedAccountsDialog(
                accounts = blockedAccounts,
                loading = blockedLoading,
                unblocking = unblocking,
                tint = com.mediaviewer.ui.rememberSelfTint(selfProfile?.author?.avatarUrl, NeutralGlassTint),
                onUnblock = viewModel::unblockAccount,
                onClose = viewModel::closeBlockedAccounts
            )
        }

        // Share To / Quote Repost fade (and gently scale) in over the post
        // while its own UI fades away, and reverse that when they close.
        com.mediaviewer.ui.FadingPopupHost(sendPopupTarget, Modifier.zIndex(10f)) { target ->
            SendDmDialog(
                target          = target,
                conversations   = dmConversations,
                loading         = dmConversationsLoading,
                selected        = sendPopupSelected,
                sending         = sendPopupSending,
                liquidGlass     = liquidGlass,
                dominantColor   = currentDominantColor,
                backdrop        = currentBackdrop,
                onToggleSelect  = viewModel::toggleSendRecipient,
                onSend          = viewModel::sendToSelectedRecipients,
                onDismiss       = viewModel::dismissSendPopup
            )
        }

        com.mediaviewer.ui.FadingPopupHost(quoteRepostTarget, Modifier.zIndex(10f)) { target ->
            QuoteRepostDialog(
                target      = target,
                submitting  = quoteRepostSubmitting,
                liquidGlass   = liquidGlass,
                dominantColor = currentDominantColor,
                backdrop      = currentBackdrop,
                onSubmit    = viewModel::submitQuoteRepost,
                onDismiss   = viewModel::dismissQuoteRepost
            )
        }

        val currentReplyConvo = replyToConvo
        if (currentReplyConvo != null) {
            ReplyDialog(
                convo     = currentReplyConvo,
                onSend    = viewModel::sendReply,
                onDismiss = viewModel::dismissReplyPopup
            )
        }

        val listMemberships by viewModel.listMemberships.collectAsState()
        val listMembershipBusy by viewModel.listMembershipBusy.collectAsState()
        val creatingPickerList by viewModel.creatingPickerList.collectAsState()
        val pickerOverProfile = profileOverlay?.let { !it.hidden } == true
        com.mediaviewer.ui.FadingPopupHost(listPickerDid, Modifier.zIndex(10f)) { _ ->
            ListPickerDialog(
                lists         = userLists,
                starterPacks  = userStarterPacks,
                listsLoading  = userListsLoading,
                initialTab    = lastPickerTab,
                liquidGlass   = liquidGlass,
                dominantColor = if (pickerOverProfile) profileTint else currentDominantColor,
                backdrop      = if (pickerOverProfile) profileBackdrop else currentBackdrop,
                memberships   = listMemberships,
                busy          = listMembershipBusy,
                creating      = creatingPickerList,
                onTabChange   = { tab -> viewModel.setPickerTab(tab) },
                onToggle      = { listUri, additionalUri -> viewModel.toggleListMembership(listUri, additionalUri) },
                onCreate      = { kind, name, description, cover, done -> viewModel.createPickerList(kind, name, description, cover, done) },
                onRename      = { uris, name, done -> viewModel.renamePickerEntry(uris, name, done) },
                onDismiss     = { viewModel.dismissListPicker() }
            )
        }

        // Report (post or account) — the same centered glass popup as Add
        // To, in the reported post's (or profile's) own color.
        com.mediaviewer.ui.FadingPopupHost(reportTarget, Modifier.zIndex(10f)) { target ->
            val overProfile = target.fromProfile
            com.mediaviewer.ui.ReportDialog(
                target      = target,
                submitting  = reportSubmitting,
                liquidGlass = liquidGlass,
                tint        = if (overProfile) profileTint else currentDominantColor,
                backdrop    = if (overProfile) profileBackdrop else currentBackdrop,
                onSubmit    = viewModel::submitReport,
                onDismiss   = viewModel::dismissReport
            )
        }

        // Settings → Dev Tools → "Preview Login Page": the login page over
        // everything, without signing out. Back (or its back button) closes it.
        if (com.mediaviewer.util.UiToggles.devLoginPreview) {
            com.mediaviewer.ui.compat.BackHandler(onBack = { com.mediaviewer.util.UiToggles.devLoginPreview = false })
            Box(Modifier.fillMaxSize().zIndex(10.9f).blockClicksBehind()) {
                com.mediaviewer.ui.LoginScreen(
                    isLoading = false,
                    onLogin = { _, _ -> com.mediaviewer.ui.compat.Toast.makeText(context, "Preview only — you're already signed in", com.mediaviewer.ui.compat.Toast.LENGTH_SHORT).show() },
                    onClose = { com.mediaviewer.util.UiToggles.devLoginPreview = false }
                )
            }
        }

        // Item 8: the camera-notch ring — hugs the real display cutout and
        // is ALWAYS visible: composed unconditionally, after every other
        // in-app layer (feed, Hub, profiles, Search, DMs, composer, VRM
        // mode), with a zIndex above the DM-feed loading screen. Only the
        // cold-launch cover and the pixel transition draw over it.
        // Tapping (expanding into Camera/VRM) works on the Hub, a visible
        // profile page and the posting page (a photo taken there is added
        // to the draft); everywhere else it's a passive ring that
        // lets touches fall through to whatever is underneath.
        run {
            // The notch button wears the feed's current dominant color — except
            // while the Hub (SettingsSheet) is open, where every other piece
            // of hub UI tints itself with the logged-in user's OWN profile
            // color instead (see SettingsSheet's dominantColor shadowing).
            // The button follows the same rule so it doesn't stick out in
            // the wrong color over the hub.
            val profileVisible = profileOverlay?.let { !it.hidden && profileRevealArmed } == true
            // The posting page always gets a working notch (it may have been
            // opened on top of Search/DMs, whose flags stay set underneath).
            val notchInteractive = !vrmModeOpen && !cameraModeOpen && (composePostOpen || (
                !searchOpen && !dmInboxOpen && !inboxOpen && !taggingOverlayOpen && playingLive == null &&
                    (profileVisible || screenState == ScreenState.SETTINGS)))
            val openProfile = profileOverlay
            val notchTint = if (vrmModeOpen || cameraModeOpen || composePostOpen) {
                val selfAvatar = selfProfile?.author?.avatarUrl
                com.mediaviewer.ui.rememberSelfTint(selfAvatar, currentDominantColor)
            } else if (dmInboxOpen) {
                // DMs: in a 1:1 chat the notch wears the other person's
                // profile colors (like the chat itself); the inbox and group
                // chats keep yours.
                val openThread = dmThread
                if (openThread != null && !openThread.convo.isGroup && openThread.convo.member.did.isNotBlank()) {
                    com.mediaviewer.ui.rememberAuthorProfileTint(openThread.convo.member.did, openThread.convo.member.avatarUrl)
                } else {
                    com.mediaviewer.ui.rememberSelfTint(selfProfile?.author?.avatarUrl, currentDominantColor)
                }
            } else if (profileVisible && openProfile != null) {
                // On a profile page the notch wears that profile's own color —
                // the same banner/avatar blend the page's glass uses.
                val bannerColor = rememberDominantColor(openProfile.profile?.bannerUrl ?: openProfile.author.avatarUrl ?: "")
                val avatarColor = rememberDominantColor(openProfile.author.avatarUrl ?: "")
                Color(
                    red = (bannerColor.red + avatarColor.red) / 2f,
                    green = (bannerColor.green + avatarColor.green) / 2f,
                    blue = (bannerColor.blue + avatarColor.blue) / 2f,
                    alpha = 1f
                )
            } else if (inboxOpen || screenState == ScreenState.SETTINGS || screenState == ScreenState.GRID) {
                // The Hub, the Inbox and Explore/grid mode wear your own color.
                val selfAvatar = selfProfile?.author?.avatarUrl
                com.mediaviewer.ui.rememberSelfTint(selfAvatar, currentDominantColor)
            } else {
                currentDominantColor
            }
            CameraNotchButton(
                liquidGlass = liquidGlass,
                tint = notchTint,
                interactive = notchInteractive,
                modifier = Modifier.zIndex(11f),
                // Camera: Stellar's own camera page (see CameraModeScreen).
                onOpenCamera = viewModel::openCameraMode,
                onOpenVrm = viewModel::openVrmMode
            )
            // Settings → App Functionality → "FPS Overlay": the frame rate in
            // the same color the current page (and the notch ring) wears.
            if (com.mediaviewer.util.UiToggles.debugOverlay) {
                com.mediaviewer.ui.DebugOverlay(
                    tint = notchTint,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(14f)
                )
            }
        }

        // Bug fix (item 3): unconditional opaque backing for the cold-boot
        // window described above — sits above every other layer (matching
        // PixelMatrixOverlay's own z-order) so nothing real is reachable
        // until the very first wipe-in has genuinely finished covering the
        // screen, regardless of how many frames that takes to kick off.
        if (coldLaunchCovered) {
            Box(Modifier.fillMaxSize().background(Color.Black).zIndex(12f))
        }

        // Settings → Dev Tools → "Preview Loading Animation": plays the
        // picked loading animation over everything. Tapping moves it along —
        // Pixels: your colors → a second color (#FF4FA1) → its outro; the
        // others: straight to the outro. Back ends it too.
        if (com.mediaviewer.util.UiToggles.devLoadingPreview) {
            fun endPreview() {
                rootScope.launch {
                    if (pixelController.phase != PixelPhase.HIDDEN) pixelController.finish()
                    com.mediaviewer.util.UiToggles.devLoadingPreview = false
                }
            }
            com.mediaviewer.ui.compat.BackHandler(onBack = { endPreview() })
            Box(
                Modifier.fillMaxSize().zIndex(14f).clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {
                    val pixels = com.mediaviewer.util.UiToggles.loadingAnimation == com.mediaviewer.util.UiToggles.LoadingAnimation.PIXELS
                    if (pixels && devPreviewStep == 0) {
                        devPreviewStep = 1
                        rootScope.launch { pixelController.updateColor(Color(0xFFFF4FA1)) }
                    } else endPreview()
                }
            )
        }

        // Retro pixel-matrix transition overlay — last child so it draws
        // above every other layer (feed, Hub, profile, dialogs) while a
        // transition is in progress; renders nothing once HIDDEN.
        PixelMatrixOverlay(controller = pixelController.pixels, modifier = Modifier.fillMaxSize().zIndex(13f))
        // Shatter: the screenshot-of-the-old-page glass, cracking and falling away.
        ShatterOverlay(controller = pixelController.shatter, modifier = Modifier.fillMaxSize().zIndex(13f))
        // Space: the old page fading into a drifting starfield + logo.
        com.mediaviewer.ui.SpaceOverlay(controller = pixelController.space, modifier = Modifier.fillMaxSize().zIndex(13f))

    }
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            kotlinx.coroutines.delay(6000)
            viewModel.clearError()
        }
    }
}
