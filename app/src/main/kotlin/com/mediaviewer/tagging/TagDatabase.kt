package com.mediaviewer.tagging

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local-only SQLite store for the AI Tagging feature — schema lifted
 *  directly from the "Local On-Device AI Content Tagging Architecture" spec
 *  (liked_media / media_tags / tag_search_fts). Nothing in here ever touches
 *  the network or the account's AT Protocol PDS; it's purely a local index
 *  keyed by post URI, built by [com.mediaviewer.tagging.TaggingRepository]
 *  from images that are downloaded into memory, tagged, and immediately
 *  discarded (see that class's own doc comment — the actual media files are
 *  never written to disk by this feature).
 *
 *  FTS5 is bundled in the SQLite build shipped with Android since API 16's
 *  successor devices (in practice, every device this app's minSdk=26
 *  targets has it), so the virtual table below is safe to create
 *  unconditionally. */
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
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS tag_search_fts USING fts5(
                post_uri UNINDEXED,
                tag_space_separated
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_tags_post_uri ON media_tags(post_uri)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No prior versions yet — nothing to migrate.
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
     *  forever) but no media_tags/FTS rows. */
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
            db.execSQL("DELETE FROM tag_search_fts WHERE post_uri = ?", arrayOf(postUri))
            if (tags.isNotEmpty()) {
                tags.forEach { (tag, confidence) ->
                    db.execSQL(
                        "INSERT INTO media_tags (post_uri, tag_name, confidence) VALUES (?, ?, ?)",
                        arrayOf(postUri, tag, confidence)
                    )
                }
                val spaceSeparated = tags.joinToString(" ") { it.first }
                db.execSQL(
                    "INSERT INTO tag_search_fts (post_uri, tag_space_separated) VALUES (?, ?)",
                    arrayOf(postUri, spaceSeparated)
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

    /** Runs an FTS5 MATCH query (already alias-expanded by
     *  [TagAliases.expand]) and returns matching post URIs, best rank first. */
    fun searchPostUris(ftsQuery: String, limit: Int = 200): List<String> {
        if (ftsQuery.isBlank()) return emptyList()
        val results = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT post_uri FROM tag_search_fts WHERE tag_space_separated MATCH ? ORDER BY rank LIMIT ?",
            arrayOf(ftsQuery, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) results.add(cursor.getString(0))
        }
        return results
    }

    /** Wipes the whole dataset — used if the user wants to re-tag from
     *  scratch (a fresh "Locally Tag All Liked Posts" run reuses existing
     *  rows instead by default via [isIndexed]; this is only for an
     *  explicit reset, not currently wired to a button but kept available). */
    fun clearAll() {
        val db = writableDatabase
        db.execSQL("DELETE FROM media_tags")
        db.execSQL("DELETE FROM tag_search_fts")
        db.execSQL("DELETE FROM liked_media")
    }

    companion object {
        private const val DB_NAME = "liked_media_tags.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: TagDatabase? = null
        fun get(context: Context): TagDatabase =
            instance ?: synchronized(this) {
                instance ?: TagDatabase(context).also { instance = it }
            }
    }
}
