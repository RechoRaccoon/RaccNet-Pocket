package com.mediaviewer.worker

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
    return Triple(url, "simpleOSFeed_${postId}_${com.mediaviewer.platform.currentTimeMillis()}.$ext", mimeType)
}
