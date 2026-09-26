package com.mediaviewer.util

import android.app.ActivityManager
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app-wide Coil image loader, tuned so images that have loaded once
 * stay loaded.
 *
 * Why review/title/background images used to flicker out and reload when
 * scrolled away and back:
 *  - **Cache headers.** Coil obeys each server's Cache-Control by default.
 *    Several of the hosts these images come from (PDS `getBlob`, some image
 *    CDNs) send `no-cache`/`private`/short max-age, so nothing was kept on
 *    disk and every re-display was a fresh network download. Images here are
 *    content-addressed or effectively immutable, so headers are ignored and
 *    everything is kept.
 *  - **Memory cache too small for full-size art.** A few big backdrops
 *    evicted everything else. The memory cache now takes a larger share of
 *    the (largeHeap) memory budget, and a 512 MB disk cache backs it.
 *  - **Too few parallel downloads.** OkHttp's default is 5 per host, so a
 *    screen full of posters from one CDN queued up; raised to 12.
 *
 * Every existing `AsyncImage`/`Coil.imageLoader(context)` call picks this up
 * automatically — it replaces Coil's default singleton.
 */
object ImageLoading {
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = am?.isLowRamDevice == true
        Coil.setImageLoader {
            val dispatcher = Dispatcher().apply {
                maxRequests = 48
                maxRequestsPerHost = 12
            }
            val client = OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            ImageLoader.Builder(app)
                .okHttpClient(client)
                .respectCacheHeaders(false)
                .memoryCache {
                    MemoryCache.Builder(app)
                        .maxSizePercent(if (lowRam) 0.2 else 0.3)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(app.cacheDir.resolve("image_cache"))
                        .maxSizeBytes(if (lowRam) 200L * 1024 * 1024 else 512L * 1024 * 1024)
                        .build()
                }
                // Halves bitmap memory for opaque images on low-RAM phones,
                // so far more of them fit in the memory cache.
                .allowRgb565(lowRam)
                .build()
        }
    }

    // ── Age limit ────────────────────────────────────────────────────────
    // Because cache headers are ignored above, nothing else would ever
    // expire a stored image; it would only leave when the size cap pushed
    // it out. TMDB's API terms (§1.C) forbid caching their content for more
    // than 6 months, and TMDB posters/backdrops show up here via Popfeed
    // reviews, so the whole image cache (disk + memory) is wiped every
    // 30 days — well inside that limit. It's checked every time the app
    // starts AND by a once-a-day background job (ImageCacheExpiryWorker),
    // so it still happens for someone who doesn't open the app for months.
    private const val META_PREFS = "image_cache_meta"
    private const val KEY_LAST_WIPE = "last_wipe_ms"
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** Wipes the image cache if it's been [MAX_AGE_MS] since the last wipe.
     *  Safe from any thread; safe to call often. */
    @Synchronized
    fun wipeIfDue(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_WIPE, 0L)
        if (last == 0L) {
            // First run with the age limit: anything already cached came
            // from older builds with no start date — clear it once.
            wipe(app)
            prefs.edit().putLong(KEY_LAST_WIPE, now).apply()
            return
        }
        if (now - last < MAX_AGE_MS && now >= last) return
        wipe(app)
        prefs.edit().putLong(KEY_LAST_WIPE, now).apply()
    }

    private fun wipe(app: Context) {
        install(app)
        val loader = Coil.imageLoader(app)
        runCatching { loader.memoryCache?.clear() }
        runCatching { loader.diskCache?.clear() }
    }

    /**
     * Rewrites known image URLs to a size that fits a phone screen:
     * TMDB `original` (often 3000–4000 px, several MB) → w780 posters /
     * w1280 backdrops. Anything else is returned unchanged.
     */
    fun optimizeUrl(url: String, wide: Boolean): String {
        if (url.contains("image.tmdb.org/t/p/original/")) {
            return url.replace("/t/p/original/", if (wide) "/t/p/w1280/" else "/t/p/w780/")
        }
        return url
    }

    /** Bluesky's image CDN for a blob: resized, cached at the edge, and far
     *  faster than pulling the original from the owner's PDS. */
    fun bskyCdnUrl(did: String, cid: String, wide: Boolean): String =
        "https://cdn.bsky.app/img/${if (wide) "feed_fullsize" else "feed_thumbnail"}/plain/$did/$cid@jpeg"
}
