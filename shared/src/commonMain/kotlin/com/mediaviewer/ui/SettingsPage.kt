package com.mediaviewer.ui

import com.mediaviewer.ui.compat.rememberPlatformView

import com.mediaviewer.ui.compat.jformat

import kotlinx.coroutines.IO

import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.ui.compat.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.model.DownloadProgress
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.StoredBskyAccount
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel

/** Which of the Settings page's two tabs is showing — switched by the
 *  "Settings / Credits" control at the right end of the Hub's bottom bar. */
internal enum class SettingsTab { SETTINGS, CREDITS, SUPPORT }

/** Everything the reworked Settings page needs beyond what [SettingsSheet]
 *  already took: multiple-account handling, the tagging-model download, and
 *  the e621 download button. Bundled into one object with defaults so it
 *  travels through MainFeedScreen as a single parameter. */
data class SettingsExtras(
    val otherBskyAccounts: List<StoredBskyAccount> = emptyList(),
    val showSwitchAccountsRow: Boolean = true,
    val accountSwitching: Boolean = false,
    val taggerModelReady: Boolean = false,
    val taggerModelDownloading: Boolean = false,
    val downloadIsE621: Boolean = false,
    val onToggleShowSwitchAccountsRow: (Boolean) -> Unit = {},
    /** (handle, appPassword, onResult) — onResult gets null on success or an error message. */
    val onAddBskyAccount: (String, String, (String?) -> Unit) -> Unit = { _, _, done -> done(null) },
    val onSwitchBskyAccount: (String) -> Unit = {},
    val onRemoveBskyAccount: (String) -> Unit = {},
    val onDownloadTaggerModel: () -> Unit = {},
    val onDownloadAllE621Saved: () -> Unit = {},
    /** Data and Privacy → Blocked Accounts → View. */
    val onOpenBlockedAccounts: () -> Unit = {},
    /** Media Tagging → Export Dataset progress (see MainViewModel.DatasetExportState). */
    val datasetExportState: com.mediaviewer.viewmodel.MainViewModel.DatasetExportState = com.mediaviewer.viewmodel.MainViewModel.DatasetExportState.Idle,
    // ── Customize Hub ──
    /** Your own Bluesky lists (Customize Hub → Add list to Hub). */
    val userLists: List<com.mediaviewer.model.BskyList> = emptyList(),
    val userListsLoading: Boolean = false,
    val onEnsureUserLists: () -> Unit = {},
    /** (list URI, list name) */
    val onAddHubList: (String, String) -> Unit = { _, _ -> },
    /** (pasted list link, onDone(error or null)) */
    val onAddHubListFromUrl: (String, (String?) -> Unit) -> Unit = { _, done -> done(null) },
    /** Each Hub list row's loaded content, by list URI. */
    val hubLists: Map<String, com.mediaviewer.viewmodel.MainViewModel.HubListState> = emptyMap(),
    val onLoadHubList: (String) -> Unit = {},
    /** A Hub list row's posts scrolled to the end: load the next page. */
    val onLoadMoreHubList: (String) -> Unit = {},
    /** (list URI, list name, index) — a post tapped in a Hub list row. */
    val onOpenHubListPost: (String, String, Int) -> Unit = { _, _, _ -> },
    /** Customize Hub → Add → Profiles: who the popup lists (your follows,
     *  or the search results), searching, and the finished row. */
    val hubProfileCandidates: List<com.mediaviewer.model.AuthorInfo> = emptyList(),
    val hubProfileSearching: Boolean = false,
    val onSearchHubProfiles: (String) -> Unit = {},
    val onLoadMoreHubProfileSuggestions: () -> Unit = {},
    /** (row name, the accounts picked) */
    val onAddHubProfiles: (String, List<com.mediaviewer.model.AuthorInfo>) -> Unit = { _, _ -> },
    /** (row id, new name, its accounts) — a Profiles row's edit button. */
    val onEditHubProfiles: (String, String, List<com.mediaviewer.model.AuthorInfo>) -> Unit = { _, _, _ -> },
    // ── Dev Tools ──
    val onForceRefreshHub: () -> Unit = {},
    val onPreviewWelcome: () -> Unit = {}
)

// ── Shared building blocks ──────────────────────────────────────────────────

/** The Settings page's accent (switches, sliders, picked values): your
 *  profile color — or Override App Colors' color — lifted to a readable
 *  brightness, instead of a fixed green. */
private val LocalSettingsAccent = androidx.compose.runtime.compositionLocalOf { VoteGreen }

private val BubbleShape = RoundedCornerShape(14.dp)
private val DangerRed = Color(0xFFEF5350)

/** The section titles' color: the same reflected profile color every rim on
 *  this page uses, lifted to a readable brightness (a dominant color sampled
 *  from an avatar is often too dark to read as text on the dark background). */
private fun headerColorFor(tint: Color): Color {
    val hsv = FloatArray(3)
    com.mediaviewer.ui.compat.PlatformColor.colorToHSV(tint.toArgb(), hsv)
    hsv[2] = hsv[2].coerceAtLeast(0.85f)
    return Color(com.mediaviewer.ui.compat.PlatformColor.HSVToColor(hsv))
}

@Composable
private fun SectionHeader(text: String, tint: Color, first: Boolean = false) {
    Text(
        text,
        color = headerColorFor(tint),
        fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(top = if (first) 2.dp else 18.dp)
    )
}

/** One settings bubble. Rows placed inside are plain — they never draw their
 *  own glass surface/outline, only this bubble does — so a bubble holding
 *  several rows (with [BubbleDivider]s between them) reads as one shape. */
@Composable
private fun SettingsBubble(
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    content: @Composable ColumnScope.() -> Unit
) {
    if (liquidGlass) {
        LiquidGlassSurface(Modifier.fillMaxWidth(), shape = BubbleShape, tint = tint, backdrop = backdrop) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    } else {
        Box(Modifier.fillMaxWidth().clip(BubbleShape).background(Color.White.copy(0.04f))) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun BubbleDivider() = HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

@Composable
private fun BubbleRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content
    )
}

@Composable
private fun RowLabel(text: String, modifier: Modifier = Modifier, sub: String? = null, dim: Boolean = false) {
    Column(modifier.padding(end = 12.dp)) {
        Text(text, color = if (dim) DimGray else Color.White, fontSize = 14.sp)
        if (sub != null) Text(sub, color = DimGray, fontSize = 11.sp, lineHeight = 13.sp)
    }
}

/** Material3's Switch has no compact size variant: pin the layout footprint
 *  to roughly two-thirds of the default via an outer Box, then scale the real
 *  Switch to fit (Modifier.scale alone wouldn't shrink the space reserved). */
@Composable
private fun CompactSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val tap = rememberHapticTap()
    Box(modifier = Modifier.size(width = 36.dp, height = 22.dp), contentAlignment = Alignment.Center) {
        Switch(
            checked = checked, onCheckedChange = { tap(); onCheckedChange(it) },
            modifier = Modifier.scale(0.7f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = LocalSettingsAccent.current,
                uncheckedThumbColor = DimGray, uncheckedTrackColor = Color.White.copy(0.1f)
            )
        )
    }
}

/** Full-size Material3 sliders reserve a 48dp touch target around the thumb,
 *  which is what used to make slider rows taller than toggle rows. Custom
 *  thumb/track composables avoid that reservation entirely. */
@Composable
private fun CompactSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Slider(
        value = value, onValueChange = onValueChange, valueRange = 0f..1f,
        modifier = modifier.height(20.dp),
        thumb = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White)) },
        track = { sliderState ->
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f))) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth(fraction = sliderState.value.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(LocalSettingsAccent.current)
                )
            }
        }
    )
}

/** The small pill button used on the right of a row ("Download", "Log out",
 *  "Add", ...). */
@Composable
private fun PillButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, color: Color = Color.White
) {
    val tap = rememberHapticTap()
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Color.White.copy(0.12f) else Color.White.copy(0.05f))
            .clickable(enabled = enabled) { tap(); onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (enabled) color else DimGray,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false
        )
    }
}

/** The white X that follows a Login pill in an inline sign-in row: closes the
 *  row again without signing in. Sized to sit level with [PillButton]. */
@Composable
private fun CancelXButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tap = rememberHapticTap()
    Box(
        modifier.size(28.dp).clip(CircleShape)
            .background(Color.White.copy(0.12f))
            .clickable { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ToggleBubble(
    label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?
) {
    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            RowLabel(label, Modifier.weight(1f))
            CompactSwitch(checked, onCheckedChange)
        }
    }
}

@Composable
private fun ActionBubble(
    label: String, buttonLabel: String, onClick: () -> Unit,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    sub: String? = null, enabled: Boolean = true, buttonColor: Color = Color.White
) {
    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            RowLabel(label, Modifier.weight(1f), sub = sub)
            PillButton(buttonLabel, onClick, enabled = enabled, color = buttonColor)
        }
    }
}

