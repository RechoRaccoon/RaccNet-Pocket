package com.mediaviewer.ui

import com.mediaviewer.platform.sharedPreferences

import kotlinx.coroutines.IO

import com.mediaviewer.ui.compat.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.mediaviewer.ui.compat.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.request.ImageRequest
import kotlinx.coroutines.async
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.rememberHapticTap

/** Bug fix: top-of-screen interactive rows (the feed's AuthorRow, the Hub
 *  header, Search/DM overlays' headers, …) used to pad themselves down by
 *  `WindowInsets.statusBars` alone. That works fine on a device with a
 *  normal status bar showing, but this app hides the status bar entirely
 *  (see MainActivity.hideSystemStatusBar) — and with it hidden, several
 *  devices report `WindowInsets.statusBars` as 0 instead of still holding
 *  space for the physical camera cutout, so real UI ends up drawn underneath
 *  it and gets visually clipped/obscured. [ProfileOverlay] already worked
 *  around this same issue locally with its own `topClearance` calculation;
 *  this is that same fix pulled out into one shared helper so every other
 *  top-of-screen surface can use it too: take whichever is tallest of the
 *  status bar inset, the display cutout inset, and a sane minimum, so
 *  content clears the cutout on every device regardless of whether the
 *  hidden status bar's own inset happens to still be reported or not.
 *  Deliberately only ever used for *top* padding on specific interactive
 *  rows, never as a full-screen inset — the background/title art behind
 *  those rows is still meant to extend all the way up under the cutout. */
@Composable
fun rememberTopCutoutClearance(minimum: Dp = 32.dp): Dp {
    val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return maxOf(cutoutTop, statusBarTop, minimum)
}

/** The actual color-sampling work behind [rememberDominantColor], factored
 *  out as a plain suspend function so non-composable call sites (the
 *  app-launch and feed-open pixel transition wiring in AppRoot, which need
 *  an explicit "done" signal to know when to reveal the real UI) can await
 *  the same real fetch+sample work directly instead of only being able to
 *  observe it asynchronously through composition. */
suspend fun fetchDominantColor(context: com.mediaviewer.platform.PlatformContext, url: String): Color {
    if (url.isBlank()) return Color(0xFF2A2A2E)
    val cached = cachedDominantColor(url)
    if (cached != null) return cached
    try {
        val c = com.mediaviewer.ui.compat.sampleAverageColor(context, url, 16)
        if (c != null) {
            putDominantColor(url, c)
            return c
        }
    } catch (_: Exception) { /* fall through to default below */ }
    return Color(0xFF2A2A2E)
}

/** Colors already sampled this session, by URL. Every glass surface, tint
 *  and transition asks for these over and over (the Hub, profiles and the
 *  notch all re-derive "your color" each time they appear); without this,
 *  each appearance started from a grey placeholder and re-ran the image
 *  fetch, then recomposed its whole subtree again once the real color
 *  arrived — a visible flash and extra work on every menu switch. */
// A plain access-ordered LinkedHashMap with manual eviction: subclassing it
// (to override removeEldestEntry) crashes the Kotlin compiler's IR backend in
// a multiplatform module ("No override for FUN ... name:get").
private val dominantColorCache = com.mediaviewer.util.LruMap<String, Color>(300)

private fun putDominantColor(url: String, color: Color) = com.mediaviewer.platform.synchronizedCompat(dominantColorCache) {
    dominantColorCache[url] = color
}

private fun cachedDominantColor(url: String): Color? = com.mediaviewer.platform.synchronizedCompat(dominantColorCache) { dominantColorCache[url] }

/** The signed-in account, set from AppRoot, so every "your color" surface
 *  outside the profile page can use the exact same banner/avatar blend the
 *  profile page itself uses. [loaded] = the profile (and so its banner, or
 *  the lack of one) is actually known yet. */
