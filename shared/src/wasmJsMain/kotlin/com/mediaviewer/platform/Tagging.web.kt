package com.mediaviewer.platform

import com.mediaviewer.model.MediaItem

/**
 * Web actual: no-op. The tag database, the ONNX tagger and the model
 * download are app-exclusive (Android only); shared UI must hide all
 * tagging surfaces when [PlatformImageTagger.isAvailable] is false.
 */
actual class TaggingController actual constructor() {

    actual fun currentCounts(): Pair<Int, Int> = 0 to 0
    actual fun datasetSizeBytes(): Long = 0L
    actual fun isModelReady(): Boolean = false
    actual fun tagVocabulary(): List<String> = emptyList()
    actual fun cancel() = Unit

    actual suspend fun deleteDatabase() = Unit
    actual suspend fun exportAllPosts(): List<ExportedPost> = emptyList()
    actual suspend fun importDataset(name: String, posts: List<ExportedPost>): DatasetInfo =
        DatasetInfo(id = "", name = name, importedAt = 0L, postCount = 0)
    actual suspend fun listImportedDatasets(): List<DatasetInfo> = emptyList()
    actual suspend fun deleteDataset(id: String) = Unit

    actual suspend fun tagAllLiked(
        isBlueskyMode: Boolean,
        bskyToken: String,
        bskyDid: String,
        e621Username: String,
        e621ApiKey: String,
        concurrency: Int,
        onProgress: (TaggingProgress) -> Unit,
    ) = Unit

    actual suspend fun tagOnLike(item: MediaItem) = Unit

    actual fun search(query: String): List<String> = emptyList()
    actual fun browseAllTagged(limit: Int): List<String> = emptyList()
    actual fun tagsForPost(postUri: String): List<String> = emptyList()
}
