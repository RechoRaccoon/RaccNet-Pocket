package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext

/**
 * The feed's audio visualizer source: bar levels (0..1) that move to
 * whatever music the phone is playing. Android listens to the audio
 * output; iOS can't hear other apps' audio at all, so there it stays flat
 * (and the setting is shown as Android only).
 */
expect object AudioVisualizerEngine {
    /** Current bar heights, 0..1 — Compose state (read it while drawing). */
    val levels: FloatArray

    /** Plain-language state for the Settings row ("" = never ran). */
    val status: String

    fun hasPermission(context: PlatformContext): Boolean

    /** Main thread. Starts listening while at least one caller holds it. */
    fun acquire(context: PlatformContext)

    /** Main thread. */
    fun release()
}
