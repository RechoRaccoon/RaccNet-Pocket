package com.mediaviewer.util

import com.mediaviewer.tagging.TagExportedPost

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.mediaviewer.tagging.TagDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Item 7: Settings → Data → Export / Import — every local setting in ONE
 * file, so moving to a build with a different signing key (a fresh install)
 * doesn't lose anything:
 *  - the app's settings (DataStore), including the Blog/Review
 *    subscription lists;
 *  - UI toggles and every VRM-mode / Live setting (SharedPreferences);
 *  - the custom app font, embedded;
 *  - the main (on-device) AI-tagged post dataset. On import it REPLACES
 *    the main dataset — it isn't added as an extra imported one.
 *
 * Images and videos attached to drafts, notes and bookmark-folder covers
 * (the files under local_media) travel in the same file too, as a trailing
 * "localMedia" section. It is written and read as a stream, in small
 * base64 chunks, so a large video never has to fit in memory. Backups made
 * before this section existed simply don't have it and import as before.
 *
 * Left out on purpose: sign-in tokens/passwords (you sign in again, which
 * is safer than a file carrying live credentials), per-account caches,
 * and the VRM avatar file pointer (the file itself isn't in the backup).
 */
actual object AppBackup {
    private const val FORMAT = "stellar-backup"
    private const val VERSION = 1
    // "hub_layout": Settings → Customize Hub (row order/toggles + Hub lists).
    // "list_recency": Add To's most-recently-added-to order.
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

    /** Writes the backup to [uri]. Returns a short summary. */
    actual suspend fun export(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        root.addProperty("exportedAt", System.currentTimeMillis())

        // App settings.
        val ds = JsonObject()
        val prefs = context.dataStore.data.first()
        for ((key, value) in prefs.asMap()) {
            if (excluded(key.name)) continue
            typed(value)?.let { ds.add(key.name, it) }
        }
        root.add("dataStore", ds)

        // UI toggles, VRM and Live settings.
        val sp = JsonObject()
        for (name in SHARED_PREFS) {
            val obj = JsonObject()
            for ((k, v) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (v != null) typed(v)?.let { obj.add(k, it) }
            }
            sp.add(name, obj)
        }
        root.add("sharedPrefs", sp)

        // Custom font, embedded.
        prefs[PrefKeys.CUSTOM_FONT_PATH]?.let { path ->
            val f = File(path)
            if (f.exists() && f.length() < 30L * 1024 * 1024) {
                val font = JsonObject()
                font.addProperty("fileName", f.name)
                font.addProperty("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                root.add("font", font)
            }
        }

        // Main AI-tagged dataset.
        val posts = TagDatabase.get(context).localPostsForExport()
        val arr = JsonArray()
        for (p in posts) {
            val o = JsonObject()
            o.addProperty("u", p.postUri); o.addProperty("c", p.cid); o.addProperty("m", p.mediaUrl)
            val tags = JsonArray()
            for ((t, conf) in p.tags) { val to = JsonArray(); to.add(t); to.add(conf); tags.add(to) }
            o.add("t", tags)
            arr.add(o)
        }
        root.add("taggedPosts", arr)

        // Local media (drafts, notes, folder covers), streamed after the rest.
        val mediaDir = mediaDir(context)
        val mediaFiles = mediaDir.walkTopDown().filter { it.isFile }.toList()
        var mediaCount = 0
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.bufferedWriter(Charsets.UTF_8).use { w ->
                val json = root.toString()
                w.write(json, 0, json.length - 1) // everything but the closing brace
                w.write(",\"localMedia\":{\"root\":" + JsonPrimitive(mediaRoot(context)).toString() + ",\"files\":[")
                val buf = ByteArray(CHUNK)
                for (f in mediaFiles) {
                    val rel = f.relativeTo(mediaDir).path.replace(File.separatorChar, '/')
                    runCatching { f.inputStream() }.getOrNull()?.use { input ->
                        if (mediaCount > 0) w.write(",")
                        w.write("{\"p\":" + JsonPrimitive(rel).toString() + ",\"d\":[")
                        var first = true
                        while (true) {
                            var n = 0
                            while (n < buf.size) {
                                val r = input.read(buf, n, buf.size - n)
                                if (r < 0) break
                                n += r
                            }
                            if (n <= 0) break
                            if (!first) w.write(",")
                            first = false
                            w.write("\"")
                            w.write(Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                            w.write("\"")
                            if (n < buf.size) break
                        }
                        w.write("]}")
                        mediaCount++
                    }
                }
                w.write("]}}")
            }
        } ?: error("Couldn't open the chosen file for writing")
        "Exported ${ds.size()} settings, ${posts.size} tagged posts and $mediaCount media files"
    }

    /** Reads a backup from [uri] and applies it. Throws on a bad file. The
     *  app should be restarted afterwards so every screen reloads. */
    actual suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        // Read as a stream: everything is parsed into [root] as before, except
        // the optional "localMedia" section, whose files go straight to disk.
        var oldMediaRoot: String? = null
        var mediaCount = 0
        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open the chosen file")
        val root = runCatching {
            input.use { stream ->
                val reader = JsonReader(stream.bufferedReader(Charsets.UTF_8))
                val obj = JsonObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name == "localMedia") {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "root" -> oldMediaRoot = reader.nextString()
                                "files" -> mediaCount += readMediaFiles(context, reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        obj.add(name, JsonParser.parseReader(reader))
                    }
                }
                reader.endObject()
                obj
            }
        }.getOrNull() ?: error("That isn't a Stellar backup file")
        if (root.get("format")?.asString != FORMAT) error("That isn't a Stellar backup file")
        // Drafts, notes and covers point at their files by full path; aim
        // those at this install's copy of the files.
        val newMediaRoot = mediaRoot(context)
        fun remap(name: String, s: String): String {
            val old = oldMediaRoot
            if (name != "supporter_local" || old.isNullOrEmpty() || old == newMediaRoot) return s
            return s.replace(old, newMediaRoot).replace(old.replace("/", "\\/"), newMediaRoot.replace("/", "\\/"))
        }

        // Font first, so its new path can be written with the settings.
        var fontPath: String? = null
        root.getAsJsonObject("font")?.let { font ->
            runCatching {
                val bytes = Base64.decode(font.get("data").asString, Base64.DEFAULT)
                val ext = font.get("fileName")?.asString?.substringAfterLast('.', "ttf") ?: "ttf"
                val dir = File(context.filesDir, "fonts").apply { mkdirs() }
                val f = File(dir, "custom_font_${System.currentTimeMillis()}.$ext")
                f.writeBytes(bytes)
                fontPath = f.absolutePath
            }
        }

        var settingsCount = 0
        val ds = root.getAsJsonObject("dataStore")
        context.dataStore.edit { prefs ->
            ds?.entrySet()?.forEach { (name, el) ->
                if (excluded(name) || !el.isJsonObject) return@forEach
                if (applyTyped(prefs, name, el.asJsonObject)) settingsCount++
            }
            fontPath?.let { prefs[PrefKeys.CUSTOM_FONT_PATH] = it }
        }

        root.getAsJsonObject("sharedPrefs")?.entrySet()?.forEach { (name, el) ->
            if (name !in SHARED_PREFS || !el.isJsonObject) return@forEach
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for ((k, v) in el.asJsonObject.entrySet()) {
                if (!v.isJsonObject) continue
                val o = v.asJsonObject
                val t = o.get("t")?.asString ?: continue
                val value = o.get("v") ?: continue
                runCatching {
                    when (t) {
                        "bool" -> editor.putBoolean(k, value.asBoolean)
                        "int" -> editor.putInt(k, value.asInt)
                        "long" -> editor.putLong(k, value.asLong)
                        "float", "double" -> editor.putFloat(k, value.asFloat)
                        "string" -> editor.putString(k, remap(name, value.asString))
                        "set" -> editor.putStringSet(k, value.asJsonArray.map { it.asString }.toSet())
                    }
                    settingsCount++
                }
            }
            editor.commit()
        }

        var postCount = 0
        root.getAsJsonArray("taggedPosts")?.let { arr ->
            val posts = arr.mapNotNull { el ->
                runCatching {
                    val o = el.asJsonObject
                    TagExportedPost(
                        postUri = o.get("u").asString,
                        cid = o.get("c")?.asString ?: "",
                        mediaUrl = o.get("m")?.asString ?: "",
                        tags = o.getAsJsonArray("t")?.map { t -> t.asJsonArray.let { it[0].asString to it[1].asFloat } } ?: emptyList()
                    )
                }.getOrNull()
            }
            if (posts.isNotEmpty()) {
                TagDatabase.get(context).replaceLocalDataset(posts)
                postCount = posts.size
            }
        }
        "Imported $settingsCount settings, $postCount tagged posts and $mediaCount media files"
    }

    private const val CHUNK = 180_000 // bytes per base64 chunk (a multiple of 3)

    private fun mediaDir(context: Context) = File(context.filesDir, "local_media")

    /** The prefix every stored draft/note/cover file URI on this device starts with. */
    private fun mediaRoot(context: Context) = Uri.fromFile(mediaDir(context)).toString()

    /** Reads the "files" array of the localMedia section, writing each file
     *  into local_media. Returns how many were restored. */
    private fun readMediaFiles(context: Context, reader: JsonReader): Int {
        val dir = mediaDir(context)
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            var target: File? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "p" -> {
                        val rel = reader.nextString()
                        val parts = rel.split('/')
                        target = if (rel.isBlank() || parts.any { it.isEmpty() || it == "." || it == ".." }) null else File(dir, rel)
                    }
                    "d" -> {
                        val f = target
                        if (f == null) { reader.skipValue() } else {
                            f.parentFile?.mkdirs()
                            f.outputStream().use { out ->
                                reader.beginArray()
                                while (reader.hasNext()) out.write(Base64.decode(reader.nextString(), Base64.DEFAULT))
                                reader.endArray()
                            }
                            count++
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        reader.endArray()
        return count
    }

    private fun typed(value: Any): JsonObject? {
        val o = JsonObject()
        when (value) {
            is Boolean -> { o.addProperty("t", "bool"); o.addProperty("v", value) }
            is Int -> { o.addProperty("t", "int"); o.addProperty("v", value) }
            is Long -> { o.addProperty("t", "long"); o.addProperty("v", value) }
            is Float -> { o.addProperty("t", "float"); o.addProperty("v", value) }
            is Double -> { o.addProperty("t", "double"); o.addProperty("v", value) }
            is String -> { o.addProperty("t", "string"); o.addProperty("v", value) }
            is Set<*> -> {
                o.addProperty("t", "set")
                val arr = JsonArray()
                value.filterIsInstance<String>().forEach { arr.add(it) }
                o.add("v", arr)
            }
            else -> return null
        }
        return o
    }

    private fun applyTyped(prefs: androidx.datastore.preferences.core.MutablePreferences, name: String, o: JsonObject): Boolean {
        val t = o.get("t")?.asString ?: return false
        val v = o.get("v") ?: return false
        return runCatching {
            when (t) {
                "bool" -> prefs[booleanPreferencesKey(name)] = v.asBoolean
                "int" -> prefs[intPreferencesKey(name)] = v.asInt
                "long" -> prefs[longPreferencesKey(name)] = v.asLong
                "float" -> prefs[floatPreferencesKey(name)] = v.asFloat
                "double" -> prefs[doublePreferencesKey(name)] = v.asDouble
                "string" -> prefs[stringPreferencesKey(name)] = v.asString
                "set" -> prefs[stringSetPreferencesKey(name)] = v.asJsonArray.map { it.asString }.toSet()
                else -> return@runCatching false
            }
            true
        }.getOrDefault(false)
    }
}
