package com.mediaviewer.platform

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

actual abstract class PlatformContext

/** The single app context on iOS (there's no Android-style Context). */
object IosContext : PlatformContext()

actual abstract class PlatformUri {
    /** The file:// or https:// URL this points at. */
    abstract val urlString: String
    actual abstract override fun toString(): String
    override fun equals(other: Any?): Boolean = other is PlatformUri && other.urlString == urlString
    override fun hashCode(): Int = urlString.hashCode()
}

class IosUri(override val urlString: String) : PlatformUri() {
    override fun toString(): String = urlString
}

/** A rendered image on iOS: its size and PNG-encoded pixels. */
actual class PlatformBitmap(val width: Int, val height: Int, val pngBytes: ByteArray)

actual open class IOException : Exception {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
    actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}

/** A HashMap guarded by a lock — enough for the way shared code uses
 *  ConcurrentHashMap (get / put / remove / iterate a snapshot). */
actual class ConcurrentHashMap<K, V> actual constructor() : MutableMap<K, V> {
    private val lock = reentrantLock()
    private val map = LinkedHashMap<K, V>()

    override val size: Int get() = lock.withLock { map.size }
    override fun isEmpty(): Boolean = lock.withLock { map.isEmpty() }
    override fun containsKey(key: K): Boolean = lock.withLock { map.containsKey(key) }
    override fun containsValue(value: V): Boolean = lock.withLock { map.containsValue(value) }
    override fun get(key: K): V? = lock.withLock { map[key] }
    override fun put(key: K, value: V): V? = lock.withLock { map.put(key, value) }
    override fun remove(key: K): V? = lock.withLock { map.remove(key) }
    override fun putAll(from: Map<out K, V>) = lock.withLock { map.putAll(from) }
    override fun clear() = lock.withLock { map.clear() }
    actual fun putIfAbsent(key: K, value: V): V? = lock.withLock { map[key] ?: run { map[key] = value; null } }

    // Snapshots, like ConcurrentHashMap's weakly-consistent views: safe to
    // iterate while other threads write. Removing through them writes back.
    override val keys: MutableSet<K>
        get() = lock.withLock { SnapshotSet(LinkedHashSet(map.keys)) { k -> remove(k) } }
    override val values: MutableCollection<V>
        get() = lock.withLock { ArrayList(map.values) }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = lock.withLock {
            SnapshotSet(map.entries.mapTo(LinkedHashSet()) { e -> Entry(e.key, e.value) }) { e -> remove(e.key) }
        }

    private inner class Entry(override val key: K, private var v: V) : MutableMap.MutableEntry<K, V> {
        override val value: V get() = v
        override fun setValue(newValue: V): V { val old = v; v = newValue; put(key, newValue); return old }
        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == v
        override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (v?.hashCode() ?: 0)
    }

    private class SnapshotSet<E>(private val items: MutableSet<E>, private val onRemove: (E) -> Unit) : MutableSet<E> by items {
        override fun remove(element: E): Boolean { onRemove(element); return items.remove(element) }
        override fun removeAll(elements: Collection<E>): Boolean { elements.forEach(onRemove); return items.removeAll(elements.toSet()) }
        override fun retainAll(elements: Collection<E>): Boolean {
            val gone = items.filter { it !in elements }
            gone.forEach(onRemove)
            return items.retainAll(elements.toSet())
        }
        override fun clear() { items.toList().forEach(onRemove); items.clear() }
        override fun iterator(): MutableIterator<E> {
            val it = items.iterator()
            return object : MutableIterator<E> {
                var last: E? = null
                override fun hasNext() = it.hasNext()
                override fun next(): E = it.next().also { last = it }
                @Suppress("UNCHECKED_CAST")
                override fun remove() { it.remove(); onRemove(last as E) }
            }
        }
    }

    override fun equals(other: Any?): Boolean = lock.withLock { map == other }
    override fun hashCode(): Int = lock.withLock { map.hashCode() }
    override fun toString(): String = lock.withLock { map.toString() }
}

actual class AtomicInteger {
    private val a: kotlin.concurrent.AtomicInt
    actual constructor() { a = kotlin.concurrent.AtomicInt(0) }
    actual constructor(initialValue: Int) { a = kotlin.concurrent.AtomicInt(initialValue) }
    actual fun get(): Int = a.value
    actual fun set(newValue: Int) { a.value = newValue }
    actual fun incrementAndGet(): Int = a.incrementAndGet()
    actual fun decrementAndGet(): Int = a.decrementAndGet()
    actual fun getAndIncrement(): Int = a.getAndIncrement()
    actual fun getAndDecrement(): Int = a.getAndDecrement()
    actual fun getAndSet(newValue: Int): Int = a.getAndSet(newValue)
    actual fun addAndGet(delta: Int): Int = a.addAndGet(delta)
    actual fun compareAndSet(expectedValue: Int, newValue: Int): Boolean = a.compareAndSet(expectedValue, newValue)
    override fun toString(): String = get().toString()
}

actual class AtomicLong {
    private val a: kotlin.concurrent.AtomicLong
    actual constructor() { a = kotlin.concurrent.AtomicLong(0L) }
    actual constructor(initialValue: Long) { a = kotlin.concurrent.AtomicLong(initialValue) }
    actual fun get(): Long = a.value
    actual fun set(newValue: Long) { a.value = newValue }
    actual fun incrementAndGet(): Long = a.incrementAndGet()
    actual fun getAndIncrement(): Long = a.getAndIncrement()
    actual fun addAndGet(delta: Long): Long = a.addAndGet(delta)
    actual fun getAndSet(newValue: Long): Long = a.getAndSet(newValue)
    actual fun compareAndSet(expectedValue: Long, newValue: Long): Boolean = a.compareAndSet(expectedValue, newValue)
    override fun toString(): String = get().toString()
}

actual class AtomicBoolean {
    private val a: kotlin.concurrent.AtomicInt
    actual constructor() { a = kotlin.concurrent.AtomicInt(0) }
    actual constructor(initialValue: Boolean) { a = kotlin.concurrent.AtomicInt(if (initialValue) 1 else 0) }
    actual fun get(): Boolean = a.value != 0
    actual fun set(newValue: Boolean) { a.value = if (newValue) 1 else 0 }
    actual fun getAndSet(newValue: Boolean): Boolean = a.getAndSet(if (newValue) 1 else 0) != 0
    actual fun compareAndSet(expectedValue: Boolean, newValue: Boolean): Boolean =
        a.compareAndSet(if (expectedValue) 1 else 0, if (newValue) 1 else 0)
    override fun toString(): String = get().toString()
}
