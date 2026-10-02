package com.mediaviewer.ui

import com.mediaviewer.resources.stellar_logo_vector

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.rememberHapticTap

/** One way to support Stellar. [domain] is used to fetch the service's own
 *  icon (its site favicon) at runtime; [fallback] shows until it loads, or
 *  if the phone is offline. */
private data class SupportMethod(
    val name: String,
    val handle: String,
    val url: String,
    val domain: String,
    val accent: Color,
    val fallback: ImageVector,
    val blurb: String
)

private val supportMethods = listOf(
    SupportMethod(
        "Cash App", "\$RechoRaccoon", "https://cash.app/\$RechoRaccoon", "cash.app",
        Color(0xFF00D64F), Icons.Default.AttachMoney, "Send any amount straight from Cash App."
    ),
    SupportMethod(
        "PayPal", "paypal.biz/rechoraccoon", "https://paypal.biz/rechoraccoon", "paypal.com",
        Color(0xFF0070E0), Icons.Default.Payments, "Card, bank or PayPal balance."
    ),
    SupportMethod(
        "Ko-fi", "ko-fi.com/rechoraccoon", "https://ko-fi.com/rechoraccoon", "ko-fi.com",
        Color(0xFFFF5E5B), Icons.Default.LocalCafe, "Buy Recho a coffee, once or monthly."
    )
)

/** The supporter benefits, exactly as listed on the Support page. */
internal val SUPPORTER_BENEFITS = listOf(
    "Supporter Profile Badge and Animation!!",
    "The Ability to Edit Posts!! (with limitations)",
    "Save Posts as Drafts!!",
    "Build your own local Feeds!! (experimental)",
    "Save posts into local Bookmark Folders!!",
    "Pin DMs!!",
    "Use the Launchpad's Calendar, Notes, Calculator, and Timer features!! (experimental)",
    "Create Polls on Stellar!!",
    "View Trending Topics in Search!!",
    "Use the In-App Multitasking Browser while you explore Stellar!!",
    "Receive App Notifications!! (android only) (checks every 15 minutes)",
    "Add Notes to Profiles!!",
    "Remove the \"Stellar Supporters\" row from the Hub!!"
)

/** Settings → "Support Stellar": the logo, what supporting unlocks, and a
 *  compact row for each way to chip in — all on one page (scaled down to
 *  fit a short screen rather than scrolling). */
