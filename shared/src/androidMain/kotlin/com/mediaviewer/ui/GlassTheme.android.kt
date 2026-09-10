package com.mediaviewer.ui

import android.os.Build
import androidx.compose.ui.graphics.Color
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import com.mediaviewer.platform.AndroidAppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * androidMain actuals for the GlassTheme expects (see commonMain
 * ui/GlassTheme.kt).
 *
 * [fetchDominantColor] is the legacy Coil implementation, verbatim in
 * behavior: fetch a 16x16 copy, average its pixels, fall back to
 * 0xFF2A2A2E. Coil 3 replaced Coil 2 (the project's shared image stack),
 * so the legacy `loader.execute(request).drawable as BitmapDrawable`
 * became `SuccessResult.image as BitmapImage`.
 */

/** Unchanged legacy behavior: blur needs API 31+. */
internal actual val CAN_BLUR: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

actual suspend fun fetchDominantColor(url: String): Color {
    if (url.isBlank()) return Color(0xFF2A2A2E)
    return try {
        withContext(Dispatchers.IO) {
            val app = AndroidAppContext.app
            val loader = ImageLoader(app)
            val request = ImageRequest.Builder(app)
                .data(url)
                .size(Size(16, 16))
                .allowHardware(false)
                .build()
            val bmp = (loader.execute(request).image as? BitmapImage)?.bitmap
            if (bmp != null) {
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
                    val p = bmp.getPixel(x, y)
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                }
                if (n > 0) return@withContext Color(r.toFloat() / n / 255f, g.toFloat() / n / 255f, b.toFloat() / n / 255f, 1f)
            }
            Color(0xFF2A2A2E)
        }
    } catch (_: Exception) {
        Color(0xFF2A2A2E)
    }
}
