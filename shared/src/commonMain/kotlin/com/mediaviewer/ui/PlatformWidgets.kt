package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.util.EmojiEntry
import com.mediaviewer.util.EmojiStore

/*
 * Composables whose insides are platform code (native players, web views,
 * file pickers). Android's versions are the app's original ones.
 */

/** Height of the emoji panel while a tab is being renamed (just the tab row,
 *  riding on top of the keyboard). */
internal val EmojiPanelCompactHeight = 60.dp

/** Textshot mode's custom-emoji panel. */
@Composable
internal expect fun EmojiPanel(
    store: EmojiStore,
    height: Dp,
    compact: Boolean,
    liquidGlass: Boolean,
    tint: Color,
    onPickEmoji: (EmojiEntry) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
)

/** A muted, looping preview of a picked local video (the composer). */
@Composable
expect fun InlineVideoPlayer(uri: PlatformUri, modifier: Modifier = Modifier)

/** A web page embedded in the UI (live-stream embeds). */
@Composable
expect fun EmbeddedWebView(url: String, modifier: Modifier = Modifier)

/** Textshot's rendered image for [text] (the same renderer the uploaded
 *  image uses), or null where Textshot can't render. Blocking. */
expect fun renderTextshotPreview(text: String, store: EmojiStore): ImageBitmap?
