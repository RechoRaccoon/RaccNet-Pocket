package com.mediaviewer.viewmodel

import com.mediaviewer.platform.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediaviewer.model.*
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.repository.E621Repository
import com.mediaviewer.repository.RockskyRepository
import com.mediaviewer.repository.StreamplaceRepository
import com.mediaviewer.repository.WikipediaRepository
import com.mediaviewer.ui.PostKindFilter
import com.mediaviewer.ui.ReviewKindFilter
import com.mediaviewer.ui.matches
import com.mediaviewer.ui.matchesReview
import com.mediaviewer.ui.matchesBacklog
import com.mediaviewer.tagging.TagSuggestionProvider
import com.mediaviewer.tagging.TaggerState
import com.mediaviewer.tagging.TagDatasetInfo
import com.mediaviewer.tagging.TagExportedPost
import com.mediaviewer.platform.AppPlatform
import com.mediaviewer.platform.FontImport
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.util.PreferencesManager
import com.mediaviewer.util.StoredBskyAccount
import com.mediaviewer.worker.urlToDownloadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's state and actions — shared by Android and iOS. Everything the
 * operating system has to do (haptics, toasts, downloads, file pickers, AI
 * tagging, widgets…) goes through [platform]; on Android that's
 * AndroidAppPlatform, i.e. the same code this class used to run through its
 * Application context.
 */
class MainViewModel(
    platformFactory: (BlueskyRepository, E621Repository) -> AppPlatform
) : ViewModel() {

    private val bskyRepo  = BlueskyRepository()
    private val e621Repo  = E621Repository()
    private val platform: AppPlatform = platformFactory(bskyRepo, e621Repo)
    private val prefs     = PreferencesManager(platform.context)
    private val streamplaceRepo = StreamplaceRepository()
    private val taggingRepo = platform.tagging
    // Item 16: Rocksky music-scrobbling integration.
    private val rockskyRepo = RockskyRepository()

    /** False on platforms without on-device AI tagging (iOS for now) —
     *  the UI hides the feature there. */
    val taggingSupported: Boolean get() = taggingRepo.isSupported

    // Item 8: shared haptic tap, callable from anywhere in the ViewModel
    // (opening a profile, sending a message/comment/post, running a search).
    private fun tapHaptic() = platform.haptic()

    // ── Session ───────────────────────────────────────────────────────────────
    private val _bskyLoggedIn = MutableStateFlow(false)
    val bskyLoggedIn: StateFlow<Boolean> = _bskyLoggedIn

    private val _e621LoggedIn = MutableStateFlow(false)
    val e621LoggedIn: StateFlow<Boolean> = _e621LoggedIn

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var bskyToken        = ""
    private var bskyRefreshToken = ""
    private val _bskyDid = MutableStateFlow("")
    val bskyDid: StateFlow<String> = _bskyDid
    var bskyHandle               = ""
    var e621Username             = ""
    var e621ApiKey               = ""

    // ── Settings ──────────────────────────────────────────────────────────────
    private val _reducedAnimations = MutableStateFlow(false)
    val reducedAnimations: StateFlow<Boolean> = _reducedAnimations

    // Item (this session): profile row layout toggle — false (default) uses
    // the new single-row icon layout, true swaps back to the classic
    // two-row text-label layout.
    private val _classicProfileTabRow = MutableStateFlow(false)
    val classicProfileTabRow: StateFlow<Boolean> = _classicProfileTabRow

    fun setClassicProfileTabRow(enabled: Boolean) {
        _classicProfileTabRow.value = enabled
        viewModelScope.launch { prefs.setClassicProfileTabRow(enabled) }
    }

    // Fix (per feedback): "Rounded grid tiles" setting — false (default)
    // renders flat square tiles with no outline in the profile square grid.
    private val _squareGridRounded = MutableStateFlow(false)
    val squareGridRounded: StateFlow<Boolean> = _squareGridRounded

    fun setSquareGridRounded(enabled: Boolean) {
        _squareGridRounded.value = enabled
        viewModelScope.launch { prefs.setSquareGridRounded(enabled) }
    }

    // Feature request #7: experimental 3-wide variant of the Pinterest-style
    // masonry (Posts tab's All/Images filters). Default (false) stays 2.
    private val _pinterestThreeColumns = MutableStateFlow(false)
    val pinterestThreeColumns: StateFlow<Boolean> = _pinterestThreeColumns
    fun setPinterestThreeColumns(enabled: Boolean) {
        _pinterestThreeColumns.value = enabled
        viewModelScope.launch { prefs.setPinterestThreeColumns(enabled) }
    }

    // Feature request #8: "I hate fun" — blur Bluesky-labeled sexual/adult
    // posts behind a tap-to-reveal cover instead of hiding them outright.
    private val _hateFunBlurNsfw = MutableStateFlow(false)
    val hateFunBlurNsfw: StateFlow<Boolean> = _hateFunBlurNsfw
    fun setHateFunBlurNsfw(enabled: Boolean) {
        _hateFunBlurNsfw.value = enabled
        viewModelScope.launch { prefs.setHateFunBlurNsfw(enabled) }
    }

    private val _combineListsAndPacks = MutableStateFlow(false)
    val combineListsAndPacks: StateFlow<Boolean> = _combineListsAndPacks

    // Big Update #1 / #9: "Liquid Glass" theme toggle — on by default
    private val _liquidGlass = MutableStateFlow(true)
    val liquidGlass: StateFlow<Boolean> = _liquidGlass

    fun setLiquidGlass(enabled: Boolean) {
        _liquidGlass.value = enabled
        viewModelScope.launch { prefs.setLiquidGlass(enabled) }
    }

    // Item 26: 0f..1f dial on top of the on/off toggle above — 1f is the
    // current full blur/magnify look, 0f is flat and fully transparent.
    private val _liquidGlassIntensity = MutableStateFlow(1f)
    val liquidGlassIntensity: StateFlow<Float> = _liquidGlassIntensity

    fun setLiquidGlassIntensity(intensity: Float) {
        _liquidGlassIntensity.value = intensity.coerceIn(0f, 1f)
        viewModelScope.launch { prefs.setLiquidGlassIntensity(intensity) }
    }

    // Bug fix: independent rim/outline strength dial, split out from the
    // background dial above — 1f is the current full strongly-tinted rim,
    // 0f is no rim at all.
    private val _glassRimIntensity = MutableStateFlow(1f)
    val glassRimIntensity: StateFlow<Float> = _glassRimIntensity

    fun setGlassRimIntensity(intensity: Float) {
        _glassRimIntensity.value = intensity.coerceIn(0f, 1f)
        viewModelScope.launch { prefs.setGlassRimIntensity(intensity) }
    }

    // Item 7: see glassRimVibrantSecondary's doc comment on the
    // PreferencesManager flow this mirrors.
    private val _glassRimVibrantSecondary = MutableStateFlow(true)
    val glassRimVibrantSecondary: StateFlow<Boolean> = _glassRimVibrantSecondary

    fun setGlassRimVibrantSecondary(enabled: Boolean) {
        _glassRimVibrantSecondary.value = enabled
        viewModelScope.launch { prefs.setGlassRimVibrantSecondary(enabled) }
    }

    // Item 2: whether the "Add To" popup should open automatically right after
    // following someone. Defaulted off — the user opts in from Settings.
    private val _autoAddToOnFollow = MutableStateFlow(false)
    val autoAddToOnFollow: StateFlow<Boolean> = _autoAddToOnFollow

    fun setAutoAddToOnFollow(enabled: Boolean) {
        _autoAddToOnFollow.value = enabled
        viewModelScope.launch { prefs.setAutoAddToOnFollow(enabled) }
    }

    // Settings Update: universally hides text-only posts (no image/video) across every feed.
    private val _hideTextOnlyPosts = MutableStateFlow(false)
    val hideTextOnlyPosts: StateFlow<Boolean> = _hideTextOnlyPosts

    fun setHideTextOnlyPosts(enabled: Boolean) {
        _hideTextOnlyPosts.value = enabled
        viewModelScope.launch { prefs.setHideTextOnlyPosts(enabled) }
    }

    // Phase 4 — on-device translation toggle + preferred target language.
    private val _translationEnabled = MutableStateFlow(false)
    val translationEnabled: StateFlow<Boolean> = _translationEnabled

    private val _translationTargetLang = MutableStateFlow(com.mediaviewer.platform.defaultLanguageCode().ifBlank { "en" })
    val translationTargetLang: StateFlow<String> = _translationTargetLang

    fun setTranslationEnabled(enabled: Boolean) {
        _translationEnabled.value = enabled
        viewModelScope.launch { prefs.setTranslateEnabled(enabled) }
    }

    fun setTranslationTargetLang(languageTag: String) {
        _translationTargetLang.value = languageTag
        viewModelScope.launch { prefs.setTranslateTargetLang(languageTag) }
    }

    // Phase 4 — custom app-wide font pack: absolute path to the font file
    // copied onto internal storage, plus its original filename for display.
    private val _customFontPath = MutableStateFlow<String?>(null)
    val customFontPath: StateFlow<String?> = _customFontPath

    private val _customFontName = MutableStateFlow<String?>(null)
    val customFontName: StateFlow<String?> = _customFontName

    /** Copies the picked font file's bytes into internal storage (so it
     *  survives the transient permission a content:// Uri grants) and points
     *  the app-wide Typography at it. Only .ttf/.otf/.ttc are accepted —
     *  anything else fails loudly via the normal error snackbar rather than
     *  silently producing a FontFamily that crashes the first time Compose
     *  actually tries to lay out text with it. */
    fun setCustomFontFromUri(uri: com.mediaviewer.platform.PlatformUri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val oldPath = _customFontPath.value
                when (val result = platform.importCustomFont(uri)) {
                    is FontImport.Error -> {
                        _errorMessage.value = result.message
                        return@launch
                    }
                    is FontImport.Success -> {
                        prefs.setCustomFontPath(result.path)
                        prefs.setCustomFontName(result.displayName)
                        _customFontPath.value = result.path
                        _customFontName.value = result.displayName
                        // Clean up the previous font file now that the new one is active.
                        oldPath?.let { platform.deleteFile(it) }
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't load that font file: ${e.message}"
            }
        }
    }

    fun resetCustomFont() {
        viewModelScope.launch {
            _customFontPath.value?.let { path -> platform.deleteFile(path) }
            prefs.setCustomFontPath(null)
            prefs.setCustomFontName(null)
            _customFontPath.value = null
            _customFontName.value = null
        }
    }

    private val _downloadOnLike = MutableStateFlow(false)
    val downloadOnLike: StateFlow<Boolean> = _downloadOnLike

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress

    @kotlin.concurrent.Volatile private var cancelDownloadFlag = false

    // ── App Mode / Screen ─────────────────────────────────────────────────────
    private val _appMode     = MutableStateFlow(AppMode.BLUESKY)
    val appMode: StateFlow<AppMode> = _appMode

    private val _screenState = MutableStateFlow(ScreenState.SETTINGS)
    val screenState: StateFlow<ScreenState> = _screenState
    // Feature (this session): the Hub's "Return to Feed" button reads "Open
    // Feed" the very first time it's shown (the app now opens straight on
    // the Hub, per feedback, instead of auto-jumping into the feed — see
    // this session's init{} change), then switches to "Return to Feed"
    // once the person has actually been to the feed at least once, exactly
    // matching what tapping it will do at that point. Session-only by
    // design (not persisted) — every fresh app launch opens on the Hub
    // again, so "Open Feed" is the right label again too.
    private val _hasVisitedFeed = MutableStateFlow(false)
    val hasVisitedFeed: StateFlow<Boolean> = _hasVisitedFeed

    /** Grid or timeline — whichever the person was last in (updated by the
     *  screenState collector in init). Feeds open in the grid, so that's
     *  also where a first "Open Feed" goes. */
    private var lastFeedView = ScreenState.GRID

    /** The Hub's Return to Feed: back to the grid or the timeline, whichever
     *  the person was last using. */
    fun returnToFeed() = setScreen(lastFeedView)

    // Track swipe direction for animations (1=next/down, -1=prev/up, 0=other)
    private val _navDirection = MutableStateFlow(0)
    val navDirection: StateFlow<Int> = _navDirection

    // ── List picker (shown after following someone) ───────────────────────────
    private val _listPickerTargetDid = MutableStateFlow<String?>(null)
    val listPickerTargetDid: StateFlow<String?> = _listPickerTargetDid

    private val _userLists = MutableStateFlow<List<BskyList>>(emptyList())
    val userLists: StateFlow<List<BskyList>> = _userLists

    private val _userStarterPacks = MutableStateFlow<List<BskyStarterPackView>>(emptyList())
    val userStarterPacks: StateFlow<List<BskyStarterPackView>> = _userStarterPacks

    private val _userListsLoading = MutableStateFlow(false)
    val userListsLoading: StateFlow<Boolean> = _userListsLoading

    /** "LISTS" or "STARTER_PACKS" — persisted so the picker reopens on the last used tab */
    private val _lastPickerTab = MutableStateFlow(com.mediaviewer.util.ListRecency.lastTab ?: "LISTS")
    val lastPickerTab: StateFlow<String> = _lastPickerTab

    fun setPickerTab(tab: String) {
        _lastPickerTab.value = tab
        com.mediaviewer.util.ListRecency.lastTab = tab
        viewModelScope.launch { prefs.setLastPickerTab(tab) }
    }

    // ── Feed ──────────────────────────────────────────────────────────────────
    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = _mediaItems

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    private var feedCursor: String?  = null
    private var isLoadingMore        = false
    /** Bumped by every feed reset/switch — see loadFeed. */
    @kotlin.concurrent.Volatile private var feedLoadGeneration = 0

    // Tracks what kind of feed is active so loadMore() uses the right endpoint
    private enum class ActiveFeedMode { NORMAL, AUTHOR, LIKES, FRIENDS, SAVES, HISTORY, EXTERNAL }
    private var activeFeedMode = ActiveFeedMode.NORMAL
    private var activeFeedActorDid: String? = null  // set when mode == AUTHOR or LIKES

    // ── Author-feed overlay — saves main feed state so we can restore exactly ──
    data class AuthorFeedSavedState(
        val author: AuthorInfo,
        val items: List<MediaItem>,
        val currentIndex: Int,
        val cursor: String?,
        val feedUri: String?
    )
    private val _authorFeedState = MutableStateFlow<AuthorFeedSavedState?>(null)
    val authorFeedState: StateFlow<AuthorFeedSavedState?> = _authorFeedState

    // ── Profile Overlay (Profile Overhaul) ──────────────────────────────────
    // Profile "Posts" tab redesign: MEDIA and TEXT_POSTS used to be two
    // separate top-level tabs backed by two separate (redundant) fetches of
    // the exact same underlying post list, just client-side filtered two
    // different ways. Per the feature request they're now a single POSTS
    // tab with a sub-filter row (All/Images/Text Posts/Horizontal Videos/
    // Vertical Videos) — see ProfileOverlay's PostKindFilter.
    // LISTS_FEEDS ("Lists/Feeds") is always the last tab: the account's
    // feeds, lists, starter packs and moderation lists (see loadProfileLists).
    enum class ProfileTab { POSTS, VODS, REPOSTS, LIKES, BLOGS, REVIEWS, BACKLOG, MUSIC_HISTORY, LISTS_FEEDS }

    /** A profile's Lists/Feeds tab content (loaded when the tab is opened). */
    data class ProfileListsState(
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val failed: Boolean = false,
        val entries: List<ProfileListEntry> = emptyList()
    )

    data class ProfileTabState(
        val items: List<MediaItem> = emptyList(),
        val blogs: List<LeafletBlog> = emptyList(),
        val reviews: List<PopfeedReview> = emptyList(),
        val backlog: List<PopfeedBacklogItem> = emptyList(),
        // Item 19: VODs listed vertically, newest first — see StreamplaceRepository.
        val vods: List<StreamplaceVideoView> = emptyList(),
        // Item 16: Rocksky scrobble history, most recent first.
        val musicHistory: List<RockskyTrack> = emptyList(),
        val cursor: String? = null,
        val loading: Boolean = false,
        val loaded: Boolean = false
    )

    // Feature request #4: on-disk snapshot of one profile's tab strip —
    // which tabs it has, plus a trimmed first batch of each tab's content —
    // written every time a profile's tabs finish (re)loading, and read back
    // the *next* time that same profile is opened so its tabs and first
    // results can render instantly instead of the tab strip staying blank
    // until the network round-trip finishes. This mirrors how the Hub's
    // Reviews/Blogs sections work off HUB_REVIEWS_CACHE_JSON/
    // HUB_BLOGS_CACHE_JSON: show the cached snapshot immediately, kick off
    // the exact same real load in the background regardless (openProfile()
    // always does this unconditionally), and let the real result — whatever
    // it turns out to be — replace the cached one once it comes back. If a
    // tab that used to have content no longer does (e.g. its one post got
    // deleted), or a tab that had nothing before now does, the normal
    // "add/remove from availableTabs" logic in openProfile() already
    // reconciles that once the fresh probe finishes — see the isEmpty()
    // checks added to each probe below.
    @kotlinx.serialization.Serializable
    private data class CachedProfileTabs(
        val availableTabs: Set<String> = emptySet(),
        val posts: List<MediaItem> = emptyList(),
        val reposts: List<MediaItem> = emptyList(),
        val likes: List<MediaItem> = emptyList(),
        val blogs: List<LeafletBlog> = emptyList(),
        val reviews: List<PopfeedReview> = emptyList(),
        val backlog: List<PopfeedBacklogItem> = emptyList(),
        val vods: List<StreamplaceVideoView> = emptyList(),
        // Fix (per feedback): which sub-filter pills had content, per tab —
        // tab name -> sub-filter names that matched at least one loaded
        // item (same predicates the UI's subtab strips use). Lets the
        // subtab strip render instantly from memory on the next open,
        // instead of waiting for fresh data to decide which pills exist.
        val subtabs: Map<String, Set<String>> = emptyMap(),
        val savedAt: Long = 0L
    )
    // How many items of each per-tab list get persisted to disk — just
    // enough for an instant "first screenful" on reopen, not the entire
    // history (which would make the cache file grow without bound as
    // someone scrolls a big profile).
    private val PROFILE_TAB_CACHE_ITEM_LIMIT = 30
    // Item 7: how many scrobbles each Music History page fetches/pages in
    // at a time — matches RockskyRepository.getScrobbles' own default.
    private val MUSIC_HISTORY_PAGE_SIZE = 30
    // Bounds the number of distinct profiles kept in the cache at all —
    // oldest (by savedAt) evicted first once this is exceeded, so visiting
    // many different profiles over time can't grow the file unboundedly.
    private val PROFILE_TAB_CACHE_MAX_ENTRIES = 25
    // In-memory mirror of the on-disk cache, hydrated once at startup (see
    // init{}) and updated in lockstep with every disk write, so reads don't
    // need to suspend on DataStore.
    private var profileTabCache: MutableMap<String, CachedProfileTabs> = mutableMapOf()
    private var profileTabCacheHydrated = false
    private val profileTabCacheMutex = Mutex()
    /** Guards loading the DM conversation list (see ensureDmConversationsLoadedSuspend). */
    private val dmConversationsMutex = Mutex()

    private suspend fun ensureProfileTabCacheHydrated() {
        if (profileTabCacheHydrated) return
        profileTabCacheMutex.withLock {
            if (profileTabCacheHydrated) return
            runCatching {
                val json = prefs.profileTabCacheJson.first()
                val parsed: Map<String, CachedProfileTabs>? = json?.takeIf { it.isNotBlank() }?.let { com.mediaviewer.json.StellarJson.default.decodeFromString<Map<String, CachedProfileTabs>>(it) }
                if (parsed != null) profileTabCache = parsed.toMutableMap()
            }
            profileTabCacheHydrated = true
        }
    }

    /** Seeds a freshly-created [ProfileOverlayState] with whatever's on
     *  disk for this author, if anything — called synchronously from
     *  [openProfile] before the real network probes kick off. Since disk
     *  hydration is async, the very first profile opened right after
     *  process start may miss the cache once (falls back to the normal
     *  blank-until-loaded behavior); every subsequent open in the same
     *  process is instant. */
    private fun cachedProfileTabsFor(did: String): CachedProfileTabs? {
        if (!profileTabCacheHydrated) return null
        return profileTabCache[did]
    }

    /** Builds a snapshot of the given profile's *currently loaded* tab
     *  state and persists it, called after any tab finishes (re)loading —
     *  see loadProfileTab()'s success path and each of the Blogs/Reviews/
     *  Backlog/Vods probes in openProfile(). */
    private fun persistProfileTabCache(state: ProfileOverlayState) {
        viewModelScope.launch(Dispatchers.IO) {
            ensureProfileTabCacheHydrated()
            profileTabCacheMutex.withLock {
                // Fix (per feedback): merge with the previously cached
                // subtabs instead of rebuilding from scratch — otherwise a
                // persist that runs before a tab has (re)loaded wipes its
                // remembered pills, and they never survive a reopen.
                val prevSubtabs = profileTabCache[state.author.did]?.subtabs ?: emptyMap()
                val entry = CachedProfileTabs(
                    availableTabs = state.availableTabs.map { it.name }.toSet(),
                    posts = state.tabStates[ProfileTab.POSTS]?.items?.take(PROFILE_TAB_CACHE_ITEM_LIMIT) ?: emptyList(),
                    reposts = state.tabStates[ProfileTab.REPOSTS]?.items?.take(PROFILE_TAB_CACHE_ITEM_LIMIT) ?: emptyList(),
                    likes = state.tabStates[ProfileTab.LIKES]?.items?.take(PROFILE_TAB_CACHE_ITEM_LIMIT) ?: emptyList(),
                    blogs = state.tabStates[ProfileTab.BLOGS]?.blogs ?: emptyList(),
                    reviews = state.tabStates[ProfileTab.REVIEWS]?.reviews ?: emptyList(),
                    backlog = state.tabStates[ProfileTab.BACKLOG]?.backlog ?: emptyList(),
                    vods = state.tabStates[ProfileTab.VODS]?.vods?.take(PROFILE_TAB_CACHE_ITEM_LIMIT) ?: emptyList(),
                    // Fix (per feedback): remember which sub-filter pills
                    // had content, using the exact same predicates the
                    // UI's subtab strips use — merged over the previously
                    // cached set so tabs without fresh data keep their
                    // remembered pills instead of being wiped.
                    subtabs = buildMap {
                        putAll(prevSubtabs)
                        fun postSubtabs(tab: ProfileTab) {
                            val items = state.tabStates[tab]?.items
                            if (items.isNullOrEmpty()) return
                            put(tab.name, PostKindFilter.entries
                                .filter { f -> items.any { item -> f.matches(item) } }
                                .map { it.name }.toSet())
                        }
                        postSubtabs(ProfileTab.POSTS)
                        postSubtabs(ProfileTab.REPOSTS)
                        postSubtabs(ProfileTab.LIKES)
                        val reviews = state.tabStates[ProfileTab.REVIEWS]?.reviews
                        if (!reviews.isNullOrEmpty()) {
                            put(ProfileTab.REVIEWS.name, ReviewKindFilter.entries
                                .filter { f -> reviews.any { r -> f.matchesReview(r) } }
                                .map { it.name }.toSet())
                        }
                        val backlog = state.tabStates[ProfileTab.BACKLOG]?.backlog
                        if (!backlog.isNullOrEmpty()) {
                            put(ProfileTab.BACKLOG.name, ReviewKindFilter.entries
                                .filter { f -> backlog.any { b -> f.matchesBacklog(b) } }
                                .map { it.name }.toSet())
                        }
                    },
                    savedAt = com.mediaviewer.platform.currentTimeMillis()
                )
                profileTabCache[state.author.did] = entry
                // Evict oldest entries once over the cap.
                if (profileTabCache.size > PROFILE_TAB_CACHE_MAX_ENTRIES) {
                    val toDrop = profileTabCache.entries.sortedBy { it.value.savedAt }
                        .take(profileTabCache.size - PROFILE_TAB_CACHE_MAX_ENTRIES).map { it.key }
                    toDrop.forEach { profileTabCache.remove(it) }
                }
                runCatching { prefs.setProfileTabCacheJson(com.mediaviewer.json.StellarJson.default.encodeToString<Map<String, CachedProfileTabs>>(profileTabCache)) }
            }
        }
    }

    // ── Follower scan (feature: automatic Reviews/Blogs subscriptions) ────
    // See startFollowerScan's doc comment for the full picture. Idle before
    // ever run (and between runs); Scanning while in progress, with running
    // counts the Hub's intro bubble → progress state can render as it goes;
    // Completed once a run finishes, carrying the same three numbers the
    // completion popup is meant to show.
    sealed class FollowerScanState {
        object Idle : FollowerScanState()
        data class Scanning(val accountsScanned: Int, val reviewsFound: Int, val blogsFound: Int) : FollowerScanState()
        data class Completed(val accountsScanned: Int, val reviewsFound: Int, val blogsFound: Int) : FollowerScanState()
    }

    data class ProfileOverlayState(
        val author: AuthorInfo,
        val profile: ProfileData? = null,
        val loadingProfile: Boolean = true,
        // True while a manual "Refresh" (see refreshProfile) is in flight —
        // only drives the refresh button's spinner.
        val refreshing: Boolean = false,
        // Item 16: this profile's live Rocksky now-playing track, if any —
        // see openProfile()'s own probe for how this is fetched.
        val nowPlaying: RockskyTrack? = null,
        val selectedTab: ProfileTab = ProfileTab.POSTS,
        // Blogs/Reviews/Backlog are added to this set only once probing
        // confirms the account actually has Leaflet/Popfeed content — see
        // openProfile().
        val availableTabs: Set<ProfileTab> = setOf(ProfileTab.POSTS, ProfileTab.REPOSTS, ProfileTab.LIKES, ProfileTab.LISTS_FEEDS),
        val tabStates: Map<ProfileTab, ProfileTabState> = emptyMap(),
        // Lists/Feeds tab: its content, and which sub-tab is picked
        // (null = "All").
        val lists: ProfileListsState = ProfileListsState(),
        val listKindFilter: ProfileListKind? = null,
        // Fix (per feedback): which sub-filter pills had content the last
        // time this profile's tabs were loaded (from the on-disk cache —
        // see openProfile's seeding). The subtab strips union this with
        // the freshly-loaded matches, so they render instantly from memory
        // and reconcile once real data arrives.
        val seededSubtabs: Map<ProfileTab, Set<String>> = emptyMap(),
        val openBlog: LeafletBlog? = null,
        val openReview: PopfeedReview? = null,
        // Backlog cards' own "full info menu" (Titles feature) — see
        // openProfileTitle/closeProfileTitle and TitleDetailOverlay.
        val openTitle: TitleSearchResult? = null,
        // Set alongside [openTitle] whenever the title page was opened *from*
        // a specific review (a Hub Mutual Review card, or a row on someone's
        // Reviews tab — see openMutualReview/openProfileReview) rather than
        // from a Backlog card. TitleDetailOverlay uses this to auto-select
        // that reviewer's bubble (as the second option, right after
        // "Summary") the moment the page opens, instead of landing on the
        // Summary tab and making the person find it themselves.
        val openTitlePreselectedReview: FriendPopfeedReview? = null,
        // Pinch navigation: tapping a post from this profile's grid doesn't
        // destroy this state (see openPostFromProfileTab) — it just flips
        // this to true, so the composable stays alive (scroll position and
        // all) invisibly behind the post pager. Pinching back in from that
        // post flips it back to false instead of reconstructing the profile
        // from scratch.
        val hidden: Boolean = false,
        /** Opened from a Hub title card/blog: only that title/blog is
         *  shown, and closing it closes the whole thing (no profile). */
        val standaloneDetail: Boolean = false,
        // Bug fix: scrolling a profile's grid, tapping a post, then pinching
        // back in was jumping to the bottom of the results instead of
        // staying put. The composable itself does stay alive at zero size
        // while hidden (see `hidden` above) and in principle should keep its
        // own LazyListState untouched, but a LazyColumn collapsed to 0dp and
        // re-expanded doesn't reliably preserve its exact scroll position on
        // its own. So the scroll position is now also explicitly captured
        // here (see saveProfileScrollPosition(), called right before hiding,
        // from both openPostFromProfileTab and pinchOutFromProfile) and
        // force-restored by ProfileOverlay itself the moment `hidden` flips
        // back to false, instead of trusting Compose to have kept it.
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
        // Adjustment #7: the "tab remembering thing" below (see `parent`'s
        // own doc comment) only ever restored which main ProfileTab was
        // selected, not which PostKindFilter/ReviewKindFilter sub-tab was
        // active within it — so popping back to a parent profile would
        // land back on, say, Posts, but reset to the "All" sub-filter even
        // if Text Posts had been selected. Moving these two out of
        // ProfileOverlay's local Compose `remember` state and into the
        // state object that `parent` snapshots/restores fixes that; see
        // selectPostKindFilter()/selectReviewKindFilter() below for the
        // setters, and selectProfileTab() for why switching *to* a new
        // main tab still resets these back to ALL (unlike a parent-chain
        // restore, which leaves them alone).
        val postKindFilter: PostKindFilter = PostKindFilter.ALL,
        val reviewKindFilter: ReviewKindFilter = ReviewKindFilter.ALL,
        // Music History's sub-tabs: 0 = "Recent" (the scrobble list), else
        // a year = "Top <year>" (Rocksky Wrapped for that year).
        val musicYear: Int = 0,
        // Years this person has Music History in, newest first — one
        // "Top <year>" sub-tab each. Filled in once the tab is opened.
        val musicYears: List<Int> = emptyList(),
        val musicWrapped: Map<Int, com.mediaviewer.model.RockskyWrapped> = emptyMap(),
        // Item 17: if a profile is opened while another profile overlay is
        // already up (visible or hidden behind a post pager) — e.g. tapping
        // a different author's avatar from inside a post reached via a
        // hidden profile's grid — the profile it was opened on top of is
        // saved here instead of being discarded outright. closeProfile()
        // pops back to it (or unwinds past it entirely if it was only
        // hidden pager scaffolding) instead of jumping straight to null,
        // so the whole stack unwinds properly instead of stranding/losing
        // an intermediate layer.
        val parent: ProfileOverlayState? = null
    )

    private val _profileOverlay = MutableStateFlow<ProfileOverlayState?>(null)
    val profileOverlay: StateFlow<ProfileOverlayState?> = _profileOverlay

    // ── Own profile preview (for the Settings "Profile" button) ────────────────
    private val _selfProfile = MutableStateFlow<ProfileData?>(null)
    val selfProfile: StateFlow<ProfileData?> = _selfProfile

    private fun loadSelfProfile() {
        if (!_bskyLoggedIn.value) return
        viewModelScope.launch(Dispatchers.IO) { loadSelfProfileSuspend() }
    }

    // Bug fix (per feedback — Hub's reflective profile colors don't show
    // until the feed is opened and returned from): the only other place
    // that ever retried a failed/still-in-flight loadSelfProfile() was
    // setScreen()'s own `if (screen == ScreenState.SETTINGS && ...)` check
    // below — which only fires on an actual *navigation* to Settings via
    // setScreen(). The app now opens directly on the Hub (ScreenState
    // starts as SETTINGS, see this session's init{} change) without ever
    // calling setScreen(SETTINGS) at all, so that retry path never ran on
    // a cold start — if the one-shot loadSelfProfile() call in init{}
    // happened to race the network coming up (very plausible right at
    // process start) and failed silently, nothing on the Hub would ever
    // retry it until the person actually left for the feed and came back
    // (which DOES go through setScreen(SETTINGS)). This suspend version
    // lets startHubBackgroundWarmup's own retryWithBackoff loop (below)
    // cover this the same reliable way it already covers Mutuals/Reviews/
    // Blogs/Livestreams, independent of navigation entirely.
    /** An account's banner URL (null = it has none), for working out its
     *  profile color where only its DID and avatar are known — see
     *  ProfileColorStore. A failed lookup is a failure, not "no banner". */
    suspend fun fetchBannerUrl(did: String): Result<String?> {
        if (bskyToken.isBlank()) return Result.failure(IllegalStateException("not signed in"))
        var result = bskyRepo.getFullProfile(bskyToken, did)
        if (result.isFailure && refreshBskyTokenIfPossible()) result = bskyRepo.getFullProfile(bskyToken, did)
        return result.map { it.bannerUrl }
    }

    private suspend fun loadSelfProfileSuspend() {
        if (!_bskyLoggedIn.value) return
        bskyRepo.getFullProfile(bskyToken, _bskyDid.value).onSuccess {
            _selfProfile.value = it
            // Live Link widget feature: keeps a cached copy of the avatar
            // URL in prefs so the widget (a separate RemoteViews surface
            // with no ViewModel/network session of its own) can tint itself
            // to the profile color without needing its own auth round trip
            // — see PreferencesManager.SELF_AVATAR_URL_CACHE's doc comment.
            prefs.setSelfAvatarUrlCache(it.author.avatarUrl)
        }
    }

    /** Item 19: the profile page's edit popup → Save. [onDone] gets null on
     *  success (popup closes, page reloads) or a message to show. */
    fun updateOwnProfile(
        displayName: String, description: String, handle: String,
        avatarUri: com.mediaviewer.platform.PlatformUri?, bannerUri: com.mediaviewer.platform.PlatformUri?,
        onDone: (String?) -> Unit
    ) {
        if (!_bskyLoggedIn.value) { onDone("Not signed in"); return }
        val newHandle = handle.trim().removePrefix("@").takeIf { it.isNotBlank() && !it.equals(bskyHandle, ignoreCase = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val context = platform.context
            var result = bskyRepo.updateOwnProfile(bskyToken, context, _bskyDid.value, displayName, description, avatarUri, bannerUri, newHandle)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) {
                    result = bskyRepo.updateOwnProfile(bskyToken, context, _bskyDid.value, displayName, description, avatarUri, bannerUri, newHandle)
                }
            }
            val error = result.exceptionOrNull()?.message
            if (error == null && newHandle != null) bskyHandle = newHandle
            withContext(Dispatchers.Main) {
                onDone(error)
                if (error == null) {
                    loadSelfProfile()
                    // Give the AppView a moment to index the new record.
                    viewModelScope.launch { kotlinx.coroutines.delay(1200); refreshProfile() }
                }
            }
        }
    }

    /** Opens the logged-in user's own Profile Overlay — used by the Settings
     *  "Profile" button. */
    fun openOwnProfile() {
        val cached = _selfProfile.value
        val author = cached?.author ?: AuthorInfo(did = _bskyDid.value, handle = bskyHandle, displayName = bskyHandle, avatarUrl = null)
        openProfile(author)
    }

    // ── Local History (Settings Update) ─────────────────────────────────────────
    // Remembers every post the user scrolls onto, purely on-device, so the
    // History button can show them again later. Capped to avoid unbounded growth.
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val HISTORY_LIMIT = 500

    private fun loadHistoryFromPrefs() {
        viewModelScope.launch {
            val json = prefs.historyJson.first()
            val parsed = runCatching {
                json?.takeIf { it.isNotBlank() }?.let { com.mediaviewer.json.StellarJson.default.decodeFromString<List<HistoryEntry>>(it) } ?: emptyList()
            }.getOrDefault(emptyList())
            _history.value = parsed
        }
    }

    /** Runs for the whole lifetime of the app: whenever the on-screen post
     *  changes (in ANY feed/overlay that ultimately renders through the main
     *  pager), it's recorded into History after a short debounce so a fast
     *  swipe-through doesn't spam a write for every frame. */
    private fun trackHistoryAutomatically() {
        viewModelScope.launch {
            combine(_mediaItems, _currentIndex) { items, idx -> items.getOrNull(idx) }
                .filterNotNull()
                .debounce(500)
                .distinctUntilChangedBy { it.postUri.ifBlank { it.id } }
                .collect { item -> recordHistoryEntry(item) }
        }
    }

    private fun recordHistoryEntry(item: MediaItem) {
        val key = item.postUri.ifBlank { item.id }
        if (key.isBlank()) return
        val entry = HistoryEntry(
            uri = item.postUri, cid = item.postCid, mediaUrl = item.mediaUrl, thumbUrl = item.thumbUrl,
            isVideo = item.isVideo, text = item.text, authorDid = item.author.did,
            authorHandle = item.author.handle, authorDisplayName = item.author.displayName,
            authorAvatarUrl = item.author.avatarUrl, viewedAt = com.mediaviewer.platform.currentTimeMillis()
        )
        val updated = (listOf(entry) + _history.value.filterNot { it.uri.ifBlank { it.cid } == key }).take(HISTORY_LIMIT)
        _history.value = updated
        viewModelScope.launch { prefs.setHistoryJson(com.mediaviewer.json.StellarJson.default.encodeToString<List<HistoryEntry>>(updated)) }
    }

    private fun HistoryEntry.toMediaItem(): MediaItem = MediaItem(
        id = cid.ifBlank { uri }, mediaUrl = mediaUrl, thumbUrl = thumbUrl, isVideo = isVideo,
        postUri = uri, postCid = cid,
        author = AuthorInfo(did = authorDid, handle = authorHandle, displayName = authorDisplayName, avatarUrl = authorAvatarUrl),
        text = text
    )

    /** Opens the local History feed (Settings "History" button). */
    fun showHistory() {
        if (!_bskyLoggedIn.value) return
        val items = filterHidden(_history.value.map { it.toMediaItem() })
        if (items.isEmpty()) { showToast("No history yet"); return }
        _currentIndex.value = 0
        enterSpecialFeed("History")
        feedLoadGeneration++
        _isLoading.value = false
        feedCursor = null
        activeFeedMode = ActiveFeedMode.HISTORY
        activeFeedActorDid = null
        _mediaItems.value = items
        _screenState.value = ScreenState.FEED
    }

    /** Switching into Saved Posts / From Friends / History: remembers the
     *  feed to come back to (only the first time — the original feed stays
     *  saved when hopping between these), and always relabels the header to
     *  the one now showing. It used to keep whichever label came first, so
     *  From Friends → Saved Posts still said "From Friends". */
    private fun enterSpecialFeed(label: String) {
        val cur = _authorFeedState.value
        val author = AuthorInfo(_bskyDid.value, bskyHandle, label, null)
        _authorFeedState.value = if (cur == null) {
            AuthorFeedSavedState(
                author = author, items = _mediaItems.value, currentIndex = _currentIndex.value,
                cursor = feedCursor, feedUri = _selectedFeedUri.value
            )
        } else if (cur.author.displayName == label && cur.author.did == author.did) cur
        else cur.copy(author = author)
    }

    // ── Saves / Bookmarks (Settings Update) ─────────────────────────────────────
    fun showSaves() {
        if (!_bskyLoggedIn.value) return
        // Like switching feeds: the grid opens straight away showing
        // placeholder tiles (not the previous feed's posts) until the saves
        // arrive.
        _currentIndex.value = 0
        enterSpecialFeed("Saved Posts")
        feedLoadGeneration++
        val generation = feedLoadGeneration
        feedCursor = null
        activeFeedMode = ActiveFeedMode.SAVES
        activeFeedActorDid = null
        _mediaItems.value = emptyList()
        _isLoading.value = true
        _screenState.value = ScreenState.GRID
        viewModelScope.launch(Dispatchers.IO) {
            var result = bskyRepo.getBookmarkedPosts(bskyToken)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getBookmarkedPosts(bskyToken)
            }
            // They may have moved on to another feed while this loaded.
            if (generation != feedLoadGeneration || activeFeedMode != ActiveFeedMode.SAVES) return@launch
            result.onSuccess { (items, cursor) ->
                feedCursor = cursor
                activeFeedMode = ActiveFeedMode.SAVES
                activeFeedActorDid = null
                _mediaItems.value = filterHidden(items)
                // Opens in Explore (grid) mode, like From Friends.
                _screenState.value = ScreenState.GRID
            }.onFailure { _errorMessage.value = it.message }
            _isLoading.value = false
        }
    }

    private fun loadMoreSaves() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoadingMore = true
            var result = bskyRepo.getBookmarkedPosts(bskyToken, feedCursor)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getBookmarkedPosts(bskyToken, feedCursor)
            }
            result.onSuccess { (items, cursor) ->
                feedCursor = cursor
                _mediaItems.value = _mediaItems.value + filterHidden(items)
            }.onFailure { _errorMessage.value = it.message }
            isLoadingMore = false
        }
    }

    private val _availableFeeds = MutableStateFlow<List<BskyFeedInfo>>(emptyList())
    val availableFeeds: StateFlow<List<BskyFeedInfo>> = _availableFeeds

    private val _selectedFeedUri = MutableStateFlow<String?>(null)
    val selectedFeedUri: StateFlow<String?> = _selectedFeedUri

    // ── e621 ──────────────────────────────────────────────────────────────────
    private val _e621SearchTags = MutableStateFlow("order:hot")
    val e621SearchTags: StateFlow<String> = _e621SearchTags

    // ── e621 local following ───────────────────────────────────────────────────
    private val _e621FollowedArtists = MutableStateFlow<Set<String>>(emptySet())
    val e621FollowedArtists: StateFlow<Set<String>> = _e621FollowedArtists

    private var e621Page              = 1
    private var e621ShowingFavorites  = false

    // ── Comments ──────────────────────────────────────────────────────────────
    private val _comments = MutableStateFlow<List<CommentItem>>(emptyList())
    val comments: StateFlow<List<CommentItem>> = _comments

    private val _commentsLoading = MutableStateFlow(false)
    val commentsLoading: StateFlow<Boolean> = _commentsLoading

    // ── DMs (item 6) ───────────────────────────────────────────────────────────
    private val _dmConversations = MutableStateFlow<List<DmConversation>>(emptyList())
    // Blocked either way (see BlockedAccounts): never listed, even when an
    // old chat with them still exists on Bluesky's side.
    val dmConversations: StateFlow<List<DmConversation>> = combine(_dmConversations, com.mediaviewer.util.BlockedAccounts.version) { list, _ ->
        list.filterNot { convo -> !convo.isGroup && com.mediaviewer.util.BlockedAccounts.isHidden(convo.member.did) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _dmConversationsLoading = MutableStateFlow(false)
    val dmConversationsLoading: StateFlow<Boolean> = _dmConversationsLoading

    // ── DM Inbox overlay (Settings Update) — pick a conversation, view the full thread ──
    data class DmThreadState(
        val convo: DmConversation,
        val messages: List<BskyMessageView> = emptyList(),
        // Item 12: shared/quoted posts embedded in messages, keyed by message
        // id — parsed once when messages load rather than per-recomposition.
        val embeddedPosts: Map<String, DmEmbeddedPost> = emptyMap(),
        val loading: Boolean = true,
        val sending: Boolean = false,
        val cursor: String? = null,
        // Item 12 follow-up: infinite-scroll-up for older messages — separate
        // from `loading` (the initial/full-thread spinner) so scrolling up to
        // fetch more doesn't replace the whole thread view with a spinner.
        val loadingMore: Boolean = false,
        /** Group chats: every member by DID (names/avatars for each sender). */
        val members: Map<String, AuthorInfo> = emptyMap()
    )
    private val _dmInboxOpen = MutableStateFlow(false)
    val dmInboxOpen: StateFlow<Boolean> = _dmInboxOpen

    private val _dmThread = MutableStateFlow<DmThreadState?>(null)
    val dmThread: StateFlow<DmThreadState?> = _dmThread

    fun openDmInbox() {
        _dmInboxOpen.value = true
        loadDmConversations(silent = false)
        // Always refresh on open: one quick request for the chat list itself
        // (newest activity, unread counts), shown the moment it lands —
        // instead of only ever showing whatever was loaded at app start.
        refreshDmConvosQuick()
    }

    fun closeDmInbox() { _dmInboxOpen.value = false; _dmThread.value = null }

    private var dmQuickRefreshJob: Job? = null
    /** Re-reads just the chat list (not mutuals/blocks/etc.) and merges it in. */
    fun refreshDmConvosQuick() {
        if (!_bskyLoggedIn.value || dmQuickRefreshJob?.isActive == true) return
        dmQuickRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.listConvos(bskyToken, _bskyDid.value).onSuccess { convos ->
                val openId = _dmThread.value?.convo?.convoId
                val fresh = convos.map { if (it.convoId == openId) it.copy(unreadCount = 0) else it }
                val haveDids = fresh.map { it.member.did }.toSet()
                // Mutuals you haven't chatted with yet stay listed after them.
                val rest = _dmConversations.value.filter { it.convoId.isBlank() && it.member.did !in haveDids }
                _dmConversations.value = fresh + rest
                runCatching {
                    prefs.setHubMutualsCache(com.mediaviewer.json.StellarJson.default.encodeToString<List<DmConversation>>(_dmConversations.value))
                }
            }
        }
    }

    /** Opening a chat reads it: clears its unread count here and on Bluesky. */
    private fun markConvoRead(convoId: String) {
        if (convoId.isBlank()) return
        if (_dmConversations.value.any { it.convoId == convoId && it.unreadCount > 0 }) {
            _dmConversations.value = _dmConversations.value.map { if (it.convoId == convoId) it.copy(unreadCount = 0) else it }
        }
        viewModelScope.launch(Dispatchers.IO) { bskyRepo.markConvoRead(bskyToken, _bskyDid.value, convoId) }
    }

    // ── Foreground tracking ──────────────────────────────────────────────────
    // Live polling (new DMs, the Inbox count) only runs while the app is on
    // screen — nothing ticks away in the background.
    @kotlin.concurrent.Volatile private var appInForeground = true
    fun setAppForeground(foreground: Boolean) {
        val wasBackground = !appInForeground
        appInForeground = foreground
        if (foreground && wasBackground && _bskyLoggedIn.value) {
            refreshInboxUnreadNow()
            refreshDmConvosQuick()
            // Hub list rows: reload any that went stale or came back empty
            // / failed while the app was away (each keeps its 5-minute rule).
            _hubLists.value.forEach { (uri, st) ->
                val empty = st.members.isEmpty() && st.posts.isEmpty()
                loadHubListIfNeeded(uri, force = empty || st.failed)
            }
        }
    }

    // ── Inbox (Bluesky notifications, minus DMs — those live in the DM list) ─
    /** One row in the Inbox: a notification, or several of the same kind
     *  about the same post grouped together ("Sam and 3 others liked…"). */
    data class InboxItem(
        val key: String,
        val reason: String,
        val authors: List<AuthorInfo>,
        /** The post it's about: yours (likes/reposts) or theirs (replies…). */
        val postUri: String?,
        /** Text to preview under the headline. */
        val snippet: String,
        val thumbUrl: String?,
        val indexedAt: String,
        val isRead: Boolean
    )
    private val _inboxOpen = MutableStateFlow(false)
    val inboxOpen: StateFlow<Boolean> = _inboxOpen
    private val _inboxItems = MutableStateFlow<List<InboxItem>>(emptyList())
    val inboxItems: StateFlow<List<InboxItem>> = combine(_inboxItems, com.mediaviewer.util.BlockedAccounts.version) { list, _ ->
        list.mapNotNull { item ->
            val visible = item.authors.filterNot { com.mediaviewer.util.BlockedAccounts.isHidden(it.did) }
            when {
                visible.isEmpty() && item.authors.isNotEmpty() -> null
                visible.size == item.authors.size -> item
                else -> item.copy(authors = visible)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _inboxLoading = MutableStateFlow(false)
    val inboxLoading: StateFlow<Boolean> = _inboxLoading
    private val _inboxUnreadCount = MutableStateFlow(0)
    val inboxUnreadCount: StateFlow<Int> = _inboxUnreadCount
    private var inboxCursor: String? = null
    private var inboxRaw: List<BskyNotification> = emptyList()
    private var inboxPollingJob: Job? = null

    /** The Hub's Inbox badge: Bluesky's own unread count, checked once a
     *  minute while the app is open — one small request, answered by the
     *  AppView directly (see BlueskyRepository.viaService), never a fetch of
     *  the notifications themselves. The list is only loaded when you open
     *  the Inbox. */
    fun startInboxPolling() {
        if (inboxPollingJob?.isActive == true || !_bskyLoggedIn.value) return
        inboxPollingJob = viewModelScope.launch(Dispatchers.IO) {
            var tick = 0
            while (_bskyLoggedIn.value) {
                if (appInForeground) {
                    if (!_inboxOpen.value) {
                        bskyRepo.getNotificationUnreadCount(bskyToken, _bskyDid.value).onSuccess { _inboxUnreadCount.value = it }
                    }
                    // The chat list's unread counts: right at app start, then
                    // every 2 minutes (new messages in between are counted
                    // live by the DM poll) — so the Hub's DMs badge is right
                    // even if the DM list is never opened.
                    if (tick % 2 == 0) refreshDmConvosQuick()
                    tick++
                }
                delay(60_000)
            }
        }
    }

    private fun refreshInboxUnreadNow() {
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.getNotificationUnreadCount(bskyToken, _bskyDid.value).onSuccess { if (!_inboxOpen.value) _inboxUnreadCount.value = it }
        }
    }

    fun openInbox() {
        if (!_bskyLoggedIn.value) return
        _inboxOpen.value = true
        // Whatever was prefetched/last loaded shows straight away; the first
        // page is refreshed underneath it.
        loadInbox(reset = true)
    }

    /** Warms the Inbox's first page in the background (without marking
     *  anything seen) so opening it is instant. */
    private fun prefetchInbox() {
        if (!_bskyLoggedIn.value || _inboxItems.value.isNotEmpty()) return
        loadInbox(reset = true, markSeen = false)
    }

    /** Subject posts ("liked your post: …") already looked up, by URI, so
     *  paging or refreshing never fetches the same post twice. */
    private val inboxPostCache = com.mediaviewer.platform.ConcurrentHashMap<String, BskyPost>()

    fun closeInbox() { _inboxOpen.value = false }

    fun loadMoreInbox() { if (inboxLoadJob?.isActive != true && inboxCursor != null) loadInbox(reset = false) }

    private var inboxLoadJob: Job? = null
    private fun loadInbox(reset: Boolean, markSeen: Boolean = true) {
        if (inboxLoadJob?.isActive == true) {
            // A refresh on open while the background prefetch is still going:
            // let it finish, then just mark everything seen.
            if (reset && markSeen) viewModelScope.launch(Dispatchers.IO) {
                inboxLoadJob?.join()
                _inboxUnreadCount.value = 0
                bskyRepo.markNotificationsSeen(bskyToken, _bskyDid.value)
            }
            return
        }
        _inboxLoading.value = true
        inboxLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Smaller first page (30) so the list appears quickly; more
                // pages load as you scroll.
                val page = bskyRepo.listNotifications(bskyToken, _bskyDid.value, if (reset) null else inboxCursor, limit = if (reset) 30 else 40).getOrElse {
                    if (_inboxItems.value.isEmpty() && markSeen) _errorMessage.value = "Couldn't load your inbox: ${it.message}"
                    return@launch
                }
                if (reset && markSeen) {
                    // Opening the Inbox is "seeing" it, same as Bluesky —
                    // sent off on its own, never holding the list up.
                    _inboxUnreadCount.value = 0
                    viewModelScope.launch(Dispatchers.IO) { bskyRepo.markNotificationsSeen(bskyToken, _bskyDid.value) }
                }
                inboxCursor = page.cursor
                inboxRaw = if (reset) page.notifications else inboxRaw + page.notifications
                // Show the rows right away with whatever previews are already
                // known, then fill the rest in once their posts arrive.
                _inboxItems.value = buildInboxItems(inboxRaw, fetchMissing = false)
                val before = inboxPostCache.size
                val rebuilt = buildInboxItems(inboxRaw, fetchMissing = true)
                if (inboxPostCache.size != before) _inboxItems.value = rebuilt
            } finally {
                _inboxLoading.value = false
            }
        }
    }

    private suspend fun buildInboxItems(raw: List<BskyNotification>, fetchMissing: Boolean = true): List<InboxItem> {
        fun author(a: BskyNotificationAuthor) = AuthorInfo(
            did = a.did, handle = a.handle, displayName = a.displayName?.takeIf { it.isNotBlank() } ?: a.handle, avatarUrl = a.avatar
        )
        fun recordText(n: BskyNotification) = runCatching { n.record?.asJsonObject?.get("text")?.asString }.getOrNull().orEmpty()
        val groupable = setOf("like", "repost", "follow", "like-via-repost", "repost-via-repost", "starterpack-joined")
        // Consecutive notifications of the same kind about the same post
        // become one row, like Bluesky's own list.
        val groups = mutableListOf<MutableList<BskyNotification>>()
        for (n in raw) {
            val last = groups.lastOrNull()?.firstOrNull()
            if (last != null && n.reason in groupable && last.reason == n.reason && last.reasonSubject == n.reasonSubject) {
                groups.last().add(n)
            } else groups.add(mutableListOf(n))
        }
        // The posts likes/reposts are about — one batched lookup.
        val subjectUris = groups.mapNotNull { g -> g.first().takeIf { it.reason in groupable && it.reason != "follow" }?.reasonSubject }
            .filter { it.contains("app.bsky.feed.post") }
        val missing = subjectUris.distinct().filterNot { inboxPostCache.containsKey(it) }
        if (fetchMissing && missing.isNotEmpty()) {
            inboxPostCache.putAll(bskyRepo.getPostsByUri(bskyToken, _bskyDid.value, missing))
        }
        val posts: Map<String, BskyPost> = inboxPostCache
        return groups.map { g ->
            val first = g.first()
            val subject = first.reasonSubject?.let { posts[it] }
            val isPostAbout = first.reason in setOf("reply", "mention", "quote", "subscribed-post")
            val thumb = subject?.embed?.let { e ->
                e.images?.firstOrNull()?.thumb ?: e.items?.firstOrNull()?.thumb ?: e.thumbnail
            }
            InboxItem(
                key = first.uri + "|" + first.reason,
                reason = first.reason,
                authors = g.map { author(it.author) }.distinctBy { it.did },
                postUri = if (isPostAbout) first.uri else first.reasonSubject?.takeIf { it.contains("app.bsky.feed.post") },
                snippet = if (isPostAbout) recordText(first) else subject?.record?.text.orEmpty(),
                thumbUrl = thumb,
                indexedAt = first.indexedAt,
                isRead = g.all { it.isRead }
            )
        }
    }

    /** Opens the post an Inbox row is about. */
    fun openInboxPost(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = bskyRepo.getPostItems(bskyToken, _bskyDid.value, listOf(uri))
            if (items.isEmpty()) { showToast("Post unavailable"); return@launch }
            _currentIndex.value = 0
            feedCursor = null
            activeFeedMode = ActiveFeedMode.FRIENDS
            activeFeedActorDid = null
            _mediaItems.value = filterHidden(items)
            _navDirection.value = 0
            _inboxOpen.value = false
            _screenState.value = ScreenState.FEED
        }
    }

    // ── Starting chats / groups ──────────────────────────────────────────────
    /** The "New chat" popup: null = closed. */
    data class NewChatState(
        val group: Boolean = false,
        val preselected: List<AuthorInfo> = emptyList()
    )
    /** A person in the New chat popup, and whether they can be messaged
     *  (their Bluesky "who can message me" setting, blocks). */
    data class ChatCandidate(val author: AuthorInfo, val canMessage: Boolean)

    private val _newChatState = MutableStateFlow<NewChatState?>(null)
    val newChatState: StateFlow<NewChatState?> = _newChatState
    private val _chatCandidates = MutableStateFlow<List<ChatCandidate>>(emptyList())
    val chatCandidates: StateFlow<List<ChatCandidate>> = combine(_chatCandidates, com.mediaviewer.util.BlockedAccounts.version) { list, _ ->
        list.filterNot { com.mediaviewer.util.BlockedAccounts.isHidden(it.author.did) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _chatSearching = MutableStateFlow(false)
    val chatSearching: StateFlow<Boolean> = _chatSearching
    private val _creatingChat = MutableStateFlow(false)
    val creatingChat: StateFlow<Boolean> = _creatingChat

    fun openNewChat(group: Boolean = false, preselected: List<AuthorInfo> = emptyList()) {
        if (!_bskyLoggedIn.value) return
        _newChatState.value = NewChatState(group, preselected)
        // Fresh follow list every time the popup opens (cheap: one page).
        chatSuggestionsJob?.cancel()
        chatSuggestionPool = emptyList(); chatSuggestionCursor = null; chatSuggestionsLoaded = false
        searchChatCandidates("")
    }
    fun setNewChatGroupMode(group: Boolean) {
        _newChatState.value = _newChatState.value?.copy(group = group)
        if (lastChatQuery.isEmpty()) publishChatSuggestions()
    }
    fun closeNewChat() { if (!_creatingChat.value) _newChatState.value = null }

    private var chatSearchJob: Job? = null
    private var lastChatQuery = ""
    // "Suggested": the accounts you follow, newest follow first, exactly like
    // Bluesky's own New chat sheet (InitiateChatFlow) — kept only when they
    // can actually be messaged (or added to a group, in group mode).
    private var chatSuggestionPool: List<BskyActorBasic> = emptyList()
    private var chatSuggestionCursor: String? = null
    private var chatSuggestionsLoaded = false
    private var chatSuggestionsJob: Job? = null

    private fun publishChatSuggestions() {
        val group = _newChatState.value?.group == true
        _chatCandidates.value = chatSuggestionPool
            .filter { if (group) it.canBeAddedToGroup else it.canBeMessaged }
            .distinctBy { it.did }
            .map { a ->
                ChatCandidate(
                    AuthorInfo(did = a.did, handle = a.handle, displayName = a.displayName?.takeIf { it.isNotBlank() } ?: a.handle, avatarUrl = a.avatar),
                    canMessage = true
                )
            }
    }

    /** Loads the next page(s) of follows for the Suggested list. Keeps going
     *  until a page adds at least a screenful of messageable people, since
     *  many follows may not accept messages. */
    fun loadMoreChatSuggestions() {
        if (chatSuggestionsJob?.isActive == true) return
        if (chatSuggestionsLoaded && chatSuggestionCursor == null) return
        chatSuggestionsJob = viewModelScope.launch(Dispatchers.IO) {
            if (_chatCandidates.value.isEmpty()) _chatSearching.value = true
            var added = 0
            var pages = 0
            do {
                val res = bskyRepo.getFollowsForChat(bskyToken, _bskyDid.value, chatSuggestionCursor)
                val page = res.getOrNull() ?: break
                chatSuggestionsLoaded = true
                chatSuggestionCursor = page.second
                chatSuggestionPool = chatSuggestionPool + page.first
                added += page.first.count { it.canBeMessaged }
                pages++
                if (lastChatQuery.isEmpty()) publishChatSuggestions()
            } while (chatSuggestionCursor != null && added < 20 && pages < 5)
            if (lastChatQuery.isEmpty()) _chatSearching.value = false
        }
    }

    /** Blank query: suggestions (the people you follow, newest first). */
    fun searchChatCandidates(query: String) {
        chatSearchJob?.cancel()
        val q = query.trim()
        lastChatQuery = q
        if (q.isEmpty()) {
            if (chatSuggestionsLoaded) {
                publishChatSuggestions()
                _chatSearching.value = chatSuggestionsJob?.isActive == true && _chatCandidates.value.isEmpty()
            } else {
                _chatCandidates.value = emptyList()
                loadMoreChatSuggestions()
            }
            return
        }
        chatSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250) // typing debounce
            _chatSearching.value = true
            bskyRepo.searchActorsTypeahead(bskyToken, _bskyDid.value, q).onSuccess { actors ->
                val me = _bskyDid.value
                val existing = _dmConversations.value.filter { it.convoId.isNotBlank() && !it.isGroup }.map { it.member.did }.toSet()
                val list = actors.filter { it.did != me }.map { a ->
                    val blocked = a.viewer?.blockedBy == true || a.viewer?.blocking != null
                    // Same rule Bluesky's own app uses: "everyone", or
                    // "people I follow" when they follow you; an existing
                    // chat can always be reopened.
                    val can = !blocked && (a.did in existing || when (a.allowIncomingChat) {
                        "all" -> true
                        "none" -> false
                        else -> a.viewer?.followedBy != null
                    })
                    ChatCandidate(
                        AuthorInfo(did = a.did, handle = a.handle, displayName = a.displayName?.takeIf { it.isNotBlank() } ?: a.handle, avatarUrl = a.avatar),
                        can
                    )
                }
                // Available first, then the ones that can't be messaged.
                _chatCandidates.value = list.filter { it.canMessage } + list.filterNot { it.canMessage }
            }
            _chatSearching.value = false
        }
    }

    /** Opens (or starts) a 1:1 chat from the New chat popup. */
    fun startChatWith(author: AuthorInfo) {
        _newChatState.value = null
        openDmWithProfile(author)
    }

    /** Creates a Bluesky group chat and opens it. */
    fun createGroupChat(name: String, members: List<AuthorInfo>) {
        if (_creatingChat.value || members.isEmpty()) return
        _creatingChat.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val groupName = name.trim().ifBlank { members.take(3).joinToString(", ") { it.displayName.substringBefore(' ') } }.take(50)
            bskyRepo.createGroup(bskyToken, _bskyDid.value, members.map { it.did }, groupName)
                .onSuccess { convo ->
                    val dm = bskyRepo.conversationFor(convo, _bskyDid.value).let {
                        // Everyone just added, for the avatars straight away.
                        if (it.groupMembers.size < members.size) it.copy(groupMembers = members, memberCount = maxOf(it.memberCount, members.size + 1)) else it
                    }
                    _dmConversations.value = listOf(dm) + _dmConversations.value.filter { it.convoId != dm.convoId }
                    _newChatState.value = null
                    _dmInboxOpen.value = true
                    openDmThread(dm)
                }
                .onFailure { e ->
                    val msg = e.message.orEmpty()
                    _errorMessage.value = when {
                        "NotFollowedBySender" in msg -> "Some of them only accept group invites from people they follow."
                        "UserForbidsGroups" in msg -> "Someone you picked doesn't allow group chats."
                        "NewAccountCannotCreateGroup" in msg -> "Your account is too new to create group chats yet."
                        "Blocked" in msg -> "You can't add someone you've blocked (or who blocked you)."
                        else -> "Couldn't create the group: ${msg.take(120)}"
                    }
                }
            _creatingChat.value = false
        }
    }

    // ── Compose Post (upload flow — Hub "+" -> "Post") ──────────────────────
    // See ComposePostScreen.kt for the full composer UI. Submitting is wired
    // through here rather than straight from the screen so the screen itself
    // stays a plain, stateless-ish presentation layer — same pattern as every
    // other overlay in this app (DM inbox, Search, Reply).
    private val _composePostOpen = MutableStateFlow(false)
    val composePostOpen: StateFlow<Boolean> = _composePostOpen

    private val _composePostSubmitting = MutableStateFlow(false)
    val composePostSubmitting: StateFlow<Boolean> = _composePostSubmitting

    // Item 10: the title page's "Review" bar opens the composer straight
    // into Review mode/status for one specific title — this is that
    // title's own carried state, read by ComposePostScreen (see
    // reviewTarget param) so it knows to force REVIEW mode and grey out
    // the other mode buttons the moment it opens.
    private val _reviewComposeTarget = MutableStateFlow<TitleSearchResult?>(null)
    val reviewComposeTarget: StateFlow<TitleSearchResult?> = _reviewComposeTarget

    fun openComposePost() { _blogEditDraft.value = null; _composePostOpen.value = true }

    // ── Item 12: blog editing ───────────────────────────────────────────
    /** Non-null while the composer is editing an existing blog (opened from
     *  the reader's pen button): it opens straight into Blog mode, filled
     *  in, and its Post button reads "Save". Second = the blog's labels. */
    private val _blogEditDraft = MutableStateFlow<Pair<BlogDraft, List<String>>?>(null)
    val blogEditDraft: StateFlow<Pair<BlogDraft, List<String>>?> = _blogEditDraft

    fun openBlogEditor(blog: LeafletBlog) {
        viewModelScope.launch(Dispatchers.IO) {
            var result = bskyRepo.loadBlogForEditing(bskyToken, _bskyDid.value, blog.uri)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.loadBlogForEditing(bskyToken, _bskyDid.value, blog.uri)
            }
            result.onSuccess { draft ->
                _blogEditDraft.value = draft
                _profileOverlay.value = _profileOverlay.value?.copy(openBlog = null)
                _composePostOpen.value = true
            }.onFailure { _errorMessage.value = it.message ?: "Couldn't open that blog for editing" }
        }
    }

    /** Reader's trash button (after its "are you sure?"). */
    fun deleteBlog(blog: LeafletBlog) {
        viewModelScope.launch(Dispatchers.IO) {
            var result = bskyRepo.deleteBlog(bskyToken, _bskyDid.value, blog)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.deleteBlog(bskyToken, _bskyDid.value, blog)
            }
            result.onSuccess {
                showToast("Blog deleted")
                val cur = _profileOverlay.value
                if (cur != null) {
                    val tab = cur.tabStates[ProfileTab.BLOGS]
                    _profileOverlay.value = cur.copy(
                        openBlog = null,
                        tabStates = if (tab != null) cur.tabStates + (ProfileTab.BLOGS to tab.copy(blogs = tab.blogs.filterNot { it.uri == blog.uri })) else cur.tabStates
                    )
                }
                _friendsBlogs.value = _friendsBlogs.value.filterNot { it.blog.uri == blog.uri }
            }.onFailure { _errorMessage.value = it.message ?: "Couldn't delete the blog" }
        }
    }
    fun openReviewCompose(target: TitleSearchResult) {
        _reviewComposeTarget.value = target
        _composePostOpen.value = true
    }
    // Item 8: camera-notch button's "Camera" action lands here once the
    // system camera app hands back the file it just captured — opens the
    // composer with that file already attached, same idea as
    // openReviewCompose seeding reviewComposeTarget above.
    private val _initialComposeImageUri = MutableStateFlow<com.mediaviewer.platform.PlatformUri?>(null)
    val initialComposeImageUri: StateFlow<com.mediaviewer.platform.PlatformUri?> = _initialComposeImageUri
    private val _initialComposeVideoUri = MutableStateFlow<com.mediaviewer.platform.PlatformUri?>(null)
    val initialComposeVideoUri: StateFlow<com.mediaviewer.platform.PlatformUri?> = _initialComposeVideoUri
    fun openComposePostWithCapturedMedia(imageUri: com.mediaviewer.platform.PlatformUri?, videoUri: com.mediaviewer.platform.PlatformUri?) {
        _initialComposeImageUri.value = imageUri
        _initialComposeVideoUri.value = videoUri
        _composePostOpen.value = true
    }

    // Item 8: the VRM/VTuber mode screen — see VrmModeScreen.kt. Plain
    // open/close, same pattern as every other full-screen overlay's own
    // Boolean flow in this ViewModel.
    private val _vrmModeOpen = MutableStateFlow(false)
    val vrmModeOpen: StateFlow<Boolean> = _vrmModeOpen
    /** Entering VRM or Camera mode closes every other page first (profiles,
     *  Search, DMs, the Inbox, popups…) and leaves the Hub underneath, so
     *  closing it comes back to a clean Hub. [keepComposer]: the Camera
     *  opened from the posting page returns to that draft. */
    private fun closeAllPagesForCapture(keepComposer: Boolean) {
        _profileOverlay.value = null
        _searchOpen.value = false
        _searchHiddenBehindPost.value = false
        _dmThread.value = null
        _dmInboxOpen.value = false
        _inboxOpen.value = false
        _playingLive.value = null
        _taggingOverlayOpen.value = false
        _blockedAccountsOpen.value = false
        _listPickerTargetDid.value = null
        _sendPopupTarget.value = null
        _quoteRepostTarget.value = null
        _replyToConvo.value = null
        _newChatState.value = null
        _capturePreview.value = null
        if (!keepComposer && _composePostOpen.value) resetComposeState()
        _screenState.value = ScreenState.SETTINGS
    }

    fun openVrmMode() {
        closeAllPagesForCapture(keepComposer = false)
        // Opened from the posting page's notch bubble: VRM mode replaces the
        // posting page rather than stacking on top of it (a capture taken in
        // VRM mode comes back through its own review page into a fresh
        // composer anyway). A post that's mid-upload keeps uploading.
        if (_composePostOpen.value) resetComposeState()
        _vrmModeOpen.value = true
    }
    fun closeVrmMode() { _vrmModeOpen.value = false }

    // The notch bubble's "Camera" page (CameraModeScreen): VRM mode's page
    // with the real camera. Opened over the posting page it stacks on top
    // instead of replacing it, and a capture goes straight into that draft.
    private val _cameraModeOpen = MutableStateFlow(false)
    val cameraModeOpen: StateFlow<Boolean> = _cameraModeOpen
    private var cameraOpenedFromComposer = false
    fun openCameraMode() {
        cameraOpenedFromComposer = _composePostOpen.value
        closeAllPagesForCapture(keepComposer = cameraOpenedFromComposer)
        _cameraModeOpen.value = true
    }
    fun closeCameraMode() { _cameraModeOpen.value = false }
    /** A photo/video taken on the Camera page. */
    fun onCameraCapture(uri: com.mediaviewer.platform.PlatformUri, isVideo: Boolean) {
        if (cameraOpenedFromComposer && _composePostOpen.value) {
            _cameraModeOpen.value = false
            openComposePostWithCapturedMedia(if (isVideo) null else uri, if (isVideo) uri else null)
        } else {
            _cameraModeOpen.value = false
            _capturePreview.value = CapturePreview(uri, isVideo, fromCamera = true)
        }
    }

    /** A photo/video just taken in VRM mode, shown on its own review page
     *  (CapturePreviewScreen) with VRM mode closed — see [openCapturePreview]. */
    data class CapturePreview(val uri: com.mediaviewer.platform.PlatformUri, val isVideo: Boolean, val fromCamera: Boolean = false)
    private val _capturePreview = MutableStateFlow<CapturePreview?>(null)
    val capturePreview: StateFlow<CapturePreview?> = _capturePreview
    /** Closes VRM mode (camera, trackers and renderer all shut down) and
     *  opens the review page for the capture. */
    fun openCapturePreview(uri: com.mediaviewer.platform.PlatformUri, isVideo: Boolean) {
        _vrmModeOpen.value = false
        _capturePreview.value = CapturePreview(uri, isVideo)
    }
    /** Review page's X: back into VRM mode. */
    fun returnToVrmFromPreview() {
        val fromCamera = _capturePreview.value?.fromCamera == true
        _capturePreview.value = null
        if (fromCamera) { cameraOpenedFromComposer = false; _cameraModeOpen.value = true } else _vrmModeOpen.value = true
    }
    /** Review page's "Create Post": into the composer with the (cropped) capture. */
    fun createPostFromPreview(uri: com.mediaviewer.platform.PlatformUri, isVideo: Boolean) {
        _capturePreview.value = null
        openComposePostWithCapturedMedia(if (isVideo) null else uri, if (isVideo) uri else null)
    }

    fun closeComposePost() {
        if (_composePostSubmitting.value) return
        resetComposeState()
    }

    private fun resetComposeState() {
        _composePostOpen.value = false
        _reviewComposeTarget.value = null
        _initialComposeImageUri.value = null
        _initialComposeVideoUri.value = null
        _blogEditDraft.value = null
    }

    /** Routes a finished [com.mediaviewer.ui.ComposePostDraft] to the right
     *  BlueskyRepository call for its mode, retrying once on an expired-
     *  session error the same way every other authenticated call in this
     *  ViewModel does (see isAuthError/refreshBskyTokenIfPossible). On
     *  success the composer closes and whatever was just posted opens. */
    fun submitComposePost(draft: com.mediaviewer.ui.ComposePostDraft) {
        if (_composePostSubmitting.value) return
        // Item 8: haptic tap on posting (thread/single/review/blog/textshot
        // all funnel through this one submit function).
        tapHaptic()
        _composePostSubmitting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var result = runCatchingComposePost(draft)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = runCatchingComposePost(draft)
            }
            val error = result.exceptionOrNull()
            if (error != null) {
                // The composer sits above the feed's error snackbar, so a
                // failure used to be completely silent — the Post button just
                // came back. A toast shows over everything.
                val message = error.message?.takeIf { it.isNotBlank() } ?: "Couldn't post that"
                _errorMessage.value = message
                showToast(message)
                _composePostSubmitting.value = false
                return@launch
            }
            // Work out what to open while the Post button keeps spinning, so
            // the composer closes straight onto the new post/blog/review.
            val open = runCatching { resolvePublished(draft.mode, result.getOrNull()) }.getOrNull()
            withContext(Dispatchers.Main) {
                _composePostSubmitting.value = false
                val composerStillOpen = _composePostOpen.value
                resetComposeState()
                // Closed meanwhile (VRM mode took over the screen): don't
                // yank the person somewhere else, just confirm it went out.
                if (!composerStillOpen || _vrmModeOpen.value) showToast("Posted")
                else if (open != null) open() else showToast("Posted")
            }
        }
    }

    /** The signed-in account as an [AuthorInfo]. */
    private fun selfAuthorInfo(): AuthorInfo =
        _selfProfile.value?.author ?: AuthorInfo(did = _bskyDid.value, handle = bskyHandle, displayName = bskyHandle, avatarUrl = null)

    /**
     * After posting: fetches whatever was just published and returns the
     * action that opens it (run on the main thread) — the post itself in the
     * pager (reached through your own profile, so pinching in lands on it),
     * or your profile with the new blog/review open. IO thread.
     */
    private suspend fun resolvePublished(mode: com.mediaviewer.ui.ComposeMode, published: Any?): (() -> Unit)? {
        val did = _bskyDid.value
        return when (mode) {
            com.mediaviewer.ui.ComposeMode.BLOG -> {
                val uri = published as? String ?: return { showOwnProfileWith(ProfileTab.BLOGS) }
                val blog = bskyRepo.getBlogByUri(bskyToken, did, uri)
                return { showOwnProfileWith(ProfileTab.BLOGS, blog = blog) }
            }
            com.mediaviewer.ui.ComposeMode.REVIEW -> {
                val uri = published as? String ?: return { showOwnProfileWith(ProfileTab.REVIEWS) }
                val review = bskyRepo.getReviewByUri(bskyToken, did, uri)
                return { showOwnProfileWith(ProfileTab.REVIEWS, review = review) }
            }
            else -> {
                val uris = when (published) {
                    is BskyRef -> listOf(published.uri)
                    is List<*> -> published.filterIsInstance<BskyRef>().map { it.uri }
                    else -> emptyList()
                }
                if (uris.isEmpty()) return { showOwnProfileWith(ProfileTab.POSTS) }
                // The AppView indexes a new post within a second or two;
                // retry briefly until it's there.
                var items: List<MediaItem> = emptyList()
                for (attempt in 0 until 6) {
                    if (attempt > 0) delay(700)
                    items = bskyRepo.getPostsByUris(bskyToken, uris).getOrNull().orEmpty()
                    if (items.isNotEmpty()) break
                }
                val order = uris.withIndex().associate { (i, u) -> u to i }
                val sorted = items.sortedBy { order[it.postUri] ?: Int.MAX_VALUE }
                if (sorted.isEmpty()) return { showOwnProfileWith(ProfileTab.POSTS) }
                return { openPublishedPosts(sorted) }
            }
        }
    }

    /** Opens your own profile on [tab] with a just-published blog/review
     *  open on top of it. Reuses your profile if it's already the one
     *  showing (refreshing it in place), otherwise opens it fresh. */
    private fun showOwnProfileWith(tab: ProfileTab, blog: LeafletBlog? = null, review: PopfeedReview? = null) {
        if (!_bskyLoggedIn.value) return
        val did = _bskyDid.value
        // Overlays that would sit on top of the profile.
        _dmInboxOpen.value = false
        _dmThread.value = null
        fun seed(o: ProfileOverlayState): ProfileOverlayState {
            // The tab probes haven't run yet (fresh open) or may be stale
            // (already open): make sure the tab exists with the new item in
            // it right away; the probe then replaces it with the full list.
            val content = when (tab) {
                ProfileTab.BLOGS -> blog?.let { b ->
                    val existing = o.tabStates[ProfileTab.BLOGS]?.blogs.orEmpty().filterNot { it.uri == b.uri }
                    ProfileTabState(blogs = listOf(b) + existing, loaded = true)
                }
                ProfileTab.REVIEWS -> review?.let { r ->
                    val existing = o.tabStates[ProfileTab.REVIEWS]?.reviews.orEmpty().filterNot { it.uri == r.uri }
                    ProfileTabState(reviews = listOf(r) + existing, loaded = true)
                }
                else -> null
            }
            val hasTab = tab in o.availableTabs || content != null
            return o.copy(
                selectedTab = if (hasTab) tab else o.selectedTab,
                availableTabs = if (content != null) o.availableTabs + tab else o.availableTabs,
                tabStates = if (content != null) o.tabStates + (tab to content) else o.tabStates,
                openBlog = blog ?: o.openBlog,
                openReview = review ?: o.openReview
            )
        }
        val cur = _profileOverlay.value
        if (cur != null && !cur.hidden && cur.author.did == did) {
            _profileOverlay.value = seed(cur.copy(openBlog = null, openReview = null, openTitle = null, openTitlePreselectedReview = null))
            // Give the AppView/PDS listing a moment, then reload in place.
            viewModelScope.launch { delay(1200); refreshProfile() }
            return
        }
        openProfile(selfAuthorInfo(), initialTab = if (tab == ProfileTab.BLOGS || tab == ProfileTab.REVIEWS) ProfileTab.POSTS else tab)
        _profileOverlay.value?.let { _profileOverlay.value = seed(it) }
    }

    /** Opens just-posted posts (a thread's posts in order) full-screen in
     *  the pager, with your own profile hidden behind them — pinch in to
     *  see it, exactly like opening a post from your profile's grid. */
    private fun openPublishedPosts(items: List<MediaItem>) {
        if (!_bskyLoggedIn.value) return
        _dmInboxOpen.value = false
        _dmThread.value = null
        // Search draws above the pager; close it so the post is visible.
        _searchOpen.value = false
        _searchHiddenBehindPost.value = false
        openProfile(selfAuthorInfo(), initialTab = ProfileTab.POSTS)
        openPostFromProfileTab(items, 0)
    }

    /** Hub "Liked Posts": your own profile, on its Likes tab. */
    fun openOwnLikes() {
        if (!_bskyLoggedIn.value) return
        openProfile(selfAuthorInfo(), initialTab = ProfileTab.LIKES)
    }

    private suspend fun runCatchingComposePost(draft: com.mediaviewer.ui.ComposePostDraft): Result<Any> {
        val context = platform.context
        val did = _bskyDid.value
        return when (draft.mode) {
            com.mediaviewer.ui.ComposeMode.SINGLE -> {
                val post = draft.posts.firstOrNull()
                if (post == null) Result.failure(IllegalStateException("Empty post"))
                else runCatching {
                    val images = post.images.map { uri -> bskyRepo.uploadImageBlob(bskyToken, context, uri).getOrElse { throw it } }
                    bskyRepo.createPost(bskyToken, did, post.text, images, selfLabels = draft.selfLabels).getOrElse { throw it }
                }
            }
            com.mediaviewer.ui.ComposeMode.THREAD -> runCatching {
                val posts = draft.posts.map { com.mediaviewer.repository.BlueskyRepository.ThreadPostToSend(it.text, it.images) }
                bskyRepo.createThread(bskyToken, did, context, posts, draft.selfLabels).getOrElse { throw it }
            }
            com.mediaviewer.ui.ComposeMode.TEXTSHOT -> runCatching {
                // Custom emoji are drawn into the image; the alt text never
                // carries an emoji's name (see AppPlatform.renderTextshot).
                val shot = platform.renderTextshot(draft.textshotText)
                bskyRepo.createTextshotPost(bskyToken, did, shot.bitmap, shot.altText, draft.selfLabels, shot.hasEmoji, postText = draft.textshotPostText).getOrElse { throw it }
            }
            com.mediaviewer.ui.ComposeMode.VIDEO -> {
                val uri = draft.videoUri
                if (uri == null) Result.failure(IllegalStateException("No video attached"))
                else runCatching {
                    bskyRepo.createVideoPost(bskyToken, did, context, uri, draft.videoThumbnailUri, draft.videoTitle, draft.videoDescription, draft.selfLabels).getOrElse { throw it }
                }
            }
            // Item 10: posts a real social.popfeed.feed.review record (not
            // an app.bsky.feed.post) for the title the composer was opened
            // from, then — per spec ("after posting the review it should
            // remove it from the backlog/watchlist") — deletes the matching
            // social.popfeed.feed.listItem record so the title drops off
            // the Backlog/Watchlist tab now that it's been reviewed. The
            // listItem delete is best-effort: a title reviewed straight
            // from a friend's Mutual Review card (openMutualReview) was
            // never on *this* account's own backlog in the first place, so
            // there's nothing there to remove — that's expected, not an
            // error, and shouldn't fail the review post itself.
            com.mediaviewer.ui.ComposeMode.BLOG -> {
                val blog = draft.blog
                if (blog == null) Result.failure(IllegalStateException("Empty blog"))
                else runCatching {
                    val selfName = _selfProfile.value?.author?.displayName.orEmpty()
                    // Opening it afterwards (see submitComposePost) also
                    // refreshes your profile's Blogs tab if it's open.
                    bskyRepo.publishBlog(bskyToken, did, bskyHandle, selfName, context, blog, draft.selfLabels).getOrElse { throw it }
                }
            }
            com.mediaviewer.ui.ComposeMode.REVIEW -> {
                val target = draft.reviewTarget
                if (target == null) Result.failure(IllegalStateException("No title to review"))
                else runCatching {
                    val uri = bskyRepo.postPopfeedReview(bskyToken, did, target, draft.reviewRating, draft.posts.firstOrNull()?.text.orEmpty(), draft.reviewContainsSpoilers)
                        .getOrElse { throw it }
                    // Only a title opened from an actual Backlog/Watchlist
                    // card (see MainViewModel.openProfileTitle) has an
                    // `id` that's really a social.popfeed.feed.listItem
                    // record's own AT-URI — one opened from a review
                    // instead (openMutualReview/openProfileReview) has a
                    // synthetic "imdb:..." or review-record id, which
                    // deliberately doesn't match this and is skipped.
                    if (target.id.startsWith("at://") && target.id.contains("social.popfeed.feed.listItem")) {
                        runCatching { bskyRepo.removeBacklogListItem(bskyToken, did, target.id) }
                    }
                    uri
                }
            }
        }
    }

    // ── Search (item 7) ──────────────────────────────────────────────────────

    // Reordered/renamed (per feedback): People now first (was Posts), and
    // the old "Lists" slot — which never actually had a working search
    // behind it, see the FEEDS case in runSearch below — is now Feeds,
    // backed by a real search. Enum name kept as ACCOUNTS/FEEDS rather than
    // renaming the Kotlin identifiers too, to keep this diff scoped to
    // what's user-visible; .label() below is what actually says "People".
    enum class SearchFilter { ACCOUNTS, POSTS, LIKED_TAGS, FEEDS, STARTER_PACKS, E621 }

    data class SearchState(
        val query: String = "",
        val filter: SearchFilter = SearchFilter.ACCOUNTS,
        val posts: List<MediaItem> = emptyList(),
        val accounts: List<SearchAccountResult> = emptyList(),
        val starterPacks: List<SearchStarterPackResult> = emptyList(),
        val feeds: List<SearchFeedResult> = emptyList(),
        val loading: Boolean = false,
        val hasSearched: Boolean = false,
        /** Posts tab: where the next page of results starts (null = no more). */
        val postsCursor: String? = null,
        val loadingMorePosts: Boolean = false
    )

    private val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen

    // Item 4 (Search page): mirrors ProfileOverlayState.hidden — true once a
    // post opened from search hides the search overlay (rather than fully
    // closing it via closeSearch(), which wipes its results/scroll/filter)
    // so pinchInFromPost() below can restore it exactly as left instead of
    // falling through to the generic grid, same as a hidden profile does.
    private val _searchHiddenBehindPost = MutableStateFlow(false)

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = combine(_searchState, com.mediaviewer.util.BlockedAccounts.version) { st, _ ->
        st.copy(
            posts = st.posts.filterNot { com.mediaviewer.util.BlockedAccounts.isHidden(it.author.did) },
            accounts = st.accounts.filterNot { com.mediaviewer.util.BlockedAccounts.isHidden(it.author.did) }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchState())

    private var searchJob: kotlinx.coroutines.Job? = null

    fun openSearch() { tapHaptic(); _searchOpen.value = true }
    fun closeSearch() {
        _searchOpen.value = false
        _searchHiddenBehindPost.value = false
        searchJob?.cancel()
        _searchState.value = SearchState()
    }

    fun setSearchFilter(filter: SearchFilter) {
        _searchState.value = _searchState.value.copy(filter = filter)
        if (filter == SearchFilter.LIKED_TAGS) {
            // Item 2: switching to the Liked tab always (re)loads its
            // current view — either the default "everything tagged, most
            // recent first" browse (blank query) or a re-run of whatever
            // was already typed — rather than the live-search-on-keystroke
            // behavior the other tabs use.
            _tagSuggestions.value = emptyList()
            viewModelScope.launch(Dispatchers.IO) { performLikedTagSearch(_searchState.value.query) }
        } else if (_searchState.value.query.isNotBlank()) runSearch(_searchState.value.query)
    }

    fun runSearch(query: String) {
        searchJob?.cancel()
        _searchState.value = _searchState.value.copy(query = query)
        if (query.isBlank()) {
            _searchState.value = _searchState.value.copy(
                posts = emptyList(), accounts = emptyList(), starterPacks = emptyList(), feeds = emptyList(), loading = false, hasSearched = false
            )
            return
        }
        val filter = _searchState.value.filter
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = _searchState.value.copy(loading = true)
            when (filter) {
                SearchFilter.POSTS -> {
                    bskyRepo.searchPosts(bskyToken, query).onSuccess { (posts, cursor) ->
                        _searchState.value = _searchState.value.copy(
                            posts = posts, loading = false, hasSearched = true,
                            postsCursor = cursor?.takeIf { it.isNotBlank() }, loadingMorePosts = false
                        )
                    }.onFailure { _searchState.value = _searchState.value.copy(loading = false, hasSearched = true) }
                }
                // Item 2: kept only as a safety net — typing on the Liked
                // tab no longer routes through runSearch at all (see
                // updateLikedQueryText/submitLikedSearch), but if anything
                // else ever calls runSearch while that filter is active,
                // this keeps it working rather than silently doing nothing.
                SearchFilter.LIKED_TAGS -> performLikedTagSearch(query)
                // Item 14: same reasoning as LIKED_TAGS above — typing on
                // the e621 filter routes through updateLikedQueryText/
                // submitE621SearchFromOverlay instead, never through here.
                SearchFilter.E621 -> {}
                SearchFilter.ACCOUNTS -> {
                    bskyRepo.searchActors(bskyToken, query).onSuccess { (accounts, _) ->
                        _searchState.value = _searchState.value.copy(accounts = accounts, loading = false, hasSearched = true)
                    }.onFailure { _searchState.value = _searchState.value.copy(loading = false, hasSearched = true) }
                }
                SearchFilter.STARTER_PACKS -> {
                    bskyRepo.searchStarterPacks(bskyToken, query).onSuccess { (packs, _) ->
                        _searchState.value = _searchState.value.copy(starterPacks = packs, loading = false, hasSearched = true)
                    }.onFailure { _searchState.value = _searchState.value.copy(loading = false, hasSearched = true) }
                }
                // Feature (this session): replaces the old "Lists" filter,
                // which had no working search behind it at all (Bluesky's
                // public API has no list-search endpoint) — Feeds does,
                // via app.bsky.unspecced.getPopularFeedGenerators's query
                // param, so this tab now actually returns results instead
                // of always showing an explanatory empty state.
                SearchFilter.FEEDS -> {
                    bskyRepo.searchFeeds(bskyToken, query).onSuccess { feeds ->
                        _searchState.value = _searchState.value.copy(feeds = feeds, loading = false, hasSearched = true)
                    }.onFailure { _searchState.value = _searchState.value.copy(loading = false, hasSearched = true) }
                }
            }
        }
    }

    /** Search page's Posts tab scrolled near its end: the next page, like
     *  every other feed in the app. */
    fun loadMoreSearchPosts() {
        val st = _searchState.value
        val cursor = st.postsCursor ?: return
        if (st.loading || st.loadingMorePosts || st.query.isBlank() || st.filter != SearchFilter.POSTS) return
        _searchState.value = st.copy(loadingMorePosts = true)
        val query = st.query
        viewModelScope.launch(Dispatchers.IO) {
            var result = bskyRepo.searchPosts(bskyToken, query, cursor)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message) && refreshBskyTokenIfPossible()) {
                result = bskyRepo.searchPosts(bskyToken, query, cursor)
            }
            val cur = _searchState.value
            if (cur.query != query) return@launch
            result.onSuccess { (posts, next) ->
                val known = cur.posts.mapTo(HashSet()) { it.id }
                val fresh = posts.filter { it.id !in known }
                _searchState.value = cur.copy(
                    posts = cur.posts + fresh,
                    postsCursor = next?.takeIf { it.isNotBlank() && it != cursor },
                    loadingMorePosts = false
                )
            }.onFailure { _searchState.value = cur.copy(loadingMorePosts = false) }
        }
    }

    /** Search page's Feeds tab: "Add" on a feed result — writes it into the
     *  user's saved feeds (see BlueskyRepository.addSavedFeed) and refreshes
     *  the Hub's own feed-picker list so it shows up there immediately
     *  without needing to reopen the app. */
    fun addSavedFeedFromSearch(feed: SearchFeedResult) {
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.addSavedFeed(bskyToken, feed.uri).onSuccess {
                loadAvailableFeeds()
            }
        }
    }

    // ── Hub: reorder / remove saved feeds ─────────────────────────────────
    // Optimistic: the Hub's row changes the moment the chip is dropped, and
    // the new order is written to the user's Bluesky preferences in the
    // background (so it matches in every AT Protocol app). Writes run one
    // at a time, newest state wins; a failure reloads the real list.
    private val feedPrefsMutex = Mutex()

    fun moveFeed(fromIndex: Int, toIndex: Int) {
        val list = _availableFeeds.value.toMutableList()
        if (fromIndex !in list.indices) return
        val to = toIndex.coerceIn(0, list.lastIndex)
        if (to == fromIndex) return
        list.add(to, list.removeAt(fromIndex))
        _availableFeeds.value = list
        persistFeedOrder(emptySet())
    }

    fun removeFeed(uri: String) {
        val list = _availableFeeds.value
        if (list.none { it.uri == uri }) return
        _availableFeeds.value = list.filterNot { it.uri == uri }
        persistFeedOrder(setOf(uri))
    }

    private fun persistFeedOrder(removed: Set<String>) {
        if (!_bskyLoggedIn.value) return
        viewModelScope.launch(Dispatchers.IO) {
            feedPrefsMutex.withLock {
                val order = _availableFeeds.value.map { it.uri }
                var result = bskyRepo.saveFeedOrder(bskyToken, order, removed)
                if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                    if (refreshBskyTokenIfPossible()) result = bskyRepo.saveFeedOrder(bskyToken, order, removed)
                }
                result.onFailure {
                    _errorMessage.value = "Couldn't update your feeds: ${it.message}"
                    loadAvailableFeeds()
                }
            }
        }
    }

    /** Opens a post found via search in its own standalone pager, the same
     *  way tapping into any other feed does — navDirection 0 since there's
     *  no meaningful slide direction coming from a flat search result list.
     *
     *  Item 4: now mirrors openPostFromProfileTab exactly — hides the search
     *  overlay instead of closing it (closeSearch() wipes _searchState
     *  entirely: query, results, active filter tab), so pinching back in
     *  from the post (pinchInFromPost()) restores the exact same search
     *  screen the person left, rather than reopening search from scratch. */
    fun openPostFromSearch(index: Int) {
        val results = _searchState.value.posts
        if (index !in results.indices) return
        _mediaItems.value = results
        _currentIndex.value = index
        _navDirection.value = 0
        _authorFeedState.value = null
        activeFeedMode = ActiveFeedMode.NORMAL
        activeFeedActorDid = null
        _selectedFeedUri.value = null
        _searchOpen.value = false
        _searchHiddenBehindPost.value = true
        _screenState.value = ScreenState.FEED
    }

    fun openDmThread(convo: DmConversation) {
        if (convo.convoId.isBlank()) return // no history yet — nothing to show
        markConvoRead(convo.convoId)
        // A chat opened before shows straight away from memory while its
        // newest messages are fetched underneath.
        val cached = dmThreadCache[convo.convoId]
        _dmThread.value = cached?.copy(convo = convo, loading = false, loadingMore = false) ?: DmThreadState(
            convo = convo, loading = true,
            members = if (convo.isGroup) convo.groupMembers.associateBy { it.did } else emptyMap()
        )
        if (convo.isGroup) viewModelScope.launch(Dispatchers.IO) {
            // Group chats: the whole member list, for every sender's
            // avatar/name/color (the convo itself only lists a few).
            bskyRepo.getConvoMembers(bskyToken, _bskyDid.value, convo.convoId).onSuccess { all ->
                val cur = _dmThread.value
                if (cur != null && cur.convo.convoId == convo.convoId) {
                    _dmThread.value = cur.copy(members = cur.members + all.associateBy { it.did })
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Just the newest page first (small, so it appears quickly);
            // older messages load as the thread is scrolled up.
            bskyRepo.getConvoMessages(bskyToken, _bskyDid.value, convo.convoId, limit = 30)
                .onSuccess { (messages, cursor) ->
                    val cur = _dmThread.value
                    if (cur == null || cur.convo.convoId != convo.convoId) return@onSuccess
                    val next = cur.copy(
                        messages = messages, embeddedPosts = buildEmbeddedPosts(messages),
                        loading = false, cursor = cursor,
                        members = if (convo.isGroup) groupSenders(messages) + cur.members else cur.members
                    )
                    _dmThread.value = next
                    dmThreadCache[convo.convoId] = next
                }
                .onFailure {
                    _dmThread.value = _dmThread.value?.copy(loading = false)
                    _errorMessage.value = it.message
                }
        }
    }

    /** Feature request #6: the profile page's "DM" interaction-bar button —
     *  looks for an existing conversation with this author first (so a
     *  thread with history opens showing that history), and falls back to
     *  getOrCreateConvo (same call [sendToSelectedRecipients] uses) for a
     *  brand new conversation with no messages yet. Either way, ends by
     *  opening the normal DM thread overlay on it. */
    fun openDmWithProfile(author: AuthorInfo) {
        if (!_bskyLoggedIn.value) return
        // Bug fix: this used to populate _dmThread without ever opening
        // DmInboxOverlay (gated separately by _dmInboxOpen — see
        // openDmInbox()) — MainActivity only renders that overlay when
        // dmInboxOpen is true, so the profile page's DM button silently
        // built a thread nobody could see. DmInboxOverlay itself already
        // knows how to show a single open thread with no conversation list
        // underneath it (that's what onSelectConvo &co. drive it into
        // normally), so opening straight into that same state works here
        // too.
        _dmInboxOpen.value = true
        // (Only a real chat counts — a mutual you've never messaged is in
        // the list too, with no chat yet, and used to make this do nothing.)
        val existing = _dmConversations.value.firstOrNull { it.member.did == author.did && !it.isGroup && it.convoId.isNotBlank() }
        if (existing != null) { openDmThread(existing); return }
        _dmThread.value = DmThreadState(convo = DmConversation(convoId = "", member = author, lastSentByUsAt = "", lastActivityAt = ""), loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.getOrCreateConvo(bskyToken, _bskyDid.value, listOf(author.did))
                .onSuccess { convoId ->
                    val convo = DmConversation(convoId = convoId, member = author, lastSentByUsAt = "", lastActivityAt = "")
                    _dmThread.value = DmThreadState(convo = convo, loading = true)
                    // Newly started chats show up in the DM list right away.
                    _dmConversations.value = listOf(convo) + _dmConversations.value.filter {
                        it.convoId != convoId && !(it.convoId.isBlank() && it.member.did == author.did)
                    }
                    bskyRepo.getConvoMessages(bskyToken, _bskyDid.value, convoId)
                        .onSuccess { (messages, cursor) ->
                            _dmThread.value = _dmThread.value?.copy(
                                messages = messages, embeddedPosts = buildEmbeddedPosts(messages),
                                loading = false, cursor = cursor
                            )
                        }
                        .onFailure { _dmThread.value = _dmThread.value?.copy(loading = false) }
                }
                .onFailure {
                    _dmThread.value = null
                    _errorMessage.value = "Couldn't open DM: ${it.message}"
                }
        }
    }

    // Item 12: parses each message's raw embed once when a batch of messages
    // loads, rather than re-parsing JSON on every recomposition.
    private fun buildEmbeddedPosts(messages: List<BskyMessageView>): Map<String, DmEmbeddedPost> =
        messages.mapNotNull { m -> bskyRepo.parseMessageEmbed(m.embed)?.let { m.id to it } }.toMap()

    fun closeDmThread() {
        _dmThread.value?.let { t -> if (t.convo.convoId.isNotBlank() && !t.loading) dmThreadCache[t.convo.convoId] = t }
        _dmThread.value = null
    }

    /** Each chat as last shown, so reopening it is instant. */
    private val dmThreadCache = com.mediaviewer.platform.ConcurrentHashMap<String, DmThreadState>()

    /** Group chats: the people who sent [messages], as far as known. */
    private fun groupSenders(messages: List<BskyMessageView>): Map<String, AuthorInfo> =
        messages.mapNotNull { m -> m.sender?.did?.let { d -> bskyRepo.chatProfiles[d]?.let { d to it } } }.toMap()

    // Item 12 follow-up: infinite-scroll-up for older DMs. `cursor` is
    // Bluesky's "further back in time" pagination token from the last fetch
    // (initial load or a previous call to this) — null means there's nothing
    // older left to load.
    fun loadMoreDmMessages() {
        val thread = _dmThread.value ?: return
        if (thread.loadingMore || thread.loading || thread.cursor == null) return
        _dmThread.value = thread.copy(loadingMore = true)
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.getConvoMessages(bskyToken, _bskyDid.value, thread.convo.convoId, thread.cursor)
                .onSuccess { (olderMessages, newCursor) ->
                    val current = _dmThread.value ?: return@onSuccess
                    // Older messages come back already in chronological order
                    // (same as the existing list), so they just prepend.
                    _dmThread.value = current.copy(
                        messages = olderMessages + current.messages,
                        embeddedPosts = current.embeddedPosts + buildEmbeddedPosts(olderMessages),
                        cursor = newCursor,
                        loadingMore = false,
                        members = if (current.convo.isGroup) groupSenders(olderMessages) + current.members else current.members
                    )
                }
                .onFailure { _dmThread.value = _dmThread.value?.copy(loadingMore = false) }
        }
    }

    // ── DM thread "shared posts" feed (item 12 follow-up) ───────────────────
    // Tapping a shared-post card in a DM thread opens a feed made of every
    // post shared *in that conversation* — both directions, unlike the
    // "From Friends" feed below which only ever shows posts others shared
    // with you. Reuses that same fetch, just scoped to one conversation.
    private val _dmFeedLoadingOverlay = MutableStateFlow(false)
    val dmFeedLoadingOverlay: StateFlow<Boolean> = _dmFeedLoadingOverlay

    fun openDmThreadSharedPostsFeed() {
        val convo = _dmThread.value?.convo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _dmFeedLoadingOverlay.value = true
            bskyRepo.getFriendsSharedPosts(bskyToken, _bskyDid.value, listOf(convo), includeSelfSent = true, selfAuthor = _selfProfile.value?.author)
                .onSuccess { items ->
                    if (items.isEmpty()) {
                        showToast("No Shared Posts")
                    } else {
                        _currentIndex.value = 0
                        feedCursor = null
                        activeFeedMode = ActiveFeedMode.FRIENDS
                        activeFeedActorDid = null
                        _mediaItems.value = filterHidden(items)
                        _navDirection.value = 0
                        _dmThread.value = null
                        _dmInboxOpen.value = false
                        _screenState.value = ScreenState.FEED
                    }
                }
                .onFailure { showToast("Feed Empty") }
            _dmFeedLoadingOverlay.value = false
        }
    }

    // ── Item 8: Hub "Friends" section (Profiles/Reviews) ────────────────────
    // "Friends" is the same set used for the "From Friends" feed and the DM-
    // thread shared-posts feed: mutuals/contacts the person has an existing
    // DM conversation with (dmConversations, already loaded for the DM
    // inbox). Profiles just reuses that list directly for its avatar row;
    // Reviews needs its own fetch, done once lazily the first time the tab
    // is opened rather than eagerly on every Hub visit.
    // Item (this session): Reviews/Blogs are now sourced from local
    // "Subscribe" lists (see the profile Reviews/Blogs tabs' sub-row, and
    // PreferencesManager.SUBSCRIBED_REVIEW_DIDS/SUBSCRIBED_BLOG_DIDS) instead
    // of the removed Jetstream/firehose "everyone you follow" pipeline —
    // direct-per-account PDS fetches (see BlueskyRepository.
    // getSubscribedReviews/getSubscribedBlogs), bounded to whatever the user
    // actually opted into rather than their whole follow list. No firehose,
    // no Jetstream, no listRecords fan-out beyond the subscribed set.
    private val _subscribedReviewDids = MutableStateFlow<Set<String>>(emptySet())
    val subscribedReviewDids: StateFlow<Set<String>> = _subscribedReviewDids
    private val _subscribedBlogDids = MutableStateFlow<Set<String>>(emptySet())
    val subscribedBlogDids: StateFlow<Set<String>> = _subscribedBlogDids

    private val _friendsReviews = MutableStateFlow<List<FriendPopfeedReview>>(emptyList())
    val friendsReviews: StateFlow<List<FriendPopfeedReview>> = combine(_friendsReviews, com.mediaviewer.util.BlockedAccounts.version) { list, _ ->
        list.filterNot { com.mediaviewer.util.BlockedAccounts.isHidden(it.author.did) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _friendsBlogs = MutableStateFlow<List<FriendLeafletBlog>>(emptyList())
    val friendsBlogs: StateFlow<List<FriendLeafletBlog>> = combine(_friendsBlogs, com.mediaviewer.util.BlockedAccounts.version) { list, _ ->
        list.filterNot { com.mediaviewer.util.BlockedAccounts.isHidden(it.author.did) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _friendsReviewsLoading = MutableStateFlow(false)
    val friendsReviewsLoading: StateFlow<Boolean> = _friendsReviewsLoading
    private var reviewsBlogsLoaded = false

    // Feature (this session): a real, non-artificial "cold start finished
    // restoring session state" signal for the app-launch pixel transition
    // (see PixelMatrixOverlay/PixelTransitionController and AppRoot's
    // wiring) — flips true once the init{} auth-restore coroutine below has
    // actually read every stored preference and kicked off whichever
    // initial load applies, not after some guessed/fixed delay.
    private val _appInitialized = MutableStateFlow(false)
    val appInitialized: StateFlow<Boolean> = _appInitialized

    /** Toggles whether `author` is subscribed for the Hub's Reviews section
     *  — called from the "Subscribe" button on their profile's Reviews tab.
     *  Refetches immediately afterward so the Hub (and the button's own
     *  state, read from subscribedReviewDids) reflects the change right
     *  away rather than only on the next Hub visit. */
    fun toggleReviewSubscription(author: AuthorInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.toggleSubscribedReviewDid(author.did)
            reviewsBlogsLoaded = false
            loadFriendsReviewsIfNeeded(force = true)
        }
    }

    /** Blogs-tab equivalent of [toggleReviewSubscription]. */
    fun toggleBlogSubscription(author: AuthorInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.toggleSubscribedBlogDid(author.did)
            reviewsBlogsLoaded = false
            loadFriendsReviewsIfNeeded(force = true)
        }
    }

    // ── Automatic Reviews/Blogs subscriptions ──────────────────────────────
    // Replaces the manual "Subscribe" button (removed from the profile
    // Reviews/Blogs sub-rows this session) with two complementary passive
    // paths that need zero extra PDS calls of their own:
    //  1. [maybeAutoSubscribeOnProfileOpen] — opening any profile already
    //     fetches that account's blogs/reviews (see openProfile below); if
    //     it turns out they have some, and the signed-in user follows them
    //     (or it's their own profile), fold them into the subscribed list
    //     right there. Runs on every profile open, not just the first,
    //     since it also keeps [rememberedTitlesJson-free] — no separate
    //     "remembered titles" cache is kept here; friendsReviews/
    //     friendsBlogs (backed by HUB_REVIEWS_CACHE_JSON/HUB_BLOGS_CACHE_JSON)
    //     already is that cache, refreshed by loadFriendsReviewsIfNeeded.
    //  2. [startFollowerScan] — a one-time (or user-re-triggered) sweep of
    //     every account the user follows, for the accounts they never
    //     happen to open a profile for.
    private fun maybeAutoSubscribeOnProfileOpen(
        author: AuthorInfo, isFollowingOrSelf: Boolean, hasReviews: Boolean, hasBlogs: Boolean
    ) {
        if (!hasReviews && !hasBlogs) return
        viewModelScope.launch(Dispatchers.IO) {
            // Bug fix (reviews from followed accounts never reaching the
            // Hub): the reviews/blogs probes usually finish before the full
            // profile does, and until then the profile's author is just the
            // one from the post that was tapped — which often doesn't carry
            // "you follow them" at all. So a followed account looked
            // unfollowed and was skipped. When it isn't clear yet, wait for
            // the real profile (or look it up) before deciding.
            val follows = isFollowingOrSelf || run {
                val fromOverlay = withTimeoutOrNull(10_000) {
                    while (true) {
                        val o = _profileOverlay.value
                        if (o == null || o.author.did != author.did) break
                        if (o.profile != null) return@withTimeoutOrNull o.author.isFollowing
                        delay(150)
                    }
                    null
                }
                fromOverlay ?: bskyRepo.getFullProfile(bskyToken, author.did).getOrNull()?.author?.isFollowing ?: false
            }
            if (!follows) return@launch
            if (hasReviews) prefs.addSubscribedReviewDidIfMissing(author.did)
            if (hasBlogs) prefs.addSubscribedBlogDidIfMissing(author.did)
            // Cheap to just re-run — loadFriendsReviewsIfNeeded's own cache
            // means this doesn't cost anything if nothing actually changed,
            // and force=true is what makes a newly-subscribed account's
            // content show up in the Hub without waiting for the next
            // natural Hub visit.
            reviewsBlogsLoaded = false
            loadFriendsReviewsIfNeeded(force = true)
        }
    }

    private val _followerScanState = MutableStateFlow<FollowerScanState>(FollowerScanState.Idle)
    val followerScanState: StateFlow<FollowerScanState> = _followerScanState
    private val _followerScanCompletedOnce = MutableStateFlow(false)
    val followerScanCompletedOnce: StateFlow<Boolean> = _followerScanCompletedOnce
    private var followerScanJob: Job? = null

    /** Feature: one-time (or user-re-triggered) follower scan — the active
     *  counterpart to [maybeAutoSubscribeOnProfileOpen]'s passive path.
     *  Walks every account the signed-in user follows (plus their own
     *  account, checked first on every run) probing each for Popfeed
     *  reviews / Leaflet blogs, so accounts the user never happens to open
     *  a profile for still end up in the local subscription lists —
     *  without ever touching Jetstream/firehose or any "everyone's
     *  activity" pipeline, just a direct per-account read.
     *
     *  Pacing: this is the same family of request as getSubscribedReviews/
     *  getSubscribedBlogs (see that doc comment for the actual documented
     *  Bluesky HTTP limit this is sized against), but can run against
     *  thousands of accounts in one go instead of the tens a subscribed
     *  list normally holds — so it runs far more conservatively:
     *  concurrency [SCAN_CONCURRENCY] with a fixed [SCAN_STAGGER_MS] delay
     *  before each probe, well under the ~10 req/sec sustained budget with
     *  wide margin left for whatever unknown limit a given third-party PDS
     *  might have of its own. This is explicitly allowed to take a while.
     *
     *  Resumable: the follows-list pagination cursor is persisted after
     *  every fully-processed page (FOLLOWER_SCAN_CURSOR), so an app kill
     *  mid-scan doesn't cost re-spending PDS calls re-checking accounts
     *  already resolved on the next [resume]d run. [resume] = false (the
     *  Settings "rescan from scratch" action) ignores any saved cursor and
     *  walks the whole follow list again — for picking up accounts that
     *  only started posting reviews/blogs after the last scan.
     */
    fun startFollowerScan(resume: Boolean = true) {
        if (followerScanJob?.isActive == true) return
        val myDid = _bskyDid.value
        if (myDid.isBlank()) return
        followerScanJob = viewModelScope.launch(Dispatchers.IO) {
            val scanned = com.mediaviewer.platform.AtomicInteger(0)
            val reviewsFound = com.mediaviewer.platform.AtomicInteger(0)
            val blogsFound = com.mediaviewer.platform.AtomicInteger(0)
            _followerScanState.value = FollowerScanState.Scanning(0, 0, 0)

            suspend fun probe(did: String) {
                val reviews = runCatching { bskyRepo.getPopfeedReviews(did, probeConcurrently = did == myDid) }.getOrDefault(emptyList())
                val blogs = runCatching { bskyRepo.getLeafletBlogs(did, probeConcurrently = did == myDid) }.getOrDefault(emptyList())
                if (reviews.isNotEmpty()) { prefs.addSubscribedReviewDidIfMissing(did); reviewsFound.incrementAndGet() }
                if (blogs.isNotEmpty()) { prefs.addSubscribedBlogDidIfMissing(did); blogsFound.incrementAndGet() }
                val s = scanned.incrementAndGet()
                _followerScanState.value = FollowerScanState.Scanning(s, reviewsFound.get(), blogsFound.get())
            }

            // The user's own account, every run, never gated behind the
            // resumable cursor below — this is what keeps a resumed or
            // re-triggered scan picking up the signed-in user's own new
            // reviews/blogs even though it's just one account.
            runCatching { probe(myDid) }

            var cursor: String? = if (resume) prefs.followerScanCursor.first() else null
            val gate = Semaphore(SCAN_CONCURRENCY)
            while (true) {
                val page = bskyRepo.getFollowsPage(bskyToken, myDid, cursor).getOrNull() ?: break
                val (dids, nextCursor) = page
                if (dids.isNotEmpty()) {
                    coroutineScope {
                        dids.filter { it != myDid }.forEach { did ->
                            launch {
                                gate.withPermit {
                                    delay(SCAN_STAGGER_MS)
                                    runCatching { probe(did) }
                                }
                            }
                        }
                    }
                }
                prefs.setFollowerScanCursor(nextCursor)
                cursor = nextCursor
                if (cursor.isNullOrBlank()) break
            }

            prefs.setFollowerScanCompleted(true)
            prefs.setFollowerScanCursor(null)
            prefs.setFollowerScanLastRunMs(com.mediaviewer.platform.currentTimeMillis())
            _followerScanCompletedOnce.value = true
            _followerScanState.value = FollowerScanState.Completed(scanned.get(), reviewsFound.get(), blogsFound.get())

            reviewsBlogsLoaded = false
            loadFriendsReviewsIfNeeded(force = true)
        }
    }

    /** Dismisses the completion popup back to a quiet Idle state — called
     *  when the user taps the popup's close bubble. Leaves the persisted
     *  followerScanCompleted flag (and therefore [followerScanCompletedOnce])
     *  alone, so the Hub's intro bubble stays gone afterward. */
    fun dismissFollowerScanResult() {
        if (_followerScanState.value is FollowerScanState.Completed) _followerScanState.value = FollowerScanState.Idle
    }

    companion object {
        /** Marks a Share with target that's a profile, not a post. */
        const val PROFILE_SHARE_PREFIX = "profile-share:"
        private const val SCAN_CONCURRENCY = 2
        private const val SCAN_STAGGER_MS = 350L
    }

    /** Loads (or reloads) the Hub's Reviews/Blogs sections from the current
     *  subscribed-DID sets. Cache-first, same instant-on-restart shape the
     *  old firehose indexer had: the last persisted snapshot publishes
     *  immediately, then a fresh fetch runs and overwrites/persists it.
     *  `force = true` (subscribe/unsubscribe, or the Hub's manual refresh
     *  bubble) bypasses the "already loaded this session" guard. */
    fun loadFriendsReviewsIfNeeded(force: Boolean = false) {
        if (reviewsBlogsLoaded && !force) return
        // A forced reload asked for while one is already running (e.g. a
        // newly-subscribed account from a profile you just opened) used to
        // be silently dropped — the running load had already read the old
        // subscription list, so the new account's reviews never appeared.
        // Now it's queued and runs as soon as the current load finishes.
        // Bug fix (per feedback — Hub reviews/blogs briefly show cached
        // content on app start, then disappear a few seconds later): this
        // used to be two separate lines — `if (_friendsReviewsLoading.value)
        // return` immediately followed by `_friendsReviewsLoading.value =
        // true` — which is a classic check-then-act race. This function is
        // no longer only ever called from one place at a time: on a
        // Hub-first cold start (this session's change), the Hub's own
        // LaunchedEffect(Unit) calls this the instant AtProtocolPageContent
        // composes (on the Main thread) at essentially the same moment
        // startHubBackgroundWarmup's retry loop (this session's earlier
        // fix) also calls it — from Dispatchers.IO, a genuinely
        // multi-threaded dispatcher. Both can read `_friendsReviewsLoading`
        // as false before either has had a chance to set it true, so both
        // proceed: two independent coroutines both re-run the "load cache
        // from disk" step, both do their own network fetch, and whichever
        // of the two finishes (and clears the loading flag) *first* lets
        // the retry loop's `while (_friendsReviewsLoading.value) delay(300)`
        // wait exit and re-check `isDone` while the *other* copy is still
        // mid-flight — exactly the kind of overlapping-attempt scenario
        // that can end with a still-running older fetch's later, unluckier
        // result (or a subscribed-DID snapshot read mid-race) landing after
        // a newer one and stomping good data with stale/incomplete data.
        // `compareAndSet` makes the whole check-and-claim a single atomic
        // operation — whichever caller sees `false` and swaps it to `true`
        // is the only one that proceeds; every other concurrent caller's
        // compareAndSet fails (sees `true` already) and returns immediately,
        // the same guarantee `if` + separate assignment was only *supposed*
        // to provide.
        if (!_friendsReviewsLoading.compareAndSet(false, true)) {
            if (!force) return
            friendsReloadPending = true
            // The running load may have finished in the meantime (and so
            // missed the flag) — if so, claim it and go now.
            if (!_friendsReviewsLoading.compareAndSet(false, true)) return
            friendsReloadPending = false
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Bug fix (reviews/blogs getting stuck on "not loading"): the
            // actual network fetch below used to run un-guarded — any
            // exception from it (a real network failure, a malformed
            // record, anything) skipped straight past
            // `_friendsReviewsLoading.value = false`, leaving the Hub
            // permanently stuck showing its loading state (every future
            // call short-circuits on the loading-guard above, and there's
            // no other path that resets it) until the process restarts.
            // try/finally now guarantees that flag always clears, and a
            // failed fetch keeps whatever was already showing (cache or
            // the previous successful fetch) instead of wiping it to
            // empty — better to show slightly-stale content than none.
            try {
                if (!force) {
                    // Instant snapshot from disk before the network round-trip —
                    // never leave the Hub blank while the fetch below is in flight.
                    runCatching {
                        // Bug fix: LeafletBlog gained a `blocks` field this
                        // session that older on-disk caches (written before
                        // it existed) don't have — see LeafletBlog.blocks'
                        // own doc comment for why a missing key can't just
                        // fall back to that field's declared default under
                        // Gson's normal reflective deserialization. This
                        // explicit deserializer sidesteps the whole
                        // problem by never asking Gson to populate `blocks`
                        // from cached JSON at all; the live fetch below
                        // supplies real block data moments later anyway.
                        // LeafletBlog.blocks is @Transient: never cached,
                        // always emptyList() from cache (see its doc comment).
                        val cachedReviews: List<FriendPopfeedReview> = prefs.hubReviewsCacheJson.first()?.takeIf { it.isNotBlank() }
                            ?.let { runCatching { com.mediaviewer.json.StellarJson.default.decodeFromString<List<FriendPopfeedReview>>(it) }.getOrNull() } ?: emptyList()
                        val cachedBlogs: List<FriendLeafletBlog> = prefs.hubBlogsCacheJson.first()?.takeIf { it.isNotBlank() }
                            ?.let { runCatching { com.mediaviewer.json.StellarJson.default.decodeFromString<List<FriendLeafletBlog>>(it) }.getOrNull() } ?: emptyList()
                        if (cachedReviews.isNotEmpty()) _friendsReviews.value = cachedReviews
                        if (cachedBlogs.isNotEmpty()) _friendsBlogs.value = cachedBlogs
                    }
                }
                val reviewDids = prefs.subscribedReviewDids.first()
                val blogDids = prefs.subscribedBlogDids.first()
                _subscribedReviewDids.value = reviewDids
                _subscribedBlogDids.value = blogDids
                // Bug fix (reviews/blogs sections sometimes showing only
                // one, or neither, despite being subscribed to both): this
                // used to fetch `reviews` then `blogs` into two sequential
                // `val`s and only ever published EITHER to state — and only
                // cached them — once BOTH had finished without throwing.
                // If the blogs fetch threw for any reason, the reviews
                // result was silently discarded right along with it (and
                // vice versa if reviews threw first, since `blogs` never
                // even got a chance to run) — so a transient failure on
                // just one side could wipe out a perfectly good result on
                // the other, or leave both sections stuck on whatever
                // (possibly incomplete) cache snapshot was loaded above.
                // Each is now fetched, published to its own state, and
                // cached independently and in parallel, so a failure in
                // one no longer discards — or blocks — a success in the
                // other, and each section updates the moment its own fetch
                // resolves rather than waiting on its sibling.
                var reviewsOk = true
                var blogsOk = true
                coroutineScope {
                    val reviewsJob = async {
                        if (reviewDids.isEmpty()) {
                            _friendsReviews.value = emptyList()
                        } else {
                            runCatching { bskyRepo.getSubscribedReviews(bskyToken, reviewDids.toList()) }
                                .onSuccess { _friendsReviews.value = it }
                                .onFailure { reviewsOk = false }
                        }
                    }
                    val blogsJob = async {
                        if (blogDids.isEmpty()) {
                            _friendsBlogs.value = emptyList()
                        } else {
                            runCatching { bskyRepo.getSubscribedBlogs(bskyToken, blogDids.toList()) }
                                .onSuccess { _friendsBlogs.value = it }
                                .onFailure { blogsOk = false }
                        }
                    }
                    reviewsJob.await()
                    blogsJob.await()
                }
                // Only counted as fully "loaded" (which stops the
                // retryWithBackoff warmup loop in startHubBackgroundWarmup
                // from retrying) once both sides have actually succeeded —
                // a partial success still updated its own section above,
                // but the loop keeps quietly retrying in the background
                // until the other side catches up too.
                reviewsBlogsLoaded = reviewsOk && blogsOk
                runCatching {
                    prefs.setHubCache(com.mediaviewer.json.StellarJson.default.encodeToString<List<FriendPopfeedReview>>(_friendsReviews.value), com.mediaviewer.json.StellarJson.default.encodeToString<List<FriendLeafletBlog>>(_friendsBlogs.value), com.mediaviewer.platform.currentTimeMillis())
                }
            } finally {
                _friendsReviewsLoading.value = false
                if (friendsReloadPending) {
                    friendsReloadPending = false
                    loadFriendsReviewsIfNeeded(force = true)
                }
            }
        }
    }
    @kotlin.concurrent.Volatile private var friendsReloadPending = false

    /** Hub refresh bubble — re-checks Mutuals, Reviews, and Blogs against
     *  the network, bypassing every "already loaded" guard. Live sections
     *  aren't included: they already refresh on their own visit-driven
     *  loader and weren't part of what was asked for here.
     *  Bug fix: this used to call a `loadDmRecipients(force = true)` that
     *  didn't actually exist on the ViewModel (only as a same-named
     *  BlueskyRepository function with an unrelated (token, myDid)
     *  signature) — an unresolved-reference compile error. The real
     *  unconditional Mutuals reloader is loadDmConversationsBlocking;
     *  ensureDmConversationsLoaded/ensureDmConversationsLoadedSuspend both
     *  short-circuit if a list is already loaded, which is exactly the
     *  "already loaded" guard this button needs to bypass. */
    fun refreshHub() {
        viewModelScope.launch(Dispatchers.IO) { loadDmConversationsBlocking(silent = true) }
        loadFriendsReviewsIfNeeded(force = true)
    }

    /** Settings → Dev Tools → "Force Refresh Hub": reloads every Hub row —
     *  Mutuals, Reviews/Blogs, Livestreams, the feed list, your profile and
     *  any Hub lists — ignoring what's already loaded. */
    fun forceRefreshHub() {
        if (!_bskyLoggedIn.value) return
        tapHaptic()
        refreshHub()
        refreshBlueskyLiveNow()
        liveFriendsLoaded = false
        loadLiveFriendsIfNeeded()
        loadAvailableFeeds()
        loadSelfProfile()
        com.mediaviewer.util.HubLayout.rows.filter { it.hasMembers && it.enabled }.forEach { row ->
            loadHubListIfNeeded(row.contentKey, force = true)
        }
        showToast("Refreshing the Hub…")
    }

    // ── Customize Hub: list rows ─────────────────────────────────────────────
    /** One Hub list row's loaded content (see BlueskyRepository.getHubListContent). */
    data class HubListState(
        val loading: Boolean = false,
        val members: List<AuthorInfo> = emptyList(),
        val posts: List<MediaItem> = emptyList(),
        val loadedAt: Long = 0L,
        val failed: Boolean = false,
        /** Where the next page of posts starts (null = the list feed has
         *  run out; older posts then come from the members' own feeds). */
        val postsCursor: String? = null,
        val loadingMore: Boolean = false,
        /** Every member's own feed has been read to the end too. */
        val membersExhausted: Boolean = false
    ) {
        val hasMore: Boolean get() = postsCursor != null || !membersExhausted
    }

    /** One list member's own feed, read in pages once the list feed runs
     *  out (see loadHubListFromMembers). [oldest] is the createdAt of the
     *  oldest post fetched so far; [buffer] holds fetched posts that can't
     *  be shown yet because another member might still have newer ones. */
    private class HubMemberFeed {
        var cursor: String? = null
        var started = false
        var done = false
        var oldest = Long.MAX_VALUE
        val buffer = ArrayList<Pair<Long, MediaItem>>()
    }
    /** list URI -> member DID -> that member's feed state. Guarded by hubListLock. */
    private val hubMemberFeeds = HashMap<String, LinkedHashMap<String, HubMemberFeed>>()
    private val _hubLists = MutableStateFlow<Map<String, HubListState>>(emptyMap())
    val hubLists: StateFlow<Map<String, HubListState>> = _hubLists
    private val hubListLock = Any()

    private fun setHubList(uri: String, transform: (HubListState) -> HubListState) {
        com.mediaviewer.platform.synchronizedCompat(hubListLock) {
            val map = _hubLists.value
            _hubLists.value = map + (uri to transform(map[uri] ?: HubListState()))
        }
    }

    /** Loads a Hub list row (members + their latest posts) from the AppView.
     *  Kept for 5 minutes unless [force]d. */
    fun loadHubListIfNeeded(uri: String, force: Boolean = false) {
        if (!_bskyLoggedIn.value || uri.isBlank()) return
        com.mediaviewer.platform.synchronizedCompat(hubListLock) {
            val cur = _hubLists.value[uri]
            if (cur?.loading == true) return
            // Loaded content is kept for 5 minutes; a failure only briefly,
            // so the row can recover by itself.
            val keepFor = if (cur?.failed == true) 15_000L else 5 * 60_000L
            if (!force && cur != null && com.mediaviewer.platform.currentTimeMillis() - cur.loadedAt < keepFor) return
            // A Profiles row (accounts kept on this device, no Bluesky list):
            // its members are already known; their posts are read straight
            // from each account's own feed, merged newest first (the same
            // reader a list row falls back to — see loadHubListFromMembers).
            val profilesRow = com.mediaviewer.util.HubLayout.rows.firstOrNull { it.isProfiles && it.id == uri }
            if (profilesRow != null) {
                val members = profilesRow.profiles
                    .filterNot { com.mediaviewer.util.BlockedAccounts.isHidden(it.did) }
                    .map { AuthorInfo(did = it.did, handle = it.handle, displayName = it.displayName, avatarUrl = it.avatarUrl) }
                val sameMembers = cur != null && cur.members.map { it.did } == members.map { it.did }
                if (!sameMembers || force) hubMemberFeeds.remove(uri)
                _hubLists.value = _hubLists.value + (uri to HubListState(
                    loading = false, members = members,
                    posts = if (sameMembers && !force) cur?.posts ?: emptyList() else emptyList(),
                    loadedAt = com.mediaviewer.platform.currentTimeMillis(),
                    postsCursor = null, membersExhausted = members.isEmpty()
                ))
                return
            }
            if (uri.startsWith("profiles:")) return
            _hubLists.value = _hubLists.value + (uri to (cur ?: HubListState()).copy(loading = true))
        }
        viewModelScope.launch(Dispatchers.IO) {
            var result = bskyRepo.getHubListContent(bskyToken, _bskyDid.value, uri)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getHubListContent(bskyToken, _bskyDid.value, uri)
            }
            result.onSuccess { content ->
                val posts = filterHidden(content.posts)
                com.mediaviewer.platform.synchronizedCompat(hubListLock) { hubMemberFeeds.remove(uri) }
                setHubList(uri) {
                    HubListState(
                        loading = false, members = content.members, posts = posts,
                        loadedAt = com.mediaviewer.platform.currentTimeMillis(), postsCursor = content.postsCursor,
                        membersExhausted = content.members.isEmpty()
                    )
                }
                // Keep the row's name in step with the list's real name
                // (renamed here or in another app).
                content.name?.takeIf { it.isNotBlank() }?.let { name ->
                    withContext(Dispatchers.Main) { com.mediaviewer.util.HubLayout.renameList(uri, name) }
                }
                // Only a few posts on the first page: fill the row right away
                // (rows in Posts mode only — Accounts mode never shows them).
                val showsPosts = com.mediaviewer.util.HubLayout.rows.any { it.contentKey == uri && it.showPosts }
                if (showsPosts && posts.size < HUB_LIST_BATCH) loadMoreHubList(uri)
            }.onFailure {
                setHubList(uri) { it.copy(loading = false, failed = true, loadedAt = com.mediaviewer.platform.currentTimeMillis()) }
            }
        }
    }

    /** A Hub list row in Posts mode scrolled to its end: the next page.
     *  Reads Bluesky's list feed first; once that stops giving a cursor
     *  (which it does early for some lists, e.g. a newly made one), carries
     *  on further back through the members' own feeds instead. */
    fun loadMoreHubList(uri: String) {
        if (!_bskyLoggedIn.value) return
        val start: Pair<String?, List<String>> = com.mediaviewer.platform.synchronizedCompat(hubListLock) {
            val cur = _hubLists.value[uri] ?: return
            if (cur.loading || cur.loadingMore || !cur.hasMore) return
            _hubLists.value = _hubLists.value + (uri to cur.copy(loadingMore = true))
            cur.postsCursor to cur.members.map { it.did }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // A list feed page is 100 items, but after dropping reposts,
            // replies (and text-only posts, if that's on) a page can leave
            // only a handful — so keep reading pages until there's a useful
            // batch of new posts, or the list feed runs out (max 6 pages a go).
            var next: String? = start.first
            var added = 0
            var pages = 0
            var failed = false
            while (next != null && added < HUB_LIST_BATCH && pages < 6) {
                val page = next ?: break
                var result = bskyRepo.getHubListPostsPage(bskyToken, _bskyDid.value, uri, page)
                if (result.isFailure && isAuthError(result.exceptionOrNull()?.message) && refreshBskyTokenIfPossible()) {
                    result = bskyRepo.getHubListPostsPage(bskyToken, _bskyDid.value, uri, page)
                }
                val ok = result.getOrNull()
                if (ok == null) { failed = true; break }
                pages++
                next = ok.second
                val newCursor = next
                setHubList(uri) { st ->
                    val known = st.posts.mapTo(HashSet()) { it.postUri }
                    val fresh = filterHidden(ok.first).filter { it.postUri !in known }
                    added += fresh.size
                    st.copy(posts = st.posts + fresh, postsCursor = newCursor)
                }
            }
            if (!failed) setHubList(uri) { it.copy(postsCursor = next) }
            // The list feed has nothing further back: the members' own feeds.
            if (!failed && next == null && added < HUB_LIST_BATCH) {
                runCatching { loadHubListFromMembers(uri, start.second, HUB_LIST_BATCH - added) }
            }
            setHubList(uri) { it.copy(loadingMore = false) }
        }
    }

    /** Pages further back through a Hub list's members' own feeds and adds
     *  up to about [want] posts to the row, newest first. It's a proper
     *  merge: a post is only shown once every member still being read has
     *  been read back past it, so nothing newer can turn up later and land
     *  out of order. Only the [HUB_LIST_MEMBER_CAP] most recently active
     *  members are read (members come sorted that way). */
    private suspend fun loadHubListFromMembers(uri: String, memberDids: List<String>, want: Int) {
        if (memberDids.isEmpty()) { setHubList(uri) { it.copy(membersExhausted = true) }; return }
        val feeds: Map<String, HubMemberFeed> = com.mediaviewer.platform.synchronizedCompat(hubListLock) {
            val m = hubMemberFeeds.getOrPut(uri) { LinkedHashMap() }
            memberDids.take(HUB_LIST_MEMBER_CAP).forEach { d -> m.getOrPut(d) { HubMemberFeed() } }
            LinkedHashMap(m)
        }
        var emitted = 0
        var rounds = 0
        while (emitted < want && rounds < 5) {
            rounds++
            val active = feeds.entries.filter { !it.value.done }
            if (active.isEmpty() && feeds.values.all { it.buffer.isEmpty() }) {
                setHubList(uri) { it.copy(membersExhausted = true) }
                return
            }
            // Fetch whoever is holding the merge back: everyone not read yet,
            // else the few members whose oldest fetched post is the newest.
            val unstarted = active.filter { !it.value.started }
            val toFetch = if (unstarted.isNotEmpty()) unstarted
                else active.sortedByDescending { it.value.oldest }.take(6)
            var anyOk = false
            toFetch.chunked(8).forEach { chunk ->
                coroutineScope {
                    chunk.map { (did, feed) ->
                        async {
                            val res = bskyRepo.getHubListMemberPostsPage(bskyToken, _bskyDid.value, did, feed.cursor).getOrNull()
                            did to res
                        }
                    }.awaitAll()
                }.forEach { (did, res) ->
                    val feed = feeds[did] ?: return@forEach
                    if (res == null) return@forEach
                    anyOk = true
                    val (items, nextCursor) = res
                    com.mediaviewer.platform.synchronizedCompat(hubListLock) {
                        // No cursor, or a page that moved nowhere: that's the end.
                        val stalled = items.isEmpty() && nextCursor == feed.cursor
                        feed.started = true
                        feed.buffer += items
                        items.minOfOrNull { it.first }?.let { feed.oldest = minOf(feed.oldest, it) }
                        feed.cursor = nextCursor
                        if (nextCursor == null || stalled) feed.done = true
                    }
                }
            }
            if (!anyOk && toFetch.isNotEmpty()) return // offline or failing: try again on the next scroll
            // Everything at least as new as the newest "oldest fetched" of the
            // members still being read is safe to show now.
            val stillReading = feeds.values.filter { !it.done }
            val frontier = if (stillReading.isEmpty()) Long.MIN_VALUE
                else stillReading.maxOf { if (it.started) it.oldest else Long.MAX_VALUE }
            val ready = ArrayList<Pair<Long, MediaItem>>()
            com.mediaviewer.platform.synchronizedCompat(hubListLock) {
                feeds.values.forEach { f ->
                    val (take, keep) = f.buffer.partition { it.first >= frontier }
                    ready += take
                    f.buffer.clear(); f.buffer += keep
                }
            }
            if (ready.isNotEmpty()) {
                val sorted = filterHidden(ready.sortedByDescending { it.first }.map { it.second })
                setHubList(uri) { st ->
                    val known = st.posts.mapTo(HashSet()) { it.postUri }
                    val fresh = sorted.filter { it.postUri !in known }.distinctBy { it.postUri }
                    emitted += fresh.size
                    st.copy(posts = st.posts + fresh)
                }
            }
        }
        val allDone = com.mediaviewer.platform.synchronizedCompat(hubListLock) { feeds.values.all { it.done && it.buffer.isEmpty() } }
        if (allDone) setHubList(uri) { it.copy(membersExhausted = true) }
    }

    /** Customize Hub → Add → one of your lists. */
    fun addHubList(uri: String, name: String) {
        com.mediaviewer.util.HubLayout.addList(uri, name)
        loadHubListIfNeeded(uri, force = true)
    }

    /** Customize Hub → Add → Other → a pasted list link. [onDone] gets null
     *  on success or an error message. */
    fun addHubListFromUrl(url: String, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = bskyRepo.resolveListUrl(url)
            withContext(Dispatchers.Main) {
                result.onSuccess { (uri, name) -> addHubList(uri, name); onDone(null) }
                    .onFailure { onDone(it.message ?: "Couldn't add that list") }
            }
        }
    }

    /** Customize Hub's list picker: makes sure your own lists are loaded. */
    fun ensureUserListsLoaded() {
        if (!_bskyLoggedIn.value || _userLists.value.isNotEmpty() || _userListsLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _userListsLoading.value = true
            bskyRepo.getUserLists(bskyToken, _bskyDid.value).onSuccess { _userLists.value = it }
            _userListsLoading.value = false
        }
    }

    /** Tapping a post in a Hub list row: opens that row's posts in the
     *  timeline at [index], titled with the list's name. */
    fun openHubListPost(listUri: String, name: String, index: Int) {
        val items = _hubLists.value[listUri]?.posts ?: return
        if (index !in items.indices) return
        val author = AuthorInfo(_bskyDid.value, HUB_LIST_FEED_HANDLE_PREFIX + listUri, name.ifBlank { "List" }, null)
        val cur = _authorFeedState.value
        _authorFeedState.value = cur?.copy(author = author) ?: AuthorFeedSavedState(
            author = author, items = _mediaItems.value, currentIndex = _currentIndex.value,
            cursor = feedCursor, feedUri = _selectedFeedUri.value
        )
        feedCursor = null
        // Fully loaded up front — no paging (same as History).
        activeFeedMode = ActiveFeedMode.HISTORY
        activeFeedActorDid = null
        _mediaItems.value = items
        _currentIndex.value = index
        _navDirection.value = 0
        _screenState.value = ScreenState.FEED
    }

    // ── Item 8/19: Hub "Livestreams" section ─────────────────────────────────
    // Live status confirmed against the real place.stream.live.getLiveUsers
    // lexicon (see StreamplaceRepository.getLiveFriends) — unlike VODs, which
    // the user said are closed-beta/restrictive right now, live status is a
    // simple read-only platform-wide list this app just filters down to
    // accounts the user follows, so there's no beta-access gate to worry
    // about here.
    private val _liveFriends = MutableStateFlow<List<StreamplaceLiveStream>>(emptyList())
    val liveFriends: StateFlow<List<StreamplaceLiveStream>> = combine(_liveFriends, com.mediaviewer.util.BlockedAccounts.version) { list, _ ->
        list.filterNot { com.mediaviewer.util.BlockedAccounts.isHidden(it.authorDid) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _liveFriendsLoading = MutableStateFlow(false)
    val liveFriendsLoading: StateFlow<Boolean> = _liveFriendsLoading
    private var liveFriendsLoaded = false

    /** Every followed DID — used only by the Live sections (Streamplace +
     *  Bluesky Live Now), which are still scoped to "everyone you follow"
     *  (unlike Reviews/Blogs, which moved to the Subscribe-list model this
     *  session — see loadFriendsReviewsIfNeeded). No indexer/cache to check
     *  first anymore, just a direct call. */
    private suspend fun followedDidsForLiveSections(): Result<Set<String>> =
        bskyRepo.getAllFollows(bskyToken, _bskyDid.value).map { list -> list.map { it.did }.toSet() }

    fun loadLiveFriendsIfNeeded() {
        if (liveFriendsLoaded) return
        // Bug fix: same check-then-act race as loadFriendsReviewsIfNeeded's
        // own compareAndSet fix above — see that function's comment for the
        // full reasoning, which applies identically here now that this is
        // also called both from startHubBackgroundWarmup's retry loop (IO
        // dispatcher) and the Hub's own composition (Main dispatcher) at
        // essentially the same moment on a Hub-first cold start.
        if (!_liveFriendsLoading.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            // Bug fix + roadmap: same dmConversations-not-loaded-yet issue as
            // Reviews above, and broadened to everyone the user follows
            // rather than just DM contacts, per feedback.
            // Same "don't cache a transient failure as done" fix as
            // loadFriendsReviewsIfNeeded above — only mark loaded on an
            // actually-successful follows fetch.
            val followsResult = followedDidsForLiveSections()
            if (followsResult.isSuccess) {
                val dids = followsResult.getOrDefault(emptySet())
                _liveFriends.value = if (dids.isEmpty()) emptyList()
                    else streamplaceRepo.getLiveFriends(dids).getOrDefault(emptyList())
                liveFriendsLoaded = true
            }
            _liveFriendsLoading.value = false
        }
    }

    // Bug fix/roadmap consistency: refreshing either of the two lazy Hub
    // fetches above (e.g. pull-to-refresh, or reopening after a while) is
    // just "forget what we loaded and load again" — exposed separately from
    // the *IfNeeded functions so a future refresh gesture has something to
    // call without duplicating the fetch logic.
    fun refreshFriendsReviews() = loadFriendsReviewsIfNeeded(force = true)
    fun refreshLiveFriends() { liveFriendsLoaded = false; loadLiveFriendsIfNeeded() }

    // Feature (this session): Bluesky's own native "Live Now" badge —
    // distinct from Streamplace above, this is an off-platform link (Twitch/
    // YouTube) a mutual set on their own profile via Bluesky's built-in
    // status feature. Rendered in the same Livestreams section as the
    // Streamplace cards (see SettingsSheet.kt), scoped the same way
    // (everyone followed, matching Streamplace's own scope in this section
    // rather than Mutuals-only, so the two sources stay visually/logically
    // consistent within one section) via the same getAllFollows() list.
    private val _blueskyLiveNow = MutableStateFlow<List<BlueskyLiveNowStream>>(emptyList())
    val blueskyLiveNow: StateFlow<List<BlueskyLiveNowStream>> = combine(_blueskyLiveNow, com.mediaviewer.util.BlockedAccounts.version) { list, _ ->
        list.filterNot { com.mediaviewer.util.BlockedAccounts.isHidden(it.author.did) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _blueskyLiveNowLoading = MutableStateFlow(false)
    val blueskyLiveNowLoading: StateFlow<Boolean> = _blueskyLiveNowLoading
    private var blueskyLiveNowLoaded = false

    fun loadBlueskyLiveNowIfNeeded() {
        if (blueskyLiveNowLoaded) return
        // Bug fix: same check-then-act race as loadFriendsReviewsIfNeeded's
        // own compareAndSet fix above — see that function's comment.
        if (!_blueskyLiveNowLoading.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            // Same "don't cache a transient failure as done" principle as
            // Reviews/Streamplace above — only latch loaded on genuine success.
            val followsResult = followedDidsForLiveSections()
            if (followsResult.isSuccess) {
                val dids = followsResult.getOrDefault(emptySet()).toList()
                _blueskyLiveNow.value = if (dids.isEmpty()) emptyList()
                    else bskyRepo.getLiveNowStreams(bskyToken, dids).getOrDefault(emptyList())
                blueskyLiveNowLoaded = true
            }
            _blueskyLiveNowLoading.value = false
        }
    }
    fun refreshBlueskyLiveNow() { blueskyLiveNowLoaded = false; loadBlueskyLiveNowIfNeeded() }

    /** A live stream currently expanded into the inline WebView player
     *  overlay (see LiveNowPlayerOverlay in SettingsSheet.kt) — generic over
     *  BOTH Live sources now (Streamplace and Bluesky Live Now both open the
     *  real stream link in an in-app WebView, per this session's change; the
     *  ViewModel doesn't need to know which source it came from, just the
     *  URL and label to show). Only one at a time, same pattern as
     *  sendPopupTarget. */
    data class PlayingLiveStream(val url: String, val title: String, val subtitle: String)
    private val _playingLive = MutableStateFlow<PlayingLiveStream?>(null)
    val playingLive: StateFlow<PlayingLiveStream?> = _playingLive
    fun openLivePlayer(url: String, title: String, subtitle: String) { _playingLive.value = PlayingLiveStream(url, title, subtitle) }
    fun closeLivePlayer() { _playingLive.value = null }

    fun sendDmThreadReply(text: String, replyToMessageId: String? = null) {
        val thread = _dmThread.value ?: return
        if (text.isBlank() || thread.convo.convoId.isBlank()) return
        // Item 8: haptic tap on sending a DM.
        tapHaptic()
        _dmThread.value = thread.copy(sending = true)
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.sendMessage(bskyToken, _bskyDid.value, thread.convo.convoId, text, replyToMessageId = replyToMessageId)
                .onSuccess {
                    // Re-fetch the thread so the new message shows up in the linear history.
                    val refreshed = bskyRepo.getConvoMessages(bskyToken, _bskyDid.value, thread.convo.convoId)
                    refreshed.onSuccess { (messages, cursor) ->
                        _dmThread.value = _dmThread.value?.copy(
                            messages = messages, embeddedPosts = buildEmbeddedPosts(messages),
                            cursor = cursor, sending = false
                        )
                    }.onFailure { _dmThread.value = _dmThread.value?.copy(sending = false) }
                }
                .onFailure {
                    _dmThread.value = _dmThread.value?.copy(sending = false)
                    showToast("Failed to send")
                }
        }
    }

    /** Long-press emoji reactions on a DM (Bluesky's own chat reactions):
     *  toggles the signed-in account's [emoji] on [messageId]. Shown
     *  immediately, then replaced by what the server returns (or undone if
     *  it refuses). */
    fun toggleDmReaction(messageId: String, emoji: String) {
        val thread = _dmThread.value ?: return
        val convoId = thread.convo.convoId
        if (convoId.isBlank() || emoji.isBlank()) return
        val me = _bskyDid.value
        val msg = thread.messages.firstOrNull { it.id == messageId } ?: return
        val mine = msg.reactions.orEmpty().any { it.value == emoji && it.sender?.did == me }
        val optimistic = msg.copy(
            reactions = if (mine) msg.reactions.orEmpty().filterNot { it.value == emoji && it.sender?.did == me }
            else msg.reactions.orEmpty() + BskyReactionView(emoji, BskyMessageSender(me), com.mediaviewer.platform.nowIsoString())
        )
        fun replace(m: BskyMessageView) {
            val cur = _dmThread.value ?: return
            if (cur.convo.convoId != convoId) return
            _dmThread.value = cur.copy(messages = cur.messages.map { if (it.id == m.id) m else it })
        }
        replace(optimistic)
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.setMessageReaction(bskyToken, me, convoId, messageId, emoji, add = !mine)
                .onSuccess { updated ->
                    // Keep whatever the message already knew it replied to if
                    // the server's copy leaves that out.
                    if (updated != null) replace(updated.copy(replyTo = updated.replyTo ?: msg.replyTo))
                }
                .onFailure {
                    replace(msg)
                    showToast(if (it.message?.contains("ReactionLimitReached") == true) "Reaction limit reached" else "Couldn't react")
                }
        }
    }

    // ── From Friends background preload ──────────────────────────────────────
    // Populated in the background on app open so opening the feed is instant.
    // Null = not loaded yet (or a background load is in flight); non-null = ready to use.
    private val _friendsFeedCache = MutableStateFlow<List<MediaItem>?>(null)
    private var friendsFeedPreloadStarted = false

    // Full-screen black "Loading From Friends feed…" overlay — only shown when the
    // user opens the feed before the background preload above has finished.
    private val _friendsFeedLoadingOverlay = MutableStateFlow(false)
    val friendsFeedLoadingOverlay: StateFlow<Boolean> = _friendsFeedLoadingOverlay

    // Send/Share popup
    private val _sendPopupTarget = MutableStateFlow<MediaItem?>(null)
    val sendPopupTarget: StateFlow<MediaItem?> = _sendPopupTarget

    private val _sendPopupSelected = MutableStateFlow<Set<String>>(emptySet())
    val sendPopupSelected: StateFlow<Set<String>> = _sendPopupSelected

    private val _sendPopupSending = MutableStateFlow(false)
    val sendPopupSending: StateFlow<Boolean> = _sendPopupSending

    // Quote repost popup (item 5)
    private val _quoteRepostTarget = MutableStateFlow<MediaItem?>(null)
    val quoteRepostTarget: StateFlow<MediaItem?> = _quoteRepostTarget

    private val _quoteRepostSubmitting = MutableStateFlow(false)
    val quoteRepostSubmitting: StateFlow<Boolean> = _quoteRepostSubmitting

    // Reply-to-DM popup (item 7)
    private val _replyToConvo = MutableStateFlow<DmConversation?>(null)
    val replyToConvo: StateFlow<DmConversation?> = _replyToConvo

    // Whether each "Sent by" message box is expanded — remembered globally, applies to all posts (item 7)
    private val _sentByExpanded = MutableStateFlow(false)
    val sentByExpanded: StateFlow<Boolean> = _sentByExpanded
    fun toggleSentByExpanded() { _sentByExpanded.value = !_sentByExpanded.value }

    // ── Derived ───────────────────────────────────────────────────────────────
    // currentItem dynamically reflects e621 follow state so the UI stays in sync
    val currentItem: StateFlow<MediaItem?> = combine(
        _mediaItems, _currentIndex, _e621FollowedArtists, _appMode
    ) { items, idx, e621Follows, mode ->
        val item = items.getOrNull(idx) ?: return@combine null
        if (mode == AppMode.E621) {
            item.copy(author = item.author.copy(isFollowing = e621Follows.contains(item.author.handle)))
        } else item
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Item 9: "Show more/less like this" sends Bluesky's feed-personalization
    // interaction signal to whichever feed generator actually supplied the
    // current post (see sendFeedInteraction's own doc comment) — so it only
    // makes sense to offer the button while genuinely viewing a real,
    // feed-generator-backed feed. It's unsupported while: (a) viewing the
    // plain chronological Following timeline (`_selectedFeedUri` null — not
    // backed by any generator to proxy the signal to), or (b) temporarily
    // viewing an author's posts / search results / bookmarks / any other
    // override of the normal feed (`_authorFeedState` non-null — see its own
    // doc comment for what that flag means), regardless of what the
    // underlying selected feed happens to be. AT Protocol feed URIs for
    // actual custom feeds always live under the `app.bsky.feed.generator`
    // collection, which is what distinguishes them from e.g. list URIs.
    // Item 3 (rework): "Show more/less like this" only makes sense — and,
    // per the official app.bsky.feed lexicon, is only safe to actually send
    // — for a feed whose generator has explicitly declared
    // `acceptsInteractions: true` on its own app.bsky.feed.generator record.
    // Checking just "is this URI shaped like a feed generator" (the old
    // approach) let the buttons show up for plenty of feeds whose generator
    // never implements the endpoint at all, so tapping them just proxied a
    // request straight to that generator's own service and got a 501 back —
    // see BlueskyRepository.getFeedGeneratorInfo's doc comment. This cache
    // holds the real, per-feed answer once known — populated for every
    // pinned/saved feed as soon as loadAvailableFeeds() resolves them (see
    // its onSuccess below), and lazily filled in for any other feed the
    // person navigates to (search results, a feed opened from a profile,
    // etc.) by the resolver collector further down in this init block.
    // Deliberately defaults to "unknown" (no entry) rather than assuming
    // either true or false, so the button only ever appears once genuinely
    // confirmed — see supportsFeedInteractions below.
    private val _feedInteractionSupport = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    // Bug fix (round 2): sendFeedInteraction has to proxy to the feed
    // generator's own service DID — a value declared on the generator's
    // record (BskyFeedGeneratorView.did) that's often different from the
    // DID in the feed's own at:// URI — not the feed URI itself. This caches
    // that resolved service DID per feed URI, alongside (and populated at
    // the same time as) _feedInteractionSupport above, so
    // sendShowMoreLikeThisForCurrentItem/sendShowLessLikeThisForCurrentItem
    // can look up the right proxy target instead of guessing it from the
    // feed URI. See BlueskyRepository.sendFeedInteraction's doc comment.
    private val _feedGeneratorDid = MutableStateFlow<Map<String, String>>(emptyMap())

    val supportsFeedInteractions: StateFlow<Boolean> = combine(
        _selectedFeedUri, _authorFeedState, _appMode, _feedInteractionSupport
    ) { feedUri, authorState, mode, cache ->
        mode == AppMode.BLUESKY && authorState == null &&
            feedUri != null && feedUri.contains("app.bsky.feed.generator") &&
            cache[feedUri] == true
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
        // Feature (this session): a single collector, rather than touching
        // every one of this file's many `_screenState.value =
        // ScreenState.FEED` call sites individually (setScreen, login,
        // showHistory, showSaves, and others) — whichever path actually
        // lands the person on the feed, this reacts to the resulting state
        // change and flips hasVisitedFeed once, for good, for the rest of
        // the process's life (see hasVisitedFeed's own doc comment above).
        // Grid mode counts as "the feed" too (feeds now open straight into
        // it), and whichever of the two the person was last in is what the
        // Hub's Return to Feed goes back to — see returnToFeed().
        viewModelScope.launch {
            screenState.collect {
                if (it == ScreenState.FEED || it == ScreenState.GRID) {
                    _hasVisitedFeed.value = true
                    lastFeedView = it
                }
            }
        }
        viewModelScope.launch { prefs.reducedAnimations.collect { _reducedAnimations.value = it } }
        viewModelScope.launch { prefs.liquidGlass.collect { _liquidGlass.value = it } }
        viewModelScope.launch { prefs.classicProfileTabRow.collect { _classicProfileTabRow.value = it } }
        viewModelScope.launch { prefs.squareGridRounded.collect { _squareGridRounded.value = it } }
        viewModelScope.launch(Dispatchers.IO) { ensureProfileTabCacheHydrated() }
        viewModelScope.launch { prefs.pinterestThreeColumns.collect { _pinterestThreeColumns.value = it } }
        viewModelScope.launch { prefs.hateFunBlurNsfw.collect { _hateFunBlurNsfw.value = it } }
        viewModelScope.launch { prefs.followerScanCompleted.collect { _followerScanCompletedOnce.value = it } }
        viewModelScope.launch { prefs.liquidGlassIntensity.collect { _liquidGlassIntensity.value = it } }
        viewModelScope.launch { prefs.glassRimIntensity.collect { _glassRimIntensity.value = it } }
        viewModelScope.launch { prefs.glassRimVibrantSecondary.collect { _glassRimVibrantSecondary.value = it } }
        viewModelScope.launch { prefs.downloadOnLike.collect { _downloadOnLike.value = it } }
        viewModelScope.launch { prefs.e621FollowedArtists.collect { _e621FollowedArtists.value = it } }
        viewModelScope.launch { prefs.hideTextOnlyPosts.collect { _hideTextOnlyPosts.value = it } }
        viewModelScope.launch { prefs.translateEnabled.collect { _translationEnabled.value = it } }
        viewModelScope.launch { prefs.translateTargetLang.collect { _translationTargetLang.value = it } }
        viewModelScope.launch { prefs.customFontPath.collect { _customFontPath.value = it } }
        viewModelScope.launch { prefs.customFontName.collect { _customFontName.value = it } }
        viewModelScope.launch { prefs.subscribedReviewDids.collect { _subscribedReviewDids.value = it } }
        viewModelScope.launch { prefs.subscribedBlogDids.collect { _subscribedBlogDids.value = it } }
        // Bug fix (per feedback — Reviews/Blogs show their cached content
        // instantly on cold start, but Mutuals sits blank/reloads every
        // time): Reviews/Blogs' "instant snapshot from disk" step
        // (loadFriendsReviewsIfNeeded) is reached the moment the Hub page
        // itself composes — cheap, and effectively immediate. Mutuals'
        // equivalent snapshot step, inside loadDmConversationsBlocking, was
        // only ever reachable through ensureDmConversationsLoadedSuspend —
        // which is itself only called from cold-start warmup/preload paths
        // that run alongside, and have to actually get scheduled amid, the
        // six-plus *other* concurrent network calls cold start also kicks
        // off (loadFeed, loadAvailableFeeds, prefetchUserLists,
        // preloadFriendsFeed, loadSelfProfile — see startHubBackgroundWarmup's
        // own doc comment on that exact pile-up). The on-disk cache read
        // itself is a handful of milliseconds, but it had no path to run
        // before all of that. Reading it here — its own tiny, standalone
        // coroutine, launched first, before any of those network calls even
        // get kicked off below — decouples "show what's on disk" from all
        // of that entirely, so it can never be starved out by unrelated
        // network traffic the way it apparently was.
        // Starts on Main so it only runs once the ViewModel has finished
        // constructing (dmCacheGson is declared further down this file).
        viewModelScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) {
                if (_dmConversations.value.isEmpty()) {
                    runCatching {
                        val cached: List<DmConversation> = decodeDmCache(prefs.hubMutualsCacheJson.first())
                        if (cached.isNotEmpty()) _dmConversations.value = cached
                    }
                }
            }
        }
        loadHistoryFromPrefs()
        trackHistoryAutomatically()
        // Item 3 (rework): fills in _feedInteractionSupport for any feed the
        // person navigates to that loadAvailableFeeds()'s pinned/saved-feeds
        // batch fetch didn't already resolve (a feed opened from search, a
        // profile's own custom feed, etc.) — see that flow's own doc
        // comment above. Only fetches for feeds not already known (either
        // way), so this never re-fetches the same feed twice.
        viewModelScope.launch {
            _selectedFeedUri.collect { uri ->
                if (uri != null && uri.contains("app.bsky.feed.generator") &&
                    !_feedInteractionSupport.value.containsKey(uri) && bskyToken.isNotBlank()
                ) {
                    val info = bskyRepo.getFeedGeneratorInfo(bskyToken, uri).getOrNull()
                    _feedInteractionSupport.value = _feedInteractionSupport.value + (uri to (info?.acceptsInteractions ?: false))
                    info?.did?.let { generatorDid -> _feedGeneratorDid.value = _feedGeneratorDid.value + (uri to generatorDid) }
                }
            }
        }
        // Crash fix: Dispatchers.Main (not the default Main.immediate) so
        // this startup work always begins *after* the ViewModel has finished
        // constructing. When the saved login was already in memory, the
        // reads below returned instantly and the warmups kicked off here
        // reached fields declared further down this file (the DM mutex)
        // before they existed, crashing on launch.
        viewModelScope.launch(Dispatchers.Main) {
            val accessJwt    = prefs.bskyAccessJwt.first()
            val refreshJwt   = prefs.bskyRefreshJwt.first()
            val did          = prefs.bskyDid.first()
            val handle       = prefs.bskyHandle.first()
            val e621User     = prefs.e621Username.first()
            val e621Key      = prefs.e621ApiKey.first()
            val lastFeedUri  = prefs.lastFeedUri.first()
            val lastE621Tags = prefs.lastE621Tags.first()

            if (!lastE621Tags.isNullOrBlank()) _e621SearchTags.value = lastE621Tags
            _selectedFeedUri.value   = lastFeedUri
            // Add To's last tab: the synchronously-saved copy wins (see
            // ListRecency.lastTab); the DataStore one only fills in when
            // there isn't one yet.
            if (com.mediaviewer.util.ListRecency.lastTab == null) _lastPickerTab.value = prefs.lastPickerTab.first()
            _combineListsAndPacks.value = prefs.combineListsAndPacks.first()
            _autoAddToOnFollow.value = prefs.autoAddToOnFollow.first()

            if (!e621User.isNullOrBlank() && !e621Key.isNullOrBlank()) {
                e621Username = e621User; e621ApiKey = e621Key; _e621LoggedIn.value = true
            }
            if (!accessJwt.isNullOrBlank() && did != null && handle != null) {
                bskyToken = accessJwt; bskyRefreshToken = refreshJwt ?: ""
                _bskyDid.value = did; bskyHandle = handle; _bskyLoggedIn.value = true
                applyAccountSettings(did)
            }

            // Item 5: always default to the Hub in AT Protocol/Bluesky mode
            // on every cold start — regardless of which mode was last
            // active — rather than restoring lastMode's e621 session
            // automatically. This is also what was causing "closing the
            // app while in e621 mode, then reopening, loads forever": this
            // block used to auto-fire loadE621Posts() on cold start
            // whenever e621 was the last-used mode, and if the very first
            // e621 network call happens to fail without going through
            // Result.onFailure cleanly (or is otherwise slow), the person
            // is dropped into e621 mode with a spinner that has nothing
            // else queued to replace it — the Bluesky branch below, in
            // contrast, kicks off several independent warmup calls, so one
            // stalling doesn't leave the whole screen stuck. Not
            // auto-restoring e621 on launch sidesteps that entirely; e621
            // mode itself, once switched to manually via the Hub, is
            // unaffected — its credentials are still restored just above,
            // so that switch is still instant.
            //
            // lastMode/prefs.setLastMode still exist and still track
            // whichever mode is currently active (so mid-session mode
            // switches keep working the same as before) — this only
            // changes what happens on a *fresh app launch*.
            try {
                if (_bskyLoggedIn.value) {
                    _appMode.value = AppMode.BLUESKY
                    loadFeed()
                    loadAvailableFeeds()
                    prefetchUserLists()   // preload so list picker opens instantly
                    startHubBackgroundWarmup() // item 6/this session: Mutuals/Reviews/Livestreams, see its own comment
                    startDmLivePolling()
                    startInboxPolling()
                    preloadFriendsFeed()  // item 7: warm the From Friends feed in the background too
                    loadSelfProfile()     // Settings Update: warm the Profile button's avatar/banner preview
                }
            } finally {
                // Stay on SETTINGS (the Hub) either way — and always flip
                // this, even if one of the warmup calls above threw
                // synchronously, so the app can never get stuck on a
                // permanent loading state at startup.
                _appInitialized.value = true
            }
        }
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    fun loginBluesky(identifier: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            bskyRepo.login(identifier, password)
                .onSuccess { session ->
                    bskyToken        = session.accessJwt
                    bskyRefreshToken = session.refreshJwt
_bskyDid.value          = session.did
                    applyAccountSettings(session.did)
                    bskyHandle       = session.handle
                    prefs.saveBskySession(session.accessJwt, session.refreshJwt, session.did, session.handle)
                    _bskyLoggedIn.value = true
                    _appMode.value = AppMode.BLUESKY
                    prefs.setLastMode("BLUESKY")
                    _screenState.value = ScreenState.FEED
                    loadFeed()
                    loadAvailableFeeds()
                    prefetchUserLists()   // preload so list picker opens instantly
                    startHubBackgroundWarmup()
                    startDmLivePolling()
                    startInboxPolling()
                    loadSelfProfile()
                }
                .onFailure { _errorMessage.value = it.message ?: "Login failed" }
            _isLoading.value = false
        }
    }

    fun logoutBluesky() {
        // Signed in to more than one AT Protocol account: logging out of the
        // active one hands the session to the next account instead of
        // leaving the app signed out with accounts still sitting unused.
        val next = _otherBskyAccounts.value.firstOrNull()
        if (next != null) {
            switchBskyAccountInternal(next, keepOutgoing = false)
            return
        }
        viewModelScope.launch {
            prefs.clearBskySession()
            bskyToken = ""; bskyRefreshToken = ""; _bskyDid.value = ""; bskyHandle = ""
            applyAccountSettings("")
            _bskyLoggedIn.value = false
            _selfProfile.value = null
            // Bug fix (this session): none of this used to be reset on
            // logout, so a subsequent login within the same app process
            // (the ViewModel instance survives logout — it's only recreated
            // on a fresh process) would find reviewsBlogsLoaded/
            // liveFriendsLoaded/etc. still true from the PREVIOUS account and
            // treat the Hub as already warm, silently keeping the old
            // account's cached data around and never re-hydrating for the
            // new one. Also stops the DM polling loop rather than leaving it
            // running against a session that's no longer valid.
            reviewsBlogsLoaded = false
            _friendsReviewsLoading.value = false
            _friendsReviews.value = emptyList()
            _friendsBlogs.value = emptyList()
            _subscribedReviewDids.value = emptySet()
            _subscribedBlogDids.value = emptySet()
            liveFriendsLoaded = false
            _liveFriends.value = emptyList()
            blueskyLiveNowLoaded = false
            _blueskyLiveNow.value = emptyList()
            dmLivePollingJob?.cancel()
            dmLivePollingJob = null
            dmLogCursor = null
            inboxPollingJob?.cancel()
            inboxPollingJob = null
            _inboxUnreadCount.value = 0
            _inboxItems.value = emptyList()
            inboxRaw = emptyList(); inboxCursor = null; inboxPostCache.clear()
            _dmConversations.value = emptyList()
            _blockedAccounts.value = emptyList()
            com.mediaviewer.util.BlockedAccounts.clear()
            if (_appMode.value == AppMode.BLUESKY) {
                _mediaItems.value = emptyList()
                _screenState.value = ScreenState.SETTINGS
            }
        }
    }

    // ── Multiple AT Protocol accounts ────────────────────────────────────────
    // The active account is everything above (bskyToken/_bskyDid/bskyHandle +
    // the BSKY_* prefs). Every other signed-in account is parked in
    // PreferencesManager's "other accounts" list with its own session, and
    // switching swaps one for the other — see PreferencesManager.
    // switchActiveBskyAccount and RestartActivity for why the switch ends in
    // a full app restart.

    private val _otherBskyAccounts = MutableStateFlow<List<StoredBskyAccount>>(emptyList())
    val otherBskyAccounts: StateFlow<List<StoredBskyAccount>> = _otherBskyAccounts

    private val _showSwitchAccountsRow = MutableStateFlow(true)
    val showSwitchAccountsRow: StateFlow<Boolean> = _showSwitchAccountsRow

    private val _accountSwitching = MutableStateFlow(false)
    val accountSwitching: StateFlow<Boolean> = _accountSwitching

    // Declared right after the flows they fill (rather than inside the big
    // init{} far above) so the collectors can never run before the backing
    // fields exist.
    init {
        viewModelScope.launch { prefs.otherBskyAccounts.collect { _otherBskyAccounts.value = it } }
        viewModelScope.launch { prefs.showSwitchAccountsRow.collect { _showSwitchAccountsRow.value = it } }
    }

    fun setShowSwitchAccountsRow(enabled: Boolean) {
        _showSwitchAccountsRow.value = enabled
        viewModelScope.launch { prefs.setShowSwitchAccountsRow(enabled) }
    }

    /** Signs in to another AT Protocol account WITHOUT making it the active
     *  one. [onResult] is called on the main thread with null on success, or
     *  a message to show under the row on failure. */
    fun addBskyAccount(identifier: String, password: String, onResult: (String?) -> Unit) {
        val id = identifier.trim().removePrefix("@")
        if (id.isBlank() || password.isBlank()) { onResult("Enter a handle and app password"); return }
        viewModelScope.launch {
            val session = bskyRepo.login(id, password).getOrElse {
                onResult(it.message ?: "Login failed")
                return@launch
            }
            if (session.did == _bskyDid.value || _otherBskyAccounts.value.any { it.did == session.did }) {
                onResult("@${session.handle} is already added")
                return@launch
            }
            // Best-effort — only used to show the account nicely in lists.
            val profile = bskyRepo.getFullProfile(session.accessJwt, session.did).getOrNull()
            prefs.addOtherBskyAccount(
                StoredBskyAccount(
                    did = session.did,
                    handle = session.handle,
                    displayName = profile?.author?.displayName?.takeIf { it.isNotBlank() } ?: session.handle,
                    avatarUrl = profile?.author?.avatarUrl,
                    accessJwt = session.accessJwt,
                    refreshJwt = session.refreshJwt
                )
            )
            onResult(null)
        }
    }

    /** Signs an inactive account out. Nothing else is connected to an
     *  inactive account (no polling, no background jobs), so removing its
     *  stored session and parked state is the whole disconnect. */
    fun removeBskyAccount(did: String) {
        viewModelScope.launch { prefs.removeOtherBskyAccount(did) }
    }

    /** Makes [did] the active account and restarts the app. */
    /** Customize Hub's rows/order/list rows and Override App Colors are
     *  saved per account: points both at [did]'s own settings. */
    private fun applyAccountSettings(did: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            com.mediaviewer.util.HubLayout.setAccount(did)
            com.mediaviewer.util.UiToggles.setAccount(did)
        }
    }

    fun switchBskyAccount(did: String) {
        val target = _otherBskyAccounts.value.firstOrNull { it.did == did } ?: return
        switchBskyAccountInternal(target, keepOutgoing = true)
    }

    private fun switchBskyAccountInternal(target: StoredBskyAccount, keepOutgoing: Boolean) {
        if (_accountSwitching.value) return
        _accountSwitching.value = true
        viewModelScope.launch {
            // Refresh the target's session first: its access token has been
            // sitting unused and has almost certainly expired, and doing this
            // here means a dead session is reported now, instead of the app
            // restarting into a signed-out state.
            val refreshed = bskyRepo.refreshToken(target.refreshJwt).getOrNull()
            if (refreshed == null) {
                _errorMessage.value = "Couldn't sign in to @${target.handle}. Remove it and add it again."
                _accountSwitching.value = false
                return@launch
            }
            val freshTarget = target.copy(
                handle = refreshed.handle.ifBlank { target.handle },
                accessJwt = refreshed.accessJwt,
                refreshJwt = refreshed.refreshJwt
            )
            val outgoing = if (_bskyLoggedIn.value && _bskyDid.value.isNotBlank()) {
                val author = _selfProfile.value?.author
                StoredBskyAccount(
                    did = _bskyDid.value,
                    handle = bskyHandle,
                    displayName = author?.displayName?.takeIf { it.isNotBlank() } ?: bskyHandle,
                    avatarUrl = author?.avatarUrl ?: prefs.selfAvatarUrlCache.first(),
                    accessJwt = bskyToken,
                    refreshJwt = bskyRefreshToken
                )
            } else null
            prefs.switchActiveBskyAccount(outgoing, freshTarget, keepOutgoing)
            prefs.setLastMode("BLUESKY")
            // So the restarted app opens straight onto this account's own
            // Hub layout and colors.
            applyAccountSettings(freshTarget.did)
            // Everything is persisted (DataStore's edit{} returns only once
            // the write is on disk) — now cold-start into the new account.
            com.mediaviewer.util.Onboarding.skipNextOpenCount()
            platform.restartApp()
            // Only reached if the relaunch couldn't be started.
            _accountSwitching.value = false
        }
    }

    fun saveE621Credentials(username: String, apiKey: String) {
        if (username.isBlank() || apiKey.isBlank()) return
        viewModelScope.launch {
            e621Username = username
            e621ApiKey   = apiKey
            prefs.saveE621Credentials(username, apiKey)
            _e621LoggedIn.value = true
            // Signing in only connects the account — it deliberately does
            // NOT switch modes or open the feed. The e621 login page closes
            // itself and drops the person back on Settings, where the row's
            // button now reads "Log out"; e621 is reached from the Hub's
            // quick-access buttons as before.
        }
    }

    fun logoutE621() {
        viewModelScope.launch {
            prefs.clearE621Credentials()
            e621Username = ""; e621ApiKey = ""
            _e621LoggedIn.value = false
            if (_appMode.value == AppMode.E621) {
                _mediaItems.value = emptyList()
                _screenState.value = ScreenState.SETTINGS
            }
        }
    }

    // ── Feed Loading ──────────────────────────────────────────────────────────

    /** Attempts to refresh the Bluesky access token. Returns true if successful. */
    private suspend fun refreshBskyTokenIfPossible(): Boolean {
        if (bskyRefreshToken.isBlank()) return false
        val result = bskyRepo.refreshToken(bskyRefreshToken)
        return result.fold(
            onSuccess = { refreshed ->
                bskyToken        = refreshed.accessJwt
                bskyRefreshToken = refreshed.refreshJwt
                _bskyDid.value   = refreshed.did
                bskyHandle       = refreshed.handle
                prefs.saveBskySession(refreshed.accessJwt, refreshed.refreshJwt, refreshed.did, refreshed.handle)
                true
            },
            onFailure = {
                // Refresh token itself is dead — force re-login
                prefs.clearBskySession()
                _bskyLoggedIn.value = false
                _screenState.value = ScreenState.SETTINGS
                false
            }
        )
    }

    private fun isAuthError(message: String?): Boolean {
        if (message == null) return false
        return message.contains("400") || message.contains("401") || message.contains("ExpiredToken", true) || message.contains("InvalidToken", true)
    }

    /** Bug fix (this session): rate limiting (HTTP 429) used to just show
     *  "Feed 429: ..." and leave the main feed empty/stuck until the user
     *  manually retried — the primary fix for that is not repeating the
     *  request storm that was causing it in the first place (see
     *  BlueskyRepository.getSubscribedReviews's doc comment on pacing), but
     *  this adds a safety net on top: a single short delayed retry
     *  specifically for 429s, since even a well-behaved client can
     *  occasionally get rate limited by something outside its control
     *  (another device on the same account, a shared IP, etc.) and
     *  shouldn't need a manual pull-to-refresh to recover.
     */
    private fun isRateLimitError(message: String?): Boolean = message?.contains("429") == true

    fun loadFeed(reset: Boolean = true) {
        // Bug fix (Outstanding Issue #1 — diagnostic, temporary): see
        // setMode()'s matching Log.d for why this is here. Logs a stack
        // trace too since loadFeed() has many call sites and knowing which
        // one fired during a repro is the whole point.
        Log.d("Stellar-FeedState", "loadFeed(reset=$reset)")
        if (_appMode.value == AppMode.E621) { loadE621Posts(reset); return }
        if (!_bskyLoggedIn.value) return
        // A feed opened from a profile's Lists/Feeds tab: Refresh reloads
        // THAT feed (it isn't the selected Hub feed).
        if (reset && isProfileFeedActive()) { loadExternalFeed(reset = true); return }
        // Every reset starts a new "generation": a slower response for a
        // feed the person has already switched away from is dropped instead
        // of overwriting (or being appended to) the new feed.
        if (reset) feedLoadGeneration++
        val generation = feedLoadGeneration
        viewModelScope.launch(Dispatchers.IO) {
            if (reset) {
                _isLoading.value = true; feedCursor = null; _currentIndex.value = 0
                activeFeedMode = ActiveFeedMode.NORMAL; activeFeedActorDid = null
                _authorFeedState.value = null   // clear any saved overlay state
            }
            if (isLoadingMore && !reset) return@launch
            isLoadingMore = true

            suspend fun attempt(): Result<Pair<List<MediaItem>, String?>> {
                val feedUri = _selectedFeedUri.value
                // The pinned "Following" entry is a synthetic stand-in (it isn't a real
                // feed generator), so it's served by getTimeline just like the no-selection case.
                // A smaller first page comes back (and parses) noticeably
                // faster; the next page is fetched right behind it.
                val limit = if (reset) 30 else 50
                return if (feedUri == null || feedUri == BlueskyRepository.FOLLOWING_FEED_URI)
                    bskyRepo.getTimeline(bskyToken, feedCursor, limit)
                // A list pinned as a feed: its members' original posts only
                // (no reposts, no replies), newest first.
                else if (com.mediaviewer.util.StellarOfficial.isListUri(feedUri))
                    bskyRepo.getListFeedOriginals(bskyToken, _bskyDid.value, feedUri, feedCursor)
                else bskyRepo.getFeed(bskyToken, feedUri, feedCursor, limit)
            }

            var result = attempt()
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = attempt()
            }
            if (result.isFailure && isRateLimitError(result.exceptionOrNull()?.message)) {
                delay(4000)
                result = attempt()
            }
            if (generation != feedLoadGeneration) {
                // Superseded by a newer feed load — that one owns the flags.
                if (!reset) isLoadingMore = false
                return@launch
            }
            result.onSuccess { (items, cursor) ->
                feedCursor = cursor
                _mediaItems.value = if (reset) filterHidden(items) else _mediaItems.value + filterHidden(items)
            }.onFailure { _errorMessage.value = it.message }
            _isLoading.value = false
            isLoadingMore = false
            // Get the second page ready before it's needed.
            if (reset && result.isSuccess && feedCursor != null) {
                delay(600)
                if (generation == feedLoadGeneration && activeFeedMode == ActiveFeedMode.NORMAL) loadFeed(reset = false)
            }
        }
    }

    /** Settings Update: "Hide Text Only Posts" — universally drops posts with
     *  no image/video from every feed this app renders. Applied at each fetch
     *  site (rather than as a post-hoc filter on [_mediaItems]) so pagination
     *  cursors and currentIndex math never have to account for hidden items. */
    private fun filterHidden(items: List<MediaItem>): List<MediaItem> {
        // Blocked either way: never in any feed (a post you block from while
        // viewing it stays on screen as "Blocked" so it can be undone — this
        // only drops them from what gets loaded).
        val unblocked = items.filterNot {
            com.mediaviewer.util.BlockedAccounts.isHidden(it.author.did) || com.mediaviewer.util.BlockedAccounts.isHidden(it.sentByAuthor?.did)
        }
        return if (_hideTextOnlyPosts.value) unblocked.filterNot { it.isTextOnly } else unblocked
    }

    fun loadMore() {
        if (feedCursor == null || isLoadingMore) return
        when (activeFeedMode) {
            ActiveFeedMode.NORMAL  -> loadFeed(reset = false)
            ActiveFeedMode.AUTHOR  -> loadMoreAuthorFeed()
            ActiveFeedMode.LIKES   -> loadMoreLikes()
            ActiveFeedMode.SAVES   -> loadMoreSaves()
            ActiveFeedMode.FRIENDS, ActiveFeedMode.HISTORY -> { /* fully loaded up front, no further pagination */ }
            ActiveFeedMode.EXTERNAL -> loadExternalFeed(reset = false)
        }
    }

    private fun loadMoreAuthorFeed() {
        val did = activeFeedActorDid ?: return
        viewModelScope.launch(Dispatchers.IO) {
            isLoadingMore = true
            var result = bskyRepo.getAuthorFeed(bskyToken, did, feedCursor)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getAuthorFeed(bskyToken, did, feedCursor)
            }
            result.onSuccess { (items, cursor) ->
                feedCursor = cursor
                _mediaItems.value = _mediaItems.value + filterHidden(items)
            }.onFailure { _errorMessage.value = it.message }
            isLoadingMore = false
        }
    }

    private fun loadMoreLikes() {
        val did = activeFeedActorDid ?: _bskyDid.value
        viewModelScope.launch(Dispatchers.IO) {
            isLoadingMore = true
            var result = bskyRepo.getActorLikes(bskyToken, did, feedCursor)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getActorLikes(bskyToken, did, feedCursor)
            }
            result.onSuccess { (items, cursor) ->
                feedCursor = cursor
                _mediaItems.value = _mediaItems.value + filterHidden(items)
            }.onFailure { _errorMessage.value = it.message }
            isLoadingMore = false
        }
    }

    fun loadAvailableFeeds() {
        if (!_bskyLoggedIn.value) return
        viewModelScope.launch(Dispatchers.IO) {
            // iOS: adult content follows the account's own Bluesky setting
            // (turned on at bsky.app) — see AdultContentPolicy.
            if (com.mediaviewer.util.AdultContentPolicy.appliesHere) {
                bskyRepo.getAdultContentEnabled(bskyToken).onSuccess { com.mediaviewer.util.AdultContentPolicy.update(it) }
            }
            var result = bskyRepo.getSavedFeeds(bskyToken, _bskyDid.value)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getSavedFeeds(bskyToken, _bskyDid.value)
            }
            result.onSuccess { feeds ->
                _availableFeeds.value = feeds
                _feedInteractionSupport.value = _feedInteractionSupport.value + feeds.associate { it.uri to it.acceptsInteractions }
                _feedGeneratorDid.value = _feedGeneratorDid.value + feeds.mapNotNull { f -> f.generatorDid?.let { f.uri to it } }
                // Bug fix (Outstanding Issue #1 — feed loses scroll position
                // navigating Hub pages): this auto-select-a-default-feed
                // fallback is only meant to cover the genuine "nothing has
                // ever been selected" case (fresh login, or a user who's
                // always been on the implicit null-URI "Following" timeline
                // and never explicitly picked a saved feed). But
                // loadAvailableFeeds() itself gets called again every single
                // time setMode() switches back into BLUESKY — including
                // right after it just restored a cached feed snapshot — and
                // a user sitting on that implicit null-URI "Following"
                // timeline (a legitimate, common, ongoing state — see
                // loadFeed()'s attempt(), which treats null the same as the
                // pinned Following entry) would have `_selectedFeedUri.value
                // == null` every single time this re-runs, so this branch
                // would fire selectFeed() -> loadFeed(reset = true) and blow
                // away the just-restored scroll position on every Hub
                // round-trip. That's confirmed as one real, concrete cause
                // of this bug (may not be the only one — see the
                // diagnostic Log.d calls in loadFeed()/loadE621Posts()/
                // setMode() below if this doesn't fully resolve it).
                // Guarded with hasAutoSelectedFeed so it can only ever fire
                // once per process, exactly like it already only mattered
                // once before this bug existed.
                if (!hasAutoSelectedFeed && _selectedFeedUri.value == null && _authorFeedState.value == null && feeds.isNotEmpty()) {
                    hasAutoSelectedFeed = true
                    selectFeed(feeds.first().uri)
                }
            }
            // Deliberately no onFailure -> _errorMessage here. This just populates the
            // feed-switcher chip row in the background; actual feed content is loaded
            // independently by loadFeed() and doesn't depend on this call succeeding.
            // Surfacing an error banner for a failed background prefetch — when
            // everything the user can actually see is working fine — does more harm than good.
        }
    }
    // Bug fix: see loadAvailableFeeds()'s doc comment above.
    private var hasAutoSelectedFeed = false

    /** Opens an author's posts as an overlay, saving current feed state to restore later. */
    fun showAuthorFeed(item: MediaItem) {
        if (_appMode.value == AppMode.E621) { searchSingleTag(item.author.handle); return }
        if (!_bskyLoggedIn.value) return
        val did = item.author.did
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            var result = bskyRepo.getAuthorFeed(bskyToken, did)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getAuthorFeed(bskyToken, did)
            }
            result.onSuccess { (items, cursor) ->
                // Save what we were looking at before opening the author feed
                if (_authorFeedState.value == null) {
                    _authorFeedState.value = AuthorFeedSavedState(
                        author       = item.author,
                        items        = _mediaItems.value,
                        currentIndex = _currentIndex.value,
                        cursor       = feedCursor,
                        feedUri      = _selectedFeedUri.value
                    )
                } else {
                    // Already in an author feed — update author but keep original saved state
                    _authorFeedState.value = _authorFeedState.value!!.copy(author = item.author)
                }
                feedCursor = cursor
                activeFeedMode = ActiveFeedMode.AUTHOR
                activeFeedActorDid = did
                _mediaItems.value = filterHidden(items)
                _currentIndex.value = 0
                _screenState.value = ScreenState.FEED
            }.onFailure { _errorMessage.value = it.message }
            _isLoading.value = false
        }
    }

    // ── Profile Overlay ───────────────────────────────────────────────────────

    /** Opens the full-screen Profile Overlay for [author]. Kicks off the full
     *  profile fetch, the default (Posts) tab, and background probes for
     *  Leaflet blogs / Popfeed reviews so those tabs only appear once we know
     *  the account actually has content in them. */
    // Feature (this session): lets a caller that already has a specific
    // review in hand (the Hub's Mutual Reviews cards) jump straight to that
    // review's detail overlay, layered on top of the profile it belongs to
    // — the exact same visual result as opening the profile normally,
    // going to its Reviews tab, and tapping that review, just in one step.
    fun openProfile(
        author: AuthorInfo, initialTab: ProfileTab = ProfileTab.POSTS, review: PopfeedReview? = null, blog: LeafletBlog? = null,
        title: TitleSearchResult? = null, preselectedReview: FriendPopfeedReview? = null
    ) {
        if (!_bskyLoggedIn.value) return
        // Item 8: haptic tap every time a profile actually opens, regardless
        // of which of the many screens (feed, search, DMs, notifications,
        // comments...) triggered it — centralized here instead of at every
        // individual call site.
        tapHaptic()
        // Add To on this profile opens with its + / − already known.
        if (author.did != _bskyDid.value) prefetchListMemberships(author.did)
        // Item 17: don't clobber a profile that's already open (visible or
        // hidden behind a post pager) — chain onto it via `parent` so
        // closeProfile() can unwind back through it instead of losing it.
        val parent = _profileOverlay.value
        // Feature request #4: seed instantly from whatever's cached on disk
        // for this exact profile (see cachedProfileTabsFor's doc comment) —
        // the network probes below still run unconditionally right after,
        // exactly as if nothing were cached, and will replace/correct
        // anything shown here once they resolve.
        val cached = cachedProfileTabsFor(author.did)
        val seededAvailableTabs = setOf(ProfileTab.POSTS, ProfileTab.REPOSTS, ProfileTab.LIKES, ProfileTab.LISTS_FEEDS) +
            (cached?.availableTabs?.mapNotNull { name -> runCatching { ProfileTab.valueOf(name) }.getOrNull() } ?: emptyList())
        val seededTabStates = buildMap {
            if (cached != null) {
                if (cached.posts.isNotEmpty()) put(ProfileTab.POSTS, ProfileTabState(items = cached.posts, loaded = true))
                if (cached.reposts.isNotEmpty()) put(ProfileTab.REPOSTS, ProfileTabState(items = cached.reposts, loaded = true))
                if (cached.likes.isNotEmpty()) put(ProfileTab.LIKES, ProfileTabState(items = cached.likes, loaded = true))
                if (cached.blogs.isNotEmpty()) put(ProfileTab.BLOGS, ProfileTabState(blogs = cached.blogs, loaded = true))
                if (cached.reviews.isNotEmpty()) put(ProfileTab.REVIEWS, ProfileTabState(reviews = cached.reviews, loaded = true))
                if (cached.backlog.isNotEmpty()) put(ProfileTab.BACKLOG, ProfileTabState(backlog = cached.backlog, loaded = true))
                if (cached.vods.isNotEmpty()) put(ProfileTab.VODS, ProfileTabState(vods = cached.vods, loaded = true))
            }
        }
        _profileOverlay.value = ProfileOverlayState(
            author = author, selectedTab = initialTab, parent = parent, openReview = review, openBlog = blog,
            openTitle = title, openTitlePreselectedReview = preselectedReview,
            availableTabs = seededAvailableTabs, tabStates = seededTabStates,
            // Fix (per feedback): seed the sub-filter memory from the
            // on-disk cache so subtab strips render instantly.
            seededSubtabs = cached?.subtabs?.mapNotNull { (tabName, subNames) ->
                runCatching { ProfileTab.valueOf(tabName) }.getOrNull()?.let { it to subNames }
            }?.toMap() ?: emptyMap()
        )

        launchProfileLoads(author, initialTab)
    }

    /** Every network load a profile overlay needs — the full profile, the
     *  selected [tab]'s first page, and the Blogs/Reviews/Backlog/Vods/Music
     *  probes. Shared by [openProfile] (fresh open) and [refreshProfile]
     *  (reload in place). [isRefresh] skips the passive auto-subscribe, which
     *  only makes sense the first time a profile is opened; [onProfileFetched]
     *  fires once the full-profile request has settled either way. */
    private fun launchProfileLoads(
        author: AuthorInfo, tab: ProfileTab,
        isRefresh: Boolean = false, onProfileFetched: () -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var result = bskyRepo.getFullProfile(bskyToken, author.did)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getFullProfile(bskyToken, author.did)
            }
            // Guarded on the same did so a slow request can never stamp
            // this profile's data onto a different overlay that has since
            // replaced it.
            val curNow = _profileOverlay.value
            if (curNow != null && curNow.author.did == author.did) {
                result.onSuccess { data ->
                    _profileOverlay.value = curNow.copy(profile = data, loadingProfile = false, author = data.author)
                }.onFailure {
                    _profileOverlay.value = curNow.copy(loadingProfile = false)
                }
            }
            onProfileFetched()
        }

        loadProfileTab(tab, reset = true)

        viewModelScope.launch(Dispatchers.IO) {
            val blogs = runCatching { bskyRepo.getLeafletBlogs(author.did, probeConcurrently = true) }.getOrDefault(emptyList())
            val cur = _profileOverlay.value?.takeIf { it.author.did == author.did } ?: return@launch
            if (blogs.isEmpty()) {
                // Feature request #4 (reconcile): drop a cached-but-now-empty
                // tab instead of leaving it stuck on stale cached content —
                // e.g. the account's one blog got deleted since it was last
                // cached.
                if (ProfileTab.BLOGS in cur.availableTabs) {
                    val updated = cur.copy(availableTabs = cur.availableTabs - ProfileTab.BLOGS, tabStates = cur.tabStates - ProfileTab.BLOGS)
                    _profileOverlay.value = updated
                    persistProfileTabCache(updated)
                }
                return@launch
            }
            val updated = cur.copy(
                availableTabs = cur.availableTabs + ProfileTab.BLOGS,
                tabStates = cur.tabStates + (ProfileTab.BLOGS to ProfileTabState(blogs = blogs, loaded = true))
            )
            _profileOverlay.value = updated
            persistProfileTabCache(updated)
            if (!isRefresh) maybeAutoSubscribeOnProfileOpen(cur.author, cur.author.isFollowing || cur.author.did == _bskyDid.value, hasReviews = false, hasBlogs = true)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val reviews = runCatching { bskyRepo.getPopfeedReviews(author.did, probeConcurrently = true) }.getOrDefault(emptyList())
            val cur = _profileOverlay.value?.takeIf { it.author.did == author.did } ?: return@launch
            if (reviews.isEmpty()) {
                if (ProfileTab.REVIEWS in cur.availableTabs) {
                    val updated = cur.copy(availableTabs = cur.availableTabs - ProfileTab.REVIEWS, tabStates = cur.tabStates - ProfileTab.REVIEWS)
                    _profileOverlay.value = updated
                    persistProfileTabCache(updated)
                }
                return@launch
            }
            val updated = cur.copy(
                availableTabs = cur.availableTabs + ProfileTab.REVIEWS,
                tabStates = cur.tabStates + (ProfileTab.REVIEWS to ProfileTabState(reviews = reviews, loaded = true))
            )
            _profileOverlay.value = updated
            persistProfileTabCache(updated)
            if (!isRefresh) maybeAutoSubscribeOnProfileOpen(cur.author, cur.author.isFollowing || cur.author.did == _bskyDid.value, hasReviews = true, hasBlogs = false)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val backlog = runCatching { bskyRepo.getPopfeedBacklog(author.did) }.getOrDefault(emptyList())
            val cur = _profileOverlay.value?.takeIf { it.author.did == author.did } ?: return@launch
            if (backlog.isEmpty()) {
                if (ProfileTab.BACKLOG in cur.availableTabs) {
                    val updated = cur.copy(availableTabs = cur.availableTabs - ProfileTab.BACKLOG, tabStates = cur.tabStates - ProfileTab.BACKLOG)
                    _profileOverlay.value = updated
                    persistProfileTabCache(updated)
                }
                return@launch
            }
            val updated = cur.copy(
                availableTabs = cur.availableTabs + ProfileTab.BACKLOG,
                tabStates = cur.tabStates + (ProfileTab.BACKLOG to ProfileTabState(backlog = backlog, loaded = true))
            )
            _profileOverlay.value = updated
            persistProfileTabCache(updated)
        }
        // Item 19: VODs tab, only shown once we actually find any — most
        // accounts won't have Streamplace VODs, and that's a normal empty
        // result, not an error, so we stay silent on failure/empty here.
        viewModelScope.launch(Dispatchers.IO) {
            val vods = streamplaceRepo.getVods(author.handle).getOrNull()?.first ?: emptyList()
            val cur = _profileOverlay.value?.takeIf { it.author.did == author.did } ?: return@launch
            if (vods.isEmpty()) {
                if (ProfileTab.VODS in cur.availableTabs) {
                    val updated = cur.copy(availableTabs = cur.availableTabs - ProfileTab.VODS, tabStates = cur.tabStates - ProfileTab.VODS)
                    _profileOverlay.value = updated
                    persistProfileTabCache(updated)
                }
                return@launch
            }
            val updated = cur.copy(
                availableTabs = cur.availableTabs + ProfileTab.VODS,
                tabStates = cur.tabStates + (ProfileTab.VODS to ProfileTabState(vods = vods, loaded = true))
            )
            _profileOverlay.value = updated
            persistProfileTabCache(updated)
        }
        // Item 16/7: Rocksky "Music History" tab, only shown once we actually
        // find any scrobbles — most accounts won't have Rocksky connected,
        // and that's a normal empty result, not an error. Paged the same
        // way Posts/Reposts/Likes are — this seeds just the first chunk;
        // see loadMoreMusicHistory for how scrolling pages in the rest.
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = rockskyRepo.getScrobbles(author.did, limit = MUSIC_HISTORY_PAGE_SIZE, offset = 0).getOrNull() ?: emptyList()
            val cur = _profileOverlay.value?.takeIf { it.author.did == author.did } ?: return@launch
            if (tracks.isEmpty()) {
                if (ProfileTab.MUSIC_HISTORY in cur.availableTabs) {
                    val updated = cur.copy(availableTabs = cur.availableTabs - ProfileTab.MUSIC_HISTORY, tabStates = cur.tabStates - ProfileTab.MUSIC_HISTORY)
                    _profileOverlay.value = updated
                    // Feature request #4 (reconcile): same pattern as the
                    // Blogs probe above — write the removal back to the
                    // on-disk cache, so a stale seeded MUSIC_HISTORY tab
                    // doesn't flicker back in on the next open before the
                    // fresh probe runs again.
                    persistProfileTabCache(updated)
                }
                return@launch
            }
            // Rocksky's actor endpoint doesn't hand back an explicit
            // "there's more" cursor of its own (see RockskyApi's doc
            // comment) — getting back fewer tracks than were actually
            // asked for is what signals we've reached the end of this
            // person's history; anything else means there's at least one
            // more page to page in.
            val nextCursor = if (tracks.size < MUSIC_HISTORY_PAGE_SIZE) null else MUSIC_HISTORY_PAGE_SIZE.toString()
            val updated = cur.copy(
                availableTabs = cur.availableTabs + ProfileTab.MUSIC_HISTORY,
                tabStates = cur.tabStates + (ProfileTab.MUSIC_HISTORY to ProfileTabState(musicHistory = tracks, cursor = nextCursor, loaded = true))
            )
            _profileOverlay.value = updated
        }
        // Item 16: "Listening to ..." bio line — this profile's live
        // now-playing state, if any (most accounts have nothing playing, or
        // no Rocksky connection at all — both are a normal null result).
        nowPlayingJob?.cancel()
        nowPlayingJob = viewModelScope.launch(Dispatchers.IO) {
            // Official now-playing first. Otherwise (the usual case) the
            // latest scrobble counts as "Listening to" for 5 minutes from
            // when it started (see RockskyRepository.inferNowPlaying). While
            // the profile stays open the history is re-checked every 30s, so
            // a newer song replaces it and the status clears on time.
            val official = rockskyRepo.getNowPlaying(author.did)
            if (official != null) {
                _profileOverlay.value?.takeIf { it.author.did == author.did }?.let { _profileOverlay.value = it.copy(nowPlaying = official) }
                return@launch
            }
            var checks = 0
            while (true) {
                val inferred = rockskyRepo.inferNowPlaying(author.did)
                val cur = _profileOverlay.value?.takeIf { it.author.did == author.did } ?: return@launch
                if (cur.nowPlaying != inferred.track) {
                    // A new song: show it at the top of the Music History tab
                    // too, from the scrobble we just read — no extra request.
                    val t = inferred.track
                    val history = cur.tabStates[ProfileTab.MUSIC_HISTORY]
                    val newTabStates = if (t != null && history != null && history.loaded &&
                        history.musicHistory.none { it.uri.isNotBlank() && it.uri == t.uri }
                    ) {
                        cur.tabStates + (ProfileTab.MUSIC_HISTORY to history.copy(musicHistory = listOf(t.copy(endsAtMs = 0L)) + history.musicHistory))
                    } else cur.tabStates
                    _profileOverlay.value = cur.copy(nowPlaying = t, tabStates = newTabStates)
                }
                // No Music History at all: nothing to keep watching.
                if (!inferred.hasHistory || ++checks > 240) return@launch
                val endsAt = inferred.track?.endsAtMs ?: 0L
                val wait = if (endsAt > 0L) (endsAt - com.mediaviewer.platform.currentTimeMillis()).coerceIn(1_000L, 30_000L) else 30_000L
                delay(wait)
                if (_profileOverlay.value?.author?.did != author.did) return@launch
            }
        }
    }

    /** The open profile's "Listening to" watcher (one at a time). */
    private var nowPlayingJob: kotlinx.coroutines.Job? = null

    /** Profile page "Refresh" button: re-fetches the open profile in place —
     *  header/counts, the selected tab, and the tab probes — without
     *  touching the overlay stack, the selected tab/sub-filter, or scroll
     *  position. Existing content stays on screen until fresh data replaces
     *  it (a failed reload keeps what was already there).
     *  [ProfileOverlayState.refreshing] drives the button's spinner and is
     *  cleared once the profile request and the current tab's first page
     *  have settled (or after a 20s safety timeout). */
    fun refreshProfile() {
        val cur = _profileOverlay.value ?: return
        if (cur.refreshing || cur.hidden) return
        tapHaptic()
        val did = cur.author.did
        _profileOverlay.value = cur.copy(refreshing = true)
        if (cur.selectedTab == ProfileTab.LISTS_FEEDS) loadProfileLists(force = true)
        val profileDone = kotlinx.coroutines.CompletableDeferred<Unit>()
        launchProfileLoads(cur.author, cur.selectedTab, isRefresh = true) { profileDone.complete(Unit) }
        viewModelScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(20_000) {
                profileDone.await()
                _profileOverlay.first { o -> o == null || o.author.did != did || o.tabStates[o.selectedTab]?.loading != true }
            }
            _profileOverlay.value?.takeIf { it.author.did == did }?.let { _profileOverlay.value = it.copy(refreshing = false) }
        }
    }

    // Item 24: the blog-detail popup and the profile it sits on top of are
    // both full-screen overlays with their own X button in roughly the same
    // top-left spot, so closeProfile() needs to step down one layer at a
    // time — close whatever sub-overlay (blog/review) is open first — rather
    // than always tearing down the whole profile in one shot.
    fun closeProfile() {
        val cur = _profileOverlay.value ?: return
        if (cur.standaloneDetail && (cur.openBlog != null || cur.openTitle != null || cur.openReview != null)) {
            closeStandaloneDetail(); return
        }
        when {
            cur.openBlog != null -> { _profileOverlay.value = cur.copy(openBlog = null); return }
            cur.openReview != null -> { _profileOverlay.value = cur.copy(openReview = null); return }
            cur.openTitle != null -> { _profileOverlay.value = cur.copy(openTitle = null, openTitlePreselectedReview = null); return }
        }
        // Item 17: walk past any *hidden* ancestors in the parent chain —
        // those only exist as scaffolding behind the post pager (see
        // openPostFromProfileTab) and were never meant to be resurfaced by
        // tapping X, only by pinching back in. If we pass through one, the
        // whole post-pager detour is stale, so restore the true saved main/
        // author feed underneath instead of stopping on a stray
        // intermediate layer. A plain profile-on-profile stack (no hidden
        // ancestor involved) just pops back one level normally.
        var ancestor = cur.parent
        var passedHidden = false
        while (ancestor?.hidden == true) {
            passedHidden = true
            ancestor = ancestor.parent
        }
        _profileOverlay.value = ancestor
        // Bug fix (item 5): passedHidden only catches a hidden *ancestor* in
        // the parent chain, so it missed the far more common case — open a
        // profile, tap into a post (openPostFromProfileTab hides this same
        // overlay and stashes the pre-profile main feed in
        // _authorFeedState), pinch back in (pinchInFromPost un-hides that
        // very overlay, so it's no longer "hidden" by the time closeProfile
        // runs), then close directly from there. `ancestor` in that case is
        // just this profile's own parent (often null/the real main feed
        // already), so passedHidden never trips — but the pager's
        // author-feed detour is still sitting underneath, unrestored, so
        // the person landed back on the single post they'd pinched back
        // from instead of the feed they were on before opening the profile
        // at all. Checking whether *this* profile (the one actually being
        // closed) is still the pager's target covers that case too.
        val stillHasPagerContext = activeFeedMode == ActiveFeedMode.AUTHOR && activeFeedActorDid == cur.author.did
        if (passedHidden || stillHasPagerContext) restoreSavedMainFeed()
    }

    /** Discards any post-pager context reached via a profile's grid (see
     *  openPostFromProfileTab) and restores the saved main/author feed
     *  exactly as it was — the same restore path selectFeedFromAnyContext()
     *  uses when the user picks the same feed they left. Used by
     *  closeProfile() when unwinding a profile stack rooted in a hidden
     *  profile — see item 17. */
    private fun restoreSavedMainFeed() {
        val saved = _authorFeedState.value ?: return
        _authorFeedState.value = null
        activeFeedMode = ActiveFeedMode.NORMAL
        activeFeedActorDid = null
        _mediaItems.value = saved.items
        _currentIndex.value = saved.currentIndex
        feedCursor = saved.cursor
        _selectedFeedUri.value = saved.feedUri
    }

    fun selectProfileTab(tab: ProfileTab) {
        val cur = _profileOverlay.value ?: return
        if (tab !in cur.availableTabs) return
        // Adjustment #7: switching to a *new* main tab still starts that
        // tab's sub-filter fresh at ALL (matching the old always-reset
        // behavior) — it's only the parent-chain restore path (going back
        // to a profile that's already mid-browse) that's supposed to leave
        // postKindFilter/reviewKindFilter alone; see ProfileOverlayState's
        // own doc comment on these two fields.
        _profileOverlay.value = cur.copy(selectedTab = tab, postKindFilter = PostKindFilter.ALL, reviewKindFilter = ReviewKindFilter.ALL, musicYear = 0)
        val state = cur.tabStates[tab]
        if (state == null || (!state.loaded && !state.loading)) loadProfileTab(tab, reset = true)
        if (tab == ProfileTab.MUSIC_HISTORY) loadMusicYears(cur.author.did)
    }

    /** Lists/Feeds → a sub-tab (null = All). */
    fun selectProfileListKind(kind: ProfileListKind?) {
        val cur = _profileOverlay.value ?: return
        _profileOverlay.value = cur.copy(listKindFilter = kind)
    }

    /** Music History → a sub-tab: 0 = Recent, else "Top <year>". */
    fun selectMusicYear(year: Int) {
        val cur = _profileOverlay.value ?: return
        _profileOverlay.value = cur.copy(musicYear = year)
        if (year != 0 && year !in cur.musicWrapped) loadMusicYear(cur.author.did, year)
    }

    /** Recently fetched Top-year data per DID, kept for half an hour (the
     *  same time Rocksky itself caches a Wrapped for), so reopening a
     *  profile shows its Top tabs instantly. */
    private data class MusicYearsCache(val years: List<Int>, val wrapped: Map<Int, com.mediaviewer.model.RockskyWrapped>, val at: Long)
    private val musicYearsCache = com.mediaviewer.platform.ConcurrentHashMap<String, MusicYearsCache>()
    private val musicYearsLoading = com.mediaviewer.platform.concurrentSetOf<String>()

    private fun applyMusicYears(did: String, transform: (ProfileOverlayState) -> ProfileOverlayState) {
        val cur = _profileOverlay.value?.takeIf { it.author.did == did } ?: return
        _profileOverlay.value = transform(cur)
    }

    /** Finds which years [did] has Music History in (one "Top <year>"
     *  sub-tab each) by loading each candidate year's Wrapped from Rocksky,
     *  a few at a time, newest first; a year with no plays gets no tab.
     *  The Wrapped data is kept, so opening a Top tab afterwards is instant. */
    private fun loadMusicYears(did: String) {
        val cached = musicYearsCache[did]?.takeIf { com.mediaviewer.platform.currentTimeMillis() - it.at < 30 * 60_000L }
        if (cached != null) {
            applyMusicYears(did) { it.copy(musicYears = cached.years, musicWrapped = cached.wrapped + it.musicWrapped) }
            return
        }
        if (!musicYearsLoading.add(did)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val candidates = rockskyRepo.getCandidateYears(did)
                val gate = Semaphore(3)
                val found = com.mediaviewer.platform.ConcurrentHashMap<Int, com.mediaviewer.model.RockskyWrapped>()
                coroutineScope {
                    candidates.map { year ->
                        async {
                            val w = gate.withPermit { rockskyRepo.getWrapped(did, year).getOrNull() } ?: return@async
                            if (w.totalScrobbles <= 0L) return@async
                            found[year] = w
                            withContext(Dispatchers.Main) {
                                applyMusicYears(did) { st ->
                                    st.copy(
                                        musicYears = (st.musicYears + year).distinct().sortedDescending(),
                                        musicWrapped = st.musicWrapped + (year to w)
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
                if (found.isNotEmpty()) {
                    musicYearsCache[did] = MusicYearsCache(found.keys.sortedDescending(), HashMap(found), com.mediaviewer.platform.currentTimeMillis())
                }
            } finally {
                musicYearsLoading.remove(did)
            }
        }
    }

    /** One Top-year tab's data, if it isn't loaded yet (e.g. it failed the
     *  first time round). */
    private fun loadMusicYear(did: String, year: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val w = rockskyRepo.getWrapped(did, year).getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                applyMusicYears(did) { it.copy(musicWrapped = it.musicWrapped + (year to w)) }
            }
        }
    }

    // Adjustment #7: see ProfileOverlayState.postKindFilter/reviewKindFilter's
    // own doc comment — these used to live purely in ProfileOverlay's local
    // Compose state (reset every time selectedTab changed), which meant a
    // parent-chain restore (see ProfileOverlayState.parent) lost whichever
    // sub-tab had been active. Promoting them into the state object that
    // `parent` snapshots fixes that.
    fun selectPostKindFilter(filter: PostKindFilter) {
        val cur = _profileOverlay.value ?: return
        _profileOverlay.value = cur.copy(postKindFilter = filter)
    }

    fun selectReviewKindFilter(filter: ReviewKindFilter) {
        val cur = _profileOverlay.value ?: return
        _profileOverlay.value = cur.copy(reviewKindFilter = filter)
    }

    fun loadMoreProfileTab() {
        val cur = _profileOverlay.value ?: return
        val state = cur.tabStates[cur.selectedTab] ?: return
        if (state.loading || state.cursor == null) return
        // Item 7: Music History pages RockskyTrack, not MediaItem, so it
        // can't share loadProfileTab's fetchPage below (which is typed
        // around MediaItem) — same cursor-in-ProfileTabState mechanism,
        // just its own fetch.
        if (cur.selectedTab == ProfileTab.MUSIC_HISTORY) loadMoreMusicHistory(cur.author.did, state.cursor)
        else loadProfileTab(cur.selectedTab, reset = false)
    }

    /** Item 7: Music History's own "load the next chunk" — see the doc
     *  comment on the initial-page probe in openProfile() for why
     *  [offsetStr] (stashed in ProfileTabState.cursor, same field every
     *  other paged tab uses for its own cursor) is really just the next
     *  offset rather than an opaque server-issued cursor. */
    private fun loadMoreMusicHistory(did: String, offsetStr: String?) {
        val offset = offsetStr?.toIntOrNull() ?: return
        val cur = _profileOverlay.value?.takeIf { it.author.did == did } ?: return
        val existing = cur.tabStates[ProfileTab.MUSIC_HISTORY] ?: return
        if (existing.loading) return
        _profileOverlay.value = cur.copy(tabStates = cur.tabStates + (ProfileTab.MUSIC_HISTORY to existing.copy(loading = true)))
        viewModelScope.launch(Dispatchers.IO) {
            val page = rockskyRepo.getScrobbles(did, limit = MUSIC_HISTORY_PAGE_SIZE, offset = offset).getOrNull()
            val cur2 = _profileOverlay.value?.takeIf { it.author.did == did } ?: return@launch
            val existing2 = cur2.tabStates[ProfileTab.MUSIC_HISTORY] ?: return@launch
            if (page == null) {
                // A failed page: stop loading, but keep the same cursor so
                // the next scroll-triggered attempt just retries it rather
                // than silently giving up on the rest of the history.
                _profileOverlay.value = cur2.copy(tabStates = cur2.tabStates + (ProfileTab.MUSIC_HISTORY to existing2.copy(loading = false)))
                return@launch
            }
            val nextCursor = if (page.size < MUSIC_HISTORY_PAGE_SIZE) null else (offset + MUSIC_HISTORY_PAGE_SIZE).toString()
            _profileOverlay.value = cur2.copy(
                tabStates = cur2.tabStates + (ProfileTab.MUSIC_HISTORY to existing2.copy(
                    musicHistory = existing2.musicHistory + page, cursor = nextCursor, loading = false, loaded = true
                ))
            )
        }
    }

    private fun loadProfileTab(tab: ProfileTab, reset: Boolean) {
        // Blogs/Reviews/Backlog/Vods are fully loaded up-front by the probes
        // in openProfile() — there's no separate paged fetch for them at
        // all. Music History *does* page (see loadMoreMusicHistory), just
        // never through this function — it fetches RockskyTrack, not
        // MediaItem, so loadMoreProfileTab() routes it there instead before
        // this is ever reached; this guard just keeps it out of here too.
        if (tab == ProfileTab.BLOGS || tab == ProfileTab.REVIEWS || tab == ProfileTab.BACKLOG || tab == ProfileTab.VODS || tab == ProfileTab.MUSIC_HISTORY) return
        // Lists/Feeds has its own loader (not MediaItem pages either).
        if (tab == ProfileTab.LISTS_FEEDS) { loadProfileLists(force = false); return }
        val cur = _profileOverlay.value ?: return
        val did = cur.author.did
        val existing = cur.tabStates[tab] ?: ProfileTabState()
        if (existing.loading) return
        val cursorToUse = if (reset) null else existing.cursor
        _profileOverlay.value = cur.copy(tabStates = cur.tabStates + (tab to existing.copy(loading = true)))

        viewModelScope.launch(Dispatchers.IO) {
            // Profile "Posts" tab redesign: POSTS used to be two separate
            // tabs (Media/Text Posts) each filtering the same underlying
            // feed down to only the type it cared about. Now that they're
            // one tab with a client-side sub-filter row (see
            // ProfileOverlay's PostKindFilter), the fetch keeps everything
            // unfiltered, exactly like Reposts/Likes already did.
            suspend fun fetchPage(cursor: String?) = when (tab) {
                ProfileTab.POSTS   -> bskyRepo.getProfilePosts(bskyToken, did, cursor)
                ProfileTab.REPOSTS -> bskyRepo.getProfileReposts(bskyToken, did, cursor)
                ProfileTab.LIKES   -> bskyRepo.getProfileLikes(bskyToken, _bskyDid.value, did, cursor)
                else -> error("unreachable")
            }
            fun filterForTab(fetched: List<MediaItem>): List<MediaItem> = filterHidden(fetched)

            var cursorNow = cursorToUse
            val accumulated = mutableListOf<MediaItem>()
            var authRetried = false
            var succeeded = false
            // Bug fix: Reposts and Likes both filter a raw underlying feed
            // page down to just the items that actually qualify — reposts
            // out of a mixed posts+reposts author feed, or hidden/blocked
            // authors stripped back out — client-side, *after* fetching it
            // (see getProfileReposts/getProfileLikes above). It's entirely
            // normal for one raw page of 50 to contain zero reposts if the
            // account mostly just posts, which used to mean this loop (with
            // maxAutoPages pinned at 1) returned an "empty but succeeded"
            // page: accumulated stayed empty, the cursor still advanced,
            // and nothing was appended for the grid to show — so scrolling
            // to the bottom of a Reposts/Likes tab that had gone quiet for
            // a stretch just silently stopped loading more instead of
            // paging through to the next repost. Letting this loop keep
            // pulling further raw pages (bounded, so an account with
            // literally zero reposts/likes ever doesn't spin forever)
            // until it actually finds something — or runs out of pages —
            // fixes that; POSTS essentially never needs more than the one
            // page this bumped-up cap still allows for it, since it's rare
            // for a raw page to contain zero own-posts.
            val maxAutoPages = 6
            var pagesFetched = 0
            while (pagesFetched < maxAutoPages) {
                pagesFetched++
                var result = fetchPage(cursorNow)
                if (result.isFailure && !authRetried && isAuthError(result.exceptionOrNull()?.message)) {
                    authRetried = true
                    if (refreshBskyTokenIfPossible()) result = fetchPage(cursorNow)
                }
                val page = result.getOrNull() ?: break
                succeeded = true
                val (fetchedItems, cursor) = page
                accumulated += filterForTab(fetchedItems)
                cursorNow = cursor
                if (accumulated.isNotEmpty() || cursor == null) break
            }

            val cur2 = _profileOverlay.value?.takeIf { it.author.did == did } ?: return@launch
            if (succeeded) {
                val prevItems = if (reset) emptyList() else (cur2.tabStates[tab]?.items ?: emptyList())
                val updated = cur2.copy(
                    tabStates = cur2.tabStates + (tab to ProfileTabState(items = prevItems + accumulated, cursor = cursorNow, loading = false, loaded = true))
                )
                _profileOverlay.value = updated
                // Feature request #4: keep the on-disk cache in step with
                // Posts/Reposts/Likes too, not just Blogs/Reviews/Backlog/
                // Vods — only worth writing on a `reset` load (a fresh
                // first page, or a tab switch) rather than on every
                // load-more page, since the cache only ever keeps a
                // trimmed first batch anyway (see PROFILE_TAB_CACHE_ITEM_LIMIT).
                if (reset) persistProfileTabCache(updated)
            } else {
                _profileOverlay.value = cur2.copy(tabStates = cur2.tabStates + (tab to existing.copy(loading = false, loaded = true)))
            }
        }
    }

    /** Tapping a tile in one of the profile's post grids pushes that tab's
     *  items into the main pager (same save/restore mechanism as [showAuthorFeed])
     *  so the post opens full-screen with normal swipe/like/comment behavior,
     *  and dismisses the Profile Overlay. */
    /** Bug fix (see ProfileOverlayState.scrollIndex/scrollOffset doc comment
     *  above): called by ProfileOverlay right before it's about to be hidden
     *  (tapping a post, or pinching out to one) so the exact scroll position
     *  is captured from a source of truth outside Compose's own LazyListState,
     *  to be force-restored when the profile is revealed again. */
    fun saveProfileScrollPosition(index: Int, offset: Int) {
        val cur = _profileOverlay.value ?: return
        _profileOverlay.value = cur.copy(scrollIndex = index, scrollOffset = offset)
    }

    // Bug fix (per feedback): this used to always take an index into the
    // *entire* tab's unfiltered item list (cur.tabStates[...].items), no
    // matter which sub-filter — Images, Horizontal Videos, Reposts'
    // MediaKindFilter, etc. — was actually on screen. So tapping into, say,
    // the Vertical Videos sub-tab and swiping through the resulting pager
    // would eventually swipe onto images, text posts, and horizontal videos
    // that sub-tab never showed. Now the caller passes the exact list
    // currently rendered on screen (already filtered to that sub-tab) plus
    // the index within *that* list, so the pager can never contain more
    // than what the grid the user tapped from was actually showing.
    fun openPostFromProfileTab(items: List<MediaItem>, index: Int) {
        val cur = _profileOverlay.value ?: return
        if (index !in items.indices) return
        if (_authorFeedState.value == null) {
            _authorFeedState.value = AuthorFeedSavedState(
                author       = cur.author,
                items        = _mediaItems.value,
                currentIndex = _currentIndex.value,
                cursor       = feedCursor,
                feedUri      = _selectedFeedUri.value
            )
        } else {
            // Already showing another profile's (or list's) posts: keep the
            // original feed saved, but the Feeds row bubble must show THIS
            // profile's name and avatar — it used to keep the first one's.
            _authorFeedState.value = _authorFeedState.value!!.copy(author = cur.author)
        }
        feedCursor = null
        activeFeedMode = ActiveFeedMode.AUTHOR
        activeFeedActorDid = cur.author.did
        _mediaItems.value = filterHidden(items)
        _currentIndex.value = index
        // This is a context jump into an unrelated list (the profile's tab
        // items), not a swipe within the feed the user was already on — the
        // stale navDirection from whatever they last swiped was driving an
        // unwanted slide/"scroll" transition on the post that just appeared.
        _navDirection.value = 0
        _screenState.value = ScreenState.FEED
        // Pinch navigation: hide the profile instead of destroying it, so its
        // scroll position/tab/loaded content survive. Pinching in from this
        // post (see pinchInFromPost()) brings it right back exactly as it was.
        _profileOverlay.value = cur.copy(hidden = true)
    }

    /** Pinch-in on a post: if it was reached by tapping a grid item inside a
     *  still-alive (hidden) profile, bring that profile back exactly as it
     *  was left instead of falling through to the generic grid. Item 4:
     *  same check for a hidden search overlay now too — checked after
     *  profile so a post reached via a profile that was itself opened from
     *  within search still restores the (closer, more specific) profile
     *  first; pinching a second time from the grid would fall through to
     *  the search restore in that nested case. */
    /** Hub → "Return to Profile": back to the profile picked in the feed
     *  selector — un-hiding it if it's still open behind the feed, or
     *  opening it again if it was closed. */
    fun returnToProfile() {
        val author = _authorFeedState.value?.author ?: return
        val overlay = _profileOverlay.value
        if (overlay != null && overlay.author.did == author.did) {
            if (overlay.hidden) _profileOverlay.value = overlay.copy(hidden = false)
        } else {
            openProfile(author)
        }
    }

    fun pinchInFromPost() {
        // A feed opened from a profile: pinching in on a post goes to that
        // feed's Explore grid (pinching in again there returns to the profile).
        if (isProfileFeedActive()) { _screenState.value = ScreenState.GRID; return }
        val overlay = _profileOverlay.value
        if (overlay != null && overlay.hidden) {
            _profileOverlay.value = overlay.copy(hidden = false)
        } else if (_searchHiddenBehindPost.value) {
            _searchHiddenBehindPost.value = false
            _searchOpen.value = true
        } else {
            _screenState.value = ScreenState.GRID
        }
    }

    /** Pinch-out on a profile: the mirror of pinchInFromPost() above — only
     *  meaningful when this profile is the one currently hidden behind the
     *  post pager (i.e. it's exactly the profile openPostFromProfileTab()
     *  hid), so hiding it again reveals that same post right where it was.
     *  A profile opened by any other route (tapping an avatar, opening your
     *  own profile from Settings, etc.) has no post to pinch back out to. */
    fun pinchOutFromProfile() {
        val overlay = _profileOverlay.value ?: return
        if (overlay.hidden) return
        if (activeFeedMode == ActiveFeedMode.AUTHOR && activeFeedActorDid == overlay.author.did) {
            _profileOverlay.value = overlay.copy(hidden = true)
        }
    }

    fun openProfileBlog(blog: LeafletBlog) { _profileOverlay.value = _profileOverlay.value?.copy(openBlog = blog) }

    /** Builds the [TitleSearchResult] shape TitleDetailOverlay renders
     *  straight out of a review's own carried title fields — used whenever
     *  a title page is opened *from* a review (see openMutualReview/
     *  openProfileReview below) rather than from a Backlog card. Reviews
     *  carry the same confirmed real lexicon fields Backlog items do
     *  (releaseDate/genres/mainCredit/mainCreditRole/identifiers.imdbId —
     *  see PopfeedReview's own doc comment), so this is exactly the same
     *  synchronous, no-network-round-trip mapping openProfileTitle already
     *  does for Backlog items. */
    /** Patches a Wikipedia lookup into an open title page: the synopsis
     *  (with its article link for attribution) and, when the Popfeed record
     *  has no cover of its own, Wikipedia's lead image as the fallback. */
    private fun TitleSearchResult.withWikipedia(info: WikipediaRepository.WikiTitleInfo): TitleSearchResult {
        val hasOwnCover = com.mediaviewer.util.BlockedHosts.isAllowedCoverUrl(posterUrl) ||
            com.mediaviewer.util.BlockedHosts.isAllowedCoverUrl(backdropUrl)
        val cover = if (hasOwnCover) null else info.imageUrl
        return copy(
            overview = info.extract ?: overview,
            wikipediaArticleUrl = if (info.extract != null || cover != null) info.pageUrl else wikipediaArticleUrl,
            wikipediaCoverUrl = cover,
            wikipediaCoverWide = info.imageIsWide
        )
    }

    private fun titleFromReview(review: PopfeedReview): TitleSearchResult = TitleSearchResult(
        id = review.imdbId?.let { "imdb:$it" } ?: review.uri,
        title = review.mediaTitle, posterUrl = review.mediaImageUrl, backdropUrl = review.mediaBackdropUrl,
        releaseDate = review.releaseDate, creator = review.mainCredit, creatorRole = review.mainCreditRole,
        genres = review.genres, mediaCategory = review.mediaCategory,
        identifiersJson = review.identifiersJson
    )

    /** Same "patch in a Wikipedia synopsis a moment after the page opens"
     *  step openProfileTitle does for Backlog cards, reused for the
     *  review-opened path below (openMutualReview/openProfileReview) — see
     *  that function's own doc comment for the full reasoning (no synopsis
     *  field on Popfeed's lexicon at all, Wikipedia is the one open,
     *  key-free source that has one). Also carries through the resolved
     *  Wikipedia article's own title/URL, needed for the CC BY-SA
     *  attribution TitleDetailOverlay's description bubble shows underneath
     *  the extract (see WikipediaRepository's class doc comment). */
    private fun fetchTitleOverviewFor(review: PopfeedReview) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                WikipediaRepository.lookup(
                    review.mediaTitle, review.mediaCategory, review.identifiersJson, review.releaseDate,
                    creator = review.mainCredit, imdbId = review.imdbId
                )
            }.getOrNull() ?: return@launch
            val cur = _profileOverlay.value ?: return@launch
            val open = cur.openTitle ?: return@launch
            if (open.title == review.mediaTitle) {
                _profileOverlay.value = cur.copy(openTitle = open.withWikipedia(result))
            }
        }
    }

    /** Feature (this session): the Hub's Mutual Reviews cards call this
     *  directly instead of onOpenProfile — opens the review's author's
     *  profile (Reviews tab), with that title's own full page open on top
     *  and this specific review pre-selected in its Summary/Reviews tab
     *  strip (item 12 — "tapping a user's review should open this title
     *  page with their specific review auto selected"), rather than the
     *  old standalone ReviewDetailOverlay popup. */
    fun openMutualReview(fr: FriendPopfeedReview) {
        openProfile(fr.author, initialTab = ProfileTab.REVIEWS, title = titleFromReview(fr.review), preselectedReview = fr)
        _profileOverlay.value = _profileOverlay.value?.copy(standaloneDetail = true)
        fetchTitleOverviewFor(fr.review)
    }

    /** Hub Blogs section equivalent of [openMutualReview] above. */
    fun openMutualBlog(fb: FriendLeafletBlog) {
        openProfile(fb.author, initialTab = ProfileTab.BLOGS, blog = fb.blog)
        _profileOverlay.value = _profileOverlay.value?.copy(standaloneDetail = true)
    }
    fun closeProfileBlog() {
        val cur = _profileOverlay.value ?: return
        if (cur.standaloneDetail) closeStandaloneDetail() else _profileOverlay.value = cur.copy(openBlog = null)
    }

    /** A Hub title/blog closes straight back to where it was opened from. */
    private fun closeStandaloneDetail() {
        val cur = _profileOverlay.value ?: return
        _profileOverlay.value = cur.copy(openBlog = null, openReview = null, openTitle = null, openTitlePreselectedReview = null, standaloneDetail = false)
        closeProfile()
    }

    /** Profile Reviews tab equivalent of [openMutualReview] — the profile
     *  overlay is already open here (we're tapping a row on it), so this
     *  just layers the title page on top of the *current* state instead of
     *  pushing a whole new profile stack entry. */
    fun openProfileReview(review: PopfeedReview) {
        val cur = _profileOverlay.value ?: return
        _profileOverlay.value = cur.copy(openTitle = titleFromReview(review), openTitlePreselectedReview = FriendPopfeedReview(cur.author, review))
        fetchTitleOverviewFor(review)
    }
    fun closeProfileReview() { _profileOverlay.value = _profileOverlay.value?.copy(openReview = null) }

    // ── Title page: Reviews tab social actions (item 12) ────────────────────
    // Keyed by review URI rather than nested in ProfileOverlayState since
    // TitleDetailOverlay renders a whole scrollable strip of *other people's*
    // reviews at once (see the Summary/Reviews tab strip), each of which can
    // independently be liked/commented on — a flat map avoids needing a
    // parallel per-review slot inside ProfileOverlayState for every one of
    // them.
    data class ReviewSocialState(val loading: Boolean = false, val likeCount: Int = 0, val likedByMe: Boolean = false, val comments: List<PopfeedCommentRecord> = emptyList())
    private val _reviewSocial = MutableStateFlow<Map<String, ReviewSocialState>>(emptyMap())
    val reviewSocial: StateFlow<Map<String, ReviewSocialState>> = _reviewSocial

    /** Loads (or reloads) the like/comment state for one review — called the
     *  moment a review's bubble becomes the selected tab on TitleDetailOverlay. */
    fun loadReviewSocial(review: PopfeedReview) {
        val did = _bskyDid.value
        if (did.isBlank()) return
        _reviewSocial.value = _reviewSocial.value + (review.uri to (_reviewSocial.value[review.uri] ?: ReviewSocialState()).copy(loading = true))
        viewModelScope.launch(Dispatchers.IO) {
            val subs = _subscribedReviewDids.value.toList()
            val likes = runCatching { bskyRepo.getPopfeedLikeSummary(did, review.uri, subs) }.getOrDefault(PopfeedLikeSummary(0, false))
            val comments = runCatching { bskyRepo.getPopfeedComments(bskyToken, did, review.uri, subs) }.getOrDefault(emptyList())
            _reviewSocial.value = _reviewSocial.value + (review.uri to ReviewSocialState(loading = false, likeCount = likes.count, likedByMe = likes.likedByMe, comments = comments))
        }
    }

    fun toggleReviewLike(review: PopfeedReview) {
        val did = _bskyDid.value
        if (did.isBlank()) return
        val cur = _reviewSocial.value[review.uri] ?: ReviewSocialState()
        val nowLiked = !cur.likedByMe
        // Optimistic update — feels instant, same pattern used for
        // Bluesky's own like button elsewhere in this app.
        _reviewSocial.value = _reviewSocial.value + (review.uri to cur.copy(likedByMe = nowLiked, likeCount = (cur.likeCount + if (nowLiked) 1 else -1).coerceAtLeast(0)))
        viewModelScope.launch(Dispatchers.IO) {
            val result = if (nowLiked) bskyRepo.likePopfeedReview(bskyToken, did, review.uri)
                         else bskyRepo.unlikePopfeedReview(bskyToken, did, review.uri).map { review.uri }
            if (result.isFailure) {
                // Roll back on failure.
                _reviewSocial.value = _reviewSocial.value + (review.uri to cur)
            }
        }
    }

    fun postReviewComment(review: PopfeedReview, text: String) {
        val did = _bskyDid.value
        if (did.isBlank() || text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.postPopfeedComment(bskyToken, did, review.uri, text).onSuccess {
                loadReviewSocial(review)
            }
        }
    }

    /** Item 9: deletes the current account's own review outright — the
     *  "Delete" segment on TitleDetailOverlay's LikeReviewCommentBar, only
     *  ever shown when the signed-in account is that review's own author
     *  (see ProfileOverlay's own selfDid check), so there's nothing to
     *  re-verify here. Drops it from the local friends/reviews cache right
     *  away so it disappears from every tab strip/list already showing it,
     *  and clears it as the title page's preselected review if that's the
     *  one being deleted, before firing the actual delete off. */
    fun deleteReview(review: PopfeedReview) {
        val did = _bskyDid.value
        if (did.isBlank()) return
        _friendsReviews.value = _friendsReviews.value.filterNot { it.review.uri == review.uri }
        _profileOverlay.value = _profileOverlay.value?.let { cur ->
            if (cur.openTitlePreselectedReview?.review?.uri == review.uri) cur.copy(openTitlePreselectedReview = null) else cur
        }
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.deleteReview(bskyToken, did, review.uri)
        }
    }

    /** Backlog cards' "full info menu" (Titles feature) — per feedback,
     *  this was meant to cover *every* title card, not just Search's
     *  Titles tab. Converts the tapped [PopfeedBacklogItem] into the same
     *  [TitleSearchResult] shape TitleDetailOverlay already renders.
     *
     *  Title/poster/backdrop/releaseDate/genres/mainCredit all come straight
     *  off the record now (confirmed real Popfeed lexicon fields — see
     *  BlueskyRepository.getPopfeedBacklog), so those show up immediately,
     *  synchronously, no network round trip needed. `overview` is the one
     *  field with no Popfeed source at all (their lexicon has no synopsis
     *  field, confirmed) — it starts null (TitleDetailOverlay shows its
     *  loading/placeholder state) and is patched in a moment later via
     *  [WikipediaRepository], the one open, key-free description source
     *  that exists. `tagline` still has nowhere to come from and stays null.
     *
     *  The `cur.openTitle?.id == item.uri` check below guards against a
     *  slow Wikipedia lookup finishing after the person has already closed
     *  this card or opened a different one — without it, a late response
     *  could overwrite whatever's open by then with the wrong movie's
     *  description. */
    fun openProfileTitle(item: PopfeedBacklogItem) {
        _profileOverlay.value = _profileOverlay.value?.copy(
            openTitle = TitleSearchResult(
                id = item.uri, title = item.title, posterUrl = item.imageUrl,
                backdropUrl = item.mediaBackdropUrl, mediaCategory = item.mediaCategory,
                releaseDate = item.releaseDate, creator = item.mainCredit,
                creatorRole = item.mainCreditRole, genres = item.genres,
                identifiersJson = item.identifiersJson ?: item.imdbId?.let { """{"imdbId":"$it"}""" }
            )
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                WikipediaRepository.lookup(
                    item.title, item.mediaCategory, item.identifiersJson, item.releaseDate,
                    creator = item.mainCredit, imdbId = item.imdbId
                )
            }.getOrNull() ?: return@launch
            val cur = _profileOverlay.value ?: return@launch
            val open = cur.openTitle ?: return@launch
            if (open.id == item.uri) {
                _profileOverlay.value = cur.copy(openTitle = open.withWikipedia(result))
            }
        }
    }
    // ── Backlog button (review page + title page) ────────────────────────────
    /** Whether the open title is in the signed-in account's Popfeed backlog
     *  ([listItemUri] non-null = yes), for the Backlog/Remove button. */
    data class TitleBacklogState(
        val key: String,
        val checking: Boolean = true,
        val busy: Boolean = false,
        val listItemUri: String? = null
    )
    private val _titleBacklog = MutableStateFlow<TitleBacklogState?>(null)
    val titleBacklog: StateFlow<TitleBacklogState?> = _titleBacklog

    private fun backlogKey(title: TitleSearchResult) = title.id + "|" + title.title

    /** Looks up whether [title] is already in your backlog (so the button
     *  shows "Remove" instead of "Backlog"). */
    fun checkTitleBacklog(title: TitleSearchResult) {
        if (!_bskyLoggedIn.value) return
        val key = backlogKey(title)
        val cur = _titleBacklog.value
        if (cur != null && cur.key == key && (cur.checking || cur.busy)) return
        _titleBacklog.value = TitleBacklogState(key, checking = true, listItemUri = cur?.takeIf { it.key == key }?.listItemUri)
        viewModelScope.launch(Dispatchers.IO) {
            val uri = runCatching { bskyRepo.findPopfeedBacklogItem(_bskyDid.value, title) }.getOrNull()
            if (_titleBacklog.value?.key == key) _titleBacklog.value = TitleBacklogState(key, checking = false, listItemUri = uri)
        }
    }

    /** Backlog → adds [title] to your Popfeed backlog; Remove → takes it off. */
    fun toggleTitleBacklog(title: TitleSearchResult) {
        val key = backlogKey(title)
        val cur = _titleBacklog.value?.takeIf { it.key == key } ?: return
        if (cur.checking || cur.busy) return
        tapHaptic()
        _titleBacklog.value = cur.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val existing = cur.listItemUri
            if (existing != null) {
                bskyRepo.removeBacklogListItem(bskyToken, _bskyDid.value, existing)
                    .onSuccess {
                        if (_titleBacklog.value?.key == key) _titleBacklog.value = TitleBacklogState(key, checking = false, listItemUri = null)
                        showToast("Removed from your backlog")
                    }
                    .onFailure {
                        if (_titleBacklog.value?.key == key) _titleBacklog.value = cur.copy(busy = false)
                        _errorMessage.value = "Couldn't remove it: ${it.message}"
                    }
            } else {
                bskyRepo.addToPopfeedBacklog(bskyToken, _bskyDid.value, title)
                    .onSuccess { uri ->
                        if (_titleBacklog.value?.key == key) _titleBacklog.value = TitleBacklogState(key, checking = false, listItemUri = uri)
                        showToast("Added to your backlog")
                    }
                    .onFailure {
                        if (_titleBacklog.value?.key == key) _titleBacklog.value = cur.copy(busy = false)
                        _errorMessage.value = "Couldn't add it to your backlog: ${it.message}"
                    }
            }
        }
    }

    fun closeProfileTitle() {
        val cur = _profileOverlay.value ?: return
        if (cur.standaloneDetail) closeStandaloneDetail()
        else _profileOverlay.value = cur.copy(openTitle = null, openTitlePreselectedReview = null)
    }

    fun toggleProfileFollow() {
        val cur = _profileOverlay.value ?: return
        val profile = cur.profile ?: return
        if (profile.blocksYou) { showToast("This user has you blocked"); return }
        val author = profile.author
        val willFollow = !author.isFollowing

        // Bug fix: the banner's FollowButton reads its isFollowing state from
        // ProfileOverlayState.author (the top-level field), not from
        // profile.author — those are two separate copies that only start out
        // in sync (set together when the profile finishes loading). This used
        // to update only profile.author, so the follow/unfollow API call
        // fired correctly but the button never visually changed. Both copies
        // need to be updated together everywhere below.
        val optimisticAuthor = author.copy(isFollowing = willFollow)
        _profileOverlay.value = cur.copy(author = optimisticAuthor, profile = profile.copy(author = optimisticAuthor))
        _mediaItems.value = _mediaItems.value.map {
            if (it.author.did == author.did) it.copy(author = it.author.copy(isFollowing = willFollow)) else it
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (!willFollow) {
                bskyRepo.unfollowUser(bskyToken, _bskyDid.value, author.followingUri ?: return@launch)
                    .onFailure {
                        val cur2 = _profileOverlay.value ?: return@onFailure
                        _profileOverlay.value = cur2.copy(author = author, profile = cur2.profile?.copy(author = author))
                    }
            } else {
                bskyRepo.followUser(bskyToken, _bskyDid.value, author.did)
                    .onSuccess { uri ->
                        val cur2 = _profileOverlay.value ?: return@onSuccess
                        val withUri = optimisticAuthor.copy(followingUri = uri)
                        _profileOverlay.value = cur2.copy(author = withUri, profile = cur2.profile?.copy(author = withUri))
                        // Same opt-in "Add To" auto-popup as following from the main
                        // feed (toggleFollow()) — this path just didn't call it before.
                        if (_autoAddToOnFollow.value) openListPicker(author.did)
                    }
                    .onFailure {
                        val cur2 = _profileOverlay.value ?: return@onFailure
                        val reverted = author.copy(isFollowing = false)
                        _profileOverlay.value = cur2.copy(author = reverted, profile = cur2.profile?.copy(author = reverted))
                    }
            }
        }
    }

    /** Select a feed from ANY context (normal, author overlay, likes overlay).
     *  If we're in an author/likes overlay and the user picks the same feed they
     *  were already on, we restore the exact saved scroll position instead of reloading. */
    fun selectFeedFromAnyContext(uri: String?) {
        val saved = _authorFeedState.value
        if (saved != null) {
            _authorFeedState.value = null
            activeFeedMode = ActiveFeedMode.NORMAL
            activeFeedActorDid = null
            // A hidden profile (see openPostFromProfileTab/pinchInFromPost) has
            // no meaning once the user has backed all the way out to a
            // different feed entirely — drop it so a later pinch-in on an
            // unrelated post doesn't resurrect a stale profile.
            if (_profileOverlay.value?.hidden == true) _profileOverlay.value = null
            if (uri == saved.feedUri) {
                // Same feed — restore exactly
                _mediaItems.value = saved.items
                _currentIndex.value = saved.currentIndex
                feedCursor = saved.cursor
                _selectedFeedUri.value = saved.feedUri
                return
            }
        }
        selectFeed(uri)
    }

    fun selectFeed(uri: String?) {
        // Switching feeds: drop the old feed's posts immediately, so the
        // feed/grid (which opens instantly) shows placeholders rather than
        // the previous feed's content until the new one arrives.
        if (_bskyLoggedIn.value || _appMode.value == AppMode.E621) {
            _mediaItems.value = emptyList()
            _currentIndex.value = 0
            _isLoading.value = true
        }
        _selectedFeedUri.value = uri
        // Bug fix: any explicit selection — including picking the null-URI
        // "Following" entry on purpose — counts as "the user has made a
        // choice," so loadAvailableFeeds()'s one-time default-feed fallback
        // (see its doc comment) should never fire again after this, even
        // though _selectedFeedUri.value can legitimately be null again.
        hasAutoSelectedFeed = true
        viewModelScope.launch { prefs.setLastFeedUri(uri) }
        loadFeed(reset = true)
    }

    fun loadE621Posts(reset: Boolean = true) {
        // Bug fix (Outstanding Issue #1 — diagnostic, temporary): see
        // loadFeed()'s matching Log.d above.
        Log.d("Stellar-FeedState", "loadE621Posts(reset=$reset)", Exception("trace"))
        if (!_e621LoggedIn.value) return
        viewModelScope.launch(Dispatchers.IO) {
            if (reset) { e621Page = 1; _isLoading.value = true; _currentIndex.value = 0 }
            try {
                // Bug fix: guarantee this can never leave _isLoading stuck
                // at true (the app's "loads forever" symptom) even if the
                // network call hangs indefinitely or throws something that
                // isn't a normal Result.failure — a hard timeout plus a
                // try/finally around the whole call, instead of relying on
                // the repo call always resolving cleanly on its own.
                val result = kotlinx.coroutines.withTimeout(20_000) {
                    if (e621ShowingFavorites)
                        e621Repo.getFavorites(e621Username, e621ApiKey, e621Page)
                    else
                        e621Repo.searchPosts(e621Username, e621ApiKey, _e621SearchTags.value, e621Page)
                }
                result.onSuccess { items ->
                    val followed = _e621FollowedArtists.value
                    val stamped = items.map { it.copy(author = it.author.copy(isFollowing = followed.contains(it.author.handle))) }
                    _mediaItems.value = if (reset) stamped else _mediaItems.value + stamped
                    e621Page++
                }.onFailure { _errorMessage.value = it.message }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "e621 request timed out"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setE621SearchTags(tags: String) {
        _e621SearchTags.value = tags
        viewModelScope.launch { prefs.setLastE621Tags(tags) }
    }

    /** Replace search with a single tag and execute the search immediately (tag tap). */
    fun searchSingleTag(tag: String) {
        e621ShowingFavorites = false
        _e621SearchTags.value = tag
        viewModelScope.launch { prefs.setLastE621Tags(tag) }
        loadE621Posts(reset = true)
        _screenState.value = ScreenState.FEED
    }

    /** Append (or exclude with -) a tag to the current search without executing it. */
    fun addTagToSearch(tag: String, exclude: Boolean) {
        val token = if (exclude) "-$tag" else tag
        val current = _e621SearchTags.value.trim()
        val parts = current.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        // Remove any existing occurrence (with or without the opposite sign) before adding
        parts.removeAll { it == tag || it == "-$tag" }
        parts.add(token)
        _e621SearchTags.value = parts.joinToString(" ")
        viewModelScope.launch { prefs.setLastE621Tags(_e621SearchTags.value) }
    }

    fun searchE621() {
        e621ShowingFavorites = false
        loadE621Posts(reset = true)
    }

    fun showE621Favorites() {
        e621ShowingFavorites = true
        loadE621Posts(reset = true)
    }

    fun toggleE621Follow() {
        val item   = currentItem.value ?: return
        val artist = item.author.handle.ifBlank { return }
        val isFollowing = _e621FollowedArtists.value.contains(artist)
        if (isFollowing) {
            _e621FollowedArtists.value = _e621FollowedArtists.value - artist
            viewModelScope.launch { prefs.unfollowE621Artist(artist) }
        } else {
            _e621FollowedArtists.value = _e621FollowedArtists.value + artist
            viewModelScope.launch { prefs.followE621Artist(artist) }
        }
        // The feed renders straight from _mediaItems (not the derived currentItem
        // overlay), so we need to actually write the new follow state onto every
        // loaded item by this artist for the button to visually update.
        _mediaItems.value = _mediaItems.value.map {
            if (it.author.handle == artist) it.copy(author = it.author.copy(isFollowing = !isFollowing)) else it
        }
    }

    fun searchFollowingE621() {
        val artists = _e621FollowedArtists.value
        if (artists.isEmpty()) {
            _errorMessage.value = "You're not following any artists yet"
            return
        }
        // ~tag syntax: e621 OR-searches, showing posts from ANY of the followed artists
        val tags = artists.joinToString(" ") { "~$it" }
        e621ShowingFavorites = false
        _e621SearchTags.value = tags
        viewModelScope.launch { prefs.setLastE621Tags(tags) }
        loadE621Posts(reset = true)
        _screenState.value = ScreenState.FEED
    }

    fun showBskyLikes() {
        if (!_bskyLoggedIn.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _currentIndex.value = 0
            // Save current state so user can restore
            if (_authorFeedState.value == null) {
                _authorFeedState.value = AuthorFeedSavedState(
                    author       = AuthorInfo(_bskyDid.value, bskyHandle, "Liked Posts", null),
                    items        = _mediaItems.value,
                    currentIndex = _currentIndex.value,
                    cursor       = feedCursor,
                    feedUri      = _selectedFeedUri.value
                )
            } else {
                _authorFeedState.value = _authorFeedState.value!!.copy(author = AuthorInfo(_bskyDid.value, bskyHandle, "Liked Posts", null))
            }
            var result = bskyRepo.getActorLikes(bskyToken, _bskyDid.value)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getActorLikes(bskyToken, _bskyDid.value)
            }
            result.onSuccess { (items, cursor) ->
                feedCursor = cursor
                activeFeedMode = ActiveFeedMode.LIKES
                activeFeedActorDid = _bskyDid.value
                _mediaItems.value = filterHidden(items)
                _screenState.value = ScreenState.FEED
            }.onFailure { _errorMessage.value = it.message }
            _isLoading.value = false
        }
    }

    // ── From Friends (item 7) ──────────────────────────────────────────────────

    /** Warms the From Friends feed in the background on app open so opening it
     *  from Settings is instant instead of waiting on a fresh DM scan every time. */
    private fun preloadFriendsFeed() {
        if (friendsFeedPreloadStarted) return
        friendsFeedPreloadStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            ensureDmConversationsLoadedSuspend(silent = true)
            val realConvos = _dmConversations.value.filter { it.convoId.isNotBlank() }
            bskyRepo.getFriendsSharedPosts(bskyToken, _bskyDid.value, realConvos)
                .onSuccess { _friendsFeedCache.value = it }
            // On failure the cache just stays null — showFriendsFeed() below will
            // fall back to a live (loading-screen) fetch instead of silently failing.
        }
    }

    private fun openFriendsFeed(items: List<MediaItem>) {
        _currentIndex.value = 0
        enterSpecialFeed("From Friends")
        if (items.isEmpty()) {
            // Nothing to show — undo the overlay save and bounce back to Settings
            _authorFeedState.value = null
            _screenState.value = ScreenState.SETTINGS
            showToast("Feed Empty")
        } else {
            feedCursor = null
            activeFeedMode = ActiveFeedMode.FRIENDS
            activeFeedActorDid = null
            _mediaItems.value = filterHidden(items)
            // "From Friends" opens in Explore (grid) mode, each tile wearing
            // a little bubble with who sent it and what they said.
            _screenState.value = ScreenState.GRID
        }
        // A Saved Posts load that was still running is superseded.
        feedLoadGeneration++
        _isLoading.value = false
    }

    /** Opens the (still empty) From Friends grid straight away while its
     *  posts load — no loading screen, no old feed showing in the meantime. */
    private fun beginFriendsFeedLoad() {
        _currentIndex.value = 0
        enterSpecialFeed("From Friends")
        feedLoadGeneration++
        feedCursor = null
        activeFeedMode = ActiveFeedMode.FRIENDS
        activeFeedActorDid = null
        _mediaItems.value = emptyList()
        _isLoading.value = true
        _screenState.value = ScreenState.GRID
    }

    fun showFriendsFeed() {
        if (!_bskyLoggedIn.value) return
        val cached = _friendsFeedCache.value
        if (cached != null) {
            // Already warmed up in the background — opens instantly, no loading screen.
            openFriendsFeed(cached)
            // The cache could be from a while ago (e.g. app launch, if this is a
            // later visit in the same session) — check for anything shared since
            // then in the background and append it, rather than only ever
            // showing what was there the first time this session.
            refreshFriendsFeedInBackground()
            return
        }
        // Not ready yet: open the grid right away (showing placeholder
        // tiles) and fill it in once the fetch lands — feeds no longer get a
        // loading screen.
        beginFriendsFeedLoad()
        val generation = feedLoadGeneration
        viewModelScope.launch(Dispatchers.IO) {
            ensureDmConversationsLoadedSuspend(silent = true)
            val realConvos = _dmConversations.value.filter { it.convoId.isNotBlank() }
            val result = bskyRepo.getFriendsSharedPosts(bskyToken, _bskyDid.value, realConvos)
            // The person may have left for another feed while this loaded.
            val stillHere = generation == feedLoadGeneration && activeFeedMode == ActiveFeedMode.FRIENDS
            result.onSuccess { items ->
                _friendsFeedCache.value = items
                if (stillHere) openFriendsFeed(items)
            }.onFailure {
                if (stillHere) openFriendsFeed(emptyList())
            }
            if (stillHere) _isLoading.value = false
        }
    }

    /** Re-scans in the background and appends anything new to the end of both
     *  the cache and (if still on the From Friends feed) the visible list —
     *  appending rather than prepending/resorting so it doesn't shift the
     *  index of whatever the user is currently looking at. */
    private fun refreshFriendsFeedInBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            val realConvos = _dmConversations.value.filter { it.convoId.isNotBlank() }
            bskyRepo.getFriendsSharedPosts(bskyToken, _bskyDid.value, realConvos)
                .onSuccess { fresh ->
                    val existingIds = _friendsFeedCache.value.orEmpty().map { it.id }.toSet()
                    val newOnes = fresh.filter { it.id !in existingIds }
                    if (newOnes.isNotEmpty()) {
                        val merged = _friendsFeedCache.value.orEmpty() + newOnes
                        _friendsFeedCache.value = merged
                        if (activeFeedMode == ActiveFeedMode.FRIENDS) {
                            _mediaItems.value = filterHidden(merged)
                        }
                    }
                }
        }
    }

    /** Opens the reply popup for the friend who sent the current post (item 7). */
    fun openReplyToSender() {
        val item = currentItem.value ?: return
        val convoId = item.sentByConvoId ?: return
        val convo = _dmConversations.value.firstOrNull { it.convoId == convoId }
            ?: item.sentByAuthor?.let { a -> DmConversation(convoId, a, "", "") }
            ?: return
        _replyToConvo.value = convo
    }

    fun dismissReplyPopup() { _replyToConvo.value = null }

    fun sendReply(text: String) {
        val convo = _replyToConvo.value ?: return
        if (text.isBlank()) return
        _replyToConvo.value = null
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.sendMessage(bskyToken, _bskyDid.value, convo.convoId, text)
                .onSuccess { showToast("Reply sent") }
                .onFailure { _errorMessage.value = "Reply failed: ${it.message}" }
        }
    }

    // ── Block account (item 3) ─────────────────────────────────────────────────

    // Settings → Data and Privacy → Blocked Accounts.
    private val _blockedAccounts = MutableStateFlow<List<BlockedAccount>>(emptyList())
    val blockedAccounts: StateFlow<List<BlockedAccount>> = _blockedAccounts
    private val _blockedAccountsLoading = MutableStateFlow(false)
    val blockedAccountsLoading: StateFlow<Boolean> = _blockedAccountsLoading
    private val _blockedAccountsOpen = MutableStateFlow(false)
    val blockedAccountsOpen: StateFlow<Boolean> = _blockedAccountsOpen
    /** DIDs with an unblock request in flight (their row shows a spinner). */
    private val _unblockingDids = MutableStateFlow<Set<String>>(emptySet())
    val unblockingDids: StateFlow<Set<String>> = _unblockingDids

    fun openBlockedAccounts() {
        if (!_bskyLoggedIn.value) return
        _blockedAccountsOpen.value = true
        loadBlockedAccounts()
    }
    fun closeBlockedAccounts() { _blockedAccountsOpen.value = false }

    private var blockedAccountsJob: Job? = null
    /** Fetches every account you're blocking (most recent first) and seeds
     *  [com.mediaviewer.util.BlockedAccounts], which the whole app filters by. */
    fun loadBlockedAccounts() {
        if (!_bskyLoggedIn.value || blockedAccountsJob?.isActive == true) return
        blockedAccountsJob = viewModelScope.launch(Dispatchers.IO) {
            if (_blockedAccounts.value.isEmpty()) _blockedAccountsLoading.value = true
            var result = bskyRepo.getBlockedAccounts(bskyToken)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getBlockedAccounts(bskyToken)
            }
            result.onSuccess { list ->
                _blockedAccounts.value = list
                // Anything already on screen from these accounts goes too.
                val hidden = list.map { it.author.did }.toSet()
                if (hidden.isNotEmpty() && _mediaItems.value.any { it.author.did in hidden && !it.isBlocked }) {
                    val cur = currentItem.value
                    val kept = _mediaItems.value.filterNot { it.author.did in hidden && !it.isBlocked && it.id != cur?.id }
                    if (kept.size != _mediaItems.value.size) {
                        val newIdx = cur?.let { c -> kept.indexOfFirst { it.id == c.id } } ?: -1
                        _mediaItems.value = kept
                        _currentIndex.value = if (newIdx >= 0) newIdx else _currentIndex.value.coerceAtMost((kept.size - 1).coerceAtLeast(0))
                    }
                }
            }
            _blockedAccountsLoading.value = false
        }
    }

    /** Unblock from the Blocked Accounts list. */
    fun unblockAccount(did: String) {
        val entry = _blockedAccounts.value.firstOrNull { it.author.did == did } ?: return
        if (did in _unblockingDids.value) return
        _unblockingDids.value = _unblockingDids.value + did
        viewModelScope.launch(Dispatchers.IO) {
            val uri = entry.blockUri.ifBlank { com.mediaviewer.util.BlockedAccounts.blockUriFor(did).orEmpty() }
            val result = if (uri.isNotBlank()) bskyRepo.unblockUser(bskyToken, _bskyDid.value, uri)
                else Result.failure<Unit>(IllegalStateException("block record not found"))
            result.onSuccess {
                com.mediaviewer.util.BlockedAccounts.removeBlocking(did)
                _blockedAccounts.value = _blockedAccounts.value.filterNot { it.author.did == did }
                _mediaItems.value = _mediaItems.value.map {
                    if (it.author.did == did && it.isBlocked) it.copy(isBlocked = false, blockUri = null) else it
                }
                showToast("Unblocked @${entry.author.handle}")
            }.onFailure { _errorMessage.value = "Unblock failed: ${it.message}" }
            _unblockingDids.value = _unblockingDids.value - did
        }
    }

    fun toggleBlockCurrentAuthor() {
        val item = currentItem.value ?: return
        if (_appMode.value != AppMode.BLUESKY) return
        val targetDid = item.author.did
        if (item.isBlocked) {
            // Unblock
            val uri = item.blockUri
            _mediaItems.value = _mediaItems.value.map {
                if (it.author.did == targetDid) it.copy(isBlocked = false, blockUri = null) else it
            }
            viewModelScope.launch(Dispatchers.IO) {
                if (uri != null) {
                    bskyRepo.unblockUser(bskyToken, _bskyDid.value, uri)
                        .onSuccess {
                            showToast("Unblocked @${item.author.handle}")
                            com.mediaviewer.util.BlockedAccounts.removeBlocking(targetDid)
                            _blockedAccounts.value = _blockedAccounts.value.filterNot { it.author.did == targetDid }
                        }
                        .onFailure {
                            // Revert on failure
                            _mediaItems.value = _mediaItems.value.map { m ->
                                if (m.author.did == targetDid) m.copy(isBlocked = true, blockUri = uri) else m
                            }
                            _errorMessage.value = "Unblock failed: ${it.message}"
                        }
                }
            }
        } else {
            // Block
            viewModelScope.launch(Dispatchers.IO) {
                bskyRepo.blockUser(bskyToken, _bskyDid.value, targetDid)
                    .onSuccess { uri ->
                        showToast("Blocked @${item.author.handle}")
                        com.mediaviewer.util.BlockedAccounts.addBlocking(targetDid, uri)
                        _blockedAccounts.value = listOf(BlockedAccount(item.author, uri)) +
                            _blockedAccounts.value.filterNot { it.author.did == targetDid }
                        _mediaItems.value = _mediaItems.value.map {
                            if (it.author.did == targetDid) it.copy(isBlocked = true, blockUri = uri) else it
                        }
                    }
                    .onFailure { _errorMessage.value = "Block failed: ${it.message}" }
            }
        }
    }

    /** A bsky.app/profile/<handle or DID> link opened with Stellar (see
     *  MainActivity's intent filter): looks the account up and opens its
     *  profile page. */
    private val profileCardCache = HashMap<String, AuthorInfo>()

    /** A profile shared in a DM (its bsky.app link): who it is, for the
     *  message's profile card. Cached for the session. */
    suspend fun profileCard(actor: String): AuthorInfo? {
        if (actor.isBlank() || !_bskyLoggedIn.value) return null
        com.mediaviewer.platform.synchronizedCompat(profileCardCache) { profileCardCache[actor] }?.let { return it }
        return withContext(Dispatchers.IO) {
            var result = bskyRepo.getFullProfile(bskyToken, actor)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message) && refreshBskyTokenIfPossible()) {
                result = bskyRepo.getFullProfile(bskyToken, actor)
            }
            result.getOrNull()?.author?.also { a -> com.mediaviewer.platform.synchronizedCompat(profileCardCache) { profileCardCache[actor] = a } }
        }
    }

    fun openProfileFromLink(actor: String, postRkey: String? = null) {
        if (!_bskyLoggedIn.value || actor.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            var result = bskyRepo.getFullProfile(bskyToken, actor)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getFullProfile(bskyToken, actor)
            }
            result.onSuccess { p ->
                withContext(Dispatchers.Main) {
                    // Any popup/overlay that would sit on top of it is closed first.
                    _searchOpen.value = false
                    _dmInboxOpen.value = false
                    _inboxOpen.value = false
                    openProfile(p.author)
                }
                if (postRkey.isNullOrBlank()) return@onSuccess
                // A post link: once the poster's profile has loaded, open the
                // post on top of it — closing the post goes back to the
                // profile (see backFromFeed).
                val did = p.author.did
                withTimeoutOrNull(12_000) {
                    _profileOverlay.first { o -> o == null || o.author.did != did || !o.loadingProfile }
                }
                if (_profileOverlay.value?.author?.did != did) return@onSuccess
                val items = runCatching {
                    bskyRepo.getPostItems(bskyToken, _bskyDid.value, listOf("at://$did/app.bsky.feed.post/$postRkey"))
                }.getOrDefault(emptyList())
                if (items.isEmpty()) { showToast("Couldn't open that post"); return@onSuccess }
                // Let the profile's loading animation finish revealing it.
                delay(400)
                withContext(Dispatchers.Main) {
                    if (_profileOverlay.value?.author?.did == did) {
                        openPostFromProfileTab(items, 0)
                        linkPostReturnsToProfile = true
                    }
                }
            }.onFailure { showToast("Couldn't open @$actor") }
        }
    }

    /** A post opened from a bsky.app post link: Back returns to its poster's profile. */
    private var linkPostReturnsToProfile = false

    /** Back from the Timeline/Explore: normally the Hub; after opening a
     *  post from a link, back to that poster's profile instead. */
    fun backFromFeed() {
        val overlay = _profileOverlay.value
        if (linkPostReturnsToProfile && overlay != null && overlay.hidden) {
            linkPostReturnsToProfile = false
            pinchInFromPost()
            return
        }
        linkPostReturnsToProfile = false
        setScreen(ScreenState.SETTINGS)
    }

    /** Profile interaction bar → Block: the same block/unblock the
     *  timeline's Block does, for the profile's own account. */
    fun toggleBlockProfile(author: AuthorInfo) {
        if (!_bskyLoggedIn.value || author.did.isBlank() || author.did == _bskyDid.value) return
        tapHaptic()
        val targetDid = author.did
        fun markProfile(blocked: Boolean) {
            val cur = _profileOverlay.value
            if (cur != null && cur.author.did == targetDid) {
                _profileOverlay.value = cur.copy(profile = cur.profile?.let { p -> p.copy(blockedEitherWay = blocked || p.blocksYou) })
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (com.mediaviewer.util.BlockedAccounts.isBlocking(targetDid)) {
                val uri = com.mediaviewer.util.BlockedAccounts.blockUriFor(targetDid) ?: run {
                    // Not known yet: the profile view carries it.
                    bskyRepo.getFullProfile(bskyToken, targetDid)
                    com.mediaviewer.util.BlockedAccounts.blockUriFor(targetDid)
                }
                if (uri == null) { _errorMessage.value = "Couldn't unblock @${author.handle}"; return@launch }
                bskyRepo.unblockUser(bskyToken, _bskyDid.value, uri)
                    .onSuccess {
                        showToast("Unblocked @${author.handle}")
                        com.mediaviewer.util.BlockedAccounts.removeBlocking(targetDid)
                        _blockedAccounts.value = _blockedAccounts.value.filterNot { it.author.did == targetDid }
                        _mediaItems.value = _mediaItems.value.map {
                            if (it.author.did == targetDid) it.copy(isBlocked = false, blockUri = null) else it
                        }
                        markProfile(false)
                    }
                    .onFailure { _errorMessage.value = "Unblock failed: ${it.message}" }
            } else {
                bskyRepo.blockUser(bskyToken, _bskyDid.value, targetDid)
                    .onSuccess { uri ->
                        showToast("Blocked @${author.handle}")
                        com.mediaviewer.util.BlockedAccounts.addBlocking(targetDid, uri)
                        _blockedAccounts.value = listOf(BlockedAccount(author, uri)) +
                            _blockedAccounts.value.filterNot { it.author.did == targetDid }
                        _mediaItems.value = _mediaItems.value.map {
                            if (it.author.did == targetDid) it.copy(isBlocked = true, blockUri = uri) else it
                        }
                        markProfile(true)
                    }
                    .onFailure { _errorMessage.value = "Block failed: ${it.message}" }
            }
        }
    }

    /** More menu → Delete, on the signed-in account's own post. */
    fun deleteCurrentPost() {
        val item = currentItem.value ?: return
        if (_appMode.value != AppMode.BLUESKY) return
        if (item.postUri.isBlank() || item.author.did != _bskyDid.value) return
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.deletePost(bskyToken, _bskyDid.value, item.postUri)
                .onSuccess {
                    showToast("Post deleted")
                    val remaining = _mediaItems.value.filterNot { it.postUri == item.postUri }
                    _mediaItems.value = remaining
                    _currentIndex.value = _currentIndex.value.coerceAtMost((remaining.size - 1).coerceAtLeast(0))
                }
                .onFailure { _errorMessage.value = "Couldn't delete the post: ${it.message}" }
        }
    }

    // ── "Show more/less like this" (item 4) ─────────────────────────────────
    // Sends Bluesky's own feed-personalization interaction signal for
    // whichever post is currently on screen back to the AppView, which
    // forwards it on to the feed generator that actually supplied it.
    // Fire-and-forget from the UI's point of view — there's no per-post state
    // to reflect back (unlike like/repost/bookmark), so a failure here is
    // silent aside from the error banner; nothing needs reverting.
    fun sendShowMoreLikeThisForCurrentItem() {
        val item = currentItem.value ?: return
        if (_appMode.value != AppMode.BLUESKY) return
        val generatorDid = _selectedFeedUri.value?.let { _feedGeneratorDid.value[it] }
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.sendFeedInteraction(bskyToken, _bskyDid.value, item.postUri, wantMore = true, feedContext = item.feedContext, generatorDid = generatorDid)
                .onSuccess { showToast("Showing more like this") }
                .onFailure { _errorMessage.value = "Couldn't send feedback: ${it.message}" }
        }
    }

    fun sendShowLessLikeThisForCurrentItem() {
        val item = currentItem.value ?: return
        if (_appMode.value != AppMode.BLUESKY) return
        val generatorDid = _selectedFeedUri.value?.let { _feedGeneratorDid.value[it] }
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.sendFeedInteraction(bskyToken, _bskyDid.value, item.postUri, wantMore = false, feedContext = item.feedContext, generatorDid = generatorDid)
                .onSuccess { showToast("Showing less like this") }
                .onFailure { _errorMessage.value = "Couldn't send feedback: ${it.message}" }
        }
    }

    // ── "Add account to list" from the interaction bar's More menu (item 4) ──
    // Same underlying picker/flow as the existing auto-add-on-follow feature
    // (see openListPicker above) — just manually triggered for whichever
    // post's author is currently on screen, instead of automatically after a
    // follow.
    fun openListPickerForCurrentAuthor() {
        val item = currentItem.value ?: return
        if (_appMode.value != AppMode.BLUESKY) return
        openListPicker(item.author.did)
    }

    /** Feature request #6: the profile page's "Add To" interaction-bar
     *  button — same list-picker sheet as [openListPickerForCurrentAuthor],
     *  just for whichever profile is currently open rather than the
     *  currently-open post's author. */
    fun openListPickerForProfile(did: String) {
        if (_appMode.value != AppMode.BLUESKY) return
        openListPicker(did)
    }

    // ── Reporting (Bluesky moderation) ─────────────────────────────────────────

    private val _reportTarget = MutableStateFlow<com.mediaviewer.model.ReportTarget?>(null)
    /** What the Report popup is open for (null = closed). */
    val reportTarget: StateFlow<com.mediaviewer.model.ReportTarget?> = _reportTarget
    private val _reportSubmitting = MutableStateFlow(false)
    val reportSubmitting: StateFlow<Boolean> = _reportSubmitting

    /** The post on screen's More → Report. */
    fun openReportForCurrentPost() {
        val item = currentItem.value ?: return
        if (_appMode.value != AppMode.BLUESKY || item.postUri.isBlank() || item.postCid.isBlank()) return
        _reportTarget.value = com.mediaviewer.model.ReportTarget(
            author = item.author, postUri = item.postUri, postCid = item.postCid,
            postThumbUrl = item.thumbUrl.ifBlank { item.mediaUrl.takeUnless { item.isVideo }.orEmpty() },
            postText = item.text
        )
    }

    /** A profile page's More → Report. */
    fun openReportForProfile(author: AuthorInfo) {
        if (author.did.isBlank()) return
        _reportTarget.value = com.mediaviewer.model.ReportTarget(author = author, fromProfile = true)
    }

    fun dismissReport() {
        if (_reportSubmitting.value) return
        _reportTarget.value = null
    }

    fun submitReport(reason: com.mediaviewer.model.ReportReason, details: String) {
        val target = _reportTarget.value ?: return
        if (_reportSubmitting.value) return
        _reportSubmitting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            suspend fun send(): Result<Unit> =
                if (target.postUri != null && target.postCid != null)
                    bskyRepo.reportPost(bskyToken, target.postUri, target.postCid, reason, details)
                else bskyRepo.reportAccount(bskyToken, target.author.did, reason, details)
            var result = send()
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = send()
            }
            _reportSubmitting.value = false
            result.onSuccess {
                _reportTarget.value = null
                tapHaptic()
                showToast("Report sent to Bluesky's moderators. Thank you!")
            }.onFailure {
                showToast("Couldn't send the report: ${it.message}")
            }
        }
    }

    // ── Quote repost (item 5) ──────────────────────────────────────────────────

    fun openQuoteRepost() {
        val item = currentItem.value ?: return
        if (_appMode.value != AppMode.BLUESKY) return
        if (_appMode.value == AppMode.BLUESKY && blocksViewer(item)) { showToast("This user has you blocked"); return }
        _quoteRepostTarget.value = item
    }

    fun dismissQuoteRepost() {
        if (_quoteRepostSubmitting.value) return
        _quoteRepostTarget.value = null
    }

    fun submitQuoteRepost(text: String) {
        val item = _quoteRepostTarget.value ?: return
        if (_quoteRepostSubmitting.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _quoteRepostSubmitting.value = true
            bskyRepo.quoteRepost(bskyToken, _bskyDid.value, text, item.postUri, item.postCid)
                .onSuccess {
                    _quoteRepostSubmitting.value = false
                    _quoteRepostTarget.value = null
                    updateCurrentItem { if (it.id == item.id) it.copy(isQuoteReposted = true) else it }
                    showToast("Quote reposted")
                }
                .onFailure {
                    _quoteRepostSubmitting.value = false
                    _errorMessage.value = "Quote repost failed: ${it.message}"
                }
        }
    }

    // ── DMs / Send popup (item 6) ──────────────────────────────────────────────

    // Bug fix (this session): "Mutuals"/dmConversations autoloading was
    // reported as inconsistent — sometimes populated on cold start, often
    // not, only reliably fixed by manually opening the Send Post popup.
    // Root cause: at cold start, MainViewModel's init block fires off
    // loadDmConversations(silent=true) AND preloadFriendsFeed() (which
    // *also* independently loads dmConversations if empty) essentially
    // simultaneously, alongside loadFeed/loadAvailableFeeds/
    // prefetchUserLists/loadSelfProfile — six-plus concurrent network calls
    // at once, several of which (getMutuals) themselves fan out into
    // multiple paginated follows/followers requests. Any one of those
    // hitting a transient failure (timeout, 429, connection hiccup — much
    // more likely under this much simultaneous cold-start load) silently
    // resolved to an empty list via loadDmRecipients' getOrDefault, and
    // nothing ever retried afterward unless the user happened to trigger
    // openSendPopup(), which re-checked "isEmpty -> reload" and got a clean
    // shot at it (by then, the cold-start network storm had settled).
    // Two-part fix: (1) a mutex makes concurrent callers actually wait for
    // and share one in-flight load instead of racing duplicate requests
    // that make the congestion worse, and (2) the Hub's AT Protocol page
    // now also calls ensureDmConversationsLoaded() itself (see
    // AtProtocolPageContent's LaunchedEffect in SettingsSheet.kt) the same
    // way it already self-heals friendsReviews/liveFriends — so simply
    // opening the Hub is itself a retry, not just something that works if
    // you happen to open Send Post.
    // (dmConversationsMutex is declared near the top of the class, so it
    // exists before any init block can start work that uses it.)

    // Bug fix (see the init-block cache-hydration comment above): a plain
    // Gson().fromJson would throw — and, wrapped in runCatching, silently
    // discard the *entire* cached list — the moment any single cached
    // AuthorInfo/DmConversation object doesn't line up with the current
    // data class shape (a field renamed/added since that cache was written
    // by an older build). This lenient reader defaults every field instead
    // of leaving that to Gson's normal reflective (and much stricter about
    // matching the JSON exactly) deserialization, the same fix already
    // applied to LeafletBlog's own cache reader above.
    // Missing keys fall back to each field's default (AuthorInfo /
    // DmConversation declare defaults for every field), so older caches
    // still read — what the custom Gson deserializers used to do.
    private fun decodeDmCache(json: String?): List<DmConversation> =
        json?.takeIf { it.isNotBlank() }?.let { com.mediaviewer.json.StellarJson.default.decodeFromString<List<DmConversation>>(it) } ?: emptyList()

    private suspend fun ensureDmConversationsLoadedSuspend(silent: Boolean) {
        if (_dmConversations.value.isNotEmpty()) return
        dmConversationsMutex.withLock {
            // Re-check inside the lock: another caller may have already
            // finished loading while we were waiting for the lock.
            if (_dmConversations.value.isNotEmpty()) return@withLock
            loadDmConversationsBlocking(silent)
        }
    }

    fun loadDmConversations(silent: Boolean = false) {
        if (!_bskyLoggedIn.value) return
        viewModelScope.launch(Dispatchers.IO) { ensureDmConversationsLoadedSuspend(silent) }
    }

    /** Public, fire-and-forget entry point for the Hub's AT Protocol page to
     *  call every time it composes — no-ops instantly if already loaded or
     *  already in flight (via the mutex + isNotEmpty check above), so it's
     *  cheap to call unconditionally and gives the Mutuals row a real chance
     *  to self-heal if the cold-start load happened to fail. */
    fun ensureDmConversationsLoaded() {
        if (!_bskyLoggedIn.value) return
        viewModelScope.launch(Dispatchers.IO) { ensureDmConversationsLoadedSuspend(silent = true) }
    }

    // Feature (this session): Mutuals/Latest Reviews/Livestreams used to
    // only get ONE chance to load automatically — the single attempt fired
    // at cold start/login — with the Hub's own AT Protocol page composing
    // being the only other thing that ever retried it (via
    // ensureDmConversationsLoaded/loadFriendsReviewsIfNeeded/
    // loadLiveFriendsIfNeeded above). If that one cold-start attempt lost
    // the race against the rest of the app-launch network storm and failed
    // silently, and the user never happened to open the Hub, it simply
    // never loaded — no matter how long the app stayed open on the feed or
    // anywhere else. This starts a small set of background retry loops the
    // moment the user's logged in, entirely on viewModelScope — the same
    // ViewModel-lifetime scope every other background load in this app
    // already uses, which lives for as long as the Activity does (this
    // ViewModel is obtained via `by viewModels()` at the Activity level in
    // MainActivity, not scoped to any individual screen/composable), so it
    // is NOT tied to, and does not get cancelled or paused by, navigating
    // between the feed, Grid, Comments, or Hub — it runs identically no
    // matter which screen is currently showing, exactly like loadFeed() or
    // any other existing background call already does. Each loop backs off
    // and stops retrying once its data has actually loaded (or the user's
    // logged out), so a healthy app isn't left doing pointless work forever.
    private fun startHubBackgroundWarmup() {
        // Blocked accounts first: everything else filters by them.
        loadBlockedAccounts()
        // The Inbox's first page, a little after launch, so it opens instantly.
        viewModelScope.launch(Dispatchers.IO) { delay(2500); prefetchInbox() }
        viewModelScope.launch(Dispatchers.IO) {
            retryWithBackoff(isDone = { _dmConversations.value.isNotEmpty() || !_bskyLoggedIn.value }) {
                ensureDmConversationsLoadedSuspend(silent = true)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            retryWithBackoff(isDone = { reviewsBlogsLoaded || !_bskyLoggedIn.value }) {
                // loadFriendsReviewsIfNeeded() launches its own coroutine and
                // returns immediately (it's the same public entry point the
                // Hub page's LaunchedEffect calls) — wait for that in-flight
                // attempt to actually finish before this loop re-checks
                // isDone and possibly retries, otherwise every backoff tick
                // would pile a new attempt on top of a still-running one.
                loadFriendsReviewsIfNeeded()
                while (_friendsReviewsLoading.value) delay(300)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            retryWithBackoff(isDone = { liveFriendsLoaded || !_bskyLoggedIn.value }) {
                loadLiveFriendsIfNeeded()
                while (_liveFriendsLoading.value) delay(300)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            retryWithBackoff(isDone = { blueskyLiveNowLoaded || !_bskyLoggedIn.value }) {
                loadBlueskyLiveNowIfNeeded()
                while (_blueskyLiveNowLoading.value) delay(300)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            retryWithBackoff(isDone = { _selfProfile.value != null || !_bskyLoggedIn.value }) {
                loadSelfProfileSuspend()
            }
        }
    }

    // ── Real-time DMs ─────────────────────────────────────────────────────────
    // Per the architecture note's §2 (Direct Messages): there's no public
    // chat firehose/WebSocket the way Jetstream exists for repo commits, so
    // "real-time" here means short-interval polling of chat.bsky.convo.
    // getLog — a delta endpoint across ALL convos at once, driven by a saved
    // cursor — rather than re-fetching every conversation's full message
    // list on a timer (what a naive polling implementation would do, and
    // exactly the slow approach being replaced elsewhere in this session).
    private var dmLogCursor: String? = null
    private var dmLivePollingJob: kotlinx.coroutines.Job? = null

    fun startDmLivePolling() {
        if (dmLivePollingJob?.isActive == true || !_bskyLoggedIn.value) return
        dmLivePollingJob = viewModelScope.launch(Dispatchers.IO) {
            // Seed the cursor with one no-op call so the first real poll
            // only returns messages that arrive from here on, instead of
            // replaying recent history as if it just happened.
            bskyRepo.getConvoLog(bskyToken, _bskyDid.value, null).onSuccess { (_, cursor) -> dmLogCursor = cursor }
            while (_bskyLoggedIn.value) {
                delay(4000)
                // Paused while the app isn't on screen (it catches up from
                // the same cursor when you come back).
                if (!appInForeground) continue
                val result = bskyRepo.getConvoLog(bskyToken, _bskyDid.value, dmLogCursor)
                result.onSuccess { (logs, cursor) ->
                    cursor?.let { dmLogCursor = it }
                    if (logs.isNotEmpty()) applyDmLogEntries(logs)
                }
            }
        }
    }

    private fun applyDmLogEntries(logs: List<BskyConvoLogEntry>) {
        // Read somewhere else (the Bluesky app, another device): clear it here too.
        val readConvos = logs.filter { (it.type?.endsWith("#logReadMessage") == true || it.type?.endsWith("#logReadConvo") == true) && it.convoId != null }
            .map { it.convoId!! }.toSet()
        if (readConvos.isNotEmpty()) {
            _dmConversations.value = _dmConversations.value.map { if (it.convoId in readConvos) it.copy(unreadCount = 0) else it }
        }
        val messageEntries = logs.filter { it.convoId != null && it.message != null }
        if (messageEntries.isEmpty()) return

        val knownConvoIds = _dmConversations.value.map { it.convoId }.toSet()
        val hasUnknownConvo = messageEntries.any { it.convoId !in knownConvoIds }

        // A message in a convo we don't have locally yet (a brand-new convo,
        // or the very first message from someone we've never messaged) needs
        // that convo's member/profile info we don't have from the log alone
        // — cheapest correct fix is a normal refresh, same call the DM inbox
        // itself already uses. Existing convos are just bumped in place.
        if (hasUnknownConvo) {
            refreshDmConvosQuick()
        } else {
            val byConvo = messageEntries.groupBy { it.convoId!! }
            _dmConversations.value = _dmConversations.value.map { convo ->
                val latest = byConvo[convo.convoId]?.maxByOrNull { it.message!!.sentAt } ?: return@map convo
                val msg = latest.message!!
                val mine = msg.sender?.did == _bskyDid.value
                val openHere = _dmThread.value?.convo?.convoId == convo.convoId
                val newIncoming = byConvo[convo.convoId].orEmpty().count {
                    it.type?.endsWith("#logCreateMessage") == true && it.message?.sender?.did != _bskyDid.value
                }
                if (openHere && newIncoming > 0) viewModelScope.launch(Dispatchers.IO) { bskyRepo.markConvoRead(bskyToken, _bskyDid.value, convo.convoId) }
                convo.copy(
                    unreadCount = if (openHere) 0 else if (mine) 0 else convo.unreadCount + newIncoming,
                    lastActivityAt = msg.sentAt,
                    lastSentByUsAt = if (mine) msg.sentAt else convo.lastSentByUsAt,
                    // Group chats show the newest message under their name.
                    lastMessageText = if (!convo.isGroup) when {
                        msg.text.isNotBlank() -> if (mine) "You: ${msg.text}" else msg.text
                        msg.embed != null -> if (mine) "You shared a post" else "Shared a post"
                        else -> convo.lastMessageText
                    } else when {
                        msg.isSystem -> "Group updated"
                        msg.text.isNotBlank() -> {
                            val who = if (mine) "You" else (convo.groupMembers.firstOrNull { it.did == msg.sender?.did }
                                ?: bskyRepo.chatProfiles[msg.sender?.did ?: ""])?.displayName?.substringBefore(' ')
                            if (who != null) "$who: ${msg.text}" else msg.text
                        }
                        msg.embed != null -> "Shared a post"
                        else -> convo.lastMessageText
                    }
                )
            }.sortedByDescending { it.lastActivityAt.ifBlank { it.lastSentByUsAt } }
        }

        // Live-append into whichever thread is currently open, if any of
        // these messages belong to it — this is what makes an open DM
        // thread update in real time rather than only on next manual open.
        val openConvoId = _dmThread.value?.convo?.convoId ?: return
        val forOpenThread = messageEntries.filter { it.convoId == openConvoId }.mapNotNull { it.message }
        if (forOpenThread.isEmpty()) return
        val current = _dmThread.value ?: return
        val existingIds = current.messages.map { it.id }.toSet()
        val newOnes = forOpenThread.filterNot { it.id in existingIds }
        if (newOnes.isEmpty()) return
        val merged = (current.messages + newOnes).sortedBy { it.sentAt }
        _dmThread.value = current.copy(messages = merged, embeddedPosts = buildEmbeddedPosts(merged))
    }

    /** Retries [attempt] with exponential-ish backoff until [isDone] is true
     *  or [maxAttempts] is used up — bounded so a persistently broken case
     *  (e.g. genuinely no network) doesn't retry forever in the background. */
    private suspend fun retryWithBackoff(maxAttempts: Int = 6, isDone: () -> Boolean, attempt: suspend () -> Unit) {
        var delayMs = 3000L
        repeat(maxAttempts) {
            if (isDone()) return
            attempt()
            if (isDone()) return
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000L)
        }
    }

    /** Item (this session): the Mutuals row now has the same "instant from
     *  disk on cold start, live fetch replaces it" cache shape Reviews/Blogs
     *  already had (see loadFriendsReviewsIfNeeded's matching comment) —
     *  it used to just sit blank until this fetch resolved. `_dmConversations
     *  .value = it` on success is already a full replace (not a merge), so a
     *  mutual who's since been removed (unfollowed each other, blocked,
     *  etc.) already correctly drops out of both the in-memory state AND
     *  this cache the next time a fetch succeeds — nothing further needed
     *  for that half of the behavior. */
    private suspend fun loadDmConversationsBlocking(silent: Boolean = false) {
        _dmConversationsLoading.value = true
        if (_dmConversations.value.isEmpty()) {
            runCatching {
                val cached: List<DmConversation> = decodeDmCache(prefs.hubMutualsCacheJson.first())
                if (cached.isNotEmpty()) _dmConversations.value = cached
            }
        }
        bskyRepo.loadDmRecipients(bskyToken, _bskyDid.value)
            .onSuccess {
                _dmConversations.value = it
                runCatching {
                    prefs.setHubMutualsCache(com.mediaviewer.json.StellarJson.default.encodeToString<List<DmConversation>>(it))
                }
            }
            .onFailure {
                // Only surface an error when the user is actively, visibly waiting on this
                // (opening the share sheet). Background warm-ups (app open, From Friends
                // preload) retry silently — the DM/From Friends UI itself retries live and
                // reports its own failure if that also doesn't pan out, so a banner here
                // would just be a confusing, non-actionable false alarm.
                if (!silent) _errorMessage.value = "Couldn't load DMs: ${it.message}"
            }
        _dmConversationsLoading.value = false
    }

    fun openSendPopup() {
        val item = currentItem.value ?: return
        if (_appMode.value != AppMode.BLUESKY) return
        _sendPopupTarget.value = item
        _sendPopupSelected.value = emptySet()
        if (_dmConversations.value.isEmpty()) loadDmConversations()
    }

    /** A profile page's Share button: the same "Share with" popup as posts,
     *  sending the profile's bsky.app link (the way Bluesky shares profiles
     *  in chats — a tappable link; chat embeds only carry posts). */
    fun openShareProfile(author: AuthorInfo) {
        if (author.did.isBlank()) return
        _sendPopupTarget.value = MediaItem(
            id = PROFILE_SHARE_PREFIX + author.did,
            author = author,
            thumbUrl = author.avatarUrl.orEmpty(),
            text = "https://bsky.app/profile/" + author.handle.ifBlank { author.did }
        )
        _sendPopupSelected.value = emptySet()
        if (_dmConversations.value.isEmpty()) loadDmConversations()
    }

    fun dismissSendPopup() {
        if (_sendPopupSending.value) return
        _sendPopupTarget.value = null
        _sendPopupSelected.value = emptySet()
    }

    fun toggleSendRecipient(did: String) {
        _sendPopupSelected.value =
            if (_sendPopupSelected.value.contains(did)) _sendPopupSelected.value - did
            else _sendPopupSelected.value + did
    }

    fun sendToSelectedRecipients(message: String) {
        val item = _sendPopupTarget.value ?: return
        val recipients = _dmConversations.value.filter { _sendPopupSelected.value.contains(it.member.did) }
        if (recipients.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _sendPopupSending.value = true
            var failures = 0
            var lastError: String? = null
            recipients.forEach { convo ->
                val convoId = convo.convoId.ifBlank {
                    bskyRepo.getOrCreateConvo(bskyToken, _bskyDid.value, listOf(convo.member.did))
                        .onFailure { lastError = it.message }
                        .getOrNull()
                }
                if (convoId.isNullOrBlank()) {
                    failures++
                } else {
                    if (item.id.startsWith(PROFILE_SHARE_PREFIX)) {
                        val text = listOf(message.trim(), item.text).filter { it.isNotBlank() }.joinToString("\n")
                        bskyRepo.sendMessage(bskyToken, _bskyDid.value, convoId, text)
                    } else {
                        bskyRepo.sendMessage(bskyToken, _bskyDid.value, convoId, message, item.postUri, item.postCid)
                    }
                        .onFailure { failures++; lastError = it.message }
                }
            }
            _sendPopupSending.value = false
            _sendPopupTarget.value = null
            _sendPopupSelected.value = emptySet()
            if (failures == 0) showToast("Sent")
            else _errorMessage.value = "Send failed (${recipients.size - failures}/${recipients.size} sent): $lastError"
        }
    }



    // Bug fix (this session): switching to the e621/AT Protocol Hub pages
    // flips the active AppMode (see goToHubPage's onSwitchMode calls in
    // SettingsSheet.kt — viewing the e621 Hub page is, by design, meant to
    // make e621 the active mode so swiping back down to the feed shows e621
    // content matching the Hub page you were just on). The bug: swiping back
    // to the AT Protocol Hub page flips the mode back to Bluesky just as
    // legitimately, but `setMode` used to always call loadFeed()/
    // loadE621Posts() on every switch, which resets to page 1 and index 0
    // and re-fetches from the network — so a simple round trip through the
    // e621 Hub page and back (or Settings and back, if that also happens to
    // pass through a different mode) silently blew away exactly where the
    // user was in their feed. These two caches snapshot each mode's feed
    // (items, scroll index, and pagination cursor) the moment you switch
    // away from it, and restore that snapshot instead of re-fetching when
    // you switch back — network only happens the first time a mode is ever
    // activated. An explicit refresh (switching away and back on purpose to
    // force a reload, or tapping the already-open feed's own button) still
    // works exactly as before, since neither of those paths go through this
    // restore branch.
    private var cachedBlueskyFeed: FeedSnapshot? = null
    private var cachedE621Feed: FeedSnapshot? = null
    private data class FeedSnapshot(
        val items: List<MediaItem>, val index: Int, val cursor: String?,
        val activeMode: ActiveFeedMode, val activeActorDid: String?, val authorFeedState: AuthorFeedSavedState?
    )

    fun setMode(mode: AppMode) {
        // Bug fix (Outstanding Issue #1 — diagnostic, temporary): logs every
        // real call (the same-mode no-op above returns before this, so this
        // only fires on genuine switches) so a logcat capture during a
        // repro can show exactly when/how often this fires. Safe to leave
        // in — remove once the bug's fully confirmed fixed.
        Log.d("Stellar-FeedState", "setMode: ${_appMode.value} -> $mode")
        if (_appMode.value == mode) return // already there — nothing to switch, nothing to reload
        // Snapshot whichever mode we're leaving before touching anything.
        when (_appMode.value) {
            AppMode.BLUESKY -> cachedBlueskyFeed = FeedSnapshot(
                _mediaItems.value, _currentIndex.value, feedCursor, activeFeedMode, activeFeedActorDid, _authorFeedState.value
            )
            AppMode.E621 -> cachedE621Feed = FeedSnapshot(
                _mediaItems.value, _currentIndex.value, null, ActiveFeedMode.NORMAL, null, null
            )
        }
        _appMode.value = mode
        viewModelScope.launch { prefs.setLastMode(mode.name) }
        when (mode) {
            AppMode.E621 -> {
                if (!_e621LoggedIn.value) { _screenState.value = ScreenState.SETTINGS; return }
                val cached = cachedE621Feed
                if (cached != null) { _mediaItems.value = cached.items; _currentIndex.value = cached.index }
                else loadE621Posts()
            }
            AppMode.BLUESKY -> {
                if (!_bskyLoggedIn.value) { _screenState.value = ScreenState.SETTINGS; return }
                val cached = cachedBlueskyFeed
                if (cached != null) {
                    _mediaItems.value = cached.items; _currentIndex.value = cached.index; feedCursor = cached.cursor
                    activeFeedMode = cached.activeMode; activeFeedActorDid = cached.activeActorDid
                    _authorFeedState.value = cached.authorFeedState
                    // Bug fix: this used to unconditionally call
                    // loadAvailableFeeds() on every single restore, which
                    // (before the fix on loadAvailableFeeds() itself, above)
                    // could stomp the snapshot just restored one line above
                    // whenever the user was on the implicit null-URI
                    // "Following" timeline. Now skipped entirely once the
                    // feed-switcher chip row has already been populated —
                    // there's no need to keep re-fetching that list on every
                    // mode round-trip, only the very first time.
                    if (_availableFeeds.value.isEmpty()) loadAvailableFeeds()
                } else { loadFeed(); loadAvailableFeeds() }
            }
        }
    }

    fun setScreen(screen: ScreenState) {
        // Leaving a feed that was opened from a profile's Lists/Feeds tab
        // (swipe down, Back) goes back to that profile, not the Hub.
        if (screen == ScreenState.SETTINGS && isProfileFeedActive()) { closeProfileFeed(); return }
        _navDirection.value = when {
            screen == ScreenState.COMMENTS -> 1
            screen == ScreenState.FEED && _screenState.value == ScreenState.COMMENTS -> -1
            screen == ScreenState.SETTINGS -> -1
            screen == ScreenState.FEED && _screenState.value == ScreenState.SETTINGS -> 1
            else -> 0
        }
        _screenState.value = screen
        if (screen == ScreenState.COMMENTS) {
            loadComments()
            attachAiTagsToCurrentItem()
        }
        // Item 3: the Settings "Profile" button was only ever populated by the
        // one loadSelfProfile() fired at app startup/login. If that request
        // hadn't finished (or had failed) by the time the person actually
        // opened Settings, the button was stuck grey for the rest of the
        // session with nothing to retry it. Re-check every time Settings
        // opens so a missed/failed load gets a fresh attempt.
        if (screen == ScreenState.SETTINGS && _bskyLoggedIn.value && _selfProfile.value == null) loadSelfProfile()
    }

    /** Every path that opens the comments sheet — main feed, grid, profile,
     *  DMs, wherever — funnels through [setScreen]\(COMMENTS\), so this is
     *  the one place a lazy local-DB lookup covers all of them, instead of
     *  only the Liked-tab search path ([openLikedPostFromSearch] below,
     *  which is now just the "tags are already known, skip the DB round
     *  trip" fast path for that one specific entry point). e621-mode posts
     *  already carry real API tags in `tags` and are skipped; only blank
     *  Bluesky-mode posts trigger a lookup. No-ops instead of racing if the
     *  person navigates away before the (local, near-instant, but still
     *  async) DB query resolves. */
    private fun attachAiTagsToCurrentItem() {
        val idx = _currentIndex.value
        val item = _mediaItems.value.getOrNull(idx) ?: return
        if (item.tags.isNotBlank() || item.postUri.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val aiTags = taggingRepo.tagsForPost(item.postUri)
            if (aiTags.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val list = _mediaItems.value.toMutableList()
                val current = list.getOrNull(idx) ?: return@withContext
                if (current.postUri == item.postUri && current.tags.isBlank()) {
                    list[idx] = current.copy(tags = aiTags.joinToString(" "))
                    _mediaItems.value = list
                }
            }
        }
    }

    fun navigateNext() {
        val next = _currentIndex.value + 1
        if (next < _mediaItems.value.size) {
            _navDirection.value = 1
            _currentIndex.value = next
            // Fetch the next page well before the end is reached.
            if (next >= _mediaItems.value.size - 15) loadMore()
        }
    }

    fun navigatePrev() {
        val prev = _currentIndex.value - 1
        if (prev >= 0) {
            _navDirection.value = -1
            _currentIndex.value = prev
        }
    }

    fun navigateTo(index: Int) {
        if (index in _mediaItems.value.indices) {
            _navDirection.value = if (index > _currentIndex.value) 1 else -1
            _currentIndex.value = index
            _screenState.value  = ScreenState.FEED
        }
    }

    // ── Social Actions (optimistic updates) ───────────────────────────────────

    /** Bluesky doesn't let you like, repost, quote or follow someone who's
     *  blocked you (its own app refuses too), so those are switched off. */
    private fun blocksViewer(item: MediaItem): Boolean =
        item.authorBlocksViewer || com.mediaviewer.util.BlockedAccounts.isBlockedBy(item.author.did)

    fun toggleLike() {
        val item = currentItem.value ?: return
        if (_appMode.value == AppMode.BLUESKY && blocksViewer(item)) { showToast("This user has you blocked"); return }
        if (_appMode.value == AppMode.BLUESKY) {
            if (item.isLiked) {
                // Optimistic unlike
                updateCurrentItem { it.copy(isLiked = false, likeUri = null, likeCount = (it.likeCount - 1).coerceAtLeast(0)) }
                viewModelScope.launch(Dispatchers.IO) {
                    bskyRepo.unlikePost(bskyToken, _bskyDid.value, item.likeUri ?: return@launch)
                        .onFailure { updateCurrentItem { it.copy(isLiked = true, likeUri = item.likeUri, likeCount = item.likeCount) } }
                }
            } else {
                // Optimistic like
                updateCurrentItem { it.copy(isLiked = true, likeCount = it.likeCount + 1) }
                viewModelScope.launch(Dispatchers.IO) {
                    bskyRepo.likePost(bskyToken, _bskyDid.value, item.postUri, item.postCid)
                        .onSuccess { uri ->
                            updateCurrentItem { it.copy(likeUri = uri) }
                            if (_downloadOnLike.value) {
                                enqueueDownload(item)
                                updateCurrentItem { it.copy(isDownloaded = true) }
                            }
                            maybeTagOnLike(item)
                        }
                        .onFailure { updateCurrentItem { it.copy(isLiked = false, likeCount = item.likeCount) } }
                }
            }
        }
    }

    fun toggleRepost() {
        val item = currentItem.value ?: return
        if (_appMode.value != AppMode.BLUESKY) return
        if (_appMode.value == AppMode.BLUESKY && blocksViewer(item)) { showToast("This user has you blocked"); return }
        if (item.isReposted) {
            updateCurrentItem { it.copy(isReposted = false, repostUri = null, repostCount = (it.repostCount - 1).coerceAtLeast(0)) }
            viewModelScope.launch(Dispatchers.IO) {
                bskyRepo.unrepost(bskyToken, _bskyDid.value, item.repostUri ?: return@launch)
                    .onFailure { updateCurrentItem { it.copy(isReposted = true, repostUri = item.repostUri, repostCount = item.repostCount) } }
            }
        } else {
            updateCurrentItem { it.copy(isReposted = true, repostCount = it.repostCount + 1) }
            viewModelScope.launch(Dispatchers.IO) {
                bskyRepo.repostPost(bskyToken, _bskyDid.value, item.postUri, item.postCid)
                    .onSuccess { uri -> updateCurrentItem { it.copy(repostUri = uri) } }
                    .onFailure { updateCurrentItem { it.copy(isReposted = false, repostCount = item.repostCount) } }
            }
        }
    }

    fun toggleBookmark() {
        val item = currentItem.value ?: return
        if (_appMode.value == AppMode.E621) {
            val pid = item.e621PostId ?: return
            if (item.isBookmarked) {
                updateCurrentItem { it.copy(isBookmarked = false) }
                viewModelScope.launch(Dispatchers.IO) {
                    e621Repo.removeFavorite(e621Username, e621ApiKey, pid)
                        .onFailure { updateCurrentItem { it.copy(isBookmarked = true) } }
                }
            } else {
                updateCurrentItem { it.copy(isBookmarked = true) }
                viewModelScope.launch(Dispatchers.IO) {
                    e621Repo.addFavorite(e621Username, e621ApiKey, pid)
                        .onSuccess {
                            if (_downloadOnLike.value) {
                                enqueueDownload(item)
                                updateCurrentItem { it.copy(isDownloaded = true) }
                            }
                            maybeTagOnLike(item)
                        }
                        .onFailure { updateCurrentItem { it.copy(isBookmarked = false) } }
                }
            }
        } else {
            val wasBookmarked = item.isBookmarked
            updateCurrentItem { it.copy(isBookmarked = !wasBookmarked) }
            viewModelScope.launch(Dispatchers.IO) {
                if (wasBookmarked) {
                    bskyRepo.removeBookmark(bskyToken, item.postUri)
                        .onFailure { updateCurrentItem { it.copy(isBookmarked = true) } }
                } else {
                    bskyRepo.addBookmark(bskyToken, item.postUri, item.postCid)
                        .onFailure { updateCurrentItem { it.copy(isBookmarked = false) } }
                }
            }
        }
    }

    fun e621Vote(vote: Int) {
        val item = currentItem.value ?: return
        val pid  = item.e621PostId ?: return
        val newVote = if (item.e621UserVote == vote) 0 else vote
        updateCurrentItem { it.copy(e621UserVote = newVote) }
        viewModelScope.launch(Dispatchers.IO) {
            e621Repo.votePost(e621Username, e621ApiKey, pid, if (newVote == 0) (vote * -1) else newVote)
                .onFailure { updateCurrentItem { it.copy(e621UserVote = item.e621UserVote) } }
        }
    }

    fun toggleFollow() {
        if (_appMode.value == AppMode.E621) { toggleE621Follow(); return }
        val item   = currentItem.value ?: return
        if (_appMode.value == AppMode.BLUESKY && blocksViewer(item)) { showToast("This user has you blocked"); return }
        val author = item.author
        if (author.isFollowing) {
            updateCurrentItemAuthor { it.copy(isFollowing = false, followingUri = null) }
            viewModelScope.launch(Dispatchers.IO) {
                bskyRepo.unfollowUser(bskyToken, _bskyDid.value, author.followingUri ?: return@launch)
                    .onFailure { updateCurrentItemAuthor { it.copy(isFollowing = true, followingUri = author.followingUri) } }
            }
        } else {
            updateCurrentItemAuthor { it.copy(isFollowing = true) }
            viewModelScope.launch(Dispatchers.IO) {
                bskyRepo.followUser(bskyToken, _bskyDid.value, author.did)
                    .onSuccess { uri ->
                        updateCurrentItemAuthor { it.copy(followingUri = uri) }
                        // Item 2: only auto-open the "Add To" popup if the user opted in
                        if (_autoAddToOnFollow.value) openListPicker(author.did)
                    }
                    .onFailure { updateCurrentItemAuthor { it.copy(isFollowing = false) } }
            }
        }
    }

    /** Warms Coil's cache for each list's custom icon in the background, so the
     *  Add To menu — including the merged List/Starter Pack view, which shows the
     *  real List icon rather than the generic one — opens with icons already
     *  loaded instead of popping in one by one. Starter packs have no custom
     *  icon of their own in this app (they show the generic icon), so only list
     *  avatars need prefetching. */
    private fun prefetchListAvatars(lists: List<BskyList>) {
        platform.preloadImages(lists.mapNotNull { it.avatar }.distinct())
    }

    /** Prefetch user's lists and starter packs in the background.
     *  Called right after login so the picker opens instantly. */
    private fun prefetchUserLists() {
        if (!_bskyLoggedIn.value || _bskyDid.value.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val listJob = launch {
                bskyRepo.getUserLists(bskyToken, _bskyDid.value)
                    .onSuccess { _userLists.value = it; prefetchListAvatars(it) }
            }
            val packJob = launch {
                bskyRepo.getUserStarterPacks(bskyToken, _bskyDid.value)
                    .onSuccess { _userStarterPacks.value = it }
            }
            listJob.join(); packJob.join()
        }
    }

    // Add To's + / − buttons: which lists (by list URI) the picked account
    // is already on, with the list item to delete when removing them.
    private val _listMemberships = MutableStateFlow<Map<String, String>>(emptyMap())
    val listMemberships: StateFlow<Map<String, String>> = _listMemberships
    private val _listMembershipsLoading = MutableStateFlow(false)
    val listMembershipsLoading: StateFlow<Boolean> = _listMembershipsLoading
    /** List URIs with an add/remove in flight. */
    private val _listMembershipBusy = MutableStateFlow<Set<String>>(emptySet())
    val listMembershipBusy: StateFlow<Set<String>> = _listMembershipBusy
    private var listMembershipJob: Job? = null

    /** Memberships looked up recently, by account (did -> time, list URI -> item URI). */
    private val listMembershipCache = com.mediaviewer.platform.ConcurrentHashMap<String, Pair<Long, Map<String, String>>>()
    private val listMembershipFetches = com.mediaviewer.platform.ConcurrentHashMap<String, Job>()

    /** Starts looking up which of your lists [did] is on before Add To is
     *  even opened (the More menu or a profile page opening), so the
     *  + / − buttons are already right the moment it appears. */
    fun prefetchListMemberships(did: String) {
        if (!_bskyLoggedIn.value || did.isBlank()) return
        val cached = listMembershipCache[did]
        if (cached != null && com.mediaviewer.platform.currentTimeMillis() - cached.first < 5 * 60_000L) return
        fetchListMemberships(did)
    }

    private fun fetchListMemberships(did: String): Job {
        listMembershipFetches[did]?.takeIf { it.isActive }?.let { return it }
        val job = viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.getListMemberships(bskyToken, _bskyDid.value, did).onSuccess { map ->
                listMembershipCache[did] = com.mediaviewer.platform.currentTimeMillis() to map
                if (_listPickerTargetDid.value == did) _listMemberships.value = map
            }
            if (_listPickerTargetDid.value == did) _listMembershipsLoading.value = false
        }
        listMembershipFetches[did] = job
        return job
    }

    private fun loadListMemberships(targetDid: String) {
        val cached = listMembershipCache[targetDid]
        // Known already: shown straight away, refreshed quietly underneath.
        _listMemberships.value = cached?.second ?: emptyMap()
        _listMembershipsLoading.value = cached == null
        listMembershipJob = fetchListMemberships(targetDid)
    }

    /** Add To's + / − button: adds the account to [listUri] (and, merged
     *  with a same-named starter pack, to [additionalListUri] too), or takes
     *  them back off if they're already on it. The popup stays open. */
    fun toggleListMembership(listUri: String, additionalListUri: String? = null) {
        val targetDid = _listPickerTargetDid.value ?: return
        if (listUri in _listMembershipBusy.value) return
        val uris = listOfNotNull(listUri, additionalListUri)
        _listMembershipBusy.value = _listMembershipBusy.value + listUri
        tapHaptic()
        viewModelScope.launch(Dispatchers.IO) {
            // Tapped before the lookup finished: let it finish first, so an
            // account that's already on the list isn't added twice.
            if (_listMembershipsLoading.value) listMembershipJob?.join()
            val removing = _listMemberships.value.containsKey(listUri)
            var failed: String? = null
            for (uri in uris) {
                if (removing) {
                    val itemUri = _listMemberships.value[uri] ?: continue
                    bskyRepo.removeFromList(bskyToken, _bskyDid.value, itemUri)
                        .onSuccess { _listMemberships.value = _listMemberships.value - uri }
                        .onFailure { failed = "Couldn't remove them: ${it.message}" }
                } else {
                    if (_listMemberships.value.containsKey(uri)) continue
                    bskyRepo.addToList(bskyToken, _bskyDid.value, uri, targetDid)
                        .onSuccess { itemUri ->
                            _listMemberships.value = _listMemberships.value + (uri to itemUri)
                            // Add To sorts by most recently added-to (on-device only).
                            com.mediaviewer.util.ListRecency.noteAdded(uri)
                        }
                        .onFailure { failed = "Couldn't add them: ${it.message}" }
                }
            }
            failed?.let { _errorMessage.value = it }
            listMembershipCache[targetDid] = com.mediaviewer.platform.currentTimeMillis() to _listMemberships.value
            // Keep the member counts under each list honest.
            if (failed == null) {
                val delta = if (removing) -1 else 1
                _userLists.value = _userLists.value.map { l ->
                    if (l.uri in uris) l.copy(itemCount = ((l.itemCount ?: 0) + delta).coerceAtLeast(0)) else l
                }
                _userStarterPacks.value = _userStarterPacks.value.map { p ->
                    if (p.record?.list?.let { it in uris } == true) p.copy(listItemCount = ((p.listItemCount ?: 0) + delta).coerceAtLeast(0)) else p
                }
            }
            _listMembershipBusy.value = _listMembershipBusy.value - listUri
        }
    }

    /** Add To → double-tap a name → rename. [recordUris]: every record to
     *  rename (a list; a starter pack and its own list; or, from the Both
     *  tab, the list plus the starter pack and its list). [onDone] gets null
     *  on success or an error. */
    fun renamePickerEntry(recordUris: List<String>, newName: String, onDone: (String?) -> Unit) {
        val clean = newName.trim()
        if (clean.isBlank() || recordUris.isEmpty()) { onDone(null); return }
        viewModelScope.launch(Dispatchers.IO) {
            var error: String? = null
            for (uri in recordUris.distinct()) {
                bskyRepo.renameNamedRecord(bskyToken, _bskyDid.value, uri, clean).onFailure { error = it.message ?: "Couldn't rename it" }
            }
            val renamed = if (error == null) recordUris.toSet() else emptySet()
            _userLists.value = _userLists.value.map { if (it.uri in renamed) it.copy(name = clean) else it }
            _userStarterPacks.value = _userStarterPacks.value.map { p ->
                if (p.uri in renamed) p.copy(record = p.record?.copy(name = clean)) else p
            }
            withContext(Dispatchers.Main) {
                error?.let { _errorMessage.value = it }
                // A renamed list that's also a Hub row: the row takes the new
                // name, and reloads once Bluesky has re-indexed the list
                // (straight after a rename it can briefly fail to load).
                renamed.forEach { com.mediaviewer.util.HubLayout.renameList(it, clean) }
                onDone(error)
            }
            val hubRows = renamed.filter { u -> com.mediaviewer.util.HubLayout.rows.any { it.listUri == u } }
            if (hubRows.isNotEmpty()) {
                delay(4_000)
                hubRows.forEach { loadHubListIfNeeded(it, force = true) }
            }
        }
    }

    private val _creatingPickerList = MutableStateFlow(false)
    val creatingPickerList: StateFlow<Boolean> = _creatingPickerList

    /** Add To → "Create new …". [kind]: "LISTS", "MODLISTS", "STARTER_PACKS"
     *  or "BOTH" (a list and a starter pack under the same name, which Add
     *  To's Both tab then treats as one). The new entry appears at the top
     *  of its tab, ready for its + button. */
    fun createPickerList(kind: String, name: String, description: String, coverUri: com.mediaviewer.platform.PlatformUri?, onDone: (String?) -> Unit) {
        val clean = name.trim()
        if (clean.isBlank() || _creatingPickerList.value) return
        _creatingPickerList.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val context = platform.context
            val me = _bskyDid.value
            val result = runCatching {
                if (kind == "LISTS" || kind == "MODLISTS" || kind == "BOTH") {
                    val purpose = if (kind == "MODLISTS") "modlist" else "curatelist"
                    val uri = bskyRepo.createList(bskyToken, me, clean, description.trim(), purpose, context, coverUri).getOrThrow()
                    val newList = BskyList(
                        uri = uri, cid = "", name = clean, purpose = "app.bsky.graph.defs#$purpose",
                        description = description.trim().ifBlank { null }, avatar = coverUri?.toString(), itemCount = 0
                    )
                    _userLists.value = listOf(newList) + _userLists.value
                }
                if (kind == "STARTER_PACKS" || kind == "BOTH") {
                    val (packUri, listUri) = bskyRepo.createStarterPack(bskyToken, me, clean, description.trim()).getOrThrow()
                    val pack = BskyStarterPackView(
                        uri = packUri, cid = "",
                        record = BskyStarterPackRecord(type = "app.bsky.graph.starterpack", name = clean, description = description.trim().ifBlank { null }, list = listUri),
                        listItemCount = 1
                    )
                    _userStarterPacks.value = listOf(pack) + _userStarterPacks.value
                }
            }
            _creatingPickerList.value = false
            withContext(Dispatchers.Main) {
                val err = result.exceptionOrNull()?.message
                if (err != null) _errorMessage.value = "Couldn't create it: $err" else showToast("Created \"$clean\"")
                onDone(err)
            }
        }
    }

    private fun openListPicker(targetDid: String) {
        _listPickerTargetDid.value = targetDid
        loadListMemberships(targetDid)
        // If lists are already cached from prefetch, show immediately
        if (_userLists.value.isNotEmpty() || _userStarterPacks.value.isNotEmpty()) {
            _userListsLoading.value = false
            return
        }
        // Otherwise fetch now (first login or cleared cache)
        viewModelScope.launch(Dispatchers.IO) {
            _userListsLoading.value = true
            val listJob = launch {
                bskyRepo.getUserLists(bskyToken, _bskyDid.value)
                    .onSuccess { _userLists.value = it; prefetchListAvatars(it) }
            }
            val packJob = launch {
                bskyRepo.getUserStarterPacks(bskyToken, _bskyDid.value)
                    .onSuccess { _userStarterPacks.value = it }
            }
            listJob.join(); packJob.join()
            _userListsLoading.value = false
        }
    }

    fun dismissListPicker() {
        _listPickerTargetDid.value = null
        _listMembershipsLoading.value = false
    }

    fun addAccountToList(listUri: String, additionalListUri: String? = null) {
        val targetDid = _listPickerTargetDid.value ?: return
        _listPickerTargetDid.value = null
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.addToList(bskyToken, _bskyDid.value, listUri, targetDid)
                .onSuccess { com.mediaviewer.util.ListRecency.noteAdded(listUri); showToast("Added to list") }
                .onFailure { _errorMessage.value = "Add to list failed: ${it.message}" }
            if (additionalListUri != null) {
                bskyRepo.addToList(bskyToken, _bskyDid.value, additionalListUri, targetDid)
                    .onSuccess { showToast("Added to starter pack") }
                    .onFailure { _errorMessage.value = "Add to starter pack failed: ${it.message}" }
            }
        }
    }

    fun downloadCurrentItem() {
        val item = currentItem.value ?: return
        if (item.isTextOnly) return
        enqueueDownload(item)
        updateCurrentItem { it.copy(isDownloaded = true) }
    }

    /** Downloads the current post's media as a full-quality GIF (item 4). Images
     *  are saved losslessly (no re-encoding); only video is truly re-encoded into
     *  an animated GIF, since that's the only way to get a real multi-frame GIF. */
    fun downloadCurrentItemAsGif() {
        val item = currentItem.value ?: return
        if (item.isTextOnly) return
        if (item.mediaGroup.size > 1) {
            item.mediaGroup.forEachIndexed { i, img ->
                platform.enqueueGifDownload(img.mediaUrl, false, "gif_${item.id}_$i")
            }
        } else {
            val sourceUrl = if (item.isVideo) (item.videoPlaylistUrl.takeUnless { it.isNullOrBlank() } ?: item.mediaUrl) else item.mediaUrl
            val did = item.author.did.takeIf { item.isVideo && it.isNotBlank() }
            val cid = item.videoBlobCid.takeIf { item.isVideo }
            platform.enqueueGifDownload(sourceUrl, item.isVideo, "gif_${item.id}", blobDid = did, blobCid = cid)
        }
        updateCurrentItem { it.copy(isGifDownloaded = true) }
    }

    // ── Comments ──────────────────────────────────────────────────────────────

    // Comments are fetched as soon as a post settles on screen (not when the
    // sheet opens), so swiping up shows them straight away with no spinner.
    // A small per-post cache keeps swiping back and forth free.
    private class CachedComments(val atMs: Long, val list: List<CommentItem>)
    // Least-recently-used, at most 30 posts.
    private val commentsCache = com.mediaviewer.util.LruMap<String, CachedComments>(30)
    @kotlin.concurrent.Volatile private var commentsShownFor: String? = null
    private fun commentKey(item: MediaItem) = "${_appMode.value}:${item.id}"
    private fun cachedComments(key: String): CachedComments? = com.mediaviewer.platform.synchronizedCompat(commentsCache) { commentsCache[key] }
    private fun putCachedComments(key: String, list: List<CommentItem>) {
        com.mediaviewer.platform.synchronizedCompat(commentsCache) {
            commentsCache[key] = CachedComments(com.mediaviewer.platform.currentTimeMillis(), list)
        }
    }

    init {
        viewModelScope.launch {
            currentItem.distinctUntilChangedBy { it?.id }.collectLatest { item ->
                if (item == null) return@collectLatest
                showCommentsFor(item)
                // Don't fetch for every post flicked straight past.
                delay(350)
                val key = commentKey(item)
                if (cachedComments(key) == null) {
                    if (_appMode.value == AppMode.BLUESKY && item.replyCount == 0 && item.postUri.isNotBlank()) {
                        putCachedComments(key, emptyList())
                        if (commentsShownFor == key) { _comments.value = emptyList(); _commentsLoading.value = false }
                    } else {
                        fetchComments(item)
                    }
                }
            }
        }
    }

    /** Points the sheet at [item]'s comments (cached ones right away). */
    private fun showCommentsFor(item: MediaItem) {
        val key = commentKey(item)
        if (commentsShownFor == key) return
        commentsShownFor = key
        val cached = cachedComments(key)
        _comments.value = cached?.list ?: emptyList()
        _commentsLoading.value = cached == null
    }

    private fun fetchComments(item: MediaItem) {
        val key = commentKey(item)
        viewModelScope.launch(Dispatchers.IO) {
            if (commentsShownFor == key && _comments.value.isEmpty()) _commentsLoading.value = true
            val result: Result<List<CommentItem>>? = if (_appMode.value == AppMode.BLUESKY) {
                if (item.postUri.isBlank()) null else bskyRepo.getPostThread(bskyToken, item.postUri)
            } else {
                item.e621PostId?.let { pid -> e621Repo.getComments(e621Username, e621ApiKey, pid) }
            }
            result?.onSuccess { list ->
                putCachedComments(key, list)
                if (commentsShownFor == key) _comments.value = list
            }?.onFailure {
                // A background prefetch failing stays quiet; only report it
                // when the sheet is actually open on this post.
                if (commentsShownFor == key && _screenState.value == ScreenState.COMMENTS) _errorMessage.value = it.message
            }
            if (commentsShownFor == key) _commentsLoading.value = false
        }
    }

    /** Opening the sheet / after posting: show what's cached and refresh if
     *  it's missing, stale (2 min) or [force]d. */
    private fun loadComments(force: Boolean = false) {
        val item = currentItem.value ?: return
        showCommentsFor(item)
        val cached = cachedComments(commentKey(item))
        if (force || cached == null || com.mediaviewer.platform.currentTimeMillis() - cached.atMs > 120_000) fetchComments(item)
    }

    // Item 20: replying to a specific comment now actually threads the reply
    // under that comment (parent = the tapped comment's own uri/cid) instead
    // of always posting a fresh top-level reply to the post with just an
    // "@handle" tacked onto the text. The root stays the original post, same
    // as Bluesky's own reply-thread semantics.
    fun postComment(text: String, replyTo: CommentItem? = null) {
        val item = currentItem.value ?: return
        // Item 8: haptic tap on sending a comment.
        tapHaptic()
        viewModelScope.launch(Dispatchers.IO) {
            if (_appMode.value == AppMode.BLUESKY) {
                val parentUri = replyTo?.uri?.takeIf { it.isNotBlank() } ?: item.postUri
                val parentCid = replyTo?.cid?.takeIf { it.isNotBlank() } ?: item.postCid
                // The post on screen may itself be a reply: then the root is
                // its thread's root, not the post.
                val root = bskyRepo.threadRootOf(item.postUri)
                bskyRepo.replyToPost(bskyToken, _bskyDid.value,
                    root?.uri ?: item.postUri, root?.cid ?: item.postCid, parentUri, parentCid, text)
                    .onSuccess { loadComments(force = true) }
                    .onFailure { _errorMessage.value = it.message }
            } else {
                e621Repo.createComment(e621Username, e621ApiKey, item.e621PostId ?: return@launch, text)
                    .onSuccess { loadComments(force = true) }
                    .onFailure { _errorMessage.value = it.message }
            }
        }
    }

    fun likeComment(comment: CommentItem) {
        if (_appMode.value != AppMode.BLUESKY) return
        val newLiked = !comment.isLiked
        updateComment(comment.id) { it.copy(isLiked = newLiked, likeCount = if (newLiked) it.likeCount + 1 else (it.likeCount - 1).coerceAtLeast(0)) }
        viewModelScope.launch(Dispatchers.IO) {
            if (comment.isLiked) {
                bskyRepo.unlikeComment(bskyToken, _bskyDid.value, comment.likeUri ?: return@launch)
                    .onFailure { updateComment(comment.id) { it.copy(isLiked = comment.isLiked, likeCount = comment.likeCount) } }
            } else {
                bskyRepo.likeComment(bskyToken, _bskyDid.value, comment.uri, comment.cid)
                    .onSuccess { uri -> updateComment(comment.id) { it.copy(likeUri = uri) } }
                    .onFailure { updateComment(comment.id) { it.copy(isLiked = comment.isLiked, likeCount = comment.likeCount) } }
            }
        }
    }

    fun voteComment(comment: CommentItem, vote: Int) {
        if (_appMode.value != AppMode.E621) return
        val newVote = if (comment.e621UserVote == vote) 0 else vote
        updateComment(comment.id) { it.copy(e621UserVote = newVote) }
        viewModelScope.launch(Dispatchers.IO) {
            val id = comment.id.toIntOrNull() ?: return@launch
            e621Repo.voteComment(e621Username, e621ApiKey, id, if (newVote == 0) vote * -1 else newVote)
                .onFailure { updateComment(comment.id) { it.copy(e621UserVote = comment.e621UserVote) } }
        }
    }

    // ── Downloads ─────────────────────────────────────────────────────────────

    fun setDownloadOnLike(enabled: Boolean) {
        viewModelScope.launch { prefs.setDownloadOnLike(enabled) }
    }

    fun setReducedAnimations(enabled: Boolean) {
        _reducedAnimations.value = enabled
        viewModelScope.launch { prefs.setReducedAnimations(enabled) }
    }

    fun setCombineListsAndPacks(enabled: Boolean) {
        _combineListsAndPacks.value = enabled
        viewModelScope.launch { prefs.setCombineListsAndPacks(enabled) }
    }

    fun downloadAllLiked() {
        if (_downloadProgress.value?.isRunning == true) return
        cancelDownloadFlag = false
        if (_appMode.value == AppMode.BLUESKY) downloadAllBskyLiked()
        else downloadAllE621Favorites()
    }

    // Settings' Data section has one Download button per service, so each
    // one has to download from ITS service regardless of which mode the feed
    // happens to be in (downloadAllLiked above picks by mode, which is only
    // right when there's a single button).
    private val _downloadIsE621 = MutableStateFlow(false)
    val downloadIsE621: StateFlow<Boolean> = _downloadIsE621

    fun downloadAllBskyLikedMedia() {
        if (_downloadProgress.value?.isRunning == true) return
        cancelDownloadFlag = false
        _downloadIsE621.value = false
        downloadAllBskyLiked()
    }

    fun downloadAllE621SavedMedia() {
        if (_downloadProgress.value?.isRunning == true) return
        cancelDownloadFlag = false
        _downloadIsE621.value = true
        downloadAllE621Favorites()
    }

    fun cancelDownloadAll() {
        cancelDownloadFlag = true
        _downloadProgress.value = _downloadProgress.value?.copy(isRunning = false)
    }

    private fun downloadAllBskyLiked() {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadProgress.value = DownloadProgress(0, true)
            var cursor: String? = null
            var total = 0
            do {
                if (cancelDownloadFlag) break
                bskyRepo.getActorLikes(bskyToken, _bskyDid.value, cursor)
                    .onSuccess { (items, nextCursor) ->
                        items.forEach { if (!cancelDownloadFlag) { enqueueDownload(it); total++ } }
                        _downloadProgress.value = DownloadProgress(total, !cancelDownloadFlag)
                        cursor = nextCursor
                    }
                    .onFailure { cursor = null }
            } while (cursor != null && !cancelDownloadFlag)
            _downloadProgress.value = DownloadProgress(total, false)
        }
    }

    private fun downloadAllE621Favorites() {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadProgress.value = DownloadProgress(0, true)
            var page  = 1
            var total = 0
            while (!cancelDownloadFlag) {
                val items = e621Repo.getFavorites(e621Username, e621ApiKey, page)
                    .getOrNull() ?: break
                if (items.isEmpty()) break
                items.forEach { if (!cancelDownloadFlag) { enqueueDownload(it); total++ } }
                _downloadProgress.value = DownloadProgress(total, !cancelDownloadFlag)
                page++
            }
            _downloadProgress.value = DownloadProgress(total, false)
        }
    }

    private fun enqueueDownload(url: String, uniqueId: String, isVideo: Boolean = false) {
        val (finalUrl, filename, mimeType) = urlToDownloadInfo(url, uniqueId, isVideo)
        platform.enqueueDownload(finalUrl, filename, mimeType, uniqueId)
    }

    // Bug fix (item 5): for Bluesky videos, item.mediaUrl only ever holds the
    // poster-frame thumbnail (see BlueskyRepository.parseFeedItem) — the actual
    // playable video lives at item.videoPlaylistUrl. Downloading mediaUrl
    // unconditionally meant "download video" silently saved a single still
    // frame instead of the video. Route video posts to the real source and
    // force a video/mp4 filename+mimetype regardless of the source URL's
    // extension (the playlist URL may not end in .mp4).
    private fun enqueueDownload(item: MediaItem) {
        if (item.isTextOnly) return
        if (item.mediaGroup.size > 1) {
            item.mediaGroup.forEachIndexed { i, img -> enqueueDownload(img.mediaUrl, "${item.id}_$i") }
        } else if (item.isVideo) {
            val did = item.author.did
            val cid = item.videoBlobCid
            if (did.isNotBlank() && !cid.isNullOrBlank()) {
                // Real fix: fetch the original video blob directly, instead of
                // saving the HLS playlist manifest as a fake .mp4.
                platform.enqueueVideoBlobDownload(did, cid, item.id)
            } else {
                // Fallback for sources that don't have a resolvable blob (e.g.
                // e621, whose "playlist" URL already points at a real mp4 file).
                val videoUrl = item.videoPlaylistUrl.takeUnless { it.isNullOrBlank() } ?: item.mediaUrl
                enqueueDownload(videoUrl, item.id, isVideo = true)
            }
        } else {
            enqueueDownload(item.mediaUrl, item.id)
        }
    }

    // ── AI Tagging (local, on-device) ────────────────────────────────────────
    // See com.mediaviewer.tagging.* for the actual model/DB/pipeline code.
    // This section just exposes state for the Search page's "Liked" tab, the
    // full-screen tagging overlay, and the Settings "AI Tagging" section, and
    // routes their button taps into TaggingRepository.

    data class TaggingUiState(
        val scanned: Int = 0,
        val tagged: Int = 0,
        val datasetBytes: Long = 0L,
        val isRunning: Boolean = false,
        val isComplete: Boolean = false,
        val modelState: TaggerState = TaggerState.NotDownloaded,
        val errorMessage: String? = null,
        // Tagging page redesign (item 3): the post currently being fetched/
        // tagged, straight from TaggingRepository.Progress — drives the
        // overlay's full-screen "now tagging" view.
        val currentItem: MediaItem? = null
    )

    private val _taggingOverlayOpen = MutableStateFlow(false)
    val taggingOverlayOpen: StateFlow<Boolean> = _taggingOverlayOpen

    private val _taggingUiState = MutableStateFlow(TaggingUiState())
    val taggingUiState: StateFlow<TaggingUiState> = _taggingUiState

    // True once at least one liked post has ever been scanned — this is what
    // gates the Search page's "Liked" tab between showing the "Start
    // Tagging" prompt vs. an actual search box, and it's derived straight
    // from the on-disk dataset (see TagDatabase) rather than a separate
    // "setup complete" flag, so it can never drift out of sync with it.
    private val _hasTaggedDataset = MutableStateFlow(false)
    val hasTaggedDataset: StateFlow<Boolean> = _hasTaggedDataset

    val tagPostWhenLiked: StateFlow<Boolean> =
        prefs.tagPostWhenLiked.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Item 2 (this session): the "posts tagged at once" slider was removed
    // from Settings — see TaggingRepository.tagAllLiked's own doc comment,
    // but in short it only ever controlled fetch/decode *prefetch* depth,
    // never simultaneous inference (that's always one at a time regardless).
    // 3 was already the slider's own default; hardcoding it here means the
    // prefetch pipeline keeps exactly the same shape/behavior it always had
    // at that setting, just without a control the person had to think about
    // for something that "really doesn't make much of a difference".
    private val taggingPrefetchDepth = 3

    // likedTagSearchQuery removed — the Liked tab now reads/writes
    // _searchState.query directly (see updateLikedQueryText/submitLikedSearch
    // below), the same field the other search tabs already use, so there's
    // one query string per tab instead of a second one that could drift out
    // of sync with what the text field actually shows.
    private val _likedTagSearchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val likedTagSearchResults: StateFlow<List<MediaItem>> = _likedTagSearchResults

    // Item 4: autocomplete/autocorrect suggestions for the word currently
    // being typed in the Liked tab's search bar.
    private val _tagSuggestions = MutableStateFlow<List<String>>(emptyList())
    val tagSuggestions: StateFlow<List<String>> = _tagSuggestions

    // Item 4 (Import/Export): every dataset imported on this device, for
    // Settings' list under the Import/Export buttons — refreshed after
    // every import/delete (see refreshImportedDatasets below).
    private val _importedDatasets = MutableStateFlow<List<TagDatasetInfo>>(emptyList())
    val importedDatasets: StateFlow<List<TagDatasetInfo>> = _importedDatasets

    // Item 4 (Import/Export): human-readable status for the last
    // export/import attempt — surfaced as a small toast-style message
    // rather than a full error dialog, since a failure here (bad file, I/O
    // error) isn't as disruptive as e.g. a login failure. Reuses
    // _errorMessage's existing display path (see MainActivity's error
    // banner) rather than a brand new one.
    private fun reportDatasetError(message: String) { _errorMessage.value = message }

    // Whether the on-device tagging model is on disk — drives Settings'
    // "Download On-Device Tagging Model" button ("Download" vs a grayed-out
    // "Downloaded") and whether the tagging options beneath it appear.
    private val _taggerModelReady = MutableStateFlow(taggingRepo.isModelReady())
    val taggerModelReady: StateFlow<Boolean> = _taggerModelReady

    private val _taggerModelDownloading = MutableStateFlow(false)
    val taggerModelDownloading: StateFlow<Boolean> = _taggerModelDownloading

    private var modelDownloadJob: Job? = null

    init {
        refreshTaggingCounts()
        refreshImportedDatasets()
    }

    /** Settings' "Download On-Device Tagging Model" button. Opens the same
     *  progress page the model download has always used (the tagging
     *  overlay's "Downloading Tagging Model" card), and closes it by itself
     *  the moment the download finishes. Closing the page early cancels the
     *  download. A failure leaves the page open showing the error. */
    fun downloadTaggerModel() {
        if (_taggerModelDownloading.value || _taggerModelReady.value) return
        _taggerModelDownloading.value = true
        _taggingOverlayOpen.value = true
        _taggingUiState.value = TaggingUiState(
            scanned = _taggingUiState.value.scanned, tagged = _taggingUiState.value.tagged,
            datasetBytes = _taggingUiState.value.datasetBytes,
            isRunning = true, modelState = TaggerState.Downloading(0, 0)
        )
        modelDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                taggingRepo.downloadModel { state ->
                    _taggingUiState.value = _taggingUiState.value.copy(
                        modelState = state,
                        isRunning = state is TaggerState.Downloading,
                        errorMessage = (state as? TaggerState.Failed)?.message
                    )
                    if (state is TaggerState.Ready) {
                        _taggerModelReady.value = true
                        _taggingOverlayOpen.value = false
                        _taggingUiState.value = _taggingUiState.value.copy(
                            isRunning = false, modelState = TaggerState.Ready, errorMessage = null
                        )
                    }
                }
            } finally {
                _taggerModelDownloading.value = false
            }
        }
    }

    private fun refreshTaggingCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            val (scanned, tagged) = taggingRepo.currentCounts()
            // The search page's "Tagged" tab only exists once there's at
            // least one post that actually has tags to search — a dataset
            // made only of posts that came back with no tags (text-only
            // posts, say) would just be an empty tab.
            _hasTaggedDataset.value = tagged > 0
            _taggingUiState.value = _taggingUiState.value.copy(scanned = scanned, tagged = tagged, datasetBytes = taggingRepo.datasetSizeBytes())
        }
    }

    private fun refreshImportedDatasets() {
        viewModelScope.launch(Dispatchers.IO) {
            _importedDatasets.value = taggingRepo.listImportedDatasets()
        }
    }

    fun setTagPostWhenLiked(enabled: Boolean) {
        viewModelScope.launch { prefs.setTagPostWhenLiked(enabled) }
    }

    // ── Tag-on-like queue ────────────────────────────────────────────────
    // Liking several posts quickly used to launch one tagging job per like,
    // all at once: several model loads/inferences in parallel, which is
    // what made the app lag or crash. Likes now go into a queue that ONE
    // worker drains, one post at a time, in order. Its state also feeds the
    // debug overlay's "Activating tagger… / Tagging N posts" readout.
    enum class LikeTagPhase { IDLE, ACTIVATING, TAGGING }
    private val _likeTagPhase = MutableStateFlow(LikeTagPhase.IDLE)
    val likeTagPhase: StateFlow<LikeTagPhase> = _likeTagPhase
    private val _likeTagPending = MutableStateFlow(0)
    val likeTagPending: StateFlow<Int> = _likeTagPending
    private val likeTagQueue = kotlinx.coroutines.channels.Channel<MediaItem>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val likeTagQueued = com.mediaviewer.platform.concurrentSetOf<String>()
    private val likeTagWorker = viewModelScope.launch(Dispatchers.IO) {
        for (item in likeTagQueue) {
            // Never overlap the full "Tag all liked posts" pass either.
            while (_taggingUiState.value.isRunning) delay(500)
            _likeTagPhase.value = if (taggingRepo.isTaggerLoaded()) LikeTagPhase.TAGGING else LikeTagPhase.ACTIVATING
            runCatching { taggingRepo.tagOnLike(item) }
                .onFailure { Log.e("MainViewModel", "Tag-on-like failed", it) }
            likeTagQueued.remove(item.postUri)
            _likeTagPending.value = (_likeTagPending.value - 1).coerceAtLeast(0)
            _likeTagPhase.value = if (_likeTagPending.value > 0) LikeTagPhase.TAGGING else LikeTagPhase.IDLE
            if (_likeTagPending.value == 0) refreshTaggingCounts()
        }
    }

    private fun maybeTagOnLike(item: MediaItem) {
        if (!tagPostWhenLiked.value) return
        if (item.postUri.isBlank() || !likeTagQueued.add(item.postUri)) return
        _likeTagPending.value = _likeTagPending.value + 1
        if (_likeTagPhase.value == LikeTagPhase.IDLE) {
            _likeTagPhase.value = if (taggingRepo.isTaggerLoaded()) LikeTagPhase.TAGGING else LikeTagPhase.ACTIVATING
        }
        likeTagQueue.trySend(item)
    }

    /** Opens the full-screen tagging overlay and kicks off (or resumes) a
     *  full backlog pass over every liked post. Used both by the Search
     *  page's "Start Tagging" button and Settings' "Locally Tag All Liked
     *  Posts" row — they're the same underlying action. */
    fun startTaggingAllLiked() {
        if (_taggingUiState.value.isRunning) return
        _taggingOverlayOpen.value = true
        _taggingUiState.value = _taggingUiState.value.copy(isRunning = true, isComplete = false, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            taggingRepo.tagAllLiked(
                isBlueskyMode = _appMode.value == AppMode.BLUESKY,
                bskyToken = bskyToken,
                bskyDid = _bskyDid.value,
                e621Username = e621Username,
                e621ApiKey = e621ApiKey,
                concurrency = taggingPrefetchDepth
            ) { progress ->
                _taggingUiState.value = TaggingUiState(
                    scanned = progress.scanned,
                    tagged = progress.tagged,
                    datasetBytes = progress.datasetBytes,
                    isRunning = progress.isRunning,
                    isComplete = progress.isComplete,
                    modelState = progress.modelState,
                    errorMessage = (progress.modelState as? TaggerState.Failed)?.message,
                    currentItem = progress.currentItem
                )
                if (progress.tagged > 0) _hasTaggedDataset.value = true
                if (progress.modelState is TaggerState.Ready) _taggerModelReady.value = true
            }
        }
    }

    fun cancelTagging() {
        taggingRepo.cancel()
    }

    /** Settings' "Delete Tagged Post Database" button (item 5). Stops any
     *  in-flight tagging pass first (so it can't keep writing rows back in
     *  while/after the wipe), clears the dataset, then resets every piece
     *  of UI state that was derived from it — otherwise the Search page's
     *  Liked tab would still show stale "already tagged" results, and the
     *  Settings row would still show the old scanned/tagged counts, until
     *  the next unrelated refresh happened to overwrite them. */
    fun deleteTaggedDatabase() {
        if (_taggingUiState.value.isRunning) taggingRepo.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            taggingRepo.deleteDatabase()
            _hasTaggedDataset.value = false
            _taggingUiState.value = TaggingUiState()
            _importedDatasets.value = emptyList() // clearAll() wipes the datasets table too
            performLikedTagSearch("")
        }
    }

    // ── Import/Export (item 4) ──────────────────────────────────────────

    /** Plain JSON shape of an exported dataset file — kept intentionally
     *  simple/flat (no versioned wrapper classes, no binary format) so a
     *  friend's export is just a small readable JSON file that's easy to
     *  back up, email, or inspect. [name] is what the person typed into the
     *  "Export" naming dialog; it's what shows up in the *other* person's
     *  imported-datasets list after they import it. */
    @kotlinx.serialization.Serializable
    private data class DatasetFile(
        val formatVersion: Int = 1,
        val name: String,
        val exportedAt: Long,
        val posts: List<DatasetFilePost>
    )
    @kotlinx.serialization.Serializable
    private data class DatasetFilePost(
        val postUri: String,
        val cid: String,
        val mediaUrl: String,
        val tags: List<DatasetFileTag>
    )
    @kotlinx.serialization.Serializable
    private data class DatasetFileTag(val name: String, val confidence: Float)


    /** Settings' "Export" button, once the person has named the dataset and
     *  picked a save location via the system file picker (ActivityResult
     *  CreateDocument — see SettingsSheet's exportLauncher). Bundles
     *  *everything* currently tagged on the device — local + every imported
     *  dataset together — into one file, per the request that Export is a
     *  full backup/share of "their dataset" as a whole rather than picking
     *  one dataset to export. */
    /** Settings → Media Tagging → Export Dataset: what the row shows while
     *  the file is being written, and once it's finished. */
    sealed class DatasetExportState {
        object Idle : DatasetExportState()
        data class Working(val stage: String, val postCount: Int = 0) : DatasetExportState()
        data class Done(val postCount: Int) : DatasetExportState()
        data class Failed(val message: String) : DatasetExportState()
    }
    private val _datasetExportState = MutableStateFlow<DatasetExportState>(DatasetExportState.Idle)
    val datasetExportState: StateFlow<DatasetExportState> = _datasetExportState
    private var datasetExportResetJob: Job? = null

    fun exportDataset(name: String, uri: com.mediaviewer.platform.PlatformUri) {
        if (_datasetExportState.value is DatasetExportState.Working) return
        datasetExportResetJob?.cancel()
        _datasetExportState.value = DatasetExportState.Working("Gathering tagged posts…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val posts = taggingRepo.exportAllPosts()
                _datasetExportState.value = DatasetExportState.Working("Writing ${posts.size} posts…", posts.size)
                val file = DatasetFile(
                    name = name.ifBlank { "Untitled Dataset" },
                    exportedAt = com.mediaviewer.platform.currentTimeMillis(),
                    posts = posts.map { p ->
                        DatasetFilePost(p.postUri, p.cid, p.mediaUrl, p.tags.map { (tag, conf) -> DatasetFileTag(tag, conf) })
                    }
                )
                val json = com.mediaviewer.json.StellarJson.default.encodeToString(DatasetFile.serializer(), file)
                platform.writeTextToUri(uri, json)
                _datasetExportState.value = DatasetExportState.Done(posts.size)
                showToast("Dataset exported (${posts.size} posts)")
            } catch (e: Exception) {
                _datasetExportState.value = DatasetExportState.Failed(e.message ?: "unknown error")
                reportDatasetError("Export failed: ${e.message ?: "unknown error"}")
            }
            // The finished/failed line stays up for a few seconds, then clears.
            datasetExportResetJob = viewModelScope.launch {
                delay(6000)
                if (_datasetExportState.value !is DatasetExportState.Working) _datasetExportState.value = DatasetExportState.Idle
            }
        }
    }

    /** Settings' "Import" button, once a file's been picked via the system
     *  file picker (ActivityResult OpenDocument — see SettingsSheet's
     *  importLauncher). Reads and parses the file, then hands the posts to
     *  TaggingRepository.importDataset, which is what actually keeps it
     *  separate from every other dataset already on the device (see that
     *  method's own doc comment). */
    fun importDatasetFromUri(uri: com.mediaviewer.platform.PlatformUri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = platform.readTextFromUri(uri)
                    ?: throw com.mediaviewer.platform.IOException("Couldn't open the chosen file for reading")
                val file = runCatching { com.mediaviewer.json.StellarJson.default.decodeFromString(DatasetFile.serializer(), json) }.getOrNull()
                    ?: throw com.mediaviewer.platform.IOException("That file isn't a dataset export")
                if (file.posts.isEmpty()) throw com.mediaviewer.platform.IOException("That dataset export is empty")
                taggingRepo.importDataset(
                    name = file.name.ifBlank { "Untitled Dataset" },
                    posts = file.posts.map { p ->
                        TagExportedPost(p.postUri, p.cid, p.mediaUrl, p.tags.map { it.name to it.confidence })
                    }
                )
                refreshImportedDatasets()
                refreshTaggingCounts()
                performLikedTagSearch("")
            } catch (e: Exception) {
                reportDatasetError("Import failed: ${e.message ?: "that file doesn't look like a dataset export"}")
            }
        }
    }

    /** Per-row "X" in Settings' imported-datasets list. Only ever removes
     *  the one dataset picked — see TagDatabase.deleteDataset's own doc
     *  comment for why every other dataset (including the local one) is
     *  untouched. */
    fun deleteImportedDataset(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            taggingRepo.deleteDataset(id)
            refreshImportedDatasets()
            refreshTaggingCounts()
            performLikedTagSearch("")
        }
    }

    /** Closes the overlay after a completed (or cancelled) run — separate
     *  from cancelTagging() since the person can dismiss a *finished* run's
     *  "Tagging Complete" card without that meaning "stop", and dismissing
     *  mid-run should stop the in-flight pass. */
    fun dismissTaggingOverlay() {
        if (_taggerModelDownloading.value) {
            modelDownloadJob?.cancel()
            _taggingUiState.value = _taggingUiState.value.copy(isRunning = false, modelState = TaggerState.NotDownloaded, errorMessage = null)
        }
        if (_taggingUiState.value.isRunning) taggingRepo.cancel()
        _taggingOverlayOpen.value = false
        // Item 2: land back on the Liked tab's default "everything, most
        // recent first" browse rather than whatever stale search results
        // (or lack thereof) were showing before tagging started.
        viewModelScope.launch(Dispatchers.IO) { performLikedTagSearch("") }
    }

    /** Item 2: the query text field's live value updates on every
     *  keystroke (so the field visibly shows what's being typed and
     *  suggestions can react), but — unlike the other search tabs — does
     *  NOT re-run the actual dataset lookup. That only happens on
     *  [submitLikedSearch] (Enter/search-key) or [setSearchFilter] (tab
     *  switch), matching the request that results shouldn't change while
     *  typing. Also drives the item-4 autocomplete off the in-progress
     *  last word. */
    fun updateLikedQueryText(text: String) {
        _searchState.value = _searchState.value.copy(query = text)
        val lastWord = text.substringAfterLast(' ')
        if (lastWord.isBlank()) {
            // Item 4: "tapping space should close this menu" — an empty
            // in-progress word (just typed a space, or field is empty)
            // means there's nothing to suggest completions for.
            _tagSuggestions.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val vocabulary = taggingRepo.tagVocabulary()
            _tagSuggestions.value = TagSuggestionProvider.suggest(lastWord, vocabulary)
        }
    }

    /** Item 4: tapping a suggestion replaces the in-progress last word with
     *  it (keeping any earlier words untouched) and adds a trailing space,
     *  same as e621's own autocomplete, then continues as if the person had
     *  typed it — suggestions clear immediately since the new last word is
     *  now empty. */
    fun applyTagSuggestion(suggestion: String) {
        val current = _searchState.value.query
        val lastSpace = current.lastIndexOf(' ')
        val newQuery = (if (lastSpace >= 0) current.substring(0, lastSpace + 1) else "") + suggestion + " "
        _searchState.value = _searchState.value.copy(query = newQuery)
        _tagSuggestions.value = emptyList()
    }

    fun submitLikedSearch() {
        _tagSuggestions.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { performLikedTagSearch(_searchState.value.query) }
    }

    /** Item 14: the search page's "e621" filter option (only shown once
     *  logged into e621 — see SearchOverlay's own gating) reuses the Tagged
     *  tab's text field/autocomplete verbatim (updateLikedQueryText above
     *  drives both), but submitting doesn't render inline results here at
     *  all — it closes Search and jumps straight to the e621 feed with
     *  those tags, the same self-contained pattern the Hub's own e621 Hot/
     *  Favorites/Following buttons use (see AtProtocolPageContent's
     *  onOpenE621* handlers). */
    fun submitE621SearchFromOverlay() {
        _tagSuggestions.value = emptyList()
        val tags = _searchState.value.query
        closeSearch()
        setE621SearchTags(tags)
        setMode(AppMode.E621)
        searchE621()
        setScreen(ScreenState.FEED)
    }

    /** Item 2: blank query browses everything tagged so far, most recent
     *  first, instead of an empty "type to search" state — a search query
     *  narrows that same list by tag. */
    private val likedSearchGeneration = com.mediaviewer.platform.AtomicInteger(0)

    private suspend fun performLikedTagSearch(query: String) {
        // Only the most recently started search may publish results — an
        // older, slower one (a tab-switch refresh, a background refresh
        // after tagging) finishing later used to replace the results of
        // the search the person actually just ran.
        val generation = likedSearchGeneration.incrementAndGet()
        _searchState.value = _searchState.value.copy(loading = true)
        val uris = if (query.isBlank()) taggingRepo.browseAllTagged() else taggingRepo.search(query)
        val hydrated = hydrateLikedUris(uris)
        if (generation != likedSearchGeneration.get()) return
        _likedTagSearchResults.value = hydrated
        _searchState.value = _searchState.value.copy(loading = false, hasSearched = true)
    }

    private suspend fun hydrateLikedUris(uris: List<String>): List<MediaItem> {
        if (uris.isEmpty()) return emptyList()
        // TaggingRepository's dataset only stores URIs/tags, never full
        // post content (see its storage note), so a hit's URI has to be
        // hydrated back into a real MediaItem before it can be rendered.
        val hydrated = if (_appMode.value == AppMode.BLUESKY) {
            bskyRepo.getPostsByUris(bskyToken, uris).getOrNull() ?: emptyList()
        } else {
            e621Repo.getPostsByUris(e621Username, e621ApiKey, uris).getOrNull() ?: emptyList()
        }
        val order = uris.withIndex().associate { (i, uri) -> uri to i }
        return hydrated.sortedBy { order[it.postUri] ?: Int.MAX_VALUE }
    }

    /** Item 3 bug fix: posts opened from the Liked tab weren't clickable at
     *  all — openPostFromSearch only ever looked at _searchState.value.posts
     *  (the POSTS tab's own result list), which is always empty for the
     *  Liked tab (its results live in _likedTagSearchResults, a separate
     *  pipeline — see performLikedTagSearch above), so the index bounds
     *  check silently failed and the tap did nothing. This is the Liked
     *  tab's own equivalent of openPostFromSearch, and also attaches each
     *  opened post's full AI tag list (item 3: "Tags mode needs to display
     *  ALL the tags on the post") via CommentsSheet's existing `tags` field
     *  — but only when the post doesn't already carry real tags of its own
     *  (e621-mode posts already have genuine e621 tags from the API; only
     *  Bluesky posts, which have no tags concept at all, need the AI ones
     *  substituted in). */
    /** Item 4: same hide-not-close treatment as openPostFromSearch — see its
     *  doc comment. The Liked tab's own results/query/tag-suggestion state
     *  all live outside _searchState (in _likedTagSearchResults etc.), so
     *  leaving _searchState/that state alone and just hiding the overlay is
     *  enough to bring the whole Liked tab view back intact on pinch-in. */
    fun openLikedPostFromSearch(index: Int) {
        val results = _likedTagSearchResults.value
        if (index !in results.indices) return
        viewModelScope.launch(Dispatchers.IO) {
            val withTags = results.map { item ->
                if (item.tags.isNotBlank()) item
                else {
                    val aiTags = taggingRepo.tagsForPost(item.postUri)
                    if (aiTags.isEmpty()) item else item.copy(tags = aiTags.joinToString(" "))
                }
            }
            withContext(Dispatchers.Main) {
                _mediaItems.value = withTags
                _currentIndex.value = index
                _navDirection.value = 0
                _authorFeedState.value = null
                activeFeedMode = ActiveFeedMode.NORMAL
                activeFeedActorDid = null
                _selectedFeedUri.value = null
                _searchOpen.value = false
                _searchHiddenBehindPost.value = true
                _screenState.value = ScreenState.FEED
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateCurrentItem(transform: (MediaItem) -> MediaItem) {
        val idx  = _currentIndex.value
        val list = _mediaItems.value.toMutableList()
        val item = list.getOrNull(idx) ?: return
        list[idx] = transform(item)
        _mediaItems.value = list
    }

    private fun updateCurrentItemAuthor(transform: (AuthorInfo) -> AuthorInfo) {
        updateCurrentItem { it.copy(author = transform(it.author)) }
    }

    private fun updateComment(commentId: String, transform: (CommentItem) -> CommentItem) {
        _comments.value = _comments.value.map { if (it.id == commentId) transform(it) else it }
        commentsShownFor?.let { key -> if (cachedComments(key) != null) putCachedComments(key, _comments.value) }
    }

    // ── Live Link widget feature ────────────────────────────────────────
    // Single bundled StateFlow (see LiveLinkState's doc comment in
    // Models.kt for why it's one flow, not three) — the Settings section,
    // the Hub's bottom row, and (indirectly, via the same
    // PreferencesManager the widget/worker also read) the widget itself all
    // observe this same underlying prefs data, so a toggle from any one of
    // those three surfaces is instantly reflected in the other two without
    // this ViewModel needing to manually push updates to each.
    val liveLinkState: StateFlow<com.mediaviewer.model.LiveLinkState> =
        prefs.liveLinkState.stateIn(viewModelScope, SharingStarted.Eagerly, com.mediaviewer.model.LiveLinkState())

    fun saveLiveTwitchUrl(url: String) = viewModelScope.launch { prefs.setLiveTwitchUrl(url) }
    fun saveLiveYoutubeUrl(url: String) = viewModelScope.launch { prefs.setLiveYoutubeUrl(url) }

    /** Turns a Live Link ON — used by both the Hub row and (for symmetry,
     *  though the widget itself calls LiveLinkManager directly since it has
     *  no ViewModel of its own) anything else in the UI layer that might
     *  want to. All the actual work (Bluesky status write, prefs, worker
     *  scheduling, widget refresh) lives in LiveLinkManager — see its own
     *  doc comment for why this is a shared standalone object rather than
     *  logic duplicated here. */
    fun toggleLiveLink(platform: com.mediaviewer.model.LiveNowPlatform) {
        viewModelScope.launch {
            val state = liveLinkState.value
            val url = if (platform == com.mediaviewer.model.LiveNowPlatform.TWITCH) state.twitchUrl else state.youtubeUrl
            if (url.isNullOrBlank()) return@launch
            this@MainViewModel.platform.goLive(platform, url)
                .onFailure { showToast("Couldn't start Live Link: ${it.message}") }
        }
    }

    fun endLiveLink() {
        viewModelScope.launch {
            platform.endLive()
                .onFailure { showToast("Couldn't end Live Link: ${it.message}") }
        }
    }

    /** Settings' "Create Widget" button — requests the launcher pin the
     *  Live Link widget directly, per the feature request ("should only be
     *  creatable from the app via a button next to the links in settings").
     *  Silently no-ops if the launcher doesn't support this (very old/
     *  unusual launchers) rather than crashing; there's no in-app fallback
     *  UI for "drag it from the widget picker yourself" since that picker
     *  entry point is intentionally what this feature avoids relying on. */
    fun createLiveLinkWidget() {
        if (!platform.requestPinLiveLinkWidget()) {
            showToast("Your launcher doesn't support pinning widgets from apps")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Lists as feeds · profile Lists/Feeds tab · Hub profile rows ·
    // Stellar Supporters · welcome / tutorial popups
    // ═════════════════════════════════════════════════════════════════════

    // ── Feeds list: add a feed / pin a list ──────────────────────────────

    /** Profile → Lists/Feeds → a feed's "Add": saves it to your feeds. */
    fun addFeedFromProfile(entry: ProfileListEntry) {
        if (!_bskyLoggedIn.value || _availableFeeds.value.any { it.uri == entry.uri }) return
        tapHaptic()
        // Shown in the Hub's Feeds row straight away; written in the background.
        _availableFeeds.value = _availableFeeds.value + BskyFeedInfo(entry.uri, entry.name, entry.avatarUrl)
        viewModelScope.launch(Dispatchers.IO) {
            feedPrefsMutex.withLock {
                var result = bskyRepo.addSavedFeed(bskyToken, entry.uri)
                if (result.isFailure && isAuthError(result.exceptionOrNull()?.message) && refreshBskyTokenIfPossible()) {
                    result = bskyRepo.addSavedFeed(bskyToken, entry.uri)
                }
                result.onSuccess { showToast("Added \"${entry.name}\" to your feeds") }
                    .onFailure { _errorMessage.value = "Couldn't add that feed: ${it.message}" }
            }
            loadAvailableFeeds()
        }
    }

    /** Profile → Lists/Feeds → a list's "Pin to Feeds": the list becomes a
     *  feed in your feeds list (its cover beside its name), showing only
     *  its members' own posts — no reposts, no replies — newest first. It's
     *  saved the way Bluesky's own app pins a list, so it shows there too. */
    fun pinListAsFeed(listUri: String, name: String, avatarUrl: String?) {
        if (!_bskyLoggedIn.value || listUri.isBlank() || _availableFeeds.value.any { it.uri == listUri }) return
        tapHaptic()
        _availableFeeds.value = _availableFeeds.value + BskyFeedInfo(listUri, name.ifBlank { "List" }, avatarUrl)
        viewModelScope.launch(Dispatchers.IO) {
            feedPrefsMutex.withLock {
                var result = bskyRepo.addSavedFeed(bskyToken, listUri, type = "list", pinned = true)
                if (result.isFailure && isAuthError(result.exceptionOrNull()?.message) && refreshBskyTokenIfPossible()) {
                    result = bskyRepo.addSavedFeed(bskyToken, listUri, type = "list", pinned = true)
                }
                result.onSuccess { showToast("Pinned \"${name.ifBlank { "List" }}\" to your feeds") }
                    .onFailure { _errorMessage.value = "Couldn't pin that list: ${it.message}" }
            }
            loadAvailableFeeds()
        }
    }

    // ── Profile → Lists/Feeds tab ────────────────────────────────────────

    private fun loadProfileLists(force: Boolean) {
        val cur = _profileOverlay.value ?: return
        val did = cur.author.did
        if (cur.lists.loading || (cur.lists.loaded && !force)) return
        _profileOverlay.value = cur.copy(lists = cur.lists.copy(loading = true, failed = false))
        viewModelScope.launch(Dispatchers.IO) {
            var result = bskyRepo.getProfileLists(bskyToken, did)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message) && refreshBskyTokenIfPossible()) {
                result = bskyRepo.getProfileLists(bskyToken, did)
            }
            val now = _profileOverlay.value?.takeIf { it.author.did == did } ?: return@launch
            _profileOverlay.value = result.fold(
                onSuccess = { entries -> now.copy(lists = ProfileListsState(loaded = true, entries = entries)) },
                onFailure = { now.copy(lists = now.lists.copy(loading = false, loaded = true, failed = true)) }
            )
        }
    }

    private fun updateProfileListEntry(uri: String, transform: (ProfileListEntry) -> ProfileListEntry) {
        val cur = _profileOverlay.value ?: return
        _profileOverlay.value = cur.copy(lists = cur.lists.copy(entries = cur.lists.entries.map { if (it.uri == uri) transform(it) else it }))
    }

    /** What a Lists/Feeds row's button is busy with / has finished, by the
     *  entry's URI: "3/20" while following, "Followed", "Blocking…" … */
    private val _listActions = MutableStateFlow<Map<String, String>>(emptyMap())
    val listActions: StateFlow<Map<String, String>> = _listActions
    private fun setListAction(uri: String, label: String?) {
        _listActions.value = if (label == null) _listActions.value - uri else _listActions.value + (uri to label)
    }

    /** A starter pack's "Follow All": follows everyone in it you don't
     *  follow yet (one after another, so Bluesky's rate limits are safe). */
    fun followAllInList(entry: ProfileListEntry) {
        val listUri = entry.listUri ?: return
        if (!_bskyLoggedIn.value || _listActions.value.containsKey(entry.uri)) return
        tapHaptic()
        val me = _bskyDid.value
        setListAction(entry.uri, "Following…")
        viewModelScope.launch(Dispatchers.IO) {
            var membersResult = bskyRepo.getListMembers(bskyToken, me, listUri)
            if (membersResult.isFailure && isAuthError(membersResult.exceptionOrNull()?.message) && refreshBskyTokenIfPossible()) {
                membersResult = bskyRepo.getListMembers(bskyToken, me, listUri)
            }
            val members = membersResult.getOrNull()
            if (members == null) {
                setListAction(entry.uri, null)
                _errorMessage.value = "Couldn't read that starter pack: ${membersResult.exceptionOrNull()?.message}"
                return@launch
            }
            val todo = members.map { it.author }.filter {
                !it.isFollowing && it.did != me && !com.mediaviewer.util.BlockedAccounts.isHidden(it.did)
            }
            var done = 0
            var failed = 0
            for (a in todo) {
                setListAction(entry.uri, "${done + failed + 1}/${todo.size}")
                bskyRepo.followUser(bskyToken, me, a.did).onSuccess { done++ }.onFailure { failed++ }
                delay(140)
            }
            setListAction(entry.uri, "Followed")
            showToast(
                when {
                    todo.isEmpty() -> "You already follow everyone in \"${entry.name}\""
                    failed > 0 -> "Followed $done of ${todo.size} accounts"
                    else -> "Followed $done account${if (done == 1) "" else "s"}"
                }
            )
            // The open members popup (if it's this list) shows them as followed.
            _listMembers.value?.takeIf { it.entry.uri == entry.uri }?.let { st ->
                _listMembers.value = st.copy(members = st.members.map { m -> if (m.author.did != me) m.copy(author = m.author.copy(isFollowing = true)) else m })
            }
        }
    }

    /** Accounts hidden in-app because of a list block made here (list URI →
     *  their DIDs), so "Unblock All" can show them again straight away. */
    private val listBlockedDids = HashMap<String, Set<String>>()

    /** A moderation list's "Block All" / "Unblock All": Bluesky's list
     *  block, so everyone on the list is blocked (and people added to it
     *  later are too) until it's undone here. */
    fun toggleBlockList(entry: ProfileListEntry) {
        val listUri = entry.listUri ?: entry.uri
        if (!_bskyLoggedIn.value || _listActions.value.containsKey(entry.uri)) return
        tapHaptic()
        val me = _bskyDid.value
        val existing = entry.blockUri
        setListAction(entry.uri, if (existing != null) "Unblocking…" else "Blocking…")
        viewModelScope.launch(Dispatchers.IO) {
            if (existing != null) {
                bskyRepo.unblockList(bskyToken, me, existing)
                    .onSuccess {
                        updateProfileListEntry(entry.uri) { it.copy(blockUri = null) }
                        // Only the accounts hidden by this list block — an
                        // account you also blocked directly stays blocked.
                        listBlockedDids.remove(listUri)?.forEach { did ->
                            if (com.mediaviewer.util.BlockedAccounts.blockUriFor(did) == null) com.mediaviewer.util.BlockedAccounts.removeBlocking(did)
                        }
                        showToast("Unblocked \"${entry.name}\"")
                    }
                    .onFailure { _errorMessage.value = "Couldn't unblock that list: ${it.message}" }
            } else {
                bskyRepo.blockList(bskyToken, me, listUri)
                    .onSuccess { blockUri ->
                        updateProfileListEntry(entry.uri) { it.copy(blockUri = blockUri) }
                        // Hide them everywhere in the app right away.
                        val members = bskyRepo.getListMembers(bskyToken, me, listUri).getOrNull().orEmpty()
                        val newlyHidden = members.map { it.author.did }
                            .filter { it != me && !com.mediaviewer.util.BlockedAccounts.isBlocking(it) }.toSet()
                        newlyHidden.forEach { com.mediaviewer.util.BlockedAccounts.addBlocking(it, null) }
                        listBlockedDids[listUri] = newlyHidden
                        showToast("Blocked everyone on \"${entry.name}\"")
                    }
                    .onFailure { _errorMessage.value = "Couldn't block that list: ${it.message}" }
            }
            setListAction(entry.uri, null)
        }
    }

    // ── The members popup (tap a list / starter pack / moderation list) ──

    data class ListMembersState(
        val entry: ProfileListEntry,
        /** It's one of your own lists: each account gets an X to remove it. */
        val isOwn: Boolean,
        val loading: Boolean = true,
        val failed: Boolean = false,
        val members: List<ListMember> = emptyList(),
        /** DIDs with a removal in flight. */
        val removing: Set<String> = emptySet()
    )
    private val _listMembers = MutableStateFlow<ListMembersState?>(null)
    val listMembers: StateFlow<ListMembersState?> = _listMembers

    fun openListMembers(entry: ProfileListEntry) {
        val listUri = entry.listUri ?: return
        if (!_bskyLoggedIn.value) return
        tapHaptic()
        val ownerDid = entry.uri.removePrefix("at://").substringBefore('/')
        _listMembers.value = ListMembersState(entry, isOwn = ownerDid == _bskyDid.value)
        viewModelScope.launch(Dispatchers.IO) {
            var result = bskyRepo.getListMembers(bskyToken, _bskyDid.value, listUri)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message) && refreshBskyTokenIfPossible()) {
                result = bskyRepo.getListMembers(bskyToken, _bskyDid.value, listUri)
            }
            val cur = _listMembers.value?.takeIf { it.entry.uri == entry.uri } ?: return@launch
            _listMembers.value = result.fold(
                onSuccess = { cur.copy(loading = false, members = it) },
                onFailure = { cur.copy(loading = false, failed = true) }
            )
        }
    }

    fun closeListMembers() { _listMembers.value = null }

    /** The members popup's delete button (your own lists, starter packs
     *  and moderation lists): deletes the one that's open. */
    fun deleteOpenList(onDone: (String?) -> Unit) {
        val cur = _listMembers.value
        if (cur == null || !cur.isOwn) { onDone(null); return }
        val entry = cur.entry
        deletePickerEntry(listOfNotNull(entry.uri, entry.listUri)) { error ->
            if (error == null) {
                if (_listMembers.value?.entry?.uri == entry.uri) _listMembers.value = null
                _profileOverlay.value?.let { o ->
                    _profileOverlay.value = o.copy(lists = o.lists.copy(entries = o.lists.entries.filterNot { it.uri == entry.uri }))
                }
            }
            onDone(error)
        }
    }

    /** The X beside an account in one of your own lists: takes them off it. */
    fun removeListMember(member: ListMember) {
        val cur = _listMembers.value ?: return
        if (!cur.isOwn || member.itemUri.isBlank() || member.author.did in cur.removing) return
        tapHaptic()
        _listMembers.value = cur.copy(removing = cur.removing + member.author.did)
        viewModelScope.launch(Dispatchers.IO) {
            val result = bskyRepo.removeFromList(bskyToken, _bskyDid.value, member.itemUri)
            val now = _listMembers.value?.takeIf { it.entry.uri == cur.entry.uri } ?: return@launch
            result.onSuccess {
                _listMembers.value = now.copy(
                    members = now.members.filterNot { it.author.did == member.author.did },
                    removing = now.removing - member.author.did
                )
                updateProfileListEntry(cur.entry.uri) { e -> e.copy(itemCount = e.itemCount?.let { (it - 1).coerceAtLeast(0) }) }
                // Add To's + / − for that account is out of date now.
                listMembershipCache.remove(member.author.did)
                // A Hub row showing this list reloads.
                cur.entry.listUri?.let { uri ->
                    if (com.mediaviewer.util.HubLayout.rows.any { it.listUri == uri }) loadHubListIfNeeded(uri, force = true)
                }
            }.onFailure {
                _listMembers.value = now.copy(removing = now.removing - member.author.did)
                _errorMessage.value = "Couldn't remove them: ${it.message}"
            }
        }
    }

    /** Tapping an account in the members popup: closes it, opens them. */
    fun openProfileFromListMembers(author: AuthorInfo) {
        _listMembers.value = null
        openProfile(author)
    }

    // ── A feed opened from a profile's Lists/Feeds tab ───────────────────
    // It opens in Explore mode, titled with the feed's name where the Feeds
    // row normally is (like From Friends / Saved Posts). The profile stays
    // alive, hidden behind it; leaving the feed (swipe down, pinch in on the
    // grid, Back) puts everything back exactly as it was and shows the
    // profile again — never the Hub.

    private class ProfileFeedReturn(
        val authorFeedState: AuthorFeedSavedState?,
        val items: List<MediaItem>, val index: Int, val cursor: String?,
        val mode: ActiveFeedMode, val actorDid: String?,
        val feedUri: String?, val screen: ScreenState
    )
    private var profileFeedReturn: ProfileFeedReturn? = null
    /** The feed (or list) [ActiveFeedMode.EXTERNAL] is showing. */
    private var externalFeedUri: String? = null

    private fun isProfileFeedActive(): Boolean =
        profileFeedReturn != null && activeFeedMode == ActiveFeedMode.EXTERNAL &&
            _authorFeedState.value?.author?.isProfileFeed() == true

    fun openProfileFeed(entry: ProfileListEntry) {
        val overlay = _profileOverlay.value ?: return
        if (!_bskyLoggedIn.value || entry.uri.isBlank()) return
        tapHaptic()
        if (profileFeedReturn == null || !isProfileFeedActive()) {
            profileFeedReturn = ProfileFeedReturn(
                _authorFeedState.value, _mediaItems.value, _currentIndex.value, feedCursor,
                activeFeedMode, activeFeedActorDid, _selectedFeedUri.value, _screenState.value
            )
        }
        val pseudo = AuthorInfo(_bskyDid.value, PROFILE_FEED_HANDLE_PREFIX + entry.uri, entry.name.ifBlank { "Feed" }, null)
        val cur = _authorFeedState.value
        _authorFeedState.value = cur?.copy(author = pseudo) ?: AuthorFeedSavedState(
            author = pseudo, items = _mediaItems.value, currentIndex = _currentIndex.value,
            cursor = feedCursor, feedUri = _selectedFeedUri.value
        )
        feedLoadGeneration++
        feedCursor = null
        activeFeedMode = ActiveFeedMode.EXTERNAL
        activeFeedActorDid = null
        externalFeedUri = entry.uri
        _mediaItems.value = emptyList()
        _currentIndex.value = 0
        _navDirection.value = 0
        _isLoading.value = true
        _profileOverlay.value = overlay.copy(hidden = true)
        _screenState.value = ScreenState.GRID
        loadExternalFeed(reset = true)
    }

    private fun loadExternalFeed(reset: Boolean) {
        val uri = externalFeedUri ?: return
        if (reset) { feedLoadGeneration++; _isLoading.value = true; feedCursor = null }
        else if (isLoadingMore) return
        val generation = feedLoadGeneration
        if (!reset) isLoadingMore = true
        viewModelScope.launch(Dispatchers.IO) {
            suspend fun attempt(): Result<Pair<List<MediaItem>, String?>> =
                if (com.mediaviewer.util.StellarOfficial.isListUri(uri)) bskyRepo.getListFeedOriginals(bskyToken, _bskyDid.value, uri, feedCursor)
                else bskyRepo.getFeed(bskyToken, uri, feedCursor, if (reset) 30 else 50)
            var result = attempt()
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message) && refreshBskyTokenIfPossible()) result = attempt()
            // They've left this feed (or moved to another) meanwhile.
            if (generation != feedLoadGeneration || activeFeedMode != ActiveFeedMode.EXTERNAL || externalFeedUri != uri) {
                if (!reset) isLoadingMore = false
                return@launch
            }
            result.onSuccess { (items, cursor) ->
                feedCursor = cursor
                val fresh = filterHidden(items)
                _mediaItems.value = if (reset) fresh else {
                    val known = _mediaItems.value.mapTo(HashSet()) { it.id }
                    _mediaItems.value + fresh.filter { it.id !in known }
                }
            }.onFailure { _errorMessage.value = it.message }
            _isLoading.value = false
            isLoadingMore = false
        }
    }

    /** Leaves a feed opened from a profile: the feed underneath is put back
     *  exactly as it was and the profile is shown again. */
    fun closeProfileFeed() {
        val back = profileFeedReturn ?: return
        profileFeedReturn = null
        externalFeedUri = null
        feedLoadGeneration++
        isLoadingMore = false
        _isLoading.value = false
        _authorFeedState.value = back.authorFeedState
        activeFeedMode = back.mode
        activeFeedActorDid = back.actorDid
        _mediaItems.value = back.items
        _currentIndex.value = back.index
        feedCursor = back.cursor
        _selectedFeedUri.value = back.feedUri
        _navDirection.value = 0
        _profileOverlay.value?.let { if (it.hidden) _profileOverlay.value = it.copy(hidden = false) }
        _screenState.value = back.screen
        tapHaptic()
    }

    /** A profile's "Supporter" label: closes every open profile and shows
     *  the Hub (which then turns to Settings → Support Stellar). */
    fun closeAllProfilesForSettings() {
        var guard = 0
        while (_profileOverlay.value != null && guard++ < 16) closeProfile()
        _profileOverlay.value = null
        setScreen(ScreenState.SETTINGS)
    }

    /** Explore mode's pinch-in: only does something on a feed opened from
     *  a profile (back to that profile). */
    fun pinchInFromGrid() { if (isProfileFeedActive()) closeProfileFeed() }

    // ── Add To: delete a list / change its cover ─────────────────────────

    /** Add To → press and hold an entry → Delete: removes those records
     *  (a list; a starter pack and the list behind it; or all of a "Both"
     *  pair). [onDone] gets null on success or an error message. */
    fun deletePickerEntry(recordUris: List<String>, onDone: (String?) -> Unit) {
        val uris = recordUris.distinct().filter { it.isNotBlank() }
        if (uris.isEmpty() || !_bskyLoggedIn.value) { onDone(null); return }
        viewModelScope.launch(Dispatchers.IO) {
            val me = _bskyDid.value
            var error: String? = null
            val deleted = HashSet<String>()
            // Starter packs first, then the lists they point at.
            for (uri in uris.sortedBy { if (it.contains("app.bsky.graph.starterpack")) 0 else 1 }) {
                var result = bskyRepo.deleteOwnRecord(bskyToken, me, uri)
                if (result.isFailure && isAuthError(result.exceptionOrNull()?.message) && refreshBskyTokenIfPossible()) {
                    result = bskyRepo.deleteOwnRecord(bskyToken, me, uri)
                }
                result.onSuccess { deleted += uri }.onFailure { error = it.message ?: "Couldn't delete it" }
            }
            _userLists.value = _userLists.value.filterNot { it.uri in deleted }
            _userStarterPacks.value = _userStarterPacks.value.filterNot { it.uri in deleted || it.record?.list in deleted }
            _listMemberships.value = _listMemberships.value - deleted
            listMembershipCache.clear()
            withContext(Dispatchers.Main) {
                // A deleted list can't stay a Hub row or a pinned feed.
                deleted.forEach { uri ->
                    com.mediaviewer.util.HubLayout.remove(com.mediaviewer.util.HubLayout.listId(uri))
                    if (_availableFeeds.value.any { it.uri == uri }) removeFeed(uri)
                }
                if (error != null) _errorMessage.value = "Couldn't delete it: $error" else showToast("Deleted")
                onDone(error)
            }
            // Tidy up the list items that pointed at the deleted lists.
            deleted.filter { it.contains("app.bsky.graph.list/") }.forEach { bskyRepo.deleteListItemsOf(bskyToken, me, it) }
        }
    }

    /** Add To → double-tap a list's cover → pick a picture: that becomes
     *  the list's cover. (Lists only — starter packs draw their own card.) */
    fun setPickerListCover(listUri: String, image: com.mediaviewer.platform.PlatformUri, onDone: (String?) -> Unit) {
        if (!_bskyLoggedIn.value || listUri.isBlank()) { onDone(null); return }
        viewModelScope.launch(Dispatchers.IO) {
            var result = bskyRepo.setListCover(bskyToken, _bskyDid.value, platform.context, listUri, image)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message) && refreshBskyTokenIfPossible()) {
                result = bskyRepo.setListCover(bskyToken, _bskyDid.value, platform.context, listUri, image)
            }
            result.onSuccess {
                // Show the picked picture straight away; Bluesky's own CDN
                // link takes over the next time the lists are loaded.
                _userLists.value = _userLists.value.map { if (it.uri == listUri) it.copy(avatar = image.toString()) else it }
                showToast("Cover updated")
            }.onFailure { _errorMessage.value = "Couldn't change the cover: ${it.message}" }
            withContext(Dispatchers.Main) { onDone(result.exceptionOrNull()?.message) }
        }
    }

    // ── Customize Hub → Add → Profiles ───────────────────────────────────

    private val _hubProfileCandidates = MutableStateFlow<List<AuthorInfo>>(emptyList())
    val hubProfileCandidates: StateFlow<List<AuthorInfo>> = _hubProfileCandidates
    private val _hubProfileSearching = MutableStateFlow(false)
    val hubProfileSearching: StateFlow<Boolean> = _hubProfileSearching
    private var hubProfileSearchJob: Job? = null
    private var hubProfileSuggestJob: Job? = null
    private var hubProfileQuery = ""
    private var hubProfileSuggestions: List<AuthorInfo> = emptyList()
    private var hubProfileSuggestCursor: String? = null
    private var hubProfileSuggestLoaded = false

    /** Blank query: the accounts you follow (newest first); otherwise a
     *  search across all of Bluesky. */
    fun searchHubProfiles(query: String) {
        hubProfileSearchJob?.cancel()
        val q = query.trim()
        hubProfileQuery = q
        if (q.isEmpty()) {
            _hubProfileCandidates.value = hubProfileSuggestions
            if (!hubProfileSuggestLoaded) loadMoreHubProfileSuggestions()
            else _hubProfileSearching.value = false
            return
        }
        hubProfileSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250) // typing debounce
            _hubProfileSearching.value = true
            bskyRepo.searchActorsTypeahead(bskyToken, _bskyDid.value, q).onSuccess { actors ->
                if (hubProfileQuery == q) {
                    _hubProfileCandidates.value = actors.map { a ->
                        AuthorInfo(did = a.did, handle = a.handle, displayName = a.displayName?.takeIf { it.isNotBlank() } ?: a.handle, avatarUrl = a.avatar)
                    }
                }
            }
            _hubProfileSearching.value = false
        }
    }

    fun loadMoreHubProfileSuggestions() {
        if (hubProfileSuggestJob?.isActive == true) return
        if (hubProfileSuggestLoaded && hubProfileSuggestCursor == null) return
        hubProfileSuggestJob = viewModelScope.launch(Dispatchers.IO) {
            if (hubProfileSuggestions.isEmpty()) _hubProfileSearching.value = true
            bskyRepo.getFollowsForChat(bskyToken, _bskyDid.value, hubProfileSuggestCursor).onSuccess { (page, cursor) ->
                hubProfileSuggestLoaded = true
                hubProfileSuggestCursor = cursor
                hubProfileSuggestions = (hubProfileSuggestions + page.map { a ->
                    AuthorInfo(did = a.did, handle = a.handle, displayName = a.displayName?.takeIf { it.isNotBlank() } ?: a.handle, avatarUrl = a.avatar)
                }).distinctBy { it.did }
                if (hubProfileQuery.isEmpty()) _hubProfileCandidates.value = hubProfileSuggestions
            }
            if (hubProfileQuery.isEmpty()) _hubProfileSearching.value = false
        }
    }

    /** "Add to hub": a new Hub row of the picked accounts, kept on this
     *  device only (no Bluesky list is made). */
    fun addHubProfilesRow(name: String, members: List<AuthorInfo>) {
        if (members.isEmpty()) return
        tapHaptic()
        val id = com.mediaviewer.util.HubLayout.addProfiles(
            name, members.map { com.mediaviewer.util.HubLayout.Profile(it.did, it.handle, it.displayName, it.avatarUrl) }
        )
        loadHubListIfNeeded(id, force = true)
        showToast("Added \"${name.trim().ifBlank { "Profiles" }}\" to the Hub")
    }

    /** Customize Hub → a Profiles row's edit button → Save. */
    fun editHubProfilesRow(id: String, name: String, members: List<AuthorInfo>) {
        if (members.isEmpty()) return
        tapHaptic()
        com.mediaviewer.util.HubLayout.updateProfiles(
            id, name, members.map { com.mediaviewer.util.HubLayout.Profile(it.did, it.handle, it.displayName, it.avatarUrl) }
        )
        loadHubListIfNeeded(id, force = true)
    }

    // ── Stellar Supporters ───────────────────────────────────────────────

    /** Re-reads the Stellar Supporters list (every app start). */
    private fun refreshSupporters() {
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.getPublicListMemberDids(com.mediaviewer.util.StellarOfficial.SUPPORTERS_LIST_URI).onSuccess { dids ->
                withContext(Dispatchers.Main) { com.mediaviewer.util.StellarSupporters.update(dids) }
            }
        }
    }

    // ── Welcome popup → tutorial popup ───────────────────────────────────

    /** One row of the welcome popup. */
    data class WelcomeEntry(
        val key: String,
        val title: String,
        /** "@handle" under/after the title. */
        val by: String,
        val description: String,
        val imageUrl: String? = null,
        /** Accounts are round; feed/list covers are rounded squares. */
        val round: Boolean = false
    )
    data class WelcomeState(
        val feeds: List<WelcomeEntry>,
        val accounts: List<WelcomeEntry>,
        /** "Continue" was pressed: adding/following in progress. */
        val applying: Boolean = false
    )

    private val _welcome = MutableStateFlow<WelcomeState?>(null)
    val welcome: StateFlow<WelcomeState?> = _welcome
    private val _tutorialOpen = MutableStateFlow(false)
    val tutorialOpen: StateFlow<Boolean> = _tutorialOpen

    /** The tutorial video, once its post has been looked up (null while
     *  there's no tutorial yet — the popup shows a placeholder card). */
    data class TutorialVideo(val playlistUrl: String, val thumbUrl: String, val aspectRatio: Float)
    private val _tutorialVideo = MutableStateFlow<TutorialVideo?>(null)
    val tutorialVideo: StateFlow<TutorialVideo?> = _tutorialVideo

    private val welcomeFeedForYou = "feed:for-you"
    private val welcomeFeedSupporters = "feed:supporters"
    private val welcomeFollowStellar = "follow:stellar"
    private val welcomeFollowRecho = "follow:recho"

    /** Opens the welcome popup (once per account — see Onboarding; Dev
     *  Tools can show it again). Covers and avatars are filled in as they
     *  arrive. */
    fun openWelcome() {
        if (_welcome.value != null || _tutorialOpen.value || !_bskyLoggedIn.value) return
        val official = com.mediaviewer.util.StellarOfficial
        _welcome.value = WelcomeState(
            feeds = listOf(
                WelcomeEntry(
                    welcomeFeedForYou, "For You", "@" + official.FOR_YOU_FEED_BY,
                    "Recommended feed for Stellar due to it's accurate, algorithmic nature."
                ),
                WelcomeEntry(
                    welcomeFeedSupporters, "Stellar Supporters", "@" + official.STELLAR_HANDLE,
                    "A feed of people who support Stellar financially!!"
                )
            ),
            accounts = listOf(
                WelcomeEntry(
                    welcomeFollowStellar, "Stellar", "@" + official.STELLAR_HANDLE,
                    "Stellar's official account!! Follow to keep up to date with all things Stellar!!", round = true
                ),
                WelcomeEntry(
                    welcomeFollowRecho, "Recho Raccoon", "@" + official.RECHO_HANDLE,
                    "The developer of Stellar!!", round = true
                )
            )
        )
        fun setImage(key: String, url: String?) {
            if (url.isNullOrBlank()) return
            val cur = _welcome.value ?: return
            _welcome.value = cur.copy(
                feeds = cur.feeds.map { if (it.key == key) it.copy(imageUrl = url) else it },
                accounts = cur.accounts.map { if (it.key == key) it.copy(imageUrl = url) else it }
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            launch { setImage(welcomeFeedForYou, bskyRepo.getFeedGeneratorInfo(bskyToken, official.FOR_YOU_FEED_URI).getOrNull()?.avatar) }
            launch { setImage(welcomeFeedSupporters, bskyRepo.getListInfo(bskyToken, official.SUPPORTERS_LIST_URI).getOrNull()?.avatarUrl) }
            launch { setImage(welcomeFollowStellar, bskyRepo.getProfileBasics(bskyToken, official.STELLAR_DID).getOrNull()?.avatarUrl) }
            launch { setImage(welcomeFollowRecho, bskyRepo.getProfileBasics(bskyToken, official.RECHO_HANDLE).getOrNull()?.avatarUrl) }
        }
    }

    /** The welcome popup's "Continue": adds every feed and follows every
     *  account whose switch is on ([selected] = their keys), then moves on
     *  to the tutorial popup. */
    fun applyWelcome(selected: Set<String>) {
        val cur = _welcome.value ?: return
        if (cur.applying) return
        tapHaptic()
        _welcome.value = cur.copy(applying = true)
        val official = com.mediaviewer.util.StellarOfficial
        val me = _bskyDid.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                var feedsChanged = false
                // One preferences write at a time (they're read-modify-write).
                feedPrefsMutex.withLock {
                    // Both go to the top of the feeds list: For You first, then
                    // Stellar Supporters (so Supporters is put in first).
                    if (welcomeFeedSupporters in selected && _availableFeeds.value.none { it.uri == official.SUPPORTERS_LIST_URI }) {
                        bskyRepo.addSavedFeed(bskyToken, official.SUPPORTERS_LIST_URI, type = "list", pinned = true, atFront = true)
                            .onSuccess { feedsChanged = true }
                    }
                    if (welcomeFeedForYou in selected && _availableFeeds.value.none { it.uri == official.FOR_YOU_FEED_URI }) {
                        bskyRepo.addSavedFeed(bskyToken, official.FOR_YOU_FEED_URI, pinned = true, atFront = true)
                            .onSuccess { feedsChanged = true }
                    }
                }
                suspend fun follow(actor: String) {
                    val profile = bskyRepo.getProfileBasics(bskyToken, actor).getOrNull() ?: return
                    if (profile.did == me || profile.isFollowing) return
                    bskyRepo.followUser(bskyToken, me, profile.did)
                }
                if (welcomeFollowStellar in selected) follow(official.STELLAR_DID)
                if (welcomeFollowRecho in selected) follow(official.RECHO_HANDLE)
                if (feedsChanged) loadAvailableFeeds()
            }
            withContext(Dispatchers.Main) {
                com.mediaviewer.util.Onboarding.markWelcomed(me)
                com.mediaviewer.util.UiToggles.devWelcomePreview = false
                _welcome.value = null
                _tutorialOpen.value = true
            }
            loadTutorialVideo()
        }
    }

    /** "Go beyond the Atmosphere": closes the tutorial popup. */
    fun closeTutorial() { _tutorialOpen.value = false }

    private suspend fun loadTutorialVideo() {
        val link = com.mediaviewer.util.StellarOfficial.TUTORIAL_POST_URL
        if (link.isBlank() || _tutorialVideo.value != null) return
        runCatching {
            val m = Regex("""profile/([^/?#\s]+)/post/([^/?#\s]+)""").find(link) ?: return
            val actor = m.groupValues[1]
            val did = if (actor.startsWith("did:")) actor else bskyRepo.getProfileBasics(bskyToken, actor).getOrNull()?.did ?: return
            val item = bskyRepo.getPostItems(bskyToken, _bskyDid.value, listOf("at://$did/app.bsky.feed.post/${m.groupValues[2]}"))
                .firstOrNull { it.isVideo && !it.videoPlaylistUrl.isNullOrBlank() } ?: return
            _tutorialVideo.value = TutorialVideo(
                playlistUrl = item.videoPlaylistUrl!!, thumbUrl = item.thumbUrl.ifBlank { item.mediaUrl },
                aspectRatio = item.aspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
            )
        }
    }

    init {
        // Declared last, so everything above exists by the time it runs.
        com.mediaviewer.util.StellarSupporters.init(platform.context)
        com.mediaviewer.util.Onboarding.init(platform.context)
        refreshSupporters()
    }

    fun clearError() { _errorMessage.value = null }

    private fun showToast(msg: String) {
        viewModelScope.launch(Dispatchers.Main) {
            platform.toast(msg)
        }
    }
}

/** How many new posts a Hub list row pulls in per batch. */
private const val HUB_LIST_BATCH = 12
/** How many of a list's (most recently active) members the Hub row reads
 *  one by one once Bluesky's list feed runs out. */
private const val HUB_LIST_MEMBER_CAP = 40
