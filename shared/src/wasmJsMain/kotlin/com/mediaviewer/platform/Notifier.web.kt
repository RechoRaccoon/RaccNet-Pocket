package com.mediaviewer.platform

/**
 * Web actual: no-ops. The web has no notification surface for downloads —
 * progress is shown in-page by the download manager UI instead.
 */
actual class PlatformNotifier actual constructor() {
    actual fun showDownloadProgress(id: Int, title: String, progress: Float) = Unit
    actual fun showDownloadComplete(id: Int, title: String, fileName: String) = Unit
    actual fun cancel(id: Int) = Unit
}
