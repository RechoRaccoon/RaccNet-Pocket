package com.mediaviewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
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
    onSwipeToFeed: () -> Unit,
    // Settings Update
    selfProfile: com.mediaviewer.model.ProfileData?,
    hideTextOnlyPosts: Boolean,
    onToggleHideTextOnlyPosts: (Boolean) -> Unit,
    onOpenOwnProfile: () -> Unit,
    onShowSaves: () -> Unit,
    onShowHistory: () -> Unit,
    onOpenDmInbox: () -> Unit,
    // Phase 4 — on-device translation
    translationEnabled: Boolean = false,
    translationTargetLang: String = "en",
    onToggleTranslation: (Boolean) -> Unit = {},
    onSelectTranslationLanguage: (String) -> Unit = {},
    // Phase 4 — custom app-wide font pack
    customFontName: String? = null,
    onPickFontFile: (android.net.Uri) -> Unit = {},
    onResetFont: () -> Unit = {},
    // Item 1 (Phase 3): the post the user was last looking at, so Settings'
    // glass rims pick up its color the same way the in-post glass buttons do.
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null
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

                // ── Settings Update: 6 evenly-divided quick-access buttons ──────
                if (appMode == AppMode.BLUESKY) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsGridButton("Liked Posts", Icons.Default.Favorite, LikeRed, liquidGlass, Modifier.weight(1f), onShowLikes, panelTint = dominantColor, backdrop = backdrop)
                            ProfileGridButton(selfProfile, bskyHandle, liquidGlass, Modifier.weight(1f), onOpenOwnProfile)
                            SettingsGridButton("From Friends", Icons.Default.Favorite, Color.White, liquidGlass, Modifier.weight(1f), onShowFriends, panelTint = dominantColor, backdrop = backdrop)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsGridButton("Saves", Icons.Default.Star, BookmarkYellow, liquidGlass, Modifier.weight(1f), onShowSaves, panelTint = dominantColor, backdrop = backdrop)
                            SettingsGridButton("History", Icons.Default.History, Color.White, liquidGlass, Modifier.weight(1f), onShowHistory, panelTint = dominantColor, backdrop = backdrop)
                            SettingsGridButton("DMs", Icons.Default.Chat, Color.White, liquidGlass, Modifier.weight(1f), onOpenDmInbox, panelTint = dominantColor, backdrop = backdrop)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
                        Text("Settings", color = DimGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp))
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Item 3: each switch row is now its own individual glass
                    // button (same treatment as the buttons further down),
                    // instead of all of them sharing one big box.
                    @Composable
                    fun SettingsRow(content: @Composable RowScope.() -> Unit) {
                        val rowModifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                        if (liquidGlass) {
                            LiquidGlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), tint = dominantColor, backdrop = backdrop) {
                                Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = content)
                            }
                        } else {
                            Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = content)
                        }
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

                    // Phase 4 — on-device translation
                    SettingsRow {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Translate Post Text", color = Color.White, fontSize = 14.sp)
                            Text("On-device translation (ML Kit) for text-only posts and post text bubbles — no internet needed once the language pack is downloaded",
                                color = DimGray, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                        Switch(checked = translationEnabled, onCheckedChange = onToggleTranslation,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = VoteGreen,
                                uncheckedThumbColor = DimGray, uncheckedTrackColor = Color.White.copy(0.1f)))
                    }
                    if (translationEnabled) {
                        var langMenuExpanded by remember { mutableStateOf(false) }
                        SettingsRow {
                            Text("Translate To", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Box {
                                Row(
                                    modifier = Modifier.clickable { langMenuExpanded = true },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        com.mediaviewer.util.TranslationManager.SUPPORTED_LANGUAGES
                                            .firstOrNull { it.first == translationTargetLang }?.second
                                            ?: com.mediaviewer.util.TranslationManager.displayNameFor(translationTargetLang),
                                        color = VoteGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                    )
                                }
                                DropdownMenu(expanded = langMenuExpanded, onDismissRequest = { langMenuExpanded = false }) {
                                    com.mediaviewer.util.TranslationManager.SUPPORTED_LANGUAGES.forEach { (tag, name) ->
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = { onSelectTranslationLanguage(tag); langMenuExpanded = false }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Phase 4 — custom app-wide font pack
                    run {
                        val fontPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                            if (uri != null) onPickFontFile(uri)
                        }
                        SettingsRow {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("App Font", color = Color.White, fontSize = 14.sp)
                                Text(customFontName ?: "Default", color = DimGray, fontSize = 11.sp, lineHeight = 14.sp,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (customFontName != null) {
                                    Text("Reset", color = Color(0xFFEF5350), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.clickable(onClick = onResetFont))
                                }
                                Text("Choose File", color = VoteGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { fontPickerLauncher.launch("*/*") })
                            }
                        }
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
                        // Settings Update: universal toggle, sits right above Download All.
                        SettingsRow {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Hide Text Only Posts", color = Color.White, fontSize = 14.sp)
                                Text("Skip posts with no image or video, everywhere in the app",
                                    color = DimGray, fontSize = 11.sp, lineHeight = 14.sp)
                            }
                            Switch(checked = hideTextOnlyPosts, onCheckedChange = onToggleHideTextOnlyPosts,
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = VoteGreen,
                                    uncheckedThumbColor = DimGray, uncheckedTrackColor = Color.White.copy(0.1f)))
                        }

                        // Download All button with live progress — text centered per Settings Update.
                        val prog = downloadProgress
                        @Composable
                        fun DownloadAllContent() {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable { if (prog?.isRunning != true) onDownloadAllLiked() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    when {
                                        prog?.isRunning == true        -> "Downloading… ${prog.count} queued"
                                        prog != null && prog.count > 0 -> "Done — ${prog.count} queued"
                                        else                            -> "Download All Liked Media"
                                    },
                                    color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center
                                )
                                if (prog?.isRunning == true) {
                                    IconButton(onClick = onCancelDownload, modifier = Modifier.align(Alignment.CenterEnd).size(40.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = DimGray, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        if (liquidGlass) {
                            LiquidGlassSurface(Modifier.fillMaxWidth().height(46.dp), tint = dominantColor, backdrop = backdrop) { DownloadAllContent() }
                        } else {
                            Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.08f))) { DownloadAllContent() }
                        }
                    } else {
                        // Item 4: Hot → Favorites/Following → Download All Saved Media
                        @Composable
                        fun HotContent() {
                            Row(
                                modifier = Modifier.fillMaxSize().clickable(onClick = { onSearchE621("order:hot") }),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text("\uD83D\uDD25", fontSize = 16.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("Hot", color = Color.White, fontSize = 13.sp)
                            }
                        }
                        if (liquidGlass) {
                            LiquidGlassSurface(Modifier.fillMaxWidth().height(46.dp), tint = dominantColor, backdrop = backdrop) { HotContent() }
                        } else {
                            Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.08f))) { HotContent() }
                        }

                        // e621: two buttons side by side — Favorites | Following
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // My Favorites
                            @Composable
                            fun FavoritesContent() {
                                Row(
                                    modifier = Modifier.fillMaxSize().clickable(onClick = onShowE621Favorites),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = BookmarkYellow, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Favorites", color = Color.White, fontSize = 13.sp)
                                }
                            }
                            if (liquidGlass) {
                                LiquidGlassSurface(Modifier.weight(1f).height(46.dp), tint = dominantColor, backdrop = backdrop) { FavoritesContent() }
                            } else {
                                Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.08f))) { FavoritesContent() }
                            }
                            // Following
                            @Composable
                            fun FollowingContent() {
                                Row(
                                    modifier = Modifier.fillMaxSize().clickable(onClick = onShowE621Following),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, tint = VoteGreen, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Following", color = Color.White, fontSize = 13.sp)
                                }
                            }
                            if (liquidGlass) {
                                LiquidGlassSurface(Modifier.weight(1f).height(46.dp), tint = dominantColor, backdrop = backdrop) { FollowingContent() }
                            } else {
                                Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.08f))) { FollowingContent() }
                            }
                        }

                        // Download All button with live progress
                        val prog2 = downloadProgress
                        @Composable
                        fun DownloadAllSavedContent() {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable { if (prog2?.isRunning != true) onDownloadAllLiked() },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    when {
                                        prog2?.isRunning == true        -> "Downloading… ${prog2.count} queued"
                                        prog2 != null && prog2.count > 0 -> "Done — ${prog2.count} queued"
                                        else                             -> "Download All Saved Media"
                                    },
                                    color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp)
                                )
                                if (prog2?.isRunning == true) {
                                    IconButton(onClick = onCancelDownload, modifier = Modifier.align(Alignment.CenterEnd).size(40.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = DimGray, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        if (liquidGlass) {
                            LiquidGlassSurface(Modifier.fillMaxWidth().height(46.dp), tint = dominantColor, backdrop = backdrop) { DownloadAllSavedContent() }
                        } else {
                            Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.08f))) { DownloadAllSavedContent() }
                        }
                    }

                    // Logged in as + Logout
                    @Composable
                    fun LoggedInRowContent() {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
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
                    if (liquidGlass) {
                        LiquidGlassSurface(Modifier.fillMaxWidth().height(46.dp), tint = dominantColor, backdrop = backdrop) { LoggedInRowContent() }
                    } else {
                        Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(0.05f))) { LoggedInRowContent() }
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

