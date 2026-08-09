package com.mediaviewer.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.LeafletBlog
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
    onPinchOut: () -> Unit
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

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

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
                            onPinchOut()
                        }
                    }
                }
            }
    ) {
        LazyColumn(
            state = listState,
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
                    ProfileTabsRow(
                        tabs = MainViewModel.ProfileTab.entries.filter { it in state.availableTabs },
                        selected = state.selectedTab,
                        liquidGlass = liquidGlass,
                        tint = blended,
                        onSelect = onSelectTab
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                }
            }

            profileResultsContent(
                state = state,
                liquidGlass = liquidGlass,
                profileTint = blended,
                onLoadMore = onLoadMore,
                onTapItem = onTapItem,
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
            BlogDetailOverlay(blog = blog, author = author, liquidGlass = liquidGlass, onClose = onCloseBlog)
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
    modifier: Modifier = Modifier, backdrop: GlassBackdrop? = null
) {
    val shape = RoundedCornerShape(14.dp)
    @Composable
    fun Label() {
        Text(text, color = Color.White, fontSize = fontSize, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = modifier, shape = shape, tint = tint, backdrop = backdrop) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) { Label() }
        }
    } else {
        Box(
            modifier.clip(shape).background(Color.Black.copy(0.55f)).padding(horizontal = 12.dp, vertical = 6.dp),
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
    onLoadMore: () -> Unit,
    onTapItem: (Int) -> Unit,
    onOpenBlog: (LeafletBlog) -> Unit,
    onOpenReview: (PopfeedReview) -> Unit
) {
    val tabState = state.tabStates[state.selectedTab]

    when (state.selectedTab) {
        MainViewModel.ProfileTab.MEDIA, MainViewModel.ProfileTab.REPOSTS, MainViewModel.ProfileTab.LIKES -> {
            profileMediaGridRows(
                items = tabState?.items ?: emptyList(),
                loading = tabState?.loading == true,
                onTapItem = onTapItem, onLoadMore = onLoadMore
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
                BlogBubble(blog = blog, liquidGlass = liquidGlass, tint = profileTint, onOpenBlog = onOpenBlog,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        MainViewModel.ProfileTab.REVIEWS -> {
            items(tabState?.reviews ?: emptyList(), key = { "review_${it.uri}" }) { review ->
                ReviewRow(review = review, liquidGlass = liquidGlass, onOpenReview = onOpenReview,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        MainViewModel.ProfileTab.BACKLOG -> {
            profileBacklogGridRows(items = tabState?.backlog ?: emptyList(), liquidGlass = liquidGlass)
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
    items: List<MediaItem>, loading: Boolean, onTapItem: (Int) -> Unit, onLoadMore: () -> Unit
) {
    val flattened = items.mapIndexed { postIndex, item ->
        if (item.mediaGroup.size > 1) item.mediaGroup.map { img -> postIndex to img.thumbUrl.ifBlank { img.mediaUrl } }
        else listOf(postIndex to item.thumbUrl.ifBlank { item.mediaUrl })
    }.flatten()
    val rows = flattened.chunked(3)

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

@Composable
private fun BlogBubble(blog: LeafletBlog, liquidGlass: Boolean, tint: Color, onOpenBlog: (LeafletBlog) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                else Modifier.clip(shape).background(Color.White.copy(0.06f))
            )
            .clickable { onOpenBlog(blog) }
            .padding(16.dp)
    ) {
        Text(blog.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
private fun BlogDetailOverlay(blog: LeafletBlog, author: AuthorInfo, liquidGlass: Boolean, onClose: () -> Unit) {
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
                CloseGlassBubble(liquidGlass = liquidGlass, tint = NeutralGlassTint, onClick = onClose)
                Spacer(Modifier.width(10.dp))
                ProfileGlassPill(text = blog.title, liquidGlass = liquidGlass, tint = NeutralGlassTint, fontSize = 15.sp, bold = true)
            }
            ByAndDateRow(
                author = author, createdAt = blog.createdAt, liquidGlass = liquidGlass, tint = NeutralGlassTint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)
            // Horizontal padding matches the 12dp used by the header row
            // above so the body text's edges line up with the buttons.
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 20.dp)) {
                Text(blog.bodyText.ifBlank { "This blog has no readable text content." },
                    color = Color.White.copy(0.92f), fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(40.dp))
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

@Composable
private fun StarRatingPill(rating: Float, liquidGlass: Boolean, tint: Color = NeutralGlassTint, modifier: Modifier = Modifier) {
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
