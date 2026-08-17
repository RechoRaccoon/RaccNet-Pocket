package com.mediaviewer.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.LeafletBlock
import com.mediaviewer.model.LeafletBlog
import com.mediaviewer.model.LeafletTextSpan
import com.mediaviewer.model.MediaItem
import com.mediaviewer.model.PopfeedBacklogItem
import com.mediaviewer.model.PopfeedReview
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.ui.theme.OledBlack
import com.mediaviewer.viewmodel.MainViewModel
import kotlinx.coroutines.launch

private fun MainViewModel.ProfileTab.label(): String = when (this) {
    MainViewModel.ProfileTab.MEDIA      -> "Media"
    MainViewModel.ProfileTab.TEXT_POSTS -> "Text Posts"
    MainViewModel.ProfileTab.REPOSTS    -> "Reposts"
    MainViewModel.ProfileTab.LIKES      -> "Likes"
    MainViewModel.ProfileTab.BLOGS      -> "Blogs"
    MainViewModel.ProfileTab.REVIEWS    -> "Reviews"
    MainViewModel.ProfileTab.BACKLOG    -> "Backlog"
    MainViewModel.ProfileTab.VODS       -> "Vods"
}

// ─── Profile tabs sub-filter row ────────────────────────────────────────────
// A second, half-height row of pills under the main tab row (Popfeed does
// this too — a type filter directly under the tab strip). Only shown for
// tabs where it means something: Media/Reposts/Likes filter by
// image-vs-video, Reviews/Backlog filter by the media's own category.

private enum class MediaKindFilter { ALL, IMAGES, VIDEOS }
private fun MediaKindFilter.label() = when (this) {
    MediaKindFilter.ALL -> "All"; MediaKindFilter.IMAGES -> "Images"; MediaKindFilter.VIDEOS -> "Videos"
}
private fun MediaKindFilter.matches(item: MediaItem) = when (this) {
    MediaKindFilter.ALL -> true
    MediaKindFilter.IMAGES -> !item.isVideo
    MediaKindFilter.VIDEOS -> item.isVideo
}

private enum class ReviewKindFilter { ALL, MOVIES, TV, GAMES, MUSIC }
private fun ReviewKindFilter.label() = when (this) {
    ReviewKindFilter.ALL -> "All"; ReviewKindFilter.MOVIES -> "Movies"; ReviewKindFilter.TV -> "TV"
    ReviewKindFilter.GAMES -> "Games"; ReviewKindFilter.MUSIC -> "Music"
}

/** Buckets a raw creativeWorkType string (e.g. "movie", "tv_show",
 *  "video_game", "album") into one of the four sub-filter categories.
 *  Keyword-contains matching, same defensive style as the rest of this
 *  record's parsing (see BlueskyRepository.getPopfeedBacklog) — Popfeed's
 *  exact set of type strings isn't fully documented, so this is deliberately
 *  loose rather than an exact-match enum. Null/unrecognized categories only
 *  show up under "All", never hidden entirely. */
private fun categoryBucket(raw: String?): ReviewKindFilter? {
    val v = raw?.lowercase() ?: return null
    return when {
        v.contains("movie") || v.contains("film") -> ReviewKindFilter.MOVIES
        v.contains("tv") || v.contains("show") || v.contains("series") || v.contains("episode") -> ReviewKindFilter.TV
        v.contains("game") -> ReviewKindFilter.GAMES
        v.contains("album") || v.contains("music") || v.contains("song") || v.contains("track") -> ReviewKindFilter.MUSIC
        else -> null
    }
}
private fun ReviewKindFilter.matchesReview(review: PopfeedReview) = this == ReviewKindFilter.ALL || categoryBucket(review.mediaCategory) == this
private fun ReviewKindFilter.matchesBacklog(item: PopfeedBacklogItem) = this == ReviewKindFilter.ALL || categoryBucket(item.mediaCategory) == this