/** A short text field for use inside a [BubbleRow] — 30dp tall, so a row that
 *  swaps its label for input fields stays as short as any other row. */
@Composable
private fun CompactField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    password: Boolean = false, imeAction: ImeAction = ImeAction.Next, onDone: () -> Unit = {}
) {
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Email,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(0.08f)).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) Text(placeholder, color = DimGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        }
    )
}

/** A running/finished export's status, shown as its own line under the
 *  row that started it: an animated bar while the file is being written,
 *  then a check (or the error) once it's done. */
@Composable
private fun ExportStatusLine(working: Boolean, message: String, success: Boolean, tint: Color) {
    val barColor = headerColorFor(tint)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp, top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (working) {
                CircularProgressIndicator(Modifier.size(12.dp), color = barColor, strokeWidth = 1.5.dp)
            } else {
                Text(if (success) "✓" else "!", color = if (success) VoteGreen else DangerRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                message, color = if (working) Color.White.copy(alpha = 0.85f) else if (success) VoteGreen else DangerRed,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        if (working) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = barColor, trackColor = Color.White.copy(alpha = 0.12f)
            )
        }
    }
}

// ── The Settings page ───────────────────────────────────────────────────────

@Composable
internal fun SettingsPageContent(
    reducedAnimations: Boolean,
    onToggleReducedAnimations: (Boolean) -> Unit,
    hateFunBlurNsfw: Boolean,
    onToggleHateFunBlurNsfw: (Boolean) -> Unit,
    squareGridRounded: Boolean,
    onToggleSquareGridRounded: (Boolean) -> Unit,
    followerScanState: MainViewModel.FollowerScanState,
    onRescanFollowersFromScratch: () -> Unit,
    hideTextOnlyPosts: Boolean,
    onToggleHideTextOnlyPosts: (Boolean) -> Unit,
    liquidGlass: Boolean,
    onToggleLiquidGlass: (Boolean) -> Unit,
    liquidGlassIntensity: Float,
    onSetLiquidGlassIntensity: (Float) -> Unit,
    glassRimIntensity: Float,
    onSetGlassRimIntensity: (Float) -> Unit,
    glassRimVibrantSecondary: Boolean,
    onToggleGlassRimVibrantSecondary: (Boolean) -> Unit,
    translationEnabled: Boolean,
    translationTargetLang: String,
    onToggleTranslation: (Boolean) -> Unit,
    onSelectTranslationLanguage: (String) -> Unit,
    customFontName: String?,
    onPickFontFile: (com.mediaviewer.platform.PlatformUri) -> Unit,
    onResetFont: () -> Unit,
    bskyLoggedIn: Boolean,
    bskyHandle: String,
    isLoading: Boolean,
    onLoginBluesky: (String, String) -> Unit,
    onLogoutBluesky: () -> Unit,
    e621LoggedIn: Boolean,
    e621Username: String,
    onLoginE621: (String, String) -> Unit,
    onLogoutE621: () -> Unit,
    downloadOnLike: Boolean,
    onToggleDownloadOnLike: (Boolean) -> Unit,
    downloadProgress: DownloadProgress?,
    onDownloadAllLiked: () -> Unit,
    onCancelDownload: () -> Unit,
    tagPostWhenLiked: Boolean,
    onToggleTagPostWhenLiked: (Boolean) -> Unit,
    taggingRunning: Boolean,
    taggingScanned: Int,
    taggingTagged: Int,
    onLocallyTagAllLiked: () -> Unit,
    onDeleteTaggedDatabase: () -> Unit,
    importedDatasets: List<com.mediaviewer.tagging.TagDatasetInfo>,
    onExportDataset: (String, com.mediaviewer.platform.PlatformUri) -> Unit,
    onImportDataset: (com.mediaviewer.platform.PlatformUri) -> Unit,
    onDeleteImportedDataset: (String) -> Unit,
    combineListsAndPacks: Boolean,
    onToggleCombineListsPacks: (Boolean) -> Unit,
    autoAddToOnFollow: Boolean,
    onToggleAutoAddToOnFollow: (Boolean) -> Unit,
    extras: SettingsExtras,
    dominantColor: Color,
    backdrop: GlassBackdrop?,
    // Live Link widget feature (hidden behind FeatureFlags.LIVE_LINK_ENABLED)
    liveTwitchUrl: String? = null,
    liveYoutubeUrl: String? = null,
    onSaveLiveTwitchUrl: (String) -> Unit = {},
    onSaveLiveYoutubeUrl: (String) -> Unit = {},
    onCreateLiveLinkWidget: () -> Unit = {}
) {
    val tint = dominantColor
    val anyLoggedIn = bskyLoggedIn || e621LoggedIn

    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsAccent provides headerColorFor(tint)) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── UI Customization ────────────────────────────────────────────
        SectionHeader("UI Customization", tint, first = true)

        ToggleBubble("Reduced Animations", reducedAnimations, onToggleReducedAnimations, liquidGlass, tint, backdrop)
        ToggleBubble("Rounded Grid Tiles", squareGridRounded, onToggleSquareGridRounded, liquidGlass, tint, backdrop)
        // Twinkling stars + the odd shooting star behind every page (the
        // dim profile-color background stays either way).
        SettingsBubble(liquidGlass, tint, backdrop) {
            BubbleRow {
                RowLabel("Starry Background", Modifier.weight(1f))
                CompactSwitch(com.mediaviewer.util.UiToggles.starryBackground) { com.mediaviewer.util.UiToggles.updateStarryBackground(it) }
            }
            if (com.mediaviewer.util.UiToggles.starryBackground) {
                BubbleDivider()
                var fpsMenuExpanded by remember { mutableStateOf(false) }
                BubbleRow {
                    RowLabel(
                        "Frame Rate Cap", Modifier.weight(1f),
                        sub = "How smoothly the stars move. Lower lets your screen rest at its idle refresh rate."
                    )
                    Box {
                        Text(
                            "${com.mediaviewer.util.UiToggles.starFrameRate} FPS",
                            color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { fpsMenuExpanded = true }
                        )
                        DropdownMenu(expanded = fpsMenuExpanded, onDismissRequest = { fpsMenuExpanded = false }) {
                            com.mediaviewer.util.UiToggles.starFrameRateOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("$option FPS", fontWeight = if (option == com.mediaviewer.util.UiToggles.starFrameRate) FontWeight.SemiBold else FontWeight.Normal) },
                                    onClick = { com.mediaviewer.util.UiToggles.updateStarFrameRate(option); fpsMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
            }
        }
        // Override App Colors: everywhere the app would wear your profile
        // color, it wears the color picked here instead. Your own profile
        // page keeps its real colors.
        SettingsBubble(liquidGlass, tint, backdrop) {
            var showWheel by remember { mutableStateOf(false) }
            val overrideOn = com.mediaviewer.util.UiToggles.overrideAppColors
            val overrideColor = Color(com.mediaviewer.util.UiToggles.overrideColor)
            BubbleRow {
                RowLabel("Override App Colors", Modifier.weight(1f), sub = "Your profile page keeps its own colors.")
                CompactSwitch(overrideOn) { com.mediaviewer.util.UiToggles.updateOverrideAppColors(it) }
            }
            if (overrideOn) {
                val tapColor = rememberHapticTap()
                BubbleDivider()
                BubbleRow(Modifier.clickable { tapColor(); showWheel = true }) {
                    RowLabel("Color", Modifier.weight(1f))
                    Text(
                        "#%06X".jformat(com.mediaviewer.util.UiToggles.overrideColor and 0xFFFFFF),
                        color = DimGray, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(overrideColor)
                            .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    )
                }
            }
            if (showWheel) {
                ColorWheelDialog(
                    initial = overrideColor,
                    title = "App Color",
                    onDismiss = { showWheel = false },
                    onPick = {
                        com.mediaviewer.util.UiToggles.updateOverrideColor(it.toArgb())
                        showWheel = false
                    }
                )
            }
        }
        // Which transition plays while a page loads: None (pages open
        // instantly and fill in as their data arrives), Pixels, Shatter or
        // Space (the default).
        SettingsBubble(liquidGlass, tint, backdrop) {
            var animMenuExpanded by remember { mutableStateOf(false) }
            val currentAnim = com.mediaviewer.util.UiToggles.loadingAnimation
            BubbleRow {
                RowLabel("Loading Animation", Modifier.weight(1f))
                Box {
                    Text(
                        currentAnim.label,
                        color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { animMenuExpanded = true }
                    )
                    DropdownMenu(expanded = animMenuExpanded, onDismissRequest = { animMenuExpanded = false }) {
                        com.mediaviewer.util.UiToggles.LoadingAnimation.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label, fontWeight = if (option == currentAnim) FontWeight.SemiBold else FontWeight.Normal) },
                                onClick = { com.mediaviewer.util.UiToggles.updateLoadingAnimation(option); animMenuExpanded = false }
                            )
                        }
                    }
                }
            }
        }

        // Item 8: audio visualizer above the feed's interaction bar. It needs
        // the microphone permission to read the phone's audio output
        // (nothing is recorded), so turning it on asks for that first.
        val visualizerContext = com.mediaviewer.ui.compat.LocalContext.current
        val visualizerPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            com.mediaviewer.util.UiToggles.updateAudioVisualizer(granted)
        }
        PlatformFeatureGate(com.mediaviewer.platform.PlatformFeature.AUDIO_VISUALIZER) {
            SettingsBubble(liquidGlass, tint, backdrop) {
                BubbleRow {
                    val visualizerStatus = com.mediaviewer.util.AudioVisualizerEngine.status
                    RowLabel(
                        "Audio Visualizer", Modifier.weight(1f),
                        sub = if (com.mediaviewer.util.UiToggles.audioVisualizer && visualizerStatus.isNotBlank())
                            "Bars on the timeline that move to your music.\nLast check: $visualizerStatus"
                        else "Bars on the timeline that move to your music."
                    )
                    CompactSwitch(com.mediaviewer.util.UiToggles.audioVisualizer) { on ->
                        if (!on) com.mediaviewer.util.UiToggles.updateAudioVisualizer(false)
                        else if (com.mediaviewer.util.AudioVisualizerEngine.hasPermission(visualizerContext)) com.mediaviewer.util.UiToggles.updateAudioVisualizer(true)
                        else visualizerPermission.launch("android.permission.RECORD_AUDIO")
                    }
                }
                if (com.mediaviewer.util.UiToggles.audioVisualizer) {
                    var callMenuExpanded by remember { mutableStateOf(false) }
                    val callMode = com.mediaviewer.util.UiToggles.visualizerCallMode
                    BubbleDivider()
                    BubbleRow {
                        RowLabel(
                            "During Calls", Modifier.weight(1f),
                            sub = when (callMode) {
                                com.mediaviewer.util.UiToggles.VisualizerCallMode.PAUSE -> "The bars rest while you're on a call (phone, Discord…)."
                                com.mediaviewer.util.UiToggles.VisualizerCallMode.MUSIC_ONLY -> "Only your music app, so voices on the call don't move the bars. Works with apps that share their audio, like Spotify or YouTube Music."
                                com.mediaviewer.util.UiToggles.VisualizerCallMode.ALL_AUDIO -> "Everything your phone plays, the call's voices included."
                            }
                        )
                        Box {
                            Text(
                                callMode.label,
                                color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { callMenuExpanded = true }
                            )
                            DropdownMenu(expanded = callMenuExpanded, onDismissRequest = { callMenuExpanded = false }) {
                                com.mediaviewer.util.UiToggles.VisualizerCallMode.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label, fontWeight = if (option == callMode) FontWeight.SemiBold else FontWeight.Normal) },
                                        onClick = { com.mediaviewer.util.UiToggles.updateVisualizerCallMode(option); callMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // App Font: "Import" adds a .ttf/.otf to the list; the list (styled
        // like Loading Animation) shows the selected font — Audiowide by
        // default, the Original system font second, then every import, each
        // written in its own face.
        val fontContext = com.mediaviewer.ui.compat.LocalContext.current
        val fontScope = androidx.compose.runtime.rememberCoroutineScope()
        val fontPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) fontScope.launch {
                val error = com.mediaviewer.util.FontStore.import(fontContext, uri)
                if (error != null) com.mediaviewer.ui.compat.Toast.makeText(fontContext, error, com.mediaviewer.ui.compat.Toast.LENGTH_LONG).show()
            }
        }
        SettingsBubble(liquidGlass, tint, backdrop) {
            var fontMenuExpanded by remember { mutableStateOf(false) }
            val fontStore = com.mediaviewer.util.FontStore
            val selectedFont = fontStore.selected
            BubbleRow {
                RowLabel("App Font", Modifier.weight(1f))
                PlatformFeatureInline(com.mediaviewer.platform.PlatformFeature.CUSTOM_FONT) {
                    PillButton("Import", { fontPickerLauncher.launch("*/*") })
                }
                Spacer(Modifier.width(10.dp))
                Box {
                    Text(
                        fontStore.selectedName,
                        color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp).clickable { fontMenuExpanded = true }
                    )
                    DropdownMenu(expanded = fontMenuExpanded, onDismissRequest = { fontMenuExpanded = false }) {
                        fontStore.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.name,
                                        fontFamily = fontStore.familyFor(option.id) ?: androidx.compose.ui.text.font.FontFamily.Default,
                                        fontWeight = if (option.id == selectedFont) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingIcon = if (option.imported) {
                                    {
                                        Icon(
                                            Icons.Default.Close, contentDescription = "Remove ${option.name}",
                                            modifier = Modifier.size(18.dp).clip(CircleShape).clickable { fontStore.remove(option.id) }
                                        )
                                    }
                                } else null,
                                onClick = { fontStore.select(option.id); fontMenuExpanded = false }
                            )
                        }
                    }
                }
            }
        }

        // ── Customize Hub ───────────────────────────────────────────────
        if (bskyLoggedIn) {
            // The arrow beside the title folds the whole section away.
            val hubCollapsed = com.mediaviewer.util.UiToggles.customizeHubCollapsed
            val hubArrowTap = rememberHapticTap()
            val hubArrowTurn by androidx.compose.animation.core.animateFloatAsState(
                if (hubCollapsed) -90f else 0f, label = "customizeHubArrow"
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Customize Hub", color = headerColorFor(tint),
                    fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(0.12f))
                        .clickable {
                            hubArrowTap()
                            com.mediaviewer.util.UiToggles.updateCustomizeHubCollapsed(!hubCollapsed)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                        contentDescription = if (hubCollapsed) "Show Customize Hub" else "Hide Customize Hub",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = hubArrowTurn }
                    )
                }
            }
            AnimatedVisibility(
                visible = !hubCollapsed,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                CustomizeHubSection(extras = extras, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop)
            }
        }

        // ── App Functionality ───────────────────────────────────────────
        SectionHeader("App Functionality", tint)

        ToggleBubble("Hide Text Only Posts", hideTextOnlyPosts, onToggleHideTextOnlyPosts, liquidGlass, tint, backdrop)
        // NSFW Content: managed by the Bluesky account itself ("Enable adult
        // content" on the Bluesky website). On iOS that account setting is
        // the only switch (App Store rule — see AdultContentPolicy); Android
        // also keeps its own "I Hate Fun" blur, in the same bubble.
        val nsfwContext = com.mediaviewer.ui.compat.LocalContext.current
        SettingsBubble(liquidGlass, tint, backdrop) {
            BubbleRow {
                RowLabel(
                    "NSFW Content", Modifier.weight(1f),
                    sub = if (com.mediaviewer.util.AdultContentPolicy.appliesHere)
                        "Hidden unless adult content is enabled on your Bluesky account. Refresh your feed after changing it."
                    else null
                )
                PillButton("Manage on Bluesky", { com.mediaviewer.ui.compat.openUrl(nsfwContext, "https://bsky.app/moderation") })
            }
            if (!com.mediaviewer.util.AdultContentPolicy.appliesHere) {
                BubbleDivider()
                BubbleRow {
                    RowLabel("I Hate Fun (Blur NSFW Content)", Modifier.weight(1f))
                    CompactSwitch(hateFunBlurNsfw, onToggleHateFunBlurNsfw)
                }
            }
        }

        if (bskyLoggedIn) {
            // Runs the follower scan from scratch — for picking up accounts
            // that started posting reviews/blogs after the last scan, or
            // that were skipped. Opening a profile already auto-subscribes
            // it if it has any, so this is only for accounts never visited.
            val scanning = followerScanState is MainViewModel.FollowerScanState.Scanning
            ActionBubble(
                label = if (scanning) "Scanning Who You Follow…" else "Scan Following for Reviews/Blogs",
                buttonLabel = if (scanning) "…" else "Scan",
                onClick = onRescanFollowersFromScratch,
                liquidGlass = liquidGlass, tint = tint, backdrop = backdrop, enabled = !scanning
            )
        }

        // Translate Post Text + Translate To share one bubble.
        PlatformFeatureGate(com.mediaviewer.platform.PlatformFeature.TRANSLATION) {
            SettingsBubble(liquidGlass, tint, backdrop) {
                BubbleRow {
                    RowLabel("Translate Post Text", Modifier.weight(1f))
                    CompactSwitch(translationEnabled, onToggleTranslation)
                }
                if (translationEnabled) {
                    BubbleDivider()
                    var langMenuExpanded by remember { mutableStateOf(false) }
                    BubbleRow {
                        RowLabel("Translate To", Modifier.weight(1f))
                        Box {
                            Text(
                                com.mediaviewer.util.TranslationManager.SUPPORTED_LANGUAGES
                                    .firstOrNull { it.first == translationTargetLang }?.second
                                    ?: com.mediaviewer.util.TranslationManager.displayNameFor(translationTargetLang),
                                color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { langMenuExpanded = true }
                            )
                            DropdownMenu(expanded = langMenuExpanded, onDismissRequest = { langMenuExpanded = false }) {
                                com.mediaviewer.util.TranslationManager.SUPPORTED_LANGUAGES.forEach { (langTag, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = { onSelectTranslationLanguage(langTag); langMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                    BubbleDivider()
                    BubbleRow {
                        RowLabel("Show Translation Status", Modifier.weight(1f))
                        CompactSwitch(com.mediaviewer.util.UiToggles.showTranslationStatus) {
                            com.mediaviewer.util.UiToggles.updateShowTranslationStatus(it)
                        }
                    }
                }
            }
        }

        if (bskyLoggedIn) {
            ToggleBubble("Show \"Add To\" After Following", autoAddToOnFollow, onToggleAutoAddToOnFollow, liquidGlass, tint, backdrop)
        }

        // Bluesky profile/post links (a scanned QR code, a link in the
        // browser) can open in Stellar — "Set Up" explains how, with
        // shortcuts to both apps' Android settings.
        var linkSetupOpen by remember { mutableStateOf(false) }
        PlatformFeatureGate(com.mediaviewer.platform.PlatformFeature.OPEN_BY_DEFAULT_LINKS) {
            ActionBubble(
                label = "Open Bluesky Links in Stellar",
                buttonLabel = "Set Up",
                onClick = { linkSetupOpen = true },
                liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
            )
        }
        if (linkSetupOpen) {
            OpenLinksSetupDialog(liquidGlass = liquidGlass, tint = tint, onDismiss = { linkSetupOpen = false })
        }

        // ── Integrations ────────────────────────────────────────────────
        SectionHeader("Integrations", tint)

        AtProtocolAccountsBubble(
            bskyLoggedIn = bskyLoggedIn, bskyHandle = bskyHandle, isLoading = isLoading,
            onLoginBluesky = onLoginBluesky, onLogoutBluesky = onLogoutBluesky,
            extras = extras, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
        )

        if (com.mediaviewer.util.FeatureFlags.E621_ENABLED) {
            E621AccountBubble(
                e621LoggedIn = e621LoggedIn, e621Username = e621Username,
                onLoginE621 = onLoginE621, onLogoutE621 = onLogoutE621,
                liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
            )
        }

        // ── Live Link widget feature ────────────────────────────────────
        // Save a Twitch and/or YouTube channel URL here, then "Create
        // Widget" (enabled once at least one is saved) requests the
        // resizable home-screen widget be pinned. Gated behind
        // FeatureFlags.LIVE_LINK_ENABLED: unfinished, so hidden for now, but
        // left fully in place to resume from later.
        if (bskyLoggedIn && com.mediaviewer.util.FeatureFlags.LIVE_LINK_ENABLED) {
            var twitchField by remember(liveTwitchUrl) { mutableStateOf(liveTwitchUrl.orEmpty()) }
            var youtubeField by remember(liveYoutubeUrl) { mutableStateOf(liveYoutubeUrl.orEmpty()) }
            val linkColors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = tint, unfocusedBorderColor = DimGray,
                cursorColor = tint, focusedLabelColor = tint, unfocusedLabelColor = DimGray
            )
            OutlinedTextField(value = twitchField, onValueChange = { twitchField = it },
                label = { Text("Twitch channel URL", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSaveLiveTwitchUrl(twitchField) }),
                colors = linkColors)
            OutlinedTextField(value = youtubeField, onValueChange = { youtubeField = it },
                label = { Text("YouTube channel URL", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSaveLiveYoutubeUrl(youtubeField) }),
                colors = linkColors)
            val widgetEnabled = twitchField.isNotBlank() || youtubeField.isNotBlank() ||
                !liveTwitchUrl.isNullOrBlank() || !liveYoutubeUrl.isNullOrBlank()
            SettingsBubble(liquidGlass, tint, backdrop) {
                BubbleRow {
                    RowLabel("Live Link", Modifier.weight(1f), sub = "The widget can only be created once at least one link is saved.")
                    PillButton("Save", { onSaveLiveTwitchUrl(twitchField); onSaveLiveYoutubeUrl(youtubeField) })
                    Spacer(Modifier.width(6.dp))
                    PillButton("Widget", onCreateLiveLinkWidget, enabled = widgetEnabled)
                }
            }
        }

        // ── Media Tagging ───────────────────────────────────────────────
        SectionHeader("Media Tagging", tint)

        var showExportNameDialog by remember { mutableStateOf(false) }
        var pendingExportName by remember { mutableStateOf("") }
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) onExportDataset(pendingExportName, uri)
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImportDataset(uri)
        }
        // Tap-to-arm confirmation for the destructive delete, same idea as
        // "Add" -> "Added" elsewhere: a stray tap can't wipe the dataset.
        var confirmingDelete by remember { mutableStateOf(false) }
        LaunchedEffect(confirmingDelete) {
            if (confirmingDelete) {
                kotlinx.coroutines.delay(3000)
                confirmingDelete = false
            }
        }
        val hasTaggedData = taggingScanned > 0 || importedDatasets.isNotEmpty()

        PlatformFeatureGate(com.mediaviewer.platform.PlatformFeature.AI_TAGGING) {
            SettingsBubble(liquidGlass, tint, backdrop) {
                BubbleRow {
                    RowLabel("Import Dataset", Modifier.weight(1f))
                    PillButton("Import", { importLauncher.launch(arrayOf("application/json")) })
                }
                // Every imported dataset, each removable on its own (the
                // on-device dataset isn't listed — its delete is in the AI tagging bubble below).
                importedDatasets.forEach { dataset ->
                    BubbleDivider()
                    BubbleRow {
                        RowLabel(
                            dataset.name, Modifier.weight(1f),
                            sub = "${dataset.postCount} post${if (dataset.postCount == 1) "" else "s"}"
                        )
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).clickable { onDeleteImportedDataset(dataset.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove ${dataset.name}", tint = DimGray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        if (showExportNameDialog) {
            ExportDatasetNameDialog(
                liquidGlass = liquidGlass, dominantColor = tint, backdrop = backdrop,
                onConfirm = { name ->
                    pendingExportName = name
                    showExportNameDialog = false
                    val fileSafeName = name.ifBlank { "dataset" }.replace(Regex("[^A-Za-z0-9 _-]"), "").ifBlank { "dataset" }
                    exportLauncher.launch("$fileSafeName.json")
                },
                onDismiss = { showExportNameDialog = false }
            )
        }

        // The on-device model. Once it's downloaded the button turns into a
        // grayed-out "Downloaded" and the tagging options open up beneath it.
        PlatformFeatureGate(com.mediaviewer.platform.PlatformFeature.AI_TAGGING) {
            SettingsBubble(liquidGlass, tint, backdrop) {
                BubbleRow {
                    RowLabel("Download On-Device Tagging Model", Modifier.weight(1f))
                    PillButton(
                        label = when {
                            extras.taggerModelReady -> "Downloaded"
                            extras.taggerModelDownloading -> "Downloading…"
                            else -> "Download"
                        },
                        onClick = extras.onDownloadTaggerModel,
                        enabled = !extras.taggerModelReady && !extras.taggerModelDownloading
                    )
                }
                AnimatedVisibility(visible = extras.taggerModelReady) {
                    Column(Modifier.fillMaxWidth()) {
                        BubbleDivider()
                        BubbleRow {
                            RowLabel("Tag Media When Liked", Modifier.weight(1f))
                            CompactSwitch(tagPostWhenLiked, onToggleTagPostWhenLiked)
                        }
                        if (tagPostWhenLiked) {
                            BubbleDivider()
                            BubbleRow {
                                RowLabel("Show Tagging Status", Modifier.weight(1f), sub = "A bubble on the timeline while liked posts are being tagged.")
                                CompactSwitch(com.mediaviewer.util.UiToggles.showTaggingStatus) {
                                    com.mediaviewer.util.UiToggles.updateShowTaggingStatus(it)
                                }
                            }
                        }
                        BubbleDivider()
                        BubbleRow {
                            RowLabel(
                                "Tag Previously Liked Media", Modifier.weight(1f),
                                sub = when {
                                    taggingRunning -> "$taggingScanned scanned"
                                    taggingScanned > 0 -> "$taggingTagged tagged"
                                    else -> null
                                }
                            )
                            PillButton(
                                if (taggingRunning) "…" else "Tag", onLocallyTagAllLiked,
                                enabled = !taggingRunning && anyLoggedIn
                            )
                        }
                    }
                }
                // Dataset housekeeping lives at the very bottom of this bubble,
                // and stays visible whether or not the model is downloaded (an
                // imported dataset can exist without it).
                if (hasTaggedData) {
                    BubbleDivider()
                    val exportState = extras.datasetExportState
                    val exportWorking = exportState is MainViewModel.DatasetExportState.Working
                    BubbleRow {
                        RowLabel("Export Dataset", Modifier.weight(1f))
                        PillButton(
                            if (exportWorking) "Exporting…" else "Export",
                            { pendingExportName = ""; showExportNameDialog = true },
                            enabled = !exportWorking
                        )
                    }
                    AnimatedVisibility(visible = exportState !is MainViewModel.DatasetExportState.Idle) {
                        when (exportState) {
                            is MainViewModel.DatasetExportState.Working ->
                                ExportStatusLine(true, exportState.stage, success = false, tint = tint)
                            is MainViewModel.DatasetExportState.Done ->
                                ExportStatusLine(false, "Exported ${exportState.postCount} post${if (exportState.postCount == 1) "" else "s"} — saved to the file you picked.", success = true, tint = tint)
                            is MainViewModel.DatasetExportState.Failed ->
                                ExportStatusLine(false, "Export failed: ${exportState.message}", success = false, tint = tint)
                            MainViewModel.DatasetExportState.Idle -> Spacer(Modifier.height(0.dp))
                        }
                    }
                    BubbleDivider()
                    BubbleRow {
                        RowLabel("Delete Tagged Posts Dataset", Modifier.weight(1f))
                        PillButton(
                            if (confirmingDelete) "Really?" else "Delete",
                            onClick = {
                                if (confirmingDelete) { confirmingDelete = false; onDeleteTaggedDatabase() }
                                else confirmingDelete = true
                            },
                            enabled = !taggingRunning, color = DangerRed
                        )
                    }
                }
            }
        }

        // ── Data and Privacy ────────────────────────────────────────────
        SectionHeader("Data and Privacy", tint)
        if (bskyLoggedIn) {
            ActionBubble(
                label = "Blocked Accounts",
                sub = "Hidden everywhere in Stellar, along with anyone who's blocked you.",
                buttonLabel = "View",
                onClick = extras.onOpenBlockedAccounts,
                liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
            )
        }
        if (anyLoggedIn) {

            if (bskyLoggedIn) {
                val prog = downloadProgress.takeIf { !extras.downloadIsE621 }
                val running = prog?.isRunning == true
                ActionBubble(
                    label = "Download all liked AT Protocol media",
                    sub = when {
                        running -> "${prog?.count ?: 0} queued"
                        prog != null && prog.count > 0 -> "Done — ${prog.count} queued"
                        else -> null
                    },
                    buttonLabel = if (running) "Cancel" else "Download",
                    onClick = { if (running) onCancelDownload() else onDownloadAllLiked() },
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    enabled = running || downloadProgress?.isRunning != true
                )
            }
            if (e621LoggedIn) {
                val prog = downloadProgress.takeIf { extras.downloadIsE621 }
                val running = prog?.isRunning == true
                ActionBubble(
                    label = "Download all saved e621 media",
                    sub = when {
                        running -> "${prog?.count ?: 0} queued"
                        prog != null && prog.count > 0 -> "Done — ${prog.count} queued"
                        else -> null
                    },
                    buttonLabel = if (running) "Cancel" else "Download",
                    onClick = { if (running) onCancelDownload() else extras.onDownloadAllE621Saved() },
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    enabled = running || downloadProgress?.isRunning != true
                )
            }
            // The one auto-download switch covers both services (it's a
            // single shared preference) — new likes/saves get downloaded as
            // they happen, on top of the one-time buttons above.
            ToggleBubble("Auto-Download New Likes and Saves", downloadOnLike, onToggleDownloadOnLike, liquidGlass, tint, backdrop)
        }

        // Item 7: everything local in one file — settings, VRM + Live
        // settings, the main AI-tagged dataset, Blog/Review subscriptions.
        // Importing replaces them (the tagged dataset becomes the main one)
        // and restarts the app so every screen reloads with them.
        val backupContext = com.mediaviewer.ui.compat.LocalContext.current
        val backupScope = rememberCoroutineScope()
        var backupBusy by remember { mutableStateOf(false) }
        // What the Export/Import rows show underneath while (and just after)
        // a backup file is written or read: null = nothing.
        var backupStatus by remember { mutableStateOf<Triple<Boolean, String, Boolean>?>(null) } // (working, message, success)
        LaunchedEffect(backupStatus) {
            val st = backupStatus
            if (st != null && !st.first) { kotlinx.coroutines.delay(6000); if (backupStatus == st) backupStatus = null }
        }
        var confirmingImport by remember { mutableStateOf(false) }
        LaunchedEffect(confirmingImport) {
            if (confirmingImport) { kotlinx.coroutines.delay(4000); confirmingImport = false }
        }
        val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                backupBusy = true
                backupStatus = Triple(true, "Exporting app data…", false)
                backupScope.launch {
                    val result = runCatching { com.mediaviewer.util.AppBackup.export(backupContext, uri) }
                    backupBusy = false
                    val msg = result.getOrElse { "Export failed: ${it.message}" }
                    backupStatus = Triple(false, if (result.isSuccess) "Export complete — $msg" else msg, result.isSuccess)
                    com.mediaviewer.ui.compat.Toast.makeText(backupContext, msg, com.mediaviewer.ui.compat.Toast.LENGTH_LONG).show()
                }
            }
        }
        val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                backupBusy = true
                backupStatus = Triple(true, "Importing app data…", false)
                backupScope.launch {
                    val result = runCatching { com.mediaviewer.util.AppBackup.import(backupContext, uri) }
                    backupBusy = false
                    backupStatus = Triple(false, result.fold({ "Imported — restarting…" }, { "Import failed: ${it.message}" }), result.isSuccess)
                    result.onSuccess { msg ->
                        com.mediaviewer.ui.compat.Toast.makeText(backupContext, "$msg — restarting…", com.mediaviewer.ui.compat.Toast.LENGTH_LONG).show()
                        kotlinx.coroutines.delay(900)
                        com.mediaviewer.ui.compat.restartApp(backupContext)
                    }.onFailure {
                        com.mediaviewer.ui.compat.Toast.makeText(backupContext, "Import failed: ${it.message}", com.mediaviewer.ui.compat.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        SettingsBubble(liquidGlass, tint, backdrop) {
            BubbleRow {
                RowLabel("Export App Data", Modifier.weight(1f), sub = "Settings, VRM & Live settings, tagged posts and subscriptions, in one file.")
                PillButton(if (backupBusy) "Working…" else "Export", {
                    val date = com.mediaviewer.util.DateText.format(com.mediaviewer.platform.currentTimeMillis(), "yyyy-MM-dd")
                    backupExportLauncher.launch("Stellar-backup-$date.json")
                }, enabled = !backupBusy)
            }
            AnimatedVisibility(visible = backupStatus != null) {
                val st = backupStatus
                if (st != null) ExportStatusLine(st.first, st.second, st.third, tint)
                else Spacer(Modifier.height(0.dp))
            }
            BubbleDivider()
            BubbleRow {
                RowLabel("Import App Data", Modifier.weight(1f), sub = "Replaces your settings and main tagged dataset, then restarts.")
                PillButton(
                    if (backupBusy) "…" else if (confirmingImport) "Replace?" else "Import",
                    {
                        if (confirmingImport) { confirmingImport = false; backupImportLauncher.launch(arrayOf("application/json", "*/*")) }
                        else confirmingImport = true
                    },
                    enabled = !backupBusy, color = if (confirmingImport) DangerRed else Color.White
                )
            }
        }

        // ── Dev Tools (hidden: hold the "Settings" tab for 10 seconds) ─
        if (com.mediaviewer.util.UiToggles.devToolsUnlocked) {
            SectionHeader("Dev Tools", tint)
            // Frame rate beside the camera cutout — see DebugOverlay.
            ToggleBubble(
                "FPS Overlay", com.mediaviewer.util.UiToggles.debugOverlay,
                { com.mediaviewer.util.UiToggles.updateDebugOverlay(it) }, liquidGlass, tint, backdrop
            )
            // Glass Theme + its Background/Outline dials + the highlight toggle
            // share one bubble; none of the rows inside draws its own outline.
            SettingsBubble(liquidGlass, tint, backdrop) {
                BubbleRow {
                    RowLabel("Glass Theme", Modifier.weight(1f))
                    CompactSwitch(liquidGlass, onToggleLiquidGlass)
                }
                if (liquidGlass) {
                    BubbleDivider()
                    BubbleRow {
                        // widthIn(min=) rather than a fixed width: the app's
                        // custom font is wider, and a fixed width wrapped
                        // "Background" onto two lines.
                        Text("Background", color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false,
                            modifier = Modifier.widthIn(min = 74.dp))
                        CompactSlider(liquidGlassIntensity, onSetLiquidGlassIntensity, Modifier.weight(1f).padding(horizontal = 10.dp))
                        Text("${(liquidGlassIntensity * 100).toInt()}%", color = DimGray, fontSize = 12.sp,
                            modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                    }
                    BubbleDivider()
                    BubbleRow {
                        Text("Outline", color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false,
                            modifier = Modifier.widthIn(min = 74.dp))
                        CompactSlider(glassRimIntensity, onSetGlassRimIntensity, Modifier.weight(1f).padding(horizontal = 10.dp))
                        Text("${(glassRimIntensity * 100).toInt()}%", color = DimGray, fontSize = 12.sp,
                            modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                    }
                    BubbleDivider()
                    BubbleRow {
                        RowLabel("Vibrant Outline Highlight", Modifier.weight(1f))
                        CompactSwitch(glassRimVibrantSecondary, onToggleGlassRimVibrantSecondary)
                    }
                }
            }
            SettingsBubble(liquidGlass, tint, backdrop) {
                BubbleRow {
                    RowLabel("Show Scan Bubble in Hub", Modifier.weight(1f), sub = "Replaces the Reviews/Blogs rows with the scan-your-follows bubble.")
                    CompactSwitch(com.mediaviewer.util.UiToggles.devForceScanBubble) { com.mediaviewer.util.UiToggles.updateDevForceScanBubble(it) }
                }
                BubbleDivider()
                BubbleRow {
                    RowLabel("Preview Loading Animation", Modifier.weight(1f), sub = "Tap the screen to move it along; Back closes it.")
                    PillButton("Play", { com.mediaviewer.util.UiToggles.devLoadingPreview = true })
                }
                BubbleDivider()
                BubbleRow {
                    RowLabel("Preview Login Page", Modifier.weight(1f), sub = "Opens it without logging out; Back closes it.")
                    PillButton("Open", { com.mediaviewer.util.UiToggles.devLoginPreview = true })
                }
                BubbleDivider()
                BubbleRow {
                    RowLabel("Force Refresh Hub", Modifier.weight(1f), sub = "Reloads every Hub row from scratch.")
                    PillButton("Refresh", extras.onForceRefreshHub, enabled = bskyLoggedIn)
                }
                BubbleDivider()
                BubbleRow {
                    RowLabel("Preview Welcome Popup", Modifier.weight(1f), sub = "Opens in the Hub; nothing is added until Continue.")
                    PillButton("Open", extras.onPreviewWelcome, enabled = bskyLoggedIn)
                }
                BubbleDivider()
                BubbleRow {
                    RowLabel("Preview Support Popup", Modifier.weight(1f), sub = "The one shown on the 10th, 25th, 50th… open.")
                    PillButton("Open", { com.mediaviewer.util.UiToggles.devSupportPreview = true })
                }
                BubbleDivider()
                val coverContext = com.mediaviewer.ui.compat.LocalContext.current
                val coverScope = rememberCoroutineScope()
                BubbleRow {
                    RowLabel("Clear Cached Title Covers", Modifier.weight(1f), sub = "Removes saved review/backlog covers (incl. Wikipedia lookups) from this phone.")
                    PillButton("Clear", {
                        coverScope.launch {
                            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.mediaviewer.repository.WikipediaRepository.clearCoverCache()
                                com.mediaviewer.util.TitleCovers.clearCached(coverContext)
                            }
                            com.mediaviewer.ui.compat.Toast.makeText(coverContext, "Cleared $n cached title cover${if (n == 1) "" else "s"}", com.mediaviewer.ui.compat.Toast.LENGTH_SHORT).show()
                        }
                    }, color = DangerRed)
                }
                BubbleDivider()
                BubbleRow {
                    RowLabel("Hide Dev Tools", Modifier.weight(1f))
                    PillButton("Hide", { com.mediaviewer.util.UiToggles.updateDevToolsUnlocked(false) }, color = DangerRed)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
    }
}

/** The AT Protocol accounts bubble: the active account, every other signed-in
 *  account (each with Switch To / Log out), and an "Add additional AT
 *  Protocol account" row that turns into a handle + app password + Login row
 *  when tapped — all in one bubble that grows downward. */
@Composable
private fun AtProtocolAccountsBubble(
    bskyLoggedIn: Boolean, bskyHandle: String, isLoading: Boolean,
    onLoginBluesky: (String, String) -> Unit, onLogoutBluesky: () -> Unit,
    extras: SettingsExtras, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?
) {
    // Used for the first sign-in when nobody's logged in yet, and for adding
    // another account afterwards — one set of fields, two destinations.
    var adding by remember { mutableStateOf(false) }
    var handleField by remember { mutableStateOf("") }
    var passwordField by remember { mutableStateOf("") }
    var addBusy by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    fun cancelAdd() { adding = false; handleField = ""; passwordField = ""; addError = null }

    // Back closes an open add row instead of leaving the page.
    BackHandler(enabled = adding && bskyLoggedIn) { cancelAdd() }

    fun submit() {
        val id = handleField.trim()
        if (id.isBlank() || passwordField.isBlank() || addBusy) return
        if (!bskyLoggedIn) {
            onLoginBluesky(id, passwordField)
            return
        }
        addBusy = true
        addError = null
        extras.onAddBskyAccount(id, passwordField) { error ->
            addBusy = false
            if (error == null) {
                // Back to the normal "Add" row; the new account's own row
                // appears above it.
                adding = false; handleField = ""; passwordField = ""
            } else addError = error
        }
    }

    @Composable
    fun LoginFieldsRow(buttonLabel: String, busy: Boolean, onCancel: (() -> Unit)? = null) {
        BubbleRow {
            CompactField(handleField, { handleField = it }, "handle", Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            CompactField(
                passwordField, { passwordField = it }, "app password", Modifier.weight(1f),
                password = true, imeAction = ImeAction.Done, onDone = { submit() }
            )
            Spacer(Modifier.width(6.dp))
            PillButton(
                if (busy) "…" else buttonLabel, { submit() },
                enabled = !busy && handleField.isNotBlank() && passwordField.isNotBlank()
            )
            if (onCancel != null) {
                Spacer(Modifier.width(6.dp))
                CancelXButton(onCancel)
            }
        }
    }

    SettingsBubble(liquidGlass, tint, backdrop) {
        if (!bskyLoggedIn) {
            BubbleRow { RowLabel("Not logged in to the AT Protocol", Modifier.weight(1f), dim = true) }
            BubbleDivider()
            LoginFieldsRow("Login", isLoading)
            return@SettingsBubble
        }

        // The active account.
        BubbleRow {
            Text(
                buildAnnotatedString {
                    append("Logged in to the AT Protocol as ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("@$bskyHandle") }
                },
                color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 12.dp)
            )
            PillButton("Log out", onLogoutBluesky, enabled = !extras.accountSwitching, color = DangerRed)
        }

        // Every other signed-in account, sitting between the active account
        // and the add row.
        extras.otherBskyAccounts.forEach { account ->
            BubbleDivider()
            BubbleRow {
                Text(
                    "@${account.handle}", color = Color.White, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                PillButton(
                    "Switch To", { extras.onSwitchBskyAccount(account.did) },
                    enabled = !extras.accountSwitching
                )
                Spacer(Modifier.width(6.dp))
                PillButton(
                    "Log out", { extras.onRemoveBskyAccount(account.did) },
                    enabled = !extras.accountSwitching, color = DangerRed
                )
            }
        }

        BubbleDivider()
        if (adding) {
            LoginFieldsRow("Login", addBusy, onCancel = { cancelAdd() })
            val error = addError
            if (error != null) {
                Text(
                    error, color = DangerRed, fontSize = 11.sp, lineHeight = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
                )
            }
        } else {
            BubbleRow {
                RowLabel("Add additional AT Protocol account", Modifier.weight(1f))
                PillButton("Add", { adding = true; addError = null })
            }
        }

        if (extras.otherBskyAccounts.isNotEmpty()) {
            BubbleDivider()
            BubbleRow {
                RowLabel("Show \"Switch Accounts\" row in the Hub", Modifier.weight(1f))
                CompactSwitch(extras.showSwitchAccountsRow, extras.onToggleShowSwitchAccountsRow)
            }
        }
    }
}

// ── e621 account bubble ─────────────────────────────────────────────────────

/** The e621 row: shows the signed-in user with a Log out button, or a Login
 *  button that swaps the row for the same inline username + API key + Login
 *  (+ white X to cancel) layout the AT Protocol "Add" row uses. */
@Composable
private fun E621AccountBubble(
    e621LoggedIn: Boolean, e621Username: String,
    onLoginE621: (String, String) -> Unit, onLogoutE621: () -> Unit,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?
) {
    var adding by remember { mutableStateOf(false) }
    var userField by remember { mutableStateOf("") }
    var keyField by remember { mutableStateOf("") }
    val canSubmit = userField.isNotBlank() && keyField.isNotBlank()

    fun cancel() { adding = false; userField = ""; keyField = "" }
    fun submit() {
        if (!canSubmit) return
        // Signing in just returns to this row (now showing the username);
        // it never opens the feed.
        onLoginE621(userField.trim(), keyField.trim())
        cancel()
    }

    // Back closes the open sign-in row instead of leaving the page.
    BackHandler(enabled = adding && !e621LoggedIn) { cancel() }

    SettingsBubble(liquidGlass, tint, backdrop) {
        if (adding && !e621LoggedIn) {
            BubbleRow {
                CompactField(userField, { userField = it }, "e621 username", Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                CompactField(
                    keyField, { keyField = it }, "API key", Modifier.weight(1f),
                    password = true, imeAction = ImeAction.Done, onDone = { submit() }
                )
                Spacer(Modifier.width(6.dp))
                PillButton("Login", { submit() }, enabled = canSubmit)
                Spacer(Modifier.width(6.dp))
                CancelXButton({ cancel() })
            }
        } else {
            BubbleRow {
                RowLabel("e621", Modifier.weight(1f), sub = if (e621LoggedIn) "@$e621Username" else null)
                PillButton(
                    if (e621LoggedIn) "Log out" else "Login",
                    { if (e621LoggedIn) onLogoutE621() else adding = true },
                    color = if (e621LoggedIn) DangerRed else Color.White
                )
            }
        }
    }
}

// ── About page ──────────────────────────────────────────────────────────────

/** Business contact shown on the About page. */
internal const val STELLAR_CONTACT_EMAIL = "RechoRaccoonBusiness@proton.me"

/**
 * Settings → About: every credit on one compact screen (no scrolling), with
 * the business contact at the bottom. On a short screen the whole block
 * scales down to fit rather than scrolling.
 */
@Composable
internal fun AboutPageContent() {
    val recho = Color(0xFF00FF07)
    val rose = Color(0xFFE0245E)
    val dim = Color.White.copy(alpha = 0.6f)
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val tap = rememberHapticTap()

    @Composable
    fun Header(text: String) {
        Text(
            text.uppercase(), color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
        )
    }
    /** "Name — what it's for" on one line. */
    @Composable
    fun Line(name: String, role: String, nameColor: Color = Color.White) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.SemiBold)) { append(name) }
                if (role.isNotEmpty()) withStyle(SpanStyle(color = dim)) { append(" · $role") }
            },
            fontSize = 12.sp, lineHeight = 16.sp
        )
    }
    @Composable
    fun Small(text: String) {
        Text(text, color = dim, fontSize = 11.sp, lineHeight = 15.sp)
    }

    FitToHeight(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Header("Created by")
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = recho, fontWeight = FontWeight.SemiBold)) { append("Recho Raccoon") }
                    withStyle(SpanStyle(color = dim)) { append(" · coded with Claude Sonnet, Claude Opus 5.5 and Muse by Meta") }
                },
                fontSize = 12.sp, lineHeight = 16.sp
            )

            Header("Special thanks")
            Line("Rose (SomeDudeGT)", "publishing builds on GitHub", rose)

            Header("AT Protocol")
            Line("Bluesky", "accounts, posts and feeds")
            Line("Leaflet / Standard.site", "long-form blogs")
            Line("Popfeed", "title reviews, backlog and covers")
            Line("Rocksky", "music listening history and yearly top stats")
            Line("Streamplace", "livestreams")

            Header("Other services")
            Line("Wikipedia & Wikidata", "synopses (CC BY-SA 4.0) and fallback covers")
            Line("e621", "content browsing")
            Line("DecAPI", "Twitch live status")
            Line("Hugging Face", "hosts the tagging model")

            Header("On-device AI")
            Line("Z3D-E621-Convnext", "tagging, by Zack3D, via ONNX Runtime")
            Line("MediaPipe", "VRM face, hand and pose tracking (Google)")
            Line("ML Kit", "translation and language ID (Google)")

            Header("Open source")
            Small("Jetpack Compose, Media3/ExoPlayer, CameraX, Filament, Coil, OkHttp, Retrofit, Gson, ZXing, and the Audiowide font by Astigmatic (SIL OFL).")

            Spacer(Modifier.height(12.dp))
            Small("All trademarks, logos and cover art belong to their respective owners. Stellar is independent and not affiliated with or endorsed by the services above.")
            Spacer(Modifier.height(10.dp))
            Small("For app inquiries or copyright and trademark concerns, please contact:")
            Text(
                STELLAR_CONTACT_EMAIL, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp))
                    .clickable { tap(); runCatching { uriHandler.openUri("mailto:$STELLAR_CONTACT_EMAIL") } }
                    .padding(vertical = 2.dp)
            )
        }
    }
}

/** Lays [content] out at its natural height and, if that's taller than the
 *  space available, scales it down (from the top) so it always fits on one
 *  screen instead of scrolling. */
@Composable
private fun FitToHeight(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.first().measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        )
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
        val scale = if (placeable.height > maxH && placeable.height > 0) maxH.toFloat() / placeable.height else 1f
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        layout(width, maxH.coerceAtLeast(0)) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }
        }
    }
}

