package com.mediaviewer.app

import com.mediaviewer.platform.AppPlatform
import com.mediaviewer.platform.FontImport
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.TextshotImage

import android.app.Application
import android.widget.Toast
import coil.imageLoader
import coil.request.ImageRequest
import com.mediaviewer.model.LiveNowPlatform
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.repository.E621Repository
import com.mediaviewer.tagging.TaggingRepository
import com.mediaviewer.tagging.TaggingService
import com.mediaviewer.worker.DownloadWorker
import com.mediaviewer.worker.GifDownloadWorker

/** Android side of [AppPlatform] — the Application-context code
 *  MainViewModel used to run inline, moved here unchanged. */
class AndroidAppPlatform(
    private val application: Application,
    bskyRepo: BlueskyRepository,
    e621Repo: E621Repository,
) : AppPlatform {
    override val context: PlatformContext get() = application

    override val tagging: TaggingService = TaggingRepository.get(application, bskyRepo, e621Repo)

    // Item 8: shared haptic tap, callable from anywhere in the ViewModel
    // (opening a profile, sending a message/comment/post, running a search)
    // without needing a Compose/View context at each call site. Uses the
    // Vibrator system service directly via the Application context.
    override fun haptic() {
        try {
            val context = application
            val vibrator: android.os.Vibrator? =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(android.os.VibratorManager::class.java)
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK))
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(15, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(15)
                }
            }
        } catch (_: Exception) {
            // Haptics are a nicety, never worth crashing over.
        }
    }

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    override fun toast(message: String) {
        val show = { Toast.makeText(application, message, Toast.LENGTH_SHORT).show() }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) show() else mainHandler.post { show() }
    }

    override fun restartApp() {
        com.mediaviewer.RestartActivity.restartApp(application)
    }

    override fun preloadImages(urls: List<String>) {
        val loader = application.imageLoader
        urls.forEach { url -> loader.enqueue(ImageRequest.Builder(application).data(url).build()) }
    }

    override fun enqueueDownload(url: String, filename: String, mimeType: String, postId: String) {
        DownloadWorker.enqueue(application, url, filename, mimeType, postId)
    }

    override fun enqueueVideoBlobDownload(did: String, cid: String, postId: String) {
        DownloadWorker.enqueueVideoBlob(application, did, cid, postId)
    }

    override fun enqueueGifDownload(url: String, isVideo: Boolean, postId: String, blobDid: String?, blobCid: String?) {
        GifDownloadWorker.enqueue(application, url, isVideo, postId, blobDid = blobDid, blobCid = blobCid)
    }

    override fun importCustomFont(uri: PlatformUri): FontImport {
        val context = application
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Custom Font"
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("ttf", "otf", "ttc")) return FontImport.Error("Please choose a .ttf or .otf font file")
        val fontsDir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
        // Item 22: previously always wrote to the same "custom_font.$ext"
        // path. Re-picking a font with the same extension left that path
        // unchanged, and MainActivity's FontFamily is `remember`'d keyed
        // only on the path — so the new file's bytes were saved but the
        // already-cached FontFamily never got rebuilt. A unique name per
        // pick guarantees the path changes every time.
        val destFile = java.io.File(fontsDir, "custom_font_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return FontImport.Error("Couldn't read that font file")
        return FontImport.Success(destFile.absolutePath, displayName)
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            application.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    override fun deleteFile(path: String) {
        runCatching { java.io.File(path).delete() }
    }

    override fun writeTextToUri(uri: PlatformUri, text: String) {
        application.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw java.io.IOException("Couldn't open the chosen file for writing")
    }

    override fun readTextFromUri(uri: PlatformUri): String? =
        application.contentResolver.openInputStream(uri)?.use { it.reader(Charsets.UTF_8).readText() }

    override suspend fun renderTextshot(text: String): TextshotImage {
        val emoji = com.mediaviewer.util.EmojiStore.get(application)
        emoji.load()
        val hasEmoji = emoji.containsEmoji(text)
        val bitmap = com.mediaviewer.util.TextshotRenderer.render(text, emojiBitmap = emoji::bitmapForChar)
        // Alt text never carries a custom emoji's name — just drop the
        // token entirely rather than exposing it as a `:name:` shortcode.
        val altText = emoji.stripEmoji(text)
        return TextshotImage(bitmap, altText, hasEmoji)
    }

    override suspend fun goLive(platform: LiveNowPlatform, channelUrl: String): Result<Unit> =
        com.mediaviewer.util.LiveLinkManager.goLive(application, platform, channelUrl)

    override suspend fun endLive(): Result<Unit> =
        com.mediaviewer.util.LiveLinkManager.endLive(application)

    override fun requestPinLiveLinkWidget(): Boolean {
        val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(application)
        val provider = android.content.ComponentName(application, com.mediaviewer.widget.LiveLinkWidgetProvider::class.java)
        return if (appWidgetManager.isRequestPinAppWidgetSupported) {
            appWidgetManager.requestPinAppWidget(provider, null, null)
            true
        } else false
    }
}
