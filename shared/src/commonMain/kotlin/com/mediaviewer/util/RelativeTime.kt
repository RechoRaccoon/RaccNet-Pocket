package com.mediaviewer.util

import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.parseIsoInstantMillis

/** Fix 4: a compact relative timestamp for scrobble/song-history rows —
 *  "just now", "5m", "3h", "2d", "3w", "4mo", "1y". Returns "" for blank
 *  or unparseable input (e.g. a live now-playing entry, which leaves
 *  [RockskyTrack.playedAt] blank) so callers can render nothing at all. */
fun formatRelativeTime(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""
    val then = try {
        parseIsoInstantMillis(isoTimestamp)
    } catch (_: Exception) {
        return ""
    }
    val seconds = ((currentTimeMillis() - then) / 1000).coerceAtLeast(0)
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
