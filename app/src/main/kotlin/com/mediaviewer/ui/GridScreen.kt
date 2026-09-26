package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mediaviewer.model.AppMode
import com.mediaviewer.model.BskyFeedInfo
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The feed's grid mode (pinch in on a post). Laid out like a profile page:
 * the feeds as a row of profile-style main tabs, the content-type sub-tabs
 * under them, the posts in the profile's own layouts (masonry / square
 * grid / text list / video lists), and the profile's interaction bar with
 * Refresh + Grid layout. Everything wears the signed-in user's own profile
 * color. Pinch out anywhere to go back to the post you were on.
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
    // Bug fix: tapping the 2nd of 4 images in a grid always opened the
    // pager to that post's index-0 image instead of the one actually
    // tapped. onItemClick now also reports which sub-image within that
    // post's mediaGroup was tapped, so the caller can seed the pager's
    // per-post sub-image index before navigating to it. -1 means "leave
    // the sub-image index as-is" (used by the pinch-out-to-return gesture,
    // which shouldn't reset whatever image was already showing).
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
    val listState = rememberLazyListState()
    // A swipeable multi-image tile reports which image it's showing just
    // before its click lands; carried into onItemClick as the sub-image.
    val pendingSeed = remember { arrayOfNulls<Pair<String, Int>>(1) }

    // Live glass backdrop: this page's own background gradient, recorded so
    // the tab pills and the interaction bar can blur it like the profile's.
    val backdropLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val backdrop = remember(liquidGlass, backdropLayer) {
        if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                .then(
                    if (liquidGlass) Modifier.background(postBackgroundBrush(tint)).drawWithContent {
                        backdropLayer.record { this@drawWithContent.drawContent() }
                        drawContent()
                    } else Modifier.background(OledBlack)
                )
        )
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
                PostKindSubTabRow(items, kind, liquidGlass, tint) { kind = it }
            }

            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 1.5.dp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Item 5: pinch OUT (fingers spreading apart) — the opposite
                        // gesture from the pinch-IN that enters grid mode — jumps back
                        // to the specific post the user was viewing before entering grid.
                        .pointerInput(currentIndex) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var armDist = -1f
                                var prevDist = -1f
                                while (true) {
                                    val event = withTimeoutOrNull(16L) { awaitPointerEvent(PointerEventPass.Initial) } ?: continue
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size >= 2) {
                                        val p1 = pressed[0].position; val p2 = pressed[1].position
                                        val dist = (p1 - p2).getDistance()
                                        if (armDist < 0f) armDist = dist
                                        if (prevDist > 0f && dist > armDist * 1.4f) {
                                            onItemClick(currentIndex, -1)
                                            pressed.forEach { it.consume() }
                                            break
                                        }
                                        prevDist = dist
                                        pressed.forEach { it.consume() }
                                    } else {
                                        prevDist = -1f
                                    }
                                }
                            }
                        }
                ) {
                    sharedPostResults(
                        items = items, loading = isLoading, filter = kind,
                        gridMode = resultsGridMode(gridScreen, kind), tint = tint, liquidGlass = liquidGlass,
                        roundedGridTiles = roundedGridTiles,
                        onTapItem = { item ->
                            val idx = items.indexOf(item)
                            if (idx >= 0) {
                                val seed = pendingSeed[0]?.takeIf { it.first == item.id }?.second ?: 0
                                pendingSeed[0] = null
                                onItemClick(idx, seed)
                            }
                        },
                        onSeedSubImageIndex = { id, page -> pendingSeed[0] = id to page },
                        onLoadMore = onLoadMore
                    )
                }
            }
        }
        ResultsInteractionBar(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            refreshing = isLoading, animateRefresh = !reducedAnimations,
            onRefresh = onRefresh,
            filter = kind, gridMode = resultsGridMode(gridScreen, kind),
            onGrid = { cycleResultsGridMode(gridScreen, kind) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun GridCell(item: MediaItem, thumbUrl: String, isActive: Boolean, onClick: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier.aspectRatio(1f).clickable(onClick = onClick)
    ) {
        if (item.isEmojiTextshot) {
            Box(Modifier.fillMaxSize().background(OffBlack)) {
                TextshotEmojiImage(item.textshotImageUrl, cornerRadius = 0.dp, modifier = Modifier.fillMaxSize())
            }
        } else if (item.isTextOnly) {
            // Text-only posts have no thumbnail — show a compact text preview
            // tile instead of an empty image cell.
            Box(
                Modifier.fillMaxSize().background(OffBlack).padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    item.text, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp,
                    maxLines = 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbUrl)
                    .crossfade(false).size(maxWidth.value.toInt()).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (isActive) Box(Modifier.fillMaxSize().background(Color.White.copy(0.15f)))
        if (item.isVideo) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Video",
                tint = Color.White.copy(0.85f),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp))
        }
    }
}
