package com.mediaviewer.platform

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalTime::class)
actual fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

private val monotonicOrigin = kotlin.time.TimeSource.Monotonic.markNow()

actual fun nanoTime(): Long = monotonicOrigin.elapsedNow().inWholeNanoseconds

@OptIn(ExperimentalTime::class)
actual fun nowIsoString(): String =
    // Millisecond precision, like java.time.Instant.now() on Android.
    Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()).toString()

@OptIn(ExperimentalTime::class)
actual fun parseIsoInstantMillis(text: String): Long = Instant.parse(text).toEpochMilliseconds()

@OptIn(ExperimentalTime::class)
actual fun parseIsoOffsetDateTimeMillis(text: String): Long = Instant.parse(text).toEpochMilliseconds()

@OptIn(ExperimentalUuidApi::class)
actual fun randomUuidString(): String = Uuid.random().toString()

private const val HEX = "0123456789ABCDEF"

/** Same output as java.net.URLEncoder.encode(s, "UTF-8"). */
actual fun urlEncode(text: String): String {
    val sb = StringBuilder()
    for (b in text.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        when {
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code -> sb.append(c.toChar())
            c == ' '.code -> sb.append('+')
            else -> sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }
    return sb.toString()
}

/** Same as java.net.URLDecoder.decode(s, "UTF-8") (throws on a bad %xx). */
actual fun urlDecode(text: String): String {
    val out = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '+' -> { out.add(' '.code.toByte()); i++ }
            c == '%' -> {
                require(i + 3 <= text.length) { "Incomplete trailing escape (%) pattern" }
                val hex = text.substring(i + 1, i + 3)
                out.add(hex.toInt(16).toByte())
                i += 3
            }
            else -> { out.addAll(c.toString().encodeToByteArray().toList()); i++ }
        }
    }
    return out.toByteArray().decodeToString()
}

/** java.net.URI(url).host: the authority's host part, or null. */
actual fun uriHost(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    val rest = url.substring(schemeEnd + 3)
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    val hostPort = authority.substringAfterLast('@')
    if (hostPort.isEmpty()) return null
    if (hostPort.startsWith("[")) return hostPort.substringBefore(']') + "]"
    return hostPort.substringBefore(':').ifEmpty { null }
}

@PublishedApi internal val globalLock: ReentrantLock = reentrantLock()

actual inline fun <T> synchronizedCompat(lock: Any, block: () -> T): T = globalLock.withLock(block)

actual fun <T> synchronizedMutableList(): MutableList<T> = SynchronizedList()

private class SynchronizedList<T> : MutableList<T> {
    private val lock = reentrantLock()
    private val list = ArrayList<T>()
    override val size: Int get() = lock.withLock { list.size }
    override fun contains(element: T) = lock.withLock { list.contains(element) }
    override fun containsAll(elements: Collection<T>) = lock.withLock { list.containsAll(elements) }
    override fun get(index: Int): T = lock.withLock { list[index] }
    override fun indexOf(element: T) = lock.withLock { list.indexOf(element) }
    override fun isEmpty() = lock.withLock { list.isEmpty() }
    override fun lastIndexOf(element: T) = lock.withLock { list.lastIndexOf(element) }
    override fun add(element: T) = lock.withLock { list.add(element) }
    override fun add(index: Int, element: T) = lock.withLock { list.add(index, element) }
    override fun addAll(index: Int, elements: Collection<T>) = lock.withLock { list.addAll(index, elements) }
    override fun addAll(elements: Collection<T>) = lock.withLock { list.addAll(elements) }
    override fun clear() = lock.withLock { list.clear() }
    override fun remove(element: T) = lock.withLock { list.remove(element) }
    override fun removeAll(elements: Collection<T>) = lock.withLock { list.removeAll(elements) }
    override fun removeAt(index: Int): T = lock.withLock { list.removeAt(index) }
    override fun retainAll(elements: Collection<T>) = lock.withLock { list.retainAll(elements) }
    override fun set(index: Int, element: T): T = lock.withLock { list.set(index, element) }
    // Like Collections.synchronizedList: iterators are NOT synchronized —
    // callers iterate a snapshot here instead, which is safe.
    override fun iterator(): MutableIterator<T> = lock.withLock { ArrayList(list) }.iterator()
    override fun listIterator(): MutableListIterator<T> = lock.withLock { ArrayList(list) }.listIterator()
    override fun listIterator(index: Int): MutableListIterator<T> = lock.withLock { ArrayList(list) }.listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> = lock.withLock { ArrayList(list.subList(fromIndex, toIndex)) }
    override fun equals(other: Any?) = lock.withLock { list == other }
    override fun hashCode() = lock.withLock { list.hashCode() }
    override fun toString() = lock.withLock { list.toString() }
}

