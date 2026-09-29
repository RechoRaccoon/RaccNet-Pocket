package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences

/**
 * Add To's ordering: when an account was last added to each list / starter
 * pack, kept on-device only. Lists you've added someone to most recently sort
 * to the top of the Add To popup.
 */
object ListRecency {
    private var prefs: SharedPreferences? = null

    fun init(context: PlatformContext) {
        if (prefs == null) prefs = context.sharedPreferences("list_recency")
    }

    /** Milliseconds of the last add to [listUri], or 0 if never. */
    fun lastAdded(listUri: String): Long = prefs?.getLong(listUri, 0L) ?: 0L

    /** Add To's last-used tab, kept here too (read synchronously at launch)
     *  so the popup always reopens on it, even after a restart. */
    var lastTab: String?
        get() = prefs?.getString("__last_tab", null)
        set(value) { prefs?.edit()?.putString("__last_tab", value)?.apply() }

    fun noteAdded(listUri: String, at: Long = com.mediaviewer.platform.currentTimeMillis()) {
        if (listUri.isBlank()) return
        prefs?.edit()?.putLong(listUri, at)?.apply()
    }
}
