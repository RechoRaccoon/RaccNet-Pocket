package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.json.JsonArray
import com.mediaviewer.json.JsonObject
import com.mediaviewer.json.JsonParser

/**
 * Settings → Customize Hub: which rows the Hub shows, in what order, plus any
 * Bluesky lists added as their own rows. Stored in the "hub_layout"
 * SharedPreferences (included in Settings → Export/Import App Data — see
 * AppBackup.SHARED_PREFS) and readable anywhere as Compose state.
 *
 * Saved per signed-in account: each account's layout lives under its own
 * key ("rows_json@<did>"), and [setAccount] (called whenever the signed-in
 * account changes) swaps in that account's layout. The last active account
 * is remembered so the right layout shows from the very first frame of a
 * cold start. A layout saved before this was per-account is handed to the
 * first account that opens the app afterwards; other accounts start fresh.
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
    /** The old, shared (pre per-account) layout. */
    private const val KEY_ROWS = "rows_json"
    private const val KEY_ACTIVE_DID = "active_did"
    private const val KEY_LEGACY_CLAIMED = "legacy_claimed"
    private fun keyFor(did: String) = "$KEY_ROWS@" + did.ifBlank { "signed_out" }

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
    /** Whose layout [rows] currently is. */
    private var accountDid: String = ""

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences(PREFS)
        prefs = p
        accountDid = p.getString(KEY_ACTIVE_DID, null) ?: ""
        rows = normalize(parse(readFor(p, accountDid)))
    }

    /** The signed-in account changed (or became known): show its layout. */
    fun setAccount(did: String) {
        val p = prefs ?: return
        if (did == accountDid && p.contains(keyFor(did))) return
        accountDid = did
        p.edit().putString(KEY_ACTIVE_DID, did).commit()
        rows = normalize(parse(readFor(p, did)))
    }

    /** [did]'s saved layout, claiming the old shared one for the first
     *  signed-in account that asks (so an existing setup isn't lost). */
    private fun readFor(p: SharedPreferences, did: String): String? {
        p.getString(keyFor(did), null)?.let { return it }
        if (p.getBoolean(KEY_LEGACY_CLAIMED, false)) return null
        val legacy = p.getString(KEY_ROWS, null)
        // Not signed in yet (e.g. the first launch after this update, before
        // the account is known): show the old layout without claiming it.
        if (did.isBlank()) return legacy
        val editor = p.edit().putBoolean(KEY_LEGACY_CLAIMED, true)
        if (legacy != null) editor.putString(keyFor(did), legacy)
        editor.apply()
        return legacy
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

    /** Keeps a list row's name in step with the list's real name (it was
     *  renamed in Add To or in another app). The row itself — its place,
     *  on/off and Posts/Accounts choice — is untouched. */
    fun renameList(uri: String, name: String) {
        val id = listId(uri)
        if (name.isBlank() || rows.none { it.id == id && it.name != name }) return
        update(rows.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun remove(id: String) {
        if (BUILT_IN_LABELS.containsKey(id)) return
        update(rows.filterNot { it.id == id })
    }

    /** Re-reads the saved layout (after an App Data import). */
    fun reload() {
        val p = prefs ?: return
        rows = normalize(parse(readFor(p, accountDid)))
    }

    private fun update(newRows: List<Row>) {
        rows = normalize(newRows)
        prefs?.edit()?.putString(keyFor(accountDid), serialize(rows))?.apply()
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
