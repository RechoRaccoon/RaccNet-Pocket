package com.mediaviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.FormatListBulleted
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

private enum class PickerTab { LISTS, STARTER_PACKS }

private data class CombinedEntry(
    val name: String,
    val listUri: String,
    val starterPackListUri: String,
    val avatarUrl: String?
)

// Big Update #10: like the Share page, this is rendered in-place (never a
// separate `Dialog`/Android window) so its glass sheet can share the current
// post's live backdrop layer and, in Glass mode, show no outer background of
// its own — the post stays genuinely visible around it instead of being
// dimmed toward it.
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
    onTabChange: (String) -> Unit,
    onSelectList: (listUri: String, additionalUri: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var activeTab by remember(initialTab) {
        mutableStateOf(if (initialTab == "STARTER_PACKS") PickerTab.STARTER_PACKS else PickerTab.LISTS)
    }
    var swipeDx by remember { mutableFloatStateOf(0f) }

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
            .zIndex(10f)
            .then(
                // Non-glass mode keeps a real dimming scrim since there's no
                // glass surface underneath to separate the popup from the post.
                if (liquidGlass) Modifier else Modifier.background(Color.Black.copy(alpha = 0.65f))
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        val sheetModifier = Modifier
            .fillMaxWidth(0.88f)
            .heightIn(min = 140.dp, max = 460.dp)
            // Horizontal drag to switch tabs — runs at Main pass BEFORE the
            // Final-pass consumer below, so it always sees events first.
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
            // Absorbs taps so they don't fall through to the scrim's dismiss
            // handler. Using `clickable` (tap-gesture detection only) instead of
            // a raw Final-pass consumer means it never competes with the
            // LazyColumn's own drag/scroll recognition, which was causing the
            // list to need multiple attempts before it would scroll.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { }

            @Composable
            fun SheetContent() {
                Column(modifier = Modifier.fillMaxWidth()) {

                    // ── Header ────────────────────────────────────────────────
                    if (combineMode) {
                        // Combined mode: single centered title — styled identically to
                        // the regular tab header (no icon, no extra decoration).
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Text("Add To", color = Color.White, fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                        }
                    } else {
                        // Normal mode: tab switcher with "Add To" centered
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp)
                        ) {
                            TabButton(
                                label    = "My Lists",
                                selected = activeTab == PickerTab.LISTS,
                                liquidGlass = liquidGlass,
                                dominantColor = dominantColor,
                                backdrop = backdrop,
                                modifier = Modifier.align(Alignment.CenterStart),
                                onClick  = { switchTab(PickerTab.LISTS) }
                            )
                            Text("Add To", color = Color.White, fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.Center))
                            TabButton(
                                label    = "Starter Packs",
                                selected = activeTab == PickerTab.STARTER_PACKS,
                                liquidGlass = liquidGlass,
                                dominantColor = dominantColor,
                                backdrop = backdrop,
                                modifier = Modifier.align(Alignment.CenterEnd),
                                onClick  = { switchTab(PickerTab.STARTER_PACKS) }
                            )
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    // ── Content ───────────────────────────────────────────────
                    if (combineMode) {
                        PickerBody(loading = listsLoading) {
                            if (combinedEntries.isEmpty() && !listsLoading) {
                                item { EmptyLabel("No matching Lists + Starter Packs found.\nMake sure they share the same name.") }
                            }
                            items(combinedEntries, key = { it.listUri }) { entry ->
                                EntryRow(
                                    name      = entry.name,
                                    subtitle  = null,
                                    avatarUrl = entry.avatarUrl,
                                    isPack    = false,
                                    liquidGlass = liquidGlass,
                                    onClick   = { onSelectList(entry.listUri, entry.starterPackListUri) }
                                )
                            }
                        }
                    } else {
                        AnimatedContent(
                            targetState = activeTab,
                            transitionSpec = {
                                val dir = if (targetState == PickerTab.STARTER_PACKS) 1 else -1
                                (slideInHorizontally(tween(180)) { it * dir } + fadeIn(tween(150))) togetherWith
                                (slideOutHorizontally(tween(180)) { -it * dir } + fadeOut(tween(120)))
                            },
                            label = "tab"
                        ) { tab ->
                            PickerBody(loading = listsLoading) {
                                when (tab) {
                                    PickerTab.LISTS -> {
                                        if (lists.isEmpty() && !listsLoading) item { EmptyLabel("You have no lists yet.") }
                                        items(lists, key = { it.uri }) { list ->
                                            EntryRow(
                                                name      = list.name,
                                                subtitle  = list.itemCount?.let { "$it members" },
                                                avatarUrl = list.avatar,
                                                isPack    = false,
                                                liquidGlass = liquidGlass,
                                                onClick   = { onSelectList(list.uri, null) }
                                            )
                                        }
                                    }
                                    PickerTab.STARTER_PACKS -> {
                                        if (starterPacks.isEmpty() && !listsLoading) item { EmptyLabel("You have no Starter Packs yet.") }
                                        items(starterPacks, key = { it.uri }) { pack ->
                                            val listUri = pack.record?.list ?: return@items
                                            EntryRow(
                                                name      = pack.record.name,
                                                subtitle  = pack.listItemCount?.let { "$it members" },
                                                avatarUrl = null,
                                                isPack    = true,
                                                liquidGlass = liquidGlass,
                                                onClick   = { onSelectList(listUri, null) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        // Big Update #10 / item 3: no outer background of its own in Glass mode
        // (the post stays visible around it), and the sheet's own glass reflects
        // that same post in real time via [backdrop], exactly like the main
        // glass buttons.
        if (liquidGlass) {
            LiquidGlassSurface(
                modifier = sheetModifier, shape = RoundedCornerShape(20.dp),
                tint = dominantColor, backdrop = backdrop
            ) { SheetContent() }
        } else {
            Box(sheetModifier.clip(RoundedCornerShape(20.dp)).background(OffBlack)) { SheetContent() }
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
    val shape = RoundedCornerShape(10.dp)
    if (liquidGlass && selected) {
        LiquidGlassSurface(
            modifier = modifier, shape = shape, tint = dominantColor, backdrop = backdrop
        ) {
            Text(
                text = label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }
    } else {
        Text(
            text       = label,
            color      = if (selected) Color.White else DimGray,
            fontSize   = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier   = modifier
                .clip(shape)
                .background(if (selected) Color.White.copy(0.1f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
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
private fun EntryRow(name: String, subtitle: String?, avatarUrl: String?, isPack: Boolean, liquidGlass: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (avatarUrl != null) {
            AsyncImage(model = avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(32.dp).clip(CircleShape))
        } else {
            // Item 3: placeholder icon is white (not grey) in Glass mode so it
            // reads clearly against the clear/tinted glass background.
            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(0.09f)),
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
            Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (subtitle != null) Text(subtitle, color = DimGray, fontSize = 11.sp)
        }
    }
}
