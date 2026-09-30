package com.mediaviewer.util

import androidx.compose.ui.graphics.ImageBitmap
import com.mediaviewer.platform.PlatformContext

/** iOS: an empty custom-emoji library for now. */
actual class EmojiStore private constructor() {
    actual val state: EmojiState = EmojiState(EmojiIndex())
    actual suspend fun load() {}
    actual fun entryFor(c: Char): EmojiEntry? = null
    actual fun charFor(entry: EmojiEntry): Char = (0xE000 + entry.id).toChar()
    actual fun containsEmoji(text: String): Boolean = false
    actual fun toShortcodes(text: String): String = text
    actual fun stripEmoji(text: String): String = text
    actual fun imageBitmapForChar(c: Char): ImageBitmap? = null

    actual companion object {
        private val instance by lazy { EmojiStore() }
        actual fun get(context: PlatformContext): EmojiStore = instance
    }
}
