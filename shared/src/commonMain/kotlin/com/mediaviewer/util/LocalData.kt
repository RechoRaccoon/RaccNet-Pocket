package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.json.StellarJson
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.randomUuidString
import com.mediaviewer.platform.sharedPreferences
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Who is signed in, whether they're a Stellar Supporter, and how to send
 * someone who isn't to Settings → Support Stellar. Readable anywhere as
 * Compose state, so supporter-only buttons don't need plumbing.
 */
object Supporter {
    /** The signed-in account (kept current by AppRoot). */
    var selfDid by mutableStateOf("")

    /** True while the signed-in account is on the Stellar Supporters list. */
    val active: Boolean get() = StellarSupporters.isSupporter(selfDid)

    /** Set by AppRoot: closes whatever is open and shows the support page. */
    var openPageHook: (() -> Unit)? = null

    fun openPage() {
        val hook = openPageHook
        if (hook != null) hook() else UiToggles.supportPageRequest++
    }

    /** Runs [action] for supporters; everyone else lands on the support page. */
    inline fun gate(action: () -> Unit) {
        if (active) action() else openPage()
    }
}

// ── Models (all on-device only) ──────────────────────────────────────────

@Serializable
data class DraftThreadPost(val text: String = "", val images: List<String> = emptyList(), val video: String? = null)

/** A saved, unposted post: everything the composer needs to rebuild it. */
@Serializable
data class PostDraftEntry(
    val id: String = "",
    val savedAt: Long = 0L,
    /** SINGLE, THREAD, TEXTSHOT, VIDEO or POLL. */
    val mode: String = "SINGLE",
    val posts: List<DraftThreadPost> = emptyList(),
    val textshotPostText: String = "",
    val videoTitle: String = "",
    val videoDescription: String = "",
    val videoUri: String? = null,
    val videoThumbUri: String? = null,
    val adultLabel: String? = null,
    val graphicMedia: Boolean = false,
    val pollOptions: List<String> = emptyList()
) {
    val preview: String get() = when (mode) {
        "VIDEO" -> videoTitle.ifBlank { videoDescription }.ifBlank { "Video" }
        "POLL" -> posts.firstOrNull()?.text?.ifBlank { null } ?: "Poll"
        else -> posts.firstOrNull { it.text.isNotBlank() }?.text ?: when {
            posts.any { it.images.isNotEmpty() } -> "Media post"
            else -> "Empty draft"
        }
    }
    val label: String get() = when (mode) {
        "VIDEO" -> "Video"
        "THREAD" -> "Thread"
        "TEXTSHOT" -> "Textshot"
        "POLL" -> "Poll"
        else -> if (posts.any { it.images.isNotEmpty() }) "Media Post" else "Text Post"
    }
    val firstImage: String? get() = posts.firstNotNullOfOrNull { it.images.firstOrNull() } ?: videoThumbUri
}

@Serializable
data class BookmarkFolder(
    val id: String = "",
    val name: String = "",
    /** A picture for the folder (a local file or a post's thumbnail). */
    val cover: String? = null,
    /** at:// URIs of the saved posts in it, newest first. */
    val posts: List<String> = emptyList(),
    val createdAt: Long = 0L
)

@Serializable
data class LocalFeedSource(
    /** "list", "account" or "hashtag". */
    val kind: String = "",
    /** A list's at:// URI, an account's DID, or a hashtag (no #). */
    val value: String = "",
    val label: String = ""
)

/** A feed built on this device: a mix of lists, accounts and hashtags. */
@Serializable
data class LocalFeed(
    val id: String = "",
    val name: String = "",
    val sources: List<LocalFeedSource> = emptyList(),
    val textPosts: Boolean = true,
    val images: Boolean = true,
    val horizontalVideos: Boolean = true,
    val verticalVideos: Boolean = true
) {
    val uri: String get() = LocalData.LOCAL_FEED_PREFIX + id
}

@Serializable
data class DmStreak(
    /** Days in a row both people messaged (0 until it reaches 3). */
    val count: Int = 0,
    /** Raw run of days, even below 3. */
    val run: Int = 0,
    /** The last local day (days since 1970) both people messaged. */
    val lastDay: Long = 0L,
    val checkedAt: Long = 0L
)

@Serializable
data class CalendarEvent(
    val id: String = "",
    /** yyyymmdd, e.g. 20261002. */
    val day: Int = 0,
    /** Minutes after midnight, or -1 for all day. */
    val minute: Int = -1,
    val title: String = ""
)

@Serializable
data class NoteFolder(val id: String = "", val name: String = "")