object SelfProfileColors {
    var bannerUrl by mutableStateOf<String?>(null)
    var did by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)

    /** The signed-in account's DID from the last run, read synchronously at
     *  launch. The live [did] only arrives once the saved login has loaded,
     *  a moment after the first frame — this lets "your color" come
     *  straight out of [ProfileColorStore] from the very first frame
     *  instead of starting grey and then shifting. */
    var savedDid by mutableStateOf<String?>(null)
        private set

    /** True once "your color" is the real one (or there's none to wait
     *  for). MainActivity holds the first frame until then, up to a short
     *  timeout, so the app opens already in your colors. */
    @kotlin.concurrent.Volatile var ready = false

    private var prefs: com.mediaviewer.platform.SharedPreferences? = null

    /** Call after [ProfileColorStore.init]. */
    fun init(context: com.mediaviewer.platform.PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences("self_profile_color")
        prefs = p
        savedDid = p.getString("did", null)?.takeIf { it.isNotBlank() }
        if (com.mediaviewer.util.UiToggles.overrideAppColors) ready = true
        savedDid?.let { if (ProfileColorStore.get(it) != null) ready = true }
    }

    fun rememberDid(value: String) {
        if (value.isBlank() || value == savedDid) return
        savedDid = value
        prefs?.edit()?.putString("did", value)?.apply()
    }
}

private val PlaceholderGrey = Color(0xFF2A2A2E)

/** A profile's two source colors: its banner's (the avatar's again when it
 *  has no banner) and its avatar's. [blended] is the profile's UI color. */
data class ProfileColors(val banner: Color, val avatar: Color) {
    val blended: Color get() = Color(
        red = (banner.red + avatar.red) / 2f,
        green = (banner.green + avatar.green) / 2f,
        blue = (banner.blue + avatar.blue) / 2f,
        alpha = 1f
    )
}

/**
 * Every account's real profile colors, remembered by DID — in memory and on
 * disk — once they've been worked out from the real banner + avatar.
 *
 * Why: a profile's color needs its banner, and the banner only arrives with
 * the profile itself. Until then everything used to fall back to the avatar
 * alone (the old formula), so each profile opened in a slightly-off color
 * and then shifted to the real one; imageless blog cards never got the real
 * one at all. Now the last real color is used straight away, and accounts
 * seen for the first time get their banner looked up ([bannerResolver]).
 */
object ProfileColorStore {
    private var prefs: com.mediaviewer.platform.SharedPreferences? = null
    private val mem = HashMap<String, ProfileColors>()
    private val banners = HashMap<String, String?>()
    private val inFlight = HashMap<String, kotlinx.coroutines.Deferred<String?>>()

    /** Looks up an account's banner URL (null = it has none); a failure
     *  means "unknown". Set once from AppRoot. */
    @kotlin.concurrent.Volatile var bannerResolver: (suspend (did: String) -> Result<String?>)? = null

    fun init(context: com.mediaviewer.platform.PlatformContext) {
        if (prefs == null) prefs = context.sharedPreferences("profile_colors")
    }

    fun get(did: String): ProfileColors? {
        if (did.isBlank()) return null
        com.mediaviewer.platform.synchronizedCompat(mem) { mem[did]?.let { return it } }
        val p = prefs ?: return null
        val b = p.getInt("b:$did", 0); val a = p.getInt("a:$did", 0)
        if (b == 0 || a == 0) return null
        return ProfileColors(Color(b), Color(a)).also { com.mediaviewer.platform.synchronizedCompat(mem) { mem[did] = it } }
    }

    fun put(did: String, colors: ProfileColors) {
        if (did.isBlank()) return
        val old = com.mediaviewer.platform.synchronizedCompat(mem) { mem.put(did, colors) }
        if (old == colors) return
        prefs?.edit()?.putInt("b:$did", colors.banner.toArgb())?.putInt("a:$did", colors.avatar.toArgb())?.apply()
    }

