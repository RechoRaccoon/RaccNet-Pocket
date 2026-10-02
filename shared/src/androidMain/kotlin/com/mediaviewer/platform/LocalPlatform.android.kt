// check:jvm
package com.mediaviewer.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

actual object LocalPlatform {
    private var player: MediaPlayer? = null

    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    actual fun importMedia(context: PlatformContext, uri: PlatformUri, folder: String): PlatformUri? = try {
        val app = context.applicationContext
        val dir = File(File(app.filesDir, "local_media"), folder).apply { mkdirs() }
        // Already one of ours: nothing to copy.
        val ownPath = uri.path
        if (uri.scheme == "file" && ownPath != null && ownPath.startsWith(File(app.filesDir, "local_media").absolutePath)) {
            uri
        } else {
            val type = app.contentResolver.getType(uri)
            val ext = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }
                ?: "bin"
            val out = File(dir, UUID.randomUUID().toString() + "." + ext)
            val input = app.contentResolver.openInputStream(uri)
            if (input == null) null else {
                input.use { ins -> out.outputStream().use { ins.copyTo(it) } }
                Uri.fromFile(out)
            }
        }
    } catch (_: Exception) {
        null
    }

    actual fun parseUri(text: String): PlatformUri = Uri.parse(text)

    actual fun deleteMedia(context: PlatformContext, uri: String) {
        try {
            val parsed = Uri.parse(uri)
            val path = parsed.path ?: return
            val root = File(context.applicationContext.filesDir, "local_media").absolutePath
            if (parsed.scheme == "file" && path.startsWith(root)) File(path).delete()
        } catch (_: Exception) {
        }
    }

    actual fun setBatterySaver(context: PlatformContext, on: Boolean) {
        val activity = activityOf(context) ?: return
        try {
            val attrs = activity.window.attributes
            // 0 = "no preference" (the display's own choice).
            attrs.preferredRefreshRate = if (on) 60f else 0f
            activity.window.attributes = attrs
        } catch (_: Exception) {
        }
    }

    actual fun playLoopingWav(context: PlatformContext, wav: ByteArray) {
        stopSound()
        try {
            val file = File(context.applicationContext.cacheDir, "stellar_timer_alarm.wav")
            file.writeBytes(wav)
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            p.setDataSource(file.absolutePath)
            p.isLooping = true
            p.prepare()
            p.start()
            player = p
        } catch (_: Exception) {
            player = null
        }
    }

    actual fun stopSound() {
        val p = player ?: return
        player = null
        try { p.stop() } catch (_: Exception) {}
        try { p.release() } catch (_: Exception) {}
    }

    actual fun syncNotifications(context: PlatformContext, requestPermission: Boolean) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(com.mediaviewer.util.LocalData.PREFS, Context.MODE_PRIVATE)
        val wanted = prefs.getBoolean(com.mediaviewer.util.LocalData.KEY_NOTIFY_DMS, false) ||
            prefs.getBoolean(com.mediaviewer.util.LocalData.KEY_NOTIFY_INBOX, false)
        if (wanted) {
            if (requestPermission && Build.VERSION.SDK_INT >= 33) {
                val activity = activityOf(context)
                if (activity != null && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    try { activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7341) } catch (_: Exception) {}
                }
            }
            com.mediaviewer.worker.StellarNotificationScheduler.schedule(app)
        } else {
            com.mediaviewer.worker.StellarNotificationScheduler.cancel(app)
        }
    }
}
