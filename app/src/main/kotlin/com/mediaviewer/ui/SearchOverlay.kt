package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.MediaItem
import com.mediaviewer.model.SearchAccountResult
import com.mediaviewer.model.SearchFeedResult
import com.mediaviewer.model.SearchStarterPackResult
import com.mediaviewer.ui.theme.*
import com.mediaviewer.viewmodel.MainViewModel

/** Item 7: full-screen search — round search bar, Posts/Accounts/Lists/
 *  Starter Packs filter row, and grid/list results, with a top-left X
 *  matching the profile page's close button style (see CloseGlassBubble in
 *  ProfileOverlay.kt — mirrored here rather than imported since that one's
 *  private and hard-coded to "Close profile" semantics). */
@Composable
fun SearchOverlay(
    state: MainViewModel.SearchState,
    liquidGlass: Boolean,
    // Item 8: the logged-in user's own avatar, so this page's background
    // and glass surfaces reflect their profile color — same pattern the
    // Hub (SettingsSheet's `dominantColor` shadow) and DM inbox use.
    selfAvatarUrl: String? = null,
    // AI Tagging feature: the "Liked" tab's whole tab content depends on
    // whether an initial tagging pass has ever completed (hasTaggedDataset)
    // — before that it's just the explainer card + "Start Tagging" button;
    // after, it's a normal tag search reading from likedTagResults.
    hasTaggedDataset: Boolean = false,
    likedTagResults: List<MediaItem> = emptyList(),
    onStartTagging: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onSelectFilter: (MainViewModel.SearchFilter) -> Unit,
    onOpenPost: (Int) -> Unit,
    onOpenAccount: (AuthorInfo) -> Unit,
    onAddFeed: (com.mediaviewer.model.SearchFeedResult) -> Unit = {},
    onClose: () -> Unit
) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Item 8: same profile-color pattern as the Hub/DM inbox — falls back
    // to the shared neutral tint when there's no avatar yet.
    val profileTint = if (selfAvatarUrl != null) rememberDominantColor(selfAvatarUrl) else NeutralGlassTint

    // Bug fix/roadmap: the search bar, its buttons, and the filter row now
    // float directly over this page's own background gradient — sampling it
    // live, the same way the main feed's glass buttons sample a live
    // recording of the post behind them — instead of sitting on a flat
    // rectangular fill. The background gradient is recorded into its own
    // GraphicsLayer by the bottom-most Box below (which draws nothing else),
    // and every glass piece above it reads that recording as its `backdrop`.
    val backdropLayer = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    val searchBackdrop = remember(liquidGlass, backdropLayer) {
        if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
    }

    Box(
        Modifier.fillMaxSize()
            // Bug fix: claim pointer input over the whole overlay so taps on
            // dead space (Spacers, plain Text/dividers with no click handler
            // of their own) can't fall through to the feed/Hub still
            // composed behind this overlay — see blockClicksBehind() in
            // GlassTheme.kt for the full explanation.
            .blockClicksBehind()
    ) {
        // Background layer only — recorded as-is into backdropLayer every
        // frame, with nothing else drawn inside it, so the glass pieces
        // sitting on top of it (as separate siblings below) can sample it
        // without recording themselves into their own reflection.
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                .then(
                    if (liquidGlass) Modifier.background(postBackgroundBrush(profileTint)).drawWithContent {
                        backdropLayer.record { this@drawWithContent.drawContent() }
                        drawContent()
                    } else Modifier.background(OledBlack)
                )
        )

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Spacer(Modifier.height(16.dp))

            // ── Bar: close bubble + round search field ──────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SearchCloseBubble(liquidGlass = liquidGlass, tint = profileTint, backdrop = searchBackdrop, onClick = onClose)
                val fieldShape = RoundedCornerShape(24.dp)
                @Composable
                fun SearchFieldContent() {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = DimGray, modifier = Modifier.size(18.dp))
                        BasicTextFieldWithPlaceholder(
                            value = state.query, onValueChange = onQueryChange,
                            // Item 1: just "Search" — no app-name text needed.
                            placeholder = "Search", focusRequester = focusRequester,
                            onSearch = { onQueryChange(state.query) }
                        )
                    }
                }
                if (liquidGlass) {
                    LiquidGlassSurface(Modifier.weight(1f).height(44.dp), shape = fieldShape, tint = profileTint, backdrop = searchBackdrop) { SearchFieldContent() }
                } else {
                    Box(Modifier.weight(1f).height(44.dp).clip(fieldShape).background(Color.White.copy(0.08f))) { SearchFieldContent() }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Filter row ────────────────────────────────────────────────
            // Roadmap: restyled to match the Hub's "Feeds" row — rounder,
            // compact, horizontally scrollable pills that float over the
            // live backdrop, instead of a fixed SpaceEvenly row between two
            // dividers.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MainViewModel.SearchFilter.entries.forEach { filter ->
                    FilterChip(label = filter.label(), active = state.filter == filter, liquidGlass = liquidGlass, tint = profileTint, backdrop = searchBackdrop) { onSelectFilter(filter) }
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── Results ──────────────────────────────────────────────────
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                    }
                    !state.hasSearched -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Search posts, accounts, and starter packs", color = DimGray, fontSize = 13.sp)
                    }
                    state.filter == MainViewModel.SearchFilter.FEEDS -> {
                        if (state.feeds.isEmpty()) EmptyResultsText() else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                                items(state.feeds, key = { it.uri }) { feed ->
                                    FeedResultRow(feed = feed, liquidGlass = liquidGlass, onAdd = { onAddFeed(feed) })
                                }
                            }
                        }
                    }
                    state.filter == MainViewModel.SearchFilter.POSTS -> {
                        if (state.posts.isEmpty()) EmptyResultsText() else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(0.dp),
                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(state.posts, key = { i, item -> item.id + "_$i" }) { index, item ->
                                    SearchPostCell(item = item, onClick = { onOpenPost(index) })
                                }
                            }
                        }
                    }
                    state.filter == MainViewModel.SearchFilter.LIKED_TAGS -> {
                        if (!hasTaggedDataset) {
                            LikedTagsSetupPrompt(liquidGlass = liquidGlass, tint = profileTint, backdrop = searchBackdrop, onStartTagging = onStartTagging)
                        } else if (state.query.isBlank()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Search your liked posts by tag", color = DimGray, fontSize = 13.sp)
                            }
                        } else if (likedTagResults.isEmpty()) EmptyResultsText() else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(0.dp),
                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(likedTagResults, key = { i, item -> item.id + "_$i" }) { index, item ->
                                    SearchPostCell(item = item, onClick = { onOpenPost(index) })
                                }
                            }
                        }
                    }
                    state.filter == MainViewModel.SearchFilter.ACCOUNTS -> {
                        if (state.accounts.isEmpty()) EmptyResultsText() else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                                items(state.accounts, key = { it.author.did }) { result ->
                                    AccountResultRow(result = result, liquidGlass = liquidGlass, onClick = { onOpenAccount(result.author) })
                                }
                            }
                        }
                    }
                    state.filter == MainViewModel.SearchFilter.STARTER_PACKS -> {
                        if (state.starterPacks.isEmpty()) EmptyResultsText() else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                                items(state.starterPacks, key = { it.uri }) { pack ->
                                    StarterPackResultRow(pack = pack, liquidGlass = liquidGlass)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** AI Tagging feature: the "Liked" tab's pre-setup state — glass card with
 *  the explainer copy plus a "Start Tagging" button, per the request. Sits
 *  centered in the results area, same as the other tabs' empty states. */
@Composable
private fun LikedTagsSetupPrompt(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, onStartTagging: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val cardShape = RoundedCornerShape(20.dp)
        @Composable
        fun CardContent() {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Before you can search through your liked posts, click the button below so RaccNet Pocket can start locally tagging your liked posts with an on-device model for an enhanced searching experience.",
                    color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 18.sp
                )
                Spacer(Modifier.height(16.dp))
                val buttonShape = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .then(if (liquidGlass) Modifier.glassPanel(true, shape = buttonShape, tint = tint) else Modifier.clip(buttonShape).background(tint.copy(alpha = 0.35f)))
                        .clickable(onClick = onStartTagging)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text("Start Tagging", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(Modifier.fillMaxWidth(0.85f), shape = cardShape, tint = tint, backdrop = backdrop) { CardContent() }
        } else {
            Box(Modifier.fillMaxWidth(0.85f).clip(cardShape).background(Color.White.copy(0.06f))) { CardContent() }
        }
    }
}

@Composable
private fun EmptyResultsText() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No results", color = DimGray, fontSize = 13.sp)
    }
}

