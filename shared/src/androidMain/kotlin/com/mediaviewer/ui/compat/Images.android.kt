package com.mediaviewer.ui.compat

import androidx.compose.ui.graphics.Color
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.mediaviewer.platform.PlatformContext

actual suspend fun sampleAverageColor(context: PlatformContext, url: String, size: Int): Color? {
    val loader = SingletonImageLoader.get(context)
    val request = ImageRequest.Builder(context).data(url).size(size, size).allowHardware(false).build()
    val bmp = (loader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
    var r = 0L; var g = 0L; var b = 0L; var n = 0
    for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
        val p = bmp.getPixel(x, y)
        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
    }
    if (n == 0) return null
    return Color(r.toFloat() / n / 255f, g.toFloat() / n / 255f, b.toFloat() / n / 255f, 1f)
}

actual val PlatformContext.coilContext: coil3.PlatformContext get() = this

actual fun qrModuleMatrix(content: String): Array<BooleanArray> {
    val hints = mapOf(
        com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
        com.google.zxing.EncodeHintType.MARGIN to 0
    )
    val code = com.google.zxing.qrcode.encoder.Encoder.encode(
        content, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H, hints
    )
    val m = code.matrix
    return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y).toInt() == 1 } }
}
