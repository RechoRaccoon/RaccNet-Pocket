package com.mediaviewer.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Small app-wide switches that any screen can read directly as Compose
 * state (no plumbing through every screen's parameter list), persisted in
 * plain SharedPreferences. Initialised once from MainActivity.onCreate.
 */
object UiToggles {
    private const val PREFS = "ui_toggles"
    private const val KEY_DEBUG_OVERLAY = "debug_overlay"
    private const val KEY_LOADING_ANIMATION = "loading_animation"

    /** Settings → UI Customization → "Loading Animation". */
    enum class LoadingAnimation(val label: String) {
        /** Pages open immediately and fill in as their data arrives. */
        NONE("None"),
        /** The retro pixel-matrix wipe. */
        PIXELS("Pixels"),
        /** The screen shatters like glass from where you tapped. */
        SHATTER("Shatter")
    }

    private var prefs: SharedPreferences? = null

    /** Settings → App Functionality → "Debug Overlay": FPS counter (top
     *  right) and AI-tagging queue readout (top left). */
    var debugOverlay by mutableStateOf(false)
        private set

    /** Which loading transition plays (default: none). */
    var loadingAnimation by mutableStateOf(LoadingAnimation.NONE)
        private set

    /** Whether any loading transition/screen plays at all. */
    val loadingScreens: Boolean get() = loadingAnimation != LoadingAnimation.NONE

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        debugOverlay = p.getBoolean(KEY_DEBUG_OVERLAY, false)
        loadingAnimation = p.getString(KEY_LOADING_ANIMATION, null)
            ?.let { name -> LoadingAnimation.entries.firstOrNull { it.name == name } }
            ?: LoadingAnimation.NONE
    }

    fun updateDebugOverlay(enabled: Boolean) {
        debugOverlay = enabled
        prefs?.edit()?.putBoolean(KEY_DEBUG_OVERLAY, enabled)?.apply()
    }

    fun updateLoadingAnimation(value: LoadingAnimation) {
        loadingAnimation = value
        prefs?.edit()?.putString(KEY_LOADING_ANIMATION, value.name)?.apply()
    }
}
