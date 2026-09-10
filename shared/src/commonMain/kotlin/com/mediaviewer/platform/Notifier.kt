package com.mediaviewer.platform

/**
 * Local (non-push) notifications. The legacy app only ever used these for
 * download-progress notifications — there is no FCM / push anywhere.
 *
 * androidMain: NotificationManager progress notifications.
 * wasmJsMain:  no-ops — the web shows download progress in-page instead.
 */
expect class PlatformNotifier() {

    fun showDownloadProgress(id: Int, title: String, progress: Float)

    fun showDownloadComplete(id: Int, title: String, fileName: String)

    fun cancel(id: Int)
}
