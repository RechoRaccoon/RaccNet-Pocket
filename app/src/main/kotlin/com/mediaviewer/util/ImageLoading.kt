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
