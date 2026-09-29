package com.mediaviewer.platform

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

actual interface SharedPreferences {
    actual fun getString(key: String, defValue: String?): String?
    actual fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    actual fun getInt(key: String, defValue: Int): Int
    actual fun getLong(key: String, defValue: Long): Long
    actual fun getFloat(key: String, defValue: Float): Float
    actual fun getBoolean(key: String, defValue: Boolean): Boolean
    actual fun contains(key: String): Boolean
    actual fun getAll(): Map<String, *>
    actual fun edit(): Editor

    actual interface Editor {
        actual fun putString(key: String, value: String?): Editor
        actual fun putStringSet(key: String, values: Set<String>?): Editor
        actual fun putInt(key: String, value: Int): Editor
        actual fun putLong(key: String, value: Long): Editor
        actual fun putFloat(key: String, value: Float): Editor
        actual fun putBoolean(key: String, value: Boolean): Editor
        actual fun remove(key: String): Editor
        actual fun clear(): Editor
        actual fun commit(): Boolean
        actual fun apply()
    }
}

private val prefsCache = HashMap<String, FilePreferences>()
private val prefsCacheLock = reentrantLock()

actual fun PlatformContext.sharedPreferences(name: String): SharedPreferences =
    prefsCacheLock.withLock { prefsCache.getOrPut(name) { FilePreferences(IosPaths.preferencesDir() + "/" + name + ".json") } }

/** SharedPreferences stored as one JSON file per name (like Android's XML
 *  files in shared_prefs/). Values keep their types. */
internal class FilePreferences(private val path: String) : SharedPreferences {
    private val lock = reentrantLock()
    private var loaded = false
    private val values = LinkedHashMap<String, Any>()

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val bytes = readLocalFile(path) ?: return
        val root = runCatching { com.mediaviewer.json.StellarJson.default.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull() ?: return
        for ((k, entry) in root) {
            val o = runCatching { entry.jsonObject }.getOrNull() ?: continue
            val t = o["t"]?.jsonPrimitive?.content ?: continue
            val v = o["v"] ?: continue
            val value: Any? = runCatching {
                when (t) {
                    "s" -> v.jsonPrimitive.content
                    "i" -> v.jsonPrimitive.content.toInt()
                    "l" -> v.jsonPrimitive.content.toLong()
                    "f" -> v.jsonPrimitive.content.toFloat()
                    "b" -> v.jsonPrimitive.booleanOrNull
                    "set" -> v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    else -> null
                }
            }.getOrNull()
            if (value != null) values[k] = value
        }
    }

    private fun save(): Boolean {
        val root = buildJsonObject {
            for ((k, v) in values) {
                put(k, buildJsonObject {
                    when (v) {
                        is String -> { put("t", "s"); put("v", v) }
                        is Int -> { put("t", "i"); put("v", v) }
                        is Long -> { put("t", "l"); put("v", v) }
                        is Float -> { put("t", "f"); put("v", v) }
                        is Boolean -> { put("t", "b"); put("v", v) }
                        is Set<*> -> { put("t", "set"); put("v", JsonArray(v.map { JsonPrimitive(it.toString()) })) }
                    }
                })
            }
        }
        return writeLocalFile(path, root.toString().encodeToByteArray())
    }

    private inline fun <T> read(block: () -> T): T = lock.withLock { ensureLoaded(); block() }

    override fun getString(key: String, defValue: String?): String? = read { values[key] as? String ?: defValue }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = read { (values[key] as? Set<String>)?.toSet() ?: defValues }
    override fun getInt(key: String, defValue: Int): Int = read { values[key] as? Int ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = read { values[key] as? Long ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = read { values[key] as? Float ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = read { values[key] as? Boolean ?: defValue }
    override fun contains(key: String): Boolean = read { values.containsKey(key) }
    override fun getAll(): Map<String, *> = read { LinkedHashMap(values) }
    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clearAll = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean = lock.withLock {
            ensureLoaded()
            if (clearAll) values.clear()
            for ((k, v) in changes) if (v == null) values.remove(k) else values[k] = v
            save()
        }
        override fun apply() { commit() }
    }
}
