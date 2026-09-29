package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mediaviewer.repository.WikipediaRepository
import com.mediaviewer.util.BlockedHosts

/**
 * A title card's cover: the Popfeed record's own image when it has one
 * (stored on AT Protocol), otherwise the title's Wikipedia lead image,
 * looked up once and then kept on-device. Null while nothing's available.
 */
@Composable
fun rememberTitleCover(
    existing: String?,
    title: String,
    category: String?,
    identifiersJson: String?,
    releaseDate: String?,
    creator: String? = null,
    imdbId: String? = null
): String? {
    val own = existing?.takeIf { BlockedHosts.isAllowedCoverUrl(it) }
    val key = remember(title, category, identifiersJson, releaseDate, imdbId) {
        WikipediaRepository.coverKey(title, category, identifiersJson, releaseDate, imdbId)
    }
    var fallback by remember(key) { mutableStateOf(WikipediaRepository.cachedCover(key)?.getOrNull()?.url) }
    LaunchedEffect(key, own == null) {
        if (own != null || title.isBlank()) return@LaunchedEffect
        if (WikipediaRepository.cachedCover(key) != null) return@LaunchedEffect
        fallback = runCatching {
            WikipediaRepository.fetchCover(title, category, identifiersJson, releaseDate, creator, imdbId)?.url
        }.getOrNull()
    }
    return own ?: fallback
}
