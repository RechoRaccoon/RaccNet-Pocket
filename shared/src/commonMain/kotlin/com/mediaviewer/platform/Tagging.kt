package com.mediaviewer.platform

import com.mediaviewer.model.MediaItem

/**
 * Portable snapshots of the tag database's transfer types. In the legacy app
 * these were nested inside the Android-only TagDatabase; they move to common
 * so the [TaggingController] contract can live in shared code.
 */
data class ExportedPost(
    val postUri: String,
    val cid: String,
    val mediaUrl: String,
    val tags: List<Pair<String, Float>>,
)

data class DatasetInfo(
    val id: String,
    val name: String,
    val importedAt: Long,
    val postCount: Int,
)

/** Progress snapshot for a bulk tagging run (mirrors TaggingRepository.Progress). */
data class TaggingProgress(
    val scanned: Int,
    val tagged: Int,
    val datasetBytes: Long,
    val isRunning: Boolean,
    val isComplete: Boolean,
    val currentItem: MediaItem? = null,
)

/**
 * AI tagging subsystem. APP-EXCLUSIVE: the tag database, the ONNX tagger and
 * the model download only exist on Android.
 *
 * androidMain: delegates to the ported TaggingRepository (SQLite + ONNX).
 * wasmJsMain:  no-op — every function returns empty/false; shared UI must
 *              hide all tagging surfaces when PlatformImageTagger.isAvailable
 *              is false (see platform/ImageTagger.kt).
 *
 * The function list mirrors the legacy TaggingRepository's public API 1:1.
 */
expect class TaggingController() {

    fun currentCounts(): Pair<Int, Int>
    fun datasetSizeBytes(): Long
    fun isModelReady(): Boolean
    fun tagVocabulary(): List<String>
    fun cancel()

    suspend fun deleteDatabase()
    suspend fun exportAllPosts(): List<ExportedPost>
    suspend fun importDataset(name: String, posts: List<ExportedPost>): DatasetInfo
    suspend fun listImportedDatasets(): List<DatasetInfo>
    suspend fun deleteDataset(id: String)

    suspend fun tagAllLiked(
        isBlueskyMode: Boolean,
        bskyToken: String,
        bskyDid: String,
        e621Username: String,
        e621ApiKey: String,
        concurrency: Int = 1,
        onProgress: (TaggingProgress) -> Unit,
    )

    suspend fun tagOnLike(item: MediaItem)

    fun search(query: String): List<String>
    fun browseAllTagged(limit: Int = 200): List<String>
    fun tagsForPost(postUri: String): List<String>
}
