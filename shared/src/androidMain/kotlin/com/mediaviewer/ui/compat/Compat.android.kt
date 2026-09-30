package com.mediaviewer.ui.compat

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mediaviewer.platform.PlatformContext

actual val LocalContext: CompositionLocal<PlatformContext>
    get() = androidx.compose.ui.platform.LocalContext

/** Set once from MainActivity.onCreate so non-UI code can show a Toast. */
object AndroidToastHost {
    @Volatile var appContext: android.content.Context? = null
    val main by lazy { Handler(Looper.getMainLooper()) }
}

actual fun showPlatformToast(message: String, long: Boolean) {
    val ctx = AndroidToastHost.appContext ?: return
    val show = { android.widget.Toast.makeText(ctx, message, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show() }
    if (Looper.myLooper() == Looper.getMainLooper()) show() else AndroidToastHost.main.post { show() }
}

internal class AndroidPlatformView(val view: android.view.View) : PlatformView {
    override fun performHapticFeedback(feedbackConstant: Int): Boolean = view.performHapticFeedback(feedbackConstant)

    override suspend fun captureScreen(): androidx.compose.ui.graphics.ImageBitmap? =
        runCatching { com.mediaviewer.ui.captureWindow(view).asImageBitmap() }.getOrNull()

    override fun displayCutoutCenterYPx(maxTopPx: Float): Float? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return null
        val rect = view.rootWindowInsets?.displayCutout?.boundingRects
            ?.firstOrNull { it.height() > 0 && it.top < maxTopPx }
        return rect?.exactCenterY()
    }
}

@Composable
actual fun rememberPlatformView(): PlatformView {
    val view = LocalView.current
    return remember(view) { AndroidPlatformView(view) }
}

actual fun platformSdkInt(): Int = android.os.Build.VERSION.SDK_INT

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)

@Composable
actual fun rememberScreenSizeDp(): IntSize {
    val c = LocalConfiguration.current
    return IntSize(c.screenWidthDp, c.screenHeightDp)
}

@Composable
actual fun DialogBlurBehind(radius: Int, dimAmount: Float) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    LaunchedEffect(window) {
        if (window != null && android.os.Build.VERSION.SDK_INT >= 31) runCatching {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = radius }
        }
        window?.setDimAmount(dimAmount)
    }
}

actual fun edgeToEdgeDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

actual fun String.jformat(vararg args: Any?): String = String.format(this, *args)

actual fun openUrl(context: PlatformContext, url: String) {
    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
}

/** Opens Android's "Open by default" page for [packageName] (the app's info
 *  page on older versions). Returns false if neither could be opened. */
actual fun openAppLinkSettings(context: PlatformContext, packageName: String): Boolean {
    val pkg = android.net.Uri.parse("package:$packageName")
    val flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
    val byDefault = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        android.content.Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg).addFlags(flags)
    } else null
    val details = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(flags)
    val opened = byDefault != null && runCatching { context.startActivity(byDefault) }.isSuccess
    if (opened) return true
    return runCatching { context.startActivity(details) }.isSuccess
}

actual fun uptimeMillis(): Long = android.os.SystemClock.uptimeMillis()

actual object PlatformColor {
    actual fun colorToHSV(color: Int, hsv: FloatArray) = android.graphics.Color.colorToHSV(color, hsv)
    actual fun HSVToColor(hsv: FloatArray): Int = android.graphics.Color.HSVToColor(hsv)
    actual fun HSVToColor(alpha: Int, hsv: FloatArray): Int = android.graphics.Color.HSVToColor(alpha, hsv)
}

actual fun vibrateOneShot(context: PlatformContext, ms: Long) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
        (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager)
            .defaultVibrator.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    else {
        @Suppress("DEPRECATION") val v = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(ms)
    }
}

actual fun restartApp(context: PlatformContext) = com.mediaviewer.RestartActivity.restartApp(context)

actual fun appPackageName(context: PlatformContext): String = context.packageName
