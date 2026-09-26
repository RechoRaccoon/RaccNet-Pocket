package com.mediaviewer.util

import android.content.Context

/**
 * VRM mode's settings, remembered across launches. Plain SharedPreferences:
 * read once when the screen opens, written only when a value changes.
 */
class VrmSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vrm_settings", Context.MODE_PRIVATE)

    fun bool(key: String, default: Boolean) = prefs.getBoolean(key, default)
    fun float(key: String, default: Float) = prefs.getFloat(key, default)
    fun int(key: String, default: Int) = prefs.getInt(key, default)
    fun strings(key: String): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    fun string(key: String, default: String = ""): String = prefs.getString(key, default) ?: default

    fun put(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                is Int -> putInt(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> return
            }
        }.apply()
    }

    companion object {
        const val UPPER_BODY = "upper_body"
        const val FULL_BODY = "full_body"
        const val FOLLOW_HEAD = "follow_head"
        const val SMOOTHING = "smoothing"          // 0..10
        const val FAST_TRACKING = "fast_tracking"
        const val MANUAL_EYES = "manual_eyes"
        const val EYE_CLOSED = "eye_closed"        // 0 = fully open .. 1 = closed
        const val SPRING_BONES = "spring_bones"
        const val SHOW_DEBUG = "show_debug"
        const val SHOW_PREVIEW = "show_preview"
        const val VIDEO_MODE = "video_mode"        // capture button records video
        const val FULL_BRIGHT = "full_bright"      // all materials unlit
        const val ARM_IK = "arm_ik"                // hands place the arms (IK)
        const val HIDDEN_PARTS = "hidden_parts"
        const val DEFAULT_SMOOTHING = 5
        const val MIC_MUTED = "mic_muted"
        const val LIGHT_LEVEL = "light_level"      // 0..10, 5 = default brightness
        const val STREAM_URL = "stream_url"
        const val STREAM_KEY = "stream_key"
        const val STREAM_QUALITY = "stream_quality" // "" = auto, else StreamQuality.name
    }
}
