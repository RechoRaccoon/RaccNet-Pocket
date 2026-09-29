package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
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
    // Override App Colors is saved per signed-in account: these are the old
    // shared keys, now only read once to carry an existing choice over to
    // the first account that opens the app (see setAccount).
    private const val KEY_OVERRIDE_COLORS = "override_app_colors"
    private const val KEY_OVERRIDE_COLOR = "override_app_color"
    private const val KEY_OVERRIDE_ACTIVE_DID = "override_active_did"
    private const val KEY_OVERRIDE_LEGACY_CLAIMED = "override_legacy_claimed"
    private fun acct(key: String, did: String) = "$key@" + did.ifBlank { "signed_out" }
    private const val KEY_DEV_TOOLS_UNLOCKED = "dev_tools_unlocked"
    private const val KEY_DEV_FORCE_SCAN_BUBBLE = "dev_force_scan_bubble"
    private const val KEY_SCAN_BUBBLE_DISMISSED = "scan_bubble_dismissed"

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

    /** Settings → hold the "Settings" tab for 10 seconds: the hidden
     *  "Dev Tools" section at the bottom of Settings. */
    var devToolsUnlocked by mutableStateOf(false)
        private set

    /** Dev Tools → the Hub's Reviews/Blogs rows are replaced by the
     *  "scan your follows" bubble, so that flow can be tried any time. */
    var devForceScanBubble by mutableStateOf(false)
        private set

    /** The Hub's "scan your follows" bubble was closed with its X — it
     *  doesn't come back (the scan stays available in Settings). */
    var scanBubbleDismissed by mutableStateOf(false)
        private set

    /** Dev Tools → "Preview Loading Animation": true while the preview is
     *  playing (AppRoot runs it). Not saved. */
    var devLoadingPreview by mutableStateOf(false)

    /** Dev Tools → "Preview Login Page": the login page shown over the app
     *  without signing out. Not saved. */
    var devLoginPreview by mutableStateOf(false)

    /** Whether any loading transition/screen plays at all. */
    val loadingScreens: Boolean get() = loadingAnimation != LoadingAnimation.NONE

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences(PREFS)
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
        overrideDid = p.getString(KEY_OVERRIDE_ACTIVE_DID, null) ?: ""
        loadOverride(p, overrideDid)
        devToolsUnlocked = p.getBoolean(KEY_DEV_TOOLS_UNLOCKED, false)
        devForceScanBubble = p.getBoolean(KEY_DEV_FORCE_SCAN_BUBBLE, false)
        scanBubbleDismissed = p.getBoolean(KEY_SCAN_BUBBLE_DISMISSED, false)
        visualizerCallMode = p.getString(KEY_VISUALIZER_CALL_MODE, null)
            ?.let { name -> VisualizerCallMode.entries.firstOrNull { it.name == name } }
            ?: if (p.getBoolean(KEY_VISUALIZER_DURING_CALLS, false)) VisualizerCallMode.MUSIC_ONLY else VisualizerCallMode.PAUSE
    }

    fun updateDevToolsUnlocked(enabled: Boolean) {
        devToolsUnlocked = enabled
        if (!enabled) devForceScanBubble = false
        prefs?.edit()?.putBoolean(KEY_DEV_TOOLS_UNLOCKED, enabled)?.putBoolean(KEY_DEV_FORCE_SCAN_BUBBLE, devForceScanBubble)?.apply()
    }

    fun dismissScanBubble() {
        scanBubbleDismissed = true
        // Closing it while Dev Tools forces it on turns that off too.
        devForceScanBubble = false
        prefs?.edit()?.putBoolean(KEY_SCAN_BUBBLE_DISMISSED, true)?.putBoolean(KEY_DEV_FORCE_SCAN_BUBBLE, false)?.apply()
    }

    fun updateDevForceScanBubble(enabled: Boolean) {
        devForceScanBubble = enabled
        prefs?.edit()?.putBoolean(KEY_DEV_FORCE_SCAN_BUBBLE, enabled)?.apply()
    }

    fun updateVisualizerCallMode(value: VisualizerCallMode) {
        visualizerCallMode = value
        prefs?.edit()?.putString(KEY_VISUALIZER_CALL_MODE, value.name)?.apply()
    }

    fun updateAudioVisualizer(enabled: Boolean) {
        audioVisualizer = enabled
        prefs?.edit()?.putBoolean(KEY_AUDIO_VISUALIZER, enabled)?.apply()
    }

    /** Whose Override App Colors choice is showing. */
    private var overrideDid: String = ""

    /** The signed-in account changed (or became known): use its own
     *  Override App Colors choice. */
    fun setAccount(did: String) {
        val p = prefs ?: return
        if (did == overrideDid && p.contains(acct(KEY_OVERRIDE_COLORS, did))) return
        overrideDid = did
        p.edit().putString(KEY_OVERRIDE_ACTIVE_DID, did).commit()
        loadOverride(p, did)
    }

    private fun loadOverride(p: SharedPreferences, did: String) {
        if (!p.contains(acct(KEY_OVERRIDE_COLORS, did)) && !p.getBoolean(KEY_OVERRIDE_LEGACY_CLAIMED, false)) {
            // The old shared setting: shown while signed out, and handed to
            // the first account that signs in.
            val legacyOn = p.getBoolean(KEY_OVERRIDE_COLORS, false)
            val legacyColor = p.getInt(KEY_OVERRIDE_COLOR, 0xFFFF4FA1.toInt())
            overrideAppColors = legacyOn
            overrideColor = legacyColor
            if (did.isNotBlank()) {
                p.edit().putBoolean(KEY_OVERRIDE_LEGACY_CLAIMED, true)
                    .putBoolean(acct(KEY_OVERRIDE_COLORS, did), legacyOn)
                    .putInt(acct(KEY_OVERRIDE_COLOR, did), legacyColor)
                    .apply()
            }
            return
        }
        overrideAppColors = p.getBoolean(acct(KEY_OVERRIDE_COLORS, did), false)
        overrideColor = p.getInt(acct(KEY_OVERRIDE_COLOR, did), 0xFFFF4FA1.toInt())
    }

    fun updateOverrideAppColors(enabled: Boolean) {
        overrideAppColors = enabled
        prefs?.edit()?.putBoolean(acct(KEY_OVERRIDE_COLORS, overrideDid), enabled)
            ?.putInt(acct(KEY_OVERRIDE_COLOR, overrideDid), overrideColor)?.apply()
    }

    fun updateOverrideColor(argb: Int) {
        overrideColor = argb or 0xFF000000.toInt()
        prefs?.edit()?.putInt(acct(KEY_OVERRIDE_COLOR, overrideDid), overrideColor)
            ?.putBoolean(acct(KEY_OVERRIDE_COLORS, overrideDid), overrideAppColors)?.apply()
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
