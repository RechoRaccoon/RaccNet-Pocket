package com.mediaviewer

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * androidMain actual for the [loadFontFamily] expect in commonMain's App.kt.
 *
 * Verbatim port of the legacy implementation: plain filesystem font loading
 * from a user-picked .ttf/.otf path. A null/blank path — or a file that's
 * gone missing — falls back to null (the theme's default Typography).
 */
actual fun loadFontFamily(path: String?): FontFamily? {
    if (path.isNullOrBlank()) return null
    val file = File(path)
    return if (file.exists()) FontFamily(Font(file)) else null
}
