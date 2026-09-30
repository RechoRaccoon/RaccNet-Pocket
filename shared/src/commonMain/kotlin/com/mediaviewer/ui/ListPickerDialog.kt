package com.mediaviewer.ui

import com.mediaviewer.ui.compat.navBarSpace

import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.ui.compat.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.BskyList
import com.mediaviewer.model.BskyStarterPackView
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap

/** Add To's tabs, in order. [key] is what's remembered between openings. */
private enum class PickerTab(val key: String, val label: String, val createLabel: String) {
    LISTS("LISTS", "Lists", "Create new List"),
    STARTER_PACKS("STARTER_PACKS", "Starter Packs", "Create new Starter Pack"),
    BOTH("BOTH", "Both", "Create merged List + Starter Pack"),
    MODLISTS("MODLISTS", "Moderation Lists", "Create new Moderation List")
}

private data class PickerEntry(
    val key: String,
    val name: String,
    val subtitle: String?,
    val avatarUrl: String?,
    val kind: PickerTab,
    /** The list the account is added to / removed from. */
    val listUri: String,
    /** "Both": the same-named starter pack's list, kept in step. */
    val additionalUri: String? = null,
    /** Every record renamed together when the name is edited. */
    val renameUris: List<String> = emptyList()
)

/**
 * Add To — your Lists, Starter Packs, both at once (a list and a starter pack
 * that share a name) or Moderation Lists, each with a + (add them) or −
 * (take them back off) button on the far right; the popup stays open until
 * its X, Back or a tap outside. The last row of every tab creates a new one.
 *
 * Only as tall as its rows need (capped to the screen). In-place glass that
 * live-blurs the post, faded/scaled in and out via [FadingPopupHost].
 */
