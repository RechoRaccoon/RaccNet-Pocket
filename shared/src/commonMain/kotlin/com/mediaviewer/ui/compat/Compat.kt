package com.mediaviewer.ui.compat

import com.mediaviewer.ui.compat.rememberPlatformView

import com.mediaviewer.ui.compat.jformat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogProperties
import com.mediaviewer.platform.PlatformContext

/*
 * Small stand-ins for the Android-only APIs Stellar's screens use, with the
 * same names/shapes so the shared UI code reads exactly as it did on
 * Android. On Android each one forwards to the real API (behaviour is
 * unchanged); iOS has its own implementation.
 */

/** com.mediaviewer.ui.compat.LocalContext on Android. */
expect val LocalContext: CompositionLocal<PlatformContext>

/** com.mediaviewer.ui.compat.Toast, same call shape: Toast.makeText(ctx, text, len).show(). */
object Toast {
    const val LENGTH_SHORT = 0
    const val LENGTH_LONG = 1
    fun makeText(context: PlatformContext?, text: CharSequence, duration: Int): ToastMessage =
        ToastMessage(text.toString(), duration == LENGTH_LONG)
}

class ToastMessage(val text: String, val long: Boolean) {
    fun show() = showPlatformToast(text, long)
}

/** Shows a short message (a Toast on Android; an in-app message on iOS). */
expect fun showPlatformToast(message: String, long: Boolean = false)

/** com.mediaviewer.ui.compat.HapticFeedbackConstants (same values). */
object HapticFeedbackConstants {
    const val LONG_PRESS = 0
    const val VIRTUAL_KEY = 1
    const val KEYBOARD_TAP = 3
    const val CLOCK_TICK = 4
    const val CONTEXT_CLICK = 6
    const val CONFIRM = 16
    const val REJECT = 17
}

/** The bits of android.view.View the screens use (haptics). */
interface PlatformView {
    fun performHapticFeedback(feedbackConstant: Int): Boolean

    /** A screenshot of the whole window (for the space / shatter
     *  transitions), or null if it can't be taken. */
    suspend fun captureScreen(): androidx.compose.ui.graphics.ImageBitmap?

    /** The vertical center (px) of a display cutout whose top is above
     *  [maxTopPx], or null when there isn't one. */
    fun displayCutoutCenterYPx(maxTopPx: Float): Float?

    /** A short crunchy buzz (Shatter's glass cracking). */
    fun crunchHaptic()
}

/** rememberPlatformView() on Android, wrapped. */
@Composable
expect fun rememberPlatformView(): PlatformView

/** com.mediaviewer.ui.compat.Build.VERSION / VERSION_CODES. iOS reports a very high
 *  SDK_INT, i.e. "every modern capability is available" (blur, etc.). */
object Build {
    object VERSION {
        val SDK_INT: Int get() = platformSdkInt()
    }
    object VERSION_CODES {
        const val N = 24
        const val N_MR1 = 25
        const val O = 26
        const val O_MR1 = 27
        const val P = 28
        const val Q = 29
        const val R = 30
        const val S = 31
        const val S_V2 = 32
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
        const val VANILLA_ICE_CREAM = 35
    }
}

expect fun platformSdkInt(): Int

/** com.mediaviewer.ui.compat.BackHandler on Android. */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** LocalConfiguration.current.screenWidthDp / screenHeightDp. */
@Composable
expect fun rememberScreenSizeDp(): IntSize

/** Blurs and dims the app behind the current Dialog (Android 12+ window
 *  blur; on iOS the dialog's own scrim does the dimming). Call inside a
 *  Dialog's content. */
@Composable
expect fun DialogBlurBehind(radius: Int = 48, dimAmount: Float = 0.45f)

/** com.mediaviewer.ui.compat.edgeToEdgeDialogProperties():
 *  a full-width dialog that draws edge to edge. */
expect fun edgeToEdgeDialogProperties(): DialogProperties

/** Java's String.format, for the few patterns the UI uses. */
expect fun String.jformat(vararg args: Any?): String

/** Opens a web link in the browser. */
expect fun openUrl(context: PlatformContext, url: String)

/** Android's "Open by default" settings page for [packageName] (Android only). */
expect fun openAppLinkSettings(context: PlatformContext, packageName: String): Boolean

/** com.mediaviewer.ui.compat.uptimeMillis(). */
expect fun uptimeMillis(): Long

/** android.graphics.Color's HSV helpers. */
expect object PlatformColor {
    fun colorToHSV(color: Int, hsv: FloatArray)
    fun HSVToColor(hsv: FloatArray): Int
    fun HSVToColor(alpha: Int, hsv: FloatArray): Int
}

/** One short vibration of [ms] milliseconds (default strength). */
expect fun vibrateOneShot(context: PlatformContext, ms: Long)

/** Restarts the app so freshly imported data is loaded (Android relaunches
 *  the process; iOS rebuilds the app state in place). */
expect fun restartApp(context: PlatformContext)

/** This app's package / bundle id. */
expect fun appPackageName(context: PlatformContext): String

/** Settings → Reduced Animations (every Compose animation jumps to its
 *  end). Android applies it to the whole window; iOS doesn't yet. */
expect fun applyReducedAnimations(context: PlatformContext, reduced: Boolean)
