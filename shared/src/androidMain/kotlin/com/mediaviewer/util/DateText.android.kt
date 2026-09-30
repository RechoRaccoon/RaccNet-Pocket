// check:jvm
package com.mediaviewer.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

actual object DateText {
    actual fun format(epochMillis: Long, pattern: String, utc: Boolean): String =
        DateTimeFormatter.ofPattern(pattern)
            .withZone(if (utc) ZoneId.of("UTC") else ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))

    actual fun groupedInteger(n: Long): String = java.text.NumberFormat.getIntegerInstance().format(n)

    actual fun utcHourToLocal(hourUtc: Int): Int =
        ZonedDateTime.now(ZoneOffset.UTC).withHour(hourUtc).withMinute(0)
            .withZoneSameInstant(ZoneId.systemDefault()).hour
}
