package com.mediaviewer.platform

import com.mediaviewer.model.MediaItem

/**
 * Web actual: no-op. The tag database, the ONNX tagger and the model
 * download are app-exclusive (Android only); shared UI must hide all
 * tagging surfaces when [PlatformImageTagger.isAvailable] is false.
 */
actual class TaggingController actual constructor() {

    fun currentCounts(): Pair<Int, Int> = 0 to 0
    fun datasetSizeBytes(): Long = 0L
    fun isModelReady(): Boolean = false
    fun tagVocabulary(): List<String> = emptyList()
    fun cancel() = Unit

    suspend fun deleteDatabase() = Unit
    suspend fun exportAllPosts(): List<ExportedPost> = emptyList()
    suspend fun importDataset(name: String, posts: List<ExportedPost>): DatasetInfo =
        DatasetInfo(id = "", name = name, importedAt = 0L, postCount = 0)
    suspend fun listImportedDatasets(): List<DatasetInfo> = emptyList()
    suspend fun deleteDataset(id: String) = Unit

    suspend fun tagAllLiked(
        isBlueskyMode: Boolean,
        bskyToken: String,
        bskyDid: String,
        e621Username: String,
        e621ApiKey: String,
        concurrency: Int,
        onProgress: (TaggingProgress) -> Unit,
    ) = Unit

    suspend fun tagOnLike(item: MediaItem) = Unit

    fun search(query: String): List<String> = emptyList()
    fun browseAllTagged(limit: Int): List<String> = emptyList()
    fun tagsForPost(postUri: String): List<String> = emptyList()
}
