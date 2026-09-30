package com.mediaviewer.ui

import com.mediaviewer.ui.compat.rememberPlatformView

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.DmConversation
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel

/**
 * "New chat" — a centred popup over a blurred screen, Stellar's take on
 * Bluesky's own new-chat sheet:
 *  - search anyone on Bluesky; people you can message are lit up and listed
 *    first, people who can't be messaged (their "who can message me"
 *    setting, blocks) are greyed out underneath;
 *  - "New group chat": pick people (chips along the top, checkboxes), then
 *    name the group and create it.
 */
@Composable
fun NewChatDialog(
    state: MainViewModel.NewChatState,
    candidates: List<MainViewModel.ChatCandidate>,
    searching: Boolean,
    creating: Boolean,
    tint: Color,
    onSearch: (String) -> Unit,
    onSetGroupMode: (Boolean) -> Unit,
    onStartChat: (AuthorInfo) -> Unit,
    onCreateGroup: (String, List<AuthorInfo>) -> Unit,
    onClose: () -> Unit,
    /** Scrolled near the end of the Suggested list: fetch more follows. */
    onLoadMoreSuggestions: () -> Unit = {}
) {
    val tap = rememberHapticTap()
    // Opened straight into group mode (e.g. "New group with …" on a
    // profile): back closes instead of dropping to the plain chat search.
    val startedAsGroup = remember { state.group }
    val selected = remember { mutableStateListOf<AuthorInfo>().apply { addAll(state.preselected) } }
    var naming by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }

    fun back() {
        when {
            naming -> naming = false
            state.group && !startedAsGroup -> { onSetGroupMode(false); selected.clear() }
            else -> onClose()
        }
    }

    Dialog(
        onDismissRequest = { if (!creating) back() },
        properties = com.mediaviewer.ui.compat.edgeToEdgeDialogProperties()
    ) {
        // Blur whatever is behind the popup (Android 12+), plus a dim.
        com.mediaviewer.ui.compat.DialogBlurBehind(radius = 48, dimAmount = 0.45f)
        BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
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
                // ── Header ──
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundIconButton(
                        if (naming || (state.group && !startedAsGroup)) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                        "Back"
                    ) { tap(); back() }
                    Text(
                        when { naming -> "Name your group"; state.group -> "New group chat"; else -> "New chat" },
                        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                    if (state.group && !naming) {
                        val enabled = selected.isNotEmpty()
                        Box(
                            Modifier.clip(RoundedCornerShape(16.dp))
                                .background(if (enabled) lerp(tint, Color.White, 0.1f) else Color.White.copy(alpha = 0.08f))
                                .clickable(enabled = enabled) { tap(); naming = true }
                                .padding(horizontal = 16.dp, vertical = 7.dp)
                        ) { Text("Next", color = if (enabled) Color.White else DimGray, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    } else Spacer(Modifier.size(38.dp))
                }

                AnimatedContent(
                    targetState = naming,
                    transitionSpec = {
                        val dir = if (targetState) 1 else -1
                        (slideInHorizontally(tween(240)) { it * dir / 3 } + fadeIn(tween(240)))
                            .togetherWith(slideOutHorizontally(tween(200)) { -it * dir / 3 } + fadeOut(tween(160)))
                    },
                    label = "newChatStep"
                ) { isNaming ->
                    if (isNaming) {
                        GroupNamingStep(
                            members = selected.toList(), name = groupName, onName = { groupName = it.take(50) },
                            creating = creating, tint = tint,
                            onCreate = { tap(); onCreateGroup(groupName, selected.toList()) }
                        )
                    } else {
                        Column {
                            SearchField(query, tint) { query = it; onSearch(it) }
                            if (state.group && selected.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    selected.forEach { a ->
                                        SelectedChip(a, tint) { tap(); selected.removeAll { it.did == a.did } }
                                    }
                                }
                            }
                            if (!state.group) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { tap(); onSetGroupMode(true) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.35f))
                                            .border(1.dp, tint.copy(alpha = 0.8f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Default.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                                    Spacer(Modifier.width(12.dp))
                                    Text("New group chat", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                                }
                            }
                            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
                            if (query.isBlank()) {
                                Text(
                                    "Suggested", color = lerp(tint, Color.White, 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
                                )
                            }
                            Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                                when {
                                    searching && candidates.isEmpty() -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                                    }
                                    candidates.isEmpty() -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                                        Text(if (query.isBlank()) "Search for anyone on Bluesky" else "No one found", color = DimGray, fontSize = 13.sp)
                                    }
                                    else -> {
                                        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                                        val nearEnd by androidx.compose.runtime.remember {
                                            androidx.compose.runtime.derivedStateOf {
                                                val info = listState.layoutInfo
                                                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                                                info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
                                            }
                                        }
                                        LaunchedEffect(nearEnd, query) { if (nearEnd && query.isBlank()) onLoadMoreSuggestions() }
                                        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 10.dp)) {
                                        items(candidates, key = { it.author.did }) { c ->
                                            val isSelected = selected.any { it.did == c.author.did }
                                            CandidateRow(
                                                c, groupMode = state.group, selected = isSelected, tint = tint,
                                                onClick = {
                                                    tap()
                                                    if (!state.group) onStartChat(c.author)
                                                    else if (isSelected) selected.removeAll { it.did == c.author.did }
                                                    else if (selected.size < 99) selected.add(c.author)
                                                }
                                            )
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
}

@Composable
private fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(19.dp)) }
}

@Composable
private fun SearchField(value: String, tint: Color, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().height(44.dp)
            .clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.28f))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = lerp(tint, Color.White, 0.5f), modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            if (value.isEmpty()) Text("Search for people", color = DimGray, fontSize = 15.sp)
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Default.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onChange("") }
            )
        }
    }
}

