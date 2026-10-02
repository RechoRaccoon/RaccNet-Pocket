package com.mediaviewer.util

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.readLocalFile
import com.mediaviewer.platform.sharedPreferences
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import platform.Foundation.NSFileManager
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.mkdir

/**
 * Settings → Export / Import App Data on iOS. The file is the same
 * "stellar-backup" JSON the Android app writes, so a backup made on either
 * platform can be imported on the other:
 *  - the app's settings (DataStore), including Blog/Review subscriptions;
 *  - UI toggles, Customize Hub and Add To's recent order (preference files).
 *
 * Images and videos attached to drafts, notes and bookmark-folder covers
 * travel in the same file, as a trailing "localMedia" section of base64
 * chunks. Backups without that section import as before.
 *
 * Left out, like on Android: sign-in tokens and per-account caches. The
 * Android-only parts of a backup (the custom font, VRM settings' avatar and
 * the AI-tagged dataset) are skipped on import here, and an iOS export
 * carries an empty tagged dataset.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
actual object AppBackup {
    private const val FORMAT = "stellar-backup"
    private const val VERSION = 1
    private val SHARED_PREFS = listOf("ui_toggles", "vrm_settings", "hub_layout", "list_recency", "supporter_local")

    private val EXCLUDED = setOf(
        "bsky_access_jwt", "bsky_refresh_jwt", "bsky_did", "bsky_handle", "bsky_service_url",
        "e621_username", "e621_api_key", "bsky_other_accounts_json",
        "hub_reviews_cache_json", "hub_blogs_cache_json", "hub_cache_hydrated_at", "hub_mutuals_cache_json",
        "profile_tab_cache_json_v2", "self_avatar_url_cache", "history_json",
        "follower_scan_completed", "follower_scan_last_run_ms", "follower_scan_cursor",
        "live_active_platform", "live_expires_at_ms", "vrm_avatar_uri", "custom_font_path"
    )
    private fun excluded(name: String) = name in EXCLUDED || name.startsWith(PrefKeys.BSKY_ACCOUNT_STATE_PREFIX)

    private fun pathOf(uri: PlatformUri) = uri.toString().removePrefix("file://")

    actual suspend fun export(context: PlatformContext, uri: PlatformUri): String = withContext(Dispatchers.IO) {
        var settingsCount = 0
        val prefs = context.dataStore.data.first()
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", currentTimeMillis())
            put("dataStore", buildJsonObject {
                for ((key, value) in prefs.asMap()) {
                    if (excluded(key.name)) continue
                    typed(value)?.let { put(key.name, it); settingsCount++ }
                }
            })
            put("sharedPrefs", buildJsonObject {
                for (name in SHARED_PREFS) {
                    put(name, buildJsonObject {
                        for ((k, v) in context.sharedPreferences(name).getAll()) {
                            if (v != null) typed(v)?.let { put(k, it) }
                        }
                    })
                }
            })
            put("taggedPosts", JsonArray(emptyList()))
        }
        // Everything but the closing brace, then the local media, streamed.
        val f = fopen(pathOf(uri), "wb") ?: error("Couldn't open the chosen file for writing")
        var mediaCount = 0
        var ok = true
        try {
            fun w(s: String) { if (ok) ok = put(f, s.encodeToByteArray()) }
            w(root.toString().dropLast(1))
            w(",\"localMedia\":{\"root\":" + JsonPrimitive(mediaRoot()).toString() + ",\"files\":[")
            for (rel in mediaFiles()) {
                val bytes = readLocalFile(mediaDir() + "/" + rel) ?: continue
                if (mediaCount > 0) w(",")
                w("{\"p\":" + JsonPrimitive(rel).toString() + ",\"d\":[")
                var at = 0
                while (at < bytes.size) {
                    val end = minOf(bytes.size, at + CHUNK)
                    w((if (at > 0) ",\"" else "\"") + Base64.encode(bytes, at, end) + "\"")
                    at = end
                }
                w("]}")
                mediaCount++
            }
            w("]}}")
        } finally {
            fclose(f)
        }
        if (!ok) error("Couldn't write the backup file")
        "Exported $settingsCount settings and $mediaCount media files"
    }

    actual suspend fun import(context: PlatformContext, uri: PlatformUri): String = withContext(Dispatchers.IO) {
        val data = readLocalFile(pathOf(uri)) ?: error("Couldn't open the chosen file")
        // The media section, when there is one, is the tail of the file; the
        // part before it is the plain settings JSON older backups consist of.
        val mediaAt = indexOf(data, MEDIA_MARKER.encodeToByteArray(), 0)
        val text = if (mediaAt >= 0) data.decodeToString(0, mediaAt) + "}" else data.decodeToString()
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: error("That isn't a Stellar backup file")
        if (runCatching { root["format"]?.jsonPrimitive?.content }.getOrNull() != FORMAT) error("That isn't a Stellar backup file")

        var oldMediaRoot: String? = null
        var mediaCount = 0
        if (mediaAt >= 0) runCatching {
            val (r, n) = readMedia(data, mediaAt)
            oldMediaRoot = r; mediaCount = n
        }
        // Drafts, notes and covers point at their files by full path; aim
        // those at this install's copy of the files.
        val newMediaRoot = mediaRoot()
        fun remap(name: String, s: String): String {
            val old = oldMediaRoot
            if (name != "supporter_local" || old.isNullOrEmpty() || old == newMediaRoot) return s
            return s.replace(old, newMediaRoot).replace(old.replace("/", "\\/"), newMediaRoot.replace("/", "\\/"))
        }

        var settingsCount = 0
        val ds = root["dataStore"] as? JsonObject
        context.dataStore.edit { prefs ->
            ds?.forEach { (name, el) ->
                if (excluded(name) || el !is JsonObject) return@forEach
                if (applyTyped(prefs, name, el)) settingsCount++
            }
        }

        (root["sharedPrefs"] as? JsonObject)?.forEach { (name, el) ->
            if (name !in SHARED_PREFS || el !is JsonObject) return@forEach
            val editor = context.sharedPreferences(name).edit()
            for ((k, v) in el) {
                val o = v as? JsonObject ?: continue
                val t = runCatching { o["t"]?.jsonPrimitive?.content }.getOrNull() ?: continue
                val value = o["v"] ?: continue
                runCatching {
                    when (t) {
                        "bool" -> editor.putBoolean(k, value.jsonPrimitive.boolean)
                        "int" -> editor.putInt(k, value.jsonPrimitive.int)
                        "long" -> editor.putLong(k, value.jsonPrimitive.long)
                        "float", "double" -> editor.putFloat(k, value.jsonPrimitive.float)
                        "string" -> editor.putString(k, remap(name, value.jsonPrimitive.content))
                        "set" -> editor.putStringSet(k, value.jsonArray.map { it.jsonPrimitive.content }.toSet())
                        else -> return@runCatching
                    }
                    settingsCount++
                }
            }
            editor.commit()
        }
        "Imported $settingsCount settings and $mediaCount media files"
    }

    private const val CHUNK = 180_000 // bytes per base64 chunk (a multiple of 3)
    private const val MEDIA_MARKER = ",\"localMedia\":{"

    private fun mediaDir(): String = IosPaths.filesDir() + "/local_media"

    /** The prefix every stored draft/note/cover file URI on this device starts with. */
    private fun mediaRoot(): String = "file://" + mediaDir()

    private fun list(dir: String): List<String>? =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null)?.map { it.toString() }

    /** Every file under local_media, as "folder/name" (or just "name"). */
    private fun mediaFiles(): List<String> {
        val out = ArrayList<String>()
        for (name in list(mediaDir()) ?: emptyList()) {
            val children = list(mediaDir() + "/" + name)
            if (children == null) out.add(name) else children.forEach { out.add("$name/$it") }
        }
        return out
    }

    private fun put(f: CPointer<FILE>, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var written = 0
        bytes.usePinned { pinned ->
            while (written < bytes.size) {
                val n = fwrite(pinned.addressOf(written), 1u, (bytes.size - written).toULong(), f).toInt()
                if (n <= 0) break
                written += n
            }
        }
        return written == bytes.size
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        val first = pattern[0]
        var i = from
        val last = data.size - pattern.size
        while (i <= last) {
            if (data[i] == first) {
                var j = 1
                while (j < pattern.size && data[i + j] == pattern[j]) j++
                if (j == pattern.size) return i
            }
            i++
        }
        return -1
    }

    /** Index of the quote closing the JSON string whose first character is at [from]. */
    private fun stringEnd(data: ByteArray, from: Int): Int {
        var i = from
        while (i < data.size) {
            val c = data[i].toInt()
            if (c == '\\'.code) i++ else if (c == '"'.code) return i
            i++
        }
        return -1
    }

    private fun jsonString(data: ByteArray, openQuote: Int, closeQuote: Int): String =
        Json.parseToJsonElement(data.decodeToString(openQuote, closeQuote + 1)).jsonPrimitive.content

    /** Walks the localMedia section starting at [start], writing each file
     *  into local_media. Returns the exporting device's media root and how
     *  many files were restored. */
    private fun readMedia(data: ByteArray, start: Int): Pair<String?, Int> {
        var root: String? = null
        val rootKey = "\"root\":\"".encodeToByteArray()
        val fileKey = "{\"p\":\"".encodeToByteArray()
        val dataKey = "\"d\":[".encodeToByteArray()
        var pos = start
        val firstFile = indexOf(data, fileKey, pos)
        val r = indexOf(data, rootKey, pos)
        if (r >= 0 && (firstFile < 0 || r < firstFile)) {
            val open = r + rootKey.size - 1
            val close = stringEnd(data, open + 1)
            if (close > 0) root = jsonString(data, open, close)
        }
        val dir = mediaDir()
        mkdir(dir, 0x1ED.convert())
        var count = 0
        while (true) {
            val p = indexOf(data, fileKey, pos)
            if (p < 0) break
            val open = p + fileKey.size - 1
            val close = stringEnd(data, open + 1)
            if (close < 0) break
            val rel = jsonString(data, open, close)
            val d = indexOf(data, dataKey, close)
            if (d < 0) break
            pos = d + dataKey.size
            val parts = rel.split('/')
            val safe = rel.isNotBlank() && parts.size <= 2 && parts.none { it.isEmpty() || it == "." || it == ".." }
            if (safe && parts.size == 2) mkdir(dir + "/" + parts[0], 0x1ED.convert())
            val f = if (safe) fopen("$dir/$rel", "wb") else null
            var ok = f != null
            try {
                while (pos < data.size) {
                    val c = data[pos].toInt()
                    if (c == '"'.code) {
                        val e = indexOf(data, byteArrayOf('"'.code.toByte()), pos + 1)
                        if (e < 0) { pos = data.size; break }
                        if (f != null && ok) ok = put(f, Base64.decode(data, pos + 1, e))
                        pos = e + 1
                    } else if (c == ']'.code) {
                        pos++; break
                    } else {
                        pos++
                    }
                }
            } finally {
                if (f != null) fclose(f)
            }
            if (ok) count++
        }
        return root to count
    }

    private fun typed(value: Any): JsonObject? = when (value) {
        is Boolean -> buildJsonObject { put("t", "bool"); put("v", value) }
        is Int -> buildJsonObject { put("t", "int"); put("v", value) }
        is Long -> buildJsonObject { put("t", "long"); put("v", value) }
        is Float -> buildJsonObject { put("t", "float"); put("v", value) }
        is Double -> buildJsonObject { put("t", "double"); put("v", value) }
        is String -> buildJsonObject { put("t", "string"); put("v", value) }
        is Set<*> -> buildJsonObject {
            put("t", "set")
            put("v", JsonArray(value.filterIsInstance<String>().map { JsonPrimitive(it) }))
        }
        else -> null
    }

    private fun applyTyped(prefs: MutablePreferences, name: String, o: JsonObject): Boolean {
        val t = runCatching { o["t"]?.jsonPrimitive?.content }.getOrNull() ?: return false
        val v: JsonElement = o["v"] ?: return false
        return runCatching {
            when (t) {
                "bool" -> prefs[booleanPreferencesKey(name)] = v.jsonPrimitive.boolean
                "int" -> prefs[intPreferencesKey(name)] = v.jsonPrimitive.int
                "long" -> prefs[longPreferencesKey(name)] = v.jsonPrimitive.long
                "float" -> prefs[floatPreferencesKey(name)] = v.jsonPrimitive.float
                "double" -> prefs[doublePreferencesKey(name)] = v.jsonPrimitive.double
                "string" -> prefs[stringPreferencesKey(name)] = v.jsonPrimitive.content
                "set" -> prefs[stringSetPreferencesKey(name)] = v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                else -> return@runCatching false
            }
            true
        }.getOrDefault(false)
    }
}
