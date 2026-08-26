package com.mediaviewer

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.mediaviewer.ui.NeutralGlassTint
import com.mediaviewer.ui.DmInboxOverlay
import com.mediaviewer.ui.ListPickerDialog
import com.mediaviewer.ui.LiveNowPlayerOverlay
import com.mediaviewer.ui.MainFeedScreen
import com.mediaviewer.ui.PixelMatrixOverlay
import com.mediaviewer.ui.PixelPhase
import com.mediaviewer.ui.ProfileOverlay
import com.mediaviewer.ui.fetchDominantColor
import com.mediaviewer.ui.rememberPixelTransitionController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.mediaviewer.ui.QuoteRepostDialog
import com.mediaviewer.ui.ReplyDialog
import com.mediaviewer.ui.SearchOverlay
import com.mediaviewer.ui.SendDmDialog
import com.mediaviewer.ui.TaggingOverlay
import com.mediaviewer.ui.theme.MediaViewerTheme
import com.mediaviewer.viewmodel.MainViewModel
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// No Android Studio/adb in this workflow (APKs are built by GitHub Actions
// and sideloaded straight onto the phone), so there's normally no way to see
// a crash's stack trace at all. This is a minimal self-contained crash
// catcher: any uncaught exception gets written to a plain file in internal
// storage, and the *next* time the app is opened, that file's contents are
// shown as plain copyable text instead of the normal UI — so a crash can be
// diagnosed just by reopening the app and copying what's on screen.
private const val CRASH_LOG_FILENAME = "last_crash.txt"

private fun installCrashHandler(context: Context) {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            File(context.filesDir, CRASH_LOG_FILENAME).writeText(sw.toString())
        }
        // Still hand off to whatever Android's own default handler is (shows
        // the normal "app has stopped" dialog and actually closes the
        // process) — this only adds a side-effect, it doesn't swallow the
        // crash.
        previousHandler?.uncaughtException(thread, throwable)
    }
}

private fun readCrashLog(context: Context): String? {
    val file = File(context.filesDir, CRASH_LOG_FILENAME)
    return if (file.exists()) runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } else null
}

private fun clearCrashLog(context: Context) {
    runCatching { File(context.filesDir, CRASH_LOG_FILENAME).delete() }
}

