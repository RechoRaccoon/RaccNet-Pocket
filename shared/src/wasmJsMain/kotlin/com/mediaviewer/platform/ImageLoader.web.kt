package com.mediaviewer.platform

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory

/**
 * Configures Coil 3's singleton [ImageLoader] with the Ktor network fetcher.
 *
 * Coil 3 ships without a network fetcher — without this, [AsyncImage]
 * silently fails to load any network images on web. Must be called from
 * within the Compose content (it uses a composable under the hood).
 */
@Composable
fun ConfigureWebImageLoader() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory())
            }
            .build()
    }
}
