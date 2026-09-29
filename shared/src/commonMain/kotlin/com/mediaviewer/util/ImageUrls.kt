package com.mediaviewer.util

/** Image-URL helpers shared by the data layer and the UI (Android's
 *  ImageLoading forwards to these). */
object ImageUrls {
    /** Image URLs are used as-is. (This used to shrink TMDB links; TMDB is
     *  no longer used at all.) */
    @Suppress("UNUSED_PARAMETER")
    fun optimizeUrl(url: String, wide: Boolean): String = url

    /** Bluesky's image CDN for a blob: resized, cached at the edge, and far
     *  faster than pulling the original from the owner's PDS. */
    fun bskyCdnUrl(did: String, cid: String, wide: Boolean, keepAlpha: Boolean = false): String =
        // @jpeg flattens transparency onto white — PNG/WebP blobs (which may
        // be transparent) are asked for as @png instead.
        "https://cdn.bsky.app/img/${if (wide) "feed_fullsize" else "feed_thumbnail"}/plain/$did/$cid@${if (keepAlpha) "png" else "jpeg"}"
}
