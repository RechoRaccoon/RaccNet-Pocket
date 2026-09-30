package com.mediaviewer.util

import android.app.ActivityManager
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
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
        SingletonImageLoader.setSafe {
            val dispatcher = Dispatcher().apply {
                maxRequests = 48
                maxRequestsPerHost = 12
            }
            val client = OkHttpClient.Builder()
                .dispatcher(dispatcher)
                // Never TMDB (see BlockedHosts).
                .addInterceptor(BlockedHostsInterceptors.interceptor)
                // Wikimedia asks every client to identify itself (fallback
                // title covers load from upload.wikimedia.org).
                .addInterceptor(BlockedHostsInterceptors.wikimediaUserAgent)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            ImageLoader.Builder(app)
                // Coil 3 ignores servers' Cache-Control by default (the
                // equivalent of Coil 2's respectCacheHeaders(false)).
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(app, if (lowRam) 0.2 else 0.3)
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
    // it out, so the whole image cache (disk + memory) is still wiped every
    // 30 days to keep it fresh. (Stellar no longer loads anything from
    // TMDB at all — see BlockedHosts.) It's checked every time the app
    // starts AND by a once-a-day background job (ImageCacheExpiryWorker),
    // so it still happens for someone who doesn't open the app for months.
    private const val META_PREFS = "image_cache_meta"
    private const val KEY_LAST_WIPE = "last_wipe_ms"
    private const val KEY_TMDB_PURGED = "tmdb_purged_v1"
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** Wipes the image cache if it's been [MAX_AGE_MS] since the last wipe.
     *  Safe from any thread; safe to call often. */
    @Synchronized
    fun wipeIfDue(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_WIPE, 0L)
        // One time, on the first run of the build that cut TMDB off: drop
        // every cached image so no TMDB poster stays on the phone.
        if (!prefs.getBoolean(KEY_TMDB_PURGED, false)) {
            wipe(app)
            prefs.edit().putLong(KEY_LAST_WIPE, now).putBoolean(KEY_TMDB_PURGED, true).apply()
            return
        }
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
        val loader = SingletonImageLoader.get(app)
        runCatching { loader.memoryCache?.clear() }
        runCatching { loader.diskCache?.clear() }
    }

    /** See [ImageUrls.optimizeUrl]. */
    fun optimizeUrl(url: String, wide: Boolean): String = ImageUrls.optimizeUrl(url, wide)

    /** See [ImageUrls.bskyCdnUrl]. */
    fun bskyCdnUrl(did: String, cid: String, wide: Boolean, keepAlpha: Boolean = false): String =
        ImageUrls.bskyCdnUrl(did, cid, wide, keepAlpha)
}
