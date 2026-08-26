package com.mediaviewer.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil.request.ImageRequest
import com.mediaviewer.ui.theme.DimGray

/** The actual color-sampling work behind [rememberDominantColor], factored
 *  out as a plain suspend function so non-composable call sites (the
 *  app-launch and feed-open pixel transition wiring in AppRoot, which need
 *  an explicit "done" signal to know when to reveal the real UI) can await
 *  the same real fetch+sample work directly instead of only being able to
 *  observe it asynchronously through composition. */
suspend fun fetchDominantColor(context: android.content.Context, url: String): Color {
    if (url.isBlank()) return Color(0xFF2A2A2E)
    try {
        val loader = coil.Coil.imageLoader(context)
        val request = ImageRequest.Builder(context).data(url).size(16, 16).allowHardware(false).build()
        val bmp = (loader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (bmp != null) {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
                val p = bmp.getPixel(x, y)
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
            }
            if (n > 0) return Color(r.toFloat() / n / 255f, g.toFloat() / n / 255f, b.toFloat() / n / 255f, 1f)
        }
    } catch (_: Exception) { /* fall through to default below */ }
    return Color(0xFF2A2A2E)
}

/** Samples a low-res copy of the given media URL and returns its average
 *  color. This is the "color of the post" used to tint that post's
 *  background, its glass panels' rims, and to decide whether panel content
 *  needs a legibility scrim. */
@Composable
fun rememberDominantColor(url: String): Color {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf(Color(0xFF2A2A2E)) }
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        color = fetchDominantColor(context, url)
    }
    return color
}

/** Big Update #8: the full-screen background behind a post — a dark vignette
 *  tinted with that post's own dominant color, instead of flat black — so the
 *  clear glass panels have real, post-specific color to show through to. */
fun postBackgroundBrush(dominantColor: Color): Brush {
    val deep = Color(
        red = dominantColor.red * 0.22f,
        green = dominantColor.green * 0.22f,
        blue = dominantColor.blue * 0.22f,
        alpha = 1f
    )
    return Brush.verticalGradient(listOf(deep, Color.Black, deep))
}

/** Bug fix: blocks taps/drags from passing through a full-screen overlay to
 *  whatever is still composed underneath it (the feed pager, a Hub page's
 *  own swipe gestures, etc.). Full-screen overlays built from a plain
 *  Column (Search, DM inbox) have plenty of "dead space" — Spacers,
 *  dividers, plain Text with no click handler — that never register any
 *  pointer input of their own. Compose's hit-testing doesn't stop at an
 *  occluding node just because it's drawn on top; it only stops if that
 *  node (or an ancestor) actually claims the pointer input for that screen
 *  region. A `Box(Modifier.fillMaxSize().background(...))` alone does NOT
 *  claim it, so a tap on any of that dead space can silently reach the
 *  sibling composable still rendered behind the overlay — including, in
 *  one observed case, this exact bug: tapping the overlay's own close
 *  button landed on the same screen position as a button on the page
 *  behind it, and both fired. Applying this to the overlay's outermost
 *  Box claims the whole area; nested interactive children (close buttons,
 *  text fields, list rows) still win over this no-op handler for the exact
 *  pixels they cover, so nothing inside the overlay is affected — this
 *  only catches the gaps. */
fun Modifier.blockClicksBehind(): Modifier = composed {
    this.clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null
    ) { /* no-op — exists purely to claim pointer input over this region */ }
}

/** Neutral glass tint for surfaces that aren't tied to a specific post's
 *  media (Settings, Comments, Share, Add To, the quick-action radial menu). */
val NeutralGlassTint = Color(0xFF7A7AA6)

/** True on devices that can actually run [android.graphics.RenderEffect]-backed
 *  blur (Compose's [Modifier.blur] is a no-op below API 31). Backdrop panels
 *  fall back to a plain tinted glass look on older devices instead of showing
 *  an unblurred capture poking through. */
// Item: Hub "Profile" button now also uses this to gate its own blur.
internal val CAN_BLUR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Big Update #4: a live, real-time source for backdrop-blurred glass —
 * [layer] is a [GraphicsLayer] that some ancestor re-records every frame with
 * `graphicsLayer.record { drawContent() }` (so it always holds *this frame's*
 * actual rendered pixels — video playing, sub-image swipes, animations, all
 * of it — never a separate static snapshot), and [originInRoot] reports where
 * that recorded content starts in window/root coordinates, so a glass panel
 * anywhere else on screen can figure out exactly which pixels of it are
 * "directly underneath" itself.
 */
class GlassBackdrop(val layer: GraphicsLayer, val originInRoot: () -> Offset)

/** Item 26: how strong the blur/magnify/background-tint effect is right now,
 *  0f (flat, fully transparent — no blur, no magnify, no background tint) to
 *  1f (the full look). Provided once near the composition root from the
 *  "Background" slider in Settings, and read by every glass surface below
 *  instead of threading a Float through every single composable's parameter
 *  list. */
