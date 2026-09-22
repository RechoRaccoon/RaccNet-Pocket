package com.mediaviewer.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.EmojiEntry
import com.mediaviewer.util.EmojiFolder
import com.mediaviewer.util.EmojiStore
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.launch

/** Height of the panel while a tab is being renamed. The keyboard is up then,
 *  so the panel shrinks to just its tab row and rides on top of the keyboard. */
internal val EmojiPanelCompactHeight = 60.dp

/**
 * Textshot mode's emoji menu. It takes the keyboard's place (the composer
 * sizes it to exactly the keyboard's height), Discord-style:
 *
 *  - top row: the "All" tab, one tab per emoji folder, then a (+) bubble that
 *    always sits after the last folder and makes a new folder; pinned at the
 *    far right of the row is the Import bubble.
 *  - below: a grid of the selected tab's emoji, 9 per row. Tapping one calls
 *    [onPickEmoji].
 *
 * "All" holds every imported emoji. Importing while a folder tab is selected
 * adds the emoji to that folder as well (and, always, to "All").
 * Making a new folder immediately puts its tab into rename mode; tapping a
 * folder tab that's already selected renames it.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun EmojiPanel(
    store: EmojiStore,
    height: Dp,
    compact: Boolean,
    liquidGlass: Boolean,
    tint: Color,
    onPickEmoji: (EmojiEntry) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tap = rememberHapticTap()
    val keyboard = LocalSoftwareKeyboardController.current

    val folders = store.state.index.folders
    // null = the "All" tab.
    var selectedFolderId by remember { mutableStateOf<Int?>(null) }
    if (selectedFolderId != null && folders.none { it.id == selectedFolderId }) selectedFolderId = null

    var editingId by remember { mutableStateOf<Int?>(null) }
    var editValue by remember { mutableStateOf(TextFieldValue("")) }
    var importMenuOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    // Press-and-hold delete, for both a single emoji and a whole folder tab
    // (long-pressing "All" instead deletes whichever emoji aren't filed into
    // any folder, since "All" isn't a real, deletable folder of its own).
    var pendingDeleteEmoji by remember { mutableStateOf<EmojiEntry?>(null) }
    var pendingDeleteFolder by remember { mutableStateOf<EmojiFolder?>(null) }
    var pendingDeleteOrphans by remember { mutableStateOf(false) }

    fun startEditing(id: Int, current: String) {
        editingId = id
        editValue = TextFieldValue(current, TextRange(0, current.length))
        onEditingChange(true)
    }

    fun finishEditing() {
        val id = editingId ?: return
        editingId = null
        store.renameFolder(id, editValue.text)
        onEditingChange(false)
        keyboard?.hide()
    }

    fun runImport(block: suspend () -> Int) {
        importing = true
        scope.launch {
            val n = runCatching { block() }.getOrDefault(0)
            importing = false
            Toast.makeText(
                context, if (n > 0) "Imported $n emoji" else "No emoji imported", Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Import folder: every image inside the picked folder. Import images: one
    // or several individual pictures. Either way they land in the selected tab.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runImport { store.importTree(uri, selectedFolderId) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runImport { store.importImages(uris, selectedFolderId) }
    }

    val panelBg = Color(red = tint.red * 0.16f, green = tint.green * 0.16f, blue = tint.blue * 0.16f, alpha = 1f)
    val tabShape = RoundedCornerShape(16.dp)

    Column(
        modifier
            .fillMaxWidth()
            .height(if (compact) EmojiPanelCompactHeight else height)
            .background(panelBg)
            // Swallow taps so they never fall through to the composer behind.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .then(if (compact) Modifier else Modifier.navigationBarsPadding())
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))

        // ── Tab row: All · folders · (+)      Import ───────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelPill(
                    liquidGlass = liquidGlass, tint = tint, shape = tabShape,
                    onClick = {
                        finishEditing()
                        selectedFolderId = null
                    },
                    onLongClick = {
                        if (store.orphanEmojiCount() > 0) pendingDeleteOrphans = true
                        else Toast.makeText(context, "No untagged emoji to delete", Toast.LENGTH_SHORT).show()
                    }
                ) { PillLabel("All", selected = selectedFolderId == null) }

                folders.forEach { folder ->
                    val isSelected = folder.id == selectedFolderId
                    if (editingId == folder.id) {
                        PanelPill(liquidGlass = liquidGlass, tint = tint, shape = tabShape, onClick = null) {
                            EditableTabName(
                                value = editValue,
                                onValueChange = { editValue = it },
                                onDone = { finishEditing() }
                            )
                        }
                    } else {
                        PanelPill(
                            liquidGlass = liquidGlass, tint = tint, shape = tabShape,
                            onClick = {
                                if (isSelected) {
                                    // Tapping the already-selected folder renames it.
                                    startEditing(folder.id, folder.name)
                                } else {
                                    finishEditing()
                                    selectedFolderId = folder.id
                                }
                            },
                            onLongClick = { pendingDeleteFolder = folder }
                        ) { PillLabel(folder.name, selected = isSelected) }
                    }
                }

                // (+) — always the last thing in the row, after every folder.
                PanelPill(
                    liquidGlass = liquidGlass, tint = tint, shape = CircleShape,
                    modifier = Modifier.width(32.dp),
                    onClick = {
                        finishEditing()
                        val id = store.createFolder()
                        selectedFolderId = id
                        startEditing(id, EmojiStore.DEFAULT_FOLDER_NAME)
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New emoji folder", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.width(8.dp))

            Box {
                PanelPill(
                    liquidGlass = liquidGlass, tint = tint, shape = tabShape,
                    onClick = { if (!importing) importMenuOpen = true }
                ) { PillLabel(if (importing) "Importing…" else "Import", selected = !importing) }

                if (importMenuOpen) {
                    Popup(
                        popupPositionProvider = ImportMenuPosition,
                        onDismissRequest = { importMenuOpen = false },
                        properties = PopupProperties(focusable = true)
                    ) {
                        Column(Modifier.width(168.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ImportMenuItem("Import folder") {
                                importMenuOpen = false
                                folderPicker.launch(null)
                            }
                            ImportMenuItem("Import images") {
                                importMenuOpen = false
                                imagePicker.launch(arrayOf("image/*"))
                            }
                        }
                    }
                }
            }
        }

        // ── Emoji grid (hidden while renaming a tab, when only the row shows) ─
        if (!compact) {
            val emoji = store.emojiFor(selectedFolderId)
            if (emoji.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (selectedFolderId == null) "No emoji yet. Tap Import to add some."
                        else "This folder is empty. Tap Import to add emoji to it.",
                        color = DimGray, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(9),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    items(emoji, key = { it.id }) { e ->
                        Box(
                            Modifier.aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(6.dp))
                                .combinedClickable(
                                    onClick = { tap(); onPickEmoji(e) },
                                    onLongClick = { tap(); pendingDeleteEmoji = e }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = store.fileFor(e), contentDescription = e.name,
                                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(1.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Delete confirmations ────────────────────────────────────────────
    pendingDeleteEmoji?.let { e ->
        // Named precisely, not "every folder" — deleting only ever affects
        // All (every emoji is implicitly part of it) plus whichever folder(s)
        // this specific emoji is actually filed into, never folders it isn't in.
        val ownerFolders = store.foldersContaining(e.id)
        val message = when (ownerFolders.size) {
            0 -> "This removes \":${e.name}:\" from All. It isn't filed into any folder."
            1 -> "This removes \":${e.name}:\" from All and from \"${ownerFolders[0].name}\"."
            else -> "This removes \":${e.name}:\" from All and from ${ownerFolders.size} folders it's in: ${ownerFolders.joinToString { it.name }}."
        }
        EmojiDeleteConfirmDialog(
            title = "Delete Emoji?",
            message = message,
            liquidGlass = liquidGlass, tint = tint,
            onConfirm = { store.deleteEmoji(e.id); pendingDeleteEmoji = null },
            onDismiss = { pendingDeleteEmoji = null }
        )
    }
    pendingDeleteFolder?.let { f ->
        EmojiDeleteConfirmDialog(
            title = "Delete Folder?",
            message = "\"${f.name}\" will be removed. Its emoji stay in All and in any other folder they're also in.",
            liquidGlass = liquidGlass, tint = tint,
            onConfirm = {
                store.deleteFolder(f.id)
                if (selectedFolderId == f.id) selectedFolderId = null
                pendingDeleteFolder = null
            },
            onDismiss = { pendingDeleteFolder = null }
        )
    }
    if (pendingDeleteOrphans) {
        val n = store.orphanEmojiCount()
        EmojiDeleteConfirmDialog(
            title = "Delete Emoji?",
            message = if (n == 1) "This deletes the 1 emoji that isn't filed into any folder."
                      else "This deletes the $n emoji that aren't filed into any folder.",
            liquidGlass = liquidGlass, tint = tint,
            onConfirm = { store.deleteOrphanEmoji(); pendingDeleteOrphans = false },
            onDismiss = { pendingDeleteOrphans = false }
        )
    }
}

/** Same plain warning-dialog scaffold as [ExportDatasetNameDialog] (see
 *  SettingsSheet.kt) — a title, a line of body text, Cancel/Delete — just
 *  without the text field, and with Delete styled as a destructive action. */
