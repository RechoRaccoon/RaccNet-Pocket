package com.mediaviewer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
fun MediaViewerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        content = content
    )
}
