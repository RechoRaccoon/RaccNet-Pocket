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
 * rows added on top of the defaults — Bluesky lists, and "Profiles" rows
 * (a hand-picked set of accounts that lives only on this device). Stored in
 * the "hub_layout" SharedPreferences (included in Settings → Export/Import
 * App Data — see AppBackup.SHARED_PREFS) and readable anywhere as Compose
 * state.
 *
 * Every row can be removed, the default ones included; Customize Hub's
 * Add → Default puts a removed default row back (see [addDefault]).
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
    /** "Stellar's Supporters": a default row backed by the Stellar
     *  Supporters list, in Posts mode unless switched to Profiles. */
    const val SUPPORTERS = "supporters"
    const val SWITCH_ACCOUNTS = "switch"
    private const val LIST_PREFIX = "list:"
    private const val PROFILES_PREFIX = "profiles:"

    private const val PREFS = "hub_layout"
    /** The old, shared (pre per-account) layout. */
    private const val KEY_ROWS = "rows_json"
    private const val KEY_ACTIVE_DID = "active_did"
    private const val KEY_LEGACY_CLAIMED = "legacy_claimed"
    private fun keyFor(did: String) = "$KEY_ROWS@" + did.ifBlank { "signed_out" }
    /** Default rows the account has removed (so they aren't put back). */
    private fun removedKeyFor(did: String) = "removed_defaults@" + did.ifBlank { "signed_out" }

    /** One account in a Profiles row (kept on-device with the row). */
    data class Profile(val did: String, val handle: String, val displayName: String, val avatarUrl: String?)

    /** One Hub row. Built-in rows only use [id] and [enabled]; a list row
     *  also carries its list's [listUri], [name] and whether it shows the
     *  members' latest posts ([showPosts]) or just their icons; a Profiles
     *  row carries its [profiles] instead of a list. */
    data class Row(
        val id: String,
        val enabled: Boolean = true,
        val listUri: String? = null,
        val name: String = "",
        val showPosts: Boolean = false,
        val profiles: List<Profile> = emptyList()
    ) {
        /** One of the default rows (Add → Default). */
        val isBuiltIn: Boolean get() = BUILT_IN_LABELS.containsKey(id)
        /** Backed by a Bluesky list (Stellar's Supporters included). */
        val isList: Boolean get() = listUri != null
        /** A local, private set of accounts. */
        val isProfiles: Boolean get() = id.startsWith(PROFILES_PREFIX)
        /** Has members and their posts: gets the Profiles/Posts button. */
        val hasMembers: Boolean get() = isList || isProfiles
        /** What its loaded content is stored under (MainViewModel.hubLists). */
        val contentKey: String get() = listUri ?: id
        val label: String get() = BUILT_IN_LABELS[id] ?: name.ifBlank { if (isProfiles) "Profiles" else "List" }
    }

    val BUILT_IN_LABELS = linkedMapOf(
        FEEDS to "Feeds",
        BUTTONS to "Launchpad",
        MUTUALS to "Mutuals",
        LIVESTREAMS to "Livestreams",
        BLOGS to "Blogs",
        REVIEWS to "Reviews",
        SUPPORTERS to "Stellar's Supporters",
        SWITCH_ACCOUNTS to "Switch Accounts"
    )

    /** The default rows, in their default order (Add → Default's list). */
    val defaultRowIds: List<String> get() = BUILT_IN_LABELS.keys.toList()

    private fun defaultRow(id: String): Row =
        if (id == SUPPORTERS) Row(id, listUri = StellarOfficial.SUPPORTERS_LIST_URI, name = "Stellar's Supporters", showPosts = true)
        else Row(id)

    private fun defaultRows(): List<Row> = BUILT_IN_LABELS.keys.map { defaultRow(it) }

    var rows by mutableStateOf(defaultRows())
        private set

    private var prefs: SharedPreferences? = null
    /** Whose layout [rows] currently is. */
    private var accountDid: String = ""
    private var removedDefaults: Set<String> = emptySet()

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences(PREFS)
        prefs = p
        accountDid = p.getString(KEY_ACTIVE_DID, null) ?: ""
        removedDefaults = readRemoved(p, accountDid)
        rows = normalize(parse(readFor(p, accountDid)))
    }

    /** The signed-in account changed (or became known): show its layout. */
    fun setAccount(did: String) {
        val p = prefs ?: return
        if (did == accountDid && p.contains(keyFor(did))) return
        accountDid = did
        p.edit().putString(KEY_ACTIVE_DID, did).commit()
        removedDefaults = readRemoved(p, did)
        rows = normalize(parse(readFor(p, did)))
    }

    private fun readRemoved(p: SharedPreferences, did: String): Set<String> =
        p.getString(removedKeyFor(did), null)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

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

    /** Adds a Profiles row (accounts picked in Customize Hub → Add →
     *  Profiles) at the bottom. Private and on-device: no Bluesky list is
     *  made. Returns the new row's id. */
    fun addProfiles(name: String, profiles: List<Profile>): String {
        val id = PROFILES_PREFIX + com.mediaviewer.platform.randomUuidString()
        update(rows + Row(id = id, name = name.trim().ifBlank { "Profiles" }, profiles = profiles.distinctBy { it.did }))
        return id
    }

    /** Keeps a list row's name in step with the list's real name (it was
     *  renamed in Add To or in another app). The row itself — its place,
     *  on/off and Posts/Profiles choice — is untouched. */
    fun renameList(uri: String, name: String) {
        val id = listId(uri)
        if (name.isBlank() || rows.none { it.id == id && it.name != name }) return
        update(rows.map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Removes a row. A default row can be added back from Add → Default. */
    fun remove(id: String) {
        if (rows.none { it.id == id }) return
        if (BUILT_IN_LABELS.containsKey(id)) {
            removedDefaults = removedDefaults + id
            saveRemoved()
        }
        update(rows.filterNot { it.id == id })
    }

    /** Add → Default: puts a removed default row back, where it sits in the
     *  default order (right after the nearest default row before it). */
    fun addDefault(id: String) {
        if (!BUILT_IN_LABELS.containsKey(id) || rows.any { it.id == id }) return
        removedDefaults = removedDefaults - id
        saveRemoved()
        update(rows)
    }

    private fun saveRemoved() {
        prefs?.edit()?.putString(removedKeyFor(accountDid), removedDefaults.joinToString(","))?.apply()
    }

    /** Re-reads the saved layout (after an App Data import). */
    fun reload() {
        val p = prefs ?: return
        removedDefaults = readRemoved(p, accountDid)
        rows = normalize(parse(readFor(p, accountDid)))
    }

    private fun update(newRows: List<Row>) {
        rows = normalize(newRows)
        prefs?.edit()?.putString(keyFor(accountDid), serialize(rows))?.apply()
    }

    /** Every default row exactly once (new ones added in, removed ones left
     *  out), no duplicate lists. */
    private fun normalize(input: List<Row>): List<Row> {
        val seen = HashSet<String>()
        val out = ArrayList<Row>()
        for (r in input) {
            if (!seen.add(r.id)) continue
            val builtIn = BUILT_IN_LABELS.containsKey(r.id)
            if (!builtIn && !r.isList && !r.isProfiles) continue
            if (builtIn && r.id in removedDefaults) continue
            // Stellar's Supporters always points at the real list.
            out += if (r.id == SUPPORTERS) r.copy(listUri = StellarOfficial.SUPPORTERS_LIST_URI, name = "Stellar's Supporters") else r
        }
        // A default row that's new since the layout was saved (or was just
        // added back) goes right after the one before it in the default
        // order (e.g. Stellar's Supporters after Reviews), not at the very
        // bottom.
        val defaults = BUILT_IN_LABELS.keys.toList()
        for ((i, id) in defaults.withIndex()) {
            if (id in seen || id in removedDefaults) continue
            val prev = defaults.subList(0, i).lastOrNull { p -> out.any { it.id == p } }
            val at = if (prev == null) 0 else out.indexOfFirst { it.id == prev } + 1
            out.add(at, defaultRow(id))
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
            if (r.listUri != null) o.addProperty("listUri", r.listUri)
            if (r.hasMembers) {
                o.addProperty("name", r.name)
                o.addProperty("showPosts", r.showPosts)
            }
            if (r.isProfiles) {
                val ps = JsonArray()
                for (p in r.profiles) {
                    val po = JsonObject()
                    po.addProperty("did", p.did)
                    po.addProperty("handle", p.handle)
                    po.addProperty("displayName", p.displayName)
                    if (p.avatarUrl != null) po.addProperty("avatarUrl", p.avatarUrl)
                    ps.add(po)
                }
                o.add("profiles", ps)
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
                val profiles = runCatching {
                    o.get("profiles")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull member@{ pe ->
                        val po = pe.takeIf { it.isJsonObject }?.asJsonObject ?: return@member null
                        val did = po.get("did")?.takeIf { !it.isJsonNull }?.asString ?: return@member null
                        val handle = po.get("handle")?.takeIf { !it.isJsonNull }?.asString ?: did
                        Profile(
                            did = did, handle = handle,
                            displayName = po.get("displayName")?.takeIf { !it.isJsonNull }?.asString?.ifBlank { null } ?: handle,
                            avatarUrl = po.get("avatarUrl")?.takeIf { !it.isJsonNull }?.asString
                        )
                    }
                }.getOrNull() ?: emptyList()
                Row(
                    id = id,
                    enabled = o.get("enabled")?.asBoolean ?: true,
                    listUri = o.get("listUri")?.takeIf { !it.isJsonNull }?.asString,
                    name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    // Stellar's Supporters starts out in Posts mode.
                    showPosts = o.get("showPosts")?.asBoolean ?: (id == SUPPORTERS),
                    profiles = profiles
                )
            }
        }.getOrElse { defaultRows() }
    }
}
