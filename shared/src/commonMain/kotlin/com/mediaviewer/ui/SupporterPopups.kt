package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.BskyList
import com.mediaviewer.model.MediaItem
import com.mediaviewer.platform.LocalPlatform
import com.mediaviewer.ui.compat.ActivityResultContracts
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.ui.compat.PickVisualMediaRequest
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.util.BookmarkFolder
import com.mediaviewer.util.DateText
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.LocalFeed
import com.mediaviewer.util.LocalFeedSource
import com.mediaviewer.util.PostEditVersion
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun isoToText(iso: String): String {
    if (iso.isBlank()) return ""
    val ms = runCatching { com.mediaviewer.platform.parseIsoInstantMillis(iso) }.getOrNull()
        ?: runCatching { com.mediaviewer.platform.parseIsoOffsetDateTimeMillis(iso) }.getOrNull()
        ?: return ""
    return DateText.format(ms, "MMM d, yyyy · h:mm a")
}

// ── Edited posts: version history ───────────────────────────────────────

/** Tapping a post's "Edited" status: the current version on top, then
 *  every earlier version, newest first. */
@Composable
fun EditHistoryPopup(item: MediaItem, liquidGlass: Boolean, tint: Color, onClose: () -> Unit) {
    val versions: List<PostEditVersion> = remember(item) { (item.editHistory ?: emptyList()).reversed() }
    LocalPopup(title = "Edit History", liquidGlass = liquidGlass, tint = tint, onClose = onClose) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VersionCard("Current version", isoToText(item.editedAt.orEmpty()).let { if (it.isBlank()) "" else "Edited $it" }, item.text, tint, current = true)
            versions.forEachIndexed { i, v ->
                val original = i == versions.lastIndex
                VersionCard(
                    if (original) "Original" else "Earlier version",
                    isoToText(v.at) + if (v.images > 0) " · ${v.images} image${if (v.images == 1) "" else "s"}" else "",
                    v.text, tint, current = false
                )
            }
            if (versions.isEmpty()) Text(
                "This post was edited, but its earlier versions aren't available.",
                color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun VersionCard(label: String, sub: String, text: String, tint: Color, current: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color.Black.copy(alpha = if (current) 0.34f else 0.24f))
            .border(1.dp, lerp(tint, Color.White, 0.25f).copy(alpha = if (current) 0.8f else 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (sub.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text.ifBlank { "(no text)" }, color = Color.White.copy(alpha = if (current) 0.96f else 0.82f),
            fontSize = 14.sp, lineHeight = 19.sp
        )
    }
}

// ── Bookmark folders ────────────────────────────────────────────────────

/** A folder's picture: its cover, or a folder icon on the app color. */
@Composable
internal fun FolderCover(folder: BookmarkFolder?, tint: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier.clip(shape).background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.15f), lerp(tint, Color.Black, 0.45f)))),
        contentAlignment = Alignment.Center
    ) {
        val cover = folder?.cover
        if (!cover.isNullOrBlank()) {
            AsyncImage(
                model = if (cover.startsWith("http")) cover else LocalPlatform.parseUri(cover),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape)
            )
        } else Icon(Icons.Default.Folder, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
    }
}

/**
 * "Add To", for a saved post: your bookmark folders (none to begin with),
 * each tappable to put the post in or take it out, and a row to make a new
 * one with a name and a cover. Folders live only on this device.
 */
