package com.mediaviewer.repository

import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.FriendLeafletBlog
import com.mediaviewer.model.FriendPopfeedReview
import com.mediaviewer.model.LeafletBlog
import com.mediaviewer.model.PopfeedReview
import com.mediaviewer.network.JetstreamClient
import com.mediaviewer.network.JetstreamCommitEvent
import com.mediaviewer.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Firehose-backed indexer for the Hub's "Latest Reviews" and "Blogs"
 * sections (and, in passing, this app's live follow-graph state) — see
 * the attached AT Protocol architecture note this implements.
 *
 * The problem this replaces: BlueskyRepository.getFriendsPopfeedReviews /
 * getLeafletBlogs used to fan out one com.atproto.repo.listRecords request
 * PER FOLLOWED ACCOUNT, every single time the Hub was opened — an N+1
 * pattern that gets slow fast once "friends" means "everyone you follow"
 * rather than just DM contacts.
 *
 * The fix: that fan-out still happens (Popfeed reviews and Leaflet blogs are
 * arbitrary third-party lexicons with no bulk "check these 50 accounts"
 * endpoint, so a per-account request is unavoidable), but:
 *  1. Only when actually needed — the result is persisted to disk (see
 *     PreferencesManager.HUB_REVIEWS_CACHE_JSON) and a fresh-enough cache
 *     (see CACHE_FRESH_WINDOW_MS) is read instantly on startup instead of
 *     re-fetching, so restarting the app doesn't repeat the fan-out at all
 *     most of the time — this was a real bug: every restart used to trigger
 *     a brand new full fan-out with no persistence, and doing that
 *     repeatedly in a short window is exactly what was tripping Bluesky's
 *     rate limiting (429s) badly enough to break the main feed too.
 *  2. Bounded and paced (HYDRATE_CONCURRENCY, HYDRATE_STAGGER_MS) when it
 *     does run, instead of firing every followed account's requests all at
 *     once — a few hundred simultaneous requests in one burst is itself
 *     enough to trip rate limiting even on a single run.
 *  3. After hydration, updates come from **Jetstream** — Bluesky's public,
 *     filtered JSON mirror of the AT Protocol firehose — which pushes new/
 *     edited/deleted records for the collections this cares about the
 *     moment they're written, with no polling and no further per-account
 *     requests at all.
 *
 * Not backed by Room/SQLite (per the architecture note's suggestion) — the
 * in-memory maps below are still the live working set; PreferencesManager's
 * JSON blob is purely a startup-time snapshot, not a queryable store. If the
 * Hub ever needs more than "instant snapshot on launch", Room is the
 * natural next step.
 */
class FirehoseIndexer(private val bskyRepo: BlueskyRepository, private val prefs: PreferencesManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = com.google.gson.Gson()

    private val reviewCollections = BlueskyRepository.REVIEW_COLLECTIONS
    private val blogCollections = BlueskyRepository.LEAFLET_COLLECTIONS
    private val followCollection = "app.bsky.graph.follow"

    // did -> AuthorInfo, for every account currently in scope (the user's
    // full follow list, kept live via follow/unfollow commits below).
    private val followedAuthors = ConcurrentHashMap<String, AuthorInfo>()

    private val reviewsByDid = ConcurrentHashMap<String, List<PopfeedReview>>()
    private val blogsByDid = ConcurrentHashMap<String, List<LeafletBlog>>()

    private val _friendReviews = MutableStateFlow<List<FriendPopfeedReview>>(emptyList())
    val friendReviews: StateFlow<List<FriendPopfeedReview>> = _friendReviews

    private val _friendBlogs = MutableStateFlow<List<FriendLeafletBlog>>(emptyList())
    val friendBlogs: StateFlow<List<FriendLeafletBlog>> = _friendBlogs

    // Live-updated "everyone I follow" DID set — seeded once from
    // getAllFollows, then kept in sync purely from the user's own
    // app.bsky.graph.follow commits (no re-fetch of the whole list needed
    // after that). Other Hub sections (Live, VODs) can read this too instead
    // of calling getAllFollows themselves on every visit.
    private val _followedDids = MutableStateFlow<Set<String>>(emptySet())
    val followedDids: StateFlow<Set<String>> = _followedDids

    private var jetstream: JetstreamClient? = null
    // Tracked explicitly (rather than just letting stop()/next start() leak
    // them into `scope` forever) so stop() can actually cancel these two
    // background loops instead of leaving a stale cursor-persist loop
    // spinning forever, or an events collector idling on a dead socket.
    private var eventsJob: kotlinx.coroutines.Job? = null
    private var cursorPersistJob: kotlinx.coroutines.Job? = null
    private var myDid: String = ""

    /** Hydration (from cache if fresh, network fan-out otherwise) + firehose
     *  subscribe for a freshly-logged-in session. Safe to call again (e.g.
     *  re-login as a different account) — tears down any previous
     *  connection/cache first. */
    fun start(myDid: String, initialFollows: List<AuthorInfo>) {
        this.myDid = myDid
        jetstream?.stop()
        eventsJob?.cancel()
        cursorPersistJob?.cancel()
        followedAuthors.clear(); reviewsByDid.clear(); blogsByDid.clear()
        initialFollows.forEach { followedAuthors[it.did] = it }
        _followedDids.value = followedAuthors.keys.toSet()
        _friendReviews.value = emptyList()
        _friendBlogs.value = emptyList()

        scope.launch {
            // Always show the last snapshot instantly, regardless of its
            // age — never leave the Hub blank while catch-up is in flight.
            loadFromCache(followedAuthors.keys)

            // The real fix for "cached data might miss new posts" (see this
            // class's doc comment): a persisted Jetstream cursor lets the
            // NEW connection below replay everything missed since last
            // seen — one streaming connection doing server-side catch-up,
            // not a REST re-fetch — so a fresh full hydration fan-out is
            // only needed when there's truly no usable cursor to resume
            // from (first-ever login, or a cursor old enough that Jetstream
            // itself likely can't/won't replay that far back).
            val savedCursor = runCatching { prefs.jetstreamCursor.first() }.getOrNull()
            val cursorAgeMs = savedCursor?.let { System.currentTimeMillis() - it / 1000 }
            val cursorUsable = savedCursor != null && cursorAgeMs != null && cursorAgeMs in 0 until JETSTREAM_REPLAY_WINDOW_MS
            if (!cursorUsable) {
                hydrate(initialFollows)
                persistCache()
            }

            val wanted = reviewCollections + blogCollections + followCollection
            val js = JetstreamClient(wanted, initialCursor = savedCursor.takeIf { cursorUsable })
            jetstream = js
            eventsJob = js.events.onEach { handleEvent(it) }.launchIn(scope)
            js.start()
            cursorPersistJob = startCursorPersistLoop(js)
        }
    }

    /** Persists Jetstream's replay cursor every few seconds while connected
     *  — not just at some clean shutdown, since Android can kill the
     *  process at any time without warning, and a stale-but-present cursor
     *  is exactly what makes the next launch's catch-up work. Returns the
     *  Job so stop()/a subsequent start() can cancel it explicitly, rather
     *  than leaving it spinning forever on a dead JetstreamClient. */
    private fun startCursorPersistLoop(js: JetstreamClient): kotlinx.coroutines.Job {
        return scope.launch {
            var lastPersisted: Long? = null
            while (true) {
                delay(CURSOR_PERSIST_INTERVAL_MS)
                val current = js.currentCursor()
                if (current != null && current != lastPersisted) {
                    runCatching { prefs.setJetstreamCursor(current) }
                    lastPersisted = current
                }
            }
        }
    }

    /** Stops the Jetstream connection and clears every cache — called on
     *  logout so a subsequent login (same process, same ViewModel/indexer
     *  instance) starts from a clean slate instead of momentarily showing
     *  the previous account's cached reviews/blogs/follow-graph. Doesn't
     *  touch the persisted disk cache (see PreferencesManager) — that's
     *  fine, it's keyed by nothing account-specific today, so the next
     *  login's loadFromCache call just filters it down to whatever of it
     *  still matches that account's current follows. */
    fun stop() {
        jetstream?.stop()
        jetstream = null
        eventsJob?.cancel(); eventsJob = null
        cursorPersistJob?.cancel(); cursorPersistJob = null
        followedAuthors.clear(); reviewsByDid.clear(); blogsByDid.clear()
        _followedDids.value = emptySet()
        _friendReviews.value = emptyList()
        _friendBlogs.value = emptyList()
        myDid = ""
    }

    /** See JetstreamClient.reconnectIfNeeded's doc comment — called from
     *  MainViewModel.onAppForegrounded, itself called from MainActivity.
     *  onResume. No-op if the indexer was never started (not logged in
     *  yet) or the socket's already connected. */
    fun onAppForegrounded() {
        jetstream?.reconnectIfNeeded()
    }

    /** Reads the persisted snapshot (if any) and publishes it immediately —
     *  this is what makes a restart show the Hub's Reviews/Blogs instantly
     *  instead of blank-until-fetched. Returns true only if that snapshot
     *  is recent enough (CACHE_FRESH_WINDOW_MS) that the caller should skip
     *  a full re-hydration entirely; a stale (or missing) cache still gets
     *  published if present — better to show something slightly old for a
     *  moment than nothing — but the caller will go on to refresh it. */
    /** Reads the persisted snapshot (if any) and publishes it immediately —
     *  this is what makes a restart show the Hub's Reviews/Blogs instantly
     *  instead of blank-until-fetched, regardless of how stale the snapshot
     *  itself is. Actual freshness/completeness comes from the Jetstream
     *  cursor catch-up in start() above, not from this — this is purely
     *  "show something now" while that catch-up (or, rarely, a full
     *  hydration) runs. */
    private suspend fun loadFromCache(followedSet: Set<String>) {
        try {
            val reviewsJson = prefs.hubReviewsCacheJson.first()
            val blogsJson = prefs.hubBlogsCacheJson.first()
            val reviewType = object : com.google.gson.reflect.TypeToken<List<FriendPopfeedReview>>() {}.type
            val blogType = object : com.google.gson.reflect.TypeToken<List<FriendLeafletBlog>>() {}.type
            val cachedReviews: List<FriendPopfeedReview> = gson.fromJson(reviewsJson, reviewType) ?: emptyList()
            val cachedBlogs: List<FriendLeafletBlog> = gson.fromJson(blogsJson, blogType) ?: emptyList()

            // Only keep entries from accounts still actually followed — an
            // unfollow between sessions shouldn't leave a stale card around
            // just because it happened to be in the last snapshot.
            cachedReviews.filter { it.author.did in followedSet }.groupBy { it.author.did }
                .forEach { (did, list) -> reviewsByDid[did] = list.map { it.review } }
            cachedBlogs.filter { it.author.did in followedSet }.groupBy { it.author.did }
                .forEach { (did, list) -> blogsByDid[did] = list.map { it.blog } }
            if (cachedReviews.isNotEmpty() || cachedBlogs.isNotEmpty()) publish()
        } catch (e: Exception) {
            // No usable snapshot yet (first-ever login, or corrupt/older
            // schema) — fine, start() falls back to a full hydration in
            // that case anyway.
        }
    }

    private suspend fun persistCache() {
        runCatching {
            val reviewType = object : com.google.gson.reflect.TypeToken<List<FriendPopfeedReview>>() {}.type
            val blogType = object : com.google.gson.reflect.TypeToken<List<FriendLeafletBlog>>() {}.type
            prefs.setHubCache(
                reviewsJson = gson.toJson(_friendReviews.value, reviewType),
                blogsJson = gson.toJson(_friendBlogs.value, blogType),
                hydratedAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun hydrate(accounts: List<AuthorInfo>) = coroutineScope {
        // Bounded + paced (see this class's doc comment) — caps how many
        // accounts are actually in flight at once instead of firing every
        // one of them simultaneously, and staggers when each one starts so
        // the whole fan-out spreads out over time rather than landing on
        // the server as one instantaneous spike.
        val gate = Semaphore(HYDRATE_CONCURRENCY)
        accounts.distinctBy { it.did }.mapIndexed { i, account ->
            async {
                delay((i % HYDRATE_CONCURRENCY) * HYDRATE_STAGGER_MS)
                gate.withPermit {
                    runCatching { bskyRepo.getPopfeedReviews(account.did) }.getOrDefault(emptyList())
                        .takeIf { it.isNotEmpty() }?.let { reviewsByDid[account.did] = it }
                    runCatching { bskyRepo.getLeafletBlogs(account.did) }.getOrDefault(emptyList())
                        .takeIf { it.isNotEmpty() }?.let { blogsByDid[account.did] = it }
                }
            }
        }.awaitAll()
        publish()
    }

    private fun handleEvent(event: JetstreamCommitEvent) {
        if (event.collection == followCollection) {
            handleFollowEvent(event)
            return
        }
        if (event.did !in _followedDids.value) return

        val uri = "at://${event.did}/${event.collection}/${event.rkey}"
        when {
            event.collection in reviewCollections -> {
                scope.launch {
                    when (event.operation) {
                        "create", "update" -> {
                            val obj = event.record ?: return@launch
                            val review = runCatching { bskyRepo.parsePopfeedReviewRecord(event.did, uri, obj) }.getOrNull() ?: return@launch
                            reviewsByDid[event.did] = (reviewsByDid[event.did].orEmpty().filterNot { it.uri == uri } + review)
                                .sortedByDescending { it.createdAt }
                        }
                        "delete" -> reviewsByDid[event.did] = reviewsByDid[event.did].orEmpty().filterNot { it.uri == uri }
                    }
                    publish()
                    persistCache()
                }
            }
            event.collection in blogCollections -> {
                scope.launch {
                    when (event.operation) {
                        "create", "update" -> {
                            val obj = event.record ?: return@launch
                            val blog = runCatching { bskyRepo.parseLeafletBlogRecord(event.did, uri, obj) }.getOrNull() ?: return@launch
                            blogsByDid[event.did] = (blogsByDid[event.did].orEmpty().filterNot { it.uri == uri } + blog)
                                .sortedByDescending { it.createdAt }
                        }
                        "delete" -> blogsByDid[event.did] = blogsByDid[event.did].orEmpty().filterNot { it.uri == uri }
                    }
                    publish()
                    persistCache()
                }
            }
        }
    }

    /** Keeps the follow graph current from the user's own follow/unfollow
     *  commits, without ever re-calling getFollows. New follows start out
     *  with a placeholder AuthorInfo (handle/display name = their DID) since
     *  Jetstream's follow record carries no profile info — good enough for
     *  the follow set itself to be accurate immediately; a full profile
     *  fetch would need a separate getProfile call this deliberately skips
     *  to keep this handler cheap. Once that account posts a review/blog its
     *  entry is upgraded the same way any other cache entry is. */
    private fun handleFollowEvent(event: JetstreamCommitEvent) {
        if (event.did != myDid) return
        val subject = event.record?.get("subject")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        when (event.operation) {
            "create" -> {
                followedAuthors.putIfAbsent(subject, AuthorInfo(did = subject, handle = subject, displayName = subject, avatarUrl = null))
                _followedDids.value = _followedDids.value + subject
            }
            "delete" -> {
                followedAuthors.remove(subject)
                reviewsByDid.remove(subject)
                blogsByDid.remove(subject)
                _followedDids.value = _followedDids.value - subject
                publish()
            }
        }
    }

    private fun publish() {
        _friendReviews.value = reviewsByDid.entries.flatMap { (did, reviews) ->
            val author = followedAuthors[did] ?: return@flatMap emptyList<FriendPopfeedReview>()
            reviews.map { FriendPopfeedReview(author, it) }
        }.sortedByDescending { it.review.createdAt }

        _friendBlogs.value = blogsByDid.entries.flatMap { (did, blogs) ->
            val author = followedAuthors[did] ?: return@flatMap emptyList<FriendLeafletBlog>()
            blogs.map { FriendLeafletBlog(author, it) }
        }.sortedByDescending { it.blog.createdAt }
    }

    companion object {
        // How many followed accounts' Popfeed/Leaflet fetches run at once
        // during a hydration — deliberately small. Each "account slot" can
        // itself briefly issue more than one request (getPopfeedReviews
        // probes multiple candidate collection names the first time it
        // doesn't yet know which one a given Popfeed deployment uses), so
        // the real peak concurrent request count is somewhat higher than
        // this number, not exactly equal to it.
        private const val HYDRATE_CONCURRENCY = 4
        // Extra stagger so even the accounts allowed to start "at once" by
        // the semaphore don't all begin in the very same instant.
        private const val HYDRATE_STAGGER_MS = 120L
        // Jetstream's own backlog retention is finite (varies by deployment,
        // commonly on the order of 1–3 days) — a cursor older than this is
        // treated as "too old to trust a replay from", falling back to a
        // full hydration instead of risking Jetstream silently skipping
        // ahead past what it can actually replay. Deliberately conservative
        // (well under typical retention) rather than cutting it close.
        private val JETSTREAM_REPLAY_WINDOW_MS = TimeUnit.HOURS.toMillis(20)
        // How often the live cursor is written to disk while connected.
        private val CURSOR_PERSIST_INTERVAL_MS = TimeUnit.SECONDS.toMillis(8)
    }
}