@Composable
private fun EmojiDeleteConfirmDialog(
    title: String, message: String, liquidGlass: Boolean, tint: Color,
    onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true, usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val shape = RoundedCornerShape(20.dp)
            @Composable
            fun DialogContent() {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        title, color = Color.White, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(message, color = DimGray, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            modifier = Modifier.weight(1f).height(46.dp)
                        ) { Text("Cancel") }
                        Button(
                            onClick = onConfirm,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            modifier = Modifier.weight(1f).height(46.dp)
                        ) { Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(Modifier.fillMaxWidth(0.86f), shape = shape, tint = tint) { DialogContent() }
            } else {
                Box(Modifier.fillMaxWidth(0.86f).clip(shape).background(Color(0xFF1E1E22))) { DialogContent() }
            }
        }
    }
}

/** A glass pill (or circle) in the panel's tab row. [onClick] null = not tappable. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelPill(
    liquidGlass: Boolean, tint: Color, shape: Shape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val tap = rememberHapticTap()
    val clickMod = modifier.height(32.dp).clip(shape)
        .then(
            if (onClick != null) Modifier.combinedClickable(
                onClick = { tap(); onClick() },
                onLongClick = onLongClick?.let { { tap(); it() } }
            ) else Modifier
        )
    if (liquidGlass) {
        LiquidGlassSurface(clickMod, shape = shape, tint = tint, contentAlignment = Alignment.Center, content = content)
    } else {
        Box(clickMod.background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center, content = content)
    }
}

/** Same dim-unless-selected treatment as the composer's Blog/Textshot toggles. */
@Composable
private fun PillLabel(text: String, selected: Boolean) {
    Text(
        text, color = Color.White.copy(alpha = if (selected) 1f else 0.45f),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.padding(horizontal = 14.dp)
    )
}

/** The tab's name as a live text field: focused right away, everything
 *  selected so typing replaces it; Done (or tapping elsewhere) commits. */
@Composable
private fun EditableTabName(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { if (it.text.length <= 24) onValueChange(it) },
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Words),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .widthIn(min = 56.dp)
            .width(IntrinsicSize.Max)
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) onDone()
            }
    )
}

@Composable
private fun ImportMenuItem(label: String, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(Color(0xFF1E1E22))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .clickable { tap(); onClick() },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

/** Opens the Import menu just under its button, right-aligned to it. */
private object ImportMenuPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize
    ): IntOffset = IntOffset(
        x = (anchorBounds.right - popupContentSize.width).coerceAtLeast(0),
        y = anchorBounds.bottom + 8
    )
}
