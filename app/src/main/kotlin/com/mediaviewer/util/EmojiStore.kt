package com.mediaviewer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

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
 * Custom emoji ("Discord style") for Textshot mode.
 *
 * HOW EMOJI LIVE IN TEXT: every emoji is stored in the composer's plain text
 * as ONE Unicode Private Use Area character — U+E000 + the emoji's id. That
 * keeps a whole emoji exactly one char long, so backspace, caret movement,
 * selection and the character counter all behave like they would for any
 * normal emoji, with no custom text-editing machinery. The composer draws
 * the picture over that character (see GrowingTextField), and
 * [TextshotRenderer] draws it inline when it renders the final image.
 *
 * The private-use range gives 6,400 ids; ids freed by a future delete are
 * reused, so that ceiling is on emoji *currently in the library*.
 *
 * ON THE WIRE the token becomes a readable `:name:` shortcode in the image's
 * alt text (see [toShortcodes]), so screen readers and other clients get
 * something meaningful instead of a private-use glyph.
 */
class EmojiStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "emoji").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val gson = Gson()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bitmapCache = ConcurrentHashMap<Int, Bitmap>()
    private val imageBitmapCache = ConcurrentHashMap<Int, ImageBitmap>()

    /** Observable by Compose — read this (directly or via [entryFor]) inside
     *  composition and the UI recomposes when emoji/folders change. */
    var state by mutableStateOf(EmojiState(EmojiIndex()))
        private set

    @Volatile private var loaded = false
    private val lock = Any()
    private val reservedIds = mutableSetOf<Int>()

    // ── Loading / persistence ───────────────────────────────────────────

    /** Reads the saved library from disk (once). Blocking file IO — call it
     *  from a background dispatcher. Safe to call repeatedly. */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        synchronized(lock) {
            if (loaded) return@synchronized
            val parsed = runCatching { gson.fromJson(indexFile.readText(), EmojiIndex::class.java) }.getOrNull()
            @Suppress("SENSELESS_COMPARISON")
            val safe = if (parsed == null || parsed.emojis == null || parsed.folders == null) EmojiIndex() else parsed
            // Drop entries whose image file has gone missing.
            val emojis = safe.emojis.filter { File(dir, it.file).exists() }
            val keep = emojis.map { it.id }.toSet()
            val folders = safe.folders.map { f -> f.copy(emojiIds = f.emojiIds.filter { it in keep }) }
            state = EmojiState(EmojiIndex(emojis, folders))
            loaded = true
        }
    }

    private fun persist(index: EmojiIndex) {
        val json = gson.toJson(index)
        ioScope.launch {
            synchronized(lock) {
                runCatching {
                    val tmp = File(dir, "index.json.tmp")
                    tmp.writeText(json)
                    if (!tmp.renameTo(indexFile)) indexFile.writeText(json)
                }
            }
        }
    }

    private fun commit(next: EmojiIndex) {
        state = EmojiState(next)
        persist(next)
    }

    // ── Token <-> emoji mapping ─────────────────────────────────────────

    /** The emoji this text character stands for, or null for any ordinary
     *  character. Reads [state], so it is safe to call from composition. */
    fun entryFor(c: Char): EmojiEntry? {
        val code = c.code
        if (code < TOKEN_BASE || code >= TOKEN_BASE + TOKEN_LIMIT) return null
        return state.byId[code - TOKEN_BASE]
    }

    fun charFor(entry: EmojiEntry): Char = (TOKEN_BASE + entry.id).toChar()

    fun containsEmoji(text: String): Boolean = text.any { entryFor(it) != null }

    /** Replaces every emoji token with a `:name:` shortcode. Used for the
     *  post's alt text, and when a draft leaves Textshot mode (so it never
     *  leaves invisible private-use characters behind in a normal post). */
    fun toShortcodes(text: String): String {
        if (text.none { entryFor(it) != null }) return text
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            val e = entryFor(c)
            if (e != null) sb.append(':').append(e.name).append(':') else sb.append(c)
        }
        return sb.toString()
    }

    /** Drops every emoji token, leaving no trace of its name — used for the
     *  post's alt text, which should never expose what a custom emoji was
     *  called. Each token is simply removed, not replaced with anything. */
    fun stripEmoji(text: String): String {
        if (text.none { entryFor(it) != null }) return text
        val sb = StringBuilder(text.length)
        for (c in text) if (entryFor(c) == null) sb.append(c)
        return sb.toString()
    }

    /** Which of this emoji's folders (not counting "All", which every emoji
     *  is implicitly part of) actually contain it right now. Used only to
     *  word the delete-confirmation prompt precisely, so it never implies a
     *  wider effect ("every folder") than what deleting it actually does. */
    fun foldersContaining(id: Int): List<EmojiFolder> = state.index.folders.filter { id in it.emojiIds }

    // ── Images ──────────────────────────────────────────────────────────

    fun fileFor(entry: EmojiEntry): File = File(dir, entry.file)

    /** Decoded emoji picture (cached) — used by [TextshotRenderer]. */
    fun bitmapFor(entry: EmojiEntry): Bitmap? {
        bitmapCache[entry.id]?.let { return it }
        val bmp = runCatching { BitmapFactory.decodeFile(fileFor(entry).absolutePath) }.getOrNull() ?: return null
        bitmapCache[entry.id] = bmp
        return bmp
    }

    fun bitmapForChar(c: Char): Bitmap? = entryFor(c)?.let { bitmapFor(it) }

    /** Same picture as an [ImageBitmap] for drawing inside the composer's text field. */
    fun imageBitmapForChar(c: Char): ImageBitmap? {
        val e = entryFor(c) ?: return null
        imageBitmapCache[e.id]?.let { return it }
        val img = bitmapFor(e)?.asImageBitmap() ?: return null
        imageBitmapCache[e.id] = img
        return img
    }

    // ── Folders ─────────────────────────────────────────────────────────

    /** Adds a new, empty folder after the existing ones and returns its id. */
    fun createFolder(name: String = DEFAULT_FOLDER_NAME): Int = synchronized(lock) {
        val cur = state.index
        val id = (cur.folders.maxOfOrNull { it.id } ?: 0) + 1
        commit(cur.copy(folders = cur.folders + EmojiFolder(id, name)))
        id
    }

    fun renameFolder(id: Int, name: String) = synchronized(lock) {
        val clean = name.trim().ifBlank { DEFAULT_FOLDER_NAME }
        val cur = state.index
        commit(cur.copy(folders = cur.folders.map { if (it.id == id) it.copy(name = clean) else it }))
    }

    /** Removes the folder itself. The emoji that were in it are untouched —
     *  they stay in the library (and in "All", and in any other folder they
     *  were also added to), since a folder is just a grouping over the
     *  shared library, not a separate copy of the emoji. */
    fun deleteFolder(id: Int) = synchronized(lock) {
        val cur = state.index
        commit(cur.copy(folders = cur.folders.filterNot { it.id == id }))
    }

    /** Deletes one emoji everywhere: out of the master library, out of every
     *  folder that had it, and its stored picture. Also frees its id for
     *  reuse by a future import. */
    fun deleteEmoji(id: Int) = synchronized(lock) {
        val cur = state.index
        val entry = cur.emojis.firstOrNull { it.id == id } ?: return@synchronized
        commit(
            cur.copy(
                emojis = cur.emojis - entry,
                folders = cur.folders.map { f -> if (id in f.emojiIds) f.copy(emojiIds = f.emojiIds - id) else f }
            )
        )
        bitmapCache.remove(id)?.recycle()
        imageBitmapCache.remove(id)
        runCatching { fileFor(entry).delete() }
    }

    /** "All" isn't a real folder, so long-pressing it can't delete "the All
     *  folder" — instead it bulk-deletes every emoji that isn't filed into
     *  any folder (i.e. the ones that only exist because of "All"). Returns
     *  how many were removed. */
    fun deleteOrphanEmoji(): Int = synchronized(lock) {
        val cur = state.index
        val filed = cur.folders.flatMapTo(mutableSetOf()) { it.emojiIds }
        val orphans = cur.emojis.filter { it.id !in filed }
        if (orphans.isEmpty()) return@synchronized 0
        val orphanFiles = orphans.map { fileFor(it) }
        commit(cur.copy(emojis = cur.emojis.filter { it.id in filed }))
        for (e in orphans) {
            bitmapCache.remove(e.id)?.recycle()
            imageBitmapCache.remove(e.id)
        }
        orphanFiles.forEach { runCatching { it.delete() } }
        orphans.size
    }

    /** How many emoji a long-press on "All" would remove (its orphan count),
     *  used to word that confirmation prompt. */
    fun orphanEmojiCount(): Int {
        val filed = state.index.folders.flatMapTo(mutableSetOf()) { it.emojiIds }
        return state.index.emojis.count { it.id !in filed }
    }

    /** Emoji shown for a tab: [folderId] null = "All" (everything). */
    fun emojiFor(folderId: Int?): List<EmojiEntry> {
        val st = state
        if (folderId == null) return st.index.emojis
        val folder = st.index.folders.firstOrNull { it.id == folderId } ?: return emptyList()
        return folder.emojiIds.mapNotNull { st.byId[it] }
    }

    // ── Import ──────────────────────────────────────────────────────────

    /** Imports single images the person picked. Returns how many were added. */
    suspend fun importImages(uris: List<Uri>, folderId: Int?): Int = withContext(Dispatchers.IO) {
        load()
        val items = uris.map { it to displayNameOf(it) }
        importItems(items, folderId)
    }

    /** Imports every image inside a picked folder (subfolders too, a few
     *  levels deep). Returns how many were added. */
    suspend fun importTree(treeUri: Uri, folderId: Int?): Int = withContext(Dispatchers.IO) {
        load()
        importItems(listImagesInTree(treeUri), folderId)
    }

    private fun importItems(items: List<Pair<Uri, String>>, folderId: Int?): Int {
        val staged = mutableListOf<Pair<Int, String>>() // id to raw name
        for ((uri, rawName) in items) {
            val id = reserveId() ?: break
            val ok = runCatching { decodeAndStore(uri, id) }.getOrDefault(false)
            if (ok) staged += id to rawName else synchronized(lock) { reservedIds.remove(id) }
        }
        if (staged.isEmpty()) return 0

        synchronized(lock) {
            val cur = state.index
            val taken = cur.emojis.map { it.name.lowercase() }.toMutableSet()
            val added = staged.map { (id, rawName) ->
                val name = uniqueName(sanitizeName(rawName), taken)
                taken += name.lowercase()
                EmojiEntry(id, name, "e_$id.png")
            }
            val addedIds = added.map { it.id }
            val folders = cur.folders.map { f ->
                if (f.id == folderId) f.copy(emojiIds = f.emojiIds + addedIds) else f
            }
            commit(EmojiIndex(cur.emojis + added, folders))
            reservedIds.removeAll(addedIds.toSet())
        }
        return staged.size
    }

    private fun reserveId(): Int? = synchronized(lock) {
        val used = state.index.emojis.map { it.id }.toHashSet()
        var id = 0
        while (id < TOKEN_LIMIT) {
            if (id !in used && id !in reservedIds) {
                reservedIds += id
                return@synchronized id
            }
            id++
        }
        null
    }

    private fun decodeAndStore(uri: Uri, id: Int): Boolean {
        val bmp = decodeScaled(uri, MAX_EMOJI_SIDE) ?: return false
        FileOutputStream(File(dir, "e_$id.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmapCache[id] = bmp
        return true
    }

    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap? {
        val cr = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val longest = max(raw.width, raw.height)
        if (longest <= maxSide) return raw
        val scale = maxSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            raw, (raw.width * scale).roundToInt().coerceAtLeast(1), (raw.height * scale).roundToInt().coerceAtLeast(1), true
        )
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    private fun displayNameOf(uri: Uri): String {
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "emoji"
    }

    private fun listImagesInTree(tree: Uri): List<Pair<Uri, String>> {
        val out = mutableListOf<Pair<Uri, String>>()
        val imageExt = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
        fun walk(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            runCatching {
                app.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val childId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        when {
                            mime == DocumentsContract.Document.MIME_TYPE_DIR -> if (depth < 3) walk(childId, depth + 1)
                            mime.startsWith("image/") || name.substringAfterLast('.', "").lowercase() in imageExt ->
                                out += DocumentsContract.buildDocumentUriUsingTree(tree, childId) to name
                        }
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return out.sortedBy { it.second.lowercase() }
    }

    /** File name -> a safe `:shortcode:` name: extension dropped, anything
     *  other than letters/digits/underscore/dash turned into "_". */
    private fun sanitizeName(raw: String): String {
        val base = raw.substringBeforeLast('.', raw)
        val cleaned = base.replace(Regex("[^A-Za-z0-9_\\-]+"), "_").trim('_').take(40)
        return cleaned.ifBlank { "emoji" }
    }

    private fun uniqueName(base: String, taken: Set<String>): String {
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("${base}_$n".lowercase() in taken) n++
        return "${base}_$n"
    }

    companion object {
        /** First code point of the private-use range emoji tokens live in. */
        const val TOKEN_BASE = 0xE000
        /** 0xE000..0xF8FF — the whole Basic Multilingual Plane private-use area. */
        const val TOKEN_LIMIT = 0x1900
        const val DEFAULT_FOLDER_NAME = "New Folder"
        /** Stored emoji are downscaled so their longest side is at most this. */
        private const val MAX_EMOJI_SIDE = 256

        @Volatile private var instance: EmojiStore? = null

        fun get(context: Context): EmojiStore =
            instance ?: synchronized(this) {
                instance ?: EmojiStore(context.applicationContext).also { instance = it }
            }

        /** True for any char in the private-use range (a *possible* token). */
        fun isTokenChar(c: Char): Boolean = c.code in TOKEN_BASE until (TOKEN_BASE + TOKEN_LIMIT)
    }
}
