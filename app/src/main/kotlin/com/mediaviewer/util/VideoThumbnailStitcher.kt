package com.mediaviewer.util

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.effect.Presentation
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
        // The video's shape as it's actually shown (rotation applied), and a
        // copy of the thumbnail centre-cropped and scaled to exactly that —
        // so a square (or any other shape) picture fits the video instead of
        // making the export fail or the whole video take the picture's shape.
        val (videoW, videoH) = withContext(Dispatchers.IO) { displaySize(context, videoUri) }
            ?: error("Couldn't read the video's size")
        val fittedThumb = withContext(Dispatchers.IO) { fitThumbnail(context, thumbnailUri, videoW, videoH) }
            ?: error("Couldn't read the thumbnail image")
        val outputFile = withContext(Dispatchers.IO) {
            File.createTempFile("stitched-", ".mp4", context.cacheDir)
        }
        // Transformer must be created and started on a Looper thread; the
        // caller runs on Dispatchers.IO, so hop to Main for the export and
        // suspend until the listener fires.
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                // Both clips are drawn at the video's own size, so the whole
                // export keeps the video's resolution and shape.
                fun sized() = Effects(
                    emptyList(),
                    listOf(Presentation.createForWidthAndHeight(videoW, videoH, Presentation.LAYOUT_SCALE_TO_FIT))
                )
                val imageItem = EditedMediaItem.Builder(
                    MediaItem.Builder()
                        .setUri(Uri.fromFile(fittedThumb))
                        .setImageDurationMs(THUMBNAIL_DURATION_MS)
                        .build()
                )
                    // Images have no frame rate of their own — without one
                    // the export fails (which is why custom thumbnails
                    // silently never showed up).
                    .setFrameRate(30)
                    .setEffects(sized())
                    .build()
                val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(videoUri)).setEffects(sized()).build()
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
                        fittedThumb.delete()
                        cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        android.util.Log.e("VideoThumbnailStitcher", "Thumbnail export failed", exportException)
                        fittedThumb.delete()
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

    /** The video's width/height as displayed (rotation applied), even. */
    private fun displaySize(context: Context, uri: Uri): Pair<Int, Int>? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (dw, dh) = if (rot == 90 || rot == 270) h to w else w to h
            (dw / 2 * 2).coerceAtLeast(2) to (dh / 2 * 2).coerceAtLeast(2)
        } catch (e: Exception) {
            android.util.Log.e("VideoThumbnailStitcher", "Reading the video size failed", e)
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** [uri] decoded (EXIF-rotated), centre-cropped to [w]:[h] and scaled
     *  to exactly [w]×[h], saved as a JPEG in the cache. */
    private fun fitThumbnail(context: Context, uri: Uri, w: Int, h: Int): File? = runCatching {
        val decoded: android.graphics.Bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                // Decode no bigger than needed (keeps a 50 MP photo cheap).
                val scale = maxOf(w.toFloat() / info.size.width, h.toFloat() / info.size.height).coerceAtMost(1f)
                if (scale < 1f) decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                ?: error("undecodable image")
        }
        val target = w.toFloat() / h
        val srcW = decoded.width; val srcH = decoded.height
        val (cropW, cropH) = if (srcW.toFloat() / srcH > target) (srcH * target).toInt() to srcH else srcW to (srcW / target).toInt()
        val left = (srcW - cropW) / 2; val top = (srcH - cropH) / 2
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(
            decoded,
            android.graphics.Rect(left, top, left + cropW.coerceAtLeast(1), top + cropH.coerceAtLeast(1)),
            android.graphics.Rect(0, 0, w, h),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        decoded.recycle()
        val file = File.createTempFile("thumb-", ".jpg", context.cacheDir)
        file.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
        out.recycle()
        file
    }.onFailure { android.util.Log.e("VideoThumbnailStitcher", "Preparing the thumbnail failed", it) }.getOrNull()
}
