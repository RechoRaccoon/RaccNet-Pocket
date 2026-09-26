package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.model.AppMode
import com.mediaviewer.model.BskyFeedInfo
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * The feed's grid mode (pinch in on a post). Laid out like a profile page:
 * the feeds as a row of profile-style main tabs, the content-type sub-tabs
 * under them, the posts in the profile's own layouts (masonry / square
 * grid / text list / video lists), and the profile's interaction bar with
 * Refresh + Grid layout. Everything wears the signed-in user's own profile
 * color. Pinch out anywhere to go back to the post you were on.
 */
/**
 * Where the grid was, kept across the grid being closed (pinch out / opening
 * a post) and reopened — like a profile remembers its scroll. Saved as "which
 * post sat in the middle of the screen, and how far off-centre", so it lands
 * exactly back in place, whatever the layout.
 */
private object GridScrollMemory {
    var scope: String? = null
    var anchorId: String? = null
    var anchorDelta: Int = 0
    /** The feed post the grid was left for; if the feed has since moved to a
     *  different post, the grid centres that one instead. */
    var feedIndex: Int = -1
}

private const val POST_KEY_PREFIX = "p:"

/** The post nearest the viewport's middle, and how far its centre is from it. */
private fun middleAnchor(state: LazyStaggeredGridState): Pair<String, Int>? {
    val info = state.layoutInfo
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val best = info.visibleItemsInfo
        .filter { (it.key as? String)?.startsWith(POST_KEY_PREFIX) == true }
        .minByOrNull { kotlin.math.abs(it.offset.y + it.size.height / 2 - mid) } ?: return null
    return (best.key as String).removePrefix(POST_KEY_PREFIX) to (best.offset.y + best.size.height / 2 - mid)
}

/** Scrolls so post [index] sits [delta] px off the viewport's middle. */
private suspend fun centreOn(state: LazyStaggeredGridState, index: Int, delta: Int) {
    state.scrollToItem(index)
    val info = state.layoutInfo
    val it = info.visibleItemsInfo.firstOrNull { v -> v.index == index } ?: return
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val shift = (it.offset.y + it.size.height / 2 - mid) - delta
    if (shift != 0) state.scrollBy(shift.toFloat())
}

/**
 * The feed's grid mode (pinch in on a post). Laid out like a profile page:
 * the feeds as a row of profile-style main tabs, the content-type sub-tabs
 * under them, the posts in the profile's own layouts (masonry / square
 * grid / text list / video lists), and the profile's interaction bar with
 * Refresh + Grid layout. Everything wears the signed-in user's own profile
 * color. Pinch out anywhere to go back to the post you were on.
 *
 * The posts are a LazyVerticalStaggeredGrid, so only on-screen tiles exist
 * (the profile's LazyColumn masonry composes every loaded tile at once,
 * which crawled with a whole feed loaded).
 */
