package com.mediaviewer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.ListMember
import com.mediaviewer.model.ProfileListEntry
import com.mediaviewer.model.ProfileListKind
import com.mediaviewer.ui.compat.rememberPlatformView
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.StellarOfficial
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** What a Lists/Feeds row's button does (and says) for an entry. */
internal fun ProfileListEntry.actionLabel(savedFeedUris: Set<String>): String = when (kind) {
    ProfileListKind.FEED -> if (uri in savedFeedUris) "Added" else "Add"
    ProfileListKind.LIST -> if (uri in savedFeedUris) "Pinned" else "Pin to Feeds"
    ProfileListKind.STARTER_PACK -> "Follow All"
    ProfileListKind.MOD_LIST -> if (blockUri != null) "Unblock All" else "Block All"
}

/**
 * The profile's "Lists/Feeds" tab: one compact glass row per feed, list,
 * starter pack and moderation list. With [MainViewModel.ProfileOverlayState.listKindFilter]
 * null ("All") the four kinds follow each other in that order, each under a
 * small header.
 */
internal fun LazyListScope.profileListsRows(
    state: MainViewModel.ProfileOverlayState,
    liquidGlass: Boolean,
    tint: Color,
    savedFeedUris: Set<String>,
    listActions: Map<String, String>,
    onOpen: (ProfileListEntry) -> Unit,
    onAction: (ProfileListEntry) -> Unit
) {
    val lists = state.lists
    val filter = state.listKindFilter
    val entries = if (filter == null) lists.entries else lists.entries.filter { it.kind == filter }
    when {
        lists.loading && lists.entries.isEmpty() || !lists.loaded && !lists.failed -> item(key = "lists_loading") {
            Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
            }
        }
        entries.isEmpty() -> item(key = "lists_empty") {
            Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        lists.failed -> "Couldn't load these"
                        filter == null -> "No feeds or lists yet"
                        else -> "No ${filter.label.lowercase()} yet"
                    },
                    color = DimGray, fontSize = 13.sp
                )
            }
        }
        else -> ProfileListKind.entries.forEach { kind ->
            val ofKind = entries.filter { it.kind == kind }
            if (ofKind.isEmpty()) return@forEach
            if (filter == null) item(key = "lists_header_${kind.name}") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        kind.label.uppercase(), color = lerp(tint, Color.White, 0.6f),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.size(8.dp))
                    Box(Modifier.weight(1f).height(0.5.dp).background(Color.White.copy(alpha = 0.10f)))
                    Spacer(Modifier.size(8.dp))
                    Text("${ofKind.size}", color = DimGray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            items(ofKind, key = { "lists_${it.kind.name}_${it.uri}" }) { entry ->
                ProfileListRow(
                    entry = entry, liquidGlass = liquidGlass, tint = tint,
                    label = listActions[entry.uri] ?: entry.actionLabel(savedFeedUris),
                    busy = listActions[entry.uri]?.let { it.endsWith("…") || it.contains('/') } == true,
                    done = listActions[entry.uri] == null && (
                        (entry.kind == ProfileListKind.FEED || entry.kind == ProfileListKind.LIST) && entry.uri in savedFeedUris
                    ) || listActions[entry.uri] == "Followed",
                    onOpen = { onOpen(entry) },
                    onAction = { onAction(entry) }
                )
            }
        }
    }
}

