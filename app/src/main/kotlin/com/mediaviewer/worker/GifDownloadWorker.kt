package com.mediaviewer.worker

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.work.*
import com.mediaviewer.network.NetworkClient
import com.mediaviewer.util.GifEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Downloads the source media (image or video) and re-encodes it as a GIF at
 * full original resolution, every extracted frame, with no additional
 * downscaling — per the "100% full quality, no compression" requirement.
 * Note: GIF is inherently a 256-color-per-frame format; that's a property of
 * the format itself, not extra lossy compression this worker applies.
 *
 * For video sources this samples frames every 100ms up to a 20s cap (~200
 * frames) to keep processing time on-device reasonable; images become a
 * single-frame GIF at their full original resolution.
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
        const val FOLDER_NAME  = "SimpleOSFeed"
        private const val FRAME_INTERVAL_US = 100_000L   // 100ms between frames
        private const val MAX_CAPTURE_US    = 20_000_000L // cap at 20s of source video

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
                saveGif(encodeVideoAsGif(sourceUrl), postId)
            } else {
                // A GIF is fundamentally limited to a 256-color palette per frame —
                // saving the original bytes verbatim under a .gif extension produced
                // a file that shows a thumbnail (which sniffs real bytes) but fails
                // to open full-screen (viewers that trust the declared GIF type and
                // use a GIF-specific decoder, which then can't parse non-GIF bytes).
                // So this now encodes a genuine GIF, using an exhaustive high-quality
                // palette pass since it's a single one-shot frame (not hundreds like
                // video), which is the best achievable quality within the format.
                saveGif(encodeImageAsGif(url), postId)
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private fun encodeImageAsGif(url: String): ByteArray {
        val response = NetworkClient.downloadClient.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) error("HTTP ${response.code}")
        val bitmap = response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) } ?: error("Decode failed")
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out)
        encoder.start()
        encoder.addFrame(bitmap, highQuality = true)
        encoder.finish()
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun encodeVideoAsGif(url: String): ByteArray {
        // Download the source video to a temp file first — MediaMetadataRetriever
        // frame extraction is far more reliable against a local file than a
        // remote/HLS stream.
        val tmp = File.createTempFile("racc_gif_src", ".mp4", context.cacheDir)
        try {
            val response = NetworkClient.downloadClient.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Empty body")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tmp.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val durationUs = (durationMs * 1000L).coerceAtMost(MAX_CAPTURE_US)

            val out = ByteArrayOutputStream()
            val encoder = GifEncoder(out)
            encoder.setDelay((FRAME_INTERVAL_US / 1000).toInt())
            encoder.start()
            var t = 0L
            var frameCount = 0
            while (t <= durationUs) {
                val frame: Bitmap? = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    encoder.addFrame(frame)
                    frame.recycle()
                    frameCount++
                }
                t += FRAME_INTERVAL_US
            }
            retriever.release()
            if (frameCount == 0) error("No frames extracted")
            encoder.finish()
            return out.toByteArray()
        } finally {
            tmp.delete()
        }
    }

    private fun saveGif(bytes: ByteArray, postId: String) {
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
            resolver.openOutputStream(itemUri)?.use { it.write(bytes) }
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, cv, null, null)
        } catch (e: Exception) { resolver.delete(itemUri, null, null); throw e }
    }
}