private fun MainViewModel.SearchFilter.label(): String = when (this) {
    MainViewModel.SearchFilter.ACCOUNTS      -> "People"
    MainViewModel.SearchFilter.POSTS         -> "Posts"
    MainViewModel.SearchFilter.LIKED_TAGS    -> "Liked"
    MainViewModel.SearchFilter.FEEDS         -> "Feeds"
    MainViewModel.SearchFilter.STARTER_PACKS -> "Starter Packs"
}

@Composable
private fun SearchPostCell(item: MediaItem, onClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).clickable(onClick = onClick)) {
        if (item.isTextOnly) {
            Box(Modifier.fillMaxSize().background(OffBlack).padding(6.dp), contentAlignment = Alignment.Center) {
                Text(item.text, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        } else {
            AsyncImage(
                model = item.thumbUrl.ifBlank { item.mediaUrl }, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun AccountResultRow(result: SearchAccountResult, liquidGlass: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(0.1f))) {
            if (result.author.avatarUrl != null) {
                AsyncImage(model = result.author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.Person, contentDescription = null, tint = DimGray, modifier = Modifier.align(Alignment.Center).size(22.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(result.author.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${result.author.handle}", color = DimGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!result.description.isNullOrBlank()) {
                Text(result.description, color = Color.White.copy(0.7f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (result.isFollowing) {
            Text("Following", color = VoteGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StarterPackResultRow(pack: SearchStarterPackResult, liquidGlass: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(0.1f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Group, contentDescription = null, tint = DimGray, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(pack.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("by @${pack.creator.handle} · ${pack.joinedCount} joined", color = DimGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!pack.description.isNullOrBlank()) {
                Text(pack.description, color = Color.White.copy(0.7f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Search page's Feeds filter — a discoverable feed generator with an "Add"
 *  button (writes it to the user's saved feeds, see MainViewModel.
 *  addSavedFeedFromSearch) instead of a click-to-open row, since these
 *  aren't things you "view", you subscribe to them. */
@Composable
private fun FeedResultRow(feed: SearchFeedResult, liquidGlass: Boolean, onAdd: () -> Unit) {
    var added by remember(feed.uri) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(0.1f)), contentAlignment = Alignment.Center) {
            if (feed.avatarUrl != null) {
                AsyncImage(model = feed.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.RssFeed, contentDescription = null, tint = DimGray, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(feed.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (feed.creatorHandle.isNotBlank()) {
                Text("by @${feed.creatorHandle}", color = DimGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!feed.description.isNullOrBlank()) {
                Text(feed.description, color = Color.White.copy(0.7f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        val pillShape = RoundedCornerShape(14.dp)
        Box(
            Modifier
                .then(if (liquidGlass) Modifier.glassPanel(true, shape = pillShape) else Modifier.clip(pillShape).background(Color.White.copy(0.1f)))
                .clickable(enabled = !added) { added = true; onAdd() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(if (added) "Added" else "Add", color = if (added) VoteGreen else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// Roadmap: same rounded-pill treatment as the Hub's FeedChip — a Box/Row
// with a proper pill shape and glass rim, rather than plain Text with a
// small-radius background, so this filter row visually matches the Hub's
// "Feeds" row style the person asked for.
// Item 7: restyled to match the profile pages' own sub-filter chips
// (ProfileSubFilterRow in ProfileOverlay.kt — e.g. the Media tab's All/
// Images/Videos row) instead of the bigger, more prominent tab-style pills
// this used before: smaller corner radius, smaller/tighter text, and a
// lower-alpha tint on the unselected state, so Search reads as visually
// consistent with the rest of the app's filter rows rather than a heavier,
// one-off treatment.
@Composable
private fun FilterChip(label: String, active: Boolean, liquidGlass: Boolean, tint: Color = NeutralGlassTint, backdrop: GlassBackdrop? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    @Composable
    fun ChipLabel() {
        Text(
            label, color = if (active) Color.White else DimGray, fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
    if (liquidGlass) {
        LiquidGlassSurface(
            modifier = Modifier.clickable(onClick = onClick),
            shape = shape, tint = if (active) tint else tint.copy(alpha = 0.4f), backdrop = backdrop
        ) {
            Box(Modifier.padding(horizontal = 9.dp, vertical = 4.dp)) { ChipLabel() }
        }
    } else {
        Box(
            Modifier.clip(shape).background(if (active) Color.White.copy(0.15f) else Color.White.copy(0.06f))
                .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 4.dp)
        ) { ChipLabel() }
    }
}

/** Matches ProfileOverlay's CloseGlassBubble exactly (30dp glass circle,
 *  Close icon) — that one is private to ProfileOverlay.kt and hard-coded to
 *  "Close profile" content description, so it's mirrored here rather than
 *  reused. */
@Composable
private fun SearchCloseBubble(liquidGlass: Boolean, tint: Color = NeutralGlassTint, backdrop: GlassBackdrop? = null, onClick: () -> Unit) {
    val shape = CircleShape
    if (liquidGlass) {
        LiquidGlassSurface(modifier = Modifier.size(30.dp).clickable(onClick = onClick), shape = shape, tint = tint, backdrop = backdrop) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, contentDescription = "Close search", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    } else {
        Box(Modifier.size(30.dp).clip(shape).background(Color.White.copy(0.14f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, contentDescription = "Close search", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BasicTextFieldWithPlaceholder(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    onSearch: () -> Unit
) {
    Box(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
        )
        if (value.isEmpty()) {
            Text(placeholder, color = DimGray, fontSize = 15.sp)
        }
    }
}