@Serializable
data class NoteEntry(
    val id: String = "",
    val folderId: String? = null,
    val title: String = "",
    /** Markdown. Pictures are written as ![](uri) lines. */
    val body: String = "",
    val updatedAt: Long = 0L
)

/** One earlier version of an edited post. */
@Serializable
data class PostEditVersion(val text: String = "", val at: String = "", val images: Int = 0)

/**
 * Everything Stellar keeps only on this device for the supporter features
 * (drafts, bookmark folders, profile notes, built feeds, DM streaks and
 * pins, calendar, notes, supporter settings). One SharedPreferences file,
 * "supporter_local", which Settings → Export App Data includes.
 */
object LocalData {
    const val PREFS = "supporter_local"
    const val LOCAL_FEED_PREFIX = "stellar-local://feed/"

    const val KEY_NOTIFY_DMS = "notify_dms"
    const val KEY_NOTIFY_INBOX = "notify_inbox"
    private const val KEY_SEARCH_ENGINE = "search_engine"
    private const val KEY_BATTERY_SAVER = "battery_saver"
    private const val KEY_BROWSER_LAST = "browser_last_url"

    private var prefs: SharedPreferences? = null
    private val json get() = StellarJson.default

    private class Slot<T>(val key: String, val serializer: KSerializer<T>, val default: T) {
        var value by mutableStateOf(default)
        fun load(p: SharedPreferences) {
            value = p.getString(key, null)?.let { runCatching { StellarJson.default.decodeFromString(serializer, it) }.getOrNull() } ?: default
        }
        fun save(p: SharedPreferences?, v: T) {
            value = v
            // commit(): written before anything else can happen, so a draft
            // or note is never lost to a crash right after saving.
            runCatching { p?.edit()?.putString(key, StellarJson.default.encodeToString(serializer, v))?.commit() }
        }
    }

    private val draftSlot = Slot("drafts", ListSerializer(PostDraftEntry.serializer()), emptyList())
    private val folderSlot = Slot("bookmark_folders", ListSerializer(BookmarkFolder.serializer()), emptyList())
    private val noteSlot = Slot("profile_notes", MapSerializer(String.serializer(), String.serializer()), emptyMap())
    private val feedSlot = Slot("local_feeds", ListSerializer(LocalFeed.serializer()), emptyList())
    private val streakSlot = Slot("dm_streaks", MapSerializer(String.serializer(), DmStreak.serializer()), emptyMap())
    private val pinSlot = Slot("dm_pins", ListSerializer(String.serializer()), emptyList())
    private val effectSlot = Slot("dm_effects_played", ListSerializer(String.serializer()), emptyList())
    private val eventSlot = Slot("calendar_events", ListSerializer(CalendarEvent.serializer()), emptyList())
    private val noteFolderSlot = Slot("note_folders", ListSerializer(NoteFolder.serializer()), emptyList())
    private val notesSlot = Slot("notes", ListSerializer(NoteEntry.serializer()), emptyList())
    private val pollVoteSlot = Slot("poll_votes", MapSerializer(String.serializer(), String.serializer()), emptyMap())

    /** Supporter Settings → Notifications (Android). */
    var notifyDms by mutableStateOf(false)
        private set
    var notifyInbox by mutableStateOf(false)
        private set

    /** Supporter Settings → Web Browser → Search Engine. */
    var searchEngine by mutableStateOf(SearchEngine.DUCKDUCKGO)
        private set

    /** Supporter Settings → Battery Saver. */
    var batterySaver by mutableStateOf(false)
        private set

    /** Battery Saver only counts while the account is a supporter. */
    val batterySaverActive: Boolean get() = batterySaver && Supporter.active

    enum class SearchEngine(val label: String, val home: String, private val query: String) {
        DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/", "https://duckduckgo.com/?q="),
        GOOGLE("Google", "https://www.google.com/", "https://www.google.com/search?q="),
        BING("Bing", "https://www.bing.com/", "https://www.bing.com/search?q="),
        BRAVE("Brave", "https://search.brave.com/", "https://search.brave.com/search?q="),
        STARTPAGE("Startpage", "https://www.startpage.com/", "https://www.startpage.com/sp/search?query="),
        ECOSIA("Ecosia", "https://www.ecosia.org/", "https://www.ecosia.org/search?q=");

