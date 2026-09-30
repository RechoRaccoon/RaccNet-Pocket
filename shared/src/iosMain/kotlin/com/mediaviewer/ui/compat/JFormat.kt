package com.mediaviewer.ui.compat

import kotlin.math.abs

/** A small java.util.Formatter subset: %s %d %f %x %X %c %% with flags
 *  ('0', '-', ',', '+'), width and precision — what the UI uses. */
internal object JFormat {
    private val spec = Regex("%([-0+, ]*)(\\d+)?(?:\\.(\\d+))?([sdfxXc%])")

    fun format(pattern: String, args: Array<out Any?>): String {
        var argIndex = 0
        return spec.replace(pattern) { m ->
            val flags = m.groupValues[1]
            val width = m.groupValues[2].toIntOrNull() ?: 0
            val precision = m.groupValues[3].toIntOrNull()
            val conv = m.groupValues[4][0]
            if (conv == '%') return@replace "%"
            val arg = args.getOrNull(argIndex++)
            val body = when (conv) {
                's' -> arg.toString().let { if (precision != null) it.take(precision) else it }
                'c' -> arg.toString()
                'd' -> {
                    val n = (arg as? Number)?.toLong() ?: 0L
                    val digits = abs(n).toString().let { if (',' in flags) group(it) else it }
                    (if (n < 0) "-" else if ('+' in flags) "+" else "") + digits
                }
                'x', 'X' -> {
                    val n = (arg as? Number)?.toLong() ?: 0L
                    val bits = if (arg is Int) (n and 0xFFFFFFFFL) else n
                    bits.toString(16).let { if (conv == 'X') it.uppercase() else it }
                }
                'f' -> fixed(if (arg is Float) arg.toString().toDouble() else (arg as? Number)?.toDouble() ?: 0.0, precision ?: 6, ',' in flags, '+' in flags)
                else -> m.value
            }
            pad(body, width, flags)
        }
    }

    private fun group(digits: String): String =
        digits.reversed().chunked(3).joinToString(",").reversed()

    /** Like Java: HALF_UP rounding applied to the double's shortest
     *  decimal representation (so 1.005 → "1.01"), sign kept for -0.x. */
    private fun fixed(v: Double, precision: Int, grouping: Boolean, plus: Boolean): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
        val neg = v < 0 || (v == 0.0 && 1.0 / v < 0)
        val digits = plainDecimal(abs(v))           // e.g. "1.005"
        val intStr = digits.substringBefore('.')
        val fracStr = digits.substringAfter('.', "")
        // Round HALF_UP at [precision] fraction digits, on the decimal string.
        val all = (intStr + fracStr.padEnd(precision + 1, '0')).toCharArray()
        val cut = intStr.length + precision
        val roundUp = all[cut] >= '5'
        val kept = all.copyOfRange(0, cut)
        var carry = roundUp
        var i = kept.size - 1
        while (carry && i >= 0) {
            if (kept[i] == '9') { kept[i] = '0'; i-- } else { kept[i] = kept[i] + 1; carry = false }
        }
        var str = kept.concatToString()
        if (carry) str = "1$str"
        val intLen = str.length - precision
        val ip = str.substring(0, intLen).trimStart('0').ifEmpty { "0" }
        val fp = str.substring(intLen)
        val i2 = if (grouping) group(ip) else ip
        val body = if (precision > 0) "$i2.$fp" else i2
        return (if (neg) "-" else if (plus) "+" else "") + body
    }

    /** Non-scientific decimal text of a non-negative double. */
    private fun plainDecimal(v: Double): String {
        val t = v.toString()
        val e = t.indexOfFirst { it == 'E' || it == 'e' }
        if (e < 0) return if ('.' in t) t else "$t.0"
        val mant = t.substring(0, e); val exp = t.substring(e + 1).toInt()
        val mi = mant.substringBefore('.'); val mf = mant.substringAfter('.', "")
        val digits = mi + mf
        val point = mi.length + exp
        return when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length) + ".0"
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
    }

    private fun pad(s: String, width: Int, flags: String): String {
        if (s.length >= width) return s
        return when {
            '-' in flags -> s.padEnd(width)
            '0' in flags -> {
                val sign = if (s.startsWith("-") || s.startsWith("+")) s.substring(0, 1) else ""
                sign + s.removePrefix(sign).padStart(width - sign.length, '0')
            }
            else -> s.padStart(width)
        }
    }
}
