package com.mediaviewer.repository

import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.FriendLeafletBlog
import com.mediaviewer.model.FriendPopfeedReview
import com.mediaviewer.model.LeafletBlog
import com.mediaviewer.model.PopfeedReview
import com.mediaviewer.network.JetstreamClient
import com.mediaviewer.network.JetstreamCommitEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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
 * The fix: that fan-out still happens, but only ONCE per session/login, as
 * a one-time hydration of the in-memory cache below. After that, updates
 * come from **Jetstream** — Bluesky's public, filtered JSON mirror of the
 * AT Protocol firehose — which pushes new/edited/deleted records for the
 * collections this cares about the moment they're written, with no polling
 * and no further per-account requests at all. Re-opening the Hub just reads
 * the already-current in-memory cache.
 *
 * Not backed by Room/SQLite (per the architecture note's suggestion) —
 * deliberately kept in-memory only, scoped to one logged-in session, since
 * this app has no other local-DB infrastructure and the cache is cheap to
 * rebuild via hydrate() on next login. If the Hub ever needs this to survive
 * process death, that's the natural next step.
 */
class FirehoseIndexer(private val bskyRepo: BlueskyRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    private var myDid: String = ""

    /** One-time hydration + firehose subscribe for a freshly-logged-in
     *  session. Safe to call again (e.g. re-login as a different account) —
     *  tears down any previous connection/cache first. */
    fun start(myDid: String, initialFollows: List<AuthorInfo>) {
        this.myDid = myDid
        jetstream?.stop()
        followedAuthors.clear(); reviewsByDid.clear(); blogsByDid.clear()
        initialFollows.forEach { followedAuthors[it.did] = it }
        _followedDids.value = followedAuthors.keys.toSet()
        _friendReviews.value = emptyList()
        _friendBlogs.value = emptyList()

        // Initial hydration (architecture note §3.3) — the one and only
        // per-account fan-out this indexer ever does.
        scope.launch { hydrate(initialFollows) }

        val wanted = reviewCollections + blogCollections + followCollection
        val js = JetstreamClient(wanted)
        jetstream = js
        js.events.onEach { handleEvent(it) }.launchIn(scope)
        js.start()
    }

    /** Stops the Jetstream connection and clears every cache — called on
     *  logout so a subsequent login (same process, same ViewModel/indexer
     *  instance) starts from a clean slate instead of momentarily showing
     *  the previous account's cached reviews/blogs/follow-graph. */
    fun stop() {
        jetstream?.stop()
        jetstream = null
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

    private suspend fun hydrate(accounts: List<AuthorInfo>) = coroutineScope {
        accounts.distinctBy { it.did }.map { account ->
            async {
                runCatching { bskyRepo.getPopfeedReviews(account.did) }.getOrDefault(emptyList())
                    .takeIf { it.isNotEmpty() }?.let { reviewsByDid[account.did] = it }
                runCatching { bskyRepo.getLeafletBlogs(account.did) }.getOrDefault(emptyList())
                    .takeIf { it.isNotEmpty() }?.let { blogsByDid[account.did] = it }
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
}
