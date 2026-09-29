package com.mediaviewer.worker

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.*
import com.mediaviewer.network.NetworkClient
import com.mediaviewer.util.GifEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Downloads the source media (image or video) and re-encodes it as a GIF at
 * full original resolution, every extracted frame, with no additional
 * downscaling — per the "100% full quality, no compression" requirement.
 * Note: GIF is inherently a 256-color-per-frame format; that's a property of
 * the format itself, not extra lossy compression this worker applies.
 *
 * Video sources keep every frame at their own frame rate (up to 50 fps, the
 * fastest GIF players honor) for up to 30 s; images become a single-frame
 * GIF at their full original resolution — from the original upload when it
 * can be found — and a source that's already a GIF is saved untouched.
 */
class GifDownloadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URL      = "url"
        const val KEY_IS_VIDEO = "is_video"
        const val KEY_POST_ID  = "post_id"
        // Same bug/fix as DownloadWorker: a Bluesky video's KEY_URL (HLS
        // playlist) isn't itself a downloadable file — resolve the real blob.
        const val KEY_BLOB_DID = "blob_did"
        const val KEY_BLOB_CID = "blob_cid"
        const val FOLDER_NAME  = "Stellar"
        private const val MAX_CAPTURE_US    = 30_000_000L // cap at 30s of source video
        private const val MAX_GIF_FPS       = 50.0        // 2/100 s — the fastest delay GIF viewers honor
        private const val BATCH             = 4           // frames decoded per getFramesAtIndex call

        fun enqueue(
            context: Context, url: String, isVideo: Boolean, postId: String = "",
            blobDid: String? = null, blobCid: String? = null
        ) {
            val data = workDataOf(
                KEY_URL to url, KEY_IS_VIDEO to isVideo, KEY_POST_ID to postId,
                KEY_BLOB_DID to blobDid, KEY_BLOB_CID to blobCid
            )
            val request = OneTimeWorkRequestBuilder<GifDownloadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("gif_$postId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("gif_$postId", ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url     = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val isVideo = inputData.getBoolean(KEY_IS_VIDEO, false)
        val postId  = inputData.getString(KEY_POST_ID) ?: ""
        val blobDid = inputData.getString(KEY_BLOB_DID)
        val blobCid = inputData.getString(KEY_BLOB_CID)
        try {
            if (isVideo) {
                val sourceUrl = if (!blobDid.isNullOrBlank() && !blobCid.isNullOrBlank())
                    BlueskyBlobResolver.resolveBlobUrl(blobDid, blobCid)
                else url
                encodeVideoAsGif(sourceUrl, postId)
            } else {
                encodeImageAsGif(url, postId)
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    /** The original upload for a Bluesky CDN image URL
     *  (…/img/<preset>/plain/<did>/<cid>@jpeg) — the CDN's own copy is a
     *  re-compressed JPEG; the blob is exactly what was posted. */
    private fun originalBlobUrl(url: String): String? {
        val m = Regex("/plain/(did:[^/]+)/([^/@?]+)").find(url) ?: return null
        return runCatching { kotlinx.coroutines.runBlocking { BlueskyBlobResolver.resolveBlobUrl(m.groupValues[1], m.groupValues[2]) } }.getOrNull()
    }

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        com.mediaviewer.network.AndroidHttpClients.downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    }.getOrNull()

    private fun encodeImageAsGif(url: String, postId: String) {
        // Best available source: the original upload, else the URL we were given.
        val bytes = originalBlobUrl(url)?.let { fetchBytes(it) }?.takeIf { it.isNotEmpty() }
            ?: fetchBytes(url) ?: error("Download failed")
        // Already a GIF (e621 and others host real GIFs): saved byte for
        // byte — every frame, every color, exactly as posted.
        if (bytes.size > 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
            saveGif(postId) { out -> out.write(bytes) }
            return
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inSampleSize = 1
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("Decode failed")
        try {
            saveGif(postId) { out ->
                val encoder = GifEncoder(out)
                encoder.start()
                // Full-resolution, exact colors when there are ≤256 of them,
                // otherwise a per-image palette + full-strength dithering.
                encoder.addFrame(bitmap, delayCs = 10, ditherStrength = 1f)
                encoder.finish()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeVideoAsGif(url: String, postId: String) {
        // Download the source video to a temp file first — MediaMetadataRetriever
        // frame extraction is far more reliable against a local file than a
        // remote/HLS stream.
        val tmp = File.createTempFile("racc_gif_src", ".mp4", context.cacheDir)
        try {
            com.mediaviewer.network.AndroidHttpClients.downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty body")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tmp.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (durationMs <= 0L) error("Unknown video length")
                val captureMs = durationMs.coerceAtMost(MAX_CAPTURE_US / 1000L)
                val totalFrames = if (Build.VERSION.SDK_INT >= 28)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                else 0
                // The source's real frame rate, kept as-is up to 50 fps (the
                // fastest rate GIF viewers actually play — a 1/100 s delay
                // below 2 is slowed down to 10 fps by most of them).
                val sourceFps = when {
                    totalFrames > 0 -> totalFrames * 1000.0 / durationMs
                    else -> retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 30.0
                }.coerceIn(1.0, 240.0)
                val stride = kotlin.math.ceil(sourceFps / MAX_GIF_FPS).toInt().coerceAtLeast(1)
                val gifFps = sourceFps / stride

                var written = 0
                saveGif(postId) { out ->
                    val encoder = GifEncoder(out)
                    encoder.start()
                    // Delays land on the 1/100 s grid without drifting: each
                    // frame ends at round(n * 100 / fps).
                    fun addTimed(frame: Bitmap) {
                        val startCs = Math.round(written * 100.0 / gifFps)
                        val endCs = Math.round((written + 1) * 100.0 / gifFps)
                        encoder.addFrame(frame, delayCs = (endCs - startCs).toInt().coerceAtLeast(2), ditherStrength = 0.85f)
                        written++
                    }
                    if (Build.VERSION.SDK_INT >= 28 && totalFrames > 0) {
                        // Every source frame, decoded in order (much faster
                        // and exact, unlike seeking by timestamp).
                        val lastIndex = (Math.round(captureMs / 1000.0 * sourceFps).toInt()).coerceIn(1, totalFrames) - 1
                        var index = 0
                        while (index <= lastIndex) {
                            val batch = minOf(BATCH, lastIndex - index + 1)
                            val frames = runCatching { retriever.getFramesAtIndex(index, batch) }.getOrNull()
                            if (frames.isNullOrEmpty()) {
                                // Some decoders refuse batches: fall back to one at a time.
                                val single = runCatching { retriever.getFrameAtIndex(index) }.getOrNull()
                                if (single != null) {
                                    if (index % stride == 0) addTimed(single)
                                    single.recycle()
                                }
                                index++
                                continue
                            }
                            frames.forEachIndexed { i, frame ->
                                if ((index + i) % stride == 0) addTimed(frame)
                                frame.recycle()
                            }
                            index += batch
                        }
                    }
                    if (written == 0) {
                        // Older Android: exact-time seeks at the source rate.
                        val stepUs = (1_000_000.0 / gifFps).toLong().coerceAtLeast(20_000L)
                        var t = 0L
                        val endUs = captureMs * 1000L
                        while (t < endUs) {
                            val frame: Bitmap? = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                            if (frame != null) {
                                addTimed(frame)
                                frame.recycle()
                            }
                            t += stepUs
                        }
                    }
                    if (written == 0) error("No frames extracted")
                    encoder.finish()
                }
            } finally {
                runCatching { retriever.release() }
            }
        } finally {
            tmp.delete()
        }
    }

    /** Streams the GIF straight into the gallery (DCIM/Stellar) — no
     *  in-memory copy of the whole file, so long/large GIFs don't run out
     *  of memory. */
    private fun saveGif(postId: String, write: (java.io.OutputStream) -> Unit) {
        val filename = "simpleOSFeed_${postId}_${System.currentTimeMillis()}.gif"
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/$FOLDER_NAME")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val itemUri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: error("MediaStore insert failed")
        try {
            val stream = resolver.openOutputStream(itemUri) ?: error("Couldn't open the gallery file")
            java.io.BufferedOutputStream(stream, 1 shl 16).use { write(it) }
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, cv, null, null)
        } catch (e: Exception) { resolver.delete(itemUri, null, null); throw e }
    }
}