@Composable
private fun <T> ProfileSubFilterRow(
    options: List<T>, selected: T, liquidGlass: Boolean, tint: Color, labelOf: (T) -> String, onSelect: (T) -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier
                        .then(
                            if (liquidGlass) Modifier.glassPanel(true, tint = if (isSelected) tint else tint.copy(alpha = 0.4f), shape = shape)
                            else Modifier.clip(shape).background(if (isSelected) Color.White.copy(0.15f) else Color.White.copy(0.06f))
                        )
                        .clickable { onSelect(option) }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(labelOf(option), color = if (isSelected) Color.White else DimGray, fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        // Item (this session): "Subscribe" button — pinned to the row's far
        // right, outside the horizontally-scrolling chip list (so it never
        // scrolls out of view alongside All/Movies/TV/etc, and never
        // overlaps them either).
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Local-only "Subscribe" toggle — adds/removes this profile from the Hub's
 *  Reviews or Blogs source list (see MainViewModel.toggleReviewSubscription/
 *  toggleBlogSubscription). A filled bookmark once subscribed, outline
 *  otherwise — same compact pill treatment as the filter chips next to it. */
@Composable
private fun SubscribeBubble(subscribed: Boolean, liquidGlass: Boolean, tint: Color, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = if (subscribed) tint else tint.copy(alpha = 0.4f), shape = shape)
                else Modifier.clip(shape).background(if (subscribed) Color.White.copy(0.15f) else Color.White.copy(0.06f))
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            if (subscribed) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = if (subscribed) "Subscribed" else "Subscribe",
            tint = if (subscribed) Color.White else DimGray, modifier = Modifier.size(13.dp)
        )
        Text(if (subscribed) "Subscribed" else "Subscribe", color = if (subscribed) Color.White else DimGray,
            fontSize = 11.sp, fontWeight = if (subscribed) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/**
 * Profile Overhaul — a full-screen overlay page for viewing an account's
 * profile. Rendered above everything else (see MainActivity) so closing it
 * just removes this composable and drops the user back exactly where they
 * were underneath.
 *
 * The whole page — banner/bio/counts, tabs, and results — is one continuous
 * scroll (a single [LazyColumn]) rather than a fixed header with an
 * independently-scrolling results section below it. Once the user scrolls
 * past the bottom of the tabs row, a "scroll to top" glass bubble appears
 * under the status bar to jump back up quickly.
 */
@Composable
fun ProfileOverlay(
    state: MainViewModel.ProfileOverlayState,
    liquidGlass: Boolean,
    reducedAnimations: Boolean,
    // The logged-in user's own did — used only to detect "this is my own
    // profile" so the banner shows a placeholder "Edit" button instead of
    // Follow/Following (following yourself doesn't make sense).
    selfDid: String,
    onClose: () -> Unit,
    onSelectTab: (MainViewModel.ProfileTab) -> Unit,
    onLoadMore: () -> Unit,
    onToggleFollow: () -> Unit,
    onTapItem: (Int) -> Unit,
    onOpenBlog: (LeafletBlog) -> Unit,
    onCloseBlog: () -> Unit,
    onOpenReview: (PopfeedReview) -> Unit,
    onCloseReview: () -> Unit,
    // Pinch navigation: the mirror of the post pager's pinch-in. Only takes
    // effect (see pinchOutFromProfile() in the ViewModel) when this profile
    // is the one currently hidden behind a post — hiding it again is what
    // reveals that post.
    onPinchOut: () -> Unit,
    // Bug fix: captures the current scroll position into the ViewModel right
    // before this profile is hidden (see ProfileOverlayState.scrollIndex/
    // scrollOffset doc comment) so it can be force-restored on the way back.
    onSaveScroll: (Int, Int) -> Unit,
    // Item (this session): profile-level "Subscribe" toggle for the Hub's
    // Reviews/Blogs sections — see PreferencesManager.SUBSCRIBED_REVIEW_DIDS/
    // SUBSCRIBED_BLOG_DIDS. Two independent lists: subscribing to someone's
    // Reviews doesn't imply their Blogs, or vice versa.
    isReviewSubscribed: Boolean = false,
    isBlogSubscribed: Boolean = false,
    onToggleReviewSubscribe: () -> Unit = {},
    onToggleBlogSubscribe: () -> Unit = {}
) {
    val author  = state.author
    val profile = state.profile
    val bannerUrl = profile?.bannerUrl
    val avatarUrl = author.avatarUrl

    val bannerColor = rememberDominantColor(bannerUrl ?: avatarUrl ?: "")
    val avatarColor = rememberDominantColor(avatarUrl ?: "")
    val blended = remember(bannerColor, avatarColor) {
        Color(
            red = (bannerColor.red + avatarColor.red) / 2f,
            green = (bannerColor.green + avatarColor.green) / 2f,
            blue = (bannerColor.blue + avatarColor.blue) / 2f,
            alpha = 1f
        )
    }

    BackHandler(onClose)

    // Bug fix: seed from the last explicitly-saved position too (not just
    // relied on the LazyListState surviving the hide/show cycle on its
    // own — see the force-restore LaunchedEffect below and the doc comment
    // on ProfileOverlayState.scrollIndex/scrollOffset).
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.scrollIndex,
        initialFirstVisibleItemScrollOffset = state.scrollOffset
    )
    val coroutineScope = rememberCoroutineScope()

    // Bug fix: force-restore the saved scroll position the moment this
    // profile is revealed again (hidden flips false->true->false), instead
    // of trusting that collapsing/expanding the LazyColumn via the 0dp-size
    // trick in MainActivity kept the LazyListState's position intact on its
    // own — in practice it doesn't reliably, which was the cause of the
    // "jumps to the bottom of the results" bug. Also covers the very first
    // open (hidden starts false), which is a harmless scrollToItem(0, 0).
    LaunchedEffect(state.hidden) {
        if (!state.hidden) {
            listState.scrollToItem(state.scrollIndex, state.scrollOffset)
        }
    }

    // Item 0 = header, item 1 = tabs+divider. Once both have fully scrolled
    // past the top of the viewport (i.e. we're rendering item index 2+),
    // we've passed the bottom of the tabs — show the "scroll to top" bubble.
    val pastTabs by remember { derivedStateOf { listState.firstVisibleItemIndex >= 2 } }

    // Loading screen: "content ready" means both the profile fetch (bio,
    // counts, banner/avatar) and the initially-selected tab's first page are
    // done — either succeeded or failed, loadProfileTab sets `loaded = true`
    // either way, so this can't get stuck if a fetch errors out. Gated by
    // hasShownContentOnce so this only covers the very first open, not every
    // subsequent tab switch (those already have their own small in-line
    // spinner — a full black screen every tab switch would be jarring).
    val contentReady = !state.loadingProfile && state.tabStates[state.selectedTab]?.loaded == true
    var hasShownContentOnce by remember(author.did) { mutableStateOf(false) }
    LaunchedEffect(contentReady) { if (contentReady) hasShownContentOnce = true }

    // Profile tabs sub-filter row state — purely local/display-only (not
    // round-tripped through the ViewModel, since it never needs to survive
    // beyond this composition), reset whenever the profile or the selected
    // tab changes so switching tabs/profiles doesn't carry over a stale
    // filter selection from a completely different tab's category set.
    var mediaKindFilter by remember(author.did, state.selectedTab) { mutableStateOf(MediaKindFilter.ALL) }
    var reviewKindFilter by remember(author.did, state.selectedTab) { mutableStateOf(ReviewKindFilter.ALL) }

    Box(
        Modifier
            .fillMaxSize()
            .background(postBackgroundBrush(blended))
            // Pinch-out detection: watched passively (PointerEventPass.Initial,
            // never consumed) purely to peek at 2-finger spread without
            // interfering with the LazyColumn's own single-finger scroll
            // handling below. One-shot per gesture, same "compare against the
            // spread when the 2nd finger first touched down" approach as the
            // pager's existing pinch gestures in MainFeedScreen.
            .pointerInput(Unit) {
                awaitEachGesture {
                    var startDist = -1f
                    var fired = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size < 2) {
                            if (pressed.isEmpty()) break
                            startDist = -1f; fired = false
                            continue
                        }
                        val dist = (pressed[0].position - pressed[1].position).getDistance()
                        if (startDist < 0f) {
                            startDist = dist
                        } else if (!fired && dist / startDist > 1.4f) {
                            fired = true
                            // Bug fix: capture scroll position before hiding.
                            onSaveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                            onPinchOut()
                        }
                    }
                }
            }
    ) {
        LazyColumn(
            state = listState,
            // Bug fix (per feedback): the last item in a tab (e.g. the
            // bottom-most Blogs card) used to end flush with the very
            // bottom of the screen, so on gesture-nav devices it sat
            // partly hidden under the gesture bar with nothing but its own
            // padding between them. A little reserved space at the end of
            // the scroll content — the actual navigation-bar/gesture-bar
            // inset, plus a small fixed buffer so it's not flush even
            // against that — keeps the last item fully visible and clear
            // of it once scrolled all the way down.
            contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
        ) {
            item(key = "profile_header") {
                ProfileHeaderSection(
                    author = author,
                    profile = profile,
                    loadingProfile = state.loadingProfile,
                    liquidGlass = liquidGlass,
                    bannerColor = bannerColor,
                    avatarColor = avatarColor,
                    isOwnProfile = selfDid.isNotBlank() && author.did == selfDid,
                    linkColor = blended,
                    onToggleFollow = onToggleFollow,
                    onClose = onClose
                )
            }

            item(key = "profile_tabs") {
                Column {
                    // Compact divider above the tab row (per feedback) — the
                    // existing divider below the tabs stays where it was;
                    // this just adds the matching one above so the tab strip
                    // reads as its own bounded section rather than floating
                    // directly under the header.
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                    ProfileTabsRow(
                        tabs = MainViewModel.ProfileTab.entries.filter { it in state.availableTabs },
                        selected = state.selectedTab,
                        liquidGlass = liquidGlass,
                        tint = blended,
                        onSelect = onSelectTab
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                    // Sub-filter row (per feedback) — a second, half-height
                    // row of pills directly under the tab strip, same
                    // treatment Popfeed itself uses. Only rendered for tabs
                    // where a type filter means something; other tabs (Text
                    // Posts, Blogs, Vods) show nothing extra here.
                    when (state.selectedTab) {
                        MainViewModel.ProfileTab.MEDIA, MainViewModel.ProfileTab.REPOSTS, MainViewModel.ProfileTab.LIKES -> {
                            ProfileSubFilterRow(
                                options = MediaKindFilter.entries.toList(), selected = mediaKindFilter,
                                liquidGlass = liquidGlass, tint = blended, labelOf = { it.label() },
                                onSelect = { mediaKindFilter = it }
                            )
                        }
                        MainViewModel.ProfileTab.REVIEWS -> {
                            ProfileSubFilterRow(
                                options = ReviewKindFilter.entries.toList(), selected = reviewKindFilter,
                                liquidGlass = liquidGlass, tint = blended, labelOf = { it.label() },
                                onSelect = { reviewKindFilter = it },
                                trailing = { SubscribeBubble(isReviewSubscribed, liquidGlass, blended, onToggleReviewSubscribe) }
                            )
                        }
                        MainViewModel.ProfileTab.BACKLOG -> {
                            ProfileSubFilterRow(
                                options = ReviewKindFilter.entries.toList(), selected = reviewKindFilter,
                                liquidGlass = liquidGlass, tint = blended, labelOf = { it.label() },
                                onSelect = { reviewKindFilter = it }
                            )
                        }
                        // Item (this session): Blogs has no type filter chips
                        // (unlike Reviews/Backlog), so this row exists purely
                        // to host the Subscribe button in the same place.
                        MainViewModel.ProfileTab.BLOGS -> {
                            ProfileSubFilterRow(
                                options = emptyList<Unit>(), selected = Unit,
                                liquidGlass = liquidGlass, tint = blended, labelOf = { "" }, onSelect = {},
                                trailing = { SubscribeBubble(isBlogSubscribed, liquidGlass, blended, onToggleBlogSubscribe) }
                            )
                        }
                        else -> {}
                    }
                }
            }

            profileResultsContent(
                state = state,
                liquidGlass = liquidGlass,
                mediaKindFilter = mediaKindFilter,
                reviewKindFilter = reviewKindFilter,
                profileTint = blended,
                onLoadMore = onLoadMore,
                // Bug fix: capture scroll position before this profile gets
                // hidden behind the post that's about to open — see
                // onSaveScroll's doc comment above.
                onTapItem = { index ->
                    onSaveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                    onTapItem(index)
                },
                onOpenBlog = onOpenBlog,
                onOpenReview = onOpenReview
            )
        }

        if (!state.loadingProfile && profile == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Couldn't load this profile", color = DimGray, fontSize = 13.sp)
            }
        }

        // ── Loading screen — covers the staggered pop-in of the banner,
        // buttons, and first tab's results while they're still loading, then
        // fades away once everything's ready. Consumes touches so nothing
        // underneath is tappable while it's up (a plain .background() alone
        // isn't hit-testable in Compose and would otherwise let taps pass
        // straight through to whatever's rendered beneath it).
        AnimatedVisibility(
            visible = !hasShownContentOnce,
            enter = EnterTransition.None,
            exit = fadeOut(tween(280))
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black)
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(36.dp))
            }
        }

        // ── Scroll-to-top bubble — appears once scrolled past the tabs ──
        AnimatedVisibility(
            visible = pastTabs,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 8.dp),
            enter = fadeIn(tween(if (reducedAnimations) 0 else 180)) + scaleIn(initialScale = 0.8f),
            exit = fadeOut(tween(if (reducedAnimations) 0 else 180)) + scaleOut(targetScale = 0.8f)
        ) {
            ScrollToTopBubble(liquidGlass = liquidGlass, tint = blended) {
                coroutineScope.launch {
                    if (reducedAnimations) listState.scrollToItem(0) else listState.animateScrollToItem(0)
                }
            }
        }

        state.openBlog?.let { blog ->
            BlogDetailOverlay(blog = blog, author = author, liquidGlass = liquidGlass, tint = blended, onClose = onCloseBlog)
        }
        state.openReview?.let { review ->
            ReviewDetailOverlay(review = review, author = author, liquidGlass = liquidGlass, onClose = onCloseReview)
        }
    }
}

