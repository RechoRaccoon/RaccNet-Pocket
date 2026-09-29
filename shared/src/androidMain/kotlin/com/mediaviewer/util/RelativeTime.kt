package com.mediaviewer.util

import java.time.Duration
import java.time.Instant

/** Fix 4: a compact relative timestamp for scrobble/song-history rows —
 *  "just now", "5m", "3h", "2d", "3w", "4mo", "1y". Returns "" for blank
 *  or unparseable input (e.g. a live now-playing entry, which leaves
 *  [RockskyTrack.playedAt] blank) so callers can render nothing at all.
 *
 *  [isoTimestamp] is expected in ISO-8601 form (e.g.
 *  "2026-09-18T23:05:17.000Z"); anything [Instant.parse] rejects falls
 *  back to "" rather than crashing the row. */
fun formatRelativeTime(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""
    val then = try {
        Instant.parse(isoTimestamp)
    } catch (_: Exception) {
        return ""
    }
    val seconds = Duration.between(then, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> {
            val days = seconds / 86400
            when {
                days < 7 -> "${days}d"
                days < 30 -> "${days / 7}w"
                days < 365 -> "${days / 30}mo"
                else -> "${days / 365}y"
            }
        }
    }
}
