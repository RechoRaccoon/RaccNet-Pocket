package com.mediaviewer.platform

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView

actual val isWebPlatform: Boolean = false

/**
 * Inline video playback, ported from the legacy MainFeedScreen.VideoPlayer.
 *
 * One ExoPlayer per composable, released in onDispose. repeatMode follows
 * [loop]; volume follows [muted]. HLS (what Bluesky serves) gets an explicit
 * [HlsMediaSource] when the URL looks like a playlist — the default media
 * source factory sniffs the container for everything else, same as the
 * legacy `setMediaItem(fromUri(url))` path.
 *
 * Legacy note: the old app inflated PlayerView from XML to force a
 * TextureView surface (needed for its live backdrop-blur capture). That
 * capture doesn't exist in the shared UI, so this uses a plain programmatic
 * PlayerView (SurfaceView) — simpler and cheaper.
 */
@Composable
actual fun VideoPlayer(
    url: String,
    modifier: Modifier,
    autoplay: Boolean,
    muted: Boolean,
    loop: Boolean,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (muted) 0f else 1f
        }
    }

    LaunchedEffect(url, player) {
        val mediaItem = ExoMediaItem.fromUri(url)
        if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            player.setMediaSource(
                HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                    .createMediaSource(mediaItem)
            )
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        if (autoplay) player.play()
    }
    // Keep volume/mute in sync if the caller toggles it without recomposing
    // the whole player.
    LaunchedEffect(muted, player) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(loop, player) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    DisposableEffect(player) { onDispose { player.release() } }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                // The shared UI draws its own transport/buffering UI; never
                // stack PlayerView's native spinner on top of it.
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            }
        },
        modifier = modifier,
    )
}

/**
 * Embedded third-party players (Twitch / YouTube live embeds in the Hub).
 * Plain WebView with JS + DOM storage enabled — video embeds require both —
 * and media playback not gated behind a user gesture so embeds can autoplay.
 */
@Composable
actual fun WebEmbed(url: String, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                loadUrl(url)
            }
        },
        update = { view ->
            if (view.url != url) view.loadUrl(url)
        },
        onRelease = { view -> view.destroy() },
        modifier = modifier,
    )
}

/** Opens [url] in the user's external browser via ACTION_VIEW. */
actual fun openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    AndroidAppContext.app.startActivity(intent)
}
