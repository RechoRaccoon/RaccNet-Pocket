package com.mediaviewer.util

import com.mediaviewer.model.BskyMessageView
import com.mediaviewer.platform.currentTimeMillis

/**
 * DM streaks, worked out entirely on this device from the chat's own
 * messages: a day counts when BOTH people sent at least one message (in the
 * phone's own time zone); the streak counts from the first such day (1, 2, 3…).
 * Nothing is uploaded, and nothing extra is requested just to show it — the
 * number in the chat list is whatever was last worked out, and tapping the
 * flame re-counts from the chat's history.
 */
object DmStreaks {
    const val DAY_MS = 86_400_000L

    /** How far the phone's clock is from UTC right now. */
    fun zoneOffsetMs(): Long = runCatching {
        val now = currentTimeMillis()
        val local = DateText.format(now, "yyyy-MM-dd'T'HH:mm:ss")
        com.mediaviewer.platform.parseIsoLocalDateTimeUtcMillis(local) - now / 1000 * 1000
    }.getOrDefault(0L)

    fun dayOf(millis: Long, offsetMs: Long): Long = (millis + offsetMs).floorDiv(DAY_MS)

    private fun sentMillis(raw: String): Long? =
        runCatching { com.mediaviewer.platform.parseIsoInstantMillis(raw) }.getOrNull()
            ?: runCatching { com.mediaviewer.platform.parseIsoOffsetDateTimeMillis(raw) }.getOrNull()

    class Count(
        /** Days in a row, ending today or yesterday. */
        val run: Int,
        val lastDay: Long,
        /** False when the run reaches the oldest message looked at — older
         *  history might extend it. */
        val settled: Boolean
    )

    /** Counts the current run from [messages] (any order). */
    fun count(messages: List<BskyMessageView>, myDid: String): Count {
        val offset = zoneOffsetMs()
        val today = dayOf(currentTimeMillis(), offset)
        val mine = HashSet<Long>()
        val theirs = HashSet<Long>()
        var oldest = Long.MAX_VALUE
        for (m in messages) {
            val sender = m.sender?.did ?: continue
            if (m.type?.contains("deleted") == true || m.type?.contains("system") == true) continue
            val day = dayOf(sentMillis(m.sentAt) ?: continue, offset)
            if (day < oldest) oldest = day
            if (sender == myDid) mine += day else theirs += day
        }
        if (oldest == Long.MAX_VALUE) return Count(0, 0L, settled = true)
        fun both(d: Long) = d in mine && d in theirs
        // Today not being done yet doesn't break yesterday's streak.
        var d = if (both(today)) today else today - 1
        if (!both(d)) return Count(0, 0L, settled = true)
        val last = d
        var run = 0
        while (both(d)) { run++; d-- }
        // The oldest day looked at may be only partly loaded.
        return Count(run, last, settled = d > oldest)
    }

    /** What to show for a saved streak today (it lapses after a missed day). */
    fun shown(streak: DmStreak): Int {
        if (streak.count <= 0) return 0
        val today = dayOf(currentTimeMillis(), zoneOffsetMs())
        return if (streak.lastDay >= today - 1) streak.count else 0
    }

    /** Saves [count] for [convoId] (shown from the first day). */
    fun save(convoId: String, count: Count) {
        LocalData.setDmStreak(
            convoId,
            DmStreak(count = count.run, run = count.run, lastDay = count.lastDay, checkedAt = currentTimeMillis())
        )
    }

    /**
     * Called with whatever messages a chat already has loaded: keeps the
     * saved streak current at no cost. Only trusts a result that is settled
     * or at least as long as what's saved (a short loaded window can't
     * shorten a long streak).
     */
    fun noteLoaded(convoId: String, messages: List<BskyMessageView>, myDid: String, hasOlder: Boolean) {
        if (convoId.isBlank() || messages.isEmpty()) return
        val c = count(messages, myDid)
        val saved = LocalData.dmStreak(convoId)
        val savedLive = saved.lastDay >= dayOf(currentTimeMillis(), zoneOffsetMs()) - 1
        if (c.settled || !hasOlder || !savedLive || c.run >= saved.run) save(convoId, c)
        else if (c.lastDay > saved.lastDay && saved.run > 0) {
            // The run continues past what's loaded: it grew by the new days.
            val grown = saved.run + (c.lastDay - saved.lastDay).toInt()
            LocalData.setDmStreak(convoId, saved.copy(count = grown, run = grown, lastDay = c.lastDay, checkedAt = currentTimeMillis()))
        }
    }
}
