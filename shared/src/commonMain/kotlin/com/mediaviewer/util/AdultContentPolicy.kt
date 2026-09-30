package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.model.MediaItem
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformKind
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.currentPlatform
import com.mediaviewer.platform.sharedPreferences

/**
 * iOS only: adult content follows the Bluesky account's own "Enable adult
 * content" setting, which can only be turned on on the Bluesky website
 * (bsky.app → Settings → Moderation) — the same rule the official Bluesky
 * app follows under the App Store's guidelines. Until it's on, posts
 * labeled porn / sexual / nudity are left out everywhere.
 *
 * Android is unaffected: everything shows, with the optional "I Hate Fun"
 * blur, exactly as before.
 */
object AdultContentPolicy {
    private const val PREFS = "content_policy"
    private const val KEY_ALLOWED = "bsky_adult_content_enabled"

    /** Whether this platform follows the account setting at all. */
    val appliesHere: Boolean get() = currentPlatform == PlatformKind.IOS

    /** The account's setting, as last read from Bluesky (remembered, so the
     *  first feed after launch already follows it). Compose state. */
    var accountAllowsAdult by mutableStateOf(false)
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences(PREFS)
        prefs = p
        accountAllowsAdult = p.getBoolean(KEY_ALLOWED, false)
    }

    /** Called with the value read from the account's Bluesky preferences. */
    fun update(allowed: Boolean) {
        if (allowed == accountAllowsAdult) return
        accountAllowsAdult = allowed
        prefs?.edit()?.putBoolean(KEY_ALLOWED, allowed)?.apply()
    }

    /** True when [item] must not be shown here. */
    fun hides(item: MediaItem): Boolean = appliesHere && !accountAllowsAdult && item.isNsfwLabeled
}
