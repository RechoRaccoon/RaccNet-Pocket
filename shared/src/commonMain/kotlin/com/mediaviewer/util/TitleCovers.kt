package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.concurrentSetOf
import com.mediaviewer.platform.sharedPreferences

/**
 * Remembers which image URLs are title covers (Popfeed review / backlog
 * posters and backdrops), so Settings → Dev Tools → "Clear Cached Title
 * Covers" can drop exactly those from the image cache without touching
 * everything else (post images, avatars, banners).
 */
object TitleCovers {
    private const val PREFS = "title_covers"
    private const val KEY = "urls"
    private const val MAX = 4000

    private var prefs: SharedPreferences? = null
    private val urls: MutableSet<String> = concurrentSetOf()

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences(PREFS)
        prefs = p
        p.getStringSet(KEY, null)?.let { urls.addAll(it) }
    }

    /** Notes [url] as a title cover (no-op for null/blank/known ones). */
    fun note(url: String?) {
        if (url.isNullOrBlank() || urls.size >= MAX || !urls.add(url)) return
        prefs?.edit()?.putStringSet(KEY, HashSet(urls))?.apply()
    }

    /** Removes every known title cover from the disk cache (and clears the
     *  in-memory image cache, which refills from disk). Returns how many
     *  covers were dropped. */
    fun clearCached(context: PlatformContext): Int {
        val all = urls.toList()
        val removed = removeFromImageCache(context, all)
        urls.clear()
        prefs?.edit()?.remove(KEY)?.apply()
        return removed
    }
}

/** Drops [urls] from the image loader's disk cache and clears its memory
 *  cache; returns how many were removed. */
expect fun removeFromImageCache(context: PlatformContext, urls: List<String>): Int