// ── Settings Update: quick-access button grid ────────────────────────────────

@Composable
private fun SettingsGridButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    liquidGlass: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    panelTint: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null
) {
    val shape = RoundedCornerShape(12.dp)

    @Composable
    fun ButtonContent() {
        Column(
            Modifier.fillMaxSize().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }

    if (liquidGlass) {
        LiquidGlassSurface(modifier = modifier.height(72.dp), shape = shape, tint = panelTint, backdrop = backdrop) { ButtonContent() }
    } else {
        Box(modifier.height(72.dp).clip(shape).background(Color.White.copy(0.06f))) { ButtonContent() }
    }
}

/** The "Profile" quick-access button — shows the user's own avatar big on the
 *  left, "Profile" on the centered right, their banner blurred into the glass
 *  background, and a rim that reflects the avatar/banner's own colors, same
 *  as every other glass surface in the app. */
@Composable
private fun ProfileGridButton(
    profile: com.mediaviewer.model.ProfileData?,
    fallbackHandle: String,
    liquidGlass: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val avatarUrl = profile?.author?.avatarUrl
    val bannerUrl = profile?.bannerUrl
    val tint = rememberDominantColor(bannerUrl ?: avatarUrl ?: "")

    Box(
        modifier
            .height(72.dp)
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        if (bannerUrl != null) {
            AsyncImage(model = bannerUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Box(Modifier.matchParentSize().background(tint.copy(alpha = 0.4f)))
        }
        // The glass reflecting treatment blurs/tints the banner underneath and
        // gives the rim the avatar/banner's own dominant color.
        if (liquidGlass) {
            Box(Modifier.matchParentSize().glassPanel(true, tint = tint, shape = shape))
        } else {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(0.15f))) {
                if (avatarUrl != null) {
                    AsyncImage(model = avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("Profile", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
