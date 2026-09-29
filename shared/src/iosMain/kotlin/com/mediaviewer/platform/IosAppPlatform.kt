package com.mediaviewer.platform

import com.mediaviewer.model.LiveNowPlatform
import com.mediaviewer.tagging.TaggingService
import com.mediaviewer.tagging.UnavailableTaggingService

/** iOS side of [AppPlatform]. Features iOS can't do yet report themselves
 *  unavailable (Android keeps all of them). */
class IosAppPlatform : AppPlatform {
    override val context: PlatformContext get() = IosContext
    override val tagging: TaggingService = UnavailableTaggingService

    override fun haptic() = IosHaptics.tick()

    override fun toast(message: String) = AppEvents.postMessage(message)

    override fun restartApp() = AppEvents.requestRestart()

    override fun preloadImages(urls: List<String>) {
        IosImagePreloader.preload?.invoke(urls)
    }

    override fun enqueueDownload(url: String, filename: String, mimeType: String, postId: String) {
        IosDownloads.save(url, filename, mimeType)
    }

    override fun enqueueVideoBlobDownload(did: String, cid: String, postId: String) {
        IosDownloads.saveVideoBlob(did, cid)
    }

    override fun enqueueGifDownload(url: String, isVideo: Boolean, postId: String, blobDid: String?, blobCid: String?) {
        toast("Saving as GIF isn't available on iOS yet")
    }

    override fun importCustomFont(uri: PlatformUri): FontImport =
        FontImport.Error("Custom fonts aren't available on iOS yet")

    override fun deleteFile(path: String) { platform.posix.remove(path) }

    override fun writeTextToUri(uri: PlatformUri, text: String) {
        val path = uri.toString().removePrefix("file://")
        if (!writeLocalFile(path, text.encodeToByteArray())) throw IOException("Couldn't write the file")
    }

    override fun readTextFromUri(uri: PlatformUri): String? =
        readLocalFile(uri.toString().removePrefix("file://"))?.decodeToString()

    override suspend fun renderTextshot(text: String): TextshotImage =
        IosTextshot.render?.invoke(text) ?: throw UnsupportedOperationException("Textshot isn't available on iOS yet")

    override suspend fun goLive(platform: LiveNowPlatform, channelUrl: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Live Link isn't available on iOS yet"))

    override suspend fun endLive(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Live Link isn't available on iOS yet"))

    override fun requestPinLiveLinkWidget(): Boolean = false
}

/** Filled in by the iOS UI layer once its image loader exists. */
object IosImagePreloader { var preload: ((List<String>) -> Unit)? = null }

/** Filled in by the iOS UI layer (Textshot rendering needs its text engine). */
object IosTextshot { var render: (suspend (String) -> TextshotImage)? = null }