@Composable
fun GridScreen(
    items: List<MediaItem>,
    currentIndex: Int,
    appMode: AppMode,
    availableFeeds: List<BskyFeedInfo>,
    selectedFeedUri: String?,
    authorFeedState: MainViewModel.AuthorFeedSavedState?,
    e621SearchTags: String,
    liquidGlass: Boolean = false,
    // onItemClick also reports which sub-image within that post's
    // mediaGroup was tapped, so the pager opens on it. -1 means "leave the
    // sub-image index as-is" (the pinch-out-to-return gesture).
    onItemClick: (postIndex: Int, subImageIndex: Int) -> Unit,
    onLoadMore: () -> Unit,
    onSelectFeed: (String?) -> Unit,
    onSearchE621: (String) -> Unit,
    onRefresh: () -> Unit,
    /** The signed-in user's avatar — the grid's UI wears its color. */
    selfAvatarUrl: String? = null,
    isLoading: Boolean = false,
    roundedGridTiles: Boolean = false,
    reducedAnimations: Boolean = false
) {
    val tap = rememberHapticTap()
    var localTags  by remember(e621SearchTags) { mutableStateOf(e621SearchTags) }
    val tint = if (!selfAvatarUrl.isNullOrBlank()) rememberDominantColor(selfAvatarUrl) else NeutralGlassTint
    var kind by remember { mutableStateOf(PostKindFilter.ALL) }
    val gridScreen = "feed_grid"
    val gridMode = resultsGridMode(gridScreen, kind)
    val spec = resultsLayoutSpec(kind, gridMode, roundedGridTiles)
    val gridState = rememberLazyStaggeredGridState()
    // A swipeable multi-image tile reports which image it's showing just
    // before its click lands; carried into onItemClick as the sub-image.
    val pendingSeed = remember { arrayOfNulls<Pair<String, Int>>(1) }
    val latestItems by rememberUpdatedState(items)
    val latestCurrentIndex by rememberUpdatedState(currentIndex)

    // Posts shown under the current sub-tab. Deduped by id: lazy layouts
    // crash on duplicate keys, and feeds can repeat a post.
    val matched = remember(items, kind) { items.filter { kind.matches(it) }.distinctBy { it.id } }
    val indexById = remember(matched) { HashMap<String, Int>(matched.size * 2).also { m -> matched.forEachIndexed { i, it -> m[it.id] = i } } }
    val memoryScope = "$appMode|${authorFeedState?.author?.did ?: selectedFeedUri}"

    // ── Keeping its place ─────────────────────────────────────────────────
    // (a) across the grid closing and reopening (see GridScrollMemory);
    // (b) across a Grid-layout or sub-tab switch: the post in the middle
    //     before the switch is put back in the middle after it.
    var exitFeedIndex by remember { mutableIntStateOf(currentIndex) }
    var pendingAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(matched.isNotEmpty()) {
        if (restored || matched.isEmpty()) return@LaunchedEffect
        restored = true
        val mem = GridScrollMemory
        val sameSpot = mem.scope == memoryScope && mem.feedIndex == currentIndex && mem.anchorId != null
        if (sameSpot) {
            val idx = indexById[mem.anchorId!!]
            if (idx != null) { centreOn(gridState, idx, mem.anchorDelta); return@LaunchedEffect }
        }
        val current = items.getOrNull(currentIndex)?.id?.let { indexById[it] }
        if (current != null) centreOn(gridState, current, 0)
    }
    LaunchedEffect(kind, gridMode) {
        val a = pendingAnchor ?: return@LaunchedEffect
        pendingAnchor = null
        val idx = indexById[a.first]
        if (idx != null) centreOn(gridState, idx, a.second) else gridState.scrollToItem(0)
    }
    DisposableEffect(memoryScope) {
        onDispose {
            val a = middleAnchor(gridState)
            GridScrollMemory.scope = memoryScope
            GridScrollMemory.anchorId = a?.first
            GridScrollMemory.anchorDelta = a?.second ?: 0
            GridScrollMemory.feedIndex = exitFeedIndex
        }
    }

    // Paging: ask for more once the last dozen tiles come into view (the
    // old masonry asked on every composition, so it kept loading forever).
    LaunchedEffect(gridState, isLoading, matched.size) {
        if (isLoading) return@LaunchedEffect
        // A sub-tab with nothing matching on the loaded pages yet: keep paging.
        if (matched.isEmpty() && items.isNotEmpty()) { onLoadMore(); return@LaunchedEffect }
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 12
        }.distinctUntilChanged().filter { it }.collect { onLoadMore() }
    }

    // Live glass backdrop: this page's background AND its scrolling posts,
    // recorded so the interaction bar blurs whatever is really behind it.
    // The bar itself sits outside the recorded box (a glass panel must never
    // draw inside the layer it samples).
    val backdropLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val backdrop = remember(liquidGlass, backdropLayer) {
        if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
    }

    Box(Modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
            .drawWithContent {
                if (liquidGlass) backdropLayer.record { this@drawWithContent.drawContent() }
                drawContent()
            }
            .then(if (liquidGlass) Modifier.background(postBackgroundBrush(tint)) else Modifier.background(OledBlack))
    ) {
        Column(Modifier.fillMaxSize().padding(top = rememberTopCutoutClearance())) {
            // ── Feeds (profile-style main tabs) / e621 tag search ─────────────
            if (appMode == AppMode.BLUESKY) {
                val saved = authorFeedState
                val labels = buildList {
                    if (saved != null) add(saved.author.displayName.ifBlank { "@" + saved.author.handle })
                    availableFeeds.forEach { add(it.displayName) }
                }
                val offset = if (saved != null) 1 else 0
                val selected = if (saved != null) 0 else availableFeeds.indexOfFirst { it.uri == selectedFeedUri }.let { if (it >= 0) it + offset else -1 }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                ProfileStyleTabRow(labels = labels, selectedIndex = selected, liquidGlass = liquidGlass, tint = tint) { i ->
                    if (i >= offset) availableFeeds.getOrNull(i - offset)?.let { onSelectFeed(it.uri) }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = localTags, onValueChange = { localTags = it },
                        placeholder = { Text("Search tags…", color = DimGray, fontSize = 13.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tint.copy(0.6f), unfocusedBorderColor = Color.White.copy(0.1f),
                            cursorColor = Color.White, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.weight(1f).height(54.dp)
                    )
                    Button(
                        onClick = { tap(); onSearchE621(localTags) },
                        colors = ButtonDefaults.buttonColors(containerColor = tint.copy(0.35f), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(54.dp)
                    ) { Text("Go", fontSize = 13.sp) }
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
            // ── Content-type sub-tabs ──────────────────────────────────────
            if (items.isNotEmpty()) {
                PostKindSubTabRow(items, kind, liquidGlass, tint) { newKind ->
                    if (newKind != kind) {
                        pendingAnchor = middleAnchor(gridState)
                        kind = newKind
                    }
                }
            }

            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 1.5.dp)
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(spec.lanes),
                    state = gridState,
                    contentPadding = PaddingValues(start = spec.horizontalPadding, end = spec.horizontalPadding, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(spec.spacing),
                    verticalItemSpacing = spec.spacing,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Pinch OUT (fingers spreading apart) — the opposite of
                        // the pinch-in that opened the grid — goes back to the
                        // post you were on. Watched at the Initial pass and only
                        // consumed once it fires, so one-finger scrolling is
                        // untouched.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                var startDist = -1f
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size < 2) { startDist = -1f; continue }
                                    val dist = (pressed[0].position - pressed[1].position).getDistance()
                                    if (startDist < 0f) { startDist = dist; continue }
                                    pressed.forEach { it.consume() }
                                    if (dist > startDist * 1.4f) {
                                        exitFeedIndex = latestCurrentIndex
                                        onItemClick(latestCurrentIndex, -1)
                                        break
                                    }
                                }
                            }
                        }
                ) {
                    items(
                        count = matched.size,
                        key = { i -> POST_KEY_PREFIX + matched[i].id },
                        contentType = { gridMode * 10 + kind.ordinal }
                    ) { i ->
                        val item = matched[i]
                        PostResultTile(
                            item = item, filter = kind, gridMode = gridMode, tint = tint,
                            liquidGlass = liquidGlass, roundedGridTiles = roundedGridTiles,
                            onSeedSubImageIndex = { id, page -> pendingSeed[0] = id to page },
                            onClick = {
                                val idx = latestItems.indexOfFirst { it.id == item.id }
                                if (idx >= 0) {
                                    val seed = pendingSeed[0]?.takeIf { it.first == item.id }?.second ?: 0
                                    pendingSeed[0] = null
                                    exitFeedIndex = idx
                                    onItemClick(idx, seed)
                                }
                            }
                        )
                    }
                    if (isLoading) {
                        item(key = "grid_loading_more", span = StaggeredGridItemSpan.FullLine) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 1.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
        ResultsInteractionBar(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            refreshing = isLoading, animateRefresh = !reducedAnimations,
            onRefresh = onRefresh,
            filter = kind, gridMode = gridMode,
            onGrid = {
                pendingAnchor = middleAnchor(gridState)
                cycleResultsGridMode(gridScreen, kind)
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
        )
    }
}