    fun noteBanner(did: String, bannerUrl: String?) {
        if (did.isNotBlank()) com.mediaviewer.platform.synchronizedCompat(banners) { banners[did] = bannerUrl }
    }

    /** The account's banner: known already, or looked up once (shared by
     *  every card asking at the same time). Throws if it can't be found. */
    suspend fun banner(did: String): String? {
        com.mediaviewer.platform.synchronizedCompat(banners) { if (banners.containsKey(did)) return banners[did] }
        val resolver = bannerResolver ?: error("no resolver")
        val job = com.mediaviewer.platform.synchronizedCompat(inFlight) {
            inFlight.getOrPut(did) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                    resolver(did).getOrThrow()
                }
            }
        }
        return try {
            job.await().also { noteBanner(did, it) }
        } finally {
            com.mediaviewer.platform.synchronizedCompat(inFlight) { if (inFlight[did] === job) inFlight.remove(did) }
        }
    }
}

/** Works out (and remembers) a profile's real colors from its banner and
 *  avatar. [bannerKnown] false = the banner isn't known yet: it's looked
 *  up first (when [resolve]), and if it can't be, the remembered colors —
 *  or, for an account never seen before, the avatar alone — are returned
 *  without being stored. */
suspend fun fetchProfileColors(
    context: com.mediaviewer.platform.PlatformContext,
    did: String,
    avatarUrl: String?,
    bannerUrl: String?,
    bannerKnown: Boolean,
    resolve: Boolean = true
): ProfileColors {
    var banner = bannerUrl
    var known = bannerKnown
    if (known) ProfileColorStore.noteBanner(did, bannerUrl)
    else if (resolve && did.isNotBlank()) {
        runCatching { ProfileColorStore.banner(did) }.onSuccess { banner = it; known = true }
    }
    val avatar = if (avatarUrl.isNullOrBlank()) PlaceholderGrey else fetchDominantColor(context, avatarUrl)
    if (!known) return ProfileColorStore.get(did) ?: ProfileColors(avatar, avatar)
    val bannerColor = if (banner.isNullOrBlank()) avatar else fetchDominantColor(context, banner!!)
    return ProfileColors(bannerColor, avatar).also { ProfileColorStore.put(did, it) }
}

/** Composable form of [fetchProfileColors]: starts on the remembered real
 *  colors (or, if both images were already sampled this session, the exact
 *  ones), never on the avatar-only guess when anything better is known. */
@Composable
fun rememberProfileColors(
    did: String,
    avatarUrl: String?,
    bannerUrl: String? = null,
    bannerKnown: Boolean = false,
    resolve: Boolean = true
): ProfileColors {
    val context = LocalContext.current
    var colors by remember(did) {
        mutableStateOf(run {
            val a = avatarUrl?.takeIf { it.isNotBlank() }?.let { cachedDominantColor(it) }
            val exact = if (bannerKnown && a != null) {
                val b = if (bannerUrl.isNullOrBlank()) a else cachedDominantColor(bannerUrl)
                b?.let { ProfileColors(it, a) }
            } else null
            exact ?: ProfileColorStore.get(did) ?: ProfileColors(a ?: PlaceholderGrey, a ?: PlaceholderGrey)
        })
    }
    LaunchedEffect(did, avatarUrl, bannerUrl, bannerKnown) {
        colors = fetchProfileColors(context, did, avatarUrl, bannerUrl, bannerKnown, resolve)
    }
    return colors
}

/** A profile's UI color — identical to ProfileOverlay's own: the average of
 *  the banner's dominant color (avatar when there's no banner) and the
 *  avatar's. Prefer [rememberProfileColors] when the DID is known. */
@Composable
fun rememberProfileTint(bannerUrl: String?, avatarUrl: String?): Color {
    val bannerColor = rememberDominantColor(bannerUrl ?: avatarUrl ?: "")
    val avatarColor = rememberDominantColor(avatarUrl ?: "")
    return remember(bannerColor, avatarColor) { ProfileColors(bannerColor, avatarColor).blended }
}

