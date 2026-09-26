package com.mediaviewer.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An attached video in the composer: shows the video's real first frame
 * (not a black box), and tapping it plays the video right there with
 * standard controls. The player only exists while playing, and is released
 * when the preview leaves the screen.
 */
@Composable
fun InlineVideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var poster by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var playing by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        poster = withContext(Dispatchers.IO) { firstFrame(context, uri) }
    }

    Box(modifier.background(Color.Black)) {
        if (playing) {
            val player = remember(uri) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(uri))
                    repeatMode = Player.REPEAT_MODE_OFF
                    prepare()
                    playWhenReady = true
                }
            }
            DisposableEffect(player) { onDispose { player.release() } }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                onRelease = { it.player = null }
            )
        } else {
            poster?.let {
                Image(it.asImageBitmap(), contentDescription = "Video", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier.fillMaxSize().clickable { playing = true },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play video", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

/** The first frame, downscaled — works for content:// Uris from the picker,
 *  the system camera and VRM recordings alike. */
private fun firstFrame(context: android.content.Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 720)
        } else {
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}
