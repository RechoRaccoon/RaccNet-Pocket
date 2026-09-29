package com.mediaviewer.util

import com.mediaviewer.platform.parseUriComponents

/**
 * Hosts Stellar never talks to. TMDB (The Movie Database): Popfeed records
 * can still carry TMDB image links (`posterUrl`/`backdropUrl`), and Stellar
 * doesn't use them — title covers come only from images stored in the
 * record itself (blobs). This is the safety net: every HTTP client in the
 * app (the image loader and all API clients) refuses these hosts outright,
 * so even a stray link in an old cache or a record can't reach them.
 */
object BlockedHosts {
    private val suffixes = listOf("tmdb.org", "themoviedb.org", "tmdb.com")

    fun isBlocked(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return suffixes.any { h == it || h.endsWith(".$it") }
    }

    /** True for a URL whose host is blocked. */
    fun isBlockedUrl(url: String?): Boolean =
        !url.isNullOrBlank() && isBlocked(runCatching { parseUriComponents(url.trim()).host }.getOrNull())

    /**
     * Title covers: the only image links Stellar will load for a title are
     * images stored on AT Protocol itself (record blobs — served by
     * Bluesky's CDN or a PDS's getBlob) and Wikipedia's own files (the
     * fallback cover). Plain links a record carries to anywhere else — TMDB,
     * IGDB, Google Books, Spotify, Apple Music, Open Library, and so on —
     * are ignored, so no title type pulls from a third-party or paid
     * service.
     */
    fun isAllowedCoverUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { parseUriComponents(url.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (isBlocked(host)) return false
        if (uri.scheme?.lowercase() != "https") return false
        return host == "cdn.bsky.app" ||
            host == "upload.wikimedia.org" ||
            uri.path.orEmpty().startsWith("/xrpc/com.atproto.sync.getBlob")
    }

    /** True for Wikimedia-hosted image links (the fallback cover source). */
    fun isWikimediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { parseUriComponents(url.trim()).host?.lowercase() }.getOrNull() ?: return false
        return host == "upload.wikimedia.org" || host.endsWith(".wikipedia.org")
    }

    const val WIKIMEDIA_USER_AGENT = "Stellar/1.0 (Android app; RechoRaccoonBusiness@proton.me)"

    /** Wikimedia asks every client to identify itself. */
    fun isWikimediaHost(h: String): Boolean =
        h == "upload.wikimedia.org" || h.endsWith(".wikipedia.org") || h.endsWith(".wikidata.org")
}
