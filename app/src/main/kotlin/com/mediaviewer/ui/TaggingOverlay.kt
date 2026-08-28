package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mediaviewer.tagging.TaggerModelManager
import com.mediaviewer.ui.theme.*
import com.mediaviewer.viewmodel.MainViewModel
import kotlin.math.roundToInt

/** AI Tagging feature: the full-screen overlay shown while "Locally Tag All
 *  Liked Posts" runs — per the request, this doesn't go away until tagging
 *  is complete (its own BackHandler swallows the system back gesture rather
 *  than letting it dismiss mid-run; tapping the close bubble still lets the
 *  person cancel explicitly, same as e.g. the download-all-liked flow
 *  elsewhere in the app already allows).
 *
 *  Uses the same profile-color-reflected background gradient as every other
 *  full-screen page (Search, Hub, DM inbox) — see rememberDominantColor/
 *  postBackgroundBrush in GlassTheme.kt.
 *
 *  Redesign (item 3, this session): while a post is actively being tagged,
 *  instead of a generic centered loading card this now shows that post
 *  full-screen with a live "Tagging…"/"Tagged N Posts" header — see
 *  ActiveTaggingView below. The other states (downloading the model,
 *  finished, failed — none of which have a "current post" to show) keep the
 *  original centered glass card. */
