package com.mediaviewer.tagging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.mediaviewer.model.MediaItem
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.repository.E621Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Ties the tagger model, the local dataset ([TagDatabase]), and the app's
 *  existing Bluesky/e621 "liked posts" pagination together.
 *
 *  Storage note (per the request): this never writes the actual liked
 *  image/video to disk. Each post's media is fetched into an in-memory
 *  ByteArray, decoded straight to a Bitmap, run through the tagger, and
 *  then both are eligible for GC the moment [tagOnePost] returns — nothing
 *  but the post's URI/CID/media-URL and its resulting tag list ever reaches
 *  [TagDatabase]. See downloadAllBskyLiked/downloadAllE621Favorites in
 *  MainViewModel for the (very different, deliberately-persists-files)
 *  feature this reuses only the *pagination* shape of. */
class TaggingRepository(
    context: Context,
    private val bskyRepo: BlueskyRepository,
    private val e621Repo: E621Repository
) {
    private val db = TagDatabase.get(context)
    private val modelManager = TaggerModelManager(context)
    private val httpClient = OkHttpClient.Builder().build()

    @Volatile private var tagger: ImageTagger? = null
    @Volatile private var cancelRequested = false

    data class Progress(
        val scanned: Int,
        val tagged: Int,
        val datasetBytes: Long,
        val isRunning: Boolean,
        val isComplete: Boolean,
        val modelState: TaggerModelManager.State = TaggerModelManager.State.Ready
    )

    fun currentCounts(): Pair<Int, Int> = db.scannedCount() to db.taggedCount()
    fun datasetSizeBytes(): Long = db.datasetSizeBytes()
    fun isModelReady(): Boolean = modelManager.isReady()

    @Volatile private var cachedVocabulary: List<String>? = null

    /** The tagger's fixed tag vocabulary, for the search bar's autocomplete
     *  (item 4) — reads straight from the already-downloaded tags CSV, so
     *  it's available the instant the model's been fetched once, without
     *  needing to spin up a full ONNX session (that only happens when
     *  actually tagging an image). Returns empty before the initial
     *  "Locally Tag All Liked Posts" pass has ever run. */
    fun tagVocabulary(): List<String> {
        cachedVocabulary?.let { return it }
        if (!modelManager.tagsFile.exists()) return emptyList()
        val parsed = try {
            val lines = modelManager.tagsFile.readLines()
            if (lines.isEmpty()) emptyList() else {
                val header = lines.first().split(",")
                val nameIdx = header.indexOfFirst { it.trim().equals("name", ignoreCase = true) }
                    .let { if (it >= 0) it else 1.coerceAtMost(header.lastIndex) }
                lines.drop(1).mapNotNull { line ->
                    line.split(",").getOrNull(nameIdx)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
                }
            }
        } catch (_: Exception) { emptyList() }
        cachedVocabulary = parsed
        return parsed
    }

    private suspend fun ensureTagger(onModelProgress: (TaggerModelManager.State) -> Unit): ImageTagger {
        tagger?.let { return it }
        modelManager.ensureReady(onModelProgress)
        if (!modelManager.isReady()) error("Model not ready")
        return ImageTagger(modelManager.modelFile, modelManager.tagsFile).also { tagger = it }
    }

    fun cancel() { cancelRequested = true }

    /** Full backlog pass: pages through every liked post (Bluesky or e621,
     *  whichever app mode is active), skips anything already in the
     *  dataset (so a cancelled/resumed run doesn't redo work), tags the
     *  rest, and streams progress back for the tagging overlay.
     *
     *  [concurrency] (Settings' "posts tagged at once" slider, 1-10) tags
     *  that many posts of each fetched page in parallel rather than one at
     *  a time — each one is an independent network fetch + decode +
     *  inference pass with no shared mutable state except the counters
     *  below (kept as AtomicIntegers) and the SQLite writes in
     *  TagDatabase.storeTags (Android's SQLiteDatabase already serializes
     *  concurrent writers internally, so no extra locking is needed here). */
    suspend fun tagAllLiked(
        isBlueskyMode: Boolean,
        bskyToken: String,
        bskyDid: String,
        e621Username: String,
        e621ApiKey: String,
        concurrency: Int = 1,
        onProgress: (Progress) -> Unit
    ) {
        cancelRequested = false
        val parallelism = concurrency.coerceIn(1, 10)
        withContext(Dispatchers.IO) {
            onProgress(Progress(db.scannedCount(), db.taggedCount(), db.datasetSizeBytes(), isRunning = true, isComplete = false, modelState = TaggerModelManager.State.Downloading(0, 0)))
            val loadedTagger = try {
                ensureTagger { state -> onProgress(Progress(db.scannedCount(), db.taggedCount(), db.datasetSizeBytes(), isRunning = true, isComplete = false, modelState = state)) }
            } catch (e: Exception) {
                onProgress(Progress(db.scannedCount(), db.taggedCount(), db.datasetSizeBytes(), isRunning = false, isComplete = false, modelState = TaggerModelManager.State.Failed(e.message ?: "Model load failed")))
                return@withContext
            }

            val scanned = java.util.concurrent.atomic.AtomicInteger(db.scannedCount())
            val tagged = java.util.concurrent.atomic.AtomicInteger(db.taggedCount())
            fun reportProgress() {
                onProgress(Progress(scanned.get(), tagged.get(), db.datasetSizeBytes(), isRunning = true, isComplete = false))
            }

            /** Tags a batch of not-yet-indexed items, [parallelism] at a time. */
            suspend fun tagBatch(items: List<MediaItem>) {
                items.chunked(parallelism).forEach { chunk ->
                    if (cancelRequested) return
                    coroutineScope {
                        chunk.filter { it.postUri.isNotBlank() && !db.isIndexed(it.postUri) }
                            .map { item ->
                                async(Dispatchers.IO) {
                                    val newlyTagged = tagOnePost(loadedTagger, item)
                                    scanned.incrementAndGet()
                                    if (newlyTagged) tagged.incrementAndGet()
                                    reportProgress()
                                }
                            }.awaitAll()
                    }
                }
            }

            if (isBlueskyMode) {
                var cursor: String? = null
                do {
                    if (cancelRequested) break
                    val result = bskyRepo.getActorLikes(bskyToken, bskyDid, cursor).getOrNull() ?: break
                    val (items, nextCursor) = result
                    tagBatch(items)
                    cursor = nextCursor
                } while (cursor != null && !cancelRequested)
            } else {
                var page = 1
                while (!cancelRequested) {
                    val items = e621Repo.getFavorites(e621Username, e621ApiKey, page).getOrNull() ?: break
                    if (items.isEmpty()) break
                    tagBatch(items)
                    page++
                }
            }
            onProgress(Progress(scanned.get(), tagged.get(), db.datasetSizeBytes(), isRunning = false, isComplete = !cancelRequested))
        }
    }

    /** Realtime path: tags exactly one just-liked post, used by
     *  MainViewModel.toggleLike when the "Tag Post When Liked" setting is
     *  on. Silently no-ops (rather than throwing) if the model isn't
     *  downloaded yet — realtime tagging only makes sense once the person
     *  has already run the initial "Locally Tag All Liked Posts" pass, at
     *  which point the model is guaranteed to already be on disk. */
    suspend fun tagOnLike(item: MediaItem) {
        if (!modelManager.isReady()) return
        withContext(Dispatchers.IO) {
            val loadedTagger = try { ensureTagger { } } catch (_: Exception) { return@withContext }
            tagOnePost(loadedTagger, item)
        }
    }

    /** Returns true if this call newly tagged the post with >=1 tag (used
     *  to increment the running "tagged" counter). Callers are responsible
     *  for the resume-skip check (db.isIndexed) before calling this, since
     *  the two counting paths (tagAllLiked's batch loop vs tagOnLike's
     *  single-post realtime path) need different behavior on an
     *  already-indexed post — tagAllLiked skips it entirely (already
     *  counted), while tagOnLike re-tags unconditionally (a like can only
     *  fire once for a given post in normal use, but re-tagging on repeat
     *  calls is harmless either way). */
    private fun tagOnePost(imageTagger: ImageTagger, item: MediaItem): Boolean {
        if (item.postUri.isBlank()) return false
        if (item.isTextOnly) {
            db.storeTags(item.postUri, item.postCid, "", emptyList())
            return false
        }

        val bitmap: Bitmap?
        val sourceUrlForRecord: String

        if (item.isVideo && item.mediaUrl.isNotBlank()) {
            // Per request: for video posts, don't try to tag the whole
            // clip — grab the frame from the middle of the video (a much
            // more representative single frame than the first/poster frame,
            // which is frequently a black frame, a fade-in, or an
            // off-topic title card) and tag just that. MediaMetadataRetriever
            // can pull a single frame straight from the remote URL over
            // HTTP without ever writing the video itself to disk.
            sourceUrlForRecord = item.mediaUrl
            bitmap = extractMiddleFrame(item.mediaUrl) ?: run {
                // Frame grab failed (e.g. an unsupported codec/container) —
                // fall back to the feed-supplied poster thumbnail rather
                // than leaving the post untagged.
                item.thumbUrl.takeIf { it.isNotBlank() }?.let { fetchBytes(it) }
                    ?.let { decodeBitmap(it) }
            }
        } else {
            val imageUrl = item.mediaUrl.ifBlank { item.thumbUrl }
            sourceUrlForRecord = imageUrl
            bitmap = if (imageUrl.isBlank()) null else fetchBytes(imageUrl)?.let { decodeBitmap(it) }
        }

        if (bitmap == null) {
            db.storeTags(item.postUri, item.postCid, sourceUrlForRecord, emptyList())
            return false
        }
        val tags = try { imageTagger.tag(bitmap) } finally { bitmap.recycle() }
        // The bitmap (and, for videos, the retriever's internal decode
        // buffers) are both eligible for GC/release as soon as this
        // function returns — nothing here is written to disk.
        db.storeTags(item.postUri, item.postCid, sourceUrlForRecord, tags)
        return tags.isNotEmpty()
    }

    /** Pulls the single frame at the video's halfway point. Uses
     *  getFrameAtTime with OPTION_CLOSEST (rather than the cheaper
     *  OPTION_CLOSEST_SYNC, which snaps to the nearest keyframe and on a
     *  long-GOP clip can land far from the actual midpoint) so the frame
     *  really is representative of the middle of the clip, not just
     *  whichever keyframe happens to be nearby. */
    private fun extractMiddleFrame(videoUrl: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoUrl, emptyMap())
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val midpointUs = (durationMs * 1000L) / 2
            retriever.getFrameAtTime(midpointUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? =
        try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Exception) { null }

    private fun fetchBytes(url: String): ByteArray? = try {
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (_: Exception) { null }

    /** Search page's "Liked" tab. */
    fun search(query: String): List<String> {
        val groups = TagAliases.toTagGroups(query)
        return db.searchPostUris(groups)
    }

    /** Default view for the Liked tab (item 2): every tagged post, most
     *  recently-tagged first — shown before the person types anything. */
    fun browseAllTagged(limit: Int = 200): List<String> = db.allTaggedPostUris(limit)

    /** Full tag list for one post — item 3's "Tags mode needs to display
     *  ALL the tags on the post", sorted highest confidence first. */
    fun tagsForPost(postUri: String): List<String> = db.tagsForPost(postUri)

    companion object {
        @Volatile private var instance: TaggingRepository? = null
        fun get(context: Context, bskyRepo: BlueskyRepository, e621Repo: E621Repository): TaggingRepository =
            instance ?: synchronized(this) {
                instance ?: TaggingRepository(context.applicationContext, bskyRepo, e621Repo).also { instance = it }
            }
    }
}
