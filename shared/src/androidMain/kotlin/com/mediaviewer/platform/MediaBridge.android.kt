// check:jvm
package com.mediaviewer.platform

import com.mediaviewer.network.AndroidUriStreamSource
import com.mediaviewer.network.RequestBody
import com.mediaviewer.network.streamRequestBody

actual object MediaBridge {
    actual fun readBytes(context: PlatformContext, uri: PlatformUri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

    actual fun mimeTypeOf(context: PlatformContext, uri: PlatformUri): String? =
        context.contentResolver.getType(uri)
            // Files Stellar keeps itself (drafts, notes): file:// has no
            // provider to ask, so go by the extension.
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
                ?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

    actual fun imageSize(bytes: ByteArray): Pair<Int, Int> {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    actual fun prepareImageForUpload(context: PlatformContext, uri: PlatformUri, maxBytes: Int, maxDimension: Int): Pair<ByteArray, String> {
        val IMAGE_MAX_BLOB_BYTES = maxBytes
        val IMAGE_MAX_DIMENSION = maxDimension
        val resolver = context.contentResolver
        val original = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read image")
        val declaredType = resolver.getType(uri)

        // Fast path: already within both limits and a format Bluesky
        // accepts as-is (jpeg/png/webp/gif) — upload the original bytes
        // untouched rather than a re-encoded copy.
        if (original.size <= IMAGE_MAX_BLOB_BYTES && declaredType != null &&
            (declaredType == "image/jpeg" || declaredType == "image/png" || declaredType == "image/webp" || declaredType == "image/gif")) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
            if (bounds.outWidth <= IMAGE_MAX_DIMENSION && bounds.outHeight <= IMAGE_MAX_DIMENSION) {
                return original to declaredType
            }
        }

        var bitmap = android.graphics.BitmapFactory.decodeByteArray(original, 0, original.size)
            ?: error("Couldn't decode image")
        if (bitmap.width > IMAGE_MAX_DIMENSION || bitmap.height > IMAGE_MAX_DIMENSION) {
            val scale = IMAGE_MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
            bitmap = android.graphics.Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
        }
        var quality = 92
        var out = java.io.ByteArrayOutputStream().apply { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, this) }
        while (out.size() > IMAGE_MAX_BLOB_BYTES && quality > 30) {
            quality -= 12
            out = java.io.ByteArrayOutputStream().apply { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, this) }
        }
        return out.toByteArray() to "image/jpeg"
    }

    actual fun scaledJpeg(context: PlatformContext, uri: PlatformUri, maxW: Int, maxH: Int, maxBytes: Int): ByteArray {
        val original = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the picture")
        var bitmap = android.graphics.BitmapFactory.decodeByteArray(original, 0, original.size) ?: error("Couldn't decode the picture")
        val scale = minOf(1f, maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height)
        if (scale < 1f) {
            bitmap = android.graphics.Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
        }
        var quality = 90
        var bytes: ByteArray
        do {
            bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            quality -= 10
        } while (bytes.size > maxBytes && quality > 20)
        return bytes
    }

    actual fun encodePng(bitmap: PlatformBitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
    actual fun bitmapWidth(bitmap: PlatformBitmap): Int = bitmap.width
    actual fun bitmapHeight(bitmap: PlatformBitmap): Int = bitmap.height

    /** Returns the (width, height) of the video at [uri], swapping the axes
     *  when rotation metadata says the frame is displayed turned 90/270° so
     *  the ratio describes the displayed frame. Returns (0, 0) when probing
     *  fails — callers treat that as "unknown" and omit aspectRatio. */
    actual fun videoDimensions(context: PlatformContext, uri: PlatformUri): Pair<Int, Int> {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            runCatching {
                retriever.setDataSource(context, uri)
                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) h to w else w to h
            }.getOrDefault(0 to 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    actual fun videoUploadBody(context: PlatformContext, uri: PlatformUri, mimeType: String): RequestBody {
        // Streamed from disk instead of read into one byte array — a long
        // video no longer has to fit in memory at once.
        val length = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 } ?: -1L
        return streamRequestBody(mimeType, AndroidUriStreamSource(context, uri, length))
    }

    actual suspend fun stitchVideoThumbnail(context: PlatformContext, video: PlatformUri, thumbnail: PlatformUri): PlatformUri =
        com.mediaviewer.util.VideoThumbnailStitcher.stitch(context, video, thumbnail)
}
