package com.mediaviewer.util

import platform.Foundation.*

actual object DateText {
    actual fun format(epochMillis: Long, pattern: String, utc: Boolean): String {
        val f = NSDateFormatter()
        f.locale = NSLocale.currentLocale
        f.dateFormat = pattern
        f.timeZone = if (utc) NSTimeZone.timeZoneWithName("UTC")!! else NSTimeZone.localTimeZone
        return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
    }

    actual fun groupedInteger(n: Long): String {
        val f = NSNumberFormatter()
        f.numberStyle = NSNumberFormatterDecimalStyle
        f.maximumFractionDigits = 0u
        return f.stringFromNumber(NSNumber(longLong = n)) ?: n.toString()
    }

    actual fun utcHourToLocal(hourUtc: Int): Int {
        val offsetHours = NSTimeZone.localTimeZone.secondsFromGMT.toInt() / 3600.0
        return ((hourUtc + offsetHours).toInt() % 24 + 24) % 24
    }
}
