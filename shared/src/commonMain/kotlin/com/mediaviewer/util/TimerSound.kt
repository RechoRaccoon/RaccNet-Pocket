package com.mediaviewer.util

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Timer's alarm, synthesised from scratch every time it's needed — no
 * recording, no sample, nothing borrowed. A quick, urgent space arpeggio:
 * bright bell-like plucks racing up a scale picked from the seed, a
 * two-tone pulse underneath for urgency, and a soft swell of filtered noise
 * like a passing comet. Returned as a complete 16-bit mono WAV that loops
 * cleanly.
 */
object TimerSound {
    private const val RATE = 22_050

    fun wav(seed: Long): ByteArray {
        val rnd = Random(seed)
        // A step every 105 ms (about 9.5 notes a second): fast and insistent.
        val step = (RATE * 0.105).toInt()
        val steps = 16
        val total = step * steps
        val out = FloatArray(total)

        // Notes: a minor pentatonic ladder from a random root, climbing.
        val root = 392.0 * Math2.pow2(rnd.nextInt(0, 5) / 12.0)
        val ladder = intArrayOf(0, 3, 5, 7, 10, 12, 15, 17, 19, 22)
        val start = rnd.nextInt(0, 3)
        val pattern = IntArray(steps) { i ->
            // Up four, drop back two, up four… with the odd sparkle an octave up.
            val base = start + (i % 4) + (i / 4) % 3
            if (rnd.nextInt(7) == 0) base + 3 else base
        }
        for (s in 0 until steps) {
            val semis = ladder[pattern[s].coerceIn(0, ladder.lastIndex)]
            val f = root * Math2.pow2(semis / 12.0)
            val accent = if (s % 4 == 0) 1.0 else 0.72
            val from = s * step
            // Each pluck rings on past its own step (wrapping, so the loop is seamless).
            val len = step * 3
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                val env = exp(-t * 11.0) * (1.0 - exp(-t * 900.0))
                // A bell: the note, a slightly detuned twin, and a glassy overtone.
                val v = sin(2 * PI * f * t) * 0.55 +
                    sin(2 * PI * f * 1.004 * t) * 0.3 +
                    sin(2 * PI * f * 2.76 * t) * 0.16 * exp(-t * 24.0)
                out[(from + i) % total] += (v * env * accent * 0.5).toFloat()
            }
        }
        // The urgent part: two alternating tones pulsing twice per step pair.
        val pulseLen = step * 2
        for (i in 0 until total) {
            val t = i.toDouble() / RATE
            val which = (i / pulseLen) % 2
            val f = if (which == 0) root * 2 else root * 2 * Math2.pow2(5 / 12.0)
            val local = (i % pulseLen).toDouble() / pulseLen
            val gate = if (local < 0.55) sin(PI * local / 0.55) else 0.0
            out[i] += (sin(2 * PI * f * t) * gate * 0.17).toFloat()
        }
        // A comet: low-passed noise swelling across the loop.
        var lp = 0.0
        for (i in 0 until total) {
            val swell = sin(PI * i / total)
            lp += (rnd.nextDouble(-1.0, 1.0) - lp) * 0.06
            out[i] += (lp * swell * swell * 0.35).toFloat()
        }

        // Normalise and write the WAV.
        var peak = 0.0001f
        for (v in out) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        val gain = 0.9f / peak
        val dataSize = total * 2
        val bytes = ByteArray(44 + dataSize)
        fun str(at: Int, s: String) { for (k in s.indices) bytes[at + k] = s[k].code.toByte() }
        fun i32(at: Int, v: Int) { for (k in 0 until 4) bytes[at + k] = (v shr (8 * k)).toByte() }
        fun i16(at: Int, v: Int) { bytes[at] = v.toByte(); bytes[at + 1] = (v shr 8).toByte() }
        str(0, "RIFF"); i32(4, 36 + dataSize); str(8, "WAVE")
        str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, 1); i32(24, RATE); i32(28, RATE * 2); i16(32, 2); i16(34, 16)
        str(36, "data"); i32(40, dataSize)
        for (i in 0 until total) {
            val v = (out[i] * gain * 32767f).toInt().coerceIn(-32768, 32767)
            i16(44 + i * 2, v)
        }
        return bytes
    }
}

internal object Math2 {
    /** 2 to the power of [x]. */
    fun pow2(x: Double): Double = kotlin.math.exp(x * 0.6931471805599453)
}

/** Plain calendar arithmetic (no platform date APIs): days since 1970 ↔
 *  year/month/day, weekdays and month lengths. */
object CalendarMath {
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** (year, month 1-12, day). */
    fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719468L
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = (z - era * 146097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era.toInt() * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple(if (m <= 2) y + 1 else y, m, d)
    }

    /** 0 = Sunday … 6 = Saturday. */
    fun weekday(days: Long): Int = ((days % 7 + 11) % 7).toInt()

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Today on this phone, as yyyymmdd. */
    fun todayKey(): Int = runCatching {
        DateText.format(com.mediaviewer.platform.currentTimeMillis(), "yyyyMMdd").filter { it.isDigit() }.toInt()
    }.getOrDefault(19700101)

    fun key(year: Int, month: Int, day: Int): Int = year * 10000 + month * 100 + day

    val monthNames = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
}
