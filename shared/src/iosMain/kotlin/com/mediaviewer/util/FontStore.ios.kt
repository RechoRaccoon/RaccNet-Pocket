package com.mediaviewer.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.resources.Res
import com.mediaviewer.resources.audiowide
import org.jetbrains.compose.resources.Font

@Composable
actual fun audiowideFontFamily(): FontFamily = FontFamily(Font(Res.font.audiowide))

actual object FontFiles {
    actual fun exists(path: String): Boolean = com.mediaviewer.platform.localFileExists(path)
    actual fun delete(path: String) { com.mediaviewer.platform.deleteLocalFile(path) }
    actual fun family(path: String): FontFamily {
        val bytes = com.mediaviewer.platform.readLocalFile(path) ?: error("unreadable font")
        return FontFamily(androidx.compose.ui.text.platform.Font(identity = path, data = bytes))
    }

    /** Custom fonts are an Android-only feature (the setting is grayed out). */
    actual fun copyIn(context: PlatformContext, uri: PlatformUri): FontCopied =
        FontCopied.Error("Custom fonts are only available on Android")
}
