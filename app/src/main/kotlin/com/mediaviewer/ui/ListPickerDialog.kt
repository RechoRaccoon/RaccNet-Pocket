package com.mediaviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.mediaviewer.model.BskyList
import com.mediaviewer.model.BskyStarterPackView
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap

private enum class PickerTab { LISTS, STARTER_PACKS }

private data class CombinedEntry(
    val name: String,
    val listUri: String,
    val starterPackListUri: String,
    val avatarUrl: String?
)

/**
 * Add To — the account's lists and starter packs, each with a + (add them)
 * or − (take them back off) button on the far right. Tapping one never
 * closes the popup; the round X in the corner (or Back, or tapping outside)
 * does. Same presentation as Share To / Quote Repost: in-place glass that
 * live-blurs the post, faded/scaled in and out via [FadingPopupHost].
 */
@Composable
fun ListPickerDialog(
    lists: List<BskyList>,
    starterPacks: List<BskyStarterPackView>,
    listsLoading: Boolean,
    initialTab: String,
    combineMode: Boolean,
    liquidGlass: Boolean,
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    /** List URI -> list item URI for every list the account is already on. */
    memberships: Map<String, String> = emptyMap(),
    membershipsLoading: Boolean = false,
    /** List URIs with an add/remove in flight. */
    busy: Set<String> = emptySet(),
    onTabChange: (String) -> Unit,
    /** + / −: add to (or remove from) the list, and its merged starter pack. */
    onToggle: (listUri: String, additionalUri: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var activeTab by remember(initialTab) {
        mutableStateOf(if (initialTab == "STARTER_PACKS") PickerTab.STARTER_PACKS else PickerTab.LISTS)
    }
    var swipeDx by remember { mutableFloatStateOf(0f) }
    val tint = dominantColor

    fun switchTab(tab: PickerTab) {
        activeTab = tab
        onTabChange(if (tab == PickerTab.LISTS) "LISTS" else "STARTER_PACKS")
    }

    // Compute combined entries (List + StarterPack with matching name)
    val combinedEntries = remember(lists, starterPacks) {
        val packByName = starterPacks.mapNotNull { pack ->
            pack.record?.name?.let { name -> name to pack.record.list }
        }.toMap()
        lists.mapNotNull { list ->
            packByName[list.name]?.let { packListUri ->
                CombinedEntry(name = list.name, listUri = list.uri, starterPackListUri = packListUri, avatarUrl = list.avatar)
            }
        }
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (liquidGlass) 0.22f else 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(top = rememberTopCutoutClearance() + 8.dp)
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        PopupSheetSurface(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp)
                .fillMaxHeight(0.7f)
                // Horizontal drag switches between My Lists and Starter Packs.
                .pointerInput(combineMode) {
                    if (!combineMode) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (swipeDx < -60f) switchTab(PickerTab.STARTER_PACKS)
                                else if (swipeDx > 60f) switchTab(PickerTab.LISTS)
                                swipeDx = 0f
                            },
                            onDragCancel = { swipeDx = 0f }
                        ) { _, dragAmount -> swipeDx += dragAmount }
                    }
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                PopupSheetHeader(
                    title = "Add To",
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onClose = onDismiss
                )
                if (!combineMode) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TabButton(
                            label = "My Lists",
                            selected = activeTab == PickerTab.LISTS,
                            liquidGlass = liquidGlass,
                            dominantColor = tint,
                            backdrop = backdrop,
                            modifier = Modifier.weight(1f),
                            onClick = { switchTab(PickerTab.LISTS) }
                        )
                        TabButton(
                            label = "Starter Packs",
                            selected = activeTab == PickerTab.STARTER_PACKS,
                            liquidGlass = liquidGlass,
                            dominantColor = tint,
                            backdrop = backdrop,
                            modifier = Modifier.weight(1f),
                            onClick = { switchTab(PickerTab.STARTER_PACKS) }
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(top = 4.dp))

                @Composable
                fun Entry(name: String, subtitle: String?, avatarUrl: String?, isPack: Boolean, listUri: String, additionalUri: String?) {
                    EntryRow(
                        name = name, subtitle = subtitle, avatarUrl = avatarUrl, isPack = isPack,
                        liquidGlass = liquidGlass, tint = tint,
                        isMember = memberships.containsKey(listUri),
                        busy = listUri in busy,
                        membershipKnown = !membershipsLoading,
                        onToggle = { onToggle(listUri, additionalUri) }
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (combineMode) {
                        PickerBody(loading = listsLoading) {
                            if (combinedEntries.isEmpty() && !listsLoading) {
                                item { EmptyLabel("No matching Lists + Starter Packs found.\nMake sure they share the same name.") }
                            }
                            items(combinedEntries, key = { it.listUri }) { entry ->
                                Entry(entry.name, null, entry.avatarUrl, false, entry.listUri, entry.starterPackListUri)
                            }
                        }
                    } else {
                        AnimatedContent(
                            targetState = activeTab,
                            transitionSpec = {
                                val dir = if (targetState == PickerTab.STARTER_PACKS) 1 else -1
                                (slideInHorizontally(tween(200)) { it * dir } + fadeIn(tween(170))) togetherWith
                                (slideOutHorizontally(tween(200)) { -it * dir } + fadeOut(tween(130)))
                            },
                            label = "tab"
                        ) { tab ->
                            PickerBody(loading = listsLoading) {
                                when (tab) {
                                    PickerTab.LISTS -> {
                                        if (lists.isEmpty() && !listsLoading) item { EmptyLabel("You have no lists yet.") }
                                        items(lists, key = { it.uri }) { list ->
                                            Entry(list.name, list.itemCount?.let { "$it members" }, list.avatar, false, list.uri, null)
                                        }
                                    }
                                    PickerTab.STARTER_PACKS -> {
                                        if (starterPacks.isEmpty() && !listsLoading) item { EmptyLabel("You have no Starter Packs yet.") }
                                        items(starterPacks, key = { it.uri }) { pack ->
                                            val listUri = pack.record?.list ?: return@items
                                            Entry(pack.record.name, pack.listItemCount?.let { "$it members" }, null, true, listUri, null)
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

// ─── Helpers ──────────────────────────────────────────────────────────────────

@Composable
private fun TabButton(
    label: String,
    selected: Boolean,
    liquidGlass: Boolean,
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(14.dp)
    val m = modifier.height(34.dp).clip(shape).clickable(onClick = { tap(); onClick() })
    if (liquidGlass && selected) {
        LiquidGlassSurface(modifier = m, shape = shape, tint = dominantColor, backdrop = backdrop, contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Box(
            m.background(if (selected) Color.White.copy(0.14f) else Color.White.copy(0.04f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label, color = if (selected) Color.White else DimGray, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun PickerBody(loading: Boolean, content: LazyListScope.() -> Unit) {
    if (loading) {
        Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 1.5.dp, modifier = Modifier.size(26.dp))
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp), content = content)
    }
}

@Composable
private fun EmptyLabel(text: String) {
    Box(Modifier.fillMaxWidth().height(90.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(text, color = DimGray, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun EntryRow(
    name: String, subtitle: String?, avatarUrl: String?, isPack: Boolean,
    liquidGlass: Boolean = false, tint: Color = NeutralGlassTint,
    isMember: Boolean, busy: Boolean, membershipKnown: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (avatarUrl != null) {
            AsyncImage(model = avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            // Item 3: placeholder icon is white (not grey) in Glass mode so it
            // reads clearly against the clear/tinted glass background.
            Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(0.09f)),
                contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPack) Icons.Default.Groups else Icons.Default.FormatListBulleted,
                    contentDescription = null,
                    tint = if (liquidGlass) Color.White else DimGray,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = DimGray, fontSize = 11.sp)
        }
        // One button: + adds them, − takes them back off.
        val bg by animateColorAsState(
            if (isMember) androidx.compose.ui.graphics.lerp(tint, Color.White, 0.2f).copy(alpha = 0.9f) else Color.White.copy(alpha = 0.1f),
            label = "addToBg"
        )
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, androidx.compose.ui.graphics.lerp(tint, Color.White, 0.35f).copy(alpha = if (isMember) 0f else 0.6f), CircleShape)
                .clickable(enabled = !busy && membershipKnown, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            when {
                busy || !membershipKnown -> CircularProgressIndicator(Modifier.size(15.dp), color = Color.White, strokeWidth = 1.5.dp)
                else -> AnimatedContent(
                    targetState = isMember,
                    transitionSpec = { (scaleIn(tween(180)) + fadeIn(tween(180))) togetherWith (scaleOut(tween(140)) + fadeOut(tween(140))) },
                    label = "addToIcon"
                ) { member ->
                    Icon(
                        if (member) Icons.Default.Remove else Icons.Default.Add,
                        contentDescription = if (member) "Remove" else "Add",
                        tint = Color.White, modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
