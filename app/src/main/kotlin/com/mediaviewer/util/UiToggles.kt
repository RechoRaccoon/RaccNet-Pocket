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
    private const val KEY_SHOW_TAGGING_STATUS = "show_tagging_status"
    private const val KEY_SHOW_TRANSLATION_STATUS = "show_translation_status"
    private const val KEY_LOADING_ANIMATION = "loading_animation"
    private const val KEY_AUDIO_VISUALIZER = "audio_visualizer"
    private const val KEY_VISUALIZER_DURING_CALLS = "audio_visualizer_during_calls" // old on/off switch
    private const val KEY_VISUALIZER_CALL_MODE = "audio_visualizer_call_mode"
    private const val KEY_VISUALIZER_PERMISSION_ASKED = "audio_visualizer_permission_asked"
    private const val KEY_STARRY_BACKGROUND = "starry_background"
    private const val KEY_STAR_FRAME_RATE = "starry_background_fps"
    private const val KEY_OVERRIDE_COLORS = "override_app_colors"
    private const val KEY_OVERRIDE_COLOR = "override_app_color"

    /** Settings → UI Customization → "Loading Animation". */
    enum class LoadingAnimation(val label: String) {
        /** The old page fades into a drifting starfield with the Stellar
         *  logo, which then fades away to reveal the loaded page. First in
         *  the list (and the default). */
        SPACE("Stellar"),
        /** The retro pixel-matrix wipe. */
        PIXELS("Pixels"),
        /** The screen shatters like glass from where you tapped. */
        SHATTER("Shatter"),
        /** Pages open immediately and fill in as their data arrives. */
        NONE("None")
    }

    private var prefs: SharedPreferences? = null

    /** Settings → App Functionality → "FPS Overlay": the frame-rate readout
     *  beside the camera cutout. (Stored under its old "debug overlay" key.) */
    var debugOverlay by mutableStateOf(false)
        private set

    /** Settings → Media Tagging → "Show Tagging Status" (under Tag Media When
     *  Liked): the like-tagging queue as a status bubble on the timeline. */
    var showTaggingStatus by mutableStateOf(true)
        private set

    /** Settings → App Functionality → "Show Translation Status" (under
     *  Translate To): the "Translated X to Y" bubble on the timeline. */
    var showTranslationStatus by mutableStateOf(true)
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

    /** Settings → Starry Background → "Frame Rate Cap": how often the stars
     *  (twinkles, shooting stars) redraw. Low by default so the screen can
     *  drop to its idle refresh rate when nothing else is moving. */
    var starFrameRate by mutableStateOf(30)
        private set
    val starFrameRateOptions = listOf(30, 60, 90, 120)

    /** Settings → UI Customization → "Override App Colors": everywhere the
     *  app would wear the signed-in account's profile color, it wears
     *  [overrideColor] instead (the account's own profile page keeps its
     *  real colors). */
    var overrideAppColors by mutableStateOf(false)
        private set
    /** ARGB. Defaults to the Stellar logo pink. */
    var overrideColor by mutableStateOf(0xFFFF4FA1.toInt())
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
        showTaggingStatus = p.getBoolean(KEY_SHOW_TAGGING_STATUS, true)
        showTranslationStatus = p.getBoolean(KEY_SHOW_TRANSLATION_STATUS, true)
        loadingAnimation = p.getString(KEY_LOADING_ANIMATION, null)
            ?.let { name -> LoadingAnimation.entries.firstOrNull { it.name == name } }
            ?: LoadingAnimation.SPACE
        audioVisualizer = p.getBoolean(KEY_AUDIO_VISUALIZER, true)
        starryBackground = p.getBoolean(KEY_STARRY_BACKGROUND, true)
        starFrameRate = p.getInt(KEY_STAR_FRAME_RATE, 30).coerceIn(15, 120)
        overrideAppColors = p.getBoolean(KEY_OVERRIDE_COLORS, false)
        overrideColor = p.getInt(KEY_OVERRIDE_COLOR, 0xFFFF4FA1.toInt())
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

    fun updateOverrideAppColors(enabled: Boolean) {
        overrideAppColors = enabled
        prefs?.edit()?.putBoolean(KEY_OVERRIDE_COLORS, enabled)?.apply()
    }

    fun updateOverrideColor(argb: Int) {
        overrideColor = argb or 0xFF000000.toInt()
        prefs?.edit()?.putInt(KEY_OVERRIDE_COLOR, overrideColor)?.apply()
    }

    fun updateStarFrameRate(fps: Int) {
        starFrameRate = fps.coerceIn(15, 120)
        prefs?.edit()?.putInt(KEY_STAR_FRAME_RATE, starFrameRate)?.apply()
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

    fun updateShowTaggingStatus(enabled: Boolean) {
        showTaggingStatus = enabled
        prefs?.edit()?.putBoolean(KEY_SHOW_TAGGING_STATUS, enabled)?.apply()
    }

    fun updateShowTranslationStatus(enabled: Boolean) {
        showTranslationStatus = enabled
        prefs?.edit()?.putBoolean(KEY_SHOW_TRANSLATION_STATUS, enabled)?.apply()
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
