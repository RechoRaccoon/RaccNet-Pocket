package com.mediaviewer.platform

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory

/**
 * Configures Coil 3's singleton [ImageLoader] with web image fetching.
 *
 * Coil 3 ships without a network fetcher — without this, [AsyncImage]
 * silently fails to load any network images on web. Must be called from
 * within the Compose content (it uses a composable under the hood).
 *
 * [BrowserImageFetcherFactory] is registered first: it loads via the
 * browser's `<img>` element (CORS-friendly hosts) with a CORS-proxy
 * fallback for hosts like `cdn.bsky.app` that send no CORS headers, which
 * Ktor's `fetch()`-based downloader cannot handle. Ktor's fetcher stays as
 * a fallback for anything else.
 */
@Composable
fun ConfigureWebImageLoader() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(BrowserImageFetcherFactory())
                add(KtorNetworkFetcherFactory())
            }
            .build()
    }
}