@Composable
private fun SelectedChip(author: AuthorInfo, tint: Color, onRemove: () -> Unit) {
    val personTint = rememberAuthorProfileTint(author.did, author.avatarUrl)
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(lerp(personTint, Color.Black, 0.35f).copy(alpha = 0.8f))
            .border(1.dp, lerp(personTint, Color.White, 0.3f).copy(alpha = 0.8f), RoundedCornerShape(18.dp))
            .clickable(onClick = onRemove).padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(author, 24.dp)
        Spacer(Modifier.width(6.dp))
        Text(author.displayName, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun Avatar(author: AuthorInfo, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        if (author.avatarUrl != null) AsyncImage(
            model = author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape)
        ) else Text(author.displayName.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CandidateRow(
    c: MainViewModel.ChatCandidate, groupMode: Boolean, selected: Boolean, tint: Color, onClick: () -> Unit
) {
    val a = c.author
    val rowAlpha by animateFloatAsState(if (c.canMessage) 1f else 0.42f, label = "candidateAlpha")
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = rowAlpha }
            .then(if (selected) Modifier.background(tint.copy(alpha = 0.16f)) else Modifier)
            .clickable(enabled = c.canMessage, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(a, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.displayName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (c.canMessage) "@${a.handle}" else "@${a.handle} can't be messaged",
                color = DimGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (groupMode && c.canMessage) {
            val boxScale by animateFloatAsState(if (selected) 1f else 0.9f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "checkScale")
            Box(
                Modifier.size(26.dp).graphicsLayer { scaleX = boxScale; scaleY = boxScale }
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) lerp(tint, Color.White, 0.1f) else Color.Transparent)
                    .border(1.5.dp, if (selected) lerp(tint, Color.White, 0.3f) else Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) { if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun GroupNamingStep(
    members: List<AuthorInfo>, name: String, onName: (String) -> Unit,
    creating: Boolean, tint: Color, onCreate: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val preview = remember(members) {
        DmConversation(
            convoId = "preview", member = AuthorInfo(did = "preview", handle = "", displayName = "", avatarUrl = null),
            lastSentByUsAt = "", lastActivityAt = "", isGroup = true, groupMembers = members, memberCount = members.size + 1
        )
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DmConvoAvatar(preview, 84.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            members.joinToString(", ") { it.displayName.substringBefore(' ') },
            color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.28f))
                .border(1.dp, tint.copy(alpha = 0.7f), RoundedCornerShape(24.dp)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = name, onValueChange = onName, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!creating) onCreate() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
                if (name.isEmpty()) Text("Group name", color = DimGray, fontSize = 16.sp)
            }
            Text("${name.length}/50", color = DimGray, fontSize = 11.sp)
        }
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.horizontalGradient(listOf(lerp(tint, Color.White, 0.12f), tint)))
                .clickable(enabled = !creating, onClick = onCreate),
            contentAlignment = Alignment.Center
        ) {
            if (creating) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("Create group", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Everyone you add gets an invite in their chats.",
            color = DimGray, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
        )
    }
}