// ── Customize Hub ───────────────────────────────────────────────────────────

/**
 * Settings → Customize Hub: one bubble per Hub row (the default rows, then
 * any added lists and profile rows), each with a grab handle on the left to
 * drag it into a new place, an on/off switch, and an X to remove it (tap
 * once to arm, again to remove — same as deleting a post). List and profile
 * rows also get a "Profiles"/"Posts" mode button. The last bubble, "Add",
 * puts rows in: "Default" (any default row that was removed — ones already
 * in the Hub are greyed out), "Profiles" (pick accounts into a row that
 * lives only in Stellar) and "List" (one of your Bluesky lists, or "Other"
 * to paste any list link). Everything is saved in
 * [com.mediaviewer.util.HubLayout].
 */
@Composable
private fun CustomizeHubSection(
    extras: SettingsExtras, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?
) {
    val hub = com.mediaviewer.util.HubLayout
    val rows = hub.rows
    val density = androidx.compose.ui.platform.LocalDensity.current
    val view = com.mediaviewer.ui.compat.rememberPlatformView()
    val gapPx = with(density) { 8.dp.toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }

    fun buzz() { runCatching { view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CLOCK_TICK) } }
    // The Profiles row whose edit button was pressed.
    var editingProfilesRow by remember { mutableStateOf<com.mediaviewer.util.HubLayout.Row?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            key(row.id) {
                val dragging = draggingId == row.id
                Box(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragging) 1f else 0f)
                        .onSizeChanged { heights[row.id] = it.height }
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            val s = if (dragging) 1.02f else 1f
                            scaleX = s; scaleY = s
                        }
                ) {
                    val handle = Modifier.pointerInput(row.id) {
                        detectDragGestures(
                            onDragStart = { draggingId = row.id; dragOffset = 0f; buzz() },
                            onDragEnd = { draggingId = null; dragOffset = 0f },
                            onDragCancel = { draggingId = null; dragOffset = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                // Read live (not the composition's copy): a
                                // move just made must count straight away.
                                val list = hub.rows
                                val idx = list.indexOfFirst { it.id == row.id }
                                if (idx < 0) return@detectDragGestures
                                if (dragOffset > 0f && idx < list.lastIndex) {
                                    val step = (heights[list[idx + 1].id] ?: 0) + gapPx
                                    if (step > gapPx && dragOffset > step / 2f) {
                                        hub.move(idx, idx + 1); dragOffset -= step; buzz()
                                    }
                                } else if (dragOffset < 0f && idx > 0) {
                                    val step = (heights[list[idx - 1].id] ?: 0) + gapPx
                                    if (step > gapPx && -dragOffset > step / 2f) {
                                        hub.move(idx, idx - 1); dragOffset += step; buzz()
                                    }
                                }
                            }
                        )
                    }
                    HubRowBubble(
                        row = row, handle = handle, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                        onEdit = if (row.isProfiles) ({ editingProfilesRow = row }) else null
                    )
                }
            }
        }

        // ── Add: Default / Profiles / List ──
        var menuOpen by remember { mutableStateOf(false) }
        var defaultMenuOpen by remember { mutableStateOf(false) }
        var profilesPopupOpen by remember { mutableStateOf(false) }
        var urlPopupOpen by remember { mutableStateOf(false) }
        SettingsBubble(liquidGlass, tint, backdrop) {
            BubbleRow {
                RowLabel("Add", Modifier.weight(1f))
                Box {
                    PillButton("Default", { defaultMenuOpen = true })
                    // Every default row, in the default order; the ones
                    // already in the Hub are greyed out.
                    DropdownMenu(expanded = defaultMenuOpen, onDismissRequest = { defaultMenuOpen = false }) {
                        hub.defaultRowIds.forEach { id ->
                            val added = rows.any { it.id == id }
                            DropdownMenuItem(
                                text = { Text(hub.BUILT_IN_LABELS[id] ?: id, color = if (added) DimGray else Color.Unspecified) },
                                onClick = { defaultMenuOpen = false; hub.addDefault(id) },
                                enabled = !added
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                PillButton("Profiles", { profilesPopupOpen = true })
                Spacer(Modifier.width(8.dp))
                Box {
                    PillButton("List", { extras.onEnsureUserLists(); menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val lists = extras.userLists.filter { !it.purpose.contains("referencelist") }
                        if (lists.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(if (extras.userListsLoading) "Loading your lists…" else "You have no lists yet", color = DimGray) },
                                onClick = {}, enabled = false
                            )
                        }
                        lists.forEach { list ->
                            val added = rows.any { it.listUri == list.uri }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        list.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (added) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                onClick = { menuOpen = false; extras.onAddHubList(list.uri, list.name) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Other", fontWeight = FontWeight.SemiBold) },
                            onClick = { menuOpen = false; urlPopupOpen = true }
                        )
                    }
                }
            }
        }
        if (urlPopupOpen) {
            HubListUrlDialog(
                liquidGlass = liquidGlass, tint = tint,
                onAdd = { url, done -> extras.onAddHubListFromUrl(url) { err -> done(err); if (err == null) urlPopupOpen = false } },
                onDismiss = { urlPopupOpen = false }
            )
        }
        editingProfilesRow?.let { row ->
            HubProfilesDialog(
                candidates = extras.hubProfileCandidates, searching = extras.hubProfileSearching, tint = tint,
                onSearch = extras.onSearchHubProfiles,
                onLoadMoreSuggestions = extras.onLoadMoreHubProfileSuggestions,
                onAdd = { name, members -> editingProfilesRow = null; extras.onEditHubProfiles(row.id, name, members) },
                onClose = { editingProfilesRow = null },
                initialName = row.name,
                initialSelected = row.profiles.map { com.mediaviewer.model.AuthorInfo(it.did, it.handle, it.displayName, it.avatarUrl) },
                editing = true
            )
        }
        if (profilesPopupOpen) {
            HubProfilesDialog(
                candidates = extras.hubProfileCandidates, searching = extras.hubProfileSearching, tint = tint,
                onSearch = extras.onSearchHubProfiles,
                onLoadMoreSuggestions = extras.onLoadMoreHubProfileSuggestions,
                onAdd = { name, members -> profilesPopupOpen = false; extras.onAddHubProfiles(name, members) },
                onClose = { profilesPopupOpen = false }
            )
        }
    }
}

/** One row of Customize Hub: grab handle, name, (list/profiles: Profiles/Posts), on/off, X. */
@Composable
private fun HubRowBubble(
    row: com.mediaviewer.util.HubLayout.Row,
    handle: Modifier,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    /** Profiles rows: reopen the picker to add/remove accounts or rename. */
    onEdit: (() -> Unit)? = null
) {
    val hub = com.mediaviewer.util.HubLayout
    val editTap = rememberHapticTap()
    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            Box(
                handle.size(width = 30.dp, height = 34.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.DragIndicator,
                    contentDescription = "Drag to reorder", tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                row.label, color = if (row.enabled) Color.White else DimGray, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            if (onEdit != null) {
                // The same pen as editing your own profile, as a grey circle.
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(0.12f))
                        .clickable { editTap(); onEdit() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(androidx.compose.material.icons.Icons.Default.Edit, contentDescription = "Edit", tint = Color.White, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            if (row.hasMembers) {
                PillButton(if (row.showPosts) "Posts" else "Profiles", { hub.setShowPosts(row.id, !row.showPosts) })
                Spacer(Modifier.width(8.dp))
            }
            CompactSwitch(row.enabled) { hub.setEnabled(row.id, it) }
            // Every row can be taken out; default ones come back from
            // Add → Default.
            Spacer(Modifier.width(8.dp))
            RemoveHubListButton(onRemove = { hub.remove(row.id) })
        }
    }
}

/** The X on a Hub list row: the first tap asks ("Remove?" in red), the
 *  second removes it — the same two-tap confirm as deleting a post. */
@Composable
private fun RemoveHubListButton(onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) { kotlinx.coroutines.delay(3000); confirming = false }
    }
    if (confirming) {
        PillButton("Remove?", { confirming = false; onRemove() }, color = DangerRed)
    } else {
        CancelXButton({ confirming = true })
    }
}

/** Customize Hub → Add → Other: a compact "List URL" popup. */
@Composable
private fun HubListUrlDialog(
    liquidGlass: Boolean, tint: Color,
    onAdd: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null, onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            val shape = RoundedCornerShape(20.dp)
            @Composable
            fun Content() {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("List URL", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CompactField(
                            url, { url = it; error = null }, "https://bsky.app/profile/…/lists/…",
                            Modifier.weight(1f), imeAction = ImeAction.Done,
                            onDone = { if (url.isNotBlank() && !busy) { busy = true; onAdd(url.trim()) { err -> busy = false; error = err } } }
                        )
                        Spacer(Modifier.width(8.dp))
                        PillButton(
                            if (busy) "…" else "Add",
                            { busy = true; onAdd(url.trim()) { err -> busy = false; error = err } },
                            enabled = url.isNotBlank() && !busy
                        )
                    }
                    val err = error
                    if (err != null) Text(err, color = DangerRed, fontSize = 11.sp, lineHeight = 13.sp)
                }
            }
            val m = Modifier.fillMaxWidth(0.9f)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}
            if (liquidGlass) {
                LiquidGlassSurface(m, shape = shape, tint = tint) { Content() }
            } else {
                Box(m.clip(shape).background(OffBlack).border(1.dp, tint.copy(alpha = 0.5f), shape)) { Content() }
            }
        }
    }
}


