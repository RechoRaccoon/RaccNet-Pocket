package com.mediaviewer.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.mediaviewer.model.*
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.repository.E621Repository
import com.mediaviewer.repository.StreamplaceRepository
import com.mediaviewer.tagging.TaggerModelManager
import com.mediaviewer.tagging.TaggingRepository
import com.mediaviewer.tagging.TagSuggestionProvider
import com.mediaviewer.util.PreferencesManager
import com.mediaviewer.worker.DownloadWorker
import com.mediaviewer.worker.GifDownloadWorker
import com.mediaviewer.worker.urlToDownloadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs     = PreferencesManager(application)
    private val bskyRepo  = BlueskyRepository()
    private val e621Repo  = E621Repository()
    private val streamplaceRepo = StreamplaceRepository()
    private val taggingRepo = TaggingRepository.get(application, bskyRepo, e621Repo)

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

    private val _translationTargetLang = MutableStateFlow(java.util.Locale.getDefault().language.ifBlank { "en" })
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
    fun setCustomFontFromUri(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            try {
                val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "Custom Font"
                val ext = displayName.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("ttf", "otf", "ttc")) {
                    _errorMessage.value = "Please choose a .ttf or .otf font file"
                    return@launch
                }
                val fontsDir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
                // Item 22: previously always wrote to the same "custom_font.$ext"
                // path. Re-picking a font with the same extension left that path
                // unchanged, and MainActivity's FontFamily is `remember`'d keyed
                // only on the path — so the new file's bytes were saved but the
                // already-cached FontFamily never got rebuilt. A unique name per
                // pick guarantees the path changes every time.
                val oldPath = _customFontPath.value
                val destFile = java.io.File(fontsDir, "custom_font_${System.currentTimeMillis()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    _errorMessage.value = "Couldn't read that font file"
                    return@launch
                }
                prefs.setCustomFontPath(destFile.absolutePath)
                prefs.setCustomFontName(displayName)
                _customFontPath.value = destFile.absolutePath
                _customFontName.value = displayName
                // Clean up the previous font file now that the new one is active.
                oldPath?.let { runCatching { java.io.File(it).delete() } }
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't load that font file: ${e.message}"
            }
        }
    }

    fun resetCustomFont() {
        viewModelScope.launch {
            _customFontPath.value?.let { path -> runCatching { java.io.File(path).delete() } }
            prefs.setCustomFontPath(null)
            prefs.setCustomFontName(null)
            _customFontPath.value = null
            _customFontName.value = null
        }
    }

    private fun queryDisplayName(context: Application, uri: android.net.Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    private val _downloadOnLike = MutableStateFlow(false)
    val downloadOnLike: StateFlow<Boolean> = _downloadOnLike

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress

    @Volatile private var cancelDownloadFlag = false

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
    private val _lastPickerTab = MutableStateFlow("LISTS")
    val lastPickerTab: StateFlow<String> = _lastPickerTab

    fun setPickerTab(tab: String) {
        _lastPickerTab.value = tab
        viewModelScope.launch { prefs.setLastPickerTab(tab) }
    }

    // ── Feed ──────────────────────────────────────────────────────────────────
    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = _mediaItems

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    private var feedCursor: String?  = null
    private var isLoadingMore        = false

    // Tracks what kind of feed is active so loadMore() uses the right endpoint
    private enum class ActiveFeedMode { NORMAL, AUTHOR, LIKES, FRIENDS, SAVES, HISTORY }
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
    enum class ProfileTab { MEDIA, TEXT_POSTS, VODS, REPOSTS, LIKES, BLOGS, REVIEWS, BACKLOG }

    data class ProfileTabState(
        val items: List<MediaItem> = emptyList(),
        val blogs: List<LeafletBlog> = emptyList(),
        val reviews: List<PopfeedReview> = emptyList(),
        val backlog: List<PopfeedBacklogItem> = emptyList(),
        // Item 19: VODs listed vertically, newest first — see StreamplaceRepository.
        val vods: List<StreamplaceVideoView> = emptyList(),
        val cursor: String? = null,
        val loading: Boolean = false,
        val loaded: Boolean = false
    )

    data class ProfileOverlayState(
        val author: AuthorInfo,
        val profile: ProfileData? = null,
        val loadingProfile: Boolean = true,
        val selectedTab: ProfileTab = ProfileTab.MEDIA,
        // Blogs/Reviews/Backlog are added to this set only once probing
        // confirms the account actually has Leaflet/Popfeed content — see
        // openProfile().
        val availableTabs: Set<ProfileTab> = setOf(ProfileTab.MEDIA, ProfileTab.TEXT_POSTS, ProfileTab.REPOSTS, ProfileTab.LIKES),
        val tabStates: Map<ProfileTab, ProfileTabState> = emptyMap(),
        val openBlog: LeafletBlog? = null,
        val openReview: PopfeedReview? = null,
        // Pinch navigation: tapping a post from this profile's grid doesn't
        // destroy this state (see openPostFromProfileTab) — it just flips
        // this to true, so the composable stays alive (scroll position and
        // all) invisibly behind the post pager. Pinching back in from that
        // post flips it back to false instead of reconstructing the profile
        // from scratch.
        val hidden: Boolean = false,
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
    private suspend fun loadSelfProfileSuspend() {
        if (!_bskyLoggedIn.value) return
        bskyRepo.getFullProfile(bskyToken, _bskyDid.value).onSuccess { _selfProfile.value = it }
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
    private val historyGson = com.google.gson.Gson()
    private val HISTORY_LIMIT = 500

    private fun loadHistoryFromPrefs() {
        viewModelScope.launch {
            val json = prefs.historyJson.first()
            val parsed = runCatching {
                val type = object : com.google.gson.reflect.TypeToken<List<HistoryEntry>>() {}.type
                historyGson.fromJson<List<HistoryEntry>>(json, type) ?: emptyList()
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
            authorAvatarUrl = item.author.avatarUrl, viewedAt = System.currentTimeMillis()
        )
        val updated = (listOf(entry) + _history.value.filterNot { it.uri.ifBlank { it.cid } == key }).take(HISTORY_LIMIT)
        _history.value = updated
        viewModelScope.launch { prefs.setHistoryJson(historyGson.toJson(updated)) }
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
        if (_authorFeedState.value == null) {
            _authorFeedState.value = AuthorFeedSavedState(
                author = AuthorInfo(_bskyDid.value, bskyHandle, "History", null),
                items = _mediaItems.value, currentIndex = _currentIndex.value, cursor = feedCursor, feedUri = _selectedFeedUri.value
            )
        }
        feedCursor = null
        activeFeedMode = ActiveFeedMode.HISTORY
        activeFeedActorDid = null
        _mediaItems.value = items
        _screenState.value = ScreenState.FEED
    }

    // ── Saves / Bookmarks (Settings Update) ─────────────────────────────────────
    fun showSaves() {
        if (!_bskyLoggedIn.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _currentIndex.value = 0
            if (_authorFeedState.value == null) {
                _authorFeedState.value = AuthorFeedSavedState(
                    author = AuthorInfo(_bskyDid.value, bskyHandle, "Saves", null),
                    items = _mediaItems.value, currentIndex = _currentIndex.value, cursor = feedCursor, feedUri = _selectedFeedUri.value
                )
            }
            var result = bskyRepo.getBookmarkedPosts(bskyToken)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getBookmarkedPosts(bskyToken)
            }
            result.onSuccess { (items, cursor) ->
                feedCursor = cursor
                activeFeedMode = ActiveFeedMode.SAVES
                activeFeedActorDid = null
                _mediaItems.value = filterHidden(items)
                _screenState.value = ScreenState.FEED
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
    val dmConversations: StateFlow<List<DmConversation>> = _dmConversations

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
        val loadingMore: Boolean = false
    )
    private val _dmInboxOpen = MutableStateFlow(false)
    val dmInboxOpen: StateFlow<Boolean> = _dmInboxOpen

    private val _dmThread = MutableStateFlow<DmThreadState?>(null)
    val dmThread: StateFlow<DmThreadState?> = _dmThread

    fun openDmInbox() {
        _dmInboxOpen.value = true
        loadDmConversations(silent = false)
    }

    fun closeDmInbox() { _dmInboxOpen.value = false; _dmThread.value = null }

    // ── Search (item 7) ──────────────────────────────────────────────────────

    // Reordered/renamed (per feedback): People now first (was Posts), and
    // the old "Lists" slot — which never actually had a working search
    // behind it, see the FEEDS case in runSearch below — is now Feeds,
    // backed by a real search. Enum name kept as ACCOUNTS/FEEDS rather than
    // renaming the Kotlin identifiers too, to keep this diff scoped to
    // what's user-visible; .label() below is what actually says "People".
    enum class SearchFilter { ACCOUNTS, POSTS, LIKED_TAGS, FEEDS, STARTER_PACKS }

    data class SearchState(
        val query: String = "",
        val filter: SearchFilter = SearchFilter.ACCOUNTS,
        val posts: List<MediaItem> = emptyList(),
        val accounts: List<SearchAccountResult> = emptyList(),
        val starterPacks: List<SearchStarterPackResult> = emptyList(),
        val feeds: List<SearchFeedResult> = emptyList(),
        val loading: Boolean = false,
        val hasSearched: Boolean = false
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
    val searchState: StateFlow<SearchState> = _searchState

    private var searchJob: kotlinx.coroutines.Job? = null

    fun openSearch() { _searchOpen.value = true }
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
                    bskyRepo.searchPosts(bskyToken, query).onSuccess { (posts, _) ->
                        _searchState.value = _searchState.value.copy(posts = posts, loading = false, hasSearched = true)
                    }.onFailure { _searchState.value = _searchState.value.copy(loading = false, hasSearched = true) }
                }
                // Item 2: kept only as a safety net — typing on the Liked
                // tab no longer routes through runSearch at all (see
                // updateLikedQueryText/submitLikedSearch), but if anything
                // else ever calls runSearch while that filter is active,
                // this keeps it working rather than silently doing nothing.
                SearchFilter.LIKED_TAGS -> performLikedTagSearch(query)
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
        _dmThread.value = DmThreadState(convo = convo, loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.getConvoMessages(bskyToken, _bskyDid.value, convo.convoId)
                .onSuccess { (messages, cursor) ->
                    _dmThread.value = _dmThread.value?.copy(
                        messages = messages, embeddedPosts = buildEmbeddedPosts(messages),
                        loading = false, cursor = cursor
                    )
                }
                .onFailure {
                    _dmThread.value = _dmThread.value?.copy(loading = false)
                    _errorMessage.value = it.message
                }
        }
    }

    // Item 12: parses each message's raw embed once when a batch of messages
    // loads, rather than re-parsing JSON on every recomposition.
    private fun buildEmbeddedPosts(messages: List<BskyMessageView>): Map<String, DmEmbeddedPost> =
        messages.mapNotNull { m -> bskyRepo.parseMessageEmbed(m.embed)?.let { m.id to it } }.toMap()

    fun closeDmThread() { _dmThread.value = null }

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
                        loadingMore = false
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
            bskyRepo.getFriendsSharedPosts(bskyToken, _bskyDid.value, listOf(convo), includeSelfSent = true)
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
    val friendsReviews: StateFlow<List<FriendPopfeedReview>> = _friendsReviews
    private val _friendsBlogs = MutableStateFlow<List<FriendLeafletBlog>>(emptyList())
    val friendsBlogs: StateFlow<List<FriendLeafletBlog>> = _friendsBlogs

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

    /** Loads (or reloads) the Hub's Reviews/Blogs sections from the current
     *  subscribed-DID sets. Cache-first, same instant-on-restart shape the
     *  old firehose indexer had: the last persisted snapshot publishes
     *  immediately, then a fresh fetch runs and overwrites/persists it.
     *  `force = true` (subscribe/unsubscribe, or the Hub's manual refresh
     *  bubble) bypasses the "already loaded this session" guard. */
    fun loadFriendsReviewsIfNeeded(force: Boolean = false) {
        if (reviewsBlogsLoaded && !force) return
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
        if (!_friendsReviewsLoading.compareAndSet(false, true)) return
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
                        val gson = com.google.gson.GsonBuilder()
                            .registerTypeAdapter(LeafletBlog::class.java, com.google.gson.JsonDeserializer { json, _, _ ->
                                val o = json.asJsonObject
                                LeafletBlog(
                                    uri = o.get("uri")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                    title = o.get("title")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                    bodyText = o.get("bodyText")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                    createdAt = o.get("createdAt")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                    description = o.get("description")?.takeIf { !it.isJsonNull }?.asString,
                                    thumbnailUrl = o.get("thumbnailUrl")?.takeIf { !it.isJsonNull }?.asString
                                    // blocks intentionally omitted — always emptyList() from cache.
                                )
                            })
                            .create()
                        val reviewType = object : com.google.gson.reflect.TypeToken<List<FriendPopfeedReview>>() {}.type
                        val blogType = object : com.google.gson.reflect.TypeToken<List<FriendLeafletBlog>>() {}.type
                        val cachedReviews: List<FriendPopfeedReview> = gson.fromJson(prefs.hubReviewsCacheJson.first(), reviewType) ?: emptyList()
                        val cachedBlogs: List<FriendLeafletBlog> = gson.fromJson(prefs.hubBlogsCacheJson.first(), blogType) ?: emptyList()
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
                    val gson = com.google.gson.Gson()
                    val reviewType = object : com.google.gson.reflect.TypeToken<List<FriendPopfeedReview>>() {}.type
                    val blogType = object : com.google.gson.reflect.TypeToken<List<FriendLeafletBlog>>() {}.type
                    prefs.setHubCache(gson.toJson(_friendsReviews.value, reviewType), gson.toJson(_friendsBlogs.value, blogType), System.currentTimeMillis())
                }
            } finally {
                _friendsReviewsLoading.value = false
            }
        }
    }

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

    // ── Item 8/19: Hub "Livestreams" section ─────────────────────────────────
    // Live status confirmed against the real place.stream.live.getLiveUsers
    // lexicon (see StreamplaceRepository.getLiveFriends) — unlike VODs, which
    // the user said are closed-beta/restrictive right now, live status is a
    // simple read-only platform-wide list this app just filters down to
    // accounts the user follows, so there's no beta-access gate to worry
    // about here.
    private val _liveFriends = MutableStateFlow<List<StreamplaceLiveStream>>(emptyList())
    val liveFriends: StateFlow<List<StreamplaceLiveStream>> = _liveFriends
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
    val blueskyLiveNow: StateFlow<List<BlueskyLiveNowStream>> = _blueskyLiveNow
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

    fun sendDmThreadReply(text: String) {
        val thread = _dmThread.value ?: return
        if (text.isBlank() || thread.convo.convoId.isBlank()) return
        _dmThread.value = thread.copy(sending = true)
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.sendMessage(bskyToken, _bskyDid.value, thread.convo.convoId, text)
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
        viewModelScope.launch { screenState.collect { if (it == ScreenState.FEED) _hasVisitedFeed.value = true } }
        viewModelScope.launch { prefs.reducedAnimations.collect { _reducedAnimations.value = it } }
        viewModelScope.launch { prefs.liquidGlass.collect { _liquidGlass.value = it } }
        viewModelScope.launch { prefs.liquidGlassIntensity.collect { _liquidGlassIntensity.value = it } }
        viewModelScope.launch { prefs.glassRimIntensity.collect { _glassRimIntensity.value = it } }
        viewModelScope.launch { prefs.downloadOnLike.collect { _downloadOnLike.value = it } }
        viewModelScope.launch { prefs.e621FollowedArtists.collect { _e621FollowedArtists.value = it } }
        viewModelScope.launch { prefs.hideTextOnlyPosts.collect { _hideTextOnlyPosts.value = it } }
        viewModelScope.launch { prefs.translateEnabled.collect { _translationEnabled.value = it } }
        viewModelScope.launch { prefs.translateTargetLang.collect { _translationTargetLang.value = it } }
        viewModelScope.launch { prefs.customFontPath.collect { _customFontPath.value = it } }
        viewModelScope.launch { prefs.customFontName.collect { _customFontName.value = it } }
        viewModelScope.launch { prefs.subscribedReviewDids.collect { _subscribedReviewDids.value = it } }
        viewModelScope.launch { prefs.subscribedBlogDids.collect { _subscribedBlogDids.value = it } }
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
        viewModelScope.launch {
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
            _lastPickerTab.value     = prefs.lastPickerTab.first()
            _combineListsAndPacks.value = prefs.combineListsAndPacks.first()
            _autoAddToOnFollow.value = prefs.autoAddToOnFollow.first()

            if (!e621User.isNullOrBlank() && !e621Key.isNullOrBlank()) {
                e621Username = e621User; e621ApiKey = e621Key; _e621LoggedIn.value = true
            }
            if (!accessJwt.isNullOrBlank() && did != null && handle != null) {
                bskyToken = accessJwt; bskyRefreshToken = refreshJwt ?: ""
                _bskyDid.value = did; bskyHandle = handle; _bskyLoggedIn.value = true
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
                    loadSelfProfile()
                }
                .onFailure { _errorMessage.value = it.message ?: "Login failed" }
            _isLoading.value = false
        }
    }

    fun logoutBluesky() {
        viewModelScope.launch {
            prefs.clearBskySession()
            bskyToken = ""; bskyRefreshToken = ""; _bskyDid.value = ""; bskyHandle = ""
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
            _dmConversations.value = emptyList()
            if (_appMode.value == AppMode.BLUESKY) {
                _mediaItems.value = emptyList()
                _screenState.value = ScreenState.SETTINGS
            }
        }
    }

    fun saveE621Credentials(username: String, apiKey: String) {
        if (username.isBlank() || apiKey.isBlank()) return
        viewModelScope.launch {
            e621Username = username
            e621ApiKey   = apiKey
            prefs.saveE621Credentials(username, apiKey)
            _e621LoggedIn.value = true
            _appMode.value = AppMode.E621
            prefs.setLastMode("E621")
            _screenState.value = ScreenState.FEED
            loadE621Posts()
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
        Log.d("RaccNet-FeedState", "loadFeed(reset=$reset)", Exception("trace"))
        if (_appMode.value == AppMode.E621) { loadE621Posts(reset); return }
        if (!_bskyLoggedIn.value) return
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
                return if (feedUri == null || feedUri == BlueskyRepository.FOLLOWING_FEED_URI)
                    bskyRepo.getTimeline(bskyToken, feedCursor)
                else bskyRepo.getFeed(bskyToken, feedUri, feedCursor)
            }

            var result = attempt()
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = attempt()
            }
            if (result.isFailure && isRateLimitError(result.exceptionOrNull()?.message)) {
                delay(4000)
                result = attempt()
            }
            result.onSuccess { (items, cursor) ->
                feedCursor = cursor
                _mediaItems.value = if (reset) filterHidden(items) else _mediaItems.value + filterHidden(items)
            }.onFailure { _errorMessage.value = it.message }
            _isLoading.value = false
            isLoadingMore = false
        }
    }

    /** Settings Update: "Hide Text Only Posts" — universally drops posts with
     *  no image/video from every feed this app renders. Applied at each fetch
     *  site (rather than as a post-hoc filter on [_mediaItems]) so pagination
     *  cursors and currentIndex math never have to account for hidden items. */
    private fun filterHidden(items: List<MediaItem>): List<MediaItem> =
        if (_hideTextOnlyPosts.value) items.filterNot { it.isTextOnly } else items

    fun loadMore() {
        if (feedCursor == null || isLoadingMore) return
        when (activeFeedMode) {
            ActiveFeedMode.NORMAL  -> loadFeed(reset = false)
            ActiveFeedMode.AUTHOR  -> loadMoreAuthorFeed()
            ActiveFeedMode.LIKES   -> loadMoreLikes()
            ActiveFeedMode.SAVES   -> loadMoreSaves()
            ActiveFeedMode.FRIENDS, ActiveFeedMode.HISTORY -> { /* fully loaded up front, no further pagination */ }
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
    fun openProfile(author: AuthorInfo, initialTab: ProfileTab = ProfileTab.MEDIA, review: PopfeedReview? = null, blog: LeafletBlog? = null) {
        if (!_bskyLoggedIn.value) return
        // Item 17: don't clobber a profile that's already open (visible or
        // hidden behind a post pager) — chain onto it via `parent` so
        // closeProfile() can unwind back through it instead of losing it.
        val parent = _profileOverlay.value
        _profileOverlay.value = ProfileOverlayState(author = author, selectedTab = initialTab, parent = parent, openReview = review, openBlog = blog)

        viewModelScope.launch(Dispatchers.IO) {
            var result = bskyRepo.getFullProfile(bskyToken, author.did)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message)) {
                if (refreshBskyTokenIfPossible()) result = bskyRepo.getFullProfile(bskyToken, author.did)
            }
            result.onSuccess { data ->
                _profileOverlay.value = _profileOverlay.value?.copy(profile = data, loadingProfile = false, author = data.author)
            }.onFailure {
                _profileOverlay.value = _profileOverlay.value?.copy(loadingProfile = false)
            }
        }

        loadProfileTab(initialTab, reset = true)

        viewModelScope.launch(Dispatchers.IO) {
            val blogs = runCatching { bskyRepo.getLeafletBlogs(author.did) }.getOrDefault(emptyList())
            if (blogs.isEmpty()) return@launch
            val cur = _profileOverlay.value?.takeIf { it.author.did == author.did } ?: return@launch
            _profileOverlay.value = cur.copy(
                availableTabs = cur.availableTabs + ProfileTab.BLOGS,
                tabStates = cur.tabStates + (ProfileTab.BLOGS to ProfileTabState(blogs = blogs, loaded = true))
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val reviews = runCatching { bskyRepo.getPopfeedReviews(author.did) }.getOrDefault(emptyList())
            if (reviews.isEmpty()) return@launch
            val cur = _profileOverlay.value?.takeIf { it.author.did == author.did } ?: return@launch
            _profileOverlay.value = cur.copy(
                availableTabs = cur.availableTabs + ProfileTab.REVIEWS,
                tabStates = cur.tabStates + (ProfileTab.REVIEWS to ProfileTabState(reviews = reviews, loaded = true))
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val backlog = runCatching { bskyRepo.getPopfeedBacklog(author.did) }.getOrDefault(emptyList())
            if (backlog.isEmpty()) return@launch
            val cur = _profileOverlay.value?.takeIf { it.author.did == author.did } ?: return@launch
            _profileOverlay.value = cur.copy(
                availableTabs = cur.availableTabs + ProfileTab.BACKLOG,
                tabStates = cur.tabStates + (ProfileTab.BACKLOG to ProfileTabState(backlog = backlog, loaded = true))
            )
        }
        // Item 19: VODs tab, only shown once we actually find any — most
        // accounts won't have Streamplace VODs, and that's a normal empty
        // result, not an error, so we stay silent on failure/empty here.
        viewModelScope.launch(Dispatchers.IO) {
            val vods = streamplaceRepo.getVods(author.handle).getOrNull()?.first ?: emptyList()
            if (vods.isEmpty()) return@launch
            val cur = _profileOverlay.value?.takeIf { it.author.did == author.did } ?: return@launch
            _profileOverlay.value = cur.copy(
                availableTabs = cur.availableTabs + ProfileTab.VODS,
                tabStates = cur.tabStates + (ProfileTab.VODS to ProfileTabState(vods = vods, loaded = true))
            )
        }
    }

    // Item 24: the blog-detail popup and the profile it sits on top of are
    // both full-screen overlays with their own X button in roughly the same
    // top-left spot, so closeProfile() needs to step down one layer at a
    // time — close whatever sub-overlay (blog/review) is open first — rather
    // than always tearing down the whole profile in one shot.
    fun closeProfile() {
        val cur = _profileOverlay.value ?: return
        when {
            cur.openBlog != null -> { _profileOverlay.value = cur.copy(openBlog = null); return }
            cur.openReview != null -> { _profileOverlay.value = cur.copy(openReview = null); return }
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
        if (passedHidden) restoreSavedMainFeed()
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
        _profileOverlay.value = cur.copy(selectedTab = tab)
        val state = cur.tabStates[tab]
        if (state == null || (!state.loaded && !state.loading)) loadProfileTab(tab, reset = true)
    }

    fun loadMoreProfileTab() {
        val cur = _profileOverlay.value ?: return
        val state = cur.tabStates[cur.selectedTab] ?: return
        if (state.loading || state.cursor == null) return
        loadProfileTab(cur.selectedTab, reset = false)
    }

    private fun loadProfileTab(tab: ProfileTab, reset: Boolean) {
        // Blogs/Reviews/Backlog/Vods are fully loaded up-front by the probes
        // in openProfile() — there's no separate paged fetch for them.
        if (tab == ProfileTab.BLOGS || tab == ProfileTab.REVIEWS || tab == ProfileTab.BACKLOG || tab == ProfileTab.VODS) return
        val cur = _profileOverlay.value ?: return
        val did = cur.author.did
        val existing = cur.tabStates[tab] ?: ProfileTabState()
        if (existing.loading) return
        val cursorToUse = if (reset) null else existing.cursor
        _profileOverlay.value = cur.copy(tabStates = cur.tabStates + (tab to existing.copy(loading = true)))

        viewModelScope.launch(Dispatchers.IO) {
            // Media and Text Posts both come from the account's own posts feed —
            // Bluesky has no separate "media only"/"text only" endpoint — so both
            // tabs fetch the same underlying feed independently (own cursor, own
            // paging) and each keeps only the items it cares about.
            suspend fun fetchPage(cursor: String?) = when (tab) {
                ProfileTab.MEDIA, ProfileTab.TEXT_POSTS -> bskyRepo.getProfilePosts(bskyToken, did, cursor)
                ProfileTab.REPOSTS -> bskyRepo.getProfileReposts(bskyToken, did, cursor)
                ProfileTab.LIKES   -> bskyRepo.getProfileLikes(bskyToken, _bskyDid.value, did, cursor)
                else -> error("unreachable")
            }
            fun filterForTab(fetched: List<MediaItem>): List<MediaItem> = filterHidden(fetched).let { items ->
                when (tab) {
                    ProfileTab.MEDIA      -> items.filterNot { it.isTextOnly }
                    ProfileTab.TEXT_POSTS -> items.filter { it.isTextOnly }
                    else -> items
                }
            }

            var cursorNow = cursorToUse
            val accumulated = mutableListOf<MediaItem>()
            var authRetried = false
            var succeeded = false
            // Media/Text Posts filter client-side (MEDIA keeps non-text-only
            // posts, TEXT_POSTS keeps text-only ones) — a raw page can come
            // back entirely the *other* type and filter down to zero results,
            // even though there's more content on the next page. Without
            // auto-continuing past those empty-after-filter pages, nothing
            // would render, so no grid row would ever compose to trigger the
            // usual "near the bottom" auto-load, and the tab would look
            // permanently empty despite loaded=true. Capped so an account
            // that's e.g. entirely text-only can't spin through their whole
            // history in a single call — remaining pages still load normally
            // via the regular scroll-triggered load-more once something's on
            // screen. Reposts/Likes don't filter, so they always stop after
            // one page exactly as before.
            val maxAutoPages = if (tab == ProfileTab.MEDIA || tab == ProfileTab.TEXT_POSTS) 6 else 1
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
                _profileOverlay.value = cur2.copy(
                    tabStates = cur2.tabStates + (tab to ProfileTabState(items = prevItems + accumulated, cursor = cursorNow, loading = false, loaded = true))
                )
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

    fun openPostFromProfileTab(index: Int) {
        val cur = _profileOverlay.value ?: return
        val items = cur.tabStates[cur.selectedTab]?.items ?: return
        if (index !in items.indices) return
        if (_authorFeedState.value == null) {
            _authorFeedState.value = AuthorFeedSavedState(
                author       = cur.author,
                items        = _mediaItems.value,
                currentIndex = _currentIndex.value,
                cursor       = feedCursor,
                feedUri      = _selectedFeedUri.value
            )
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
    fun pinchInFromPost() {
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

    /** Feature (this session): the Hub's Mutual Reviews cards call this
     *  directly instead of onOpenProfile — opens the review's author's
     *  profile (Reviews tab) with the review's own detail overlay already
     *  showing on top, matching "open the full review in its overlay the
     *  same way it does when opening one on someone's profile." */
    fun openMutualReview(fr: FriendPopfeedReview) {
        openProfile(fr.author, initialTab = ProfileTab.REVIEWS, review = fr.review)
    }

    /** Hub Blogs section equivalent of [openMutualReview] above. */
    fun openMutualBlog(fb: FriendLeafletBlog) {
        openProfile(fb.author, initialTab = ProfileTab.BLOGS, blog = fb.blog)
    }
    fun closeProfileBlog() { _profileOverlay.value = _profileOverlay.value?.copy(openBlog = null) }
    fun openProfileReview(review: PopfeedReview) { _profileOverlay.value = _profileOverlay.value?.copy(openReview = review) }
    fun closeProfileReview() { _profileOverlay.value = _profileOverlay.value?.copy(openReview = null) }

    fun toggleProfileFollow() {
        val cur = _profileOverlay.value ?: return
        val profile = cur.profile ?: return
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
        Log.d("RaccNet-FeedState", "loadE621Posts(reset=$reset)", Exception("trace"))
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
        if (_authorFeedState.value == null) {
            _authorFeedState.value = AuthorFeedSavedState(
                author       = AuthorInfo(_bskyDid.value, bskyHandle, "From Friends", null),
                items        = _mediaItems.value,
                currentIndex = _currentIndex.value,
                cursor       = feedCursor,
                feedUri      = _selectedFeedUri.value
            )
        }
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
            _screenState.value = ScreenState.FEED
        }
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
        // Not ready yet: show the full-screen "Loading From Friends feed…" overlay
        // (handled in the UI layer) while we fetch it live.
        viewModelScope.launch(Dispatchers.IO) {
            _friendsFeedLoadingOverlay.value = true
            ensureDmConversationsLoadedSuspend(silent = true)
            val realConvos = _dmConversations.value.filter { it.convoId.isNotBlank() }
            bskyRepo.getFriendsSharedPosts(bskyToken, _bskyDid.value, realConvos)
                .onSuccess { items ->
                    _friendsFeedCache.value = items
                    openFriendsFeed(items)
                }
                .onFailure { showToast("Feed Empty") }
            _friendsFeedLoadingOverlay.value = false
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
                        .onSuccess { showToast("Unblocked @${item.author.handle}") }
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
                        _mediaItems.value = _mediaItems.value.map {
                            if (it.author.did == targetDid) it.copy(isBlocked = true, blockUri = uri) else it
                        }
                    }
                    .onFailure { _errorMessage.value = "Block failed: ${it.message}" }
            }
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
            bskyRepo.sendFeedInteraction(bskyToken, item.postUri, wantMore = true, feedContext = item.feedContext, generatorDid = generatorDid)
                .onSuccess { showToast("Showing more like this") }
                .onFailure { _errorMessage.value = "Couldn't send feedback: ${it.message}" }
        }
    }

    fun sendShowLessLikeThisForCurrentItem() {
        val item = currentItem.value ?: return
        if (_appMode.value != AppMode.BLUESKY) return
        val generatorDid = _selectedFeedUri.value?.let { _feedGeneratorDid.value[it] }
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.sendFeedInteraction(bskyToken, item.postUri, wantMore = false, feedContext = item.feedContext, generatorDid = generatorDid)
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

    // ── Quote repost (item 5) ──────────────────────────────────────────────────

    fun openQuoteRepost() {
        val item = currentItem.value ?: return
        if (_appMode.value != AppMode.BLUESKY) return
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
    private val dmConversationsMutex = Mutex()

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
                val result = bskyRepo.getConvoLog(bskyToken, _bskyDid.value, dmLogCursor)
                result.onSuccess { (logs, cursor) ->
                    cursor?.let { dmLogCursor = it }
                    if (logs.isNotEmpty()) applyDmLogEntries(logs)
                }
            }
        }
    }

    private fun applyDmLogEntries(logs: List<BskyConvoLogEntry>) {
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
            viewModelScope.launch(Dispatchers.IO) { loadDmConversationsBlocking(silent = true) }
        } else {
            val byConvo = messageEntries.groupBy { it.convoId!! }
            _dmConversations.value = _dmConversations.value.map { convo ->
                val latest = byConvo[convo.convoId]?.maxByOrNull { it.message!!.sentAt } ?: return@map convo
                val msg = latest.message!!
                convo.copy(
                    lastActivityAt = msg.sentAt,
                    lastSentByUsAt = if (msg.sender?.did == _bskyDid.value) msg.sentAt else convo.lastSentByUsAt
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
                val type = object : com.google.gson.reflect.TypeToken<List<DmConversation>>() {}.type
                val cached: List<DmConversation> = com.google.gson.Gson().fromJson(prefs.hubMutualsCacheJson.first(), type) ?: emptyList()
                if (cached.isNotEmpty()) _dmConversations.value = cached
            }
        }
        bskyRepo.loadDmRecipients(bskyToken, _bskyDid.value)
            .onSuccess {
                _dmConversations.value = it
                runCatching {
                    val type = object : com.google.gson.reflect.TypeToken<List<DmConversation>>() {}.type
                    prefs.setHubMutualsCache(com.google.gson.Gson().toJson(it, type))
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
                    bskyRepo.sendMessage(bskyToken, _bskyDid.value, convoId, message, item.postUri, item.postCid)
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
        Log.d("RaccNet-FeedState", "setMode: ${_appMode.value} -> $mode")
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
            if (next >= _mediaItems.value.size - 5) loadMore()
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

    fun toggleLike() {
        val item = currentItem.value ?: return
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
        val context = getApplication<Application>()
        val loader = context.imageLoader
        lists.mapNotNull { it.avatar }.distinct().forEach { url ->
            loader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
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

    private fun openListPicker(targetDid: String) {
        _listPickerTargetDid.value = targetDid
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
    }

    fun addAccountToList(listUri: String, additionalListUri: String? = null) {
        val targetDid = _listPickerTargetDid.value ?: return
        _listPickerTargetDid.value = null
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.addToList(bskyToken, _bskyDid.value, listUri, targetDid)
                .onSuccess { showToast("Added to list") }
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
                GifDownloadWorker.enqueue(getApplication(), img.mediaUrl, false, "gif_${item.id}_$i")
            }
        } else {
            val sourceUrl = if (item.isVideo) (item.videoPlaylistUrl.takeUnless { it.isNullOrBlank() } ?: item.mediaUrl) else item.mediaUrl
            val did = item.author.did.takeIf { item.isVideo && it.isNotBlank() }
            val cid = item.videoBlobCid.takeIf { item.isVideo }
            GifDownloadWorker.enqueue(getApplication(), sourceUrl, item.isVideo, "gif_${item.id}", blobDid = did, blobCid = cid)
        }
        updateCurrentItem { it.copy(isGifDownloaded = true) }
    }

    // ── Comments ──────────────────────────────────────────────────────────────

    private fun loadComments() {
        val item = currentItem.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _commentsLoading.value = true
            _comments.value = emptyList()
            if (_appMode.value == AppMode.BLUESKY)
                bskyRepo.getPostThread(bskyToken, item.postUri)
                    .onSuccess { _comments.value = it }
                    .onFailure { _errorMessage.value = it.message }
            else {
                val pid = item.e621PostId ?: return@launch
                e621Repo.getComments(e621Username, e621ApiKey, pid)
                    .onSuccess { _comments.value = it }
                    .onFailure { _errorMessage.value = it.message }
            }
            _commentsLoading.value = false
        }
    }

    // Item 20: replying to a specific comment now actually threads the reply
    // under that comment (parent = the tapped comment's own uri/cid) instead
    // of always posting a fresh top-level reply to the post with just an
    // "@handle" tacked onto the text. The root stays the original post, same
    // as Bluesky's own reply-thread semantics.
    fun postComment(text: String, replyTo: CommentItem? = null) {
        val item = currentItem.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (_appMode.value == AppMode.BLUESKY) {
                val parentUri = replyTo?.uri?.takeIf { it.isNotBlank() } ?: item.postUri
                val parentCid = replyTo?.cid?.takeIf { it.isNotBlank() } ?: item.postCid
                bskyRepo.replyToPost(bskyToken, _bskyDid.value,
                    item.postUri, item.postCid, parentUri, parentCid, text)
                    .onSuccess { loadComments() }
                    .onFailure { _errorMessage.value = it.message }
            } else {
                e621Repo.createComment(e621Username, e621ApiKey, item.e621PostId ?: return@launch, text)
                    .onSuccess { loadComments() }
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
        DownloadWorker.enqueue(getApplication(), finalUrl, filename, mimeType, uniqueId)
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
                DownloadWorker.enqueueVideoBlob(getApplication(), did, cid, item.id)
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
        val modelState: TaggerModelManager.State = TaggerModelManager.State.NotDownloaded,
        val errorMessage: String? = null
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

    // Item 6: how many liked posts to fetch+decode+run through the tagger
    // in parallel during a "Locally Tag All Liked Posts" pass (1-10, see
    // TaggingRepository.tagAllLiked's own doc comment on why this is safe
    // to parallelize). Realtime tag-on-like always tags just the one post
    // that was liked, so this only affects the bulk pass.
    val tagConcurrency: StateFlow<Int> =
        prefs.tagConcurrency.stateIn(viewModelScope, SharingStarted.Eagerly, 3)

    fun setTagConcurrency(value: Int) {
        viewModelScope.launch { prefs.setTagConcurrency(value.coerceIn(1, 10)) }
    }

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

    init {
        refreshTaggingCounts()
    }

    private fun refreshTaggingCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            val (scanned, tagged) = taggingRepo.currentCounts()
            _hasTaggedDataset.value = scanned > 0
            _taggingUiState.value = _taggingUiState.value.copy(scanned = scanned, tagged = tagged, datasetBytes = taggingRepo.datasetSizeBytes())
        }
    }

    fun setTagPostWhenLiked(enabled: Boolean) {
        viewModelScope.launch { prefs.setTagPostWhenLiked(enabled) }
    }

    private fun maybeTagOnLike(item: MediaItem) {
        if (!tagPostWhenLiked.value) return
        viewModelScope.launch(Dispatchers.IO) {
            taggingRepo.tagOnLike(item)
            refreshTaggingCounts()
        }
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
                concurrency = tagConcurrency.value
            ) { progress ->
                _taggingUiState.value = TaggingUiState(
                    scanned = progress.scanned,
                    tagged = progress.tagged,
                    datasetBytes = progress.datasetBytes,
                    isRunning = progress.isRunning,
                    isComplete = progress.isComplete,
                    modelState = progress.modelState,
                    errorMessage = (progress.modelState as? TaggerModelManager.State.Failed)?.message
                )
                if (progress.scanned > 0) _hasTaggedDataset.value = true
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
            performLikedTagSearch("")
        }
    }

    /** Closes the overlay after a completed (or cancelled) run — separate
     *  from cancelTagging() since the person can dismiss a *finished* run's
     *  "Tagging Complete" card without that meaning "stop", and dismissing
     *  mid-run should stop the in-flight pass. */
    fun dismissTaggingOverlay() {
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

    /** Item 2: blank query browses everything tagged so far, most recent
     *  first, instead of an empty "type to search" state — a search query
     *  narrows that same list by tag. */
    private suspend fun performLikedTagSearch(query: String) {
        _searchState.value = _searchState.value.copy(loading = true)
        val uris = if (query.isBlank()) taggingRepo.browseAllTagged() else taggingRepo.search(query)
        _likedTagSearchResults.value = hydrateLikedUris(uris)
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
    }

    fun clearError() { _errorMessage.value = null }

    private fun showToast(msg: String) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
        }
    }
}
