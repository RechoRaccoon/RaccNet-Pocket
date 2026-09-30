package com.mediaviewer.ui.compat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import com.mediaviewer.platform.toByteArray
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/** Window-level helpers for the iOS side of [PlatformView]. */
@OptIn(ExperimentalForeignApi::class)
internal object IosScreen {
    fun keyWindow(): UIWindow? {
        val scenes = UIApplication.sharedApplication.connectedScenes
        for (s in scenes) {
            val scene = s as? UIWindowScene ?: continue
            scene.keyWindow?.let { return it }
        }
        for (s in scenes) {
            val scene = s as? UIWindowScene ?: continue
            (scene.windows.firstOrNull() as? UIWindow)?.let { return it }
        }
        return null
    }

    /** A snapshot of the key window as a Compose image (null on failure). */
    fun capture(): ImageBitmap? {
        val window = keyWindow() ?: return null
        return runCatching {
            val renderer = UIGraphicsImageRenderer(bounds = window.bounds)
            val image = renderer.imageWithActions { _ ->
                window.drawViewHierarchyInRect(window.bounds, afterScreenUpdates = false)
            }
            val png = UIImagePNGRepresentation(image) ?: return null
            org.jetbrains.skia.Image.makeFromEncoded(png.toByteArray()).toComposeImageBitmap()
        }.getOrNull()
    }

    /** The top safe-area inset in points (the Dynamic Island / notch). */
    fun topInsetPoints(): Double = keyWindow()?.safeAreaInsets?.useContents { top } ?: 0.0
}
