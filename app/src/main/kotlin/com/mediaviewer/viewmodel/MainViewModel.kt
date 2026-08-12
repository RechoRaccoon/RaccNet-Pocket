package com.mediaviewer.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.mediaviewer.model.*
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.repository.E621Repository
import com.mediaviewer.repository.StreamplaceRepository
import com.mediaviewer.util.PreferencesManager
import com.mediaviewer.worker.DownloadWorker
import com.mediaviewer.worker.GifDownloadWorker
import com.mediaviewer.worker.urlToDownloadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs     = PreferencesManager(application)
    private val bskyRepo  = BlueskyRepository()
    private val e621Repo  = E621Repository()
    private val streamplaceRepo = StreamplaceRepository()

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
        viewModelScope.launch(Dispatchers.IO) {
            bskyRepo.getFullProfile(bskyToken, _bskyDid.value).onSuccess { _selfProfile.value = it }
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

    enum class SearchFilter { POSTS, ACCOUNTS, LISTS, STARTER_PACKS }

    data class SearchState(
        val query: String = "",
        val filter: SearchFilter = SearchFilter.POSTS,
        val posts: List<MediaItem> = emptyList(),
        val accounts: List<SearchAccountResult> = emptyList(),
        val starterPacks: List<SearchStarterPackResult> = emptyList(),
        val loading: Boolean = false,
        val hasSearched: Boolean = false
    )

    private val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState

    private var searchJob: kotlinx.coroutines.Job? = null

    fun openSearch() { _searchOpen.value = true }
    fun closeSearch() {
        _searchOpen.value = false
        searchJob?.cancel()
        _searchState.value = SearchState()
    }

    fun setSearchFilter(filter: SearchFilter) {
        _searchState.value = _searchState.value.copy(filter = filter)
        // Re-run against the already-typed query if this filter hasn't been
        // searched yet — avoids re-fetching a filter the user already saw.
        if (_searchState.value.query.isNotBlank()) runSearch(_searchState.value.query)
    }

    fun runSearch(query: String) {
        searchJob?.cancel()
        _searchState.value = _searchState.value.copy(query = query)
        if (query.isBlank()) {
            _searchState.value = _searchState.value.copy(
                posts = emptyList(), accounts = emptyList(), starterPacks = emptyList(), loading = false, hasSearched = false
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
                // Item 7: Bluesky's public API has no list-search endpoint —
                // only per-actor app.bsky.graph.getLists — so this tab has
                // nothing to fetch. SearchOverlay shows an explanatory empty
                // state instead of a fake/broken result list.
                SearchFilter.LISTS -> {
                    _searchState.value = _searchState.value.copy(loading = false, hasSearched = true)
                }
            }
        }
    }

    /** Opens a post found via search in its own standalone pager, the same
     *  way tapping into any other feed does — navDirection 0 since there's
     *  no meaningful slide direction coming from a flat search result list. */
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
        closeSearch()
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
    private val _friendsReviews = MutableStateFlow<List<FriendPopfeedReview>>(emptyList())
    val friendsReviews: StateFlow<List<FriendPopfeedReview>> = _friendsReviews
    private val _friendsReviewsLoading = MutableStateFlow(false)
    val friendsReviewsLoading: StateFlow<Boolean> = _friendsReviewsLoading
    private var friendsReviewsLoaded = false

    fun loadFriendsReviewsIfNeeded() {
        if (friendsReviewsLoaded || _friendsReviewsLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _friendsReviewsLoading.value = true
            // Bug fix: this used to read from `dmConversations`, which is
            // lazy-loaded only once the DM inbox is opened — reading it here
            // (often before that ever happened) saw an empty list and
            // silently marked itself "loaded" with nothing fetched, so
            // reviews never showed up even for accounts confirmed to have
            // some.
            //
            // Scope change (per feedback, this session): narrowed back down
            // to Mutuals specifically ("Latest Reviews From Mutuals" now,
            // not everyone followed) — reuses the exact same dmConversations
            // list the Mutuals row and Send Post use (via
            // ensureDmConversationsLoadedSuspend, single-flight-guarded —
            // see its comment), rather than a separate getAllFollows() call.
            // This also directly helps the "why is this slow" complaint:
            // mutuals are typically a much smaller set than everyone
            // followed, so there's simply far less to fan out over. See
            // BlueskyRepository.getPopfeedReviews for the other half of the
            // speed fix (parallel + cached collection-name lookup, replacing
            // what used to be up to 3 sequential requests per account).
            //
            // Second bug fix (this session): even after the fix above,
            // Latest Reviews was still reported as never showing up.
            // getOrDefault(emptyList()) on a failed fetch makes "the fetch
            // genuinely failed" indistinguishable from "no accounts to
            // check" — and this function used to set friendsReviewsLoaded =
            // true unconditionally either way, permanently caching a
            // transient failure as "checked, nothing to show" for the rest
            // of the app's process lifetime (no retry short of a full app
            // restart). Since this runs the instant the Hub's AT Protocol
            // page first composes — often just after cold start, competing
            // with several other concurrent app-launch network calls — a
            // transient failure here was actually quite likely. Now `loaded`
            // is only set once dmConversations has genuinely finished
            // loading (isNotEmpty, or the load attempt completed), so a
            // failed/still-pending attempt is retried the next time the Hub
            // page composes instead of sticking forever.
            ensureDmConversationsLoadedSuspend(silent = true)
            val accounts = _dmConversations.value.map { it.member }
            _friendsReviews.value = if (accounts.isEmpty()) emptyList()
                else runCatching { bskyRepo.getFriendsPopfeedReviews(accounts) }.getOrDefault(emptyList())
            // Same "don't cache a failure as done" principle as before: only
            // latch `loaded` once there's actually a non-empty mutuals list
            // to have fetched reviews for. If it's still empty (which is
            // ambiguous between "genuinely zero mutuals" and "the fetch
            // hasn't succeeded yet" — dmConversations has no separate loaded
            // flag of its own either), leaving this retriable next Hub visit
            // is cheap (ensureDmConversationsLoadedSuspend fast-paths) and
            // matches how the Mutuals row itself already behaves.
            if (accounts.isNotEmpty()) friendsReviewsLoaded = true
            _friendsReviewsLoading.value = false
        }
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

    fun loadLiveFriendsIfNeeded() {
        if (liveFriendsLoaded || _liveFriendsLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _liveFriendsLoading.value = true
            // Bug fix + roadmap: same dmConversations-not-loaded-yet issue as
            // Reviews above, and broadened to everyone the user follows
            // rather than just DM contacts, per feedback.
            // Same "don't cache a transient failure as done" fix as
            // loadFriendsReviewsIfNeeded above — only mark loaded on an
            // actually-successful follows fetch.
            val followsResult = bskyRepo.getAllFollows(bskyToken, _bskyDid.value)
            if (followsResult.isSuccess) {
                val dids = followsResult.getOrDefault(emptyList()).map { it.did }.toSet()
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
    fun refreshFriendsReviews() { friendsReviewsLoaded = false; loadFriendsReviewsIfNeeded() }
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
        if (blueskyLiveNowLoaded || _blueskyLiveNowLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _blueskyLiveNowLoading.value = true
            // Same "don't cache a transient failure as done" principle as
            // Reviews/Streamplace above — only latch loaded on genuine success.
            val followsResult = bskyRepo.getAllFollows(bskyToken, _bskyDid.value)
            if (followsResult.isSuccess) {
                val dids = followsResult.getOrDefault(emptyList()).map { it.did }
                _blueskyLiveNow.value = if (dids.isEmpty()) emptyList()
                    else bskyRepo.getLiveNowStreams(bskyToken, dids).getOrDefault(emptyList())
                blueskyLiveNowLoaded = true
            }
            _blueskyLiveNowLoading.value = false
        }
    }
    fun refreshBlueskyLiveNow() { blueskyLiveNowLoaded = false; loadBlueskyLiveNowIfNeeded() }

    // Which Bluesky Live Now stream (if any) is currently expanded into the
    // inline embed player overlay — see LiveNowPlayerOverlay in
    // SettingsSheet.kt. Only one at a time, same pattern as sendPopupTarget.
    private val _playingLiveNow = MutableStateFlow<BlueskyLiveNowStream?>(null)
    val playingLiveNow: StateFlow<BlueskyLiveNowStream?> = _playingLiveNow
    fun openLiveNowPlayer(stream: BlueskyLiveNowStream) { _playingLiveNow.value = stream }
    fun closeLiveNowPlayer() { _playingLiveNow.value = null }

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

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
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
        loadHistoryFromPrefs()
        trackHistoryAutomatically()
        viewModelScope.launch {
            val accessJwt    = prefs.bskyAccessJwt.first()
            val refreshJwt   = prefs.bskyRefreshJwt.first()
            val did          = prefs.bskyDid.first()
            val handle       = prefs.bskyHandle.first()
            val e621User     = prefs.e621Username.first()
            val e621Key      = prefs.e621ApiKey.first()
            val lastMode     = prefs.lastMode.first()
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

            // Restore last mode and go to feed if logged in for that mode
            if (lastMode == "E621" && _e621LoggedIn.value) {
                _appMode.value = AppMode.E621
                _screenState.value = ScreenState.FEED
                loadE621Posts()
            } else if (_bskyLoggedIn.value) {
                _appMode.value = AppMode.BLUESKY
                _screenState.value = ScreenState.FEED
                loadFeed()
                loadAvailableFeeds()
                prefetchUserLists()   // preload so list picker opens instantly
                startHubBackgroundWarmup() // item 6/this session: Mutuals/Reviews/Livestreams, see its own comment
                preloadFriendsFeed()  // item 7: warm the From Friends feed in the background too
                loadSelfProfile()     // Settings Update: warm the Profile button's avatar/banner preview
            }
            // else: stay on SETTINGS
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

    fun loadFeed(reset: Boolean = true) {
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
                // There's no default "Home" feed anymore — if nothing is selected yet
                // (and we're not inside an author/likes overlay), fall back to the
                // user's first saved feed so the app never lands on an empty state.
                if (_selectedFeedUri.value == null && _authorFeedState.value == null && feeds.isNotEmpty()) {
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
    fun openProfile(author: AuthorInfo, initialTab: ProfileTab = ProfileTab.MEDIA, review: PopfeedReview? = null) {
        if (!_bskyLoggedIn.value) return
        // Item 17: don't clobber a profile that's already open (visible or
        // hidden behind a post pager) — chain onto it via `parent` so
        // closeProfile() can unwind back through it instead of losing it.
        val parent = _profileOverlay.value
        _profileOverlay.value = ProfileOverlayState(author = author, selectedTab = initialTab, parent = parent, openReview = review)

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
     *  was left instead of falling through to the generic grid. */
    fun pinchInFromPost() {
        val overlay = _profileOverlay.value
        if (overlay != null && overlay.hidden) {
            _profileOverlay.value = overlay.copy(hidden = false)
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
        viewModelScope.launch { prefs.setLastFeedUri(uri) }
        loadFeed(reset = true)
    }

    fun loadE621Posts(reset: Boolean = true) {
        if (!_e621LoggedIn.value) return
        viewModelScope.launch(Dispatchers.IO) {
            if (reset) { e621Page = 1; _isLoading.value = true; _currentIndex.value = 0 }
            val result = if (e621ShowingFavorites)
                e621Repo.getFavorites(e621Username, e621ApiKey, e621Page)
            else
                e621Repo.searchPosts(e621Username, e621ApiKey, _e621SearchTags.value, e621Page)
            result.onSuccess { items ->
                val followed = _e621FollowedArtists.value
                val stamped = items.map { it.copy(author = it.author.copy(isFollowing = followed.contains(it.author.handle))) }
                _mediaItems.value = if (reset) stamped else _mediaItems.value + stamped
                e621Page++
            }.onFailure { _errorMessage.value = it.message }
            _isLoading.value = false
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
            retryWithBackoff(isDone = { friendsReviewsLoaded || !_bskyLoggedIn.value }) {
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

    private suspend fun loadDmConversationsBlocking(silent: Boolean = false) {
        _dmConversationsLoading.value = true
        bskyRepo.loadDmRecipients(bskyToken, _bskyDid.value)
            .onSuccess { _dmConversations.value = it }
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
                    loadAvailableFeeds()
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
        if (screen == ScreenState.COMMENTS) loadComments()
        // Item 3: the Settings "Profile" button was only ever populated by the
        // one loadSelfProfile() fired at app startup/login. If that request
        // hadn't finished (or had failed) by the time the person actually
        // opened Settings, the button was stuck grey for the rest of the
        // session with nothing to retry it. Re-check every time Settings
        // opens so a missed/failed load gets a fresh attempt.
        if (screen == ScreenState.SETTINGS && _bskyLoggedIn.value && _selfProfile.value == null) loadSelfProfile()
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
