package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext

/** iOS doesn't let an app hear other apps' audio: the bars stay flat. */
actual object AudioVisualizerEngine {
    private val flat = FloatArray(28)
    actual val levels: FloatArray get() = flat
    actual val status: String get() = "Not available on iOS"
    actual fun hasPermission(context: PlatformContext): Boolean = false
    actual fun acquire(context: PlatformContext) {}
    actual fun release() {}
}
