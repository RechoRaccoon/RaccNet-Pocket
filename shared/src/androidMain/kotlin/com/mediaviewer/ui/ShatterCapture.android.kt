package com.mediaviewer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.PixelCopy
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import com.mediaviewer.util.UiToggles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

// ── Screenshot ─────────────────────────────────────────────────────────────

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The window exactly as it's on screen (PixelCopy keeps blur and other
 *  render effects); falls back to drawing the view tree, then to black. */
internal suspend fun captureWindow(v: View): Bitmap {
    val root = v.rootView
    val w = root.width.coerceAtLeast(1)
    val h = root.height.coerceAtLeast(1)
    val window = v.context.findActivity()?.window
    if (window != null && root.isLaidOut) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ok = runCatching {
            suspendCancellableCoroutine<Boolean> { cont ->
                PixelCopy.request(window, bmp, { result ->
                    if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS)
                }, Handler(Looper.getMainLooper()))
            }
        }.getOrDefault(false)
        if (ok) return bmp
        bmp.recycle()
    }
    runCatching {
        if (root.isLaidOut) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.drawColor(android.graphics.Color.BLACK)
            root.draw(c)
            return bmp
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
}

/** A short crunchy buzz as the glass cracks (Shatter). */
internal fun shatterCrunch(v: View) {
    val vib = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            (v.context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            v.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()
    val ok = runCatching {
        if (vib == null || !vib.hasVibrator()) return@runCatching false
        val timings = longArrayOf(0, 18, 22, 12, 30, 9, 40, 6)
        val amps = intArrayOf(0, 255, 0, 170, 0, 110, 0, 70)
        if (vib.hasAmplitudeControl()) vib.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
        else vib.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        true
    }.getOrDefault(false)
    if (!ok) runCatching { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
}

