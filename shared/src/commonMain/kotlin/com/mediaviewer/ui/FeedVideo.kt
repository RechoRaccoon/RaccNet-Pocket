package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mediaviewer.platform.PlatformContext

/** The transport controls the feed's own (Compose-drawn) video UI needs. */
interface VideoController {
    val isPlaying: Boolean
    /** Milliseconds. */
    val currentPosition: Long
    /** Milliseconds; 0 or less while unknown. */
    val duration: Long
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
}

/** What a feed video reports back while it plays. */
interface FeedVideoListener {
    /** Width / height of the picture (pixel aspect already applied). */
    fun onAspectRatio(ratio: Float) {}
    fun onIsPlayingChanged(playing: Boolean) {}
    fun onFirstFrame() {}
    fun onBuffering(buffering: Boolean) {}
}

/** A pooled feed video player (ExoPlayer on Android, AVPlayer on iOS). */
interface FeedVideoPlayer : VideoController {
    /** Current picture aspect ratio, or 0 while unknown. */
    val aspectRatio: Float
    /** Still loading (buffering or not yet prepared). */
    val isBuffering: Boolean
    fun addListener(listener: FeedVideoListener)
    fun removeListener(listener: FeedVideoListener)
}

/**
 * Feed video players, pooled and pre-prepared: the next posts' videos are
 * already loading while the current one plays, so swiping to them starts
 * instantly.
 */
expect object FeedVideos {
    fun acquire(context: PlatformContext, url: String): FeedVideoPlayer
    fun recycle(url: String, player: FeedVideoPlayer)
    fun preload(context: PlatformContext, urls: List<String>)
    fun releaseIdle()
}

/** Draws [player]'s picture (no native controls), filling [modifier]. */
@Composable
expect fun FeedVideoView(player: FeedVideoPlayer, modifier: Modifier)
