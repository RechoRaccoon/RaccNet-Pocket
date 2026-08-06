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
import com.mediaviewer.viewmodel.MainViewModel
import kotlinx.coroutines.withTimeoutOrNull

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
    onItemClick: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onSelectFeed: (String?) -> Unit,
    onSearchE621: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val gridState  = rememberLazyGridState()
    var localTags  by remember(e621SearchTags) { mutableStateOf(e621SearchTags) }

    LaunchedEffect(currentIndex) {
        if (currentIndex > 0) gridState.scrollToItem(maxOf(0, currentIndex - 3))
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= items.size - 12
        }
    }
    // Keyed on items.size too, not just the boolean — if a page comes back
    // short enough that shouldLoadMore is already true, and the next page is
    // *also* short, the boolean itself never flips (stays true the whole
    // time), so a LaunchedEffect keyed on shouldLoadMore alone would only
    // fire once and then silently stop asking for more, even though the
    // screen still isn't full. Re-including items.size makes it refire every
    // time the list actually grows, so it keeps requesting more until either
    // the viewport is full or the feed truly runs out.
    LaunchedEffect(shouldLoadMore, items.size) {
        if (shouldLoadMore && items.isNotEmpty()) onLoadMore()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // ── Feed selector / search bar ─────────────────────────────────────────
        if (appMode == AppMode.BLUESKY) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Author chip when viewing a specific account
                val saved = authorFeedState
                if (saved != null) AuthorChip(author = saved.author, liquidGlass = liquidGlass)

                availableFeeds.forEach { feed ->
                    FeedChip(feed.displayName, feed.avatarUrl,
                        selectedFeedUri == feed.uri && saved == null, liquidGlass = liquidGlass) { onSelectFeed(feed.uri) }
                }
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
                        focusedBorderColor = Color.White.copy(0.3f), unfocusedBorderColor = Color.White.copy(0.1f),
                        cursorColor = Color.White, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.weight(1f).height(54.dp)
                )
                Button(
                    onClick = { onSearchE621(localTags) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f), contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(54.dp)
                ) { Text("Go", fontSize = 13.sp) }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), thickness = 0.5.dp)

        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 1.5.dp)
            }
        } else {
            // Item 4: the feed groups multi-image posts into one MediaItem (so
            // liking/following is shared across its images), but the grid should
            // still show every individual image as its own cell.
            val flattened = remember(items) {
                items.mapIndexed { postIndex, item ->
                    if (item.mediaGroup.size > 1) item.mediaGroup.map { img -> postIndex to img.thumbUrl.ifBlank { img.mediaUrl } }
                    else listOf(postIndex to item.thumbUrl.ifBlank { item.mediaUrl })
                }.flatten()
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    // Item 5: pinch OUT (fingers spreading apart) — the opposite
                    // gesture from the pinch-IN that enters grid mode — jumps back
                    // to the specific post the user was viewing before entering grid.
                    .pointerInput(currentIndex) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var armDist = -1f
                            var prevDist = -1f
                            while (true) {
                                val event = withTimeoutOrNull(16L) { awaitPointerEvent(PointerEventPass.Main) } ?: continue
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    val p1 = pressed[0].position; val p2 = pressed[1].position
                                    val dist = (p1 - p2).getDistance()
                                    if (armDist < 0f) armDist = dist
                                    if (prevDist > 0f && dist > armDist * 1.4f) {
                                        onItemClick(currentIndex)
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
                    },
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                itemsIndexed(flattened, key = { i, pair -> "${pair.first}_$i" }) { _, (postIndex, thumbUrl) ->
                    val item = items[postIndex]
                    GridCell(item, thumbUrl, postIndex == currentIndex) { onItemClick(postIndex) }
                }
            }
        }
    }
}

@Composable
private fun GridCell(item: MediaItem, thumbUrl: String, isActive: Boolean, onClick: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier.aspectRatio(1f).clickable(onClick = onClick)
    ) {
        if (item.isTextOnly) {
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