val LocalGlassIntensity = compositionLocalOf { 1f }

/** Bug fix (Hub bubble split): the rim/outline is now controlled by its own
 *  independent dial rather than sharing [LocalGlassIntensity] — a panel's
 *  background blur/tint can be turned down without also washing out its
 *  colored border, and vice versa. Provided from the "Outline" slider in
 *  Settings, alongside [LocalGlassIntensity]. 0f = no rim at all, 1f = the
 *  full strongly-tinted rim. */
val LocalGlassRimIntensity = compositionLocalOf { 1f }

/**
 * A lighter-weight liquid-glass look expressed as a plain [Modifier] (rather
 * than the panel-composable above) so existing rows/buttons/chips across
 * Settings, Comments, Share, and Add To (item 5) can opt into the same
 * clear, seamless glass treatment with a single call, without restructuring
 * their layout into a Box wrapper. These surfaces aren't tied to a specific
 * patch of media, so they use a still tint rather than the live backdrop.
 *
 * Big Update #8: no more animated glare sweep — it looked broken/disorienting
 * on some buttons (a hard flash to white then a cut to transparent). The glass
 * is now just a still, clear tinted surface with a bright rim.
 */
fun Modifier.glassPanel(
    liquidGlass: Boolean,
    tint: Color = NeutralGlassTint,
    shape: Shape = RoundedCornerShape(12.dp)
): Modifier = composed {
    if (!liquidGlass) return@composed this.clip(shape).background(Color.White.copy(alpha = 0.08f))

    // Item 26: fade the background tint/scrim toward nothing as background
    // intensity drops to 0, so 0 reads as plain and fully transparent rather
    // than just "less blurry". Bug fix: the rim/border now fades with its
    // own separate rimIntensity dial instead of sharing the background one —
    // previously the single "Glass Intensity" slider affected button rims
    // too, which wasn't supposed to happen.
    val intensity = LocalGlassIntensity.current
    val rimIntensity = LocalGlassRimIntensity.current
    val scrimAlpha = scrimAlphaFor(tint) * intensity

    this
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(tint.copy(alpha = 0.20f * intensity), Color.White.copy(alpha = 0.07f * intensity), tint.copy(alpha = 0.15f * intensity))
            )
        )
        .then(if (scrimAlpha > 0f) Modifier.background(Color.Black.copy(alpha = scrimAlpha)) else Modifier)
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(listOf(tint.copy(alpha = 0.85f * rimIntensity), Color.White.copy(alpha = 0.5f * rimIntensity), tint.copy(alpha = 0.7f * rimIntensity))),
            shape = shape
        )
}

/**
 * An opaque "masked" surface — same rim treatment as [glassPanel], but the
 * fill behind it is a fully solid copy of the app's own background gradient
 * ([postBackgroundBrush]) instead of a translucent tint. Where [glassPanel]
 * and [LiquidGlassSurface] are deliberately see-through so real content
 * shows through the glass, this is for the opposite case: a surface that
 * has to sit *over* other UI (search autocomplete expanding over results,
 * a popped-open menu sitting over page content) and fully hide whatever's
 * under it, rather than letting it show through and hurt legibility. Using
 * the same brush as the page background (rather than a flat color) means
 * the masked surface still reads as "part of this screen" instead of a
 * disconnected opaque card dropped on top of it.
 */
fun Modifier.opaqueMaskPanel(
    tint: Color = NeutralGlassTint,
    shape: Shape = RoundedCornerShape(20.dp),
    rim: Boolean = true
): Modifier = composed {
    val rimIntensity = LocalGlassRimIntensity.current
    this
        .clip(shape)
        .background(postBackgroundBrush(tint))
        .then(
            if (rim) Modifier.border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(tint.copy(alpha = 0.85f * rimIntensity), Color.White.copy(alpha = 0.5f * rimIntensity), tint.copy(alpha = 0.7f * rimIntensity))),
                shape = shape
            ) else Modifier
        )
}

/** How strong a dark legibility scrim a glass panel needs, given the color
 *  it's sitting against — keeps white icons/text readable over light posts
 *  without dulling the glass over already-dark ones. */
fun scrimAlphaFor(color: Color): Float {
    val l = color.luminance()
    return ((l - 0.35f) * 0.9f).coerceIn(0f, 0.42f)
}

