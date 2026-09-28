package com.mediaviewer.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.mediaviewer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Settings → UI Customization → "App Font".
 *
 * The list always starts with Audiowide (the default) and the Original
 * system font; every font the user imports is copied into the app's own
 * storage and added underneath. Readable anywhere as Compose state.
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

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        imported = runCatching {
            val arr = JSONArray(p.getString(KEY_IMPORTED, "[]"))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val path = o.optString("path")
                if (path.isNotBlank() && File(path).exists()) Entry(path, o.optString("name", "Imported Font")) else null
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
        if (path.isNullOrBlank() || !File(path).exists() || imported.any { it.id == path }) return
        imported = imported + Entry(path, prettyName(name ?: File(path).name))
        saveImported()
        select(path)
    }

    /** Copies the picked file into app storage, adds it to the list and
     *  selects it. Returns an error message, or null on success. */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: uri.lastPathSegment ?: "Imported Font"
            val ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("ttf", "otf", "ttc")) return@withContext "Please choose a .ttf or .otf font file"
            val dir = File(context.filesDir, "fonts").apply { mkdirs() }
            val dest = File(dir, "imported_${System.currentTimeMillis()}.$ext")
            val ok = context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }; true
            } ?: false
            if (!ok) return@withContext "Couldn't read that font file"
            // Make sure Android can actually parse it before it becomes the
            // app font — a broken file would otherwise crash the first layout.
            val valid = runCatching { android.graphics.Typeface.Builder(dest).build() != null }.getOrDefault(false)
            if (!valid) { dest.delete(); return@withContext "That file isn't a font Android can read" }
            withContext(Dispatchers.Main) {
                imported = imported + Entry(dest.absolutePath, prettyName(displayName))
                saveImported()
                select(dest.absolutePath)
            }
            null
        } catch (e: Exception) {
            "Couldn't load that font file: ${e.message}"
        }
    }

    fun remove(id: String) {
        val entry = imported.firstOrNull { it.id == id } ?: return
        imported = imported - entry
        saveImported()
        if (selected == id) select(AUDIOWIDE)
        runCatching { File(id).delete() }
    }

    private val families = HashMap<String, FontFamily?>()

    /** The FontFamily for a list entry — null means the system font. */
    fun familyFor(id: String): FontFamily? = families.getOrPut(id) {
        when (id) {
            AUDIOWIDE -> FontFamily(Font(R.font.audiowide))
            ORIGINAL -> null
            else -> File(id).takeIf { it.exists() }?.let { runCatching { FontFamily(Font(it)) }.getOrNull() }
        }
    }

    private fun prettyName(fileName: String): String =
        fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().ifBlank { "Imported Font" }
}
