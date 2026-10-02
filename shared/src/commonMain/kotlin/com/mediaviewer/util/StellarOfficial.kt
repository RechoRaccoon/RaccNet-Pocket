package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences

/** Stellar's own accounts, feeds and lists on Bluesky. */
object StellarOfficial {
    /** @stellarsocial.bsky.social */
    const val STELLAR_DID = "did:plc:7wpz3mrznvkoqxndf2sqkmaa"
    const val STELLAR_HANDLE = "stellarsocial.bsky.social"
    const val RECHO_HANDLE = "rechoraccoon.bsky.social"

    /** "Stellar Supporters" — the list of everyone supporting Stellar
     *  (https://bsky.app/profile/did:plc:7wpz3mrznvkoqxndf2sqkmaa/lists/3mwtjrwphcq2j). */
    const val SUPPORTERS_LIST_URI = "at://did:plc:7wpz3mrznvkoqxndf2sqkmaa/app.bsky.graph.list/3mwtjrwphcq2j"

    /** "For You" by @spacecowboy17.bsky.social
     *  (https://bsky.app/profile/did:plc:3guzzweuqraryl3rdkimjamk/feed/for-you). */
    const val FOR_YOU_FEED_URI = "at://did:plc:3guzzweuqraryl3rdkimjamk/app.bsky.feed.generator/for-you"
    const val FOR_YOU_FEED_BY = "spacecowboy17.bsky.social"

    /** The "Supporter" label's color (#FF4FA1). */
    const val SUPPORTER_COLOR = 0xFFFF4FA1L

    /**
     * The Stellar Tutorial video: a bsky.app post link
     * (https://bsky.app/profile/<handle>/post/<id>) whose video is shown in
     * the welcome flow's second popup. Blank until the tutorial is finished —
     * the popup then shows a "Placeholder" video card instead.
     */
    const val TUTORIAL_POST_URL = ""

    /**
     * The "Stellar Tutorial" popup after the welcome popup. Off: "Continue"
     * goes straight to the Hub (the popup fades as the Hub comes back into
     * focus). Set to true to bring the tutorial popup back — all of its
     * code is still in place.
     */
    const val TUTORIAL_ENABLED = false

    /** True for a list (not feed generator) at:// URI. */
    fun isListUri(uri: String?): Boolean = uri != null && uri.contains("/app.bsky.graph.list/")
}

/**
 * Everyone on the Stellar Supporters list. Re-read from Bluesky every time
 * the app opens (see MainViewModel.refreshSupporters) and remembered
 * on-device in between, so supporter profiles are recognised straight away
 * — even offline. Readable anywhere as Compose state.
 */
object StellarSupporters {
    private var prefs: SharedPreferences? = null

    var dids by mutableStateOf<Set<String>>(emptySet())
        private set

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences("stellar_supporters")
        prefs = p
        dids = p.getString("dids", null)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    fun isSupporter(did: String?): Boolean = !did.isNullOrBlank() && did in dids

    fun update(newDids: Set<String>) {
        if (newDids == dids) return
        dids = newDids
        prefs?.edit()?.putString("dids", newDids.joinToString(","))?.apply()
    }
}

/**
 * First-run and "thanks for sticking around" bookkeeping, on-device only:
 * how many times Stellar has been opened, whether the Support Stellar popup
 * has had its turn, and which accounts have already seen the welcome popup.
 */
object Onboarding {
    /** The Support Stellar popup appears on the 10th open, the 25th, the
     *  50th, and every 25 opens after that. Returns the latest of those
     *  that [opens] has reached (0 before the first). */
    fun supportMilestone(opens: Int): Int = when {
        opens < 10 -> 0
        opens < 25 -> 10
        else -> opens / 25 * 25
    }

    private const val KEY_OPENS = "open_count"
    private const val KEY_SUPPORT_SHOWN = "support_popup_shown"
    private const val KEY_SUPPORT_MILESTONE = "support_popup_milestone"
    private const val KEY_SKIP_NEXT = "skip_next_open"
    private const val KEY_WELCOMED = "welcomed_dids"

    private var prefs: SharedPreferences? = null
    private var counted = false

    /** Times Stellar has been opened, this one included. */
    var openCount by mutableStateOf(0)
        private set

    /** True for the rest of this session once the Support popup is due. */
    var supportPopupDue by mutableStateOf(false)
        private set

    private var welcomed by mutableStateOf<Set<String>>(emptySet())

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences("stellar_onboarding")
        prefs = p
        welcomed = p.getStringSet(KEY_WELCOMED, null)?.toSet() ?: emptySet()
        var opens = p.getInt(KEY_OPENS, 0)
        if (!counted) {
            counted = true
            // An in-app restart (switching accounts) isn't a new "open".
            if (p.getBoolean(KEY_SKIP_NEXT, false)) {
                p.edit().putBoolean(KEY_SKIP_NEXT, false).apply()
            } else {
                opens += 1
                p.edit().putInt(KEY_OPENS, opens).apply()
            }
        }
        openCount = opens
        // (The earlier one-time flag counts as having seen the 10th-open one.)
        val seen = maxOf(p.getInt(KEY_SUPPORT_MILESTONE, 0), if (p.getBoolean(KEY_SUPPORT_SHOWN, false)) 10 else 0)
        supportPopupDue = supportMilestone(opens) > seen
    }

    /** Call right before the app restarts itself. */
    fun skipNextOpenCount() {
        prefs?.edit()?.putBoolean(KEY_SKIP_NEXT, true)?.commit()
    }

    fun markSupportPopupShown() {
        supportPopupDue = false
        prefs?.edit()?.putBoolean(KEY_SUPPORT_SHOWN, true)?.putInt(KEY_SUPPORT_MILESTONE, supportMilestone(openCount))?.apply()
    }

    /** Whether [did] still has to see the welcome popup. */
    fun needsWelcome(did: String): Boolean = did.isNotBlank() && did !in welcomed

    fun markWelcomed(did: String) {
        if (did.isBlank() || did in welcomed) return
        welcomed = welcomed + did
        prefs?.edit()?.putStringSet(KEY_WELCOMED, welcomed)?.apply()
    }

    /** Dev Tools: show the welcome popup again for [did]. */
    fun resetWelcome(did: String) {
        welcomed = welcomed - did
        prefs?.edit()?.putStringSet(KEY_WELCOMED, welcomed)?.apply()
    }
}
