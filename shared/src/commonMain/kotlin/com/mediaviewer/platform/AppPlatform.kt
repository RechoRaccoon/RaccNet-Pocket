package com.mediaviewer.platform

import com.mediaviewer.model.LiveNowPlatform
import com.mediaviewer.tagging.TaggingService

/**
 * What the shared ViewModel needs from the operating system. Android's
 * implementation (AndroidAppPlatform) is the code MainViewModel used to run
 * inline through its Application context; iOS has its own, and features iOS
 * can't do (Live Link widget, AI tagging, …) report themselves unavailable
 * there instead of being removed from Android.
 */
interface AppPlatform {
    val context: PlatformContext
    val kind: PlatformKind get() = currentPlatform

    /** AI Tagging (UnavailableTaggingService where there's no tagger). */
    val tagging: TaggingService

    /** A short tick of haptic feedback. */
    fun haptic()

    /** A brief on-screen message (Toast on Android). Safe from any thread. */
    fun toast(message: String)

    /** Cold-restarts the app into the (already persisted) new account.
     *  Android relaunches the process; returns only if that couldn't start. */
    fun restartApp()

    /** Warms the image cache for [urls] (list avatars, …). */
    fun preloadImages(urls: List<String>)

    /** Saves a file to the gallery in the background. */
    fun enqueueDownload(url: String, filename: String, mimeType: String, postId: String)

    /** Saves a Bluesky video's original blob to the gallery. */
    fun enqueueVideoBlobDownload(did: String, cid: String, postId: String)

    /** Saves an image or video as an animated GIF. */
    fun enqueueGifDownload(url: String, isVideo: Boolean, postId: String, blobDid: String? = null, blobCid: String? = null)

    /** Copies a picked font file into the app's own storage. */
    fun importCustomFont(uri: PlatformUri): FontImport

    /** Deletes a file the app owns (old custom fonts). */
    fun deleteFile(path: String)

    /** Writes [text] to a user-picked destination (dataset export). Throws
     *  IOException when it can't be written. */
    fun writeTextToUri(uri: PlatformUri, text: String)

    /** Reads a user-picked file as text (dataset import), or null. */
    fun readTextFromUri(uri: PlatformUri): String?

    /** Renders Textshot [text] (with custom emoji) to an image. */
    suspend fun renderTextshot(text: String): TextshotImage

    /** Live Link: sets the Bluesky live status + background checks. */
    suspend fun goLive(platform: LiveNowPlatform, channelUrl: String): Result<Unit>
    suspend fun endLive(): Result<Unit>

    /** Asks the launcher to pin the Live Link widget; false if it can't. */
    fun requestPinLiveLinkWidget(): Boolean
}

sealed class FontImport {
    data class Success(val path: String, val displayName: String) : FontImport()
    data class Error(val message: String) : FontImport()
}

/** A rendered Textshot plus what the post needs to say about it. */
class TextshotImage(val bitmap: PlatformBitmap, val altText: String, val hasEmoji: Boolean)
