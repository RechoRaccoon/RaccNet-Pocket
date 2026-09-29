package com.mediaviewer.platform

/**
 * Features that only exist on Android. On iOS they stay visible in the UI
 * but dimmed, not clickable, and marked with an "Android only" tag (see
 * ui/AndroidOnly.kt) — never hidden, never removed from Android.
 */
enum class PlatformFeature(val label: String) {
    AI_TAGGING("AI Tagging"),
    AUDIO_VISUALIZER("Audio visualizer"),
    LIVE_STREAMING("Live streaming"),
    VRM_MODE("VRM mode"),
    GIF_EXPORT("Save as GIF"),
    CUSTOM_FONT("Custom font"),
    OPEN_BY_DEFAULT_LINKS("Open Bluesky links in Stellar"),
    APP_RESTART("Restart app");

    /** True where this feature works on the current platform. */
    val isAvailable: Boolean get() = currentPlatform == PlatformKind.ANDROID
}
