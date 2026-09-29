package com.mediaviewer.tagging

import com.mediaviewer.model.MediaItem

/*
 * AI Tagging, as the ViewModel and UI see it. Android's implementation is
 * TaggingRepository (on-device ONNX model + SQLite); iOS doesn't have the
 * tagger yet and uses [UnavailableTaggingService], so the feature is simply
 * hidden there.
 */

/** Where the tagger model download stands (was TaggerModelManager.State). */
sealed class TaggerState {
    data object NotDownloaded : TaggerState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : TaggerState()
    data object Ready : TaggerState()
    data class Failed(val message: String) : TaggerState()
}

/** One row of the "imported datasets" list Settings shows under the
 *  Import/Export buttons — [postCount] is computed live off liked_media
 *  rather than stored, so it can never drift out of sync with reality
 *  (e.g. if a future feature ever lets someone delete individual posts).
 *  (Was TagDatabase.DatasetInfo.) */
data class TagDatasetInfo(val id: String, val name: String, val importedAt: Long, val postCount: Int)

/** One post as read back out for an export (was TagDatabase.ExportedPost). */
data class TagExportedPost(val postUri: String, val cid: String, val mediaUrl: String, val tags: List<Pair<String, Float>>)

/** Progress of a tagging run (was TaggingRepository.Progress). */
data class TaggingProgress(
    val scanned: Int,
    val tagged: Int,
    val datasetBytes: Long,
    val isRunning: Boolean,
    val isComplete: Boolean,
    val modelState: TaggerState = TaggerState.Ready,
    // Tagging page redesign (item 3): whichever post is being fetched/
    // inferred right now, so the overlay can show it full-screen in real
    // time instead of a generic loading box. Null before the first item
    // starts (still downloading the model, or between pages of the
    // liked-posts pagination) and while nothing is running.
    val currentItem: MediaItem? = null
)

interface TaggingService {
    /** False where the platform has no on-device tagger (iOS for now). */
    val isSupported: Boolean get() = true
    fun currentCounts(): Pair<Int, Int>
    fun datasetSizeBytes(): Long
    fun isModelReady(): Boolean
    fun tagVocabulary(): List<String>
    fun isTaggerLoaded(): Boolean
    fun cancel()
    suspend fun downloadModel(onState: (TaggerState) -> Unit)
    suspend fun deleteDatabase()
    suspend fun exportAllPosts(): List<TagExportedPost>
    suspend fun importDataset(name: String, posts: List<TagExportedPost>): TagDatasetInfo
    suspend fun listImportedDatasets(): List<TagDatasetInfo>
    suspend fun deleteDataset(id: String)
    suspend fun tagAllLiked(
        isBlueskyMode: Boolean,
        bskyToken: String,
        bskyDid: String,
        e621Username: String,
        e621ApiKey: String,
        concurrency: Int = 1,
        onProgress: (TaggingProgress) -> Unit
    )
    suspend fun tagOnLike(item: MediaItem)
    fun search(query: String): List<String>
    fun browseAllTagged(limit: Int = 200): List<String>
    fun tagsForPost(postUri: String): List<String>
}

/** For platforms without the on-device tagger: everything is empty/no-op. */
object UnavailableTaggingService : TaggingService {
    override val isSupported: Boolean get() = false
    override fun currentCounts(): Pair<Int, Int> = 0 to 0
    override fun datasetSizeBytes(): Long = 0L
    override fun isModelReady(): Boolean = false
    override fun tagVocabulary(): List<String> = emptyList()
    override fun isTaggerLoaded(): Boolean = false
    override fun cancel() {}
    override suspend fun downloadModel(onState: (TaggerState) -> Unit) {
        onState(TaggerState.Failed("AI Tagging isn't available on this device yet"))
    }
    override suspend fun deleteDatabase() {}
    override suspend fun exportAllPosts(): List<TagExportedPost> = emptyList()
    override suspend fun importDataset(name: String, posts: List<TagExportedPost>): TagDatasetInfo =
        throw UnsupportedOperationException("AI Tagging isn't available on this device yet")
    override suspend fun listImportedDatasets(): List<TagDatasetInfo> = emptyList()
    override suspend fun deleteDataset(id: String) {}
    override suspend fun tagAllLiked(
        isBlueskyMode: Boolean, bskyToken: String, bskyDid: String, e621Username: String, e621ApiKey: String,
        concurrency: Int, onProgress: (TaggingProgress) -> Unit
    ) {
        onProgress(TaggingProgress(0, 0, 0, isRunning = false, isComplete = true, modelState = TaggerState.Failed("AI Tagging isn't available on this device yet")))
    }
    override suspend fun tagOnLike(item: MediaItem) {}
    override fun search(query: String): List<String> = emptyList()
    override fun browseAllTagged(limit: Int): List<String> = emptyList()
    override fun tagsForPost(postUri: String): List<String> = emptyList()
}
