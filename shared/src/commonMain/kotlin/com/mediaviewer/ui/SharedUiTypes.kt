package com.mediaviewer.ui

import com.mediaviewer.model.MediaItem
import com.mediaviewer.model.PopfeedBacklogItem
import com.mediaviewer.model.PopfeedReview
import com.mediaviewer.model.TitleSearchResult

// UI-level types the ViewModel also uses (moved from ComposePostScreen.kt /
// ProfileOverlay.kt so shared code can see them). Same package, same names.

enum class ComposeMode { SINGLE, THREAD, TEXTSHOT, VIDEO, REVIEW, BLOG }


/** One post's worth of content inside a [ComposeMode.THREAD] thread. */
data class ThreadPostDraft(
    val text: String,
    val images: List<com.mediaviewer.platform.PlatformUri> = emptyList(),
    val video: com.mediaviewer.platform.PlatformUri? = null
)


/** Everything the composer collected, handed to the caller on "Post". */
data class ComposePostDraft(
    val mode: ComposeMode,
    /** SINGLE: exactly one entry. THREAD: two or more, in posting order.
     *  REVIEW: exactly one entry, carrying just the typed review text (no
     *  images — see reviewTarget's own doc comment below for why). */
    val posts: List<ThreadPostDraft> = emptyList(),
    val videoUri: com.mediaviewer.platform.PlatformUri? = null,
    val videoThumbnailUri: com.mediaviewer.platform.PlatformUri? = null,
    val videoTitle: String = "",
    val videoDescription: String = "",
    val textshotText: String = "",
    /** Textshot mode: the post's own (regular Bluesky) text, separate from
     *  the text rendered into the image — for hashtags, a caption… */
    val textshotPostText: String = "",
    // Item 10: the title being reviewed, and the picked star rating on
    // Popfeed's own native 0–10 half-star scale (so 0 = unrated, 10 = full
    // 5 stars) — both only populated for ComposeMode.REVIEW. Image
    // attach/Textshot/Blog/thread are greyed out for the whole lifetime of
    // a review draft (see ComposePostScreen's reviewTarget param), so the
    // review itself is always exactly one plain-text post.
    val reviewTarget: TitleSearchResult? = null,
    val reviewRating: Int = 0,
    // Item 12: mirrors social.popfeed.feed.review's own "containsSpoilers"
    // boolean (see review.json) — set from the composer's "Mark as Spoiler"
    // toggle, only meaningful for ComposeMode.REVIEW.
    val reviewContainsSpoilers: Boolean = false,
    // Bluesky self-label values picked via the composer's "Labels" popup
    // (e.g. "sexual", "nudity", "porn", "graphic-media"). Empty = no labels.
    // Applied to every post the draft produces (all posts of a thread).
    val selfLabels: List<String> = emptyList(),
    // Item 12: ComposeMode.BLOG — the whole blog (title, description, rows).
    val blog: com.mediaviewer.model.BlogDraft? = null
)


enum class PostKindFilter { ALL, IMAGES, TEXT_POSTS, HORIZONTAL_VIDEOS, VERTICAL_VIDEOS }

fun PostKindFilter.matches(item: MediaItem) = when (this) {
    PostKindFilter.ALL              -> true
    PostKindFilter.IMAGES            -> !item.isVideo && !item.isTextOnly
    PostKindFilter.TEXT_POSTS        -> item.isTextOnly
    PostKindFilter.HORIZONTAL_VIDEOS -> item.isHorizontalVideo
    PostKindFilter.VERTICAL_VIDEOS   -> item.isVerticalVideo
}


enum class ReviewKindFilter { ALL, MOVIES, TV, GAMES, MUSIC, BOOKS }
fun ReviewKindFilter.label() = when (this) {
    ReviewKindFilter.ALL -> "All"; ReviewKindFilter.MOVIES -> "Movies"; ReviewKindFilter.TV -> "TV"
    ReviewKindFilter.GAMES -> "Games"; ReviewKindFilter.MUSIC -> "Music"; ReviewKindFilter.BOOKS -> "Books"
}

/** Buckets a raw creativeWorkType string (e.g. "movie", "tv_show",
 *  "video_game", "album") into one of the four sub-filter categories.
 *  Keyword-contains matching, same defensive style as the rest of this
 *  record's parsing (see BlueskyRepository.getPopfeedBacklog) — Popfeed's
 *  exact set of type strings isn't fully documented, so this is deliberately
 *  loose rather than an exact-match enum. Null/unrecognized categories only
 *  show up under "All", never hidden entirely. */
fun categoryBucket(raw: String?): ReviewKindFilter? {
    val v = raw?.lowercase() ?: return null
    return when {
        v.contains("movie") || v.contains("film") -> ReviewKindFilter.MOVIES
        v.contains("tv") || v.contains("show") || v.contains("series") || v.contains("episode") -> ReviewKindFilter.TV
        v.contains("game") -> ReviewKindFilter.GAMES
        v.contains("album") || v.contains("music") || v.contains("song") || v.contains("track") -> ReviewKindFilter.MUSIC
        // Titles feature: books, added alongside the Titles tab per spec
        // ("which btw need the 'Books' options at the end") — keyed off the
        // same loose keyword-contains matching as every other bucket here.
        v.contains("book") || v.contains("novel") || v.contains("comic") || v.contains("literature") -> ReviewKindFilter.BOOKS
        else -> null
    }
}
fun ReviewKindFilter.matchesReview(review: PopfeedReview) = this == ReviewKindFilter.ALL || categoryBucket(review.mediaCategory) == this
fun ReviewKindFilter.matchesBacklog(item: PopfeedBacklogItem) = this == ReviewKindFilter.ALL || categoryBucket(item.mediaCategory) == this
// Titles feature: same bucketing, applied to a search result instead of a
// Popfeed backlog/review record.
fun ReviewKindFilter.matchesTitle(result: com.mediaviewer.model.TitleSearchResult) =
    this == ReviewKindFilter.ALL || categoryBucket(result.mediaCategory) == this