/**
 * A clear "liquid glass" panel (Big Update #1 / #8 / #9): mostly transparent
 * so whatever is really behind it shows through, an adaptive dark scrim so
 * white content stays legible over light backgrounds, and a rim that's
 * strongly colored with the post's own palette — visible even from a
 * distance, like light catching the edge of real glass.
 *
 * Big Update #4/#9: when [backdrop] is given, the panel doesn't draw a fixed
 * picture behind itself — it samples the *live* [GraphicsLayer] the post is
 * already re-recording every frame, cropped to exactly the region under this
 * panel's own on-screen position, then magnifies and blurs that. Because it
 * reads the same layer the real content is drawn from, it updates in
 * real time right along with it (playing video, swiped sub-images, etc.)
 * instead of showing a separate static snapshot. Falls back to the plain
 * tint on API < 31, where draw-time blur isn't available.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = Color.White,
    backdrop: GlassBackdrop? = null,
    // Item 4 (Phase 3) fix: when a caller already knows its exact root-relative
    // screen position analytically (the quick-action radial menu computes each
    // button's position itself from a center point + fixed radius), it can pass
    // that position directly instead of relying on onGloballyPositioned. That
    // callback is a *layout*-phase signal, but the radial menu's pop-in/hover
    // "bounce" is an animated Modifier.scale() — a value that changes every
    // animation frame without necessarily forcing a fresh layout/placement
    // pass, so the tracked origin could go stale mid-animation and the live
    // backdrop crop would drift out of alignment with the (still correctly
    // scaled) visible panel — reading as "static" or "misaligned" until the
    // animation settled back to a state where the last-tracked origin happened
    // to be correct again. Passing the analytic, scale-independent position
    // directly sidesteps that timing dependency entirely.
    staticOrigin: Offset? = null,
    content: @Composable BoxScope.() -> Unit
) {
    // Item 26: same fade-to-flat behavior as glassPanel above, plus scaling
    // down the blur radius and magnify amount themselves — at 0 there's no
    // blur box at all and the panel is just its (now-invisible) tint/rim.
    // Bug fix: rim/border alpha now follows its own rimIntensity dial,
    // independent of the background blur/tint intensity — see glassPanel.
    val intensity = LocalGlassIntensity.current
    val rimIntensity = LocalGlassRimIntensity.current
    val scrimAlpha = scrimAlphaFor(tint) * intensity
    var trackedOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .clip(shape)
            .then(
                if (staticOrigin == null)
                    Modifier.onGloballyPositioned { coords -> trackedOrigin = coords.positionInRoot() }
                else Modifier
            )
    ) {
        // Big Update #4: the live backdrop — a magnified, blurred crop of
        // whatever is actually rendered directly under this panel right now,
        // sampled from the post's own shared, continuously-updated layer.
        if (CAN_BLUR && backdrop != null && intensity > 0.01f) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { scaleX = 1f + 0.3f * intensity; scaleY = 1f + 0.3f * intensity }
                    .blur(22.dp * intensity)
                    .drawWithContent {
                        val panelOrigin = staticOrigin ?: trackedOrigin
                        val delta = panelOrigin - backdrop.originInRoot()
                        translate(-delta.x, -delta.y) {
                            drawLayer(backdrop.layer)
                        }
                    }
            )
        }
        // Base frosted tint — deliberately light on alpha so the live/colored
        // backdrop behind the panel actually reads through the "glass".
        Box(
            Modifier.matchParentSize().background(
                Brush.linearGradient(
                    listOf(tint.copy(alpha = 0.16f * intensity), Color.White.copy(alpha = 0.06f * intensity), tint.copy(alpha = 0.12f * intensity))
                )
            )
        )
        if (scrimAlpha > 0f) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = scrimAlpha)))
        }
        // The rim — strongly tinted with the post's own color so it reads as
        // "this post's glass" even at a glance from across the screen.
        Box(
            Modifier.matchParentSize().border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    listOf(tint.copy(alpha = 0.95f * rimIntensity), Color.White.copy(alpha = 0.55f * rimIntensity), tint.copy(alpha = 0.85f * rimIntensity))
                ),
                shape = shape
            )
        )
        content()
    }
}

/**
 * The single shared Follow/Following button — used in the main feed's
 * [AuthorRow] and on profile pages, so both are pixel-identical in look and
 * behavior (same shape, sizing, colors, and the fixed-width label trick that
 * keeps the button from resizing when it switches between "Follow" and
 * "Following").
 */
@Composable
fun FollowButton(
    isFollowing: Boolean,
    liquidGlass: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null
) {
    val shape = RoundedCornerShape(14.dp)
    val clickableModifier = modifier.clip(shape).clickable(onClick = onClick)

    @Composable
    fun FollowLabel() {
        // Fixed-width label: "Following" (the longer word) is laid out
        // invisibly to reserve the button's width, and the real label is
        // drawn centered on top — so switching between "Follow" and
        // "Following" never resizes the button.
        Box(contentAlignment = Alignment.Center) {
            Text("Following", color = Color.Transparent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(
                if (isFollowing) "Following" else "Follow",
                color = if (liquidGlass) Color.White.copy(alpha = if (isFollowing) 0.65f else 1f)
                        else if (isFollowing) DimGray else Color.White,
                fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
        }
    }

    if (liquidGlass) {
        LiquidGlassSurface(modifier = clickableModifier, shape = shape, tint = tint, backdrop = backdrop) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) { FollowLabel() }
        }
    } else {
        Box(
            clickableModifier
                .background(if (isFollowing) Color.White.copy(0.07f) else Color.White.copy(0.14f))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) { FollowLabel() }
    }
}