@Composable
private fun ScrollToTopBubble(liquidGlass: Boolean, tint: Color, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        Modifier
            .size(38.dp)
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                else Modifier.clip(shape).background(Color.Black.copy(0.6f))
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.ArrowUpward, contentDescription = "Scroll to top", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** Intercepts the system back gesture/button while the overlay is up. */
@Composable
private fun BackHandler(onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
}

// ─── Header ──────────────────────────────────────────────────────────────────

@Composable
private fun ProfileHeaderSection(
    author: AuthorInfo,
    profile: com.mediaviewer.model.ProfileData?,
    loadingProfile: Boolean,
    liquidGlass: Boolean,
    bannerColor: Color,
    avatarColor: Color,
    isOwnProfile: Boolean,
    // Same blended banner/avatar color used everywhere else in the profile
    // (tabs, bubbles) — links in the bio use it too, per spec.
    linkColor: Color,
    onToggleFollow: () -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        // ── Banner ──
        // Big Update #4 (extended to profiles): a shared layer re-recorded
        // every frame with the banner's actual rendered pixels (photo + glass
        // wash), the exact same mechanism the main feed's posts use — so the
        // close bubble, follow/edit button, and name/username pills sitting
        // over it sample a live, real-time crop instead of a flat tint. The
        // rest of the profile (tabs, bubbles further down) sit over a plain
        // background gradient rather than any real media, so they keep the
        // still-tint glass — a live blur of a flat gradient would look
        // identical anyway, and it isn't worth the extra recorded layers.
        val backdropLayer = rememberGraphicsLayer()
        var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
        val bannerBackdrop = remember(liquidGlass, backdropLayer) {
            if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
        }
        Box(Modifier.fillMaxWidth().height(146.dp)) {
            // Only the raw photo + wash gets recorded into the shared backdrop
            // layer — ProfileBannerOverlayLayout (a SubcomposeLayout) is kept
            // as a separate sibling below rather than nested inside this
            // recording box, so it's only ever drawn once per frame (its own
            // normal draw pass) instead of twice (once via backdropLayer.record
            // {drawContent()}, once via the real drawContent() right after) —
            // SubcomposeLayout is a much heavier, stateful layout primitive
            // than anything the main feed's equivalent backdrop ever wraps,
            // and double-drawing it within one frame isn't a safe assumption
            // to carry over from there.
            Box(
                Modifier.matchParentSize()
                    .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                    .then(
                        if (liquidGlass) Modifier.drawWithContent {
                            backdropLayer.record { this@drawWithContent.drawContent() }
                            drawContent()
                        } else Modifier
                    )
            ) {
                if (profile?.bannerUrl != null) {
                    AsyncImage(
                        model = profile.bannerUrl, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()
                    )
                } else {
                    Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(bannerColor.copy(0.55f), Color.Black))))
                }
                // Glass wash so the banner reads as "under glass" rather than a bare photo.
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(0.10f), Color.Black.copy(0.45f)))))
            }

            // Everything below is positioned by a single custom layout so the
            // pieces can reference each other's *actual* measured sizes:
            //  - the close bubble (top-left) needs to line up with wherever
            //    the follow button (top-right) actually ends up
            //  - the avatar's height needs to exactly span from the top of
            //    the display-name pill down to the bottom of the username
            //    pill, whatever those pills' real heights turn out to be.
            ProfileBannerOverlayLayout(
                author = author,
                liquidGlass = liquidGlass,
                bannerColor = bannerColor,
                avatarColor = avatarColor,
                isOwnProfile = isOwnProfile,
                backdrop = bannerBackdrop,
                onToggleFollow = onToggleFollow,
                onClose = onClose
            )
        }

        // ── Bio ──
        val bio = profile?.description.orEmpty()
        if (bio.isNotBlank()) {
            LinkableBioText(
                text = bio, linkColor = linkColor,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            )
        } else if (loadingProfile) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterStart) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 1.5.dp)
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }

        // ── Counts ──
        if (profile != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start)
            ) {
                CountStat(profile.postsCount, "Posts")
                CountStat(profile.followersCount, "Followers")
                CountStat(profile.followsCount, "Following")
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** Renders a bio with any http(s)/www links styled in [linkColor] and made
 *  tappable — opened via the system's normal URL handler (whatever browser
 *  the person has set as default), the same way any other Android app would
 *  open a link. Detection is regex-based since Bluesky's profile records
 *  don't carry rich-text facets for the bio the way posts do for their text. */
@Composable
private fun LinkableBioText(text: String, linkColor: Color, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(text, linkColor) {
        buildAnnotatedString {
            append(text)
            for (match in bioLinkRegex.findAll(text)) {
                // Trim common trailing punctuation a link often gets caught up
                // in mid-sentence ("check out guns.lol/foo." shouldn't include
                // the period), without touching the plain-text append above.
                var end = match.range.last + 1
                while (end > match.range.first && text[end - 1] in ".,;:!?)]}\"'") end--
                if (end <= match.range.first) continue
                val raw = text.substring(match.range.first, end)
                val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), match.range.first, end)
                addStringAnnotation(tag = "URL", annotation = url, start = match.range.first, end = end)
            }
        }
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 18.sp,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures { tapPos ->
                val lr = layoutResult ?: return@detectTapGestures
                val offset = lr.getOffsetForPosition(tapPos)
                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { ann ->
                    runCatching { uriHandler.openUri(ann.item) }
                }
            }
        }
    )
}

private val bioLinkRegex = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)

/**
 * Positions the banner's overlay pieces:
 *  - a close ("x") glass bubble, top-left, vertically aligned with the
 *    follow button
 *  - the follow button, display-name pill, and username pill stacked and
 *    right-aligned, centered vertically as a group within the banner
 *  - the avatar, left-aligned, whose height exactly spans from the top of
 *    the display-name pill to the bottom of the username pill
 *
 * A [SubcomposeLayout] is used (rather than nested Boxes/Rows) because the
 * avatar's size and the close bubble's position both depend on the *actual
 * measured* sizes of the name pills and follow button — sizes that vary with
 * text length/font scale and can't be hard-coded.
 */
