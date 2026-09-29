package com.mediaviewer.platform

import com.mediaviewer.network.RequestBody

/**
 * Image/video work the upload code needs from the platform: reading a
 * picked file, measuring and re-encoding pictures, probing videos, and
 * streaming a video as an upload body. The Android implementation is the
 * code that used to live inline in BlueskyRepository (BitmapFactory,
 * ContentResolver, MediaMetadataRetriever), moved here unchanged.
 */
expect object MediaBridge {
    /** The picked file's bytes, or null if it can't be read. */
    fun readBytes(context: PlatformContext, uri: PlatformUri): ByteArray?

    /** The picked file's MIME type as the system reports it, if known. */
    fun mimeTypeOf(context: PlatformContext, uri: PlatformUri): String?

    /** Pixel size of encoded image [bytes] without decoding them fully;
     *  (-1, -1) when they can't be read (BitmapFactory's outWidth/outHeight). */
    fun imageSize(bytes: ByteArray): Pair<Int, Int>

    /** A post image ready for uploadBlob: the original bytes when already
     *  within [maxBytes] and [maxDimension] and in a format Bluesky takes
     *  as-is, else downscaled and JPEG re-encoded under the limits. */
    fun prepareImageForUpload(context: PlatformContext, uri: PlatformUri, maxBytes: Int, maxDimension: Int): Pair<ByteArray, String>

    /** Avatar/banner: scaled to fit [maxW]×[maxH], JPEG-compressed under
     *  [maxBytes] (quality 90 down in steps of 10, not below 20). */
    fun scaledJpeg(context: PlatformContext, uri: PlatformUri, maxW: Int, maxH: Int, maxBytes: Int): ByteArray

    /** PNG bytes of a rendered image (Textshot). */
    fun encodePng(bitmap: PlatformBitmap): ByteArray
    fun bitmapWidth(bitmap: PlatformBitmap): Int
    fun bitmapHeight(bitmap: PlatformBitmap): Int

    /** Displayed (width, height) of a video, or (0, 0) if unknown. */
    fun videoDimensions(context: PlatformContext, uri: PlatformUri): Pair<Int, Int>

    /** The video as an upload body, streamed from disk where possible. */
    fun videoUploadBody(context: PlatformContext, uri: PlatformUri, mimeType: String): RequestBody

    /** Splices [thumbnail] in as the video's first frame (so Bluesky shows
     *  it as the thumbnail); returns the new file. Throws if unsupported. */
    suspend fun stitchVideoThumbnail(context: PlatformContext, video: PlatformUri, thumbnail: PlatformUri): PlatformUri
}