@Composable
internal fun SupportPageContent(liquidGlass: Boolean, tint: Color) {
    val glow = rememberInfiniteTransition(label = "supportGlow")
    val breathe by glow.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "supportBreathe"
    )
    val pink = Color(0xFFFF4FA1)
    val uriHandler = LocalUriHandler.current
    val tap = rememberHapticTap()

    ScaleToFit(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // ── Logo with a soft breathing glow in the profile color ──
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(width = 200.dp, height = 56.dp)
                        .graphicsLayer { alpha = 0.35f + 0.25f * breathe; scaleX = 1.1f; scaleY = 1.3f }
                        .background(
                            Brush.radialGradient(listOf(tint.copy(alpha = 0.55f), Color.Transparent)),
                            RoundedCornerShape(50)
                        )
                )
                Image(
                    painterResource(com.mediaviewer.resources.Res.drawable.stellar_logo_vector), contentDescription = "Stellar",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 190.dp).fillMaxWidth(0.5f)
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Help fund Stellar's development (and Recho's survival) by donating \$4.99 or more, and you'll unlock these exclusive benefits for a month!!",
                color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, lineHeight = 18.sp,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp)
            )

            Spacer(Modifier.height(8.dp))
            // ── Benefits, in their own softly pink panel ──
            val panelShape = RoundedCornerShape(18.dp)
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth().clip(panelShape)
                    .background(Brush.verticalGradient(listOf(pink.copy(alpha = 0.16f), lerp(Color(0xFF101014), tint, 0.14f).copy(alpha = 0.7f))))
                    .border(1.dp, Brush.linearGradient(listOf(pink.copy(alpha = 0.85f), Color.White.copy(alpha = 0.16f), tint.copy(alpha = 0.5f))), panelShape)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Favorite, contentDescription = null, tint = pink,
                        modifier = Modifier.size(14.dp).graphicsLayer { scaleX = 0.92f + 0.12f * breathe; scaleY = 0.92f + 0.12f * breathe }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Supporter Benefits:", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.supporterShine()
                    )
                }
                Spacer(Modifier.height(5.dp))
                SUPPORTER_BENEFITS.forEach { benefit ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                        Text("•", color = pink, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(benefit, color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "You can donate through any of these platforms!! Just make sure to attach your Stellar/Bluesky handle to the note!!",
                color = lerp(pink, Color.White, 0.4f), fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp)
            )

            Spacer(Modifier.height(8.dp))
            // ── The compact links (same rows as the Support popup) ──
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                supportMethods.forEach { method ->
                    val shape = RoundedCornerShape(16.dp)
                    val panel = lerp(Color(0xFF101014), lerp(tint, method.accent, 0.6f), if (liquidGlass) 0.16f else 0.12f)
                    Row(
                        Modifier.fillMaxWidth().clip(shape)
                            .background(Brush.horizontalGradient(listOf(method.accent.copy(alpha = 0.30f), panel.copy(alpha = 0.85f), panel.copy(alpha = 0.85f))))
                            .border(1.dp, Brush.linearGradient(listOf(method.accent.copy(alpha = 0.9f), Color.White.copy(alpha = 0.18f), tint.copy(alpha = 0.5f))), shape)
                            .clickable { tap(); runCatching { uriHandler.openUri(method.url) } }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(method.accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(method.fallback, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                            AsyncImage(
                                model = "https://www.google.com/s2/favicons?domain=${method.domain}&sz=128",
                                contentDescription = method.name, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(method.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(method.handle, color = lerp(method.accent, Color.White, 0.45f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open ${method.name}", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportCard(method: SupportMethod, liquidGlass: Boolean, tint: Color) {
    val uriHandler = LocalUriHandler.current
    val tap = rememberHapticTap()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "supportPress")
    val shape = RoundedCornerShape(22.dp)
    val panel = lerp(Color(0xFF101014), lerp(tint, method.accent, 0.6f), if (liquidGlass) 0.16f else 0.12f)

    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(
                Brush.horizontalGradient(listOf(method.accent.copy(alpha = 0.30f), panel.copy(alpha = 0.85f), panel.copy(alpha = 0.85f)))
            )
            .border(
                1.2.dp,
                Brush.linearGradient(listOf(method.accent.copy(alpha = 0.9f), Color.White.copy(alpha = 0.18f), tint.copy(alpha = 0.5f))),
                shape
            )
            .clickable(interactionSource = interaction, indication = null) {
                tap()
                runCatching { uriHandler.openUri(method.url) }
            }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The service's own icon on a white tile, with a brand-colored
        // stand-in underneath until (or unless) it loads.
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(15.dp)).background(method.accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(method.fallback, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            AsyncImage(
                model = "https://www.google.com/s2/favicons?domain=${method.domain}&sz=128",
                contentDescription = method.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSizeCompat().clip(RoundedCornerShape(15.dp))
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(method.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(method.handle, color = lerp(method.accent, Color.White, 0.45f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(method.blurb, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(method.accent.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open ${method.name}", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** fillMaxSize inside the fixed-size icon tile (a plain Modifier, so this
 *  file doesn't depend on being inside a BoxScope helper). */
private fun Modifier.matchParentSizeCompat(): Modifier = this.fillMaxSize()

/** What supporting gets you — shown on the Support page and in the popup. */
internal const val SUPPORTER_PERK_TEXT =
    "\$4.99 or more will give you Stellar supporter benefits for a month!! Just make sure to include your Stellar/Bluesky handle in the note :3"

/**
 * The inside of the "Support Stellar" popup (shown once, on the tenth time
 * Stellar is opened): an X at the top left, how many times the app has been
 * opened, and the same three ways to chip in as the Support page — compact,
 * each one tappable.
 */
@Composable
internal fun SupportPopupContent(openCount: Int, tint: Color, onClose: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val tap = rememberHapticTap()
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                    .clickable { tap(); onClose() },
                contentAlignment = Alignment.Center
            ) { Icon(androidx.compose.material.icons.Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp)) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFFFF4FA1), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Support Stellar", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.size(34.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "You've opened Stellar $openCount times!! If you're enjoying my app, please consider supporting me through any of these platforms.",
            color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 18.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            SUPPORTER_PERK_TEXT,
            color = lerp(Color(0xFFFF4FA1), Color.White, 0.35f), fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            supportMethods.forEach { method ->
                val shape = RoundedCornerShape(16.dp)
                val panel = lerp(Color(0xFF101014), lerp(tint, method.accent, 0.6f), 0.16f)
                Row(
                    Modifier.fillMaxWidth().clip(shape)
                        .background(Brush.horizontalGradient(listOf(method.accent.copy(alpha = 0.30f), panel.copy(alpha = 0.85f), panel.copy(alpha = 0.85f))))
                        .border(1.dp, Brush.linearGradient(listOf(method.accent.copy(alpha = 0.9f), Color.White.copy(alpha = 0.18f), tint.copy(alpha = 0.5f))), shape)
                        .clickable { tap(); runCatching { uriHandler.openUri(method.url) } }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(method.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(method.fallback, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        AsyncImage(
                            model = "https://www.google.com/s2/favicons?domain=${method.domain}&sz=128",
                            contentDescription = method.name, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(method.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(method.handle, color = lerp(method.accent, Color.White, 0.45f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open ${method.name}", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