/** Any account's UI color from just its DID + avatar (a blog or review
 *  card, say): its real banner/avatar blend, looked up if needed. */
@Composable
fun rememberAuthorProfileTint(did: String, avatarUrl: String?): Color =
    rememberProfileColors(did, avatarUrl).blended

/** True when "your color" is already known without waiting for your
 *  profile to load: Override App Colors is on, or your account's real
 *  colors are remembered from an earlier run. */
fun selfProfileColorKnown(): Boolean {
    if (com.mediaviewer.util.UiToggles.overrideAppColors) return true
    val did = SelfProfileColors.did?.takeIf { it.isNotBlank() } ?: SelfProfileColors.savedDid
    return !did.isNullOrBlank() && ProfileColorStore.get(did) != null
}

/** "Your color" for any screen: the real one as soon as it's known —
 *  straight away on launch when it's remembered, before your profile (and
 *  avatar) have even loaded — else [fallback] (signed out, or a very first
 *  launch before your profile has ever loaded). */
@Composable
fun rememberSelfTint(selfAvatarUrl: String?, fallback: Color): Color =
    if (!selfAvatarUrl.isNullOrBlank() || selfProfileColorKnown()) rememberSelfProfileTint(selfAvatarUrl ?: "")
    else fallback

/** The signed-in user's own color, matching their profile page exactly. */
@Composable
fun rememberSelfProfileTint(selfAvatarUrl: String): Color {
    // Settings → "Override App Colors": the picked color everywhere "your
    // color" is used (your actual profile page computes its own colors and
    // is never routed through here, so it keeps its real ones).
    if (com.mediaviewer.util.UiToggles.overrideAppColors) return Color(com.mediaviewer.util.UiToggles.overrideColor)
    // Until the saved login has loaded, use last run's account, so its
    // remembered colors show from the very first frame.
    val did = SelfProfileColors.did?.takeIf { it.isNotBlank() } ?: SelfProfileColors.savedDid
    val color = if (!did.isNullOrBlank()) {
        rememberProfileColors(
            did, selfAvatarUrl, SelfProfileColors.bannerUrl,
            // Only "known" with the avatar in hand too — otherwise a
            // blank avatar would be sampled as grey and remembered.
            bannerKnown = SelfProfileColors.loaded && SelfProfileColors.did == did && selfAvatarUrl.isNotBlank(),
            resolve = false
        ).blended
    } else rememberProfileTint(SelfProfileColors.bannerUrl, selfAvatarUrl)
    val liveDid = SelfProfileColors.did
    LaunchedEffect(liveDid) { if (!liveDid.isNullOrBlank()) SelfProfileColors.rememberDid(liveDid) }
    // The real color is in: let the first frame through (see MainActivity).
    LaunchedEffect(color, did) {
        if (!SelfProfileColors.ready && did != null && color != PlaceholderGrey &&
            (ProfileColorStore.get(did) != null || SelfProfileColors.loaded)) SelfProfileColors.ready = true
    }
    return color
}

/** Samples a low-res copy of the given media URL and returns its average
 *  color. This is the "color of the post" used to tint that post's
 *  background, its glass panels' rims, and to decide whether panel content
 *  needs a legibility scrim. */
@Composable
fun rememberDominantColor(url: String): Color {
    val context = LocalContext.current
    // Already known: use it from the very first frame, no fetch.
    var color by remember(url) { mutableStateOf(cachedDominantColor(url) ?: Color(0xFF2A2A2E)) }
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        color = cachedDominantColor(url) ?: fetchDominantColor(context, url)
    }
    return color
}

/** Big Update #8: the full-screen background behind a post — a dark vignette
 *  tinted with that post's own dominant color, instead of flat black — so the
 *  clear glass panels have real, post-specific color to show through to. */
