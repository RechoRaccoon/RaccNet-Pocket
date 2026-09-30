package com.mediaviewer.ui

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import com.mediaviewer.util.EmojiStore

@Composable
actual fun EmbeddedWebView(url: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        update = { it.loadUrl(url) }
    )
}

actual fun renderTextshotPreview(text: String, store: EmojiStore): ImageBitmap? =
    com.mediaviewer.util.TextshotRenderer.render(text, emojiBitmap = store::bitmapForChar).asImageBitmap()
