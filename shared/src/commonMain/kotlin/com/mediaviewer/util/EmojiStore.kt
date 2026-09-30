package com.mediaviewer.util

import androidx.compose.ui.graphics.ImageBitmap
import com.mediaviewer.platform.PlatformContext

/** One imported custom emoji. [id] is permanent for the emoji's lifetime and
 *  is what the in-text token character is derived from (see [EmojiStore.charFor]).
 *  [file] is the PNG copy stored under the app's private files dir. */
data class EmojiEntry(val id: Int, val name: String, val file: String)

/** A user-made emoji folder/tab. "All" is not stored — it is implicitly every
 *  emoji in the library. */
data class EmojiFolder(val id: Int, val name: String, val emojiIds: List<Int> = emptyList())

/** The persisted shape (written as JSON). */
data class EmojiIndex(
    val emojis: List<EmojiEntry> = emptyList(),
    val folders: List<EmojiFolder> = emptyList()
)

/** [EmojiIndex] plus a lookup table, rebuilt whenever the index changes. */
class EmojiState(val index: EmojiIndex) {
    val byId: Map<Int, EmojiEntry> = index.emojis.associateBy { it.id }
}

/**
 * The user's custom emoji library. Each emoji is typed into a draft as one
 * private-use character ("token") and drawn as its picture. The Android
 * store keeps pictures + an index in app storage; iOS has an empty library
 * for now.
 */
expect class EmojiStore {
    /** Observable by Compose — recomposes readers when emoji change. */
    val state: EmojiState

    /** Reads the saved library from disk (once). */
    suspend fun load()

    fun entryFor(c: Char): EmojiEntry?
    fun charFor(entry: EmojiEntry): Char
    fun containsEmoji(text: String): Boolean
    fun toShortcodes(text: String): String
    fun stripEmoji(text: String): String
    fun imageBitmapForChar(c: Char): ImageBitmap?

    companion object {
        fun get(context: PlatformContext): EmojiStore
    }
}
