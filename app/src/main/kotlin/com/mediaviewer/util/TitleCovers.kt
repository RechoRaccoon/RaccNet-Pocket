package com.mediaviewer.util

import android.content.Context
import android.content.SharedPreferences
import coil.Coil
import java.util.concurrent.ConcurrentHashMap

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
    private val urls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
    fun clearCached(context: Context): Int {
        val loader = Coil.imageLoader(context.applicationContext)
        val all = urls.toList()
        var removed = 0
        val disk = loader.diskCache
        for (u in all) {
            if (runCatching { disk?.remove(u) }.getOrNull() == true) removed++
        }
        runCatching { loader.memoryCache?.clear() }
        urls.clear()
        prefs?.edit()?.remove(KEY)?.apply()
        return removed
    }
}
