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
 * ffmpeg (see `_process_video` in raccnet_server.py).
 *
 * Implemented with Media3 Transformer: the thumbnail is fed as an image
 * [MediaItem] with a fixed display duration, concatenated in front of the
 * real video via [EditedMediaItemSequence], with
 * `experimentalSetForceAudioTrack` so the image-only first item doesn't
 * drop the video's audio track. The export always re-muxes to mp4 —
 * callers (see `BlueskyRepository.createVideoPost`) already account for
 * that when picking the upload MIME type.
 *
 * Requires media3-transformer 1.8.0+ (hence compileSdk 35 / AGP 8.5.2 /
 * Gradle 8.7 — see the root and app build files).
 */
object VideoThumbnailStitcher {
    /** How long the spliced-in thumbnail stays on screen. One second
     *  guarantees frame 0 is the thumbnail on every player. */
    private const val THUMBNAIL_DURATION_MS = 1000L

    /** Returns a new mp4 [Uri] (in the app cache dir) with [thumbnailUri]
     *  as its first frame, or [videoUri] unchanged when [thumbnailUri] is
     *  null. Throws on export failure — the caller falls back to the
     *  original video (a missing thumbnail beats a failed post). */
    suspend fun stitch(context: Context, videoUri: Uri, thumbnailUri: Uri?): Uri {
        if (thumbnailUri == null) return videoUri
        val outputFile = withContext(Dispatchers.IO) {
            File.createTempFile("stitched-", ".mp4", context.cacheDir)
        }
        // Transformer must be created and started on a Looper thread; the
        // caller runs on Dispatchers.IO, so hop to Main for the export and
        // suspend until the listener fires.
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val imageItem = EditedMediaItem.Builder(
                    MediaItem.Builder()
                        .setUri(thumbnailUri)
                        .setImageDurationMs(THUMBNAIL_DURATION_MS)
                        .build()
                ).build()
                val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(videoUri)).build()
                val sequence = EditedMediaItemSequence.Builder(imageItem, videoItem)
                    .experimentalSetForceAudioTrack(true)
                    .build()
                val composition = Composition.Builder(sequence).build()
                val transformer = Transformer.Builder(context.applicationContext).build()
                val listener = object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        cont.resumeWithException(exportException)
                    }
                }
                transformer.addListener(listener)
                cont.invokeOnCancellation {
                    transformer.removeListener(listener)
                    transformer.cancel()
                }
                transformer.start(composition, outputFile.absolutePath)
            }
        }
        return Uri.fromFile(outputFile)
    }
}
