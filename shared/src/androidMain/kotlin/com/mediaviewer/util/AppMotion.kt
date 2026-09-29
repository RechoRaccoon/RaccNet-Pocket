package com.mediaviewer.util

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.MotionDurationScale

/**
 * Settings → UI Customization → "Reduced Animations", applied to every
 * Compose animation in the app at once.
 *
 * Compose reads the [MotionDurationScale] in an animation's coroutine
 * context to scale its duration; a scale of 0 makes it jump straight to the
 * end. MainActivity installs this object in the window's recomposer, so every
 * animate*AsState, AnimatedVisibility/AnimatedContent, Crossfade, Animatable,
 * spring/tween and animated scroll picks it up — with Reduced Animations on
 * they all complete instantly. Otherwise it follows the phone's own animation
 * speed (Developer options → Animator duration scale), like Compose normally
 * does. Scroll flings aren't affected (Compose keeps those at normal speed).
 */
object AppMotion : MotionDurationScale {
    private const val PREFS = "ui_toggles"
    private const val KEY_REDUCED = "reduced_animations_mirror"

    @Volatile var reduced: Boolean = false
        private set

    @Volatile private var systemScale: Float = 1f

    override val scaleFactor: Float
        get() = if (reduced) 0f else systemScale

    fun init(context: Context) {
        systemScale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        // Read synchronously at launch (the real setting lives in DataStore
        // and arrives a moment later), so the very first screens obey it too.
        reduced = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REDUCED, false)
    }

    fun update(context: Context, value: Boolean) {
        if (reduced == value) return
        reduced = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_REDUCED, value).apply()
    }
}
