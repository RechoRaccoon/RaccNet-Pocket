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
    private const val KEY_AUDIO_VISUALIZER = "audio_visualizer"
    private const val KEY_VISUALIZER_DURING_CALLS = "audio_visualizer_during_calls" // old on/off switch
    private const val KEY_VISUALIZER_CALL_MODE = "audio_visualizer_call_mode"
    private const val KEY_VISUALIZER_PERMISSION_ASKED = "audio_visualizer_permission_asked"
    private const val KEY_STARRY_BACKGROUND = "starry_background"

    /** Settings → UI Customization → "Loading Animation". */
    enum class LoadingAnimation(val label: String) {
        /** Pages open immediately and fill in as their data arrives. */
        NONE("None"),
        /** The retro pixel-matrix wipe. */
        PIXELS("Pixels"),
        /** The screen shatters like glass from where you tapped. */
        SHATTER("Shatter"),
        /** The old page fades into a drifting starfield with the Stellar
         *  logo, which then fades away to reveal the loaded page. */
        SPACE("Space")
    }

    private var prefs: SharedPreferences? = null

    /** Settings → App Functionality → "Debug Overlay": FPS counter (top
     *  right) and AI-tagging queue readout (top left). */
    var debugOverlay by mutableStateOf(false)
        private set

    /** Which loading transition plays (default: Space). */
    var loadingAnimation by mutableStateOf(LoadingAnimation.SPACE)
        private set

    /** Settings → UI Customization → "Audio Visualizer": bars above the
     *  feed's interaction bar that move to whatever music is playing. */
    var audioVisualizer by mutableStateOf(true)
        private set

    /** Settings → UI Customization → "Starry Background": the twinkling
     *  stars / shooting stars behind every page (on by default). */
    var starryBackground by mutableStateOf(true)
        private set

    /** Settings → Audio Visualizer → "During Calls". */
    enum class VisualizerCallMode(val label: String) {
        /** The bars rest while you're on a call. */
        PAUSE("Pause"),
        /** Only the music app's own audio, so the call never moves the bars. */
        MUSIC_ONLY("Music Only"),
        /** Everything the phone plays, the call included. */
        ALL_AUDIO("All Audio")
    }

    var visualizerCallMode by mutableStateOf(VisualizerCallMode.PAUSE)
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
            ?: LoadingAnimation.SPACE
        audioVisualizer = p.getBoolean(KEY_AUDIO_VISUALIZER, true)
        starryBackground = p.getBoolean(KEY_STARRY_BACKGROUND, true)
        visualizerCallMode = p.getString(KEY_VISUALIZER_CALL_MODE, null)
            ?.let { name -> VisualizerCallMode.entries.firstOrNull { it.name == name } }
            ?: if (p.getBoolean(KEY_VISUALIZER_DURING_CALLS, false)) VisualizerCallMode.MUSIC_ONLY else VisualizerCallMode.PAUSE
    }

    fun updateVisualizerCallMode(value: VisualizerCallMode) {
        visualizerCallMode = value
        prefs?.edit()?.putString(KEY_VISUALIZER_CALL_MODE, value.name)?.apply()
    }

    fun updateAudioVisualizer(enabled: Boolean) {
        audioVisualizer = enabled
        prefs?.edit()?.putBoolean(KEY_AUDIO_VISUALIZER, enabled)?.apply()
    }

    fun updateStarryBackground(enabled: Boolean) {
        starryBackground = enabled
        prefs?.edit()?.putBoolean(KEY_STARRY_BACKGROUND, enabled)?.apply()
    }

    /** The visualizer is on by default but needs the microphone permission
     *  to hear the phone's audio; it's asked for once, the first time the
     *  feed shows it. */
    val visualizerPermissionAsked: Boolean
        get() = prefs?.getBoolean(KEY_VISUALIZER_PERMISSION_ASKED, false) ?: true

    fun markVisualizerPermissionAsked() {
        prefs?.edit()?.putBoolean(KEY_VISUALIZER_PERMISSION_ASKED, true)?.apply()
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
