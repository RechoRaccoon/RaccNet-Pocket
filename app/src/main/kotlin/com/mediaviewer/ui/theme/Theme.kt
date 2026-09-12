package com.mediaviewer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

val OledBlack  = Color(0xFF000000)
val OffBlack   = Color(0xFF0A0A0A)
val DimGray    = Color(0xFF888888)
val LightGray  = Color(0xFFCCCCCC)
val White      = Color.White
val LikeRed    = Color(0xFFE53935)
val BookmarkYellow = Color(0xFFFDD835)
val RepostGreen = Color(0xFF43A047)
val VoteGreen   = Color(0xFF66BB6A)
val VoteRed     = Color(0xFFEF5350)

private val ColorScheme = darkColorScheme(
    primary         = White,
    onPrimary       = OledBlack,
    background      = OledBlack,
    surface         = OledBlack,
    onBackground    = White,
    onSurface       = White,
    surfaceVariant  = OffBlack,
    onSurfaceVariant = LightGray
)

/** Phase 4 — "change the app's universal font via a custom pack": applies
 *  [customFontFamily] (built from a user-picked .ttf/.otf, see MainActivity) to
 *  every Material3 text style. Text() composables across the app never pass
 *  their own explicit fontFamily — they only ever override size/weight/color —
 *  so overriding it once here, on every style in Typography, is enough to
 *  reach every screen without touching each call site individually. Null
 *  (the default/no custom font picked) falls back to Typography()'s own
 *  system default exactly as before this feature existed. */
private fun typographyFor(customFontFamily: FontFamily?): Typography {
    if (customFontFamily == null) return Typography()
    val base = Typography()
    return Typography(
        displayLarge   = base.displayLarge.copy(fontFamily = customFontFamily),
        displayMedium  = base.displayMedium.copy(fontFamily = customFontFamily),
        displaySmall   = base.displaySmall.copy(fontFamily = customFontFamily),
        headlineLarge  = base.headlineLarge.copy(fontFamily = customFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = customFontFamily),
        headlineSmall  = base.headlineSmall.copy(fontFamily = customFontFamily),
        titleLarge     = base.titleLarge.copy(fontFamily = customFontFamily),
        titleMedium    = base.titleMedium.copy(fontFamily = customFontFamily),
        titleSmall     = base.titleSmall.copy(fontFamily = customFontFamily),
        bodyLarge      = base.bodyLarge.copy(fontFamily = customFontFamily),
        bodyMedium     = base.bodyMedium.copy(fontFamily = customFontFamily),
        bodySmall      = base.bodySmall.copy(fontFamily = customFontFamily),
        labelLarge     = base.labelLarge.copy(fontFamily = customFontFamily),
        labelMedium    = base.labelMedium.copy(fontFamily = customFontFamily),
        labelSmall     = base.labelSmall.copy(fontFamily = customFontFamily)
    )
}

@Composable
fun MediaViewerTheme(customFontFamily: FontFamily? = null, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = typographyFor(customFontFamily),
        content = content
    )
}
