package com.mediaviewer.util

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Splices a custom thumbnail image into a video's first frame before
 * upload. Bluesky has no separate "thumbnail" field on app.bsky.embed.
 * video — the app always shows frame 0 of the video itself as the
 * thumbnail — so the only way a custom thumbnail shows up is to make
 * frame 0 *be* that image. RaccNet Legacy does this server-side with
 * ffmpeg (concatenating a short still of the thumbnail with the real video
 * — see `_process_video` in raccnet_server.py); this is the on-device
 * equivalent using Media3 Transformer, since this app has no ffmpeg
 * dependency (and ffmpeg-kit is no longer maintained upstream anyway).
 *
 * UNVERIFIED — flagging clearly since this environment has no Android SDK/
 * emulator to actually run it against: the approach (an
 * EditedMediaItemSequence of [thumbnail-as-image, real video], with
 * experimentalSetForceAudioTrack(true) so the audio-less image segment can
 * sit in the same sequence as the video's own audio track) matches Media3's
 * documented Composition API as of 1.11, but the exact minimum image-
 * segment duration Transformer will accept wasn't confirmed against a real
 * build, so THUMBNAIL_DURATION_MS below is a conservative 100ms rather than
 * a single frame — test on a real device and shrink it if a shorter flash
 * is wanted (or if the muxer rejects sub-frame durations at all, drop it
 * and rely on Bluesky's own frame-0 thumbnail instead).
 */
object VideoThumbnailStitcher {
    private const val THUMBNAIL_DURATION_MS = 100L

    /** Returns a new local file:// [Uri] with [thumbnailUri] spliced in as
     *  the first frame of [videoUri], or [videoUri] itself, unchanged, if
     *  [thumbnailUri] is null. Must be called from a coroutine — internally
     *  hops to the main thread since Transformer requires a prepared
     *  Looper. */
    suspend fun stitch(context: Context, videoUri: Uri, thumbnailUri: Uri?): Uri {
        if (thumbnailUri == null) return videoUri

        val thumbnailItem = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(thumbnailUri).setImageDurationMs(THUMBNAIL_DURATION_MS).build()
        ).build()
        val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(videoUri)).build()

        val sequence = EditedMediaItemSequence.Builder(thumbnailItem, videoItem)
            .experimentalSetForceAudioTrack(true)
            .build()
        val composition = Composition.Builder(sequence).build()
        val outputFile = File(context.cacheDir, "raccnet-stitched-${System.currentTimeMillis()}.mp4")

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Uri.fromFile(outputFile))
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer.start(composition, outputFile.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        }
    }
}
