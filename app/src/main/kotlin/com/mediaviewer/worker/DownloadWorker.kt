package com.mediaviewer.worker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.work.*
import com.mediaviewer.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class DownloadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URL       = "url"
        const val KEY_FILENAME  = "filename"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_POST_ID   = "post_id"
        // Bug fix: for a Bluesky video, KEY_URL alone (the HLS playlist) isn't a
        // downloadable file — see BlueskyBlobResolver. When these are present,
        // doWork() resolves the real video blob URL instead of using KEY_URL.
        const val KEY_BLOB_DID  = "blob_did"
        const val KEY_BLOB_CID  = "blob_cid"
        // ALL downloads (images + videos) go to the same DCIM folder
        const val FOLDER_NAME   = "SimpleOSFeed"

        fun enqueue(context: Context, url: String, filename: String, mimeType: String, postId: String = "") {
            if (isAlreadyDownloaded(context, postId)) return
            val data = workDataOf(KEY_URL to url, KEY_FILENAME to filename, KEY_MIME_TYPE to mimeType, KEY_POST_ID to postId)
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("dl_$postId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("dl_$postId", ExistingWorkPolicy.KEEP, request)
        }

        /** Downloads the real original video blob for a Bluesky video post
         *  (see BlueskyBlobResolver) instead of its HLS playlist URL. */
        fun enqueueVideoBlob(context: Context, did: String, cid: String, postId: String) {
            if (isAlreadyDownloaded(context, postId)) return
            val filename = "simpleOSFeed_${postId}_${System.currentTimeMillis()}.mp4"
            val data = workDataOf(
                KEY_BLOB_DID to did, KEY_BLOB_CID to cid,
                KEY_FILENAME to filename, KEY_MIME_TYPE to "video/mp4", KEY_POST_ID to postId
            )
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("dl_$postId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("dl_$postId", ExistingWorkPolicy.KEEP, request)
        }

        fun isAlreadyDownloaded(context: Context, postId: String): Boolean {
            if (postId.isBlank()) return false
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("simpleOSFeed_${postId}_%")
            // Check both image and video stores
            return listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).any { uri ->
                context.contentResolver
                    .query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                    ?.use { it.count > 0 } ?: false
            }
        }
    }

    override suspend fun doWork(): Result {
        val filename = inputData.getString(KEY_FILENAME)  ?: return Result.failure()
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "image/jpeg"
        val postId   = inputData.getString(KEY_POST_ID)   ?: ""
        val blobDid  = inputData.getString(KEY_BLOB_DID)
        val blobCid  = inputData.getString(KEY_BLOB_CID)
        if (isAlreadyDownloaded(context, postId)) return Result.success()
        return withContext(Dispatchers.IO) {
            try {
                val url = if (!blobDid.isNullOrBlank() && !blobCid.isNullOrBlank())
                    BlueskyBlobResolver.resolveBlobUrl(blobDid, blobCid)
                else
                    inputData.getString(KEY_URL) ?: return@withContext Result.failure()
                downloadFile(url, filename, mimeType)
                Result.success()
            }
            catch (e: Exception) { e.printStackTrace(); if (runAttemptCount < 3) Result.retry() else Result.failure() }
        }
    }

    private fun downloadFile(url: String, filename: String, mimeType: String) {
        val response = NetworkClient.downloadClient.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) error("HTTP ${response.code}")
        val body = response.body ?: error("Empty body")

        val isVideo = mimeType.startsWith("video")

        // Both images AND videos go to DCIM/SimpleOSFeed — same folder in gallery
        val (collection, relPath) = if (isVideo)
            Pair(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DCIM + "/$FOLDER_NAME")
        else
            Pair(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DCIM + "/$FOLDER_NAME")

        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val itemUri: Uri = resolver.insert(collection, cv) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(itemUri)?.use { out -> body.byteStream().copyTo(out) }
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, cv, null, null)
        } catch (e: Exception) { resolver.delete(itemUri, null, null); throw e }
    }
}

fun urlToDownloadInfo(url: String, postId: String, isVideo: Boolean = false): Triple<String, String, String> {
    val rawExt = url.substringAfterLast('.', "jpg").lowercase().substringBefore('?')
    // When the caller already knows this is a video (e.g. a Bluesky HLS
    // playlist URL that doesn't end in .mp4), don't trust the URL's
    // extension — force a real video mimetype/extension so it saves as a
    // playable video instead of being mis-typed as an image.
    val ext = if (isVideo && rawExt !in listOf("mp4", "webm")) "mp4" else rawExt
    val mimeType = when {
        isVideo || ext == "mp4"  -> "video/mp4"
        ext == "webm"            -> "video/webm"
        ext == "jpg" || ext == "jpeg" -> "image/jpeg"
        ext == "png"              -> "image/png"
        ext == "gif"               -> "image/gif"
        ext == "webp"              -> "image/webp"
        else -> "image/jpeg"
    }
    return Triple(url, "simpleOSFeed_${postId}_${System.currentTimeMillis()}.$ext", mimeType)
}
