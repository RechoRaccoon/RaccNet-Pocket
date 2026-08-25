package com.mediaviewer.tagging

/** Local `tag_aliases` dictionary (per the tagging spec's "Search Query
 *  Execution and Synonym Mapping" section) — maps a handful of common,
 *  everyday search words onto the canonical e621/Z3D-E621-Convnext tag
 *  vocabulary before the query hits FTS5, so searching "dog" also matches
 *  images the model tagged "canine" or "dog_ears", etc. Deliberately small
 *  and easy to extend; anything not listed here is passed straight through
 *  as its own literal tag term. */
object TagAliases {

    private val aliases: Map<String, List<String>> = mapOf(
        "dog" to listOf("canine", "dog", "dog_ears"),
        "cat" to listOf("feline", "cat", "cat_ears"),
        "wolf" to listOf("canine", "wolf"),
        "fox" to listOf("canine", "fox"),
        "dragon" to listOf("dragon"),
        "bird" to listOf("avian", "bird"),
        "horse" to listOf("equine", "horse"),
        "male" to listOf("male", "anthro_male"),
        "female" to listOf("female", "anthro_female"),
        "duo" to listOf("duo"),
        "group" to listOf("group"),
        "solo" to listOf("solo"),
        "nude" to listOf("nude"),
        "explicit" to listOf("explicit"),
        "safe" to listOf("safe"),
        "questionable" to listOf("questionable")
    )

    /** Turns a raw, space-separated user query into an FTS5 MATCH
     *  expression: each user term becomes its own parenthesized OR-group of
     *  aliases (when one exists) ANDed together with the rest of the query
     *  (FTS5's default MATCH behavior is implicit AND between terms), e.g.
     *  "dog explicit" -> "(canine OR dog OR dog_ears) (explicit)". */
    fun toFtsQuery(rawQuery: String): String {
        val terms = rawQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return ""
        return terms.joinToString(" ") { term ->
            val sanitized = term.replace(Regex("[^a-z0-9_]"), "")
            if (sanitized.isBlank()) return@joinToString ""
            val group = aliases[sanitized] ?: listOf(sanitized)
            "(" + group.joinToString(" OR ") + ")"
        }.trim()
    }
}
