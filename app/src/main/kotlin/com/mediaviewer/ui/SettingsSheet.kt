package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mediaviewer.model.AppMode
import com.mediaviewer.model.BskyFeedInfo
import com.mediaviewer.model.DownloadProgress
import com.mediaviewer.ui.theme.*
import com.mediaviewer.viewmodel.MainViewModel
import kotlin.math.abs

@Composable
fun SettingsSheet(
    appMode: AppMode,
    bskyLoggedIn: Boolean,
    e621LoggedIn: Boolean,
    bskyHandle: String,
    e621Username: String,
    availableFeeds: List<BskyFeedInfo>,
    selectedFeedUri: String?,
    authorFeedState: MainViewModel.AuthorFeedSavedState?,
    downloadOnLike: Boolean,
    downloadProgress: DownloadProgress?,
    reducedAnimations: Boolean,
    liquidGlass: Boolean,
    onToggleLiquidGlass: (Boolean) -> Unit,
    combineListsAndPacks: Boolean,
    e621SearchTags: String,
    isLoading: Boolean,
    onLoginBluesky: (String, String) -> Unit,
    onLogoutBluesky: () -> Unit,
    onSaveE621Credentials: (String, String) -> Unit,
    onLogoutE621: () -> Unit,
    onSelectFeed: (String?) -> Unit,
    onToggleDownloadOnLike: (Boolean) -> Unit,
    onDownloadAllLiked: () -> Unit,
    onCancelDownload: () -> Unit,
    onShowLikes: () -> Unit,
    onShowFriends: () -> Unit,
    onShowE621Following: () -> Unit,
    onToggleReducedAnimations: (Boolean) -> Unit,
    onToggleCombineListsPacks: (Boolean) -> Unit,
    autoAddToOnFollow: Boolean,
    onToggleAutoAddToOnFollow: (Boolean) -> Unit,
    onSearchE621: (String) -> Unit,
    onShowE621Favorites: () -> Unit,
    onSwitchMode: (AppMode) -> Unit,
    onSwipeToFeed: () -> Unit
) {
    var bskyId         by remember { mutableStateOf("") }
    var bskyPw         by remember { mutableStateOf("") }
    var e621User       by remember { mutableStateOf("") }
    var e621Key        by remember { mutableStateOf("") }
    var localE621Tags  by remember(e621SearchTags) { mutableStateOf(e621SearchTags) }
    val isLoggedIn     = if (appMode == AppMode.BLUESKY) bskyLoggedIn else e621LoggedIn

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (liquidGlass) Modifier.background(postBackgroundBrush(NeutralGlassTint))
                else Modifier.background(OledBlack)
            )
            .pointerInput(appMode) {
                detectDragGestures(
                    onDragStart = { },
                    onDragEnd   = { },
                    onDragCancel = { }
                ) { change, dragAmount ->
                    // Handled below via accumulated totals
                    change.consume()
                }
            }
            .pointerInput(appMode) {
                var totalX = 0f; var totalY = 0f
                detectDragGestures(
                    onDragStart  = { totalX = 0f; totalY = 0f },
                    onDragEnd    = {
                        when {
                            abs(totalY) > 80f && abs(totalY) > abs(totalX) * 1.2f && totalY < 0 -> onSwipeToFeed()
                            abs(totalX) > 80f && abs(totalX) > abs(totalY) * 1.2f ->
                                if (totalX < 0) onSwitchMode(AppMode.E621) else onSwitchMode(AppMode.BLUESKY)
                        }
                    },
                    onDragCancel = { }
                ) { _, dragAmount -> totalX += dragAmount.x; totalY += dragAmount.y }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))

            // ── Mode header ───────────────────────────────────────────────────
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(36.dp)
            ) {
                ModeChip("AT Protocol", appMode == AppMode.BLUESKY, liquidGlass, Modifier.align(Alignment.CenterStart)) { onSwitchMode(AppMode.BLUESKY) }
                Text(
                    if (appMode == AppMode.BLUESKY) "AT Protocol" else "e621",
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                ModeChip("e621", appMode == AppMode.E621, liquidGlass, Modifier.align(Alignment.CenterEnd)) { onSwitchMode(AppMode.E621) }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
            Spacer(Modifier.height(12.dp))

            if (!isLoggedIn) {
                // ── Login form ────────────────────────────────────────────────
                Spacer(Modifier.weight(1f))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (appMode == AppMode.BLUESKY) {
                        OutlinedTextField(value = bskyId, onValueChange = { bskyId = it },
                            placeholder = { Text("handle or email", color = DimGray) },
                            singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = bskyPw, onValueChange = { bskyPw = it },
                            placeholder = { Text("app password", color = DimGray) },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                        Button(onClick = { onLoginBluesky(bskyId.trim(), bskyPw) },
                            enabled = bskyId.isNotBlank() && bskyPw.isNotBlank() && !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth().height(46.dp)) {
                            if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                            else Text("Sign in to Bluesky", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedTextField(value = e621User, onValueChange = { e621User = it },
                            placeholder = { Text("Username", color = DimGray) },
                            singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = e621Key, onValueChange = { e621Key = it },
                            placeholder = { Text("API Key", color = DimGray) },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                        Button(onClick = { onSaveE621Credentials(e621User, e621Key) },
                            enabled = e621User.isNotBlank() && e621Key.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth().height(46.dp)) {
                            Text("Sign in to e621", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                // ── Feed row / search bar ─────────────────────────────────────
                if (appMode == AppMode.BLUESKY) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Author chip — shown when we're viewing a specific account's posts
                        val saved = authorFeedState
                        if (saved != null) {
                            AuthorChip(author = saved.author, liquidGlass = liquidGlass)
                        }
                        availableFeeds.forEach { feed ->
                            FeedChip(feed.displayName, feed.avatarUrl,
                                selectedFeedUri == feed.uri && saved == null, liquidGlass = liquidGlass) { onSelectFeed(feed.uri) }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(value = localE621Tags, onValueChange = { localE621Tags = it },
                            placeholder = { Text("Search tags…", color = DimGray, fontSize = 13.sp) },
                            singleLine = true, colors = fieldColors(),
                            modifier = Modifier.weight(1f).height(56.dp))
                        Button(onClick = { onSearchE621(localE621Tags) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White),
                            modifier = Modifier.height(56.dp)) { Text("Search") }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Item 3: each switch row is now its own individual glass
                    // button (same treatment as the buttons further down),
                    // instead of all of them sharing one big box.
                    @Composable
                    fun SettingsRow(content: @Composable RowScope.() -> Unit) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .then(
                                    if (liquidGlass) Modifier.glassPanel(true, shape = RoundedCornerShape(16.dp))
                                    else Modifier
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            content = content
                        )
                    }

                    // Download When Liked/Favorited
                    SettingsRow {
                        Text(if (appMode == AppMode.BLUESKY) "Download When Liked" else "Download When Favorited", color = Color.White, fontSize = 14.sp)
                        Switch(checked = downloadOnLike, onCheckedChange = onToggleDownloadOnLike,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = VoteGreen,
                                uncheckedThumbColor = DimGray, uncheckedTrackColor = Color.White.copy(0.1f)))
                    }

                    // Reduced Animations
                    SettingsRow {
                        Text("Reduced Animations", color = Color.White, fontSize = 14.sp)
                        Switch(checked = reducedAnimations, onCheckedChange = onToggleReducedAnimations,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = VoteGreen,
                                uncheckedThumbColor = DimGray, uncheckedTrackColor = Color.White.copy(0.1f)))
                    }

                    // Glass Theme (item 11: renamed from "Liquid Glass")
                    SettingsRow {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Glass Theme", color = Color.White, fontSize = 14.sp)
                            Text("Clear, reflective glass buttons and panels throughout the app",
                                color = DimGray, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                        Switch(checked = liquidGlass, onCheckedChange = onToggleLiquidGlass,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = VoteGreen,
                                uncheckedThumbColor = DimGray, uncheckedTrackColor = Color.White.copy(0.1f)))
                    }

                    // Merge Lists & Starter Packs (Bluesky only)
                    if (appMode == AppMode.BLUESKY) {
                        SettingsRow {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Merge Lists & Starter Packs", color = Color.White, fontSize = 14.sp)
                                Text("Show only entries that exist in both, and add to both on tap",
                                    color = DimGray, fontSize = 11.sp, lineHeight = 14.sp)
                            }
                            Switch(checked = combineListsAndPacks, onCheckedChange = onToggleCombineListsPacks,
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = VoteGreen,
                                    uncheckedThumbColor = DimGray, uncheckedTrackColor = Color.White.copy(0.1f)))
                        }
                    }

                    // Show "Add To" popup automatically after following (Bluesky only) — item 2
                    if (appMode == AppMode.BLUESKY) {
                        SettingsRow {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Show \"Add To\" After Following", color = Color.White, fontSize = 14.sp)
                                Text("Automatically open the Add To popup right after you follow someone",
                                    color = DimGray, fontSize = 11.sp, lineHeight = 14.sp)
                            }
                            Switch(checked = autoAddToOnFollow, onCheckedChange = onToggleAutoAddToOnFollow,
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = VoteGreen,
                                    uncheckedThumbColor = DimGray, uncheckedTrackColor = Color.White.copy(0.1f)))
                        }
                    }

                    if (appMode == AppMode.BLUESKY) {
                        // Download All button with live progress
                        val prog = downloadProgress
                        Box(
                            modifier = Modifier.fillMaxWidth().height(46.dp).glassPanel(liquidGlass)
                                .clickable { if (prog?.isRunning != true) onDownloadAllLiked() },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                when {
                                    prog?.isRunning == true        -> "Downloading… ${prog.count} queued"
                                    prog != null && prog.count > 0 -> "Done — ${prog.count} queued"
                                    else                            -> "Download All Liked Media"
                                },
                                color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp)
                            )
                            if (prog?.isRunning == true) {
                                IconButton(onClick = onCancelDownload, modifier = Modifier.align(Alignment.CenterEnd).size(40.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = DimGray, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        // "From Friends" — combined feed of media friends have sent us via DM
                        Row(
                            modifier = Modifier.fillMaxWidth().height(46.dp).glassPanel(liquidGlass).clickable(onClick = onShowFriends),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("From Friends", color = Color.White, fontSize = 13.sp)
                        }
                        // Bluesky: single "My Likes" button
                        Row(
                            modifier = Modifier.fillMaxWidth().height(46.dp).glassPanel(liquidGlass).clickable(onClick = onShowLikes),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = LikeRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Liked Posts", color = Color.White, fontSize = 13.sp)
                        }
                    } else {
                        // Item 4: Hot → Favorites/Following → Download All Saved Media
                        Row(
                            modifier = Modifier.fillMaxWidth().height(46.dp).glassPanel(liquidGlass)
                                .clickable(onClick = { onSearchE621("order:hot") }),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("\uD83D\uDD25", fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Hot", color = Color.White, fontSize = 13.sp)
                        }

                        // e621: two buttons side by side — Favorites | Following
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // My Favorites
                            Row(
                                modifier = Modifier.weight(1f).height(46.dp).glassPanel(liquidGlass).clickable(onClick = onShowE621Favorites),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = BookmarkYellow, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Favorites", color = Color.White, fontSize = 13.sp)
                            }
                            // Following
                            Row(
                                modifier = Modifier.weight(1f).height(46.dp).glassPanel(liquidGlass).clickable(onClick = onShowE621Following),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = VoteGreen, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Following", color = Color.White, fontSize = 13.sp)
                            }
                        }

                        // Download All button with live progress
                        val prog = downloadProgress
                        Box(
                            modifier = Modifier.fillMaxWidth().height(46.dp).glassPanel(liquidGlass)
                                .clickable { if (prog?.isRunning != true) onDownloadAllLiked() },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                when {
                                    prog?.isRunning == true        -> "Downloading… ${prog.count} queued"
                                    prog != null && prog.count > 0 -> "Done — ${prog.count} queued"
                                    else                            -> "Download All Saved Media"
                                },
                                color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp)
                            )
                            if (prog?.isRunning == true) {
                                IconButton(onClick = onCancelDownload, modifier = Modifier.align(Alignment.CenterEnd).size(40.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = DimGray, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    // Logged in as + Logout
                    Row(
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                            .then(
                                if (liquidGlass) Modifier.glassPanel(true)
                                else Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(0.05f))
                            )
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Logged in as @${if (appMode == AppMode.BLUESKY) bskyHandle else e621Username}",
                            color = DimGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("Logout", color = Color(0xFFEF5350), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { if (appMode == AppMode.BLUESKY) onLogoutBluesky() else onLogoutE621() }
                                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp))
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Text(
                buildAnnotatedString {
                    append("Created by ")
                    withStyle(SpanStyle(color = Color(0xFF00FF07))) { append("Recho Raccoon") }
                },
                color = DimGray, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}

// ── Shared feed-row chip composables ─────────────────────────────────────────

@Composable
fun AuthorChip(author: com.mediaviewer.model.AuthorInfo, liquidGlass: Boolean = false) {
    // Always shown as "selected" since we're currently viewing this author's posts.
    // Tapping it is intentionally a no-op — to leave, tap a real feed chip.
    Row(
        modifier = Modifier
            .then(
                if (liquidGlass) Modifier.glassPanel(true, shape = RoundedCornerShape(20.dp))
                else Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.18f))
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (author.displayName == "From Friends") {
            Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        } else if (author.displayName == "Liked Posts") {
            Icon(Icons.Default.Favorite, contentDescription = null, tint = LikeRed, modifier = Modifier.size(16.dp))
        } else if (author.avatarUrl != null) {
            AsyncImage(model = author.avatarUrl, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(16.dp).clip(CircleShape))
        } else {
            Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White.copy(0.2f)))
        }
        Text(author.displayName.take(16), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FeedChip(name: String, avatarUrl: String?, isSelected: Boolean, liquidGlass: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .then(
                if (liquidGlass) Modifier.glassPanel(
                    true, tint = if (isSelected) NeutralGlassTint else NeutralGlassTint.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(20.dp)
                )
                else Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) Color.White.copy(0.15f) else Color.White.copy(0.06f))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (avatarUrl != null) {
            AsyncImage(model = avatarUrl, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(16.dp).clip(CircleShape))
        }
        Text(name, color = if (isSelected) Color.White else DimGray, fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ModeChip(label: String, active: Boolean, liquidGlass: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(label,
        color = if (active) Color.White else DimGray,
        fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = modifier
            .then(
                if (liquidGlass && active) Modifier.glassPanel(true, shape = RoundedCornerShape(10.dp))
                else Modifier.clip(RoundedCornerShape(10.dp)).background(if (active) Color.White.copy(0.1f) else Color.Transparent)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Color.White.copy(0.3f), unfocusedBorderColor = Color.White.copy(0.1f),
    cursorColor = Color.White, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent
)
