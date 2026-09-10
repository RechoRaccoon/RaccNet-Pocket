package com.mediaviewer.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Download progress/completion notifications. The legacy app never actually
 * had these (its workers were silent) — this is the notification surface the
 * shared DownloadManager drives via [PlatformDownloader]'s progress
 * callbacks. One channel, low importance: progress shouldn't buzz.
 *
 * Note: on Android 13+ the app must hold POST_NOTIFICATIONS at runtime or
 * these are silently dropped; requesting it is the app shell's job.
 */
actual class PlatformNotifier actual constructor() {

    companion object {
        private const val CHANNEL_ID = "raccnet_downloads"
    }

    private val context get() = AndroidAppContext.app

    private fun manager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Download progress and completion" }
        manager().createNotificationChannel(channel)
    }

    actual fun showDownloadProgress(id: Int, title: String, progress: Float) {
        ensureChannel()
        val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (progress > 0f) "$pct%" else "Starting download…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, pct, progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        manager().notify(id, notification)
    }

    actual fun showDownloadComplete(id: Int, title: String, fileName: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .build()
        manager().notify(id, notification)
    }

    actual fun cancel(id: Int) {
        manager().cancel(id)
    }
}
