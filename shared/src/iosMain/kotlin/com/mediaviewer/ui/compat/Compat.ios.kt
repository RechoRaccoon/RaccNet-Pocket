package com.mediaviewer.ui.compat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogProperties
import com.mediaviewer.platform.AppEvents
import com.mediaviewer.platform.IosContext
import com.mediaviewer.platform.PlatformContext

private val IosLocalContext = staticCompositionLocalOf<PlatformContext> { IosContext }

actual val LocalContext: CompositionLocal<PlatformContext> get() = IosLocalContext

actual fun showPlatformToast(message: String, long: Boolean) = AppEvents.postMessage(message)

private object IosPlatformView : PlatformView {
    override fun performHapticFeedback(feedbackConstant: Int): Boolean {
        IosFeedback.perform(feedbackConstant)
        return true
    }

    override suspend fun captureScreen(): androidx.compose.ui.graphics.ImageBitmap? = IosScreen.capture()

    /** iOS doesn't expose the Dynamic Island's shape; callers fall back to
     *  the top safe-area clearance. */
    override fun displayCutoutCenterYPx(maxTopPx: Float): Float? = null

    override fun crunchHaptic() = IosFeedback.crunch()
}

@Composable
actual fun rememberPlatformView(): PlatformView = IosPlatformView

/** "Newest Android": every capability check passes on iOS. */
actual fun platformSdkInt(): Int = 10_000

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.compose.ui.backhandler.BackHandler(enabled = enabled, onBack = onBack)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun rememberScreenSizeDp(): IntSize {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    return remember(size, density) { IntSize((size.width / density).toInt(), (size.height / density).toInt()) }
}

@Composable
actual fun DialogBlurBehind(radius: Int, dimAmount: Float) {
    // The dialog's scrim dims the app behind it on iOS.
}

actual fun edgeToEdgeDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)

actual fun String.jformat(vararg args: Any?): String = JFormat.format(this, args)

actual fun openUrl(context: PlatformContext, url: String) = IosLinks.open(url)

actual fun openAppLinkSettings(context: PlatformContext, packageName: String): Boolean = false

private val uptimeOrigin = kotlin.time.TimeSource.Monotonic.markNow()
actual fun uptimeMillis(): Long = uptimeOrigin.elapsedNow().inWholeMilliseconds

actual object PlatformColor {
    // Same maths as android.graphics.Color (AOSP's Skia HSV conversion).
    actual fun colorToHSV(color: Int, hsv: FloatArray) {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val v = max
        val s = if (max == 0f) 0f else delta / max
        var h = when {
            delta == 0f -> 0f
            max == r -> (g - b) / delta
            max == g -> 2f + (b - r) / delta
            else -> 4f + (r - g) / delta
        } * 60f
        if (h < 0f) h += 360f
        hsv[0] = h; hsv[1] = s; hsv[2] = v
    }

    actual fun HSVToColor(hsv: FloatArray): Int = HSVToColor(0xFF, hsv)

    actual fun HSVToColor(alpha: Int, hsv: FloatArray): Int {
        val h = ((hsv[0] % 360f) + 360f) % 360f
        val s = hsv[1].coerceIn(0f, 1f)
        val v = hsv[2].coerceIn(0f, 1f)
        val c = v * s
        val hh = h / 60f
        val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return ((alpha and 0xFF) shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}

actual fun vibrateOneShot(context: PlatformContext, ms: Long) {
    IosFeedback.perform(HapticFeedbackConstants.LONG_PRESS)
}

actual fun restartApp(context: PlatformContext) = AppEvents.requestRestart()

actual fun appPackageName(context: PlatformContext): String =
    platform.Foundation.NSBundle.mainBundle.bundleIdentifier ?: "rechoraccoon.stellar"

actual fun applyReducedAnimations(context: PlatformContext, reduced: Boolean) {}

actual val androidx.compose.foundation.layout.WindowInsets.Companion.navBarSpace: androidx.compose.foundation.layout.WindowInsets
    @Composable get() = WindowInsets.navigationBars
