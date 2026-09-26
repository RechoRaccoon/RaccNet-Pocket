package com.mediaviewer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
internal enum class SettingsTab { SETTINGS, CREDITS }

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
    val onDownloadAllE621Saved: () -> Unit = {}
)

// ── Shared building blocks ──────────────────────────────────────────────────

private val BubbleShape = RoundedCornerShape(14.dp)
private val DangerRed = Color(0xFFEF5350)

/** The section titles' color: the same reflected profile color every rim on
 *  this page uses, lifted to a readable brightness (a dominant color sampled
 *  from an avatar is often too dark to read as text on the dark background). */
private fun headerColorFor(tint: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(tint.toArgb(), hsv)
    hsv[2] = hsv[2].coerceAtLeast(0.85f)
    return Color(android.graphics.Color.HSVToColor(hsv))
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
                checkedThumbColor = Color.White, checkedTrackColor = VoteGreen,
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
                        .background(VoteGreen)
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
    onPickFontFile: (android.net.Uri) -> Unit,
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
    importedDatasets: List<com.mediaviewer.tagging.TagDatabase.DatasetInfo>,
    onExportDataset: (String, android.net.Uri) -> Unit,
    onImportDataset: (android.net.Uri) -> Unit,
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

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── UI Customization ────────────────────────────────────────────
        SectionHeader("UI Customization", tint, first = true)

        ToggleBubble("Reduced Animations", reducedAnimations, onToggleReducedAnimations, liquidGlass, tint, backdrop)
        ToggleBubble("Rounded Grid Tiles", squareGridRounded, onToggleSquareGridRounded, liquidGlass, tint, backdrop)
        // Skips the pixel loading screen/transition everywhere: pages open
        // instantly and fill in as their data arrives.
        ToggleBubble(
            "Disable Loading Screens", !com.mediaviewer.util.UiToggles.loadingScreens,
            { com.mediaviewer.util.UiToggles.updateLoadingScreens(!it) }, liquidGlass, tint, backdrop
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

        // App Font: the label stays "App Font"; an active custom font's name
        // gets its own row underneath.
        val fontPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onPickFontFile(uri)
        }
        SettingsBubble(liquidGlass, tint, backdrop) {
            BubbleRow {
                RowLabel("App Font", Modifier.weight(1f))
                if (customFontName != null) {
                    PillButton("Reset", onResetFont, color = DangerRed)
                    Spacer(Modifier.width(6.dp))
                }
                PillButton("Choose File", { fontPickerLauncher.launch("*/*") })
            }
            if (customFontName != null) {
                BubbleDivider()
                BubbleRow {
                    Text("Font: $customFontName", color = DimGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // ── App Functionality ───────────────────────────────────────────
        SectionHeader("App Functionality", tint)

        ToggleBubble("Hide Text Only Posts", hideTextOnlyPosts, onToggleHideTextOnlyPosts, liquidGlass, tint, backdrop)
        ToggleBubble("I Hate Fun (Blur NSFW Content)", hateFunBlurNsfw, onToggleHateFunBlurNsfw, liquidGlass, tint, backdrop)
        // Frame rate (top right) + AI tag-on-like queue (top left) beside the
        // camera cutout — see DebugOverlay.
        ToggleBubble(
            "Debug Overlay", com.mediaviewer.util.UiToggles.debugOverlay,
            { com.mediaviewer.util.UiToggles.updateDebugOverlay(it) }, liquidGlass, tint, backdrop
        )

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
                            color = VoteGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
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
            }
        }

        if (bskyLoggedIn) {
            ToggleBubble("Merge Lists and Starter Packs", combineListsAndPacks, onToggleCombineListsPacks, liquidGlass, tint, backdrop)
            ToggleBubble("Show \"Add To\" After Following", autoAddToOnFollow, onToggleAutoAddToOnFollow, liquidGlass, tint, backdrop)
        }

        // ── Integrations ────────────────────────────────────────────────
        SectionHeader("Integrations", tint)

        AtProtocolAccountsBubble(
            bskyLoggedIn = bskyLoggedIn, bskyHandle = bskyHandle, isLoading = isLoading,
            onLoginBluesky = onLoginBluesky, onLogoutBluesky = onLogoutBluesky,
            extras = extras, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
        )

        E621AccountBubble(
            e621LoggedIn = e621LoggedIn, e621Username = e621Username,
            onLoginE621 = onLoginE621, onLogoutE621 = onLogoutE621,
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
        )

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
                BubbleRow {
                    RowLabel("Export Dataset", Modifier.weight(1f))
                    PillButton("Export", { pendingExportName = ""; showExportNameDialog = true })
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

        // ── Data ────────────────────────────────────────────────────────
        if (anyLoggedIn) {
            SectionHeader("Data", tint)

            if (bskyLoggedIn) {
                val prog = downloadProgress.takeIf { !extras.downloadIsE621 }
                val running = prog?.isRunning == true
                ActionBubble(
                    label = "Download AT Protocol Media when Liked",
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
                    label = "Download e621 Media when Saved",
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

        Spacer(Modifier.height(16.dp))
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

// ── Credits page ────────────────────────────────────────────────────────────

/** The Settings/Credits switch's second page: plain centered headers over
 *  left-aligned body text. */
@Composable
internal fun CreditsPageContent() {
    val recho = Color(0xFF00FF07)
    val rose = Color(0xFFE0245E)
    val deepBlue = Color(0xFF2A4CE0)

    @Composable
    fun Header(text: String) {
        Text(
            text, color = Color.White, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
    }
    @Composable
    fun Body(text: androidx.compose.ui.text.AnnotatedString) {
        Text(
            text, color = Color.White, fontSize = 16.sp, lineHeight = 23.sp,
            textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Column {
            Header("Front End Development")
            Body(buildAnnotatedString {
                append("Created by ")
                withStyle(SpanStyle(color = recho)) { append("Recho Raccoon") }
                append(", coded with Claude Sonnet, Claude Opus 5.5 and Muse by Meta")
            })
        }
        Column {
            Header("Special Thanks")
            Body(buildAnnotatedString {
                withStyle(SpanStyle(color = rose)) { append("Rose (SomeDudeGT)") }
                append(" and ")
                withStyle(SpanStyle(color = deepBlue)) { append("Popper700") }
                append(" - Helped push new builds to the GitHub Repo.")
            })
        }
        Column {
            Header("AT Protocol Integrations")
            Body(buildAnnotatedString {
                append("Bluesky - Accounts, Posts, etc.\n")
                append("Leaflet - Long-Form Blogs.\n")
                append("Popfeed - Title Reviews and Backlog.\n")
                append("Rocksky - Music Listening History.")
            })
        }
        Column {
            Header("Other Integrations")
            Body(buildAnnotatedString { append("e621 - Content Browsing.") })
        }
        Column {
            Header("On-Device AI Models")
            Body(buildAnnotatedString {
                append("AI Tagging - Z3D-E621-Convnext (Zack3D), via ONNX Runtime.\n")
                append("VRM Tracking - MediaPipe Face, Hand & Pose Landmarkers (Google).\n")
                append("Translation - ML Kit Translate & Language ID (Google).")
            })
        }
        Spacer(Modifier.height(8.dp))
    }
}
