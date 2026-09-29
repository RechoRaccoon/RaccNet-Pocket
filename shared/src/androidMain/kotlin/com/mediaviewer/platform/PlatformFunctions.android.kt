package com.mediaviewer.platform

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
actual fun nanoTime(): Long = System.nanoTime()
actual fun nowIsoString(): String = java.time.Instant.now().toString()
actual fun parseIsoInstantMillis(text: String): Long = java.time.Instant.parse(text).toEpochMilli()
actual fun parseIsoOffsetDateTimeMillis(text: String): Long = java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
actual fun randomUuidString(): String = java.util.UUID.randomUUID().toString()
actual fun urlDecode(text: String): String = java.net.URLDecoder.decode(text, "UTF-8")
actual fun urlEncode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8")
actual fun uriHost(url: String): String? = java.net.URI(url).host
@Suppress("NOTHING_TO_INLINE")
actual inline fun <T> synchronizedCompat(lock: Any, block: () -> T): T = synchronized(lock, block)
actual fun <T> synchronizedMutableList(): MutableList<T> = java.util.Collections.synchronizedList(mutableListOf<T>())

actual object Log {
    actual fun v(tag: String?, msg: String): Int = android.util.Log.v(tag, msg)
    actual fun d(tag: String?, msg: String): Int = android.util.Log.d(tag, msg)
    actual fun d(tag: String?, msg: String, tr: Throwable?): Int = android.util.Log.d(tag, msg, tr)
    actual fun i(tag: String?, msg: String): Int = android.util.Log.i(tag, msg)
    actual fun i(tag: String?, msg: String, tr: Throwable?): Int = android.util.Log.i(tag, msg, tr)
    actual fun w(tag: String?, msg: String): Int = android.util.Log.w(tag, msg)
    actual fun w(tag: String?, msg: String, tr: Throwable?): Int = android.util.Log.w(tag, msg, tr)
    actual fun w(tag: String?, tr: Throwable?): Int = android.util.Log.w(tag, tr)
    actual fun e(tag: String?, msg: String): Int = android.util.Log.e(tag, msg)
    actual fun e(tag: String?, msg: String, tr: Throwable?): Int = android.util.Log.e(tag, msg, tr)
}

actual fun parseUriComponents(url: String): UriComponents =
    java.net.URI(url).let { UriComponents(it.scheme, it.host, it.path) }

actual fun parseIsoLocalDateTimeUtcMillis(text: String): Long =
    java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

actual fun utcYearOf(epochMillis: Long): Int =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneOffset.UTC).year

actual fun <T> concurrentSetOf(): MutableSet<T> = java.util.concurrent.ConcurrentHashMap.newKeySet()
