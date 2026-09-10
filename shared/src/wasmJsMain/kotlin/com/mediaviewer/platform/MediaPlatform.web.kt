package com.mediaviewer.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.browser.document
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.HTMLVideoElement

actual val isWebPlatform: Boolean = true

/**
 * Inline video playback on web: an [HTMLVideoElement] absolutely positioned
 * over the Compose canvas, glued to this composable's bounds.
 *
 * Why a DOM overlay instead of drawing into the canvas: Compose
 * Multiplatform for web renders into a single <canvas>, which can't host a
 * natively-decoding <video> (and the web has no ExoPlayer equivalent to
 * drive frames manually). The element is created per-URL in [remember],
 * appended to <body> in [DisposableEffect], and removed on dispose; a
 * positioned [Box] reports its bounds via onGloballyPositioned and the
 * element's style is synced to them. pointer-events are disabled so taps
 * keep flowing to the shared Compose gesture handling (the shared UI draws
 * its own transport UI, like on Android).
 *
 * HLS: Safari plays it natively; other browsers need the playlist URL to
 * be handled by the page — there is intentionally no hls.js bundled here
 * (see the PORT note below if that becomes necessary).
 */
@Composable
actual fun VideoPlayer(
    url: String,
    modifier: Modifier,
    autoplay: Boolean,
    muted: Boolean,
    loop: Boolean,
) {
    val video = remember(url) {
        (document.createElement("video") as HTMLVideoElement).apply {
            setAttribute("playsinline", "")
            controls = false
            style.setProperty("position", "absolute")
            style.setProperty("z-index", "10")
            style.setProperty("object-fit", "contain")
            style.setProperty("background", "black")
            style.setProperty("pointer-events", "none")
            style.setProperty("margin", "0")
        }
    }

    // Keep the element glued to the composable's bounds (positionInWindow
    // is in CSS pixels, which is what the style properties expect).
    Box(modifier.onGloballyPositioned { coords ->
        val pos = coords.positionInWindow()
        val size = coords.size
        video.style.setProperty("left", "${pos.x}px")
        video.style.setProperty("top", "${pos.y}px")
        video.style.setProperty("width", "${size.width}px")
        video.style.setProperty("height", "${size.height}px")
    })

    // Sync playback props if the caller toggles them without changing URL.
    LaunchedEffect(video, autoplay, muted, loop) {
        video.autoplay = autoplay
        video.muted = muted
        video.loop = loop
        if (autoplay) video.play()
    }

    DisposableEffect(video) {
        video.src = url
        video.autoplay = autoplay
        video.muted = muted
        video.loop = loop
        document.body?.appendChild(video)
        // Note: browsers block autoplay *with sound*; an unmuted autoplay
        // request may reject its play() promise, which surfaces as a console
        // warning only — playback then starts on the user's first tap via
        // the shared transport UI.
        if (autoplay) video.play()
        onDispose {
            video.pause()
            video.removeAttribute("src")
            video.load()
            document.body?.removeChild(video)
        }
    }
}

/**
 * Embedded third-party players (Twitch / YouTube live embeds in the Hub):
 * an <iframe> overlaid the same way as [VideoPlayer].
 */
@Composable
actual fun WebEmbed(url: String, modifier: Modifier) {
    val iframe = remember(url) {
        (document.createElement("iframe") as HTMLIFrameElement).apply {
            setAttribute("allow", "autoplay; fullscreen; picture-in-picture")
            setAttribute("allowfullscreen", "")
            setAttribute("frameborder", "0")
            style.setProperty("position", "absolute")
            style.setProperty("z-index", "10")
            style.setProperty("border", "0")
            style.setProperty("margin", "0")
        }
    }

    Box(modifier.onGloballyPositioned { coords ->
        val pos = coords.positionInWindow()
        val size = coords.size
        iframe.style.setProperty("left", "${pos.x}px")
        iframe.style.setProperty("top", "${pos.y}px")
        iframe.style.setProperty("width", "${size.width}px")
        iframe.style.setProperty("height", "${size.height}px")
    })

    DisposableEffect(iframe) {
        iframe.src = url
        document.body?.appendChild(iframe)
        onDispose {
            document.body?.removeChild(iframe)
        }
    }
}

actual fun openUrl(url: String) {
    kotlinx.browser.window.open(url, "_blank")
}