@Composable
fun TaggingOverlay(
    state: MainViewModel.TaggingUiState,
    liquidGlass: Boolean,
    selfAvatarUrl: String? = null,
    onDismiss: () -> Unit,
    onSearchLiked: () -> Unit
) {
    // Only intercept back to *cancel* an in-flight run, asking for the tap
    // that already exists (the close bubble) rather than letting a stray
    // back-gesture silently kill a long-running pass; once the run is
    // finished there's nothing left to protect, so back behaves normally.
    if (state.isRunning) {
        androidx.activity.compose.BackHandler(onBack = {})
    } else {
        androidx.activity.compose.BackHandler(onBack = onDismiss)
    }

    val profileTint = if (selfAvatarUrl != null) rememberDominantColor(selfAvatarUrl) else NeutralGlassTint

    val backdropLayer = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    val backdrop = remember(liquidGlass, backdropLayer) {
        if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
    }

    Box(Modifier.fillMaxSize().blockClicksBehind()) {
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

        val modelState = state.modelState
        val currentItem = state.currentItem
        when {
            state.errorMessage != null -> {
                CenteredCard(liquidGlass, profileTint, backdrop) {
                    Text("Tagging Failed", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(state.errorMessage, color = DimGray, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
            state.isComplete -> {
                CenteredCard(liquidGlass, profileTint, backdrop) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(VoteGreen.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = VoteGreen, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Tagging Complete", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("${state.tagged} of ${state.scanned} liked posts tagged · ${formatBytes(state.datasetBytes)}", color = DimGray, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    DismissButton(liquidGlass, profileTint, "Search Liked Posts", onSearchLiked)
                }
            }
            modelState is TaggerModelManager.State.Downloading -> {
                CenteredCard(liquidGlass, profileTint, backdrop) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("Downloading Tagging Model", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    val total = modelState.totalBytes
                    val subtitle = if (total > 0) "${formatBytes(modelState.bytesDownloaded)} of ${formatBytes(total)}" else "Starting…"
                    Text(subtitle, color = DimGray, fontSize = 12.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("One-time download — the model stays on your device from now on", color = DimGray, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            }
            state.isRunning && currentItem != null -> {
                ActiveTaggingView(item = currentItem, state = state, liquidGlass = liquidGlass, tint = profileTint, backdrop = backdrop)
            }
            else -> {
                // Running, but the very first post hasn't come back from the
                // network/decoder yet — or not running yet at all (opening
                // transition). Nothing to show full-screen, so this is the
                // one moment that still falls back to a simple spinner.
                CenteredCard(liquidGlass, profileTint, backdrop) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Tagging Liked Posts…", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
            }
        }

        // Close bubble — top-left, glass, matches ProfileOverlay/
        // SearchOverlay's own close bubble exactly (item 3: "instead of the
        // close button, there should be a glass X bubble at the top left of
        // the page similar to profile and search pages"). Drawn last/on top
        // so it's reachable in every state above, including the full-screen
        // post view.
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(16.dp),
            contentAlignment = Alignment.TopStart
        ) {
            TaggingCloseBubble(liquidGlass = liquidGlass, tint = profileTint, backdrop = backdrop, onClick = onDismiss)
        }
    }
}

/** The original centered glass card layout — still used for every state
 *  that isn't "actively tagging a specific post" (nothing to show
 *  full-screen for a model download, a finished run, or a failure). */
@Composable
private fun CenteredCard(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        val cardShape = RoundedCornerShape(24.dp)
        @Composable
        fun CardContent() {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
        }
        if (liquidGlass) {
            LiquidGlassSurface(Modifier.fillMaxWidth(0.82f), shape = cardShape, tint = tint, backdrop = backdrop) { CardContent() }
        } else {
            Box(Modifier.fillMaxWidth(0.82f).clip(cardShape).background(Color.White.copy(0.06f))) { CardContent() }
        }
    }
}

/** The redesigned "actively tagging" view (item 3): [item] fullscreened in
 *  the center inside one continuous rounded glass outline that matches the
 *  person's own profile color. The outline expands upward from the image to
 *  make room for a two-line header — "Tagging" (+ a small spinner) and
 *  "Tagged N Posts (dataset size)" — rather than being a separate element
 *  floating above the image, per the request ("the top of the image
 *  outline should expand… the expanded outline with the text is
 *  additional").
 *
 *  No per-post scrollable tag list: ImageTagger.tag() runs one synchronous
 *  ONNX forward pass per image and returns every tag for that post at once,
 *  it doesn't stream tags in one at a time — exactly the case the request
 *  called out as *not* needing this feature ("if it does it all at once at
 *  the end then this last feature wouldn't make sense because it would
 *  instantly be on the next image before the user could see the tags"). */
@Composable
private fun ActiveTaggingView(
    item: com.mediaviewer.model.MediaItem,
    state: MainViewModel.TaggingUiState,
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?
) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp)
            .padding(top = 64.dp, bottom = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        val outlineShape = RoundedCornerShape(28.dp)
        @Composable
        fun FrameContent() {
            Column(Modifier.fillMaxSize()) {
                // Expanded header — two rows, never wrapping (maxLines = 1
                // on both, per the request: "make sure the text never
                // collapses into extra rows").
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Tagging", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, softWrap = false
                        )
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(12.dp), color = Color.White.copy(alpha = 0.85f), strokeWidth = 1.5.dp)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Tagged ${state.tagged} Posts (${formatBytes(state.datasetBytes)})",
                        color = DimGray, fontSize = 12.sp, maxLines = 1, softWrap = false
                    )
                }
                // The post itself fills the rest of the same outline — the
                // outline never breaks between header and image, it's one
                // shape the whole way down (see how FrameContent as a whole
                // is what's wrapped in the glass surface below, not just
                // this Box).
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AsyncImage(
                        model = item.thumbUrl.ifBlank { item.mediaUrl },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(Modifier.fillMaxSize(), shape = outlineShape, tint = tint, backdrop = backdrop) { FrameContent() }
        } else {
            Box(Modifier.fillMaxSize().clip(outlineShape).background(Color.White.copy(0.06f))) { FrameContent() }
        }
    }
}

/** Matches ProfileOverlay's CloseGlassBubble / SearchOverlay's
 *  SearchCloseBubble exactly (30dp glass circle, white X). */
@Composable
private fun TaggingCloseBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, onClick: () -> Unit) {
    val shape = CircleShape
    if (liquidGlass) {
        LiquidGlassSurface(
            modifier = Modifier.size(30.dp).clickable(onClick = onClick),
            shape = shape, tint = tint, backdrop = backdrop
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    } else {
        Box(
            Modifier.size(30.dp).clip(shape).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DismissButton(liquidGlass: Boolean, tint: Color, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .then(if (liquidGlass) Modifier.glassPanel(true, shape = shape, tint = tint) else Modifier.clip(shape).background(tint.copy(alpha = 0.35f)))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "${(bytes / 1000.0).roundToInt()} KB"
    else -> "$bytes B"
}