@Composable
private fun CrashLogScreen(log: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.systemBars).padding(16.dp)
    ) {
        Text("RaccNetLite crashed last time it ran", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Copy this and send it back for a fix.", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.background(Color(0xFF2A7D46)).clickable { clipboard.setText(AnnotatedString(log)) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) { Text("Copy", color = Color.White, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.background(Color.White.copy(alpha = 0.15f)).clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) { Text("Dismiss", color = Color.White, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(log, color = Color(0xFF8BE28B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {        super.onCreate(savedInstanceState)
        installCrashHandler(applicationContext)
        enableEdgeToEdge()
        setContent {
            var crashLog by remember { mutableStateOf(readCrashLog(applicationContext)) }
            if (crashLog != null) {
                CrashLogScreen(log = crashLog!!, onDismiss = { clearCrashLog(applicationContext); crashLog = null })
                return@setContent
            }
            // Phase 4 — custom font pack: rebuilt only when the stored path
            // actually changes, not on every recomposition. Falls back to null
            // (MediaViewerTheme's own default Typography) if the file somehow
            // isn't there anymore (e.g. cleared app storage out from under it).
            val customFontPath by viewModel.customFontPath.collectAsState()
            val customFontFamily = remember(customFontPath) {
                customFontPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) FontFamily(Font(file)) else null
                }
            }
            MediaViewerTheme(customFontFamily = customFontFamily) { AppRoot(viewModel) }
        }
    }

    // Item (this session): the Jetstream/firehose connection this used to
    // nudge on every resume is gone (Reviews/Blogs are now a direct,
    // per-visit/per-refresh PDS fetch off the Subscribe lists — see
    // MainViewModel.loadFriendsReviewsIfNeeded — nothing persistent to
    // reconnect), so there's nothing left for onResume to do here.
}

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val mediaItems         by viewModel.mediaItems.collectAsState()
    val currentIndex       by viewModel.currentIndex.collectAsState()
    val currentItem        by viewModel.currentItem.collectAsState()
    val screenState        by viewModel.screenState.collectAsState()
    val hasVisitedFeed     by viewModel.hasVisitedFeed.collectAsState()
    val appMode            by viewModel.appMode.collectAsState()
    val navDirection       by viewModel.navDirection.collectAsState()
    val reducedAnimations  by viewModel.reducedAnimations.collectAsState()
    val liquidGlass        by viewModel.liquidGlass.collectAsState()
    val liquidGlassIntensity by viewModel.liquidGlassIntensity.collectAsState()
    val glassRimIntensity  by viewModel.glassRimIntensity.collectAsState()
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
    val e621LoggedIn       by viewModel.e621LoggedIn.collectAsState()
    val errorMessage       by viewModel.errorMessage.collectAsState()
    val listPickerDid      by viewModel.listPickerTargetDid.collectAsState()
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
    // Item 12 follow-up: DM-thread "shared posts" feed loading overlay.
    val dmFeedLoadingOverlay   by viewModel.dmFeedLoadingOverlay.collectAsState()
    // Item 8: Hub Friends/Livestreams sections.
    val friendsReviews        by viewModel.friendsReviews.collectAsState()
    val friendsReviewsLoading by viewModel.friendsReviewsLoading.collectAsState()
    val friendsBlogs           by viewModel.friendsBlogs.collectAsState()

    val liveFriends           by viewModel.liveFriends.collectAsState()
    val liveFriendsLoading    by viewModel.liveFriendsLoading.collectAsState()
    val blueskyLiveNow        by viewModel.blueskyLiveNow.collectAsState()
    val blueskyLiveNowLoading by viewModel.blueskyLiveNowLoading.collectAsState()
    val playingLive           by viewModel.playingLive.collectAsState()
    val subscribedReviewDids  by viewModel.subscribedReviewDids.collectAsState()
    val subscribedBlogDids    by viewModel.subscribedBlogDids.collectAsState()
    val searchOpen             by viewModel.searchOpen.collectAsState()
    val searchState            by viewModel.searchState.collectAsState()
    // AI Tagging feature
    val taggingOverlayOpen     by viewModel.taggingOverlayOpen.collectAsState()
    val taggingUiState         by viewModel.taggingUiState.collectAsState()
    val hasTaggedDataset       by viewModel.hasTaggedDataset.collectAsState()
    val likedTagSearchResults  by viewModel.likedTagSearchResults.collectAsState()
    val tagSuggestions         by viewModel.tagSuggestions.collectAsState()
    val tagConcurrency         by viewModel.tagConcurrency.collectAsState()
    val tagPostWhenLiked       by viewModel.tagPostWhenLiked.collectAsState()
    // Phase 4
    val translationEnabled     by viewModel.translationEnabled.collectAsState()
    val translationTargetLang  by viewModel.translationTargetLang.collectAsState()
    val customFontName         by viewModel.customFontName.collectAsState()

    // Big Update #10: the currently-on-screen post's live backdrop + dominant
    // color, reported up from inside the pager (see PostContent's onBackdropChanged)
    // so overlays that live above the whole pager — Share, Add To — can show the
    // same real-time reflection the in-post glass panels do, instead of a plain
    // static tint.
    var currentBackdrop by remember { mutableStateOf<GlassBackdrop?>(null) }
    var currentDominantColor by remember { mutableStateOf(NeutralGlassTint) }

    // ── Retro pixel-matrix transition/loading overlay ──────────────────────
    // One shared controller drives every scenario described in the design
    // spec: the cold-boot splash, profile-navigation transitions, and
    // opening a feed from the Feeds row. See PixelTransitionOverlay.kt for
    // the state machine and rendering; everything below is just real app
    // events (never artificial timers) driving it.
    val pixelController = rememberPixelTransitionController()
    val context = LocalContext.current
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
    var coldLaunchCovered by remember { mutableStateOf(true) }
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
        pixelController.start(Color.White)
    }
    LaunchedEffect(appInitialized, bskyLoggedIn, selfProfile) {
        if (!appInitialized) return@LaunchedEffect
        if (!bskyLoggedIn) { selfColorReady = true; return@LaunchedEffect }
        val profile = selfProfile ?: return@LaunchedEffect // still loading — wait for the real fetch
        val url = profile.author.avatarUrl
        if (!url.isNullOrBlank()) {
            val c = fetchDominantColor(context, url)
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
            if (isNewProfile) {
                pixelController.start(selfThemeColor)
                // Wipe-in has now genuinely reached full coverage (start()
                // only returns once phase has advanced past WIPE_IN) —
                // safe to swap the real profile in behind it.
                revealedProfileDids = revealedProfileDids + overlay.author.did
            }
            if (!avatarUrl.isNullOrBlank()) {
                val targetColor = fetchDominantColor(context, avatarUrl)
                pixelController.updateColor(targetColor)
            }
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
        if (screenState == ScreenState.FEED) skipFeedEntryAnim = false
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
    val handleSelectFeed: (String?) -> Unit = { uri ->
        rootScope.launch {
            // Bug fix (item 4): this used to start from `currentDominantColor`
            // — the live backdrop color of whatever post happens to be on
            // screen right now, which is essentially "the color the *previous*
            // transition happened to end on" (it tracks whatever the last
            // reveal settled the feed on). Every other transition after the
            // cold-boot one is supposed to always start from the user's own
            // profile color, same as Scenario B — so start from
            // `selfThemeColor` here too.
            pixelController.start(selfThemeColor)
            viewModel.selectFeedFromAnyContext(uri)
            // selectFeedFromAnyContext's "same feed, restore exactly from
            // cache" fast-path (see its own doc comment) never flips
            // isLoading at all — this short timeout only disambiguates
            // that real, instant code path from a genuine network fetch;
            // it is not standing in for network latency itself.
            withTimeoutOrNull(300) { viewModel.isLoading.first { it } }
            viewModel.isLoading.first { !it }
            val firstMedia = viewModel.mediaItems.value.firstOrNull()
            val feedColor = if (firstMedia != null) {
                fetchDominantColor(context, firstMedia.thumbUrl.ifBlank { firstMedia.mediaUrl })
            } else currentDominantColor
            pixelController.updateColor(feedColor)
            // Bug fix (item 12): the screen is already fully covered by the
            // opaque pixel curtain at this point, so the FEED screen
            // switching in underneath should be invisible either way — but
            // MainFeedScreen's AnimatedContent normally plays a slide/scroll
            // transition on every SETTINGS -> FEED switch, regardless of
            // what triggered it. That's correct for the explicit "Return to
            // Feed" button (which has no pixel curtain covering it), but for
            // this feed-menu path it means a slide animation is quietly
            // happening underneath — and sometimes bleeding through — the
            // wipe. Flip this flag right before switching so MainFeedScreen
            // skips the slide just this once.
            skipFeedEntryAnim = true
            viewModel.setScreen(ScreenState.FEED)
            pixelController.finish()
        }
    }

    // Item 26: makes the glass-intensity dial reach every LiquidGlassSurface/
    // glassPanel below without threading a Float through every composable's
    // parameter list.
    CompositionLocalProvider(
        LocalGlassIntensity provides liquidGlassIntensity,
        LocalGlassRimIntensity provides glassRimIntensity
    ) {
    Box(Modifier.fillMaxSize()) {
        MainFeedScreen(
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
            onToggleDownloadOnLike    = viewModel::setDownloadOnLike,
            onDownloadAllLiked        = viewModel::downloadAllLiked,
            onCancelDownload          = viewModel::cancelDownloadAll,
            tagPostWhenLiked          = tagPostWhenLiked,
            onToggleTagPostWhenLiked  = viewModel::setTagPostWhenLiked,
            taggingRunning            = taggingUiState.isRunning,
            taggingScanned            = taggingUiState.scanned,
            taggingTagged             = taggingUiState.tagged,
            onLocallyTagAllLiked      = viewModel::startTaggingAllLiked,
            tagConcurrency            = tagConcurrency,
            onSetTagConcurrency       = viewModel::setTagConcurrency,
            onShowLikes               = viewModel::showBskyLikes,
            onShowFriends             = viewModel::showFriendsFeed,
            onShowE621Following       = viewModel::searchFollowingE621,
            onToggleReducedAnimations = viewModel::setReducedAnimations,
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
            // If the post being viewed is text-only, open straight into that
            // profile's Text Posts tab instead of the default Media tab.
            onTapAuthor               = { item ->
                if (appMode == AppMode.BLUESKY) {
                    val tab = if (item.isTextOnly) MainViewModel.ProfileTab.TEXT_POSTS else MainViewModel.ProfileTab.MEDIA
                    viewModel.openProfile(item.author, initialTab = tab)
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
            friendsFeedLoadingOverlay = friendsFeedLoadingOverlay,
            onCurrentBackdropChanged  = { backdrop, color -> currentBackdrop = backdrop; currentDominantColor = color },
            selfProfile               = selfProfile,
            hideTextOnlyPosts         = hideTextOnlyPosts,
            onToggleHideTextOnlyPosts = viewModel::setHideTextOnlyPosts,
            onOpenOwnProfile          = viewModel::openOwnProfile,
            onShowSaves               = viewModel::showSaves,
            onShowHistory             = viewModel::showHistory,
            onOpenDmInbox             = viewModel::openDmInbox,
            onOpenSearch              = viewModel::openSearch,
            translationEnabled        = translationEnabled,
            translationTargetLang     = translationTargetLang,
            onToggleTranslation       = viewModel::setTranslationEnabled,
            onSelectTranslationLanguage = viewModel::setTranslationTargetLang,
            customFontName            = customFontName,
            onPickFontFile            = viewModel::setCustomFontFromUri,
            onResetFont               = viewModel::resetCustomFont
        )

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
                onOpenSharedPostsFeed = viewModel::openDmThreadSharedPostsFeed
            )
        }

        // Item 12 follow-up: shown only while fetching a DM thread's shared-
        // posts feed — same pattern as the "From Friends" loading overlay.
        if (dmFeedLoadingOverlay) {
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
                onStartTagging     = viewModel::startTaggingAllLiked,
                onOpenLikedPost    = viewModel::openLikedPostFromSearch,
                tagSuggestions     = tagSuggestions,
                onQueryChange      = viewModel::runSearch,
                onLikedQueryTextChange = viewModel::updateLikedQueryText,
                onLikedSearchSubmit    = viewModel::submitLikedSearch,
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
                    selfDid           = bskyDid,
                    onClose           = viewModel::closeProfile,
                    onSelectTab       = viewModel::selectProfileTab,
                    onLoadMore        = viewModel::loadMoreProfileTab,
                    onToggleFollow    = viewModel::toggleProfileFollow,
                    onTapItem         = viewModel::openPostFromProfileTab,
                    onOpenBlog        = viewModel::openProfileBlog,
                    onCloseBlog       = viewModel::closeProfileBlog,
                    onOpenReview      = viewModel::openProfileReview,
                    onCloseReview     = viewModel::closeProfileReview,
                    onPinchOut        = viewModel::pinchOutFromProfile,
                    onSaveScroll      = viewModel::saveProfileScrollPosition,
                    isReviewSubscribed = currentProfileOverlay.author.did in subscribedReviewDids,
                    isBlogSubscribed   = currentProfileOverlay.author.did in subscribedBlogDids,
                    onToggleReviewSubscribe = { viewModel.toggleReviewSubscription(currentProfileOverlay.author) },
                    onToggleBlogSubscribe   = { viewModel.toggleBlogSubscription(currentProfileOverlay.author) }
                )
            }
        }

        val currentSendTarget = sendPopupTarget
        if (currentSendTarget != null) {
            SendDmDialog(
                target          = currentSendTarget,
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

        val currentQuoteTarget = quoteRepostTarget
        if (currentQuoteTarget != null) {
            QuoteRepostDialog(
                target      = currentQuoteTarget,
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

        if (listPickerDid != null) {
            ListPickerDialog(
                lists         = userLists,
                starterPacks  = userStarterPacks,
                listsLoading  = userListsLoading,
                initialTab    = lastPickerTab,
                combineMode   = combineListsPacks,
                liquidGlass   = liquidGlass,
                dominantColor = currentDominantColor,
                backdrop      = currentBackdrop,
                onTabChange   = { tab -> viewModel.setPickerTab(tab) },
                onSelectList  = { listUri, additionalUri -> viewModel.addAccountToList(listUri, additionalUri) },
                onDismiss     = { viewModel.dismissListPicker() }
            )
        }

        // Bug fix (item 3): unconditional opaque backing for the cold-boot
        // window described above — sits above every other layer (matching
        // PixelMatrixOverlay's own z-order) so nothing real is reachable
        // until the very first wipe-in has genuinely finished covering the
        // screen, regardless of how many frames that takes to kick off.
        if (coldLaunchCovered) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        // Retro pixel-matrix transition overlay — last child so it draws
        // above every other layer (feed, Hub, profile, dialogs) while a
        // transition is in progress; renders nothing once HIDDEN.
        PixelMatrixOverlay(controller = pixelController, modifier = Modifier.fillMaxSize())
    }
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            kotlinx.coroutines.delay(6000)
            viewModel.clearError()
        }
    }
}