/** Opens Android's "Open by default" page for [packageName] (the app's info
 *  page on older Android, or if that one isn't available). */
private fun openAppLinkSettings(context: com.mediaviewer.platform.PlatformContext, packageName: String) {
    if (!com.mediaviewer.ui.compat.openAppLinkSettings(context, packageName)) {
        com.mediaviewer.ui.compat.Toast.makeText(context, "Couldn't open that app's settings", com.mediaviewer.ui.compat.Toast.LENGTH_SHORT).show()
    }
}

/** Settings → Open Bluesky Links in Stellar → Set Up: a small centered
 *  popup with the two steps and a button for each app's settings. */
@Composable
private fun OpenLinksSetupDialog(liquidGlass: Boolean, tint: Color, onDismiss: () -> Unit) {
    val context = com.mediaviewer.ui.compat.LocalContext.current
    val tap = rememberHapticTap()
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = com.mediaviewer.ui.compat.edgeToEdgeDialogProperties()
    ) {
        // Blurs (and dims) everything behind the popup.
        com.mediaviewer.ui.compat.DialogBlurBehind(radius = 48, dimAmount = 0.45f)
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onDismiss
            ),
            contentAlignment = Alignment.Center
        ) {
            val shape = RoundedCornerShape(22.dp)
            @Composable
            fun Step(number: String, text: String) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).background(headerColorFor(tint).copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) { Text(number, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp))
                    Text(text, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                }
            }
            @Composable
            fun Content() {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Open Bluesky Links in Stellar", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    Step("1", "Tap Bluesky Settings and choose \"In your browser\".")
                    Step("2", "Tap Stellar Settings, then \"Add link\" and turn on bsky.app.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Bluesky Settings" to "xyz.blueskyweb.app", "Stellar Settings" to com.mediaviewer.ui.compat.appPackageName(context)).forEach { (label, pkg) ->
                            Box(
                                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .border(1.dp, headerColorFor(tint).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                    .clickable { tap(); openAppLinkSettings(context, pkg) },
                                contentAlignment = Alignment.Center
                            ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                        }
                    }
                }
            }
            val m = Modifier.fillMaxWidth(0.9f).widthIn(max = 420.dp).clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {}
            if (liquidGlass) {
                LiquidGlassSurface(m, shape = shape, tint = tint) { Content() }
            } else {
                Box(m.clip(shape).background(OffBlack).border(1.dp, tint.copy(alpha = 0.5f), shape)) { Content() }
            }
        }
    }
}
