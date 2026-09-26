package com.mediaviewer.util

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

/**
 * Feed videos that start the instant they're swiped onto.
 *
 * Before, every post created a brand-new ExoPlayer the moment it appeared,
 * then fetched the playlist, the first segment, spun up a decoder — about a
 * second of black on every swipe. Now:
 *
 *  1. **Preloading.** While you look at one post, the next videos (and the
 *     previous one) are already prepared in paused players: playlist
 *     parsed, the first seconds buffered, decoder ready. Swiping onto one
 *     just attaches it to the screen and presses play.
 *  2. **A disk cache** shared by every player (and every session), so a
 *     video you've seen — or one preloaded but swiped past — never
 *     downloads twice.
 *  3. **Bounded.** Only [maxPlayers] players ever exist (2 on low-RAM
 *     phones, 4 otherwise — each one can hold a hardware decoder), idle
 *     ones are released as soon as they're out of the preload window, and
 *     preloaded players buffer just a few seconds, so preloading doesn't
 *     starve the video you're actually watching.
 *
 * Main thread only (ExoPlayer's own rule).
 */
object FeedVideoPool {
    private const val TAG = "FeedVideoPool"

    private class Entry(val url: String, val player: ExoPlayer) {
        var inUse = false
        var lastUsed = System.nanoTime()
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var wanted: Set<String> = emptySet()
    private var cache: SimpleCache? = null
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null
    private var maxPlayers = 3

    private fun ensureInit(context: Context) {
        if (mediaSourceFactory != null) return
        val app = context.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        maxPlayers = if (am?.isLowRamDevice == true || (am?.memoryClass ?: 256) < 192) 2 else 4
        val upstream = DefaultDataSource.Factory(
            app,
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(8_000)
        )
        val simpleCache = runCatching {
            SimpleCache(
                File(app.cacheDir, "feed_video_cache"),
                LeastRecentlyUsedCacheEvictor(if (maxPlayers <= 2) 150L * 1024 * 1024 else 400L * 1024 * 1024),
                StandaloneDatabaseProvider(app)
            )
        }.onFailure { Log.e(TAG, "Video cache unavailable — streaming uncached", it) }.getOrNull()
        cache = simpleCache
        val dataSource = if (simpleCache != null) {
            CacheDataSource.Factory()
                .setCache(simpleCache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else upstream
        mediaSourceFactory = DefaultMediaSourceFactory(dataSource)
    }

    private fun newPlayer(context: Context): ExoPlayer {
        ensureInit(context)
        // Start after 0.4 s of media; keep a modest buffer — preloaded players
        // stop at ~4 s (minBuffer) until they're actually played.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 20_000, 400, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(context.applicationContext)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory!!)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 1f
                playWhenReady = false
            }
    }

    /** A ready (or readying) player for [url]; the preloaded one if there is
     *  one. Pair every call with [recycle]. */
    fun acquire(context: Context, url: String): ExoPlayer {
        val existing = entries[url]
        if (existing != null && !existing.inUse) {
            existing.inUse = true
            existing.lastUsed = System.nanoTime()
            return existing.player
        }
        val player = newPlayer(context)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        if (existing == null) {
            entries[url] = Entry(url, player).also { it.inUse = true }
        }
        // (If the same URL is already on screen elsewhere, this second player
        // is simply not pooled — recycle() releases it.)
        trim()
        return player
    }

    /** Hands a player back. It's kept (paused, rewound) if its video is still
     *  in the preload window, so swiping back to it is instant too. */
    fun recycle(url: String, player: ExoPlayer) {
        val e = entries[url]
        if (e == null || e.player !== player) {
            runCatching { player.release() }
            return
        }
        e.inUse = false
        e.lastUsed = System.nanoTime()
        runCatching {
            player.playWhenReady = false
            player.seekTo(0)
        }
        if (url !in wanted) {
            entries.remove(url)
            runCatching { player.release() }
        }
        trim()
    }

    /**
     * The videos around the current post: [urls] in priority order (current
     * first, then next, next+1, previous). Prepares any that aren't ready
     * yet and drops idle players that fell out of the window.
     */
    fun preload(context: Context, urls: List<String>) {
        val list = urls.filter { it.isNotBlank() }.distinct().take(maxPlayers)
        wanted = list.toSet()
        // Release idle players no longer wanted.
        val drop = entries.values.filter { !it.inUse && it.url !in wanted }
        for (e in drop) {
            entries.remove(e.url)
            runCatching { e.player.release() }
        }
        for (url in list) {
            if (entries.containsKey(url)) continue
            if (entries.size >= maxPlayers) break
            val p = newPlayer(context)
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            entries[url] = Entry(url, p)
        }
    }

    /** Frees every idle player (the feed was closed). */
    fun releaseIdle() {
        wanted = emptySet()
        val idle = entries.values.filter { !it.inUse }
        for (e in idle) {
            entries.remove(e.url)
            runCatching { e.player.release() }
        }
    }

    private fun trim() {
        if (entries.size <= maxPlayers) return
        val idle = entries.values.filter { !it.inUse }.sortedWith(
            compareBy<Entry> { it.url in wanted }.thenBy { it.lastUsed }
        )
        for (e in idle) {
            if (entries.size <= maxPlayers) break
            entries.remove(e.url)
            runCatching { e.player.release() }
        }
    }
}
