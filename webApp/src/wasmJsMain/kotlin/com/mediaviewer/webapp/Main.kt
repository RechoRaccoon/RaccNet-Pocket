package com.mediaviewer.webapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.mediaviewer.App
import kotlinx.browser.document

/**
 * Web entry point. Boots the shared [App] composable inside the browser
 * viewport. All app logic lives in :shared (commonMain); this module is
 * only the wasmJs bootstrap + index.html shell.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val body = document.body ?: error("index.html has no <body>")
    ComposeViewport(body) {
        // Coil 3 ships without a network fetcher — without this, AsyncImage
        // silently fails to load any network images on web.
        setSingletonImageLoaderFactory { context ->
            ImageLoader.Builder(context)
                .components {
                    add(KtorNetworkFetcherFactory())
                }
                .build()
        }
        App()
    }
}
