package com.mediaviewer.tagging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.mediaviewer.model.MediaItem
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.repository.E621Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    // Tagging-speed fix: kept for fetchBitmapForTagging's Coil lookups
    // (Coil's own imageLoader() call takes a Context and internally uses
    // the application one regardless, but holding this explicitly avoids
    // ever accidentally leaking an Activity context into a longer-lived
    // repository field).
    private val appContext = context.applicationContext
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
        val modelState: TaggerModelManager.State = TaggerModelManager.State.Ready,
        // Tagging page redesign (item 3): whichever post is being fetched/
        // inferred right now, so the overlay can show it full-screen in real
        // time instead of a generic loading box. Null before the first item
        // starts (still downloading the model, or between pages of the
        // liked-posts pagination) and while nothing is running.
        val currentItem: MediaItem? = null
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

    /** Settings' "Delete Tagged Post Database" button (item 5): wipes every
     *  scanned/tagged post so the person can restart the dataset from
     *  scratch. Only clears the tag data — the already-downloaded model
     *  file is left alone (no reason to force a ~390MB re-download just to
     *  reset tagging progress), and a running tag-all pass should be
     *  stopped first (the caller does this — see MainViewModel.
     *  deleteTaggedDatabase) so it doesn't keep writing rows back in while
     *  this runs. */
    suspend fun deleteDatabase() = withContext(Dispatchers.IO) { db.clearAll() }

    // ── Import/Export (item 4) ──────────────────────────────────────────
    // JSON (de)serialization itself lives in MainViewModel (via Gson,
    // already a project dependency — see NetworkClient.kt) since that's
    // where file I/O against a picked Uri already happens for the custom
    // font import feature; this repository only ever deals in plain Kotlin
    // data (TagDatabase.ExportedPost/DatasetInfo), same as every other
    // method here.

    /** Everything currently tagged, across every dataset — the source data
     *  for Settings' "Export" button. */
    suspend fun exportAllPosts(): List<TagDatabase.ExportedPost> = withContext(Dispatchers.IO) { db.allPostsForExport() }

    /** Settings' "Import" button, once a file's been picked and parsed. */
    suspend fun importDataset(name: String, posts: List<TagDatabase.ExportedPost>): TagDatabase.DatasetInfo =
        withContext(Dispatchers.IO) { db.importDataset(name, posts) }

    /** Settings' imported-datasets list, under Import/Export. */
    suspend fun listImportedDatasets(): List<TagDatabase.DatasetInfo> = withContext(Dispatchers.IO) { db.listImportedDatasets() }

    /** The list's per-row delete ("X"). */
    suspend fun deleteDataset(id: String) = withContext(Dispatchers.IO) { db.deleteDataset(id) }

    /** Full backlog pass: pages through every liked post (Bluesky or e621,
     *  whichever app mode is active), skips anything already in the
     *  dataset (so a cancelled/resumed run doesn't redo work), tags the
     *  rest, and streams progress back for the tagging overlay.
     *
     *  [concurrency] (Settings' tagging slider, 1-10) controls how many
     *  posts get fetched+decoded ahead of the inference queue at once (see
     *  [tagBatch]'s own doc comment for why this is a *prefetch depth*, not
     *  a simultaneous-inference count — the shared tagger's OrtSession
     *  already saturates the phone's cores for one inference at a time, so
     *  actual tagging always happens serially, one post at a time; what the
     *  slider buys is keeping the next posts' bitmaps ready and waiting so
     *  inference never stalls on network/decode between posts). No shared
     *  mutable state to worry about across producers besides the counters
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
            // Tagging page redesign (item 3): tracks whichever post the
            // single serial inference consumer below is currently on, for
            // reportProgress() to attach to every update. An AtomicReference
            // rather than a plain local var for the same cross-thread-
            // visibility reason scanned/tagged above are AtomicIntegers —
            // the consumer coroutine that writes it and the producer
            // coroutines that also call reportProgress() (for text-only
            // items) can run on different Dispatchers.IO threads.
            val currentItemRef = java.util.concurrent.atomic.AtomicReference<MediaItem?>(null)
            fun reportProgress() {
                onProgress(Progress(scanned.get(), tagged.get(), db.datasetSizeBytes(), isRunning = true, isComplete = false, currentItem = currentItemRef.get()))
            }

            /** Tags a batch of not-yet-indexed items.
             *
             *  Restructured (item 2 fix) from a chunked `awaitAll()` loop
             *  into a fetch/decode pipeline feeding one serial inference
             *  consumer. The old version launched [parallelism] items
             *  concurrently per chunk, but every one of those coroutines
             *  called into the same shared `ImageTagger`/`OrtSession`, whose
             *  intra-op thread pool already uses nearly all of the phone's
             *  cores for a *single* inference — so concurrent `Run()` calls
             *  just queued/timeshared the same cores instead of actually
             *  speeding anything up, and items still finished (and reported
             *  progress) essentially one at a time. Fetch+decode, on the
             *  other hand, genuinely does parallelize (network I/O and
             *  bitmap decode aren't CPU-bound on the inference cores), and
             *  the old chunk-barrier (`chunked().forEach { awaitAll() }`)
             *  also meant the next chunk's fetches couldn't start until the
             *  current chunk's slowest inference finished.
             *
             *  This version launches [parallelism] producer coroutines that
             *  fetch+decode items concurrently and push the results onto a
             *  bounded channel, and runs exactly one consumer that drains
             *  that channel and calls into the tagger serially — so while
             *  post N is being inferred, N+1, N+2, ... are already being
             *  fetched/decoded in the background, and inference time is no
             *  longer paid serially *between* the fetch/decode of the next
             *  item. The "posts tagged at once" slider now controls how
             *  many items are prefetched ahead of the inference queue,
             *  rather than how many are inferred at the same instant (see
             *  the Settings copy for this slider). */
            suspend fun tagBatch(items: List<MediaItem>) {
                val toTag = items.filter { it.postUri.isNotBlank() && !db.isIndexed(it.postUri) }
                if (toTag.isEmpty() || cancelRequested) return

                coroutineScope {
                    val itemQueue = Channel<MediaItem>(Channel.UNLIMITED)
                    toTag.forEach { itemQueue.trySend(it) }
                    itemQueue.close()

                    // Bounded to `parallelism` so producers can't run
                    // arbitrarily far ahead of inference and pile up
                    // decoded bitmaps in memory.
                    val preparedQueue = Channel<PreparedItem>(capacity = parallelism)

                    val producers = (1..parallelism).map {
                        launch(Dispatchers.IO) {
                            for (item in itemQueue) {
                                if (cancelRequested) break
                                if (item.isTextOnly) {
                                    // No fetch/decode/inference needed for a
                                    // text-only post — handle it right here
                                    // instead of round-tripping through the
                                    // inference consumer for nothing.
                                    db.storeTags(item.postUri, item.postCid, "", emptyList())
                                    scanned.incrementAndGet()
                                    reportProgress()
                                    continue
                                }
                                preparedQueue.send(PreparedItem(item, prepareMedia(item)))
                            }
                        }
                    }
                    // Close preparedQueue once every producer has drained
                    // itemQueue, so the consumer's `for` loop below knows
                    // when there's genuinely nothing left to infer.
                    launch {
                        producers.forEach { it.join() }
                        preparedQueue.close()
                    }

                    // Single serial consumer: the only coroutine that ever
                    // calls into the shared tagger, so inference for item
                    // N+1 doesn't start until N is done — but its bitmap is
                    // already sitting decoded and ready the moment it does.
                    for (prepared in preparedQueue) {
                        if (cancelRequested) {
                            if (prepared.media.ownsBitmap) prepared.media.bitmap?.recycle()
                            continue
                        }
                        // Tagging page redesign (item 3): report the post as
                        // "now tagging" before running inference on it (not
                        // just after), so the full-screen overlay shows it
                        // for the whole time it's actually being tagged —
                        // inference itself is real, visible work (see
                        // ImageTagger.tag's own doc comments), not
                        // instantaneous.
                        currentItemRef.set(prepared.item)
                        reportProgress()
                        val newlyTagged = inferAndStore(loadedTagger, prepared.item, prepared.media)
                        scanned.incrementAndGet()
                        if (newlyTagged) tagged.incrementAndGet()
                        reportProgress()
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

    /** Tagging-speed fix: routed through Coil's shared ImageLoader (memory
     *  + disk cache) instead of always doing a fresh OkHttp fetch. For the
     *  realtime tag-on-like path especially, this exact URL was very likely
     *  just decoded by Coil moments ago to display the post the person is
     *  looking at right now — reusing that cache turns what would
     *  otherwise be a second full network round trip into an in-memory
     *  lookup. Falls back to the original direct fetch+decode on any cache
     *  miss/failure, so this can only ever be as fast or faster, never
     *  slower. `allowHardware(false)` is required, not optional — a
     *  hardware Bitmap can't be read back for pixel access, same reason
     *  fetchDominantColor in GlassTheme.kt needs it.
     *
     *  Returns the bitmap paired with whether *this* call owns it and must
     *  recycle it when done: a Coil-sourced bitmap may be the same shared
     *  instance sitting in Coil's memory cache for the *displayed* post —
     *  recycling that would corrupt what the feed is showing on screen the
     *  moment this runs, so only bitmaps decoded directly here (the
     *  fallback path) are ever ours to recycle. */
    private suspend fun fetchBitmapForTagging(url: String): Pair<Bitmap, Boolean>? {
        if (url.isBlank()) return null
        try {
            val loader = coil.Coil.imageLoader(appContext)
            val request = coil.request.ImageRequest.Builder(appContext)
                .data(url).size(896, 896).allowHardware(false).build()
            val bmp = (loader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bmp != null) return bmp to false
        } catch (_: Exception) { /* fall through to the direct fetch below */ }
        return fetchBytes(url)?.let { decodeBitmap(it) }?.let { it to true }
    }

    /** The fetch+decode half of tagging a post — everything that's safe to
     *  run concurrently across several posts at once (network I/O, bitmap
     *  decode, video frame extraction). Deliberately has no dependency on
     *  the tagger, so [tagBatch]'s producer coroutines can call this without
     *  ever touching the shared `ImageTagger`/`OrtSession`. */
    private data class PreparedMedia(val bitmap: Bitmap?, val ownsBitmap: Boolean, val sourceUrlForRecord: String)

    /** Pairs a still-to-be-inferred item with its already-fetched/decoded
     *  media, as passed from [tagBatch]'s producers to its single serial
     *  inference consumer. */
    private data class PreparedItem(val item: MediaItem, val media: PreparedMedia)

    private suspend fun prepareMedia(item: MediaItem): PreparedMedia {
        var bitmap: Bitmap? = null
        var ownsBitmap = false
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
            val frame = extractMiddleFrame(item.mediaUrl)
            if (frame != null) {
                bitmap = frame; ownsBitmap = true
            } else if (item.thumbUrl.isNotBlank()) {
                // Frame grab failed (e.g. an unsupported codec/container) —
                // fall back to the feed-supplied poster thumbnail rather
                // than leaving the post untagged.
                fetchBitmapForTagging(item.thumbUrl)?.let { (bmp, owns) -> bitmap = bmp; ownsBitmap = owns }
            }
        } else {
            // Tagging-speed fix: prefer the mid-resolution taggingUrl (see
            // its doc comment on MediaItem) over the full-resolution
            // mediaUrl — same final 448x448 input either way, but a much
            // smaller fetch+decode. Still recorded against the item's real
            // mediaUrl/thumbUrl below so the stored dataset row points at
            // the same URL the rest of the app already uses for this post.
            val imageUrl = item.taggingUrl.ifBlank { item.mediaUrl.ifBlank { item.thumbUrl } }
            sourceUrlForRecord = item.mediaUrl.ifBlank { item.thumbUrl }
            if (imageUrl.isNotBlank()) {
                fetchBitmapForTagging(imageUrl)?.let { (bmp, owns) -> bitmap = bmp; ownsBitmap = owns }
            }
        }
        return PreparedMedia(bitmap, ownsBitmap, sourceUrlForRecord)
    }

    /** The inference half of tagging a post — the part that must stay
     *  serial across a batch (see [tagBatch]'s doc comment for why: the
     *  shared `OrtSession`'s intra-op thread pool already uses nearly all
     *  of the phone's cores for one inference, so running several at once
     *  doesn't get any real wall-clock benefit).
     *
     *  Returns true if this call newly tagged the post with >=1 tag (used
     *  to increment the running "tagged" counter). Callers are responsible
     *  for the resume-skip check (db.isIndexed) before calling this, since
     *  the two counting paths (tagAllLiked's batch loop vs tagOnLike's
     *  single-post realtime path) need different behavior on an
     *  already-indexed post — tagAllLiked skips it entirely (already
     *  counted), while tagOnLike re-tags unconditionally (a like can only
     *  fire once for a given post in normal use, but re-tagging on repeat
     *  calls is harmless either way). */
    private fun inferAndStore(imageTagger: ImageTagger, item: MediaItem, prepared: PreparedMedia): Boolean {
        val finalBitmap = prepared.bitmap
        if (finalBitmap == null) {
            db.storeTags(item.postUri, item.postCid, prepared.sourceUrlForRecord, emptyList())
            return false
        }
        val tags = try { imageTagger.tag(finalBitmap) } finally { if (prepared.ownsBitmap) finalBitmap.recycle() }
        // Bitmaps/decode buffers we own are eligible for GC/release the
        // moment this function returns; nothing here is ever written to
        // disk, and Coil-owned bitmaps are left untouched for Coil's own
        // cache to manage.
        db.storeTags(item.postUri, item.postCid, prepared.sourceUrlForRecord, tags)
        return tags.isNotEmpty()
    }

    /** Realtime single-post path (see [tagOnLike]) — just runs prepare then
     *  infer back-to-back, no pipelining needed for exactly one item. */
    private suspend fun tagOnePost(imageTagger: ImageTagger, item: MediaItem): Boolean {
        if (item.postUri.isBlank()) return false
        if (item.isTextOnly) {
            db.storeTags(item.postUri, item.postCid, "", emptyList())
            return false
        }
        return inferAndStore(imageTagger, item, prepareMedia(item))
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

    /** Decodes at roughly 2x the tagger's 448px input (not decoded straight
     *  to 448 itself — that would double-downsample: once here, coarsely,
     *  then again in ImageTagger's own bicubic-equivalent resize, losing
     *  quality a single well-chosen resize wouldn't). A modern phone photo
     *  can be 4000px+ on a side; decoding that in full just to immediately
     *  discard ~95% of it in [ImageTagger]'s own downscale wastes real
     *  decode time and a large transient allocation for zero quality
     *  benefit — inSampleSize lets BitmapFactory's own decoder do the coarse
     *  part of the downscale as it decodes, which is substantially cheaper
     *  than decode-at-full-res-then-Bitmap.createScaledBitmap on the result. */
    private fun decodeBitmap(bytes: ByteArray): Bitmap? = try {
        val targetSize = 896
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetSize && bounds.outHeight / (sample * 2) >= targetSize) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Exception) { null }

    private fun fetchBytes(url: String): ByteArray? = try {
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (_: Exception) { null }

    /** Search page's "Liked" tab.
     *
     *  Bug fix (item 5): a query that's non-blank but sanitizes away to
     *  nothing — e.g. typed entirely in symbols like "!!!" — used to fall
     *  through to [TagDatabase.searchPostUris] with an empty term-group
     *  list, which it (correctly) treats as "nothing to search for" and
     *  returns zero results, even though the person typed *something*. A
     *  blank query already browses everything tagged so far (see
     *  MainViewModel.performLikedTagSearch) — a query with no *searchable*
     *  characters in it should behave the same way rather than looking
     *  "broken" with a permanent empty result. */
    fun search(query: String): List<String> {
        val groups = TagAliases.toTagGroups(query)
        if (groups.isEmpty()) return browseAllTagged()
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
