package com.mediaviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.BskyFeedInfo
import com.mediaviewer.model.MediaItem

/** Launchpad page two. */
enum class LaunchApp(val label: String) {
    CALENDAR("Calendar"), NOTES("Notes"), CALCULATOR("Calculator"), TIMER("Timer")
}

/** Answers to one poll, as counted from its replies. */
data class PollTally(
    /** Votes per letter ("A" → 12). */
    val counts: Map<String, Int> = emptyMap(),
    /** The letter the signed-in account voted, if any. */
    val myVote: String? = null
) {
    val total: Int get() = counts.values.sum()
}

/** One floating web page (Search → Web Browser → Popout). */
class BrowserPopout(val id: Long, val state: BrowserState) {
    var x by mutableStateOf(0.06f)
    var y by mutableStateOf(0.16f)
    var w by mutableStateOf(0.88f)
    var h by mutableStateOf(0.42f)
    /** Stacking order: the window touched last is in front. */
    var z by mutableStateOf(0f)
}

/**
 * The supporter features' popups and pages, and the few actions they need
 * from the ViewModel. Plain Compose state that any screen can set — the
 * popups themselves are drawn once, by [LocalOverlayHost] in AppRoot — so
 * none of this has to be threaded through every screen's parameters.
 */
object LocalOverlays {
    /** A post whose "Edited" status was tapped: its version history. */
    var editHistoryFor by mutableStateOf<MediaItem?>(null)
    /** A just-saved post being filed into a bookmark folder. */
    var bookmarkFolderFor by mutableStateOf<MediaItem?>(null)
    /** The profile whose note is being written. */
    var profileNoteFor by mutableStateOf<AuthorInfo?>(null)
    /** Hub → the "+" after the feeds: the Feed Builder (null = closed;
     *  blank id = a new feed). */
    var feedBuilder by mutableStateOf<com.mediaviewer.util.LocalFeed?>(null)
    /** Launchpad page two: the open app. */
    var launchApp by mutableStateOf<LaunchApp?>(null)
    /** A feed dragged onto the Hub's DMs button, waiting to be shared. */
    var shareFeed by mutableStateOf<BskyFeedInfo?>(null)

    /** A DM chat held down: the pin / unpin confirmation. */
    var pinDmFor by mutableStateOf<com.mediaviewer.model.DmConversation?>(null)

    /** One of the popups is up: the app behind it blurs. */
    val popupOpen: Boolean
        get() = editHistoryFor != null || bookmarkFolderFor != null || profileNoteFor != null || feedBuilder != null || pinDmFor != null

    /** Floating web pages, drawn over everything. */
    val popouts = mutableStateListOf<BrowserPopout>()
    private var nextPopoutId = 1L
    private var topZ = 0f

    fun bringToFront(p: BrowserPopout) { topZ += 1f; p.z = topZ }

    fun popOut(url: String) {
        val n = popouts.size
        val state = BrowserState(url)
        popouts.add(BrowserPopout(nextPopoutId++, state).also {
            // Each new window opens a little lower so they don't stack exactly.
            it.y = (0.14f + 0.05f * (n % 5)).coerceAtMost(0.5f)
            it.x = 0.06f + 0.02f * (n % 3)
            bringToFront(it)
        })
    }

    fun closePopout(p: BrowserPopout) {
        popouts.remove(p)
        p.state.dispose()
    }

    /** Closes the popups (not the floating web pages — those stay until
     *  their own X is tapped). */
    fun closeAll() {
        editHistoryFor = null
        bookmarkFolderFor = null
        profileNoteFor = null
        feedBuilder = null
        pinDmFor = null
        launchApp = null
        shareFeed = null
    }

    // ── Actions supplied by AppRoot (ViewModel calls) ──
    var onEditCurrentPost: (() -> Unit)? = null
    var pollTally: (suspend (postUri: String) -> PollTally?)? = null
    var pollVote: ((item: MediaItem, letter: String, onDone: (Boolean) -> Unit) -> Unit)? = null
    /** Re-counts a chat's streak from its history. */
    var scanDmStreak: ((convoId: String, onDone: () -> Unit) -> Unit)? = null
    /** A feed/list link in a DM → its card. */
    var resolveFeedCard: (suspend (actor: String, kind: String, rkey: String) -> com.mediaviewer.model.ProfileListEntry?)? = null
    var openSharedFeed: ((com.mediaviewer.model.ProfileListEntry) -> Unit)? = null
    var addSharedFeed: ((com.mediaviewer.model.ProfileListEntry) -> Unit)? = null
    /** Feeds already in your feeds list (a shared feed shows "Added"). */
    var savedFeedUris by mutableStateOf<Set<String>>(emptySet())
    /** Saved Posts → the bookmark folder being shown (null = all). */
    var bookmarkFolderId by mutableStateOf<String?>(null)
    var onShowBookmarkFolder: ((String?) -> Unit)? = null
}
