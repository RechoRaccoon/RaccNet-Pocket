package com.mediaviewer.webapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.mediaviewer.App
import com.mediaviewer.platform.ConfigureWebImageLoader
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
        ConfigureWebImageLoader()
        App()
    }
}
