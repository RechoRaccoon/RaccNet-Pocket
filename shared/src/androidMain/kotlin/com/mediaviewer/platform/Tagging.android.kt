package com.mediaviewer.platform

import com.mediaviewer.model.MediaItem
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.repository.E621Repository
import com.mediaviewer.tagging.TaggingRepository

/**
 * Android actual: delegates every call to the ported [TaggingRepository]
 * (SQLite tag database + ONNX tagger). The repositories are constructed
 * here, in common code, so shared UI only ever sees this controller —
 * mirroring the legacy TaggingRepository.get() process singleton.
 */
actual class TaggingController actual constructor() {

    private val repo: TaggingRepository by lazy {
        TaggingRepository.get(BlueskyRepository(), E621Repository())
    }

    actual fun currentCounts(): Pair<Int, Int> = repo.currentCounts()
    actual fun datasetSizeBytes(): Long = repo.datasetSizeBytes()
    actual fun isModelReady(): Boolean = repo.isModelReady()
    actual fun tagVocabulary(): List<String> = repo.tagVocabulary()
    actual fun cancel() = repo.cancel()

    actual suspend fun deleteDatabase() = repo.deleteDatabase()
    actual suspend fun exportAllPosts(): List<ExportedPost> = repo.exportAllPosts()
    actual suspend fun importDataset(name: String, posts: List<ExportedPost>): DatasetInfo =
        repo.importDataset(name, posts)
    actual suspend fun listImportedDatasets(): List<DatasetInfo> = repo.listImportedDatasets()
    actual suspend fun deleteDataset(id: String) = repo.deleteDataset(id)

    actual suspend fun tagAllLiked(
        isBlueskyMode: Boolean,
        bskyToken: String,
        bskyDid: String,
        e621Username: String,
        e621ApiKey: String,
        concurrency: Int,
        onProgress: (TaggingProgress) -> Unit,
    ) = repo.tagAllLiked(
        isBlueskyMode, bskyToken, bskyDid, e621Username, e621ApiKey, concurrency, onProgress
    )

    actual suspend fun tagOnLike(item: MediaItem) = repo.tagOnLike(item)

    actual fun search(query: String): List<String> = repo.search(query)
    actual fun browseAllTagged(limit: Int): List<String> = repo.browseAllTagged(limit)
    actual fun tagsForPost(postUri: String): List<String> = repo.tagsForPost(postUri)
}
