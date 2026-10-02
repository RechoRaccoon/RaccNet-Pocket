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
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.readLocalFile
import com.mediaviewer.platform.sharedPreferences
import com.mediaviewer.platform.writeLocalFile
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

/**
 * Settings → Export / Import App Data on iOS. The file is the same
 * "stellar-backup" JSON the Android app writes, so a backup made on either
 * platform can be imported on the other:
 *  - the app's settings (DataStore), including Blog/Review subscriptions;
 *  - UI toggles, Customize Hub and Add To's recent order (preference files).
 *
 * Left out, like on Android: sign-in tokens and per-account caches. The
 * Android-only parts of a backup (the custom font, VRM settings' avatar and
 * the AI-tagged dataset) are skipped on import here, and an iOS export
 * carries an empty tagged dataset.
 */
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
        if (!writeLocalFile(pathOf(uri), root.toString().encodeToByteArray())) error("Couldn't open the chosen file for writing")
        "Exported $settingsCount settings"
    }

    actual suspend fun import(context: PlatformContext, uri: PlatformUri): String = withContext(Dispatchers.IO) {
        val text = readLocalFile(pathOf(uri))?.decodeToString() ?: error("Couldn't open the chosen file")
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: error("That isn't a Stellar backup file")
        if (runCatching { root["format"]?.jsonPrimitive?.content }.getOrNull() != FORMAT) error("That isn't a Stellar backup file")

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
                        "string" -> editor.putString(k, value.jsonPrimitive.content)
                        "set" -> editor.putStringSet(k, value.jsonArray.map { it.jsonPrimitive.content }.toSet())
                        else -> return@runCatching
                    }
                    settingsCount++
                }
            }
            editor.commit()
        }
        "Imported $settingsCount settings"
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