@Composable
fun BookmarkFolderPopup(item: MediaItem, liquidGlass: Boolean, tint: Color, onClose: () -> Unit) {
    val context = LocalContext.current
    val tap = rememberHapticTap()
    val scope = rememberCoroutineScope()
    val folders = LocalData.bookmarkFolders
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armed) { if (armed != null) { kotlinx.coroutines.delay(3000); armed = null } }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            cover = withContext(Dispatchers.IO) { LocalPlatform.importMedia(context, uri, "covers")?.toString() }
        }
    }
    val thumb = item.thumbUrl.ifBlank { item.mediaUrl }

    LocalPopup(title = "Add To", liquidGlass = liquidGlass, tint = tint, onClose = onClose, subtitle = "Folders") {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (folders.isEmpty() && !creating) Text(
                "No folders yet. Make one to organize your saved posts — folders stay on this device.",
                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
            )
            folders.forEach { folder ->
                val inFolder = item.postUri in folder.posts
                val shape = RoundedCornerShape(16.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape)
                        .background(Color.Black.copy(alpha = 0.26f))
                        .border(1.dp, lerp(tint, Color.White, 0.25f).copy(alpha = if (inFolder) 0.9f else 0.3f), shape)
                        .clickable { tap(); LocalData.toggleInBookmarkFolder(folder.id, item.postUri, thumb) }
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FolderCover(folder, tint, Modifier.size(40.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(folder.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${folder.posts.size} post${if (folder.posts.size == 1) "" else "s"}",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp
                        )
                    }
                    // Delete the folder (two taps). Its posts stay saved.
                    val confirming = armed == folder.id
                    Box(
                        Modifier.clip(RoundedCornerShape(11.dp))
                            .background(if (confirming) Color(0xFFE0245E).copy(alpha = 0.3f) else Color.Transparent)
                            .clickable {
                                tap()
                                if (confirming) {
                                    armed = null
                                    folder.cover?.let { LocalPlatform.deleteMedia(context, it) }
                                    LocalData.deleteBookmarkFolder(folder.id)
                                } else armed = folder.id
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        if (confirming) Text("Delete?", color = Color(0xFFFF6B8A), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        else Icon(Icons.Default.Close, contentDescription = "Delete folder", tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier.size(26.dp).clip(CircleShape)
                            .background(if (inFolder) lerp(tint, Color.White, 0.2f) else Color.White.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (inFolder) Icons.Default.Check else Icons.Default.Add, contentDescription = if (inFolder) "In folder" else "Add",
                            tint = Color.White, modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (creating) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The new folder's cover: tap to pick a picture.
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(11.dp))
                        .background(Color.Black.copy(alpha = 0.3f))
                        .border(1.dp, lerp(tint, Color.White, 0.25f).copy(alpha = 0.6f), RoundedCornerShape(11.dp))
                        .clickable { tap(); coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center
                ) {
                    val c = cover
                    if (c != null) AsyncImage(
                        model = LocalPlatform.parseUri(c), contentDescription = "Cover", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    ) else Icon(Icons.Default.Image, contentDescription = "Pick a cover", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                LocalTextField(name, { name = it.take(60) }, "Folder name", tint, Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalPillButton("Cancel", liquidGlass, tint, { creating = false; name = ""; cover = null }, Modifier.weight(1f))
                LocalPillButton(
                    "Create", liquidGlass, tint,
                    {
                        val made = LocalData.createBookmarkFolder(name, cover)
                        LocalData.toggleInBookmarkFolder(made.id, item.postUri, thumb)
                        creating = false; name = ""; cover = null
                    },
                    Modifier.weight(1f), enabled = name.isNotBlank()
                )
            }
        } else {
            LocalPillButton("New Folder", liquidGlass, tint, { creating = true }, Modifier.fillMaxWidth())
        }
    }
}

// ── Profile notes ───────────────────────────────────────────────────────

/** A private note about a profile (More → the note icon). Kept only on
 *  this device; shown above the bio on that profile. */
@Composable
fun ProfileNotePopup(author: AuthorInfo, liquidGlass: Boolean, tint: Color, onClose: () -> Unit) {
    val existing = remember(author.did) { LocalData.profileNote(author.did) }
    var text by remember(author.did) { mutableStateOf(existing) }
    val label = when {
        existing.isBlank() -> "Add"
        text.isBlank() -> "Remove Note"
        else -> "Save"
    }
    LocalPopup(title = "Profile Note", liquidGlass = liquidGlass, tint = tint, onClose = onClose) {
        Text(
            "Only you can see this. It stays on this device.",
            color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            LocalTextField(
                text, { text = it }, "Write a note about @${author.handle}…", tint,
                singleLine = false, minHeight = 130.dp
            )
        }
        Spacer(Modifier.height(10.dp))
        LocalPillButton(
            label, liquidGlass, tint,
            { LocalData.setProfileNote(author.did, text); onClose() },
            Modifier.fillMaxWidth(),
            enabled = existing.isNotBlank() || text.isNotBlank(),
            destructive = existing.isNotBlank() && text.isBlank()
        )
    }
}

// ── Feed Builder ────────────────────────────────────────────────────────

/**
 * Hub → the "+" after the feeds: build a feed out of your lists, any list
 * by link, specific accounts and/or hashtags, and choose what it shows
 * (text posts, images, horizontal and vertical videos). The feed exists
 * only on this device; its posts are read from Bluesky's AppView.
 */
@Composable
fun FeedBuilderPopup(
    initial: LocalFeed,
    myLists: List<BskyList>,
    liquidGlass: Boolean,
    tint: Color,
    onLoadLists: () -> Unit,
    onResolveList: (String, (LocalFeedSource?, String?) -> Unit) -> Unit,
    onResolveAccount: (String, (LocalFeedSource?, String?) -> Unit) -> Unit,
    onSaved: (LocalFeed) -> Unit,
    onDeleted: (LocalFeed) -> Unit,
    onClose: () -> Unit
) {
    val tap = rememberHapticTap()
    val editing = initial.id.isNotBlank()
    var name by remember { mutableStateOf(initial.name) }
    var sources by remember { mutableStateOf(initial.sources) }
    var textPosts by remember { mutableStateOf(initial.textPosts) }
    var images by remember { mutableStateOf(initial.images) }
    var hVideos by remember { mutableStateOf(initial.horizontalVideos) }
    var vVideos by remember { mutableStateOf(initial.verticalVideos) }
    // Which "add" panel is open: lists, link, account or hashtag.
    var adding by remember { mutableStateOf("lists") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onLoadLists() }
    LaunchedEffect(confirmDelete) { if (confirmDelete) { kotlinx.coroutines.delay(3000); confirmDelete = false } }

    fun add(source: LocalFeedSource) {
        if (sources.none { it.kind == source.kind && it.value == source.value }) sources = sources + source
    }
    fun submitInput() {
        val t = input.trim()
        if (t.isEmpty() || busy) return
        error = null
        when (adding) {
            "hashtag" -> {
                val tag = t.removePrefix("#").filter { it.isLetterOrDigit() || it == '_' }
                if (tag.isNotEmpty()) { add(LocalFeedSource("hashtag", tag, "#$tag")); input = "" }
            }
            "link" -> { busy = true; onResolveList(t) { src, err -> busy = false; if (src != null) { add(src); input = "" } else error = err } }
            "account" -> { busy = true; onResolveAccount(t) { src, err -> busy = false; if (src != null) { add(src); input = "" } else error = err } }
        }
    }

    LocalPopup(title = "Feed Builder", liquidGlass = liquidGlass, tint = tint, onClose = onClose, maxHeight = 620.dp) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LocalTextField(name, { name = it.take(40) }, "Feed name", tint, capitalization = KeyboardCapitalization.Words)

            BuilderLabel("In this feed")
            if (sources.isEmpty()) Text(
                "Nothing yet — add lists, accounts or hashtags below.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
            )
            sources.forEach { src ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.26f))
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (src.kind) { "list" -> "List"; "account" -> "Account"; else -> "Hashtag" },
                        color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, modifier = Modifier.width(58.dp)
                    )
                    Text(
                        src.label.ifBlank { src.value }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).clickable { tap(); sources = sources - src },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp)) }
                }
            }

            BuilderLabel("Add")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LocalChip("My Lists", adding == "lists", tint, { adding = "lists"; error = null })
                LocalChip("List Link", adding == "link", tint, { adding = "link"; input = ""; error = null })
                LocalChip("Account", adding == "account", tint, { adding = "account"; input = ""; error = null })
                LocalChip("Hashtag", adding == "hashtag", tint, { adding = "hashtag"; input = ""; error = null })
            }
            if (adding == "lists") {
                if (myLists.isEmpty()) Text("You don't have any lists yet.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                myLists.forEach { list ->
                    val added = sources.any { it.kind == "list" && it.value == list.uri }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (added) lerp(tint, Color.White, 0.1f).copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.2f))
                            .clickable {
                                tap()
                                sources = if (added) sources.filterNot { it.kind == "list" && it.value == list.uri }
                                else sources + LocalFeedSource("list", list.uri, list.name)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(list.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(
                            if (added) Icons.Default.Check else Icons.Default.Add, contentDescription = null,
                            tint = Color.White.copy(alpha = if (added) 1f else 0.6f), modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LocalTextField(
                        input, { input = it; error = null },
                        when (adding) {
                            "link" -> "https://bsky.app/profile/…/lists/…"
                            "account" -> "handle.bsky.social"
                            else -> "hashtag"
                        },
                        tint, Modifier.weight(1f), capitalization = KeyboardCapitalization.None
                    )
                    Spacer(Modifier.width(8.dp))
                    LocalPillButton(if (busy) "…" else "Add", liquidGlass, tint, { submitInput() }, enabled = input.isNotBlank() && !busy)
                }
                error?.let { Text(it, color = Color(0xFFFF6B8A), fontSize = 12.sp) }
            }

            BuilderLabel("Show")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LocalChip("Text Posts", textPosts, tint, { textPosts = !textPosts })
                LocalChip("Images", images, tint, { images = !images })
                LocalChip("Horizontal Videos", hVideos, tint, { hVideos = !hVideos })
                LocalChip("Vertical Videos", vVideos, tint, { vVideos = !vVideos })
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) LocalPillButton(
                if (confirmDelete) "Delete?" else "Delete", liquidGlass, tint,
                { if (confirmDelete) { LocalData.deleteLocalFeed(initial.id); onDeleted(initial); onClose() } else confirmDelete = true },
                Modifier.weight(1f), destructive = true
            )
            LocalPillButton(
                if (editing) "Save Feed" else "Create Feed", liquidGlass, tint,
                {
                    val saved = LocalData.saveLocalFeed(
                        initial.copy(
                            name = name.ifBlank { "My Feed" }, sources = sources,
                            textPosts = textPosts, images = images, horizontalVideos = hVideos, verticalVideos = vVideos
                        )
                    )
                    onSaved(saved)
                    onClose()
                },
                Modifier.weight(if (editing) 1.4f else 1f),
                enabled = sources.isNotEmpty() && (textPosts || images || hVideos || vVideos)
            )
        }
    }
}

@Composable
private fun BuilderLabel(text: String) {
    Text(
        text, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}
