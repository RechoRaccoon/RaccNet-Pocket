package com.mediaviewer.tagging

/** Item 4: e621-style "as you type" tag suggestions for the Liked search
 *  tab. Given the word currently being typed and the tagger's vocabulary
 *  (+ the handful of everyday synonyms in [TagAliases], since those are
 *  valid things to type even though they're not literal model tags),
 *  returns up to [maxResults] candidates:
 *
 *  1. Prefix matches first (typing "dr" -> "dragon", "drooling", ...) —
 *     this is the common case and matches e621's own autocomplete.
 *  2. If there are no prefix matches at all, falls back to small-edit-
 *     distance "did you mean" suggestions (e621's autocorrect for typos —
 *     e.g. typing "drogon" still suggests "dragon"). Only kicks in once the
 *     word is long enough (>=3 chars) that edit-distance comparisons are
 *     meaningful rather than matching everything. */
object TagSuggestionProvider {

    fun suggest(prefix: String, vocabulary: List<String>, maxResults: Int = 8): List<String> {
        val needle = prefix.trim().lowercase()
        if (needle.isBlank() || vocabulary.isEmpty()) return emptyList()

        val prefixMatches = vocabulary.asSequence()
            .filter { it.startsWith(needle, ignoreCase = true) }
            .sortedWith(compareBy({ it.length }, { it }))
            .take(maxResults)
            .toList()
        if (prefixMatches.isNotEmpty()) return prefixMatches

        if (needle.length < 3) return emptyList()
        val maxDistance = if (needle.length <= 5) 1 else 2
        return vocabulary.asSequence()
            .map { it to levenshtein(needle, it.lowercase(), maxDistance + 1) }
            .filter { it.second in 1..maxDistance }
            .sortedWith(compareBy({ it.second }, { it.first.length }))
            .take(maxResults)
            .map { it.first }
            .toList()
    }

    /** Whichever alias-map key or literal tag is the closest, given a
     *  possibly-misspelled/synonymous word — used to power the "did you
     *  mean" single suggestion when a fuzzy match exists but no prefix
     *  match does. Returns null when the word is already an exact match
     *  (nothing to correct) or nothing is close enough. */
    fun autocorrect(word: String, vocabulary: List<String>): String? {
        val needle = word.trim().lowercase()
        if (needle.isBlank() || vocabulary.contains(needle)) return null
        return suggest(needle, vocabulary, maxResults = 1).firstOrNull()
    }

    /** Bounded Levenshtein edit distance — stops early past [limit] rather
     *  than computing the full O(n*m) table for wildly different strings,
     *  since this runs against the whole (thousands-strong) tag vocabulary
     *  on every keystroke. Returns a value > limit (not the true distance)
     *  once it's certain the real distance exceeds it. */
    private fun levenshtein(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > limit) return limit + 1
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}