@Composable
fun ListPickerDialog(
    lists: List<BskyList>,
    starterPacks: List<BskyStarterPackView>,
    listsLoading: Boolean,
    initialTab: String,
    liquidGlass: Boolean,
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    /** List URI -> list item URI for every list the account is already on. */
    memberships: Map<String, String> = emptyMap(),
    /** List URIs with an add/remove in flight. */
    busy: Set<String> = emptySet(),
    creating: Boolean = false,
    onTabChange: (String) -> Unit,
    onToggle: (listUri: String, additionalUri: String?) -> Unit,
    /** (tab key, name, description, cover image, onDone(error or null)) */
    onCreate: (String, String, String, com.mediaviewer.platform.PlatformUri?, (String?) -> Unit) -> Unit = { _, _, _, _, _ -> },
    /** Double-tap a name to rename: (records to rename, new name, onDone(error or null)). */
    onRename: (List<String>, String, (String?) -> Unit) -> Unit = { _, _, done -> done(null) },
    onDismiss: () -> Unit
) {
    var activeTab by remember(initialTab) {
        mutableStateOf(PickerTab.entries.firstOrNull { it.key == initialTab } ?: PickerTab.LISTS)
    }
    // Non-null while the "Create new …" form is showing.
    var creatingKind by remember { mutableStateOf<PickerTab?>(null) }
    val tint = dominantColor

    fun switchTab(tab: PickerTab) {
        activeTab = tab
        onTabChange(tab.key)
    }

    // Member counts / "List + Starter Pack" subtitles are gone — just the
    // names. Every tab is ordered by the list you most recently added
    // someone to (tracked on-device, see ListRecency), worked out once when
    // the tab is shown so rows don't jump around while you're adding.
    val entries: List<PickerEntry> = remember(lists, starterPacks, activeTab) {
        val curate = lists.filter { !it.purpose.contains("modlist") && !it.purpose.contains("referencelist") }
        val raw = when (activeTab) {
            PickerTab.LISTS -> curate.map {
                PickerEntry(it.uri, it.name, null, it.avatar, PickerTab.LISTS, it.uri, renameUris = listOf(it.uri))
            }
            PickerTab.MODLISTS -> lists.filter { it.purpose.contains("modlist") }.map {
                PickerEntry(it.uri, it.name, null, it.avatar, PickerTab.MODLISTS, it.uri, renameUris = listOf(it.uri))
            }
            PickerTab.STARTER_PACKS -> starterPacks.mapNotNull { pack ->
                val rec = pack.record ?: return@mapNotNull null
                if (rec.list.isBlank()) return@mapNotNull null
                PickerEntry(pack.uri, rec.name, null, null, PickerTab.STARTER_PACKS, rec.list, renameUris = listOf(pack.uri, rec.list))
            }
            PickerTab.BOTH -> {
                // name -> (the pack's own list, the pack)
                val packByName = starterPacks.mapNotNull { p -> p.record?.let { r -> r.name to (r.list to p.uri) } }
                    .filter { it.second.first.isNotBlank() }.toMap()
                curate.mapNotNull { list ->
                    packByName[list.name]?.let { (packList, packUri) ->
                        PickerEntry(
                            list.uri, list.name, null, list.avatar, PickerTab.BOTH, list.uri, packList,
                            renameUris = listOf(list.uri, packUri, packList)
                        )
                    }
                }
            }
        }
        raw.sortedByDescending { e ->
            maxOf(
                com.mediaviewer.util.ListRecency.lastAdded(e.listUri),
                e.additionalUri?.let { com.mediaviewer.util.ListRecency.lastAdded(it) } ?: 0L
            )
        }
    }

    BackHandler(onBack = { if (creatingKind != null) creatingKind = null else onDismiss() })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (liquidGlass) 0.22f else 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(top = rememberTopCutoutClearance())
            .imePadding()
            .windowInsetsPadding(WindowInsets.navBarSpace)
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        // Centered in the middle of the screen.
        contentAlignment = Alignment.Center
    ) {
        PopupSheetSurface(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            // Wraps its rows; the list inside scrolls once it runs out of room.
            modifier = Modifier.fillMaxWidth().wrapContentHeight().animateContentSize()
        ) {
            Column(Modifier.fillMaxWidth()) {
                PopupSheetHeader(
                    title = "Add To",
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onClose = onDismiss
                )
                AnimatedContent(
                    targetState = creatingKind,
                    transitionSpec = {
                        val dir = if (targetState != null) 1 else -1
                        (slideInHorizontally(tween(220)) { it * dir / 3 } + fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(200)) { -it * dir / 3 } + fadeOut(tween(150)))
                    },
                    label = "addToMode"
                ) { formKind ->
                    if (formKind != null) {
                        CreateListForm(
                            kind = formKind, tint = tint, creating = creating,
                            onBack = { creatingKind = null },
                            onCreate = { name, description, cover ->
                                onCreate(formKind.key, name, description, cover) { err -> if (err == null) creatingKind = null }
                            }
                        )
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            ProfileStyleTabRow(
                                labels = PickerTab.entries.map { it.label },
                                selectedIndex = activeTab.ordinal,
                                liquidGlass = liquidGlass, tint = tint,
                                // Same dim backing as the rest of the popup's bubbles.
                                shadowed = true
                            ) { i -> switchTab(PickerTab.entries[i]) }

                            if (listsLoading && entries.isEmpty()) {
                                Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Color.White, strokeWidth = 1.5.dp, modifier = Modifier.size(24.dp))
                                }
                            } else {
                                val maxListHeight = (com.mediaviewer.ui.compat.rememberScreenSizeDp().height * 0.62f).dp
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight),
                                    contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (entries.isEmpty()) {
                                        item(key = "empty") {
                                            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    when (activeTab) {
                                                        PickerTab.LISTS -> "You have no lists yet."
                                                        PickerTab.STARTER_PACKS -> "You have no Starter Packs yet."
                                                        PickerTab.BOTH -> "No List + Starter Pack pairs yet (same name)."
                                                        PickerTab.MODLISTS -> "You have no moderation lists yet."
                                                    },
                                                    color = Color.White, fontSize = 13.sp,
                                                    modifier = Modifier.popupTextShadow().padding(horizontal = 12.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                    }
                                    items(entries, key = { it.key }) { entry ->
                                        EntryRow(
                                            entry = entry, tint = tint,
                                            isMember = memberships.containsKey(entry.listUri),
                                            busy = entry.listUri in busy,
                                            onToggle = { onToggle(entry.listUri, entry.additionalUri) },
                                            onRename = { name, done -> onRename(entry.renameUris, name, done) }
                                        )
                                    }
                                    item(key = "create_${activeTab.key}") {
                                        CreateRow(label = activeTab.createLabel, tint = tint) { creatingKind = activeTab }
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

// ─── Rows ───────────────────────────────────────────────────────────────────

private val RowHeight = 40.dp
private val RowShape = RoundedCornerShape(12.dp)

@Composable
private fun EntryRow(
    entry: PickerEntry, tint: Color, isMember: Boolean, busy: Boolean, onToggle: () -> Unit,
    onRename: (String, (String?) -> Unit) -> Unit = { _, done -> done(null) }
) {
    // Double-tap the name to edit it; the keyboard's Enter saves.
    var editing by remember(entry.key) { mutableStateOf(false) }
    var draft by remember(entry.key) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(entry.name)) }
    var saving by remember(entry.key) { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (editing) {
            kotlinx.coroutines.delay(60)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    fun save() {
        val name = draft.text.trim()
        if (name.isBlank() || name == entry.name) { editing = false; return }
        saving = true
        onRename(name) { err -> saving = false; if (err == null) editing = false }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Cover (or an icon for its kind), on the same dim backing as the name.
        Box(
            Modifier.size(RowHeight).clip(RowShape).background(Color.Black.copy(alpha = 0.32f)),
            contentAlignment = Alignment.Center
        ) {
            if (entry.avatarUrl != null) {
                AsyncImage(model = entry.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RowShape))
            } else {
                Icon(
                    imageVector = when (entry.kind) {
                        PickerTab.STARTER_PACKS -> Icons.Default.Groups
                        PickerTab.MODLISTS -> Icons.Default.Shield
                        else -> Icons.Default.FormatListBulleted
                    },
                    contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).height(RowHeight).popupTextShadow(RowShape)
                .then(
                    if (editing) Modifier.border(1.dp, lerp(tint, Color.White, 0.4f).copy(alpha = 0.8f), RowShape)
                    else Modifier.pointerInput(entry.key) {
                        detectTapGestures(onDoubleTap = {
                            draft = androidx.compose.ui.text.input.TextFieldValue(entry.name, androidx.compose.ui.text.TextRange(entry.name.length))
                            editing = true
                        })
                    }
                )
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            if (editing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = draft, onValueChange = { draft = it }, singleLine = true, enabled = !saving,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
                        modifier = Modifier.weight(1f).focusRequester(focus)
                    )
                    if (saving) CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
                }
            } else {
                Text(entry.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (entry.subtitle != null) Text(entry.subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
            }
        }
        // One button: + adds them, − takes them back off. It doesn't light
        // up or ripple when tapped — only the icon changes.
        Box(
            Modifier
                .size(RowHeight)
                .clip(RowShape)
                .background(Color.Black.copy(alpha = 0.32f))
                .border(1.dp, lerp(tint, Color.White, 0.4f).copy(alpha = 0.35f), RowShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = !busy, onClick = onToggle
                ),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(15.dp), color = Color.White, strokeWidth = 1.5.dp)
            } else {
                AnimatedContent(
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

@Composable
private fun CreateRow(label: String, tint: Color, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(RowHeight)
            .clip(RowShape)
            .background(lerp(tint, Color.Black, 0.45f).copy(alpha = 0.55f))
            .border(1.dp, lerp(tint, Color.White, 0.4f).copy(alpha = 0.5f), RowShape)
            .clickable { tap(); onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─── "Create new …" ─────────────────────────────────────────────────────────

@Composable
private fun CreateListForm(
    kind: PickerTab,
    tint: Color,
    creating: Boolean,
    onBack: () -> Unit,
    onCreate: (name: String, description: String, cover: com.mediaviewer.platform.PlatformUri?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<com.mediaviewer.platform.PlatformUri?>(null) }
    // Lists and moderation lists carry a cover image; starter packs don't
    // (Bluesky draws their card itself).
    val supportsCover = kind != PickerTab.STARTER_PACKS
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) cover = uri }
    val tap = rememberHapticTap()
    val nameLimit = if (kind == PickerTab.STARTER_PACKS || kind == PickerTab.BOTH) 50 else 64

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.34f))
                    .clickable { tap(); onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(18.dp)) }
            Text(
                kind.createLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.popupTextShadow().padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (supportsCover) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.32f))
                        .border(1.dp, lerp(tint, Color.White, 0.4f).copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .clickable { tap(); picker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (cover != null) {
                        AsyncImage(model = cover, contentDescription = "Cover", contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)))
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Text("Cover", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField(name, { name = it.take(nameLimit) }, "Name", tint, singleLine = true)
                FormField(description, { description = it.take(300) }, "Description (optional)", tint, singleLine = false)
            }
        }
        val enabled = name.isNotBlank() && !creating
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp))
                .background(if (enabled) lerp(tint, Color.White, 0.12f) else Color.White.copy(alpha = 0.08f))
                .clickable(enabled = enabled) { tap(); onCreate(name.trim(), description.trim(), if (supportsCover) cover else null) },
            contentAlignment = Alignment.Center
        ) {
            if (creating) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("Create", color = if (enabled) Color.White else Color.White.copy(alpha = 0.5f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FormField(value: String, onChange: (String) -> Unit, placeholder: String, tint: Color, singleLine: Boolean) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .popupFieldWell(tint, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = singleLine, maxLines = if (singleLine) 1 else 3,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth()
        )
        if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
    }
}
