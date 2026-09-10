package com.mediaviewer.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * True on the web (wasmJs) target. Used by shared UI to hide app-exclusive
 * features (AI image tagging, on-device translation toggle, …).
 */
expect val isWebPlatform: Boolean

/**
 * Inline video playback.
 *
 * androidMain: media3 ExoPlayer (incl. HLS, which is what Bluesky serves).
 * wasmJsMain:  <video> element wrapper (native HLS on Safari; hls.js where needed).
 */
@Composable
expect fun VideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    autoplay: Boolean = true,
    muted: Boolean = false,
    loop: Boolean = false,
)

/**
 * Embedded third-party players (Twitch / YouTube live embeds in the Hub).
 *
 * androidMain: android.webkit.WebView.
 * wasmJsMain:  <iframe> overlay.
 */
@Composable
expect fun WebEmbed(
    url: String,
    modifier: Modifier = Modifier,
)

/** Opens [url] in the platform's external browser / a new tab. */
expect fun openUrl(url: String)
