package com.mediaviewer.platform

/** System.currentTimeMillis() on Android. */
expect fun currentTimeMillis(): Long

/** System.nanoTime() on Android. */
expect fun nanoTime(): Long

/** java.time.Instant.now().toString() on Android — an ISO-8601 UTC
 *  timestamp like 2026-09-29T17:04:05.123Z (ATProto `createdAt`). */
expect fun nowIsoString(): String

/** Epoch millis for an ISO-8601 instant ("…Z"), or throws if it can't be
 *  parsed (java.time.Instant.parse(s).toEpochMilli() on Android). */
expect fun parseIsoInstantMillis(text: String): Long

/** Epoch millis for an ISO-8601 date-time with an offset ("…+02:00"), or
 *  throws (java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()). */
expect fun parseIsoOffsetDateTimeMillis(text: String): Long

/** java.util.UUID.randomUUID().toString() on Android. */
expect fun randomUuidString(): String

/** java.net.URLDecoder.decode(s, "UTF-8") on Android. */
expect fun urlDecode(text: String): String

/** java.net.URLEncoder.encode(s, "UTF-8") on Android (form encoding:
 *  spaces become '+'). */
expect fun urlEncode(text: String): String

/** java.net.URI(url).host on Android — null when the URL has no host;
 *  throws for a malformed URL. */
expect fun uriHost(url: String): String?

/** `synchronized(lock) { block() }` on Android. */
expect inline fun <T> synchronizedCompat(lock: Any, block: () -> T): T

/** java.util.Collections.synchronizedList(ArrayList()) on Android. */
expect fun <T> synchronizedMutableList(): MutableList<T>

/** Android's Log (android.util.Log); NSLog on iOS. Same signatures, so
 *  shared code keeps calling Log.d / Log.w / Log.e as before. */
expect object Log {
    fun v(tag: String?, msg: String): Int
    fun d(tag: String?, msg: String): Int
    fun d(tag: String?, msg: String, tr: Throwable?): Int
    fun i(tag: String?, msg: String): Int
    fun i(tag: String?, msg: String, tr: Throwable?): Int
    fun w(tag: String?, msg: String): Int
    fun w(tag: String?, msg: String, tr: Throwable?): Int
    fun w(tag: String?, tr: Throwable?): Int
    fun e(tag: String?, msg: String): Int
    fun e(tag: String?, msg: String, tr: Throwable?): Int
}

/** The parts of a URL shared code looks at. */
data class UriComponents(val scheme: String?, val host: String?, val path: String?)

/** java.net.URI(url) on Android (throws for a malformed URL). */
expect fun parseUriComponents(url: String): UriComponents

/** Epoch millis for an ISO local date-time without offset, read as UTC
 *  (java.time.LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)); throws. */
expect fun parseIsoLocalDateTimeUtcMillis(text: String): Long

/** The UTC calendar year of an epoch-millis instant. */
expect fun utcYearOf(epochMillis: Long): Int

/** ConcurrentHashMap.newKeySet() on Android. */
expect fun <T> concurrentSetOf(): MutableSet<T>

/** java.util.Locale.getDefault().language on Android ("en", "de", …). */
expect fun defaultLanguageCode(): String
