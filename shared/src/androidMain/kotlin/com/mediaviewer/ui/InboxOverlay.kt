package com.mediaviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.ui.theme.OledBlack
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel

/**
 * The Hub's Inbox: your Bluesky notifications — likes, reposts, follows,
 * replies, mentions, quotes… (DMs live in the DM list instead). Grouped the
 * way Bluesky groups them ("Sam and 3 others liked your post"), each card
 * wearing the profile colors of whoever it's from, unread ones lit up.
 */
@Composable
fun InboxOverlay(
    items: List<MainViewModel.InboxItem>,
    loading: Boolean,
    liquidGlass: Boolean,
    selfAvatarUrl: String?,
    onLoadMore: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenProfile: (AuthorInfo) -> Unit,
    onClose: () -> Unit
) {
    val tap = rememberHapticTap()
    val profileTint = rememberSelfTint(selfAvatarUrl, NeutralGlassTint)
    BackHandler(onBack = onClose)

    val backdropLayer = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    val backdrop = remember(liquidGlass, backdropLayer) { if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null }

    Box(Modifier.fillMaxSize().zIndex(8f).blockClicksBehind()) {
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
            if (liquidGlass) SpaceSky(profileTint, Modifier.matchParentSize())
        }

        Column(Modifier.fillMaxSize().padding(top = rememberTopCutoutClearance())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(32.dp)
                        .then(if (liquidGlass) Modifier.glassPanel(true, shape = CircleShape, tint = profileTint) else Modifier.clip(CircleShape).background(Color.White.copy(0.14f)))
                        .clickable { tap(); onClose() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(17.dp)) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Email, contentDescription = null, tint = lerp(profileTint, Color.White, 0.55f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Inbox", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Light)
                }
                Spacer(Modifier.size(32.dp))
            }
            HorizontalDivider(color = Color.White.copy(0.08f), thickness = 0.5.dp)

            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing here yet", color = DimGray, fontSize = 13.sp)
                }
                else -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(listState, items.size, loading) {
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                            .collect { last -> if (last >= items.size - 5) onLoadMore() }
                    }
                    LazyColumn(
                        Modifier.fillMaxSize(), state = listState,
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items, key = { it.key }) { item ->
                            InboxCard(
                                item, liquidGlass = liquidGlass, fallbackTint = profileTint, backdrop = backdrop,
                                onOpenPost = { uri -> tap(); onOpenPost(uri) },
                                onOpenProfile = { a -> tap(); onOpenProfile(a) }
                            )
                        }
                        if (loading) item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 1.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun reasonIcon(reason: String): Pair<ImageVector, Color> = when (reason) {
    "like", "like-via-repost" -> Icons.Default.Favorite to Color(0xFFFF4D6D)
    "repost", "repost-via-repost" -> Icons.Default.Repeat to Color(0xFF3DDC84)
    "follow" -> Icons.Default.PersonAdd to Color(0xFF5AB0FF)
    "reply" -> Icons.AutoMirrored.Filled.Reply to Color(0xFF9FA8FF)
    "mention" -> Icons.Default.AlternateEmail to Color(0xFFFFC857)
    "quote" -> Icons.Default.FormatQuote to Color(0xFFC08BFF)
    "starterpack-joined" -> Icons.Default.ViewList to Color(0xFF5AB0FF)
    "verified", "unverified" -> Icons.Default.Verified to Color(0xFF5AB0FF)
    else -> Icons.Default.NotificationsActive to Color.White
}

private fun reasonText(reason: String): String = when (reason) {
    "like" -> "liked your post"
    "like-via-repost" -> "liked your repost"
    "repost" -> "reposted your post"
    "repost-via-repost" -> "reposted your repost"
    "follow" -> "followed you"
    "reply" -> "replied to you"
    "mention" -> "mentioned you"
    "quote" -> "quoted your post"
    "starterpack-joined" -> "joined via your starter pack"
    "verified" -> "verified your account"
    "unverified" -> "removed your verification"
    "subscribed-post" -> "posted"
    "contact-match" -> "is on Bluesky"
    else -> reason
}

/** "3m", "2h", "5d", "4w" */
private fun ago(iso: String): String = runCatching {
    val secs = (System.currentTimeMillis() - java.time.Instant.parse(iso).toEpochMilli()) / 1000
    when {
        secs < 60 -> "now"
        secs < 3600 -> "${secs / 60}m"
        secs < 86_400 -> "${secs / 3600}h"
        secs < 604_800 -> "${secs / 86_400}d"
        else -> "${secs / 604_800}w"
    }
}.getOrDefault("")

@Composable
private fun InboxCard(
    item: MainViewModel.InboxItem,
    liquidGlass: Boolean,
    fallbackTint: Color,
    backdrop: GlassBackdrop?,
    onOpenPost: (String) -> Unit,
    onOpenProfile: (AuthorInfo) -> Unit
) {
    val lead = item.authors.first()
    val tint = if (lead.did.isNotBlank()) rememberAuthorProfileTint(lead.did, lead.avatarUrl) else fallbackTint
    val shape = RoundedCornerShape(16.dp)
    val unread = !item.isRead
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (liquidGlass) Modifier.glassPanel(true, shape = shape, tint = tint)
                else Modifier.clip(shape).background(lerp(Color(0xFF16161B), tint, 0.18f))
            )
            .then(if (unread) Modifier.border(1.2.dp, lerp(tint, Color.White, 0.25f).copy(alpha = 0.9f), shape) else Modifier)
            .clickable {
                val uri = item.postUri
                if (uri != null) onOpenPost(uri) else onOpenProfile(lead)
            }
            .padding(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            // Who: up to six overlapping avatars.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shown = item.authors.take(6)
                Box(Modifier.height(30.dp).width((30 + (shown.size - 1) * 20).dp)) {
                    shown.forEachIndexed { i, a ->
                        Box(
                            Modifier.offset(x = (i * 20).dp).size(30.dp).clip(CircleShape)
                                .background(Color(0xFF1C1C22)).border(1.5.dp, Color(0xFF0E0E12), CircleShape)
                                .clickable { onOpenProfile(a) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (a.avatarUrl != null) AsyncImage(
                                model = a.avatarUrl, contentDescription = a.displayName, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            ) else Text(a.displayName.take(1).uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(ago(item.indexedAt), color = DimGray, fontSize = 11.sp)
                if (unread) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(lerp(tint, Color.White, 0.35f)))
                }
            }
            Spacer(Modifier.height(6.dp))
            val others = item.authors.size - 1
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lead.displayName.ifBlank { "@" + lead.handle }) }
                    if (others > 0) append(" and $others ${if (others == 1) "other" else "others"}")
                    append(" ")
                    append(reasonText(item.reason))
                },
                color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (item.snippet.isNotBlank() || item.thumbUrl != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.22f)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.snippet.isNotBlank()) {
                        Text(
                            item.snippet, color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, lineHeight = 17.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                    } else Spacer(Modifier.weight(1f))
                    if (item.thumbUrl != null) {
                        Spacer(Modifier.width(8.dp))
                        AsyncImage(
                            model = item.thumbUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
            }
        }
    }
}