/**
 * Own-profile counterpart to [FollowButton] — same shape/sizing/position so
 * the banner layout doesn't shift between viewing your own profile and
 * someone else's, but reads "Edit" instead of "Follow"/"Following" since
 * following yourself makes no sense. Placeholder only for now — no editing
 * flow exists yet, so the click is intentionally a no-op.
 */
@Composable
fun EditProfileButton(
    liquidGlass: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null
) {
    val shape = RoundedCornerShape(14.dp)
    val clickableModifier = modifier.clip(shape).clickable { /* placeholder — no edit flow yet */ }

    @Composable
    fun EditLabel() {
        Text("Edit", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }

    if (liquidGlass) {
        LiquidGlassSurface(modifier = clickableModifier, shape = shape, tint = tint, backdrop = backdrop) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) { EditLabel() }
        }
    } else {
        Box(
            clickableModifier
                .background(Color.White.copy(0.14f))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) { EditLabel() }
    }
}

/** TikTok-style upload placeholder — no functionality yet, per spec.
 *  Big Update #10: in Glass mode this is just a [LiquidGlassSurface] like every
 *  other button on the post, so its rim picks up the post's own dominant color
 *  and (when a backdrop is supplied) the same live, real-time reflection —
 *  instead of a flat white rim that never matched the post it sat on. */
@Composable
fun UploadPlaceholderButton(
    liquidGlass: Boolean,
    modifier: Modifier = Modifier,
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null
) {
    val shape = RoundedCornerShape(9.dp)
    if (liquidGlass) {
        LiquidGlassSurface(
            modifier = modifier.size(width = 42.dp, height = 28.dp),
            shape = shape, tint = dominantColor, backdrop = backdrop
        ) {
            Box(Modifier.matchParentSize().clickable(onClick = { /* placeholder — no functionality yet */ }), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = "Upload", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    } else {
        Box(
            modifier = modifier
                .size(width = 42.dp, height = 28.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.20f), shape)
                .clickable(onClick = { /* placeholder — no functionality yet */ }),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = "Upload", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** One row in a [GlassDropdownMenu]. [destructive] tints the label red
 *  (used for "Block") — everything else stays plain white. */
data class GlassMenuItem(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/** A small right-aligned, text-only popup menu with dividers between rows —
 *  shared by the feed interaction bar's "More" button (item 4: Show more/
 *  less like this, Add account to list, Block) and the Hub's upload button
 *  (item 5: Post/Blog/Review/Record/Go Live). Renders via [Popup] so it
 *  floats in its own layer above everything else — it never has to worry
 *  about the crash-prone "reading a GraphicsLayer while it's mid-recording"
 *  restriction the rest of this file's live-backdrop panels are subject to
 *  (see the comments on QuickActionMenu/video controls in MainFeedScreen),
 *  since it's not a descendant of whatever Box is doing that recording.
 *
 *  Positioned with its TopEnd corner pinned to the anchor's TopEnd corner,
 *  then nudged up by its own height plus a small gap — so it always opens
 *  *above* the anchor (the interaction bar / upload button it belongs to),
 *  right-aligned to it, regardless of where on screen that anchor sits.
 *  Uses [glassPanel] (a still tint, not a live backdrop) since a floating
 *  overlay like this isn't tied to any one patch of underlying media. */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<GlassMenuItem>,
    liquidGlass: Boolean,
    tint: Color = NeutralGlassTint,
    modifier: Modifier = Modifier
) {
    if (!expanded) return
    val density = LocalDensity.current
    val itemHeightDp = 40.dp
    val gapDp = 8.dp
    val menuHeightPx = with(density) { (itemHeightDp * items.size + gapDp).roundToPx() }
    val shape = RoundedCornerShape(14.dp)
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, -menuHeightPx),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier
                .width(190.dp)
                .then(
                    if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                    else Modifier.clip(shape).background(Color(0xE6161616)).border(1.dp, Color.White.copy(0.12f), shape)
                )
        ) {
            items.forEachIndexed { index, item ->
                Box(
                    Modifier.fillMaxWidth().height(itemHeightDp)
                        .clickable { onDismissRequest(); item.onClick() },
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        item.label,
                        color = if (item.destructive) Color(0xFFE0245E) else Color.White,
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                }
                if (index != items.lastIndex) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                }
            }
        }
    }
}
