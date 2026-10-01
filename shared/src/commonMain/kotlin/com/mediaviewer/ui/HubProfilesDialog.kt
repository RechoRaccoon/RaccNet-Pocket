package com.mediaviewer.ui

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.rememberHapticTap

/**
 * Customize Hub → Add → Profiles: the "New chat" popup's look, for building
 * a Hub row out of any accounts. Search anyone, press "Select" on as many
 * as you like (the count shows as you go), name the row, then "Add to hub".
 * The row works like a list row but lives only in Stellar — no Bluesky list
 * is made.
 */
@Composable
fun HubProfilesDialog(
    candidates: List<AuthorInfo>,
    searching: Boolean,
    tint: Color,
    onSearch: (String) -> Unit,
    onLoadMoreSuggestions: () -> Unit,
    onAdd: (String, List<AuthorInfo>) -> Unit,
    onClose: () -> Unit
) {
    val tap = rememberHapticTap()
    val selected = remember { mutableStateListOf<AuthorInfo>() }
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { onSearch("") }

    Dialog(onDismissRequest = onClose, properties = com.mediaviewer.ui.compat.edgeToEdgeDialogProperties()) {
        com.mediaviewer.ui.compat.DialogBlurBehind(radius = 48, dimAmount = 0.45f)
        BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(26.dp)
            val panel = lerp(Color(0xFF101014), tint, 0.16f)
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    .widthIn(max = 440.dp).fillMaxWidth()
                    .heightIn(max = maxHeight * 0.86f)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(lerp(panel, tint, 0.12f).copy(alpha = 0.97f), panel.copy(alpha = 0.97f))))
                    .border(1.2.dp, Brush.linearGradient(listOf(tint.copy(alpha = 0.9f), Color.White.copy(alpha = 0.25f), tint.copy(alpha = 0.6f))), shape)
            ) {
                // ── Header ──
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
                        "Add profiles", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                    // How many are picked so far.
                    Box(Modifier.widthIn(min = 38.dp), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            "${selected.size} selected",
                            color = if (selected.isEmpty()) DimGray else lerp(tint, Color.White, 0.6f),
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                        )
                    }
                }

                // ── Search ──
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
                            value = query, onValueChange = { query = it; onSearch(it) }, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { }),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (query.isEmpty()) Text("Search for people", color = DimGray, fontSize = 15.sp)
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp).clip(CircleShape).clickable { query = ""; onSearch("") }
                        )
                    }
                }

                // ── Picked so far (tap one to drop it) ──
                if (selected.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selected.forEach { a ->
                            val personTint = rememberAuthorProfileTint(a.did, a.avatarUrl)
                            Row(
                                Modifier.clip(RoundedCornerShape(18.dp)).background(lerp(personTint, Color.Black, 0.35f).copy(alpha = 0.8f))
                                    .border(1.dp, lerp(personTint, Color.White, 0.3f).copy(alpha = 0.8f), RoundedCornerShape(18.dp))
                                    .clickable { tap(); selected.removeAll { it.did == a.did } }
                                    .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HubProfileAvatar(a, 24.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(a.displayName, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
                if (query.isBlank()) {
                    Text(
                        "Suggested", color = lerp(tint, Color.White, 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
                    )
                }

                // ── Results ──
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    when {
                        searching && candidates.isEmpty() -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                        }
                        candidates.isEmpty() -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            Text(if (query.isBlank()) "Search for anyone on Bluesky" else "No one found", color = DimGray, fontSize = 13.sp)
                        }
                        else -> {
                            val listState = rememberLazyListState()
                            val nearEnd by remember {
                                derivedStateOf {
                                    val info = listState.layoutInfo
                                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                                    info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
                                }
                            }
                            LaunchedEffect(nearEnd, query) { if (nearEnd && query.isBlank()) onLoadMoreSuggestions() }
                            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 6.dp)) {
                                items(candidates, key = { it.did }) { a ->
                                    val isSelected = selected.any { it.did == a.did }
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .then(if (isSelected) Modifier.background(tint.copy(alpha = 0.16f)) else Modifier)
                                            .padding(horizontal = 16.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        HubProfileAvatar(a, 38.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(a.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("@${a.handle}", color = DimGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        val pill = RoundedCornerShape(14.dp)
                                        Box(
                                            Modifier.clip(pill)
                                                .background(if (isSelected) lerp(tint, Color.White, 0.1f) else lerp(tint, Color.Black, 0.35f).copy(alpha = 0.75f))
                                                .border(1.dp, lerp(tint, Color.White, if (isSelected) 0.6f else 0.3f).copy(alpha = 0.85f), pill)
                                                .clickable {
                                                    tap()
                                                    if (isSelected) selected.removeAll { it.did == a.did }
                                                    else if (selected.size < 100) selected.add(a)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(if (isSelected) "Selected" else "Select", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Name the row, then add it ──
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
                val canAdd = selected.isNotEmpty() && name.isNotBlank()
                fun submit() { if (canAdd) { tap(); onAdd(name.trim(), selected.toList()) } }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(Color.Black.copy(alpha = 0.28f))
                            .border(1.dp, tint.copy(alpha = 0.7f), RoundedCornerShape(21.dp)).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            BasicTextField(
                                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                cursorBrush = SolidColor(Color.White),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (name.isEmpty()) Text("Section name", color = DimGray, fontSize = 15.sp)
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp))
                            .background(
                                if (canAdd) Brush.horizontalGradient(listOf(lerp(tint, Color.White, 0.12f), tint))
                                else SolidColor(Color.White.copy(alpha = 0.08f))
                            )
                            .clickable(enabled = canAdd) { submit() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Add to hub", color = if (canAdd) Color.White else DimGray, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun HubProfileAvatar(author: AuthorInfo, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        if (author.avatarUrl != null) AsyncImage(
            model = author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape)
        ) else Text(author.displayName.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold)
    }
}
