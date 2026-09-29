package com.mediaviewer.platform

/*
 * Platform types that shared code passes around without looking inside.
 * On Android each one IS the Android/Java class (actual typealias), so
 * Android code keeps using android.content.Context, android.net.Uri,
 * android.graphics.Bitmap, java.io.IOException … exactly as before; iOS has
 * its own small implementations.
 */

/** android.content.Context on Android. */
expect abstract class PlatformContext

/** android.net.Uri on Android; a file/remote URL on iOS. */
expect abstract class PlatformUri {
    // android.net.Uri declares toString() abstract.
    abstract override fun toString(): String
}

/** android.graphics.Bitmap on Android; a UIImage-backed image on iOS. */
expect class PlatformBitmap

/** java.io.IOException on Android. */
expect open class IOException : Exception {
    constructor()
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
}

/** java.util.concurrent.ConcurrentHashMap on Android. */
expect class ConcurrentHashMap<K, V>() : MutableMap<K, V> {
    fun putIfAbsent(key: K, value: V): V?
}

/** java.util.concurrent.atomic.AtomicInteger on Android. */
expect class AtomicInteger {
    constructor()
    constructor(initialValue: Int)
    fun get(): Int
    fun set(newValue: Int)
    fun incrementAndGet(): Int
    fun decrementAndGet(): Int
    fun getAndIncrement(): Int
    fun getAndDecrement(): Int
    fun getAndSet(newValue: Int): Int
    fun addAndGet(delta: Int): Int
    fun compareAndSet(expectedValue: Int, newValue: Int): Boolean
}

/** java.util.concurrent.atomic.AtomicLong on Android. */
expect class AtomicLong {
    constructor()
    constructor(initialValue: Long)
    fun get(): Long
    fun set(newValue: Long)
    fun incrementAndGet(): Long
    fun getAndIncrement(): Long
    fun addAndGet(delta: Long): Long
    fun getAndSet(newValue: Long): Long
    fun compareAndSet(expectedValue: Long, newValue: Long): Boolean
}

/** java.util.concurrent.atomic.AtomicBoolean on Android. */
expect class AtomicBoolean {
    constructor()
    constructor(initialValue: Boolean)
    fun get(): Boolean
    fun set(newValue: Boolean)
    fun getAndSet(newValue: Boolean): Boolean
    fun compareAndSet(expectedValue: Boolean, newValue: Boolean): Boolean
}
