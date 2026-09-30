package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.json.JSONArray
import com.mediaviewer.json.JSONObject
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences

/**
 * The Search page's "Recent Searches" (People tab): accounts you searched
 * for and opened, newest first. Kept on this device only.
 */
object RecentAccountSearches {
    private const val PREFS = "recent_account_searches"
    private const val KEY = "accounts"
    private const val MAX = 20

    private var prefs: SharedPreferences? = null

    /** Newest first — Compose state. */
    var accounts by mutableStateOf<List<AuthorInfo>>(emptyList())
        private set

    fun ensureLoaded(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences(PREFS)
        prefs = p
        accounts = runCatching {
            val arr = JSONArray(p.getString(KEY, "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val did = o.optString("did")
                if (did.isBlank()) null else AuthorInfo(
                    did = did,
                    handle = o.optString("handle"),
                    displayName = o.optString("name").ifBlank { o.optString("handle") },
                    avatarUrl = o.optString("avatar").ifBlank { null }
                )
            }
        }.getOrDefault(emptyList())
    }

    /** An account was opened from search results. */
    fun record(author: AuthorInfo) {
        if (author.did.isBlank()) return
        accounts = (listOf(author) + accounts.filterNot { it.did == author.did }).take(MAX)
        save()
    }

    fun remove(did: String) {
        accounts = accounts.filterNot { it.did == did }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(JSONObject().put("did", a.did).put("handle", a.handle).put("name", a.displayName).put("avatar", a.avatarUrl ?: ""))
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }
}
