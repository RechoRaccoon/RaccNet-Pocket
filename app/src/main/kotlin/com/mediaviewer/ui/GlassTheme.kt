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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest

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
                if (n > 0) color = Color(r.toFloat() / n / 255f, g.toFloat() / n / 255f, b.toFloat() / n / 255f, 1f)
            }
        } catch (_: Exception) { /* keep previous/default tint */ }
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

/** Neutral glass tint for surfaces that aren't tied to a specific post's
 *  media (Settings, Comments, Share, Add To, the quick-action radial menu). */
val NeutralGlassTint = Color(0xFF7A7AA6)

/** True on devices that can actually run [android.graphics.RenderEffect]-backed
 *  blur (Compose's [Modifier.blur] is a no-op below API 31). Backdrop panels
 *  fall back to a plain tinted glass look on older devices instead of showing
 *  an unblurred capture poking through. */
private val CAN_BLUR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

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

    val scrimAlpha = scrimAlphaFor(tint)

    this
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(tint.copy(alpha = 0.20f), Color.White.copy(alpha = 0.07f), tint.copy(alpha = 0.15f))
            )
        )
        .then(if (scrimAlpha > 0f) Modifier.background(Color.Black.copy(alpha = scrimAlpha)) else Modifier)
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(listOf(tint.copy(alpha = 0.85f), Color.White.copy(alpha = 0.5f), tint.copy(alpha = 0.7f))),
            shape = shape
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
    val scrimAlpha = scrimAlphaFor(tint)
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
        if (CAN_BLUR && backdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                    .blur(22.dp)
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
                    listOf(tint.copy(alpha = 0.16f), Color.White.copy(alpha = 0.06f), tint.copy(alpha = 0.12f))
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
                    listOf(tint.copy(alpha = 0.95f), Color.White.copy(alpha = 0.55f), tint.copy(alpha = 0.85f))
                ),
                shape = shape
            )
        )
        content()
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
