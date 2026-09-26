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
    private const val KEY_LOADING_SCREENS = "loading_screens"

    private var prefs: SharedPreferences? = null

    /** Settings → App Functionality → "Debug Overlay": FPS counter (top
     *  right) and AI-tagging queue readout (top left). */
    var debugOverlay by mutableStateOf(false)
        private set

    /** Settings → UI Customization: false skips every loading screen and
     *  the pixel transition — screens open immediately and fill in live. */
    var loadingScreens by mutableStateOf(true)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        debugOverlay = p.getBoolean(KEY_DEBUG_OVERLAY, false)
        loadingScreens = p.getBoolean(KEY_LOADING_SCREENS, true)
    }

    fun updateDebugOverlay(enabled: Boolean) {
        debugOverlay = enabled
        prefs?.edit()?.putBoolean(KEY_DEBUG_OVERLAY, enabled)?.apply()
    }

    fun updateLoadingScreens(enabled: Boolean) {
        loadingScreens = enabled
        prefs?.edit()?.putBoolean(KEY_LOADING_SCREENS, enabled)?.apply()
    }
}