fun postBackgroundBrush(dominantColor: Color): Brush =
    // A flat, dimmer version of the color (no black band through the
    // middle any more) — the same fill SpaceSky puts its stars on.
    SolidColor(dimSpaceColor(dominantColor))

/** Item 11: a DM thread's own two-tone version of [postBackgroundBrush] —
 *  [bottomColor] (the logged-in user's own dominant color) deep-tints the
 *  bottom of the screen, fading through black in the middle, up into
 *  [topColor] (the other person's dominant color) at the top — instead of
 *  one flat color reflected symmetrically top and bottom like every other
 *  page in the app. Mirrors how each side's chat bubbles are already tinted
 *  to that same person's own color (see DmThreadView's myTint/theirTint). */
fun dmThreadBackgroundBrush(bottomColor: Color, topColor: Color): Brush {
    return Brush.verticalGradient(listOf(dimSpaceColor(topColor), dimSpaceColor(bottomColor)))
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

/** Item 7: whether every glass rim's gradient uses a brighter/more-saturated
 *  version of its own tint as the middle stop (true, the default) or
 *  collapses to a single flat reflected color with no gradient at all
 *  (false) — the Settings toggle next to the rim intensity slider. */
val LocalGlassRimVibrantSecondary = compositionLocalOf { true }

/** Item 7: a brighter, more saturated version of [c] at the same hue —
 *  every rim gradient's middle stop uses this instead of a neutral
 *  grey/white, so the outline reads as "this panel's own color, lit up"
 *  rather than a generic highlight unrelated to whatever it's tinted with. */
private fun vibrantRimHighlight(c: Color): Color {
    val hsv = FloatArray(3)
    com.mediaviewer.ui.compat.PlatformColor.colorToHSV(c.toArgb(), hsv)
    hsv[1] = (hsv[1] * 0.85f + 0.15f).coerceIn(0f, 1f)
    hsv[2] = hsv[2].coerceAtLeast(0.92f)
    return Color(com.mediaviewer.ui.compat.PlatformColor.HSVToColor(hsv))
}

/** Item 7: every glass rim's border brush goes through this — a
 *  tint → brighter-tint → tint gradient when [vibrantSecondary] is on, or a
 *  single flat tint (at [edgeAlpha] strength) with no gradient at all when
 *  the person's turned that off in Settings. */
fun glassRimBrush(tint: Color, rimIntensity: Float, vibrantSecondary: Boolean, edgeAlpha: Float, midAlpha: Float): Brush =
    if (vibrantSecondary) {
        Brush.linearGradient(listOf(
            tint.copy(alpha = edgeAlpha * rimIntensity),
            vibrantRimHighlight(tint).copy(alpha = midAlpha * rimIntensity),
            tint.copy(alpha = edgeAlpha * rimIntensity)
        ))
    } else {
        androidx.compose.ui.graphics.SolidColor(tint.copy(alpha = edgeAlpha * rimIntensity))
    }

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
    val rimVibrantSecondary = LocalGlassRimVibrantSecondary.current
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
            brush = glassRimBrush(tint, rimIntensity, rimVibrantSecondary, edgeAlpha = 0.85f, midAlpha = 0.5f),
            shape = shape
        )
}

/**
 * An opaque "masked" surface for cases that need to sit *over* other UI and
 * fully hide it — search autocomplete expanding over results, a popped-open
 * menu over page content — the opposite of [glassPanel]/[LiquidGlassSurface]
 * which are deliberately see-through.
 *
 * Bug fix: this used to just paint a fresh `postBackgroundBrush(tint)` fill.
 * That brush is a 3-stop vertical gradient with no fixed start/end — Compose
 * resolves it against *whatever bounds it's drawn into*, so painting it into
 * a full-screen Box (its original, only use) and painting it again into a
 * small ~44-80dp search bubble somewhere in the upper third of the screen
 * produce two completely different-looking gradients: the small bubble
 * squashes the same deep→black→deep progression into a fraction of the
 * height, reading as a flatly darker tint instead of "a window onto the
 * matching slice of the real background". A masked surface needs to show
 * the *actual* background pixels that would be behind it, sampled at its
 * real position — which is exactly what [LiquidGlassSurface]'s live
 * backdrop crop already does, just with blur and a translucent tint over
 * top. Reusing that same crop with neither gives a true, always-pixel-
 * accurate "hole punched through to the background" instead of an
 * approximation, and fixes both at once.
 */