@Composable
private fun ProfileBannerOverlayLayout(
    author: AuthorInfo,
    liquidGlass: Boolean,
    bannerColor: Color,
    avatarColor: Color,
    isOwnProfile: Boolean,
    // Big Update #4 (extended to profiles): live backdrop of the banner photo
    // itself, re-recorded every frame by ProfileHeaderSection — see the
    // comment there. Every glass piece in this layout sits directly over
    // that photo, so they all sample it the same way the main feed's
    // AuthorRow/FollowButton sample a post's media.
    backdrop: GlassBackdrop?,
    onToggleFollow: () -> Unit,
    onClose: () -> Unit
) {
    val inset = 16.dp
    val gap = 8.dp
    val nameGap = 6.dp

    androidx.compose.ui.layout.SubcomposeLayout(
        Modifier.fillMaxSize().padding(inset)
    ) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gapPx = gap.roundToPx()
        val nameGapPx = nameGap.roundToPx()

        // Name pills, measured first: their combined height dictates the
        // avatar's height.
        val displayNamePlaceable = subcompose("displayName") {
            ProfileGlassPill(text = author.displayName, liquidGlass = liquidGlass, tint = bannerColor, fontSize = 15.sp, bold = true, backdrop = backdrop)
        }.first().measure(loose)
        val usernamePlaceable = subcompose("username") {
            ProfileGlassPill(text = "@${author.handle}", liquidGlass = liquidGlass, tint = bannerColor.copy(alpha = 0.8f), fontSize = 12.sp, bold = false, backdrop = backdrop)
        }.first().measure(loose)
        val namesHeight = displayNamePlaceable.height + nameGapPx + usernamePlaceable.height

        val followPlaceable = subcompose("follow") {
            if (isOwnProfile) {
                EditProfileButton(liquidGlass = liquidGlass, tint = bannerColor, backdrop = backdrop)
            } else {
                FollowButton(isFollowing = author.isFollowing, liquidGlass = liquidGlass, tint = bannerColor, onClick = onToggleFollow, backdrop = backdrop)
            }
        }.first().measure(loose)

        val closePlaceable = subcompose("close") {
            CloseGlassBubble(liquidGlass = liquidGlass, tint = bannerColor, onClick = onClose, backdrop = backdrop)
        }.first().measure(loose)

        // Avatar's height exactly spans display-name-top → username-bottom.
        val avatarSizeDp = with(this) { namesHeight.toDp() }
        val avatarPlaceable = subcompose("avatar") {
            ProfileAvatarGlass(url = author.avatarUrl, size = avatarSizeDp, liquidGlass = liquidGlass, tint = avatarColor, backdrop = backdrop)
        }.first().measure(loose)

        val stackHeight = followPlaceable.height + gapPx + namesHeight
        val width = constraints.maxWidth
        val height = constraints.maxHeight

        layout(width, height) {
            // Follow button + name pills, centered vertically as one group, far right.
            val stackY = ((height - stackHeight) / 2).coerceAtLeast(0)
            followPlaceable.placeRelative(width - followPlaceable.width, stackY)
            val namesY = stackY + followPlaceable.height + gapPx
            displayNamePlaceable.placeRelative(width - displayNamePlaceable.width, namesY)
            usernamePlaceable.placeRelative(
                width - usernamePlaceable.width,
                namesY + displayNamePlaceable.height + nameGapPx
            )

            // Close bubble — top-left, vertically aligned with the follow button.
            closePlaceable.placeRelative(0, stackY)

            // Avatar — left-aligned, top matching the display-name pill's top.
            avatarPlaceable.placeRelative(0, namesY)
        }
    }
}

@Composable
private fun CountStat(count: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(formatCount(count), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = DimGray, fontSize = 12.sp)
    }
}

private fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000     -> "%.1fK".format(n / 1_000f)
    else           -> n.toString()
}

