package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.util.FeedVideoPool

/** An ExoPlayer from [FeedVideoPool], seen through [FeedVideoPlayer]. */
internal class ExoFeedVideoPlayer(val exo: ExoPlayer) : FeedVideoPlayer {
    override val isPlaying: Boolean get() = exo.isPlaying
    override val currentPosition: Long get() = exo.currentPosition
    override val duration: Long get() = exo.duration
    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun seekTo(positionMs: Long) = exo.seekTo(positionMs)

    override val aspectRatio: Float
        get() = exo.videoSize.let { v -> if (v.width > 0 && v.height > 0) (v.width * v.pixelWidthHeightRatio) / v.height else 0f }

    override val isBuffering: Boolean
        get() = exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_IDLE

    private val wrapped = HashMap<FeedVideoListener, Player.Listener>()

    override fun addListener(listener: FeedVideoListener) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    listener.onAspectRatio((videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)
            override fun onRenderedFirstFrame() = listener.onFirstFrame()
            override fun onPlaybackStateChanged(playbackState: Int) = listener.onBuffering(playbackState == Player.STATE_BUFFERING)
        }
        wrapped[listener] = l
        exo.addListener(l)
    }

    override fun removeListener(listener: FeedVideoListener) {
        wrapped.remove(listener)?.let { exo.removeListener(it) }
    }
}

actual object FeedVideos {
    actual fun acquire(context: PlatformContext, url: String): FeedVideoPlayer =
        ExoFeedVideoPlayer(FeedVideoPool.acquire(context, url))

    actual fun recycle(url: String, player: FeedVideoPlayer) {
        FeedVideoPool.recycle(url, (player as ExoFeedVideoPlayer).exo)
    }

    actual fun preload(context: PlatformContext, urls: List<String>) = FeedVideoPool.preload(context, urls)
    actual fun releaseIdle() = FeedVideoPool.releaseIdle()
}

@Composable
actual fun FeedVideoView(player: FeedVideoPlayer, modifier: Modifier) {
    val exo = (player as ExoFeedVideoPlayer).exo
    AndroidView(
        factory = { ctx ->
            // Item 4: inflated from XML (see res/layout/player_view_texture.xml)
            // because PlayerView's surface type can only be set via XML/AttributeSet
            // at construction — there's no runtime setter for it — and it must be
            // TextureView (not the default SurfaceView) for the video to actually
            // show up in the live backdrop-blur capture.
            (android.view.LayoutInflater.from(ctx).inflate(com.mediaviewer.R.layout.player_view_texture, null) as PlayerView).apply {
                this.player = exo; useController = false
                // The pooled player outlives this view; keep the
                // last frame instead of flashing black on reattach.
                setKeepContentOnPlayerReset(true)
                // Item 10: only the Compose-drawn buffering spinner shows.
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        modifier = modifier,
        // Detach from the pooled player when this view goes away, so
        // it can be handed to the next view cleanly.
        onRelease = { it.player = null }
    )
}