fun Modifier.opaqueMaskPanel(
    backdrop: GlassBackdrop? = null,
    tint: Color = NeutralGlassTint,
    shape: Shape = RoundedCornerShape(20.dp),
    rim: Boolean = true
): Modifier = composed {
    val rimIntensity = LocalGlassRimIntensity.current
    val rimVibrantSecondary = LocalGlassRimVibrantSecondary.current
    var trackedOrigin by remember { mutableStateOf(Offset.Zero) }
    this
        .clip(shape)
        .onGloballyPositioned { coords -> trackedOrigin = coords.positionInRoot() }
        // Always paint a solid, opaque fallback first — even if the live
        // backdrop crop below fails/mis-times for any reason (wrong geometry
        // on the first frame before trackedOrigin settles, a size/offset
        // mismatch, etc.), the panel is still opaque instead of fully
        // see-through. Mirrors LiquidGlassSurface's proven-working structure:
        // base fill first, live crop layered on top as a separate step.
        .background(postBackgroundBrush(tint))
        .then(
            // Bug fix: this used to also gate on CAN_BLUR (API 31+), copied
            // from LiquidGlassSurface — but CAN_BLUR exists purely because
            // *.blur()*'s RenderEffect needs API 31, and this panel
            // deliberately never calls .blur() (see the comment below).
            // Plain GraphicsLayer recording/drawLayer has no such
            // requirement, so gating on CAN_BLUR here was disabling the
            // live crop on every device below API 31 for no reason — those
            // devices always fell straight to the flat, squashed-gradient
            // fallback above, which is exactly the "mask has its own
            // gradient instead of the real background" symptom.
            if (backdrop != null) {
                // No .blur()/magnify/tint scrim here on purpose, unlike
                // LiquidGlassSurface's version of this same crop — this is
                // meant to read as plain, sharp background, not glass.
                Modifier.drawWithContent {
                    val delta = trackedOrigin - backdrop.originInRoot()
                    translate(-delta.x, -delta.y) {
                        drawLayer(backdrop.layer)
                    }
                    drawContent()
                }
            } else Modifier
        )
        .then(
            if (rim) Modifier.border(
                width = 1.dp,
                brush = glassRimBrush(tint, rimIntensity, rimVibrantSecondary, edgeAlpha = 0.85f, midAlpha = 0.5f),
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
    // Item 9: centering the panel's inner content is opt-in per caller.
    // This Box used to default to TopStart, then was changed globally to
    // Center so a stretched review-page pill (sized to
    // Modifier.fillMaxHeight() to match a sibling row's height) would sit
    // its text in its middle instead of pinning it to the top — but that
    // bled into every other caller (e.g. the feed's image-post AuthorRow
    // pill started centering usernames/post text instead of start-aligning
    // them). So the default is back to TopStart, and review-page pills
    // pass Alignment.Center through ProfileGlassPill/StarRatingPill's own
    // centerContent flag instead. Every decorative layer above
    // (backdrop/tint/scrim/rim) already uses matchParentSize(), so this
    // only affects content that doesn't already fill the panel itself.
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    // Item 26: same fade-to-flat behavior as glassPanel above, plus scaling
    // down the blur radius and magnify amount themselves — at 0 there's no
    // blur box at all and the panel is just its (now-invisible) tint/rim.
    // Bug fix: rim/border alpha now follows its own rimIntensity dial,
    // independent of the background blur/tint intensity — see glassPanel.
    val intensity = LocalGlassIntensity.current
    val rimIntensity = LocalGlassRimIntensity.current
    val rimVibrantSecondary = LocalGlassRimVibrantSecondary.current
    val scrimAlpha = scrimAlphaFor(tint) * intensity
    var trackedOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .clip(shape)
            .then(
                if (staticOrigin == null)
                    Modifier.onGloballyPositioned { coords -> trackedOrigin = coords.positionInRoot() }
                else Modifier
            ),
        // Every decorative layer above (backdrop/tint/scrim/rim) already
        // uses matchParentSize(), so centering the actual content here only
        // affects content that doesn't already fill the panel itself — see
        // the [contentAlignment] parameter's own doc comment for why the
        // default is TopStart rather than Center.
        contentAlignment = contentAlignment
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
                brush = glassRimBrush(tint, rimIntensity, rimVibrantSecondary, edgeAlpha = 0.95f, midAlpha = 0.55f),
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
    backdrop: GlassBackdrop? = null,
    /** You follow them and they follow you: reads "Mutuals" instead of
     *  "Following" (profile pages). */
    isMutual: Boolean = false,
    /** false: greyed out (they've blocked you) — a tap still reaches
     *  [onClick], which explains why nothing happens. */
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(14.dp)
    // Fix 9: one shared light tap on every press, via the shared helper.
    val tap = rememberHapticTap()
    val clickableModifier = modifier.graphicsLayer { alpha = if (enabled) 1f else 0.4f }
        .clip(shape).clickable(onClick = { tap(); onClick() })

    @Composable
    fun FollowLabel() {
        // Fixed-width label: "Following" (the longer word) is laid out
        // invisibly to reserve the button's width, and the real label is
        // drawn centered on top — so switching between "Follow" and
        // "Following" never resizes the button.
        Box(contentAlignment = Alignment.Center) {
            Text("Following", color = Color.Transparent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(
                if (isFollowing && isMutual) "Mutuals" else if (isFollowing) "Following" else "Follow",
                color = if (liquidGlass) Color.White.copy(alpha = if (isFollowing) 0.65f else 1f)
                        else if (isFollowing) DimGray else Color.White,
                fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
        }
    }

    // Centered, so a caller stretching the button (the feed's author row
    // matches its height to the author bubble beside it) keeps the label
    // in the middle.
    if (liquidGlass) {
        LiquidGlassSurface(modifier = clickableModifier, shape = shape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.Center) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) { FollowLabel() }
        }
    } else {
        Box(
            clickableModifier
                .background(if (isFollowing) Color.White.copy(0.07f) else Color.White.copy(0.14f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
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
    /** Tapping it runs [onClick] without closing the menu. */
    val keepOpen: Boolean = false,
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
    // Fix 9: one shared light tap on every menu-item press.
    val tap = rememberHapticTap()
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
                        .clickable { tap(); onDismissRequest(); item.onClick() },
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


/** The round "back to top" arrow (profiles and the feed's grid mode): real
 *  glass that blurs whatever is scrolling behind it (via [backdrop]). */
@Composable
fun ScrollToTopGlassBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, onClick: () -> Unit) {
    val tap = com.mediaviewer.util.rememberHapticTap()
    val shape = androidx.compose.foundation.shape.CircleShape
    val base = Modifier.size(38.dp).clip(shape).clickable { tap(); onClick() }
    val icon: @Composable () -> Unit = {
        androidx.compose.material3.Icon(
            Icons.Filled.ArrowUpward,
            contentDescription = "Scroll to top", tint = Color.White, modifier = Modifier.size(18.dp)
        )
    }
    if (liquidGlass) {
        LiquidGlassSurface(modifier = base, shape = shape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.Center) { icon() }
    } else {
        Box(base.background(Color.Black.copy(0.6f)), contentAlignment = Alignment.Center) { icon() }
    }
}
