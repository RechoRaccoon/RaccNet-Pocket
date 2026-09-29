package com.mediaviewer.platform

import platform.Foundation.*
import platform.AVFoundation.*
import platform.CoreGraphics.*
import platform.UIKit.*
import com.mediaviewer.network.IosFileStreamSource
import com.mediaviewer.network.RequestBody
import com.mediaviewer.network.streamRequestBody
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents

@OptIn(ExperimentalForeignApi::class)
actual object MediaBridge {
    /** Local filesystem path of a file:// PlatformUri. */
    fun pathOf(uri: PlatformUri): String {
        val s = uri.toString()
        if (!s.startsWith("file:")) return s
        return NSURL.URLWithString(s)?.path ?: s.removePrefix("file://")
    }

    actual fun readBytes(context: PlatformContext, uri: PlatformUri): ByteArray? = readLocalFile(pathOf(uri))

    actual fun mimeTypeOf(context: PlatformContext, uri: PlatformUri): String? =
        when (pathOf(uri).substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> null
        }

    private fun decode(bytes: ByteArray): UIImage? = UIImage.imageWithData(bytes.toNSData())

    /** Pixel size (points × scale). */
    private fun UIImage.pixelSize(): Pair<Int, Int> =
        size.useContents { (width * scale).toInt() to (height * scale).toInt() }

    actual fun imageSize(bytes: ByteArray): Pair<Int, Int> = decode(bytes)?.pixelSize() ?: (-1 to -1)

    /** Redraws [image] at [w]×[h] pixels (also applies its orientation). */
    private fun redraw(image: UIImage, w: Int, h: Int): UIImage {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(w.toDouble(), h.toDouble()), false, 1.0)
        image.drawInRect(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
        val out = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return out ?: image
    }

    private fun jpeg(image: UIImage, quality: Int): ByteArray =
        UIImageJPEGRepresentation(image, quality / 100.0)?.toByteArray() ?: error("Couldn't encode image")

    actual fun prepareImageForUpload(context: PlatformContext, uri: PlatformUri, maxBytes: Int, maxDimension: Int): Pair<ByteArray, String> {
        val original = readBytes(context, uri) ?: error("Couldn't read image")
        val declaredType = mimeTypeOf(context, uri)
        if (original.size <= maxBytes && declaredType != null &&
            (declaredType == "image/jpeg" || declaredType == "image/png" || declaredType == "image/webp" || declaredType == "image/gif")) {
            val (w, h) = imageSize(original)
            if (w in 1..maxDimension && h in 1..maxDimension) return original to declaredType
        }
        val image = decode(original) ?: error("Couldn't decode image")
        var (w, h) = image.pixelSize()
        if (w > maxDimension || h > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(w, h)
            w = (w * scale).toInt().coerceAtLeast(1); h = (h * scale).toInt().coerceAtLeast(1)
        }
        val drawn = redraw(image, w, h)
        var quality = 92
        var out = jpeg(drawn, quality)
        while (out.size > maxBytes && quality > 30) {
            quality -= 12
            out = jpeg(drawn, quality)
        }
        return out to "image/jpeg"
    }

    actual fun scaledJpeg(context: PlatformContext, uri: PlatformUri, maxW: Int, maxH: Int, maxBytes: Int): ByteArray {
        val original = readBytes(context, uri) ?: error("Couldn't read the picture")
        val image = decode(original) ?: error("Couldn't decode the picture")
        val (w0, h0) = image.pixelSize()
        val scale = minOf(1f, maxW.toFloat() / w0, maxH.toFloat() / h0)
        val w = (w0 * scale).toInt().coerceAtLeast(1)
        val h = (h0 * scale).toInt().coerceAtLeast(1)
        val drawn = redraw(image, w, h)
        var quality = 90
        var bytes: ByteArray
        do {
            bytes = jpeg(drawn, quality)
            quality -= 10
        } while (bytes.size > maxBytes && quality > 20)
        return bytes
    }

    actual fun encodePng(bitmap: PlatformBitmap): ByteArray = bitmap.pngBytes
    actual fun bitmapWidth(bitmap: PlatformBitmap): Int = bitmap.width
    actual fun bitmapHeight(bitmap: PlatformBitmap): Int = bitmap.height

    actual fun videoDimensions(context: PlatformContext, uri: PlatformUri): Pair<Int, Int> = runCatching {
        val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(pathOf(uri)), null)
        val track = asset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack ?: return@runCatching 0 to 0
        val (w, h) = track.naturalSize.useContents { width.toInt() to height.toInt() }
        val rotated = track.preferredTransform.useContents { b != 0.0 || c != 0.0 }
        if (rotated) h to w else w to h
    }.getOrDefault(0 to 0)

    actual fun videoUploadBody(context: PlatformContext, uri: PlatformUri, mimeType: String): RequestBody {
        val path = pathOf(uri)
        return streamRequestBody(mimeType, IosFileStreamSource(path, -1L))
    }

    actual suspend fun stitchVideoThumbnail(context: PlatformContext, video: PlatformUri, thumbnail: PlatformUri): PlatformUri =
        throw UnsupportedOperationException("Custom video thumbnails aren't supported on iOS yet")
}
