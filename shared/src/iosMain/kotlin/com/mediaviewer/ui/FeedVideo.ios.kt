package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.mediaviewer.platform.PlatformContext
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol

/** An AVPlayer behind [FeedVideoPlayer]. State changes are picked up by a
 *  periodic time observer (10 a second) and reported to the listeners. */
@OptIn(ExperimentalForeignApi::class)
internal class AvFeedVideoPlayer(val url: String) : FeedVideoPlayer {
    val player: AVPlayer = AVPlayer(uRL = NSURL.URLWithString(url)!!)
    private val listeners = mutableListOf<FeedVideoListener>()
    private var lastPlaying = false
    private var lastBuffering = true
    private var lastAspect = 0f
    private var firstFrameSent = false
    private var timeObserver: Any? = null
    private var endObserver: NSObjectProtocol? = null

    init {
        timeObserver = player.addPeriodicTimeObserverForInterval(CMTimeMake(1, 10), null) { _ -> tick() }
        // Loop, like the Android feed (REPEAT_MODE_ONE).
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification, player.currentItem, NSOperationQueue.mainQueue
        ) { _ ->
            player.seekToTime(CMTimeMake(0, 1))
            player.play()
        }
    }

    private fun tick() {
        val playing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying
        if (playing != lastPlaying) { lastPlaying = playing; listeners.toList().forEach { it.onIsPlayingChanged(playing) } }
        val buffering = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate ||
            player.currentItem?.status != AVPlayerItemStatusReadyToPlay
        if (buffering != lastBuffering) { lastBuffering = buffering; listeners.toList().forEach { it.onBuffering(buffering) } }
        val a = aspectRatio
        if (a > 0f && a != lastAspect) { lastAspect = a; listeners.toList().forEach { it.onAspectRatio(a) } }
        if (!firstFrameSent && playing && currentPosition > 0) {
            firstFrameSent = true
            listeners.toList().forEach { it.onFirstFrame() }
        }
    }

    override val isPlaying: Boolean get() = player.timeControlStatus == AVPlayerTimeControlStatusPlaying
    override val currentPosition: Long get() = seconds(player.currentTime())
    override val duration: Long get() = player.currentItem?.let { seconds(it.duration) } ?: 0L
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) { player.seekToTime(CMTimeMakeWithSeconds(positionMs / 1000.0, 600)) }

    override val aspectRatio: Float
        get() = player.currentItem?.presentationSize?.useContents { if (width > 0 && height > 0) (width / height).toFloat() else 0f } ?: 0f

    override val isBuffering: Boolean get() = lastBuffering

    override fun addListener(listener: FeedVideoListener) { listeners += listener }
    override fun removeListener(listener: FeedVideoListener) { listeners -= listener }

    fun release() {
        player.pause()
        timeObserver?.let { player.removeTimeObserver(it) }
        timeObserver = null
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        listeners.clear()
    }

    private fun seconds(t: CValue<CMTime>): Long {
        val s = CMTimeGetSeconds(t)
        return if (s.isNaN() || s.isInfinite()) 0L else (s * 1000).toLong()
    }
}

/** Same pooling rules as Android's FeedVideoPool. */
actual object FeedVideos {
    private class Entry(val url: String, val player: AvFeedVideoPlayer) {
        var inUse = false
        var lastUsed = 0L
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var wanted: Set<String> = emptySet()
    private const val MAX_PLAYERS = 3
    private var clock = 0L

    actual fun acquire(context: PlatformContext, url: String): FeedVideoPlayer {
        val existing = entries[url]
        if (existing != null && !existing.inUse) {
            existing.inUse = true
            existing.lastUsed = ++clock
            return existing.player
        }
        val p = AvFeedVideoPlayer(url)
        if (existing == null) entries[url] = Entry(url, p).also { it.inUse = true; it.lastUsed = ++clock }
        trim()
        return p
    }

    actual fun recycle(url: String, player: FeedVideoPlayer) {
        val p = player as AvFeedVideoPlayer
        val e = entries[url]
        if (e == null || e.player !== p) { p.release(); return }
        e.inUse = false
        e.lastUsed = ++clock
        p.player.pause()
        p.player.seekToTime(CMTimeMake(0, 1))
        if (url !in wanted) { entries.remove(url); p.release() }
        trim()
    }

    actual fun preload(context: PlatformContext, urls: List<String>) {
        val list = urls.filter { it.isNotBlank() }.distinct().take(MAX_PLAYERS)
        wanted = list.toSet()
        entries.values.filter { !it.inUse && it.url !in wanted }.forEach { entries.remove(it.url); it.player.release() }
        for (url in list) {
            if (entries.containsKey(url)) continue
            if (entries.size >= MAX_PLAYERS) break
            entries[url] = Entry(url, AvFeedVideoPlayer(url))
        }
    }

    actual fun releaseIdle() {
        wanted = emptySet()
        entries.values.filter { !it.inUse }.forEach { entries.remove(it.url); it.player.release() }
    }

    private fun trim() {
        if (entries.size <= MAX_PLAYERS) return
        val idle = entries.values.filter { !it.inUse }.sortedWith(compareBy<Entry> { it.url in wanted }.thenBy { it.lastUsed })
        for (e in idle) {
            if (entries.size <= MAX_PLAYERS) break
            entries.remove(e.url)
            e.player.release()
        }
    }
}

/** A UIView whose AVPlayerLayer always fills it. */
@OptIn(ExperimentalForeignApi::class)
private class PlayerLayerView(player: AVPlayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val playerLayer: AVPlayerLayer = AVPlayerLayer.playerLayerWithPlayer(player).apply {
        videoGravity = AVLayerVideoGravityResizeAspect
    }

    init {
        backgroundColor = UIColor.clearColor
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}

@OptIn(ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun FeedVideoView(player: FeedVideoPlayer, modifier: Modifier) {
    val av = (player as AvFeedVideoPlayer).player
    UIKitView(
        factory = { PlayerLayerView(av) },
        modifier = modifier,
        update = { it.playerLayer.player = av },
        onRelease = { it.playerLayer.player = null },
        // Taps go to Compose (the tap-to-show-controls gesture above it).
        properties = UIKitInteropProperties(interactionMode = null)
    )
}
