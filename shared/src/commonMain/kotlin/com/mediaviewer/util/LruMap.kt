package com.mediaviewer.util

/**
 * A small least-recently-used map: reading or writing an entry makes it the
 * newest, and once there are more than [maxSize] entries the oldest is
 * dropped — the same behaviour as an access-ordered java.util.LinkedHashMap
 * with removeEldestEntry, written in common Kotlin. Not thread-safe on its
 * own (callers lock around it).
 */
class LruMap<K, V>(private val maxSize: Int) {
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? {
        val v = map.remove(key) ?: return null
        map[key] = v
        return v
    }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > maxSize) {
            val it = map.keys.iterator()
            it.next(); it.remove()
        }
    }

    fun remove(key: K): V? = map.remove(key)
    fun clear() = map.clear()
    val size: Int get() = map.size
}
