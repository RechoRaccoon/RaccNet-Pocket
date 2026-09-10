package com.mediaviewer.platform

import com.russhwolf.settings.SettingsListener
import com.russhwolf.settings.ObservableSettings
import kotlinx.browser.localStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * wasmJs Settings backend persisted in the browser's localStorage.
 *
 * Every value is stored as a string under a "raccnet." key prefix:
 * primitives via their string form, string sets as JSON arrays. This keeps
 * login tokens, theme, feed filters and every other preference across page
 * reloads — the earlier in-memory fallback lost all of them on refresh.
 *
 * Written against multiplatform-settings 1.3.0's API:
 * - put/remove/clear are synchronous (not suspend like 2.x)
 * - there is no StringSet API at all (getStringSet/putStringSet are plain
 *   methods here; PreferencesManager's commonMain extensions provide the
 *   public StringSet API on top of the String methods)
 * - there are no addStringSetListener/addStringSetOrNullListener overrides
 * - Listener is a top-level type (com.russhwolf.settings.Listener),
 *   not nested in Settings
 *
 * Listeners are same-tab only (the StorageEvent fires in *other* tabs, which
 * is out of scope for this app's usage).
 */
internal class LocalStorageObservableSettings : ObservableSettings {

    private val prefix = "raccnet."
    private val listeners = mutableListOf<(String) -> Unit>()
    private val setSerializer = ListSerializer(String.serializer())

    private fun raw(key: String): String? = try {
        localStorage.getItem(prefix + key)
    } catch (_: Exception) {
        null // private-mode / blocked storage: behave as empty
    }

    private fun putRaw(key: String, value: String) {
        try {
            localStorage.setItem(prefix + key, value)
        } catch (_: Exception) {
            // Storage full or unavailable — keep the session going in memory.
        }
        notify(key)
    }

    private fun notify(key: String) = listeners.toList().forEach { it(key) }

    override val keys: Set<String>
        get() = try {
            (0 until localStorage.length)
                .mapNotNull { localStorage.key(it) }
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }

    override val size: Int get() = keys.size
    override fun hasKey(key: String): Boolean = raw(key) != null

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        raw(key)?.toBooleanStrictOrNull() ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = raw(key)?.toBooleanStrictOrNull()

    override fun getInt(key: String, defaultValue: Int): Int =
        raw(key)?.toIntOrNull() ?: defaultValue
    override fun getIntOrNull(key: String): Int? = raw(key)?.toIntOrNull()

    override fun getLong(key: String, defaultValue: Long): Long =
        raw(key)?.toLongOrNull() ?: defaultValue
    override fun getLongOrNull(key: String): Long? = raw(key)?.toLongOrNull()

    override fun getFloat(key: String, defaultValue: Float): Float =
        raw(key)?.toFloatOrNull() ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = raw(key)?.toFloatOrNull()

    override fun getDouble(key: String, defaultValue: Double): Double =
        raw(key)?.toDoubleOrNull() ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = raw(key)?.toDoubleOrNull()

    override fun getString(key: String, defaultValue: String): String =
        raw(key) ?: defaultValue
    override fun getStringOrNull(key: String): String? = raw(key)

    // Not overrides: multiplatform-settings 1.3.0 has no StringSet API.
    // The PreferencesManager commonMain extensions provide the public API.
    fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
        getStringSetOrNull(key) ?: defaultValue
    fun getStringSetOrNull(key: String): Set<String>? = raw(key)?.let {
        try {
            Json.decodeFromString(setSerializer, it).toSet()
        } catch (_: Exception) {
            null
        }
    }

    // 1.3.0 Settings API is synchronous (not suspend like 2.x).
    override fun putBoolean(key: String, value: Boolean) = putRaw(key, value.toString())
    override fun putInt(key: String, value: Int) = putRaw(key, value.toString())
    override fun putLong(key: String, value: Long) = putRaw(key, value.toString())
    override fun putFloat(key: String, value: Float) = putRaw(key, value.toString())
    override fun putDouble(key: String, value: Double) = putRaw(key, value.toString())
    override fun putString(key: String, value: String) = putRaw(key, value)

    fun putStringSet(key: String, value: Set<String>) =
        putRaw(key, Json.encodeToString(setSerializer, value.toList()))

    override fun remove(key: String) {
        try {
            localStorage.removeItem(prefix + key)
        } catch (_: Exception) {
        }
        notify(key)
    }

    override fun clear() {
        val removed = keys
        try {
            removed.forEach { localStorage.removeItem(prefix + it) }
        } catch (_: Exception) {
        }
        removed.forEach(::notify)
    }

    private fun addListener(key: String, notify: () -> Unit): SettingsListener {
        val entry: (String) -> Unit = { changed -> if (changed == key) notify() }
        listeners += entry
        return SettingsListener { listeners -= entry }
    }

    override fun addBooleanListener(key: String, defaultValue: Boolean, callback: (Boolean) -> Unit): SettingsListener =
        addListener(key) { callback(getBoolean(key, defaultValue)) }
    override fun addIntListener(key: String, defaultValue: Int, callback: (Int) -> Unit): SettingsListener =
        addListener(key) { callback(getInt(key, defaultValue)) }
    override fun addLongListener(key: String, defaultValue: Long, callback: (Long) -> Unit): SettingsListener =
        addListener(key) { callback(getLong(key, defaultValue)) }
    override fun addFloatListener(key: String, defaultValue: Float, callback: (Float) -> Unit): SettingsListener =
        addListener(key) { callback(getFloat(key, defaultValue)) }
    override fun addDoubleListener(key: String, defaultValue: Double, callback: (Double) -> Unit): SettingsListener =
        addListener(key) { callback(getDouble(key, defaultValue)) }
    override fun addStringListener(key: String, defaultValue: String, callback: (String) -> Unit): SettingsListener =
        addListener(key) { callback(getString(key, defaultValue)) }
    override fun addStringOrNullListener(key: String, callback: (String?) -> Unit): SettingsListener =
        addListener(key) { callback(getStringOrNull(key)) }
    // NOTE: 1.3.0 has no addStringSetListener/addStringSetOrNullListener.
}

actual fun createObservableSettings(): ObservableSettings = LocalStorageObservableSettings()
