package com.mediaviewer

import androidx.compose.ui.text.font.FontFamily

/**
 * wasmJsMain actual for the [loadFontFamily] expect in commonMain's App.kt.
 *
 * PORT: wasmJs can't turn a file path into a FontFamily, so custom font
 * packs are Android-only for now — always returns null (the theme's default
 * Typography). A future implementation could register the font via the
 * CSS FontFace API and reference it by family name.
 */
actual fun loadFontFamily(path: String?): FontFamily? = null
