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
                indexed_timestamp INTEGER NOT NULL,
                dataset_id TEXT NOT NULL DEFAULT ''
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
        // Import/Export feature: one row per *imported* dataset (a JSON file
        // someone else exported from their own device — see
        // TaggingRepository.importDataset/exportAllPosts). The on-device
        // dataset built by the person's own "Locally Tag All Liked Posts"/
        // realtime tagging never gets a row here — it's identified purely by
        // liked_media.dataset_id being the empty-string sentinel (see
        // LOCAL_DATASET_ID below), so there's nothing to list/delete for it
        // beyond the existing "Delete Tagged Post Database" button. Imported
        // datasets each get their own id here so Settings can list them and
        // delete one without touching any other dataset's (or the local
        // dataset's) rows — everything in liked_media/media_tags is still
        // queried together with no dataset filter for search/browse/counts,
        // which is what "use them all as one" means in practice.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS datasets (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                imported_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_tags_post_uri ON media_tags(post_uri)")
        // The index that actually matters for search speed: every query in
        // searchPostUris filters by tag_name first.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_tags_tag_name ON media_tags(tag_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_liked_media_dataset_id ON liked_media(dataset_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Migrating off a v1 database that may have partially included
            // (or attempted and failed to include) the old fts5 table.
            db.execSQL("DROP TABLE IF EXISTS tag_search_fts")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_tags_tag_name ON media_tags(tag_name)")
        }
        if (oldVersion < 3) {
            // Import/Export feature: every pre-existing row belongs to the
            // person's own on-device dataset, so backfilling the new column
            // with the '' default (already what DEFAULT '' gives every
            // existing row) is exactly correct with no data migration needed
            // beyond adding the column itself.
            db.execSQL("ALTER TABLE liked_media ADD COLUMN dataset_id TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS datasets (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    imported_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_liked_media_dataset_id ON liked_media(dataset_id)")
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
     *  forever) but no media_tags rows.
     *
     *  [datasetId] defaults to [LOCAL_DATASET_ID] — every call from the
     *  normal on-device tagging pipeline (TaggingRepository.inferAndStore/
     *  tagOnePost/tagBatch's text-only shortcut) leaves this at the
     *  default, so only [importDataset] ever passes a real dataset id. */
    fun storeTags(postUri: String, cid: String, mediaUrl: String, tags: List<Pair<String, Float>>, datasetId: String = LOCAL_DATASET_ID) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO liked_media (post_uri, cid, media_url, indexed_timestamp, dataset_id) VALUES (?, ?, ?, ?, ?)",
                arrayOf(postUri, cid, mediaUrl, System.currentTimeMillis(), datasetId)
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
     *  words at most) and this keeps each query simple and index-friendly.
     *
     *  Bug fix (item 5 — "typing numbers or symbols... shows no results"):
     *  each term used to match only via an exact `tag_name IN (...)`
     *  lookup. Most real e621 tags aren't bare numbers/symbols on their own
     *  — they're a *part* of a compound tag (`rule_34`, `69_position`,
     *  `3d`) — so an exact-match-only query correctly, but unhelpfully,
     *  came back empty for exactly the kind of short numeric/symbolic term
     *  someone would type expecting a substring match. Every term now also
     *  tries a `LIKE '%term%'` match alongside the exact one (still OR'd
     *  together within the same group, still AND'd across groups), so
     *  "34" now finds "rule_34" the way a person typing it would expect,
     *  without changing behavior for a term that *is* an exact/aliased tag
     *  (that still matches first, via the same query). */
    fun searchPostUris(termGroups: List<List<String>>, limit: Int = 200): List<String> {
        if (termGroups.isEmpty() || termGroups.any { it.isEmpty() }) return emptyList()
        var resultSet: LinkedHashSet<String>? = null
        val db = readableDatabase
        for (group in termGroups) {
            val exactPlaceholders = group.joinToString(",") { "?" }
            val likeClauses = group.joinToString(" OR ") { "tag_name LIKE ?" }
            val likeArgs = group.map { "%$it%" }
            val matches = LinkedHashSet<String>()
            db.rawQuery(
                "SELECT DISTINCT post_uri FROM media_tags WHERE tag_name IN ($exactPlaceholders) OR $likeClauses",
                (group + likeArgs).toTypedArray()
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

    /** One row of the "imported datasets" list Settings shows under the
     *  Import/Export buttons — [postCount] is computed live off liked_media
     *  rather than stored, so it can never drift out of sync with reality
     *  (e.g. if a future feature ever lets someone delete individual posts). */
    data class DatasetInfo(val id: String, val name: String, val importedAt: Long, val postCount: Int)

    /** One post as read back out for [TaggingRepository.exportAllPosts] to
     *  serialize to JSON — a plain snapshot, not tied to any one dataset
     *  (export always bundles *everything* currently on the device, local +
     *  every imported dataset together, per the request that Export produces
     *  one shareable backup of "their dataset" as a whole). */
    data class ExportedPost(val postUri: String, val cid: String, val mediaUrl: String, val tags: List<Pair<String, Float>>)

    /** Every post currently in the database, across every dataset — the
     *  source data for an Export. */
    fun allPostsForExport(): List<ExportedPost> {
        val posts = LinkedHashMap<String, ExportedPost>()
        readableDatabase.rawQuery("SELECT post_uri, cid, media_url FROM liked_media", null).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                posts[uri] = ExportedPost(uri, cursor.getString(1), cursor.getString(2), emptyList())
            }
        }
        val tagsByPost = HashMap<String, MutableList<Pair<String, Float>>>()
        readableDatabase.rawQuery("SELECT post_uri, tag_name, confidence FROM media_tags ORDER BY confidence DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                tagsByPost.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1) to cursor.getFloat(2))
            }
        }
        return posts.values.map { it.copy(tags = tagsByPost[it.postUri] ?: emptyList()) }
    }

    /** Imports a friend's exported dataset as its own separate, independently
     *  deletable entry — per the request, importing must never override or
     *  merge into the existing on-device dataset. Everything lands in the
     *  same liked_media/media_tags tables (so search/browse still treats
     *  every dataset "as one", per the request) but tagged with a fresh
     *  [datasetId] so [deleteDataset] can later remove exactly this import
     *  and nothing else.
     *
     *  Uses INSERT OR IGNORE on post_uri (liked_media's primary key): if this
     *  import contains a post the device already has — from the local
     *  dataset or an earlier import — the existing row (and its tags) wins
     *  and the incoming duplicate is skipped rather than overwriting it or
     *  failing the whole import. A handful of coincidental overlapping likes
     *  between two people's datasets is expected and shouldn't block the
     *  rest of the import or silently reassign an existing post to a
     *  different dataset_id. */
    fun importDataset(name: String, posts: List<ExportedPost>): DatasetInfo {
        val id = java.util.UUID.randomUUID().toString()
        val importedAt = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO datasets (id, name, imported_at) VALUES (?, ?, ?)",
                arrayOf(id, name, importedAt)
            )
            var inserted = 0
            // Not android.database.Cursor — SQLiteStatement doesn't
            // implement java.io.Closeable, so this is explicit try/finally
            // rather than Kotlin's `.use {}`.
            val insertStmt = db.compileStatement(
                "INSERT OR IGNORE INTO liked_media (post_uri, cid, media_url, indexed_timestamp, dataset_id) VALUES (?, ?, ?, ?, ?)"
            )
            try {
                posts.forEach { post ->
                    if (post.postUri.isBlank()) return@forEach
                    insertStmt.clearBindings()
                    insertStmt.bindString(1, post.postUri)
                    insertStmt.bindString(2, post.cid)
                    insertStmt.bindString(3, post.mediaUrl)
                    insertStmt.bindLong(4, importedAt)
                    insertStmt.bindString(5, id)
                    val rowId = insertStmt.executeInsert()
                    if (rowId == -1L) return@forEach // already present under another dataset — skip its tags too
                    inserted++
                    post.tags.forEach { (tag, confidence) ->
                        db.execSQL(
                            "INSERT INTO media_tags (post_uri, tag_name, confidence) VALUES (?, ?, ?)",
                            arrayOf(post.postUri, tag, confidence)
                        )
                    }
                }
            } finally {
                insertStmt.close()
            }
            db.setTransactionSuccessful()
            return DatasetInfo(id, name, importedAt, inserted)
        } finally {
            db.endTransaction()
        }
    }

    /** Settings' imported-datasets list. Only ever contains entries created
     *  by [importDataset] — the local on-device dataset isn't listed here
     *  (see the `datasets` table's own doc comment in [onCreate]). */
    fun listImportedDatasets(): List<DatasetInfo> {
        val results = mutableListOf<DatasetInfo>()
        readableDatabase.rawQuery(
            """
            SELECT d.id, d.name, d.imported_at,
                (SELECT COUNT(*) FROM liked_media lm WHERE lm.dataset_id = d.id) AS post_count
            FROM datasets d ORDER BY d.imported_at DESC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(DatasetInfo(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3)))
            }
        }
        return results
    }

    /** Removes exactly one imported dataset's posts/tags — everything else
     *  (the local dataset, and every other import) is untouched, which is
     *  the whole point of tracking dataset_id per row (per the request:
     *  "if someone imports one they don't like they can remove it without
     *  deleting the others"). */
    fun deleteDataset(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM media_tags WHERE post_uri IN (SELECT post_uri FROM liked_media WHERE dataset_id = ?)", arrayOf(id))
            db.execSQL("DELETE FROM liked_media WHERE dataset_id = ?", arrayOf(id))
            db.execSQL("DELETE FROM datasets WHERE id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
        // Item 4 (Import/Export): "Delete Tagged Post Database" is the
        // nuclear "start over" option, so it clears every imported dataset's
        // listing too, not just the local tagged rows — otherwise Settings'
        // imported-datasets list would keep showing entries whose underlying
        // liked_media/media_tags rows had already been wiped.
        db.execSQL("DELETE FROM datasets")
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
        //
        // Bumped 2 -> 3 for the Import/Export feature's dataset_id column +
        // datasets table (see onUpgrade/onCreate).
        private const val DB_VERSION = 3

        /** Sentinel dataset_id for every row created by the normal on-device
         *  tagging pipeline (as opposed to an imported dataset — see the
         *  `datasets` table's doc comment in [onCreate]). Never actually
         *  shown to the user; just how liked_media rows are told apart. */
        const val LOCAL_DATASET_ID = ""

        @Volatile private var instance: TagDatabase? = null
        fun get(context: Context): TagDatabase =
            instance ?: synchronized(this) {
                instance ?: TagDatabase(context).also { instance = it }
            }
    }
}
