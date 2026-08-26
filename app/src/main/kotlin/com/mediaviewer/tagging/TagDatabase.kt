package com.mediaviewer.tagging

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local-only SQLite store for the AI Tagging feature — schema adapted from
 *  the "Local On-Device AI Content Tagging Architecture" spec's
 *  liked_media / media_tags tables. Nothing in here ever touches the
 *  network or the account's AT Protocol PDS; it's purely a local index
 *  keyed by post URI, built by [com.mediaviewer.tagging.TaggingRepository]
 *  from images that are downloaded into memory, tagged, and immediately
 *  discarded (see that class's own doc comment — the actual media files are
 *  never written to disk by this feature).
 *
 *  Bug fix: this originally also created an `fts5`-backed virtual table for
 *  search (`tag_search_fts`), on the assumption that the FTS5 module is
 *  always compiled into the SQLite build Android ships. That's not actually
 *  guaranteed — it's an optional SQLite compile-time module, and plenty of
 *  real-world OEM system-image SQLite builds omit it (this crashed with
 *  `no such module: fts5` on a real device). Since every tag here is
 *  already a single discrete token (e.g. "dog_ears", not prose), full-text
 *  tokenized search was never actually buying anything over plain exact
 *  matching anyway — so search is now just an indexed `tag_name` lookup on
 *  a normal table (see [searchPostUris]), which needs nothing beyond core
 *  SQLite and is if anything more precise for this data. */
class TagDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS liked_media (
                post_uri TEXT PRIMARY KEY,
                cid TEXT NOT NULL,
                media_url TEXT NOT NULL,
                indexed_timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS media_tags (
                post_uri TEXT NOT NULL,
                tag_name TEXT NOT NULL,
                confidence REAL NOT NULL,
                FOREIGN KEY(post_uri) REFERENCES liked_media(post_uri) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_tags_post_uri ON media_tags(post_uri)")
        // The index that actually matters for search speed: every query in
        // searchPostUris filters by tag_name first.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_tags_tag_name ON media_tags(tag_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Migrating off a v1 database that may have partially included
            // (or attempted and failed to include) the old fts5 table.
            db.execSQL("DROP TABLE IF EXISTS tag_search_fts")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_tags_tag_name ON media_tags(tag_name)")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    /** True if this post has already been scanned/tagged (so a resumed
     *  "tag all liked posts" run, or the realtime tag-on-like path, can
     *  skip work it already did). */
    fun isIndexed(postUri: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM liked_media WHERE post_uri = ? LIMIT 1", arrayOf(postUri)).use {
            return it.moveToFirst()
        }
    }

    /** Records one post + its tags. Called once per successfully-tagged
     *  image; a post with zero tags above the confidence threshold still
     *  gets a liked_media row (so it counts as "scanned" and isn't retried
     *  forever) but no media_tags rows. */
    fun storeTags(postUri: String, cid: String, mediaUrl: String, tags: List<Pair<String, Float>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO liked_media (post_uri, cid, media_url, indexed_timestamp) VALUES (?, ?, ?, ?)",
                arrayOf(postUri, cid, mediaUrl, System.currentTimeMillis())
            )
            // Clear out anything from a previous pass (realtime re-tag, retry, etc.)
            db.execSQL("DELETE FROM media_tags WHERE post_uri = ?", arrayOf(postUri))
            tags.forEach { (tag, confidence) ->
                db.execSQL(
                    "INSERT INTO media_tags (post_uri, tag_name, confidence) VALUES (?, ?, ?)",
                    arrayOf(postUri, tag, confidence)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun scannedCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM liked_media", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun taggedCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(DISTINCT post_uri) FROM media_tags", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /** Size on disk of the dataset as it's being built — shown live in the
     *  tagging overlay per the "total storage size" request. SQLite keeps
     *  writes in -wal/-shm files until checkpointed, so this sums all three. */
    fun datasetSizeBytes(): Long {
        val base = readableDatabase.path?.let { java.io.File(it) } ?: return 0L
        val wal = java.io.File(base.path + "-wal")
        val shm = java.io.File(base.path + "-shm")
        return (if (base.exists()) base.length() else 0L) +
            (if (wal.exists()) wal.length() else 0L) +
            (if (shm.exists()) shm.length() else 0L)
    }

    /** Search, without FTS: [termGroups] is a list of alias-expanded OR
     *  groups (see [TagAliases.toTagGroups]) — one group per word the
     *  person typed, e.g. searching "dog explicit" produces
     *  `[[canine, dog, dog_ears], [explicit]]`. A post has to have at least
     *  one tag from *every* group (AND across groups, OR within a group) —
     *  the same semantics the old FTS5 MATCH query had. Computed as a plain
     *  set intersection across per-group queries rather than one large SQL
     *  statement, since the number of groups is small (a handful of search
     *  words at most) and this keeps each query simple and index-friendly. */
    fun searchPostUris(termGroups: List<List<String>>, limit: Int = 200): List<String> {
        if (termGroups.isEmpty() || termGroups.any { it.isEmpty() }) return emptyList()
        var resultSet: LinkedHashSet<String>? = null
        val db = readableDatabase
        for (group in termGroups) {
            val placeholders = group.joinToString(",") { "?" }
            val matches = LinkedHashSet<String>()
            db.rawQuery(
                "SELECT DISTINCT post_uri FROM media_tags WHERE tag_name IN ($placeholders)",
                group.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) matches.add(cursor.getString(0))
            }
            resultSet = resultSet?.apply { retainAll(matches) } ?: matches
            if (resultSet.isEmpty()) break
        }
        return (resultSet ?: emptySet()).take(limit)
    }

    /** Item 2: default "browse all" view for the Liked search tab, most
     *  recently-tagged post first — only posts that ended up with >=1 tag
     *  (an EXISTS check against media_tags, same filter taggedCount() uses). */
    fun allTaggedPostUris(limit: Int = 200): List<String> {
        val results = mutableListOf<String>()
        readableDatabase.rawQuery(
            """
            SELECT lm.post_uri FROM liked_media lm
            WHERE EXISTS (SELECT 1 FROM media_tags mt WHERE mt.post_uri = lm.post_uri)
            ORDER BY lm.indexed_timestamp DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor -> while (cursor.moveToNext()) results.add(cursor.getString(0)) }
        return results
    }

    /** Item 3: the complete tag list for one post (for the Tags mode on a
     *  post opened from the Liked tab), highest confidence first. */
    fun tagsForPost(postUri: String): List<String> {
        val results = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT tag_name FROM media_tags WHERE post_uri = ? ORDER BY confidence DESC",
            arrayOf(postUri)
        ).use { cursor -> while (cursor.moveToNext()) results.add(cursor.getString(0)) }
        return results
    }

    /** Wipes the whole dataset — used if the user wants to re-tag from
     *  scratch (a fresh "Locally Tag All Liked Posts" run reuses existing
     *  rows instead by default via [isIndexed]). Now wired to Settings'
     *  "Delete Tagged Post Database" button (item 5) via
     *  [TaggingRepository.deleteDatabase]. Runs a VACUUM after the deletes —
     *  SQLite doesn't shrink the on-disk file just because its rows are
     *  gone, and the whole point of this button is to actually reclaim that
     *  space, not just make the tables logically empty while
     *  [datasetSizeBytes] keeps reporting the old size. */
    fun clearAll() {
        val db = writableDatabase
        db.execSQL("DELETE FROM media_tags")
        db.execSQL("DELETE FROM liked_media")
        db.execSQL("VACUUM")
    }

    companion object {
        private const val DB_NAME = "liked_media_tags.db"
        // Bumped 1 -> 2 for the fts5 removal (see class doc comment). Devices
        // that never got past v1's onCreate (it always threw before
        // completing, since CREATE VIRTUAL TABLE...fts5 failed) never
        // actually persisted a v1 database — SQLiteOpenHelper rolls back and
        // deletes on an onCreate failure — so onUpgrade only matters for the
        // hypothetical device that *did* have fts5 and created a v1 database
        // successfully before this fix.
        private const val DB_VERSION = 2

        @Volatile private var instance: TagDatabase? = null
        fun get(context: Context): TagDatabase =
            instance ?: synchronized(this) {
                instance ?: TagDatabase(context).also { instance = it }
            }
    }
}
