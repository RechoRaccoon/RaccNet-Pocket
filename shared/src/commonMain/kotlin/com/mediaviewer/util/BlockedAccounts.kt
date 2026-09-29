package com.mediaviewer.util

import com.mediaviewer.model.BskyActorViewer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.mediaviewer.platform.ConcurrentHashMap

/**
 * App-wide record of every account that should never appear anywhere in
 * Stellar: accounts the signed-in user is blocking, and accounts that are
 * blocking the signed-in user.
 *
 * - "Blocking" is seeded from app.bsky.graph.getBlocks on sign-in (see
 *   MainViewModel.loadBlockedAccounts) and kept current as the user blocks
 *   or unblocks from inside the app.
 * - "Blocked by" can't be listed by the API, so it's learned from every
 *   profile view the app sees along the way (a post's author, a chat
 *   member, a search result …) — each one carries a `viewer.blockedBy` flag.
 *
 * Feeds, search, DMs, the Inbox and the Hub all check [isHidden] (directly,
 * or by re-filtering whenever [version] ticks).
 */
object BlockedAccounts {
    /** did -> block record URI ("" when the URI isn't known). */
    private val blocking = ConcurrentHashMap<String, String>()
    private val blockedBy: MutableSet<String> = com.mediaviewer.platform.concurrentSetOf()

    private val _version = MutableStateFlow(0)
    /** Ticks every time either set changes, so filtered flows can re-run. */
    val version: StateFlow<Int> = _version

    private fun bump() { _version.value = _version.value + 1 }

    /** Accounts the user is blocking — never shown anywhere. (Accounts that
     *  block the user still show up, flagged with a "This user has you
     *  blocked" status — see [isBlockedBy].) */
    fun isHidden(did: String?): Boolean =
        !did.isNullOrBlank() && blocking.containsKey(did)

    /** This account blocks the signed-in user. */
    fun isBlockedBy(did: String?): Boolean = !did.isNullOrBlank() && blockedBy.contains(did)

    fun isBlocking(did: String?): Boolean = !did.isNullOrBlank() && blocking.containsKey(did)

    fun blockUriFor(did: String): String? = blocking[did]?.takeIf { it.isNotBlank() }

    /** Records whatever a profile view's viewer state says about this account. */
    fun noteViewer(did: String?, viewer: BskyActorViewer?) {
        if (did.isNullOrBlank() || viewer == null) return
        var changed = false
        val blockUri = viewer.blocking
        if (!blockUri.isNullOrBlank()) {
            if (blocking.put(did, blockUri) != blockUri) changed = true
        }
        if (viewer.blockedBy == true) {
            if (blockedBy.add(did)) changed = true
        } else if (viewer.blockedBy == false && blockedBy.remove(did)) {
            changed = true
        }
        if (changed) bump()
    }

    /** Replaces the "blocking" set with the server's full list. */
    fun setBlocking(entries: Map<String, String>) {
        blocking.clear()
        blocking.putAll(entries)
        bump()
    }

    fun addBlocking(did: String, blockUri: String?) {
        if (did.isBlank()) return
        blocking[did] = blockUri.orEmpty()
        bump()
    }

    fun removeBlocking(did: String) {
        if (blocking.remove(did) != null) bump()
    }

    /** Signing out / switching accounts. */
    fun clear() {
        blocking.clear()
        blockedBy.clear()
        bump()
    }
}