        /** What the address bar opens for [input]: the address itself when
         *  it looks like one, otherwise a search for it. */
        fun urlFor(input: String): String {
            val t = input.trim()
            if (t.isEmpty()) return home
            if (t.startsWith("http://") || t.startsWith("https://")) return t
            val looksLikeHost = !t.contains(' ') && t.contains('.') && !t.endsWith(".")
            return if (looksLikeHost) "https://$t" else query + com.mediaviewer.platform.urlEncode(t)
        }
    }

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences(PREFS)
        prefs = p
        listOf(
            draftSlot, folderSlot, noteSlot, feedSlot, streakSlot, pinSlot, effectSlot,
            eventSlot, noteFolderSlot, notesSlot, pollVoteSlot
        ).forEach { it.load(p) }
        notifyDms = p.getBoolean(KEY_NOTIFY_DMS, false)
        notifyInbox = p.getBoolean(KEY_NOTIFY_INBOX, false)
        batterySaver = p.getBoolean(KEY_BATTERY_SAVER, false)
        searchEngine = p.getString(KEY_SEARCH_ENGINE, null)
            ?.let { name -> SearchEngine.values().firstOrNull { it.name == name } } ?: SearchEngine.DUCKDUCKGO
    }

    private fun newId(): String = randomUuidString()

    // ── Supporter settings ───────────────────────────────────────────────

    fun updateNotifyDms(on: Boolean) { notifyDms = on; prefs?.edit()?.putBoolean(KEY_NOTIFY_DMS, on)?.apply() }
    fun updateNotifyInbox(on: Boolean) { notifyInbox = on; prefs?.edit()?.putBoolean(KEY_NOTIFY_INBOX, on)?.apply() }
    fun updateBatterySaver(on: Boolean) { batterySaver = on; prefs?.edit()?.putBoolean(KEY_BATTERY_SAVER, on)?.apply() }
    fun updateSearchEngine(engine: SearchEngine) { searchEngine = engine; prefs?.edit()?.putString(KEY_SEARCH_ENGINE, engine.name)?.apply() }

    var browserLastUrl: String
        get() = prefs?.getString(KEY_BROWSER_LAST, null) ?: ""
        set(value) { prefs?.edit()?.putString(KEY_BROWSER_LAST, value)?.apply() }

    // ── Drafts ───────────────────────────────────────────────────────────

    val drafts: List<PostDraftEntry> get() = draftSlot.value

    fun saveDraft(draft: PostDraftEntry): PostDraftEntry {
        val saved = draft.copy(id = draft.id.ifBlank { newId() }, savedAt = currentTimeMillis())
        draftSlot.save(prefs, listOf(saved) + draftSlot.value.filterNot { it.id == saved.id })
        return saved
    }

    fun deleteDraft(id: String) { draftSlot.save(prefs, draftSlot.value.filterNot { it.id == id }) }

    // ── Bookmark folders ─────────────────────────────────────────────────

    val bookmarkFolders: List<BookmarkFolder> get() = folderSlot.value

    fun createBookmarkFolder(name: String, cover: String?): BookmarkFolder {
        val folder = BookmarkFolder(id = newId(), name = name.trim().take(60), cover = cover, createdAt = currentTimeMillis())
        folderSlot.save(prefs, folderSlot.value + folder)
        return folder
    }

    fun updateBookmarkFolder(id: String, name: String? = null, cover: String? = null) {
        folderSlot.save(prefs, folderSlot.value.map {
            if (it.id != id) it else it.copy(name = name?.trim()?.take(60)?.ifBlank { null } ?: it.name, cover = cover ?: it.cover)
        })
    }

    fun deleteBookmarkFolder(id: String) { folderSlot.save(prefs, folderSlot.value.filterNot { it.id == id }) }

    /** Adds or removes [postUri] in a folder. Returns true if it's now in it. */
    fun toggleInBookmarkFolder(id: String, postUri: String, thumb: String?): Boolean {
        var nowIn = false
        folderSlot.save(prefs, folderSlot.value.map { f ->
            if (f.id != id) f
            else if (postUri in f.posts) f.copy(posts = f.posts - postUri)
            else { nowIn = true; f.copy(posts = listOf(postUri) + f.posts, cover = f.cover ?: thumb?.takeIf { it.isNotBlank() }) }
        })
        return nowIn
    }

    /** A post that's no longer saved leaves every folder. */
    fun removeFromAllBookmarkFolders(postUri: String) {
        if (folderSlot.value.none { postUri in it.posts }) return
        folderSlot.save(prefs, folderSlot.value.map { if (postUri in it.posts) it.copy(posts = it.posts - postUri) else it })
    }

    // ── Profile notes ────────────────────────────────────────────────────

    fun profileNote(did: String?): String = if (did.isNullOrBlank()) "" else noteSlot.value[did] ?: ""

    fun setProfileNote(did: String, text: String) {
        if (did.isBlank()) return
        val t = text.trim()
        noteSlot.save(prefs, if (t.isEmpty()) noteSlot.value - did else noteSlot.value + (did to t))
    }

    // ── Local feeds ──────────────────────────────────────────────────────

    val localFeeds: List<LocalFeed> get() = feedSlot.value
    fun isLocalFeedUri(uri: String?): Boolean = uri != null && uri.startsWith(LOCAL_FEED_PREFIX)
    fun localFeed(uri: String?): LocalFeed? = if (uri == null) null else feedSlot.value.firstOrNull { it.uri == uri }

    fun saveLocalFeed(feed: LocalFeed): LocalFeed {
        val saved = feed.copy(id = feed.id.ifBlank { newId() }, name = feed.name.trim().take(40))
        val existing = feedSlot.value
        feedSlot.save(prefs, if (existing.any { it.id == saved.id }) existing.map { if (it.id == saved.id) saved else it } else existing + saved)
        return saved
    }

    fun deleteLocalFeed(id: String) { feedSlot.save(prefs, feedSlot.value.filterNot { it.id == id }) }

    // ── DMs: streaks, pins, effects ──────────────────────────────────────

    fun dmStreak(convoId: String): DmStreak = streakSlot.value[convoId] ?: DmStreak()

    fun setDmStreak(convoId: String, streak: DmStreak) {
        if (convoId.isBlank() || streakSlot.value[convoId] == streak) return
        streakSlot.save(prefs, streakSlot.value + (convoId to streak))
    }

    val pinnedDms: List<String> get() = pinSlot.value
    fun isDmPinned(convoId: String): Boolean = convoId in pinSlot.value
    fun setDmPinned(convoId: String, pinned: Boolean) {
        if (convoId.isBlank()) return
        pinSlot.save(prefs, if (pinned) (pinSlot.value - convoId) + convoId else pinSlot.value - convoId)
    }

    fun dmEffectPlayed(messageId: String): Boolean = messageId in effectSlot.value
    fun markDmEffectsPlayed(messageIds: List<String>) {
        val fresh = messageIds.filter { it.isNotBlank() && it !in effectSlot.value }
        if (fresh.isEmpty()) return
        effectSlot.save(prefs, (effectSlot.value + fresh).takeLast(600))
    }

    // ── Polls ────────────────────────────────────────────────────────────

    /** The letter this device voted on a poll (post URI), if any. */
    fun pollVote(postUri: String): String? = pollVoteSlot.value[postUri]
    fun setPollVote(postUri: String, letter: String) {
        val m = pollVoteSlot.value + (postUri to letter)
        pollVoteSlot.save(prefs, if (m.size > 400) m.entries.drop(m.size - 400).associate { it.key to it.value } else m)
    }

    // ── Calendar ─────────────────────────────────────────────────────────

    val calendarEvents: List<CalendarEvent> get() = eventSlot.value
    fun addCalendarEvent(day: Int, minute: Int, title: String) {
        eventSlot.save(prefs, eventSlot.value + CalendarEvent(newId(), day, minute, title.trim()))
    }
    fun deleteCalendarEvent(id: String) { eventSlot.save(prefs, eventSlot.value.filterNot { it.id == id }) }

    // ── Notes ────────────────────────────────────────────────────────────

    val noteFolders: List<NoteFolder> get() = noteFolderSlot.value
    val notes: List<NoteEntry> get() = notesSlot.value

    fun createNoteFolder(name: String): NoteFolder {
        val f = NoteFolder(newId(), name.trim().take(40))
        noteFolderSlot.save(prefs, noteFolderSlot.value + f)
        return f
    }

    /** Deleting a folder keeps its notes (they move to "All Notes"). */
    fun deleteNoteFolder(id: String) {
        noteFolderSlot.save(prefs, noteFolderSlot.value.filterNot { it.id == id })
        notesSlot.save(prefs, notesSlot.value.map { if (it.folderId == id) it.copy(folderId = null) else it })
    }

    fun saveNote(note: NoteEntry): NoteEntry {
        val saved = note.copy(id = note.id.ifBlank { newId() }, updatedAt = currentTimeMillis())
        notesSlot.save(prefs, listOf(saved) + notesSlot.value.filterNot { it.id == saved.id })
        return saved
    }

    fun deleteNote(id: String) { notesSlot.save(prefs, notesSlot.value.filterNot { it.id == id }) }
}
