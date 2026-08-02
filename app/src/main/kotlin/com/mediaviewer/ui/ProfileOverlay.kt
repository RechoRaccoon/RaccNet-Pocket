package com.mediaviewer.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.LeafletBlog
import com.mediaviewer.model.MediaItem
import com.mediaviewer.model.PopfeedReview
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.ui.theme.OledBlack
import com.mediaviewer.viewmodel.MainViewModel
import kotlin.math.roundToInt

private fun MainViewModel.ProfileTab.label(): String = when (this) {
    MainViewModel.ProfileTab.POSTS   -> "Posts"
    MainViewModel.ProfileTab.REPOSTS -> "Reposts"
    MainViewModel.ProfileTab.LIKES   -> "Likes"
    MainViewModel.ProfileTab.BLOGS   -> "Blogs"
    MainViewModel.ProfileTab.REVIEWS -> "Reviews"
}

/**
 * Profile Overhaul — a full-screen overlay page for viewing an account's
 * profile. Rendered above everything else (see MainActivity) so closing it
 * just removes this composable and drops the user back exactly where they
 * were underneath.
 */
@Composable
fun ProfileOverlay(
    state: MainViewModel.ProfileOverlayState,
    liquidGlass: Boolean,
    reducedAnimations: Boolean,
    onClose: () -> Unit,
    onSelectTab: (MainViewModel.ProfileTab) -> Unit,
    onLoadMore: () -> Unit,
    onSetExpanded: (Boolean) -> Unit,
    onToggleFollow: () -> Unit,
    onTapItem: (Int) -> Unit,
    onOpenBlog: (LeafletBlog) -> Unit,
    onCloseBlog: () -> Unit,
    onOpenReview: (PopfeedReview) -> Unit,
    onCloseReview: () -> Unit
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

    val animSpecDp = if (reducedAnimations) snap() else tween<Dp>(260)
    val animSpecFloat = if (reducedAnimations) snap() else tween<Float>(260)

    BackHandler(onClose)

    Box(
        Modifier
            .fillMaxSize()
            .background(postBackgroundBrush(blended))
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {

            // ── Full profile header (banner / avatar / pills / bio) — collapses on swipe-up ──
            AnimatedVisibility(
                visible = state.expanded,
                enter = expandVertically(animationSpec = tween(if (reducedAnimations) 0 else 260)) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(if (reducedAnimations) 0 else 260)) + fadeOut()
            ) {
                ProfileHeaderSection(
                    author = author,
                    profile = profile,
                    loadingProfile = state.loadingProfile,
                    liquidGlass = liquidGlass,
                    bannerColor = bannerColor,
                    avatarColor = avatarColor,
                    onToggleFollow = onToggleFollow,
                    onSwipeUp = { onSetExpanded(false) }
                )
            }

            // ── Compact fixed header — shown only once collapsed ──
            AnimatedVisibility(
                visible = !state.expanded,
                enter = fadeIn(tween(if (reducedAnimations) 0 else 200)),
                exit = fadeOut(tween(if (reducedAnimations) 0 else 200))
            ) {
                CompactProfileHeader(
                    author = author, liquidGlass = liquidGlass, tint = blended,
                    onClose = onClose, onSwipeDown = { onSetExpanded(true) }
                )
            }

            // ── Tabs — always visible; pinned at top once collapsed ──
            ProfileTabsRow(
                tabs = MainViewModel.ProfileTab.entries.filter { it in state.availableTabs },
                selected = state.selectedTab,
                liquidGlass = liquidGlass,
                tint = blended,
                modifier = Modifier.pointerInput(state.expanded) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onVerticalDrag = { change, amount -> total += amount; change.consume() },
                        onDragEnd = {
                            if (total < -32f) onSetExpanded(false)
                            else if (total > 32f) onSetExpanded(true)
                        }
                    )
                },
                onSelect = onSelectTab
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)

            // ── Results — scrolls independently of the header/tabs above ──
            ProfileResultsSection(
                state = state,
                liquidGlass = liquidGlass,
                onLoadMore = onLoadMore,
                onTapItem = onTapItem,
                onOpenBlog = onOpenBlog,
                onOpenReview = onOpenReview,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }

        if (!state.loadingProfile && profile == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Couldn't load this profile", color = DimGray, fontSize = 13.sp)
            }
        }

        state.openBlog?.let { blog ->
            BlogDetailOverlay(blog = blog, author = author, liquidGlass = liquidGlass, onClose = onCloseBlog)
        }
        state.openReview?.let { review ->
            ReviewDetailOverlay(review = review, liquidGlass = liquidGlass, onClose = onCloseReview)
        }
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
    onToggleFollow: () -> Unit,
    onSwipeUp: () -> Unit
) {
    val avatarSize = 84.dp
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { change, amount -> total += amount; change.consume() },
                    onDragEnd = { if (total < -32f) onSwipeUp() }
                )
            }
    ) {
        // ── Banner ──
        Box(Modifier.fillMaxWidth().height(146.dp)) {
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

            // Follow / Unfollow — top right.
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                ProfileFollowButton(isFollowing = author.isFollowing, liquidGlass = liquidGlass, tint = bannerColor, onClick = onToggleFollow)
            }

            // Avatar (left) + name/username pills (right) — bottom-anchored in the banner.
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp).height(avatarSize),
                verticalAlignment = Alignment.Top
            ) {
                ProfileAvatarGlass(url = author.avatarUrl, size = avatarSize, liquidGlass = liquidGlass, tint = avatarColor)
                Spacer(Modifier.width(14.dp))
                Column(
                    Modifier.fillMaxHeight().weight(1f),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ProfileGlassPill(
                        text = author.displayName, liquidGlass = liquidGlass, tint = bannerColor,
                        fontSize = 15.sp, bold = true
                    )
                    ProfileGlassPill(
                        text = "@${author.handle}", liquidGlass = liquidGlass, tint = bannerColor.copy(alpha = 0.8f),
                        fontSize = 12.sp, bold = false
                    )
                }
            }
        }

        // ── Bio ──
        val bio = profile?.description.orEmpty()
        if (bio.isNotBlank()) {
            Text(
                bio, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 18.sp,
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CountStat(profile.postsCount, "Posts")
                CountStat(profile.followersCount, "Followers")
                CountStat(profile.followsCount, "Following")
            }
            Spacer(Modifier.height(10.dp))
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
private fun CompactProfileHeader(
    author: AuthorInfo, liquidGlass: Boolean, tint: Color,
    onClose: () -> Unit, onSwipeDown: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { change, amount -> total += amount; change.consume() },
                    onDragEnd = { if (total > 32f) onSwipeDown() }
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CloseGlassBubble(liquidGlass = liquidGlass, tint = tint, onClick = onClose)
        if (author.avatarUrl != null) {
            AsyncImage(
                model = author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(26.dp).clip(CircleShape)
            )
        }
        Column {
            Text(author.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${author.handle}", color = DimGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CloseGlassBubble(liquidGlass: Boolean, tint: Color, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        Modifier
            .size(30.dp)
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                else Modifier.clip(shape).background(Color.White.copy(0.14f))
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Close, contentDescription = "Close profile", tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

// ─── Small building blocks ──────────────────────────────────────────────────

@Composable
private fun ProfileAvatarGlass(url: String?, size: Dp, liquidGlass: Boolean, tint: Color) {
    val shape = CircleShape
    Box(
        Modifier
            .size(size)
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                else Modifier.clip(shape).background(Color.Black.copy(0.5f))
            )
            .padding(5.dp) // thick rim
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
        } else {
            Box(Modifier.fillMaxSize().clip(shape).background(Color.White.copy(0.15f)))
        }
    }
}

@Composable
private fun ProfileGlassPill(text: String, liquidGlass: Boolean, tint: Color, fontSize: androidx.compose.ui.unit.TextUnit, bold: Boolean) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                else Modifier.clip(shape).background(Color.Black.copy(0.55f))
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = Color.White, fontSize = fontSize, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProfileFollowButton(isFollowing: Boolean, liquidGlass: Boolean, tint: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val modifier = Modifier.clip(shape).clickable(onClick = onClick)
    @Composable
    fun Label() {
        Box(contentAlignment = Alignment.Center) {
            Text("Following", color = Color.Transparent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(if (isFollowing) "Following" else "Follow",
                color = if (isFollowing) Color.White.copy(0.7f) else Color.White,
                fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = modifier, shape = shape, tint = tint) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { Label() }
        }
    } else {
        Box(modifier.background(if (isFollowing) Color.White.copy(0.08f) else Color.White.copy(0.18f)).padding(horizontal = 14.dp, vertical = 8.dp)) { Label() }
    }
}

@Composable
private fun ProfileTabsRow(
    tabs: List<MainViewModel.ProfileTab>, selected: MainViewModel.ProfileTab, liquidGlass: Boolean, tint: Color,
    modifier: Modifier = Modifier, onSelect: (MainViewModel.ProfileTab) -> Unit
) {
    Row(
        modifier
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

@Composable
private fun ProfileResultsSection(
    state: MainViewModel.ProfileOverlayState,
    liquidGlass: Boolean,
    onLoadMore: () -> Unit,
    onTapItem: (Int) -> Unit,
    onOpenBlog: (LeafletBlog) -> Unit,
    onOpenReview: (PopfeedReview) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabState = state.tabStates[state.selectedTab]
    Box(modifier) {
        when (state.selectedTab) {
            MainViewModel.ProfileTab.POSTS, MainViewModel.ProfileTab.REPOSTS, MainViewModel.ProfileTab.LIKES -> {
                ProfileMediaGrid(
                    items = tabState?.items ?: emptyList(),
                    loading = tabState?.loading == true,
                    onTapItem = onTapItem, onLoadMore = onLoadMore
                )
            }
            MainViewModel.ProfileTab.BLOGS -> BlogsList(blogs = tabState?.blogs ?: emptyList(), liquidGlass = liquidGlass, onOpenBlog = onOpenBlog)
            MainViewModel.ProfileTab.REVIEWS -> ReviewsList(reviews = tabState?.reviews ?: emptyList(), liquidGlass = liquidGlass, onOpenReview = onOpenReview)
        }
        if (tabState == null || (tabState.loading && tabState.items.isEmpty() && tabState.blogs.isEmpty() && tabState.reviews.isEmpty())) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
            }
        } else if (tabState.loaded && tabState.items.isEmpty() && tabState.blogs.isEmpty() && tabState.reviews.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing here yet", color = DimGray, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ProfileMediaGrid(items: List<MediaItem>, loading: Boolean, onTapItem: (Int) -> Unit, onLoadMore: () -> Unit) {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val flattened = remember(items) {
        items.mapIndexed { postIndex, item ->
            if (item.mediaGroup.size > 1) item.mediaGroup.map { img -> postIndex to img.thumbUrl.ifBlank { img.mediaUrl } }
            else listOf(postIndex to item.thumbUrl.ifBlank { item.mediaUrl })
        }.flatten()
    }
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= flattened.size - 12
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore && items.isNotEmpty() && !loading) onLoadMore() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        itemsIndexed(flattened, key = { i, pair -> "${pair.first}_$i" }) { _, (postIndex, thumbUrl) ->
            val item = items[postIndex]
            BoxWithConstraints(Modifier.aspectRatio(1f).clickable { onTapItem(postIndex) }) {
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
    }
}

// ─── Blogs (Leaflet) ─────────────────────────────────────────────────────────

@Composable
private fun BlogsList(blogs: List<LeafletBlog>, liquidGlass: Boolean, onOpenBlog: (LeafletBlog) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(blogs, key = { it.uri }) { blog ->
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (liquidGlass) Modifier.glassPanel(true, shape = shape)
                        else Modifier.clip(shape).background(Color.White.copy(0.06f))
                    )
                    .clickable { onOpenBlog(blog) }
                    .padding(16.dp)
            ) {
                Text(blog.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BlogDetailOverlay(blog: LeafletBlog, author: AuthorInfo, liquidGlass: Boolean, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.94f))) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // Compact fixed header: X (own pill) — title + "By" row (own pills), right-aligned.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CloseGlassBubble(liquidGlass = liquidGlass, tint = NeutralGlassTint, onClick = onClose)
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                    ProfileGlassPill(text = blog.title, liquidGlass = liquidGlass, tint = NeutralGlassTint, fontSize = 15.sp, bold = true)
                    Spacer(Modifier.height(6.dp))
                    val byShape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier
                            .then(
                                if (liquidGlass) Modifier.glassPanel(true, tint = NeutralGlassTint, shape = byShape)
                                else Modifier.clip(byShape).background(Color.White.copy(0.08f))
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
                }
            }
            HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(blog.bodyText.ifBlank { "This blog has no readable text content." },
                    color = Color.White.copy(0.92f), fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// ─── Reviews (Popfeed) ───────────────────────────────────────────────────────

@Composable
private fun ReviewsList(reviews: List<PopfeedReview>, liquidGlass: Boolean, onOpenReview: (PopfeedReview) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(reviews, key = { it.uri }) { review ->
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .then(
                        if (liquidGlass) Modifier.glassPanel(true, shape = shape)
                        else Modifier.clip(shape).background(Color.White.copy(0.06f))
                    )
                    .clickable { onOpenReview(review) }
            ) {
                val imgShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                Box(
                    Modifier.fillMaxHeight().width(70.dp)
                        .then(if (liquidGlass) Modifier.glassPanel(true, tint = NeutralGlassTint, shape = imgShape) else Modifier.clip(imgShape))
                ) {
                    if (review.mediaImageUrl != null) {
                        AsyncImage(model = review.mediaImageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(imgShape))
                    }
                }
                Column(Modifier.fillMaxHeight().weight(1f).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            ProfileGlassPill(text = review.mediaTitle, liquidGlass = liquidGlass, tint = NeutralGlassTint, fontSize = 13.sp, bold = true)
                        }
                        Spacer(Modifier.width(6.dp))
                        StarRatingPill(rating = review.ratingOutOf5, liquidGlass = liquidGlass)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        review.reviewText, color = Color.White.copy(0.85f), fontSize = 12.sp, lineHeight = 15.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun StarRatingPill(rating: Float, liquidGlass: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .then(if (liquidGlass) Modifier.glassPanel(true, tint = NeutralGlassTint, shape = shape) else Modifier.clip(shape).background(Color.White.copy(0.08f)))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        val full = rating.roundToInt().coerceIn(0, 5)
        repeat(5) { i ->
            Icon(
                if (i < full) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                tint = if (i < full) Color(0xFFFFC107) else Color.White.copy(0.3f),
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

@Composable
private fun ReviewDetailOverlay(review: PopfeedReview, liquidGlass: Boolean, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.94f))) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                CloseGlassBubble(liquidGlass = liquidGlass, tint = NeutralGlassTint, onClick = onClose)
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                    ProfileGlassPill(text = review.mediaTitle, liquidGlass = liquidGlass, tint = NeutralGlassTint, fontSize = 15.sp, bold = true)
                    Spacer(Modifier.height(6.dp))
                    StarRatingPill(rating = review.ratingOutOf5, liquidGlass = liquidGlass)
                }
            }
            HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                if (review.mediaImageUrl != null) {
                    val shape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier.fillMaxWidth().height(220.dp)
                            .then(if (liquidGlass) Modifier.glassPanel(true, tint = NeutralGlassTint, shape = shape) else Modifier.clip(shape))
                    ) {
                        AsyncImage(model = review.mediaImageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text(review.reviewText, color = Color.White.copy(0.92f), fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}
