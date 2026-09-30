package com.mediaviewer.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import com.mediaviewer.json.JSONArray
import com.mediaviewer.json.JSONObject
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Settings → UI Customization → "App Font".
 *
 * The list always starts with Audiowide (the default) and the Original
 * system font; every font the user imports is copied into the app's own
 * storage and added underneath. Readable anywhere as Compose state.
 * (Importing fonts is Android only; iOS has Audiowide and Original.)
 */
object FontStore {
    const val AUDIOWIDE = "audiowide"
    const val ORIGINAL = "original"

    private const val PREFS = "app_fonts"
    private const val KEY_SELECTED = "selected"
    private const val KEY_IMPORTED = "imported"
    private const val KEY_LEGACY_ADOPTED = "legacy_adopted"

    /** One entry in the font list. [id] is [AUDIOWIDE], [ORIGINAL] or the
     *  absolute path of an imported font file. */
    data class Entry(val id: String, val name: String) {
        val imported: Boolean get() = id != AUDIOWIDE && id != ORIGINAL
    }

    private var prefs: SharedPreferences? = null

    var selected by mutableStateOf(AUDIOWIDE)
        private set

    var imported by mutableStateOf<List<Entry>>(emptyList())
        private set

    val entries: List<Entry>
        get() = listOf(Entry(AUDIOWIDE, "Audiowide"), Entry(ORIGINAL, "Original")) + imported

    val selectedName: String
        get() = entries.firstOrNull { it.id == selected }?.name ?: "Audiowide"

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences(PREFS)
        prefs = p
        imported = runCatching {
            val arr = JSONArray(p.getString(KEY_IMPORTED, "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val path = o.optString("path")
                if (path.isNotBlank() && FontFiles.exists(path)) Entry(path, o.optString("name", "Imported Font")) else null
            }
        }.getOrDefault(emptyList())
        val sel = p.getString(KEY_SELECTED, AUDIOWIDE) ?: AUDIOWIDE
        selected = if (sel == AUDIOWIDE || sel == ORIGINAL || imported.any { it.id == sel }) sel else AUDIOWIDE
    }

    fun select(id: String) {
        selected = id
        prefs?.edit()?.putString(KEY_SELECTED, id)?.apply()
    }

    private fun saveImported() {
        val arr = JSONArray()
        imported.forEach { arr.put(JSONObject().put("path", it.id).put("name", it.name)) }
        prefs?.edit()?.putString(KEY_IMPORTED, arr.toString())?.apply()
    }

    /** A font picked with the old single-font setting becomes the first
     *  imported entry (and stays selected), once. */
    fun adoptLegacy(path: String?, name: String?) {
        val p = prefs ?: return
        if (p.getBoolean(KEY_LEGACY_ADOPTED, false)) return
        p.edit().putBoolean(KEY_LEGACY_ADOPTED, true).apply()
        if (path.isNullOrBlank() || !FontFiles.exists(path) || imported.any { it.id == path }) return
        imported = imported + Entry(path, prettyName(name ?: path.substringAfterLast('/')))
        saveImported()
        select(path)
    }

    /** Copies the picked file into app storage, adds it to the list and
     *  selects it. Returns an error message, or null on success. */
    suspend fun import(context: PlatformContext, uri: PlatformUri): String? {
        val result = withContext(Dispatchers.IO) {
            try { FontFiles.copyIn(context, uri) } catch (e: Exception) { FontCopied.Error("Couldn't load that font file: ${e.message}") }
        }
        return when (result) {
            is FontCopied.Error -> result.message
            is FontCopied.Ok -> {
                withContext(Dispatchers.Main) {
                    imported = imported + Entry(result.path, prettyName(result.displayName))
                    saveImported()
                    select(result.path)
                }
                null
            }
        }
    }

    fun remove(id: String) {
        val entry = imported.firstOrNull { it.id == id } ?: return
        imported = imported - entry
        saveImported()
        if (selected == id) select(AUDIOWIDE)
        runCatching { FontFiles.delete(id) }
    }

    private val families = HashMap<String, FontFamily?>()

    /** The FontFamily for an imported entry (null = unreadable). */
    private fun importedFamily(id: String): FontFamily? = families.getOrPut(id) {
        if (FontFiles.exists(id)) runCatching { FontFiles.family(id) }.getOrNull() else null
    }

    /** The FontFamily for a list entry — null means the system font. */
    @Composable
    fun familyFor(id: String): FontFamily? = when (id) {
        AUDIOWIDE -> audiowideFontFamily()
        ORIGINAL -> null
        else -> remember(id) { importedFamily(id) }
    }

    private fun prettyName(fileName: String): String =
        fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().ifBlank { "Imported Font" }
}

/** Audiowide, the app's own font. */
@Composable
expect fun audiowideFontFamily(): FontFamily

/** What copying a picked font into the app gave. */
sealed class FontCopied {
    class Ok(val path: String, val displayName: String) : FontCopied()
    class Error(val message: String) : FontCopied()
}

/** The file side of [FontStore]. */
expect object FontFiles {
    fun exists(path: String): Boolean
    fun delete(path: String)
    fun family(path: String): FontFamily
    /** Copies a picked font into the app's storage (blocking I/O). */
    fun copyIn(context: PlatformContext, uri: PlatformUri): FontCopied
}
