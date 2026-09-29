package com.mediaviewer.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Settings → Customize Hub: which rows the Hub shows, in what order, plus any
 * Bluesky lists added as their own rows. Stored in the "hub_layout"
 * SharedPreferences (included in Settings → Export/Import App Data — see
 * AppBackup.SHARED_PREFS) and readable anywhere as Compose state.
 */
object HubLayout {
    const val FEEDS = "feeds"
    const val BUTTONS = "buttons"
    const val MUTUALS = "mutuals"
    const val LIVESTREAMS = "live"
    const val BLOGS = "blogs"
    const val REVIEWS = "reviews"
    const val SWITCH_ACCOUNTS = "switch"
    private const val LIST_PREFIX = "list:"

    private const val PREFS = "hub_layout"
    private const val KEY_ROWS = "rows_json"

    /** One Hub row. Built-in rows only use [id] and [enabled]; a list row
     *  also carries its list's [listUri], [name] and whether it shows the
     *  members' latest posts ([showPosts]) or just their icons. */
    data class Row(
        val id: String,
        val enabled: Boolean = true,
        val listUri: String? = null,
        val name: String = "",
        val showPosts: Boolean = false
    ) {
        val isList: Boolean get() = listUri != null
        val label: String get() = if (isList) name.ifBlank { "List" } else BUILT_IN_LABELS[id] ?: id
    }

    val BUILT_IN_LABELS = linkedMapOf(
        FEEDS to "Feeds",
        BUTTONS to "Launchpad",
        MUTUALS to "Mutuals",
        LIVESTREAMS to "Livestreams",
        BLOGS to "Blogs",
        REVIEWS to "Reviews",
        SWITCH_ACCOUNTS to "Switch Accounts"
    )

    private fun defaultRows(): List<Row> = BUILT_IN_LABELS.keys.map { Row(it) }

    var rows by mutableStateOf(defaultRows())
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        rows = normalize(parse(p.getString(KEY_ROWS, null)))
    }

    fun listId(uri: String) = LIST_PREFIX + uri

    fun isEnabled(id: String): Boolean = rows.firstOrNull { it.id == id }?.enabled ?: true

    /** Moves the row at [from] to [to] (indices into [rows]). */
    fun move(from: Int, to: Int) {
        val list = rows.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        update(list)
    }

    fun setEnabled(id: String, enabled: Boolean) = update(rows.map { if (it.id == id) it.copy(enabled = enabled) else it })

    fun setShowPosts(id: String, showPosts: Boolean) = update(rows.map { if (it.id == id) it.copy(showPosts = showPosts) else it })

    /** Adds a list as a new row at the bottom (or re-enables it if it's
     *  already there). Returns false when it was already added. */
    fun addList(uri: String, name: String): Boolean {
        val id = listId(uri)
        if (rows.any { it.id == id }) {
            update(rows.map { if (it.id == id) it.copy(enabled = true, name = name.ifBlank { it.name }) else it })
            return false
        }
        update(rows + Row(id = id, listUri = uri, name = name))
        return true
    }

    fun remove(id: String) {
        if (BUILT_IN_LABELS.containsKey(id)) return
        update(rows.filterNot { it.id == id })
    }

    /** Re-reads the saved layout (after an App Data import). */
    fun reload() {
        val p = prefs ?: return
        rows = normalize(parse(p.getString(KEY_ROWS, null)))
    }

    private fun update(newRows: List<Row>) {
        rows = normalize(newRows)
        prefs?.edit()?.putString(KEY_ROWS, serialize(rows))?.apply()
    }

    /** Every built-in row exactly once (new ones appended), no duplicate lists. */
    private fun normalize(input: List<Row>): List<Row> {
        val seen = HashSet<String>()
        val out = ArrayList<Row>()
        for (r in input) {
            if (!seen.add(r.id)) continue
            if (!r.isList && !BUILT_IN_LABELS.containsKey(r.id)) continue
            out += r
        }
        // A built-in row that's new since the layout was saved goes right
        // after the one before it in the default order (e.g. Livestreams
        // after Mutuals), not at the very bottom.
        val defaults = BUILT_IN_LABELS.keys.toList()
        for ((i, id) in defaults.withIndex()) {
            if (id in seen) continue
            val prev = defaults.subList(0, i).lastOrNull { p -> out.any { it.id == p } }
            val at = if (prev == null) 0 else out.indexOfFirst { it.id == prev } + 1
            out.add(at, Row(id))
            seen += id
        }
        return out
    }

    private fun serialize(list: List<Row>): String {
        val arr = JsonArray()
        for (r in list) {
            val o = JsonObject()
            o.addProperty("id", r.id)
            o.addProperty("enabled", r.enabled)
            if (r.listUri != null) {
                o.addProperty("listUri", r.listUri)
                o.addProperty("name", r.name)
                o.addProperty("showPosts", r.showPosts)
            }
            arr.add(o)
        }
        return arr.toString()
    }

    private fun parse(json: String?): List<Row> {
        if (json.isNullOrBlank()) return defaultRows()
        return runCatching {
            JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = o.get("id")?.asString ?: return@mapNotNull null
                Row(
                    id = id,
                    enabled = o.get("enabled")?.asBoolean ?: true,
                    listUri = o.get("listUri")?.takeIf { !it.isJsonNull }?.asString,
                    name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    showPosts = o.get("showPosts")?.asBoolean ?: false
                )
            }
        }.getOrElse { defaultRows() }
    }
}
