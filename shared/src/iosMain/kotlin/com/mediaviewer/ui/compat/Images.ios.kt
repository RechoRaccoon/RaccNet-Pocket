package com.mediaviewer.ui.compat

import androidx.compose.ui.graphics.Color
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.mediaviewer.platform.PlatformContext
import coil3.PlatformContext as CoilContext

actual suspend fun sampleAverageColor(context: PlatformContext, url: String, size: Int): Color? {
    val coil = CoilContext.INSTANCE
    val loader = SingletonImageLoader.get(coil)
    val request = ImageRequest.Builder(coil).data(url).size(size, size).build()
    val bmp = (loader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
    var r = 0L; var g = 0L; var b = 0L; var n = 0
    for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
        val p = bmp.getColor(x, y)
        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
    }
    if (n == 0) return null
    return Color(r.toFloat() / n / 255f, g.toFloat() / n / 255f, b.toFloat() / n / 255f, 1f)
}

actual val PlatformContext.coilContext: coil3.PlatformContext get() = CoilContext.INSTANCE

actual fun qrModuleMatrix(content: String): Array<BooleanArray> =
    com.mediaviewer.util.QrEncoder.encode(content, com.mediaviewer.util.QrEncoder.Ecc.H)
