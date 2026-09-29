package com.mediaviewer.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Add To's ordering: when an account was last added to each list / starter
 * pack, kept on-device only. Lists you've added someone to most recently sort
 * to the top of the Add To popup.
 */
object ListRecency {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("list_recency", Context.MODE_PRIVATE)
    }

    /** Milliseconds of the last add to [listUri], or 0 if never. */
    fun lastAdded(listUri: String): Long = prefs?.getLong(listUri, 0L) ?: 0L

    fun noteAdded(listUri: String, at: Long = System.currentTimeMillis()) {
        if (listUri.isBlank()) return
        prefs?.edit()?.putLong(listUri, at)?.apply()
    }
}
