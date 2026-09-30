package com.mediaviewer.util

/**
 * The few locale-aware date/number formats the UI shows. On Android these
 * are the same java.time / java.text calls the app always used; on iOS
 * they're NSDateFormatter / NSNumberFormatter with the same patterns.
 */
expect object DateText {
    /** [epochMillis] formatted with a CLDR [pattern] ("MMM d, yyyy", …) in
     *  the device's time zone, or in UTC when [utc] is true. */
    fun format(epochMillis: Long, pattern: String, utc: Boolean = false): String

    /** A whole number with the locale's thousands separators ("12,345"). */
    fun groupedInteger(n: Long): String

    /** The device-local hour (0-23) that the UTC hour [hourUtc] is today. */
    fun utcHourToLocal(hourUtc: Int): Int
}

/** "2026-01-05" (a plain ISO date) → epoch millis at UTC midnight. */
fun parseIsoDateUtcMillis(date: String): Long =
    com.mediaviewer.platform.parseIsoLocalDateTimeUtcMillis(date.trim() + "T00:00:00")
