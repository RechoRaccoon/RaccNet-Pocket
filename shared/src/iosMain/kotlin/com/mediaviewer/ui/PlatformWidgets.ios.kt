package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitViewController
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.util.EmojiEntry
import com.mediaviewer.util.EmojiStore
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.pause
import platform.AVKit.AVPlayerViewController
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

/** Custom emoji aren't on iOS yet: the panel explains that. */
@Composable
internal actual fun EmojiPanel(
    store: EmojiStore,
    height: Dp,
    compact: Boolean,
    liquidGlass: Boolean,
    tint: Color,
    onPickEmoji: (EmojiEntry) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    modifier: Modifier
) {
    Box(
        modifier.fillMaxWidth().height(if (compact) EmojiPanelCompactHeight else height)
            .background(Color.Black.copy(alpha = 0.35f)).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Custom emoji are coming to iOS soon.",
            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center
        )
    }
}

/** The system player (AVKit) with its standard controls. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun InlineVideoPlayer(uri: PlatformUri, modifier: Modifier) {
    val url = remember(uri) { NSURL.URLWithString(uri.toString()) ?: NSURL.fileURLWithPath(uri.toString()) }
    val player = remember(url) { AVPlayer(uRL = url) }
    UIKitViewController(
        factory = {
            AVPlayerViewController().apply {
                this.player = player
                showsPlaybackControls = true
            }
        },
        modifier = modifier.background(Color.Black),
        onRelease = { it.player?.pause(); it.player = null }
    )
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun EmbeddedWebView(url: String, modifier: Modifier) {
    UIKitView(
        factory = {
            val config = WKWebViewConfiguration().apply {
                allowsInlineMediaPlayback = true
                mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
            }
            WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config).apply {
                NSURL.URLWithString(url)?.let { loadRequest(NSURLRequest.requestWithURL(it)) }
            }
        },
        modifier = modifier,
        update = { view ->
            if (view.URL?.absoluteString != url) NSURL.URLWithString(url)?.let { view.loadRequest(NSURLRequest.requestWithURL(it)) }
        }
    )
}

/** Textshot rendering on iOS arrives with the rest of Textshot. */
actual fun renderTextshotPreview(text: String, store: EmojiStore): ImageBitmap? = null
