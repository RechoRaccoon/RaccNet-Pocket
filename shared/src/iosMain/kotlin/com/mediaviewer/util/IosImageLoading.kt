package com.mediaviewer.util

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.map.Mapper
import coil3.request.Options
import com.mediaviewer.platform.IosUri
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import com.mediaviewer.platform.IosImagePreloader
import com.mediaviewer.platform.IosPaths
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import okio.Path.Companion.toPath

/**
 * The app-wide Coil image loader on iOS — same rules as Android's
 * ImageLoading: servers' cache headers ignored (everything kept), a large
 * memory cache plus a 512 MB disk cache, blocked hosts never contacted,
 * and Wikimedia asked with Stellar's own User-Agent.
 */
/** Lets Coil load the app's own file Uris (picked photos, captures). */
private object IosUriMapper : Mapper<IosUri, String> {
    override fun map(data: IosUri, options: Options): String = data.urlString
}

object IosImageLoading {
    private var installed = false

    private val wikimediaUserAgent = createClientPlugin("StellarImageUserAgent") {
        onRequest { request, _ ->
            if (BlockedHosts.isWikimediaHost(request.url.host)) {
                request.headers.remove("User-Agent")
                request.headers.append("User-Agent", BlockedHosts.WIKIMEDIA_USER_AGENT)
            }
        }
    }

    fun install() {
        if (installed) return
        installed = true
        SingletonImageLoader.setSafe { context ->
            val client = HttpClient(Darwin) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 30_000
                }
                install(wikimediaUserAgent)
            }
            ImageLoader.Builder(context)
                .components {
                    add(BlockedHostsImageInterceptor)
                    // Picked photos/videos are IosUri("file://…").
                    add(IosUriMapper)
                    add(KtorNetworkFetcherFactory(httpClient = client))
                }
                .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
                .diskCache {
                    DiskCache.Builder()
                        .directory((IosPaths.cacheDir() + "/image_cache").toPath())
                        .maxSizeBytes(512L * 1024 * 1024)
                        .build()
                }
                .build()
        }
        IosImagePreloader.preload = { urls ->
            val ctx = PlatformContext.INSTANCE
            val loader = SingletonImageLoader.get(ctx)
            urls.filter { it.isNotBlank() }.forEach { loader.enqueue(ImageRequest.Builder(ctx).data(it).build()) }
        }
    }
}
