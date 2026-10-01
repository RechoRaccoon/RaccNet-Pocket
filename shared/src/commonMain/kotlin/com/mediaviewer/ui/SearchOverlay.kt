package com.mediaviewer.ui

import androidx.compose.foundation.border

import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.MediaItem
import com.mediaviewer.model.SearchAccountResult
import com.mediaviewer.model.SearchFeedResult
import com.mediaviewer.model.SearchStarterPackResult
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap
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
    onOpenLikedPost: (Int) -> Unit = {},
    // Item 4: e621-style tag autocomplete/autocorrect suggestions for the
    // current in-progress word being typed (only really meaningful on the
    // Liked tab, where the vocabulary is the tagger's own fixed tag list —
    // see TagSuggestionProvider). Empty list = nothing to show.
    tagSuggestions: List<String> = emptyList(),
    onQueryChange: (String) -> Unit,
    // Item 2: Liked tab's query text updates live (so the field shows what
    // you're typing + suggestions can react to it) but must NOT re-run the
    // actual dataset search until Enter/search is pressed — unlike the
    // other tabs' onQueryChange, which both updates the field and searches
    // immediately. Kept separate from onQueryChange rather than branching
    // inside a single callback so each tab's contract stays simple/explicit
    // at the call site.
    onLikedQueryTextChange: (String) -> Unit = {},
    onLikedSearchSubmit: () -> Unit = {},
    // Item 14: the new "e621" filter option — only rendered in the filter
    // row when true (see the filter row below); reuses the Tagged tab's
    // exact same text-field/autocomplete wiring (onLikedQueryTextChange),
    // just with its own submit action.
    e621LoggedIn: Boolean = false,
    onE621SearchSubmit: () -> Unit = {},
    onTagSuggestionSelected: (String) -> Unit = {},
    onSelectFilter: (MainViewModel.SearchFilter) -> Unit,
    onOpenPost: (Int) -> Unit,
    onOpenAccount: (AuthorInfo) -> Unit,
    onAddFeed: (com.mediaviewer.model.SearchFeedResult) -> Unit = {},
    // Feeds / Starter Packs share the profile pages' Lists/Feeds rows.
    /** URIs of every feed already in your feeds list. */
    savedFeedUris: Set<String> = emptySet(),
    /** Busy/finished labels for the rows' buttons, by URI. */
    listActions: Map<String, String> = emptyMap(),
    /** A feed's "Add" / a starter pack's "Follow All" (after its confirm). */
    onListEntryAction: (com.mediaviewer.model.ProfileListEntry) -> Unit = {},
    /** A starter pack tapped: everyone in it. */
    onOpenListEntry: (com.mediaviewer.model.ProfileListEntry) -> Unit = {},
    onLoadMorePosts: () -> Unit = {},
    onClose: () -> Unit
) {
    // The bar "splits": the page opens looking exactly like the Hub's search
    // bar (field + round search button), then the field's left end separates
    // into a round back button — mirroring the search button on the right.
    // Closing plays it backwards, so the bar is whole again as the Hub
    // reappears.
    val split = remember { androidx.compose.animation.core.Animatable(0f) }
    val splitScope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        split.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 420f))
    }
    val closeWithMerge: () -> Unit = {
        if (!closing) {
            closing = true
            splitScope.launch {
                split.animateTo(0f, androidx.compose.animation.core.tween(150))
                onClose()
            }
        }
    }
    com.mediaviewer.ui.compat.BackHandler(onBack = closeWithMerge)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // If the Tagged tab disappears while it's selected (its dataset was just
    // deleted), fall back to the first tab instead of showing an empty page
    // under a tab that no longer exists.
    LaunchedEffect(hasTaggedDataset, state.filter) {
        if (!hasTaggedDataset && state.filter == MainViewModel.SearchFilter.LIKED_TAGS) {
            onSelectFilter(MainViewModel.SearchFilter.ACCOUNTS)
        }
    }

    // Item 8: same profile-color pattern as the Hub/DM inbox — falls back
    // to the shared neutral tint when there's no avatar yet.
    val profileTint = rememberSelfTint(selfAvatarUrl, NeutralGlassTint)
    // The starter pack waiting on its "Follow All?" confirmation.
    var pendingFollowAll by remember { mutableStateOf<com.mediaviewer.model.ProfileListEntry?>(null) }

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

    // Item 4: declared at this (outer Box) scope, not inside the Column
    // below, so both the search bar Row (which measures barBottomLeft/
    // barWidthPx) and the suggestions panel (a later sibling of the whole
    // Column, so it draws on top of it) can see them.
    val isLiked = state.filter == MainViewModel.SearchFilter.LIKED_TAGS
    // Item 14: e621 reuses the Tagged tab's text-field routing/autocomplete
    // verbatim (see the doc comment on the `e621LoggedIn`/
    // `onE621SearchSubmit` params above) — grouped with isLiked below
    // wherever that means "route through updateLikedQueryText instead of a
    // live per-keystroke query", but kept as its own flag wherever the two
    // filters' *submit* behavior differs (isLiked queries inline; e621
    // jumps straight to the feed).
    val isE621Filter = state.filter == MainViewModel.SearchFilter.E621
    val isTagInputFilter = isLiked || isE621Filter
    val showSuggestions = isTagInputFilter && tagSuggestions.isNotEmpty()
    var barBottomLeft by remember { mutableStateOf(Offset.Zero) }
    var barWidthPx by remember { mutableStateOf(0) }
    // Posts / Tagged: which content type is shown (profile-style sub-tabs)
    // and, per sub-tab, which layout (the interaction bar's Grid button).
    var postsKind by remember { mutableStateOf(PostKindFilter.ALL) }
    var taggedKind by remember { mutableStateOf(PostKindFilter.ALL) }
    val isPosts = state.filter == MainViewModel.SearchFilter.POSTS
    val kindForTab = if (isLiked) taggedKind else postsKind
    val gridScreen = if (isLiked) "search_tagged" else "search_posts"
    val submitSearch: () -> Unit = {
        when {
            isE621Filter -> onE621SearchSubmit()
            isLiked -> onLikedSearchSubmit()
            else -> onQueryChange(state.query)
        }
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
                    if (liquidGlass) Modifier.drawWithContent {
                        backdropLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(backdropLayer)
                    } else Modifier.background(OledBlack)
                )
        ) {
            // Dim profile color + stars, blurred by the glass on top.
            if (liquidGlass) SpaceSky(profileTint, Modifier.matchParentSize())
        }

        // The bar sits right under the camera cutout — the same line profile
        // banners (and the Hub's search bar) start on.
        Column(Modifier.fillMaxSize().padding(top = rememberTopCutoutClearance())) {

            // ── Bar: close bubble + round search field ──────────────────────
            // Item 4 (rework #2): back to an overlay for the suggestions
            // panel — per feedback, expanding this bubble in normal layout
            // flow pushed the filter row/results down instead of floating
            // over them. Styled to still read as one continuous bubble
            // though: zero gap between this row's field and the panel
            // below, matching width, and complementary corner rounding
            // (this field's bottom corners go square exactly when the
            // panel is showing beneath it, and the panel's top corners are
            // square to meet them) — so the seam is invisible even though
            // they're two separately-positioned elements.
            // Same size, spacing and position as the Hub's search bar (the
            // back button lives up in the camera row instead, see below).
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp)) {
                val p = split.value
                // Back: a round bubble the same size as the search button on
                // the right, sliding out of the field's left end.
                RoundBackButton(
                    liquidGlass = liquidGlass, tint = profileTint, backdrop = searchBackdrop, onClick = closeWithMerge,
                    size = 44.dp,
                    modifier = Modifier.align(Alignment.CenterStart).graphicsLayer {
                        val a = p.coerceIn(0f, 1f)
                        alpha = a
                        val sc = 0.55f + 0.45f * p
                        scaleX = sc; scaleY = sc
                        translationX = (1f - a) * 26.dp.toPx()
                    }
                )
            Row(
                Modifier.fillMaxSize().padding(start = 52.dp * p.coerceAtLeast(0f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val fieldShape = if (showSuggestions) {
                    RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
                } else {
                    RoundedCornerShape(22.dp)
                }
                @Composable
                fun SearchFieldContent() {
                    val haptic = LocalHapticFeedback.current
                    // Same icon size, text size and padding as the Hub's bar.
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = DimGray, modifier = Modifier.size(16.dp))
                        // Item 2: the Liked tab's text field routes through
                        // its own live-text-only/submit-on-search callbacks
                        // instead of onQueryChange, so typing doesn't
                        // re-query the dataset on every keystroke — see
                        // onLikedQueryTextChange/onLikedSearchSubmit's doc
                        // comments on MainViewModel.
                        BasicTextFieldWithPlaceholder(
                            value = state.query,
                            onValueChange = if (isTagInputFilter) onLikedQueryTextChange else onQueryChange,
                            // Item 1: just "Search" — no app-name text needed.
                            placeholder = "Search", focusRequester = focusRequester,
                            // Item 8: haptic tap when the keyboard's search
                            // action actually submits a query.
                            onSearch = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                submitSearch()
                            }
                        )
                    }
                }
                // The field is the very same glass bubble as the Hub's search
                // bar (no dimmed/opaque look). Only while the tag suggestions
                // are open does it switch to the opaque panel, so the field
                // and the dropdown under it read as one solid shape and the
                // suggestions never turn see-through.
                //
                // barBottomLeft/barWidthPx are measured from this field
                // itself, so the suggestions panel lines up with it.
                val fieldModifier = Modifier.weight(1f).height(44.dp)
                    .onGloballyPositioned {
                        val pos = it.positionInRoot()
                        barBottomLeft = Offset(pos.x, pos.y + it.size.height)
                        barWidthPx = it.size.width
                    }
                if (showSuggestions) {
                    Box(fieldModifier.opaqueMaskPanel(backdrop = searchBackdrop, tint = profileTint, shape = fieldShape)) { SearchFieldContent() }
                } else if (liquidGlass) {
                    LiquidGlassSurface(fieldModifier, shape = fieldShape, tint = profileTint, backdrop = searchBackdrop) { SearchFieldContent() }
                } else {
                    Box(fieldModifier.clip(fieldShape).background(Color.White.copy(0.06f))) { SearchFieldContent() }
                }
                // Round search button on the right — same as the Hub's.
                val searchTap = rememberHapticTap()
                @Composable
                fun SearchCircleContent() {
                    Box(Modifier.fillMaxSize().clickable { searchTap(); submitSearch() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                if (liquidGlass) {
                    LiquidGlassSurface(Modifier.size(44.dp), shape = CircleShape, tint = profileTint, backdrop = searchBackdrop) { SearchCircleContent() }
                } else {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(0.06f))) { SearchCircleContent() }
                }
            }
            }

            Spacer(Modifier.height(10.dp))

            // ── Filter row ────────────────────────────────────────────────
            // Roadmap: restyled to match the Hub's "Feeds" row — rounder,
            // compact, horizontally scrollable pills that float over the
            // live backdrop, instead of a fixed SpaceEvenly row between two
            // dividers.
            // Main tabs — the same big pills as a profile's tab row, framed
            // by the same thin dividers.
            val tabs = MainViewModel.SearchFilter.entries.filter { filter ->
                // Item 14: e621 filter only shows once logged into e621.
                !(filter == MainViewModel.SearchFilter.E621 && !e621LoggedIn) &&
                    // The Tagged tab only exists once there's something tagged to search.
                    !(filter == MainViewModel.SearchFilter.LIKED_TAGS && !hasTaggedDataset)
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
            ProfileStyleTabRow(
                labels = tabs.map { it.label() }, selectedIndex = tabs.indexOf(state.filter),
                liquidGlass = liquidGlass, tint = profileTint, onSelect = { onSelectFilter(tabs[it]) }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
            // Content-type sub-tabs — Posts and Tagged only.
            if (isPosts && state.posts.isNotEmpty()) {
                PostKindSubTabRow(state.posts, postsKind, liquidGlass, profileTint, showAll = true) { postsKind = it }
            } else if (isLiked && hasTaggedDataset && likedTagResults.isNotEmpty()) {
                PostKindSubTabRow(likedTagResults, taggedKind, liquidGlass, profileTint) { taggedKind = it }
            }

            // ── Results ──────────────────────────────────────────────────
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    // Bug fix: this used to sit below the generic
                    // `!state.hasSearched` fallback branch, which intercepts
                    // first when the Liked tab is opened fresh (hasSearched
                    // starts false) — so the "Start Tagging" card/message
                    // never actually rendered, no matter the dataset state.
                    // Checking the LIKED_TAGS filter before that generic
                    // fallback fixes it.
                    state.filter == MainViewModel.SearchFilter.LIKED_TAGS -> {
                        if (!hasTaggedDataset) {
                            EmptyResultsText()
                        } else if (state.loading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                            }
                        } else if (likedTagResults.isEmpty()) EmptyResultsText() else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
                                sharedPostResults(
                                    items = likedTagResults, loading = false, filter = taggedKind,
                                    gridMode = resultsGridMode(gridScreen, taggedKind), tint = profileTint, liquidGlass = liquidGlass,
                                    onTapItem = { item -> likedTagResults.indexOf(item).takeIf { it >= 0 }?.let(onOpenLikedPost) }
                                )
                            }
                        }
                    }
                    // Item 14: e621 never shows inline results here — typing
                    // a tag and hitting the keyboard's search key jumps
                    // straight to the e621 feed (see onE621SearchSubmit).
                    isE621Filter -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Type e621 tags, then hit search", color = DimGray, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                    }
                    // People tab before searching: the accounts you searched
                    // for and opened recently.
                    state.filter == MainViewModel.SearchFilter.ACCOUNTS && state.query.isBlank() -> {
                        RecentAccountSearchesList(
                            liquidGlass = liquidGlass, backdrop = searchBackdrop,
                            onOpen = { author -> com.mediaviewer.util.RecentAccountSearches.record(author); onOpenAccount(author) }
                        )
                    }
                    !state.hasSearched -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Search posts, accounts, and starter packs", color = DimGray, fontSize = 13.sp)
                    }
                    state.filter == MainViewModel.SearchFilter.FEEDS -> {
                        if (state.feeds.isEmpty()) EmptyResultsText() else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
                                items(state.feeds, key = { it.uri }) { feed ->
                                    val entry = remember(feed) {
                                        com.mediaviewer.model.ProfileListEntry(
                                            kind = com.mediaviewer.model.ProfileListKind.FEED, uri = feed.uri,
                                            name = feed.displayName.ifBlank { "Feed" },
                                            description = listOfNotNull(
                                                feed.creatorHandle.takeIf { it.isNotBlank() }?.let { "by @$it" },
                                                feed.description?.takeIf { it.isNotBlank() }
                                            ).joinToString(" · ").ifBlank { null },
                                            avatarUrl = feed.avatarUrl
                                        )
                                    }
                                    val saved = feed.uri in savedFeedUris
                                    ProfileListRow(
                                        entry = entry, liquidGlass = liquidGlass, tint = profileTint,
                                        label = if (saved) "Added" else "Add", busy = false, done = saved,
                                        onOpen = null,
                                        onAction = { onListEntryAction(entry) }
                                    )
                                }
                            }
                        }
                    }
                    state.filter == MainViewModel.SearchFilter.POSTS -> {
                        if (state.posts.isEmpty()) EmptyResultsText() else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
                                sharedPostResults(
                                    items = state.posts, loading = state.loadingMorePosts, filter = postsKind,
                                    gridMode = resultsGridMode(gridScreen, postsKind), tint = profileTint, liquidGlass = liquidGlass,
                                    onTapItem = { item -> state.posts.indexOf(item).takeIf { it >= 0 }?.let(onOpenPost) },
                                    // More results as you scroll, like every other feed.
                                    onLoadMore = onLoadMorePosts,
                                    exhausted = state.postsCursor == null
                                )
                            }
                        }
                    }
                    state.filter == MainViewModel.SearchFilter.ACCOUNTS -> {
                        if (state.accounts.isEmpty()) EmptyResultsText() else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(state.accounts, key = { it.author.did }) { result ->
                                    AccountResultRow(
                                        author = result.author, description = result.description, isFollowing = result.isFollowing,
                                        liquidGlass = liquidGlass, backdrop = searchBackdrop,
                                        onClick = {
                                            com.mediaviewer.util.RecentAccountSearches.record(result.author)
                                            onOpenAccount(result.author)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    state.filter == MainViewModel.SearchFilter.STARTER_PACKS -> {
                        if (state.starterPacks.isEmpty()) EmptyResultsText() else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
                                items(state.starterPacks, key = { it.uri }) { pack ->
                                    val entry = remember(pack) {
                                        com.mediaviewer.model.ProfileListEntry(
                                            kind = com.mediaviewer.model.ProfileListKind.STARTER_PACK, uri = pack.uri,
                                            name = pack.name.ifBlank { "Starter Pack" },
                                            description = listOfNotNull(
                                                "by @${pack.creator.handle}", "${pack.joinedCount} joined",
                                                pack.description?.takeIf { it.isNotBlank() }
                                            ).joinToString(" · "),
                                            avatarUrl = pack.creator.avatarUrl, listUri = pack.listUri
                                        )
                                    }
                                    val action = listActions[pack.uri]
                                    ProfileListRow(
                                        entry = entry, liquidGlass = liquidGlass, tint = profileTint,
                                        label = action ?: "Follow All",
                                        busy = action != null && action != "Followed",
                                        done = action == "Followed" || pack.listUri == null,
                                        onOpen = if (pack.listUri != null) ({ onOpenListEntry(entry) }) else null,
                                        // Following a whole pack asks first.
                                        onAction = { pendingFollowAll = entry }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Profile-style interaction bar, trimmed to Refresh + Grid layout.
        if (!isE621Filter && !(isLiked && !hasTaggedDataset)) {
            ResultsInteractionBar(
                liquidGlass = liquidGlass, tint = profileTint, backdrop = searchBackdrop,
                refreshing = state.loading, animateRefresh = true,
                onRefresh = { if (state.query.isNotBlank() || isLiked) submitSearch() },
                filter = if (isPosts || isLiked) kindForTab else null,
                gridMode = resultsGridMode(gridScreen, kindForTab),
                onGrid = { cycleResultsGridMode(gridScreen, kindForTab) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // (The back button is part of the search bar now — see the top.)

        // Item 4: the suggestions panel — a later sibling of the Column
        // above (so it draws on top of the filter row and results grid,
        // floating over them rather than pushing them down), positioned
        // from the search bar's own tracked bottom-left corner so it
        // always sits directly under the field regardless of tab or
        // scroll state. Only ever populated (by MainViewModel.
        // updateLikedQueryText) while the Liked tab is active and there's
        // an in-progress word to suggest for; hitting space clears the
        // in-progress word, which clears this list, which closes the
        // panel — no separate "dismiss" logic needed.
        if (showSuggestions) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val suggestionsShape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
            Box(
                Modifier
                    .offset { with(density) { androidx.compose.ui.unit.IntOffset(barBottomLeft.x.toInt(), barBottomLeft.y.toInt()) } }
                    .width(with(density) { barWidthPx.toDp() })
                    .opaqueMaskPanel(backdrop = searchBackdrop, tint = profileTint, shape = suggestionsShape)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    tagSuggestions.forEachIndexed { index, suggestion ->
                        if (index > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                        Text(
                            suggestion, color = Color.White, fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTagSuggestionSelected(suggestion) }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }

        pendingFollowAll?.let { entry ->
            ConfirmPopup(
                title = "Follow All?",
                message = "Follow every account in \"${entry.name}\"?",
                confirmLabel = "Follow All",
                liquidGlass = liquidGlass, tint = profileTint, backdrop = searchBackdrop,
                onConfirm = { pendingFollowAll = null; onListEntryAction(entry) },
                onDismiss = { pendingFollowAll = null },
                preview = entry.avatarUrl,
                destructive = false
            )
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
    MainViewModel.SearchFilter.LIKED_TAGS    -> "Tagged"
    MainViewModel.SearchFilter.FEEDS         -> "Feeds"
    MainViewModel.SearchFilter.STARTER_PACKS -> "Starter Packs"
    MainViewModel.SearchFilter.E621          -> "e621"
}

@Composable
private fun SearchPostCell(item: MediaItem, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    // "I Hate Fun": NSFW-labeled results are blurred here too.
    val blurNsfw = LocalHateFunBlurNsfw.current && item.isNsfwLabeled
    Box(
        Modifier.aspectRatio(1f).clip(androidx.compose.ui.graphics.RectangleShape)
            .then(if (blurNsfw) Modifier.blur(60.dp) else Modifier)
            .clickable(onClick = { tap(); onClick() })
    ) {
        if (item.isEmojiTextshot) {
            Box(Modifier.fillMaxSize().background(OffBlack)) {
                TextshotEmojiImage(item.textshotImageUrl, cornerRadius = 0.dp, modifier = Modifier.fillMaxSize())
            }
        } else if (item.isTextOnly) {
            // The uploader's own color, dimmed, behind their text.
            val authorTint = if (item.author.did.isNotBlank()) rememberAuthorProfileTint(item.author.did, item.author.avatarUrl) else Color.Black
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.lerp(OffBlack, authorTint, 0.35f)).padding(6.dp), contentAlignment = Alignment.Center) {
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

/** One account in the People results / Recent Searches: its own compact
 *  glass bubble in that account's profile color — avatar ringed in the
 *  same color, name, handle, a line of their bio, and "Following" when
 *  you follow them. [onRemove] adds a small X (Recent Searches). */
@Composable
private fun AccountResultRow(
    author: AuthorInfo,
    description: String?,
    isFollowing: Boolean,
    liquidGlass: Boolean,
    backdrop: GlassBackdrop?,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    val tap = rememberHapticTap()
    val color = rememberAuthorProfileTint(author.did, author.avatarUrl)
    val accent = vividAccent(color)
    val shape = RoundedCornerShape(22.dp)
    val m = Modifier.fillMaxWidth().clip(shape).clickable(onClick = { tap(); onClick() })
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .border(1.5.dp, accent.copy(alpha = 0.85f), CircleShape)
                    .padding(2.dp).clip(CircleShape).background(Color.White.copy(0.1f))
            ) {
                if (author.avatarUrl != null) {
                    AsyncImage(model = author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.Person, contentDescription = null, tint = DimGray, modifier = Modifier.align(Alignment.Center).size(22.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(author.displayName.ifBlank { author.handle }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${author.handle}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!description.isNullOrBlank()) {
                    Text(description.replace('\n', ' '), color = Color.White.copy(0.78f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                }
            }
            if (isFollowing) {
                Text(
                    "Following", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.28f))
                        .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            if (onRemove != null) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f))
                        .clickable { tap(); onRemove() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Close, contentDescription = "Remove from recent searches", tint = Color.White, modifier = Modifier.size(15.dp)) }
            }
        }
    }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = m, shape = shape, tint = color, backdrop = backdrop) { content() }
    } else {
        Box(m.background(androidx.compose.ui.graphics.lerp(OffBlack, color, 0.18f))) { content() }
    }
}

/** People tab before any search: "Recent Searches" — accounts you
 *  searched for and opened, newest first (kept on this device). */
@Composable
private fun RecentAccountSearchesList(liquidGlass: Boolean, backdrop: GlassBackdrop?, onOpen: (AuthorInfo) -> Unit) {
    val context = com.mediaviewer.ui.compat.LocalContext.current
    remember { com.mediaviewer.util.RecentAccountSearches.ensureLoaded(context); true }
    val recents = com.mediaviewer.util.RecentAccountSearches.accounts
    if (recents.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Search posts, accounts, and starter packs", color = DimGray, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "recent_header") {
            Text(
                "Recent Searches", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 4.dp)
            )
        }
        items(recents, key = { it.did }) { author ->
            AccountResultRow(
                author = author, description = null, isFollowing = false,
                liquidGlass = liquidGlass, backdrop = backdrop,
                onClick = { onOpen(author) },
                onRemove = { com.mediaviewer.util.RecentAccountSearches.remove(author.did) }
            )
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
    val tap = rememberHapticTap()
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
            modifier = Modifier.clickable(onClick = { tap(); onClick() }),
            shape = shape, tint = if (active) tint else tint.copy(alpha = 0.4f), backdrop = backdrop
        ) {
            Box(Modifier.padding(horizontal = 9.dp, vertical = 4.dp)) { ChipLabel() }
        }
    } else {
        Box(
            Modifier.clip(shape).background(if (active) Color.White.copy(0.15f) else Color.White.copy(0.06f))
                .clickable(onClick = { tap(); onClick() }).padding(horizontal = 9.dp, vertical = 4.dp)
        ) { ChipLabel() }
    }
}

/** Matches ProfileOverlay's CloseGlassBubble exactly (30dp glass circle,
 *  Close icon) — that one is private to ProfileOverlay.kt and hard-coded to
 *  "Close profile" content description, so it's mirrored here rather than
 *  reused. */
@Composable
private fun SearchCloseBubble(liquidGlass: Boolean, tint: Color = NeutralGlassTint, backdrop: GlassBackdrop? = null, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    val shape = CircleShape
    if (liquidGlass) {
        LiquidGlassSurface(modifier = Modifier.size(30.dp).clickable(onClick = { tap(); onClick() }), shape = shape, tint = tint, backdrop = backdrop) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    } else {
        Box(Modifier.size(30.dp).clip(shape).background(Color.White.copy(0.14f)).clickable(onClick = { tap(); onClick() }), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(16.dp))
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
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
        )
        if (value.isEmpty()) {
            Text(placeholder, color = DimGray, fontSize = 13.sp)
        }
    }
}