actual object Log {
    private fun out(level: String, tag: String?, msg: String, tr: Throwable? = null): Int {
        println("$level/${tag ?: ""}: $msg" + (tr?.let { "\n" + it.stackTraceToString() } ?: ""))
        return 0
    }
    actual fun v(tag: String?, msg: String): Int = out("V", tag, msg)
    actual fun d(tag: String?, msg: String): Int = out("D", tag, msg)
    actual fun d(tag: String?, msg: String, tr: Throwable?): Int = out("D", tag, msg, tr)
    actual fun i(tag: String?, msg: String): Int = out("I", tag, msg)
    actual fun i(tag: String?, msg: String, tr: Throwable?): Int = out("I", tag, msg, tr)
    actual fun w(tag: String?, msg: String): Int = out("W", tag, msg)
    actual fun w(tag: String?, msg: String, tr: Throwable?): Int = out("W", tag, msg, tr)
    actual fun w(tag: String?, tr: Throwable?): Int = out("W", tag, "", tr)
    actual fun e(tag: String?, msg: String): Int = out("E", tag, msg)
    actual fun e(tag: String?, msg: String, tr: Throwable?): Int = out("E", tag, msg, tr)
}

actual fun parseUriComponents(url: String): UriComponents {
    val schemeEnd = url.indexOf(':')
    val scheme = if (schemeEnd > 0 && url.substring(0, schemeEnd).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) url.substring(0, schemeEnd) else null
    val afterScheme = if (scheme != null) url.substring(schemeEnd + 1) else url
    val noFragment = afterScheme.substringBefore('#').substringBefore('?')
    return if (noFragment.startsWith("//")) {
        val rest = noFragment.substring(2)
        val slash = rest.indexOf('/')
        val path = if (slash >= 0) rest.substring(slash) else ""
        UriComponents(scheme, uriHost(url), path)
    } else {
        UriComponents(scheme, null, noFragment)
    }
}

@OptIn(ExperimentalTime::class)
actual fun parseIsoLocalDateTimeUtcMillis(text: String): Long {
    // LocalDateTime has no zone: read it as UTC by appending "Z".
    require(!text.endsWith("Z") && !Regex("[+-]\\d\\d:?\\d\\d$").containsMatchIn(text.substringAfter('T'))) { "Not a local date-time: $text" }
    val t = if (text.length == 16) "$text:00" else text  // "yyyy-MM-ddTHH:mm"
    return Instant.parse(t + "Z").toEpochMilliseconds()
}

/** Civil year from days since 1970-01-01 (Howard Hinnant's algorithm). */
actual fun utcYearOf(epochMillis: Long): Int {
    val days = epochMillis.floorDiv(86_400_000L)
    val z = days + 719468
    val era = z.floorDiv(146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val m = if (mp < 10) mp + 3 else mp - 9
    return (if (m <= 2) y + 1 else y).toInt()
}

actual fun <T> concurrentSetOf(): MutableSet<T> = ConcurrentHashMap<T, Boolean>().let { m ->
    object : AbstractMutableSet<T>() {
        override val size: Int get() = m.size
        override fun add(element: T): Boolean = m.put(element, true) == null
        override fun remove(element: T): Boolean = m.remove(element) != null
        override fun contains(element: T): Boolean = m.containsKey(element)
        override fun clear() = m.clear()
        override fun iterator(): MutableIterator<T> {
            val it = m.keys.toMutableList().iterator()
            return object : MutableIterator<T> {
                var last: T? = null
                override fun hasNext() = it.hasNext()
                override fun next(): T = it.next().also { v -> last = v }
                @Suppress("UNCHECKED_CAST")
                override fun remove() { it.remove(); m.remove(last as T) }
            }
        }
    }
}

/** The iOS image loader registers here so shared code can evict entries. */
object ImageCacheHooks {
    var remover: ((List<String>) -> Int)? = null
    fun remove(urls: List<String>): Int = remover?.invoke(urls) ?: 0
}