@Composable
private fun ProfileListRow(
    entry: ProfileListEntry,
    liquidGlass: Boolean,
    tint: Color,
    label: String,
    busy: Boolean,
    done: Boolean,
    onOpen: () -> Unit,
    onAction: () -> Unit
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(16.dp)
    val danger = entry.kind == ProfileListKind.MOD_LIST && entry.blockUri == null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .glassPanel(liquidGlass, tint = tint.copy(alpha = 0.45f), shape = shape)
            .clickable { tap(); onOpen() }
            .padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val coverShape = RoundedCornerShape(11.dp)
        Box(
            Modifier.size(44.dp).clip(coverShape)
                .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.15f), lerp(tint, Color.Black, 0.45f)))),
            contentAlignment = Alignment.Center
        ) {
            if (!entry.avatarUrl.isNullOrBlank()) AsyncImage(
                model = entry.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(coverShape)
            ) else Text(entry.name.take(1).uppercase(), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val count = entry.itemCount?.let { n ->
                when (entry.kind) {
                    ProfileListKind.FEED -> "$n like${if (n == 1) "" else "s"}"
                    else -> "$n account${if (n == 1) "" else "s"}"
                }
            }
            val sub = listOfNotNull(count, entry.description?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, color = DimGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val pill = RoundedCornerShape(14.dp)
        val fill = when {
            done -> Color.White.copy(alpha = 0.08f)
            danger -> Color(0xFFB3261E).copy(alpha = 0.55f)
            else -> lerp(tint, Color.Black, 0.3f).copy(alpha = 0.8f)
        }
        val rim = when {
            done -> Color.White.copy(alpha = 0.18f)
            danger -> Color(0xFFFF8A80).copy(alpha = 0.8f)
            else -> lerp(tint, Color.White, 0.35f).copy(alpha = 0.85f)
        }
        Box(
            Modifier.clip(pill).background(fill).border(1.dp, rim, pill)
                .clickable(enabled = !busy && !done) { onAction() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label, color = if (done) Color.White.copy(alpha = 0.6f) else Color.White,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
        }
    }
}

/**
 * Tapping a list, starter pack or moderation list: every account on it, in
 * the same compact popup as Settings' Blocked Accounts. On your own lists
 * each account has an X at the far right that takes them off the list.
 */
@Composable
fun ListMembersDialog(
    state: MainViewModel.ListMembersState,
    tint: Color,
    onOpenProfile: (AuthorInfo) -> Unit,
    onRemove: (ListMember) -> Unit,
    onClose: () -> Unit
) {
    val tap = rememberHapticTap()
    Dialog(onDismissRequest = onClose, properties = com.mediaviewer.ui.compat.edgeToEdgeDialogProperties()) {
        com.mediaviewer.ui.compat.DialogBlurBehind(radius = 48, dimAmount = 0.45f)
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(26.dp)
            val panel = lerp(Color(0xFF101014), tint, 0.16f)
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    .widthIn(max = 440.dp).fillMaxWidth()
                    .heightIn(max = maxHeight * 0.82f)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(lerp(panel, tint, 0.12f).copy(alpha = 0.97f), panel.copy(alpha = 0.97f))))
                    .border(1.2.dp, Brush.linearGradient(listOf(tint.copy(alpha = 0.9f), Color.White.copy(alpha = 0.25f), tint.copy(alpha = 0.6f))), shape)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                            .clickable { tap(); onClose() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(19.dp)) }
                    Text(
                        state.entry.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                    )
                    Spacer(Modifier.size(38.dp))
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
                if (state.members.isNotEmpty()) {
                    Text(
                        "${state.members.size} account${if (state.members.size == 1) "" else "s"}",
                        color = lerp(tint, Color.White, 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    when {
                        state.loading -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                        }
                        state.members.isEmpty() -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            Text(if (state.failed) "Couldn't load this list" else "No accounts here yet", color = DimGray, fontSize = 13.sp)
                        }
                        else -> LazyColumn(contentPadding = PaddingValues(top = 2.dp, bottom = 10.dp)) {
                            items(state.members, key = { it.author.did }) { member ->
                                val author = member.author
                                Row(
                                    Modifier.fillMaxWidth().clickable { tap(); onOpenProfile(author) }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (author.avatarUrl != null) AsyncImage(
                                            model = author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                                        ) else Text(author.displayName.take(1).uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(author.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("@${author.handle}", color = DimGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (state.isOwn) {
                                        val removing = author.did in state.removing
                                        Box(
                                            Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                                                .clickable(enabled = !removing) { onRemove(member) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (removing) CircularProgressIndicator(Modifier.size(13.dp), color = Color.White, strokeWidth = 1.5.dp)
                                            else Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(15.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The pink "Supporter" label in a profile's stats row, with a highlight
 *  that sweeps across it on a loop. Tapping opens Settings' support page. */
@Composable
fun SupporterBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tap = rememberHapticTap()
    val base = Color(StellarOfficial.SUPPORTER_COLOR)
    val sweep by rememberInfiniteTransition().animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart)
    )
    var width by remember { mutableStateOf(1f) }
    val brush = Brush.linearGradient(
        colorStops = arrayOf(0f to base, 0.42f to base, 0.5f to Color.White, 0.58f to base, 1f to base),
        start = Offset((sweep - 1f) * width, 0f),
        end = Offset((sweep + 1f) * width, width * 0.35f)
    )
    Text(
        "Supporter", maxLines = 1, softWrap = false,
        style = TextStyle(brush = brush, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        onTextLayout = { width = it.size.width.toFloat().coerceAtLeast(1f) },
        modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable { tap(); onClick() }
    )
}

private class ConfettiPiece(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val w: Float, val h: Float, val color: Color,
    var spin: Float, val spinSpeed: Float,
    var flutter: Float, val flutterSpeed: Float,
    val drag: Float, val sway: Float
)

/**
 * Confetti for a Stellar supporter's profile: bursts up and inward from both
 * sides, drifts down slowly while tumbling, then fades. Every piece is
 * generated fresh from a new seed, so no two runs look the same. Plays once
 * per [playKey], with a hard haptic at the burst.
 */
@Composable
fun SupporterConfetti(playKey: Any, modifier: Modifier = Modifier) {
    val view = rememberPlatformView()
    var pieces by remember(playKey) { mutableStateOf<List<ConfettiPiece>?>(null) }
    var frame by remember(playKey) { mutableStateOf(0) }
    var alpha by remember(playKey) { mutableStateOf(1f) }
    var area by remember { mutableStateOf(Size.Zero) }

    LaunchedEffect(playKey) {
        val area = snapshotFlow { area }.first { it.width > 0f && it.height > 0f }
        val rnd = Random(com.mediaviewer.ui.compat.uptimeMillis() xor (playKey.hashCode().toLong() shl 16))
        val unit = area.width / 400f
        val palette = listOf(
            Color(0xFFFF4FA1), Color(0xFFFFD166), Color(0xFF4FC3F7), Color(0xFF7CFFB2),
            Color(0xFFB388FF), Color(0xFFFF8A65), Color.White
        )
        val count = 90 + rnd.nextInt(50)
        val made = List(count) { i ->
            val left = i % 2 == 0
            // Aimed up and inward, with a wide spread.
            val angle = (if (left) -75f + rnd.nextFloat() * 50f else -105f - rnd.nextFloat() * 50f) * (PI.toFloat() / 180f)
            val speed = (760f + rnd.nextFloat() * 900f) * unit
            val long = rnd.nextFloat() < 0.35f
            ConfettiPiece(
                x = if (left) -8f * unit else area.width + 8f * unit,
                y = area.height * (0.52f + rnd.nextFloat() * 0.22f),
                vx = abs(cos(angle)) * speed * (if (left) 1f else -1f) * (0.5f + rnd.nextFloat() * 0.7f),
                vy = sin(angle) * speed,
                w = (5f + rnd.nextFloat() * 5f) * unit,
                h = (if (long) 10f + rnd.nextFloat() * 8f else 5f + rnd.nextFloat() * 4f) * unit,
                color = palette[rnd.nextInt(palette.size)],
                spin = rnd.nextFloat() * 360f, spinSpeed = (rnd.nextFloat() - 0.5f) * 720f,
                flutter = rnd.nextFloat() * 6.28f, flutterSpeed = 4f + rnd.nextFloat() * 9f,
                drag = 1.6f + rnd.nextFloat() * 1.6f, sway = (18f + rnd.nextFloat() * 50f) * unit
            )
        }
        pieces = made
        runCatching { view.crunchHaptic() }
        val gravity = 620f * unit
        val terminal = 150f * unit
        val total = 5.2f
        val fadeFrom = 3.6f
        var last = 0L
        var t = 0f
        while (t < total) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                t += dt
                for (p in made) {
                    val damp = (1f - p.drag * dt).coerceAtLeast(0f)
                    p.vx *= damp
                    p.vy = if (p.vy < 0f) p.vy * damp + gravity * dt else (p.vy + gravity * 0.5f * dt).coerceAtMost(terminal * (0.6f + p.drag * 0.25f))
                    p.flutter += p.flutterSpeed * dt
                    p.x += (p.vx + sin(p.flutter * 0.6f) * p.sway) * dt
                    p.y += p.vy * dt
                    p.spin += p.spinSpeed * dt
                }
                alpha = if (t <= fadeFrom) 1f else (1f - (t - fadeFrom) / (total - fadeFrom)).coerceIn(0f, 1f)
                frame++
            }
        }
        pieces = emptyList()
    }

    Canvas(modifier.fillMaxSize()) {
        if (area != size) area = size
        val list = pieces ?: return@Canvas
        @Suppress("UNUSED_VARIABLE") val tick = frame // redraw every simulated frame
        val a = alpha
        for (p in list) {
            // The tumble: the piece's visible height swings through zero as
            // it flips, and its far side reads a little darker.
            val flip = cos(p.flutter)
            val h = (p.h * abs(flip)).coerceAtLeast(1f)
            val c = if (flip < 0f) lerp(p.color, Color.Black, 0.28f) else p.color
            rotate(p.spin, pivot = Offset(p.x, p.y)) {
                drawRect(c.copy(alpha = a), topLeft = Offset(p.x - p.w / 2f, p.y - h / 2f), size = Size(p.w, h))
            }
        }
    }
}