@Composable
private fun CloseGlassBubble(liquidGlass: Boolean, tint: Color, onClick: () -> Unit, backdrop: GlassBackdrop? = null) {
    val shape = CircleShape
    if (liquidGlass) {
        LiquidGlassSurface(
            modifier = Modifier.size(30.dp).clickable(onClick = onClick),
            shape = shape, tint = tint, backdrop = backdrop
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, contentDescription = "Close profile", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    } else {
        Box(
            Modifier.size(30.dp).clip(shape).background(Color.White.copy(0.14f)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close profile", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

// ─── Small building blocks ──────────────────────────────────────────────────

@Composable
private fun ProfileAvatarGlass(url: String?, size: Dp, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop? = null) {
    val shape = CircleShape
    @Composable
    fun AvatarImage() {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
        } else {
            Box(Modifier.fillMaxSize().clip(shape).background(Color.White.copy(0.15f)))
        }
    }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = Modifier.size(size), shape = shape, tint = tint, backdrop = backdrop) {
            Box(Modifier.fillMaxSize().padding(5.dp)) { AvatarImage() } // thick rim
        }
    } else {
        Box(Modifier.size(size).clip(shape).background(Color.Black.copy(0.5f)).padding(5.dp)) { AvatarImage() }
    }
}

@Composable
private fun ProfileGlassPill(
    text: String, liquidGlass: Boolean, tint: Color, fontSize: androidx.compose.ui.unit.TextUnit, bold: Boolean,
    modifier: Modifier = Modifier, backdrop: GlassBackdrop? = null,
    // Bug fix (per feedback): the Hub's blog title bubble used this same
    // fixed 12dp/6dp padding as the profile's full-size version even
    // though it's passed a much smaller fontSize — combined with Text's
    // default line-height (which reserves extra vertical space above/below
    // the glyphs based on the font's own metrics, not the padding here),
    // the pill ended up looking roughly twice as tall as the text inside
    // it actually needed. `compact` tightens both the padding and the
    // text's line-height together so the pill hugs its (smaller) text the
    // same way the profile's full-size pill hugs its (larger) text.
    compact: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    val padH = if (compact) 8.dp else 12.dp
    val padV = if (compact) 3.dp else 6.dp
    @Composable
    fun Label() {
        Text(text, color = Color.White, fontSize = fontSize, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            lineHeight = if (compact) fontSize else androidx.compose.ui.unit.TextUnit.Unspecified,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = modifier, shape = shape, tint = tint, backdrop = backdrop) {
            Box(Modifier.padding(horizontal = padH, vertical = padV), contentAlignment = Alignment.Center) { Label() }
        }
    } else {
        Box(
            modifier.clip(shape).background(Color.Black.copy(0.55f)).padding(horizontal = padH, vertical = padV),
            contentAlignment = Alignment.Center
        ) { Label() }
    }
}

@Composable
private fun ProfileTabsRow(
    tabs: List<MainViewModel.ProfileTab>, selected: MainViewModel.ProfileTab, liquidGlass: Boolean, tint: Color,
    onSelect: (MainViewModel.ProfileTab) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            val shape = RoundedCornerShape(20.dp)
            Box(
                Modifier
                    .then(
                        if (liquidGlass) Modifier.glassPanel(true, tint = if (isSelected) tint else tint.copy(alpha = 0.4f), shape = shape)
                        else Modifier.clip(shape).background(if (isSelected) Color.White.copy(0.15f) else Color.White.copy(0.06f))
                    )
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(tab.label(), color = if (isSelected) Color.White else DimGray, fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ─── Results ─────────────────────────────────────────────────────────────────

/**
 * Adds the selected tab's results directly into the profile's single outer
 * [LazyColumn] (see [ProfileOverlay]) — grid tabs are laid out as one item
 * per row-of-3 so the whole page, media grid included, is one continuous
 * lazily-loaded scroll instead of a nested independently-scrolling grid.
 */
private fun LazyListScope.profileResultsContent(
    state: MainViewModel.ProfileOverlayState,
    liquidGlass: Boolean,
    profileTint: Color,
    mediaKindFilter: MediaKindFilter,
    reviewKindFilter: ReviewKindFilter,
    onLoadMore: () -> Unit,
    onTapItem: (Int) -> Unit,
    onOpenBlog: (LeafletBlog) -> Unit,
    onOpenReview: (PopfeedReview) -> Unit
) {
    val tabState = state.tabStates[state.selectedTab]

    when (state.selectedTab) {
        MainViewModel.ProfileTab.MEDIA, MainViewModel.ProfileTab.REPOSTS, MainViewModel.ProfileTab.LIKES -> {
            val allItems = tabState?.items ?: emptyList()
            // See profileMediaGridRows' own doc comment on `filter` for why
            // this passes the full, unfiltered list through plus a
            // predicate, rather than pre-filtering the list itself —
            // onTapItem's index has to stay valid against the original list.
            profileMediaGridRows(
                items = allItems,
                loading = tabState?.loading == true,
                onTapItem = onTapItem, onLoadMore = onLoadMore,
                filter = { mediaKindFilter.matches(it) }
            )
        }
        MainViewModel.ProfileTab.TEXT_POSTS -> {
            val items = tabState?.items ?: emptyList()
            val loading = tabState?.loading == true
            itemsIndexed(items, key = { i, item -> "textpost_${item.id}_$i" }) { index, item ->
                if (!loading && items.isNotEmpty() && index >= items.size - 4) {
                    LaunchedEffect(index, items.size) { onLoadMore() }
                }
                TextPostBubble(item = item, liquidGlass = liquidGlass, tint = profileTint, onOpen = { onTapItem(index) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
            // Only show a "load more" spinner here when there are already
            // items on screen — on the very first load (items empty) the
            // shared "results_loading" spinner below already covers it, and
            // showing both at once was rendering two spinners stacked on
            // top of each other.
            if (loading && items.isNotEmpty()) {
                item(key = "textposts_loading_more") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 1.5.dp)
                    }
                }
            }
        }
        MainViewModel.ProfileTab.BLOGS -> {
            items(tabState?.blogs ?: emptyList(), key = { "blog_${it.uri}" }) { blog ->
                // Item 7: rims/background reflect the blog's own thumbnail
                // color when it has one, same as Reviews — falling back to
                // this profile's own avatar color (via the shared
                // fallbackAvatarUrl param) when the blog has no thumbnail
                // of its own to pull a color from.
                BlogBubble(blog = blog, liquidGlass = liquidGlass, fallbackAvatarUrl = state.author.avatarUrl, onOpenBlog = onOpenBlog,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        MainViewModel.ProfileTab.REVIEWS -> {
            val reviews = (tabState?.reviews ?: emptyList()).filter { reviewKindFilter.matchesReview(it) }
            items(reviews, key = { "review_${it.uri}" }) { review ->
                ReviewRow(review = review, liquidGlass = liquidGlass, onOpenReview = onOpenReview,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        MainViewModel.ProfileTab.BACKLOG -> {
            val backlog = (tabState?.backlog ?: emptyList()).filter { reviewKindFilter.matchesBacklog(it) }
            profileBacklogGridRows(items = backlog, liquidGlass = liquidGlass)
        }
        MainViewModel.ProfileTab.VODS -> {
            items(tabState?.vods ?: emptyList(), key = { "vod_${it.uri}" }) { vod ->
                VodBubble(vod = vod, liquidGlass = liquidGlass, tint = profileTint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
    }

    val isEmpty = tabState != null &&
        tabState.items.isEmpty() && tabState.blogs.isEmpty() && tabState.reviews.isEmpty() &&
        tabState.backlog.isEmpty() && tabState.vods.isEmpty()
    if (tabState == null || (tabState.loading && isEmpty)) {
        item(key = "results_loading") {
            Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
            }
        }
    } else if (tabState.loaded && isEmpty) {
        item(key = "results_empty") {
            Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                Text("Nothing here yet", color = DimGray, fontSize = 13.sp)
            }
        }
    }
}

private fun LazyListScope.profileMediaGridRows(
    items: List<MediaItem>, loading: Boolean, onTapItem: (Int) -> Unit, onLoadMore: () -> Unit,
    // Sub-filter row predicate (see MediaKindFilter.matches) — deliberately
    // applied INSIDE this function, filtering the flattened thumbnail
    // entries rather than the `items` list itself, so postIndex below stays
    // a true index into the original, unfiltered `items` list. onTapItem's
    // caller (openProfileMediaItem-style pager navigation) indexes against
    // that same original list — passing it a filtered list's index instead
    // would silently open the wrong post the moment a filter narrowed what
    // was on screen.
    filter: (MediaItem) -> Boolean = { true }
) {
    val flattened = items.mapIndexed { postIndex, item ->
        if (!filter(item)) return@mapIndexed emptyList()
        if (item.mediaGroup.size > 1) item.mediaGroup.map { img -> postIndex to img.thumbUrl.ifBlank { img.mediaUrl } }
        else listOf(postIndex to item.thumbUrl.ifBlank { item.mediaUrl })
    }.flatten()
    val rows = flattened.chunked(3)

    // Edge case: a sub-filter (e.g. "Videos") can match nothing in the
    // currently-loaded page even though `items` itself isn't empty — in
    // that case `rows` is empty too, so the itemsIndexed loop below never
    // renders a row and its own near-the-end onLoadMore trigger never
    // fires. Without this, a filter that happens to match zero items on the
    // current page would just show a dead-end empty grid instead of
    // continuing to page in looking for a match.
    if (rows.isEmpty() && items.isNotEmpty() && !loading) {
        item(key = "grid_filtered_empty_loadmore") {
            LaunchedEffect(items.size) { onLoadMore() }
        }
    }

    itemsIndexed(rows, key = { i, row -> "grid_row_${i}_${row.firstOrNull()?.first ?: i}" }) { rowIndex, row ->
        // Fire load-more once we're rendering near the last few rows.
        if (!loading && items.isNotEmpty() && rowIndex >= rows.size - 4) {
            LaunchedEffect(rowIndex, flattened.size) { onLoadMore() }
        }
        Row(Modifier.fillMaxWidth()) {
            row.forEach { (postIndex, thumbUrl) ->
                val item = items[postIndex]
                BoxWithConstraints(Modifier.weight(1f).aspectRatio(1f).clickable { onTapItem(postIndex) }) {
                    if (item.isTextOnly) {
                        Box(Modifier.fillMaxSize().background(OledBlack).padding(6.dp), contentAlignment = Alignment.Center) {
                            Text(item.text, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        AsyncImage(model = thumbUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    if (item.isVideo) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Video", tint = Color.White.copy(0.85f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp))
                    }
                }
            }
            // Pad out a short last row so cells keep their square aspect ratio and stay left-aligned.
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    // Same fix as the Text Posts tab: only show this "load more" spinner
    // once there are already items on screen, since the very first load
    // (items empty) is already covered by the shared "results_loading"
    // spinner — showing both at once rendered two spinners at once.
    if (loading && items.isNotEmpty()) {
        item(key = "grid_loading_more") {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 1.5.dp)
            }
        }
    }
}

// ─── Backlog (Popfeed) ───────────────────────────────────────────────────────

private fun LazyListScope.profileBacklogGridRows(items: List<PopfeedBacklogItem>, liquidGlass: Boolean) {
    val rows = items.chunked(3)
    itemsIndexed(rows, key = { i, row -> "backlog_row_${i}_${row.firstOrNull()?.uri ?: i}" }) { _, row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            row.forEach { backlogItem ->
                BacklogCard(item = backlogItem, liquidGlass = liquidGlass, modifier = Modifier.weight(1f))
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** A single Backlog tile: poster-shaped thumbnail in a liquid glass frame
 *  that extends a little further down to leave room for the title —
 *  tapping does nothing yet (per spec, this is thumbnail-browsing only for
 *  now). Rim/background tint reflects that item's own poster color, the
 *  same way Reviews tiles reflect their thumbnail's color. */
@Composable
private fun BacklogCard(item: PopfeedBacklogItem, liquidGlass: Boolean, modifier: Modifier = Modifier) {
    val tint = rememberDominantColor(item.imageUrl ?: "")
    val shape = RoundedCornerShape(14.dp)
    val imageShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    Column(
        modifier
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                else Modifier.clip(shape).background(Color.White.copy(0.06f))
            )
            .clickable { /* no functionality yet — per spec */ }
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            if (item.imageUrl != null) {
                AsyncImage(model = item.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(imageShape))
            } else {
                Box(Modifier.fillMaxSize().clip(imageShape).background(Color.White.copy(0.10f)))
            }
        }
        // Title area is a single row — the text shrinks to fit rather than
        // wrapping to a second line, and is centered rather than left-aligned.
        Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            ShrinkToFitText(item.title, baseFontSize = 10.sp, minFontSize = 7.sp)
        }
    }
}

/** A single line of text that shrinks its font size (down to [minFontSize])
 *  until it fits on one line, instead of wrapping or truncating with an
 *  ellipsis. Used for the Backlog card title, which needs to always show
 *  the whole title on exactly one row. */
@Composable
private fun ShrinkToFitText(text: String, baseFontSize: androidx.compose.ui.unit.TextUnit, minFontSize: androidx.compose.ui.unit.TextUnit) {
    var fontSize by remember(text) { mutableStateOf(baseFontSize) }
    var readyToDraw by remember(text) { mutableStateOf(false) }
    Text(
        text,
        color = Color.White,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.didOverflowWidth && fontSize > minFontSize) {
                fontSize = (fontSize.value - 1f).coerceAtLeast(minFontSize.value).sp
            } else {
                readyToDraw = true
            }
        },
        modifier = Modifier.fillMaxWidth().drawWithContent { if (readyToDraw) drawContent() }
    )
}

// ─── Text Posts ──────────────────────────────────────────────────────────────

/** A "Text Posts" tab bubble — same card treatment as [BlogBubble], but shows
 *  the post's full text (no title/truncation-to-one-line) since these posts
 *  don't have a separate title the way blogs do. */
@Composable
private fun TextPostBubble(item: MediaItem, liquidGlass: Boolean, tint: Color, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                else Modifier.clip(shape).background(Color.White.copy(0.06f))
            )
            .clickable(onClick = onOpen)
            .padding(16.dp)
    ) {
        Text(item.text, color = Color.White.copy(0.92f), fontSize = 14.sp, lineHeight = 19.sp)
    }
}

// ─── Blogs (Leaflet) ─────────────────────────────────────────────────────────

/** A single blog card.
 *
 *  With a thumbnail: the image fills the card, title pill top-right with
 *  the date pill directly under it — mirrors the blog reader's own header
 *  layout — and, if the blog has a description, a bubble bottom-left.
 *  Item 2 (bug fix): the title pill used to be capped at a small fixed
 *  width regardless of how much wider the card actually was, truncating
 *  titles that had plenty of room left to grow into — it's now measured
 *  against the card's own real width (via BoxWithConstraints) instead.
 *
 *  Without a thumbnail (item 3): rather than reusing the same
 *  overlaid-bubbles-on-a-blank-panel treatment, this is a compact two-row
 *  layout — title on the far left and the date on the far right of one
 *  row, with the description (if any) on its own row underneath, left
 *  aligned. No thumbnail means no art for pills to float over, so a plain
 *  compact block reads better than empty bubbles on a flat panel.
 *
 *  Item 7: the card's glass rim/background reflects the blog's own
 *  thumbnail color, same as Review cards — falling back to [fallbackAvatarUrl]
 *  (the posting account's own avatar color) when the blog has no thumbnail
 *  of its own to pull a color from.
 *
 *  Not private — the Hub's Blogs section (item: Hub Blogs) reuses this same
 *  card, passing [fixedHeight] (item 5) so every Hub blog card shares one
 *  height instead of one width, with width instead following each
 *  thumbnail's own aspect ratio at that height — the same way a plain
 *  Image/AsyncImage auto-sizes when only one dimension is constrained. */
@Composable
fun BlogBubble(
    blog: LeafletBlog, liquidGlass: Boolean, onOpenBlog: (LeafletBlog) -> Unit,
    modifier: Modifier = Modifier,
    fallbackAvatarUrl: String? = null,
    titleFontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    fixedHeight: androidx.compose.ui.unit.Dp? = null
) {
    val shape = RoundedCornerShape(16.dp)
    val dateText = formatCreatedAt(blog.createdAt)
    val tint = rememberDominantColor(blog.thumbnailUrl ?: fallbackAvatarUrl ?: "")
    // Item 5: the Hub passes fixedHeight, which also means smaller/tighter
    // pills than the profile's full-size ones — same visual language, just
    // scaled down to fit a shorter horizontally-scrolling card.
    val compact = fixedHeight != null
    val dateFontSize = if (compact) 9.sp else 11.sp
    val descFontSize = if (compact) 9.sp else 11.sp
    val pillPadH = if (compact) 7.dp else 10.dp
    val pillPadV = if (compact) 3.dp else 5.dp
    val cardPad = if (compact) 6.dp else 10.dp
    // Bug fix (per feedback): a Hub blog card with no thumbnail used to
    // fall through to the profile's wide two-row title/date layout below —
    // a visibly different card shape sitting in the middle of a
    // horizontally-scrolling row of otherwise-identical thumbnail cards.
    // The Hub (fixedHeight != null) now always uses the "art card" layout:
    // a real thumbnail renders as before, and a missing one just renders
    // as a plain 1:1 square in that same layout (see the `hasThumbnail`
    // check just below) instead of switching card shapes. The profile's
    // own Blogs tab (fixedHeight == null) is unaffected — it keeps its
    // dedicated wide, stacked, no-thumbnail layout, since that context has
    // no "square thumbnail" size to imitate in the first place.
    val hasThumbnail = blog.thumbnailUrl != null
    val useArtLayout = hasThumbnail || fixedHeight != null

    BoxWithConstraints(
        modifier
            .then(if (fixedHeight != null) Modifier.height(fixedHeight) else Modifier)
            .clip(shape)
            .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape) else Modifier.background(Color.White.copy(0.06f)))
            .clickable { onOpenBlog(blog) }
    ) {
        // Item 2: title pill can grow to use the card's actual measured
        // width (minus the row's own edge padding) instead of a small
        // fixed cap. Bug avoidance: this only reads maxWidth on the
        // profile's non-fixedHeight cards, which sit in a fillMaxWidth
        // column and so get a real finite constraint here — the Hub's
        // fixedHeight cards sit in a horizontally-scrolling row, where the
        // incoming width constraint is unbounded (the row itself decides
        // width per-child from content, i.e. from the image's own aspect
        // ratio at a fixed height) — maxWidth in that case would read as
        // effectively infinite and wouldn't actually cap anything, so a
        // fixed heuristic proportional to the card's own height is used
        // there instead.
        val availableTitleWidth = if (fixedHeight != null) (fixedHeight * 1.15f) else (maxWidth - cardPad * 2).coerceAtLeast(40.dp)

        if (useArtLayout) {
            if (hasThumbnail) {
                if (fixedHeight != null) {
                    // Height fixed by the outer BoxWithConstraints above; width
                    // follows the image's own aspect ratio at that height,
                    // exactly like a plain Image constrained on one axis only.
                    AsyncImage(model = blog.thumbnailUrl, contentDescription = null, contentScale = ContentScale.FillHeight,
                        modifier = Modifier.fillMaxHeight())
                } else {
                    // No forced height here — FillWidth scales the image to the
                    // card's width while preserving its native aspect ratio,
                    // and with no height constraint of its own the Box (and
                    // therefore the whole card) just wraps to whatever height
                    // that produces.
                    AsyncImage(model = blog.thumbnailUrl, contentDescription = null, contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth())
                }
            } else {
                // Hub-only (fixedHeight != null, see useArtLayout above): no
                // real thumbnail, so this card just acts as if it had a
                // plain 1x1 square one — same footprint a thumbnail would
                // have at this card height, so it lines up with its
                // neighbors in the row instead of standing out.
                Box(Modifier.fillMaxHeight().aspectRatio(1f).background(Color.White.copy(0.10f)))
            }
            // Scrim so the title/date/description bubbles stay legible over
            // busy thumbnail art — same idea used for media-post captions.
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(0.05f), Color.Black.copy(0.4f)))
                )
            )

            Column(Modifier.align(Alignment.TopEnd).padding(cardPad), horizontalAlignment = Alignment.End) {
                ProfileGlassPill(text = blog.title, liquidGlass = liquidGlass, tint = tint, fontSize = titleFontSize, bold = true,
                    compact = compact, modifier = Modifier.widthIn(max = availableTitleWidth))
                if (dateText.isNotBlank()) {
                    Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
                    val pillShape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier
                            .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = pillShape) else Modifier.clip(pillShape).background(Color.White.copy(0.08f)))
                            .padding(horizontal = pillPadH, vertical = pillPadV)
                    ) {
                        Text(dateText, color = Color.White.copy(0.85f), fontSize = dateFontSize, lineHeight = dateFontSize)
                    }
                }
            }

            if (!blog.description.isNullOrBlank()) {
                val descShape = RoundedCornerShape(12.dp)
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(cardPad)
                        .widthIn(max = availableTitleWidth)
                        .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = descShape) else Modifier.clip(descShape).background(Color.White.copy(0.08f)))
                        .padding(horizontal = pillPadH, vertical = if (compact) 6.dp else 8.dp)
                ) {
                    Text(blog.description, color = Color.White.copy(0.9f), fontSize = descFontSize, lineHeight = (descFontSize.value * 1.35f).sp,
                        maxLines = if (compact) 2 else 4, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            // Item 3: compact no-thumbnail layout, profile only (see
            // useArtLayout above — the Hub never reaches this branch). No
            // art means no reason to keep the overlaid-pills-on-a-blank-
            // panel treatment — this is just title/date on one row and
            // (optional) description on the next, both left-anchored,
            // inside the same glass card.
            Column(
                Modifier.fillMaxWidth().padding(cardPad),
                verticalArrangement = Arrangement.Top
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        blog.title, color = Color.White, fontSize = titleFontSize, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (dateText.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(dateText, color = Color.White.copy(0.6f), fontSize = dateFontSize, maxLines = 1)
                    }
                }
                if (!blog.description.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        blog.description, color = Color.White.copy(0.75f), fontSize = descFontSize,
                        lineHeight = (descFontSize.value * 1.35f).sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** Item 19: a single Streamplace VOD in the profile's Vods tab — thumbnail,
 *  title, and a duration/date pill row, in the same glass-bubble language
 *  as blogs/reviews elsewhere on the profile. Tapping opens it externally
 *  for now since this app has no video-playback surface for Streamplace's
 *  own playlist format (distinct from the Bluesky video posts it already
 *  plays) — see item 19's "Live Now"/playback follow-up. */
@Composable
private fun VodBubble(vod: com.mediaviewer.model.StreamplaceVideoView, liquidGlass: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                else Modifier.clip(shape).background(Color.White.copy(0.06f))
            )
            .clickable {
                val webUrl = "https://stream.place/${vod.authorHandle}/vod/${vod.uri.substringAfterLast('/')}"
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(webUrl)))
                }
            }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(width = 96.dp, height = 60.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(0.3f))) {
            if (vod.thumbUrl != null) {
                AsyncImage(model = vod.thumbUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            val totalSeconds = vod.durationMs / 1000
            val durationText = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
            Text(
                durationText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color.Black.copy(0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(vod.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val dateText = formatCreatedAt(vod.createdAt)
            if (dateText.isNotBlank()) {
                Text(dateText, color = DimGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Formats an ISO-8601 timestamp (e.g. a record's createdAt) as a short
 *  human-readable date like "Jan 5, 2026". Returns "" if it can't be parsed
 *  so callers can just skip rendering the date pill. */
private fun formatCreatedAt(iso: String): String {
    if (iso.isBlank()) return ""
    return runCatching {
        val instant = java.time.Instant.parse(iso)
        java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault("")
}

/** "By @username" on the left, creation date on the right — same row, same
 *  sized glass pills. Used under the title in both the Blog and Review
 *  detail overlays. */
@Composable
private fun ByAndDateRow(author: AuthorInfo, createdAt: String, liquidGlass: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val pillShape = RoundedCornerShape(12.dp)
    val dateText = formatCreatedAt(createdAt)
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.fillMaxHeight()
                .then(
                    if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = pillShape)
                    else Modifier.clip(pillShape).background(Color.White.copy(0.08f))
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("By", color = DimGray, fontSize = 11.sp)
            if (author.avatarUrl != null) {
                AsyncImage(model = author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(16.dp).clip(CircleShape))
            }
            Text("@${author.handle}", color = Color.White.copy(0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (dateText.isNotBlank()) {
            Box(
                Modifier.fillMaxHeight()
                    .then(
                        if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = pillShape)
                        else Modifier.clip(pillShape).background(Color.White.copy(0.08f))
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(dateText, color = Color.White.copy(0.85f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BlogDetailOverlay(blog: LeafletBlog, author: AuthorInfo, liquidGlass: Boolean, tint: Color, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(0.94f))
            // Consumes all touches so they can't fall through to the tabs/
            // results underneath while this popup is open — a plain
            // .background() alone doesn't register as a hit-testable pointer
            // target in Compose, so without this a tap would pass straight
            // through to whatever's rendered beneath the popup.
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CloseGlassBubble(liquidGlass = liquidGlass, tint = tint, onClick = onClose)
                Spacer(Modifier.width(10.dp))
                ProfileGlassPill(text = blog.title, liquidGlass = liquidGlass, tint = tint, fontSize = 15.sp, bold = true)
            }
            ByAndDateRow(
                author = author, createdAt = blog.createdAt, liquidGlass = liquidGlass, tint = tint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)
            // Horizontal padding matches the 12dp used by the header row
            // above so the body text's edges line up with the buttons.
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 20.dp)) {
                // Cover image at the top of the reader too, if the blog has
                // one — same banner treatment ReviewDetailOverlay gives its
                // poster/backdrop art, so opening a blog with a thumbnail
                // doesn't feel like it lost that art the moment you tap in.
                if (blog.thumbnailUrl != null) {
                    val bannerShape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier.fillMaxWidth().height(200.dp)
                            .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = bannerShape) else Modifier.clip(bannerShape))
                    ) {
                        AsyncImage(model = blog.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(bannerShape))
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (blog.blocks.isNotEmpty()) {
                    // Item 8: real formatting — headers, bold text,
                    // checklists, and inline images — instead of the
                    // flattened plain-text fallback below. See
                    // BlueskyRepository.parseLeafletBlocks' doc comment for
                    // what block types this recognizes and how.
                    LeafletBlocksContent(blocks = blog.blocks, liquidGlass = liquidGlass, tint = tint)
                } else {
                    Text(blog.bodyText.ifBlank { "This blog has no readable text content." },
                        color = Color.White.copy(0.92f), fontSize = 14.sp, lineHeight = 21.sp)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** Item 8: renders a Leaflet document's parsed block tree — headers, bold
 *  runs, checklist items, and inline images — instead of just printing
 *  [LeafletBlog.bodyText]'s flattened plain text. Consecutive
 *  [LeafletBlock.ChecklistItem]s are grouped so they sit tight against each
 *  other (a real checklist) rather than getting the same paragraph spacing
 *  as everything else. */
@Composable
private fun LeafletBlocksContent(blocks: List<LeafletBlock>, liquidGlass: Boolean, tint: Color) {
    // Bug fix (per feedback): text-row alignment (Leaflet's own per-block
    // "text-align-left/center/right", now carried through as
    // LeafletBlock's `alignment` field — see BlueskyRepository.
    // parseLeafletBlocks' `alignmentOf`) wasn't applied here at all, so
    // every block rendered start-aligned regardless of what the original
    // document actually specified. `textAlign` alone is a no-op unless the
    // Text also has room to align *within* — i.e. actually spans the full
    // width instead of just wrapping to its own text's natural width — so
    // this also adds `Modifier.fillMaxWidth()` to each text block below,
    // matching what its alignment needs to have any visible effect.
    fun LeafletAlign.toTextAlign(): TextAlign = when (this) {
        LeafletAlign.START -> TextAlign.Start
        LeafletAlign.CENTER -> TextAlign.Center
        LeafletAlign.END -> TextAlign.End
    }
    fun LeafletAlign.toHorizontalArrangement(): Arrangement.Horizontal = when (this) {
        LeafletAlign.START -> Arrangement.Start
        LeafletAlign.CENTER -> Arrangement.Center
        LeafletAlign.END -> Arrangement.End
    }

    var i = 0
    Column(Modifier.fillMaxWidth()) {
        while (i < blocks.size) {
            val block = blocks[i]
            when (block) {
                is LeafletBlock.Header -> {
                    val fontSize = when (block.level) { 1 -> 22.sp; 2 -> 19.sp; 3 -> 17.sp; else -> 15.sp }
                    Text(block.text, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold,
                        lineHeight = fontSize.value.times(1.3f).sp, textAlign = block.alignment.toTextAlign(),
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp))
                    i++
                }
                is LeafletBlock.Paragraph -> {
                    Text(
                        buildAnnotatedString {
                            block.spans.forEach { span ->
                                if (span.bold) {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                                } else {
                                    append(span.text)
                                }
                            }
                        },
                        color = Color.White.copy(0.92f), fontSize = 14.sp, lineHeight = 21.sp,
                        textAlign = block.alignment.toTextAlign(),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    )
                    i++
                }
                is LeafletBlock.ImageBlock -> {
                    // Bug fix (per feedback — cropped images with a black
                    // gap below them): this used to force the image's
                    // container into a fixed heightIn(min=120, max=320)
                    // range that had nothing to do with the image's own
                    // aspect ratio at its rendered width. A wide/short
                    // image (natural height under 120dp) left a visible
                    // gap of the panel's own background between the image
                    // and the panel's bottom edge/outline; a tall/narrow
                    // image (natural height over 320dp) got its bottom
                    // portion silently clipped off once the panel's own
                    // height was capped at 320dp. The container now just
                    // wraps to whatever height FillWidth naturally
                    // produces for that specific image, exactly like every
                    // other image-wraps-a-Box pattern elsewhere in this
                    // app (see BlogBubble's own no-fixedHeight thumbnail
                    // branch) — no min, no max, so no gap and no crop
                    // either way. The 10dp spacing before the next block
                    // also moves to being the outermost modifier (an
                    // actual external margin) instead of sitting inside
                    // the clipped/bordered panel, where it was rendering
                    // as an internal gap between the image and the panel's
                    // own bottom outline rather than real spacing between
                    // blocks.
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier
                            .padding(bottom = 10.dp)
                            .fillMaxWidth()
                            .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape) else Modifier.clip(shape))
                    ) {
                        AsyncImage(model = block.url, contentDescription = block.alt, contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth())
                    }
                    i++
                }
                is LeafletBlock.ChecklistItem -> {
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape) else Modifier.clip(shape).background(Color.White.copy(0.06f)))
                            .padding(vertical = 4.dp)
                            .padding(bottom = 10.dp)
                    ) {
                        // Consume every consecutive checklist item here so
                        // the whole run shares one glass panel instead of
                        // one panel per line.
                        while (i < blocks.size) {
                            val item = blocks[i] as? LeafletBlock.ChecklistItem ?: break
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = item.alignment.toHorizontalArrangement()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(
                                        Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                                            .then(
                                                if (item.checked) Modifier.background(tint.copy(alpha = (tint.alpha).coerceAtLeast(0.7f)))
                                                else Modifier.border(1.5.dp, Color.White.copy(0.4f), RoundedCornerShape(5.dp))
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (item.checked) {
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                        }
                                    }
                                    Text(
                                        item.text, color = if (item.checked) Color.White.copy(0.55f) else Color.White.copy(0.92f),
                                        fontSize = 14.sp, lineHeight = 19.sp,
                                        textDecoration = if (item.checked) TextDecoration.LineThrough else null
                                    )
                                }
                            }
                            i++
                        }
                    }
                }
            }
        }
    }
}

// ─── Reviews (Popfeed) ───────────────────────────────────────────────────────

@Composable
private fun ReviewRow(review: PopfeedReview, liquidGlass: Boolean, onOpenReview: (PopfeedReview) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    // Rims/backgrounds reflect the thumbnail's own colors, not a fixed neutral
    // tint — same idea as everywhere else these bubbles pull from a source
    // image, just per-review instead of per-profile.
    val tint = rememberDominantColor(review.mediaImageUrl ?: "")
    Row(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                else Modifier.clip(shape).background(Color.White.copy(0.06f))
            )
            .clickable { onOpenReview(review) }
    ) {
        val imgShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
        Box(
            Modifier.fillMaxHeight().width(70.dp)
                .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = imgShape) else Modifier.clip(imgShape))
        ) {
            if (review.mediaImageUrl != null) {
                AsyncImage(model = review.mediaImageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(imgShape))
            }
        }
        Column(Modifier.fillMaxHeight().weight(1f).padding(10.dp)) {
            Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                ProfileGlassPill(text = review.mediaTitle, liquidGlass = liquidGlass, tint = tint, fontSize = 13.sp, bold = true,
                    modifier = Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(6.dp))
                StarRatingPill(rating = review.ratingOutOf5, liquidGlass = liquidGlass, tint = tint, modifier = Modifier.fillMaxHeight())
            }
            Spacer(Modifier.height(6.dp))
            Text(
                review.reviewText, color = Color.White.copy(0.85f), fontSize = 12.sp, lineHeight = 15.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Made non-private (this session) so the Hub's Mutual Review cards in
// SettingsSheet.kt can reuse the exact same star-rating look instead of
// re-implementing it — per feedback, "look at how Review stars look in the
// Reviews tab on profiles for reference."
@Composable
fun StarRatingPill(rating: Float, liquidGlass: Boolean, tint: Color = NeutralGlassTint, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape) else Modifier.clip(shape).background(Color.White.copy(0.08f)))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Round to the nearest half star first (rather than just checking the
        // fractional part against a fixed 0.25 threshold) so a rating like
        // 4.8 correctly rounds up to a full 5th star instead of stalling on a
        // half star that's actually closer to the next whole star.
        val rounded = (kotlin.math.round(rating.coerceIn(0f, 5f) * 2f) / 2f)
        val full = kotlin.math.floor(rounded).toInt().coerceIn(0, 5)
        val hasHalf = (rounded - full) >= 0.5f && full < 5
        repeat(5) { i ->
            val icon = when {
                i < full -> Icons.Filled.Star
                i == full && hasHalf -> Icons.Filled.StarHalf
                else -> Icons.Filled.StarBorder
            }
            Icon(
                icon, contentDescription = null,
                tint = if (i < full || (i == full && hasHalf)) Color(0xFFFFC107) else Color.White.copy(0.3f),
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

@Composable
private fun ReviewDetailOverlay(review: PopfeedReview, author: AuthorInfo, liquidGlass: Boolean, onClose: () -> Unit) {
    val tint = rememberDominantColor(review.mediaImageUrl ?: "")
    // Prefer Popfeed's actual landscape/backdrop art for the wide banner.
    // Only fall back to the portrait poster (cropped) if the record truly
    // doesn't carry a separate landscape image.
    val bannerImage = review.mediaBackdropUrl ?: review.mediaImageUrl
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(0.94f))
            // Consumes all touches so they can't fall through to the tabs/
            // results underneath while this popup is open — a plain
            // .background() alone doesn't register as a hit-testable pointer
            // target in Compose, so without this a tap would pass straight
            // through to whatever's rendered beneath the popup.
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp).height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CloseGlassBubble(liquidGlass = liquidGlass, tint = tint, onClick = onClose)
                Spacer(Modifier.width(10.dp))
                // Title bubble and star-rating bubble — same row, same height.
                Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    ProfileGlassPill(text = review.mediaTitle, liquidGlass = liquidGlass, tint = tint, fontSize = 15.sp, bold = true,
                        modifier = Modifier.fillMaxHeight())
                    Spacer(Modifier.width(8.dp))
                    StarRatingPill(rating = review.ratingOutOf5, liquidGlass = liquidGlass, tint = tint, modifier = Modifier.fillMaxHeight())
                }
            }
            ByAndDateRow(
                author = author, createdAt = review.createdAt, liquidGlass = liquidGlass, tint = tint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)
            // Horizontal padding matches the 12dp used by the header row
            // above so the banner and body text's edges line up with the
            // buttons above them.
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 20.dp)) {
                if (bannerImage != null) {
                    val shape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier.fillMaxWidth().height(220.dp)
                            .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape) else Modifier.clip(shape))
                    ) {
                        AsyncImage(model = bannerImage, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text(review.reviewText, color = Color.White.copy(0.92f), fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}
