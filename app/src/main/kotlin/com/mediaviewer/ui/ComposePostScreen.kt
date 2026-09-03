package com.mediaviewer.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.ui.theme.RepostGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Bluesky post composer — opened from the Hub's "+" -> "Post" bubble
 * (see HubUploadBubble in SettingsSheet.kt). Reference for the underlying
 * upload mechanics: RaccNet Legacy's own composer (dev/raccnet_page.html /
 * raccnet_server.py), which this reimplements for a touch/mobile layout
 * rather than Legacy's desktop one, and updates for Bluesky's 2026 limits
 * (up to 10 images per post, up to ~4K image resolution, and 10-minute/
 * 300MB video — all still the *same* app.bsky.embed.images / app.bsky.
 * embed.video lexicons Legacy already used, just with higher caps; nothing
 * here needed a new lexicon). RaccNet Pocket's existing video player has no
 * hardcoded duration/size ceiling either, so longer videos should already
 * play back fine once posted — this composer is the only piece that needed
 * new work.
 *
 * NETWORKING STATUS: this file is the composer UI + local state machine
 * only. [onSubmit] hands a fully-formed [ComposePostDraft] up to the
 * ViewModel (see MainViewModel.submitComposePost), which is currently a
 * stub. The remaining upload plumbing — still to be wired up next:
 *   - Images: BlueskyApi.uploadBlob (com.atproto.repo.uploadBlob) once per
 *     image, then a createRecord with an app.bsky.embed.images embed.
 *   - Video: upload to https://video.bsky.app xrpc/app.bsky.video.
 *     uploadVideo (via a getServiceAuth-minted token), poll app.bsky.video.
 *     getJobStatus until it returns a blob, then createRecord with an
 *     app.bsky.embed.video embed. Bluesky's own API has no separate
 *     "thumbnail" field for video — same as Legacy found — so a custom
 *     thumbnail has to be spliced into the video itself as its first frame
 *     before upload. Legacy does this server-side with ffmpeg (concat a
 *     ~1-frame still of the thumbnail image with the real video — see
 *     _process_video in raccnet_server.py). On Android the equivalent,
 *     ffmpeg-free approach is androidx.media3.transformer.Transformer/
 *     EditedMediaItemSequence, which can concatenate an image-as-video clip
 *     with the real video clip entirely on-device (media3-transformer is
 *     not yet a dependency of this app — media3-exoplayer/-ui already are).
 *   - Thread: one createRecord per post, each replying to the previous as
 *     both `parent` and the *first* post's ref as `root` (a standard
 *     self-thread), in order.
 *   - Textshot: render the composed text to a Bitmap (this file already
 *     builds the exact same layout for the live preview — see
 *     [TextshotPreview]), upload it as a single image blob, and post it as
 *     a normal one-image post.
 */

private const val POST_CHAR_LIMIT = 300
private const val MAX_IMAGES = 10

enum class ComposeMode { SINGLE, THREAD, TEXTSHOT, VIDEO }

/** One post's worth of content inside a [ComposeMode.THREAD] thread. */
data class ThreadPostDraft(
    val text: String,
    val images: List<Uri> = emptyList(),
    val video: Uri? = null
)

/** Everything the composer collected, handed to the caller on "Post". */
data class ComposePostDraft(
    val mode: ComposeMode,
    /** SINGLE: exactly one entry. THREAD: two or more, in posting order. */
    val posts: List<ThreadPostDraft> = emptyList(),
    val videoUri: Uri? = null,
    val videoThumbnailUri: Uri? = null,
    val videoTitle: String = "",
    val videoDescription: String = "",
    val textshotText: String = ""
)

@Composable
fun ComposePostScreen(
    selfProfile: AuthorInfo?,
    liquidGlass: Boolean,
    dominantColor: Color = NeutralGlassTint,
    submitting: Boolean = false,
    onClose: () -> Unit,
    onSubmit: (ComposePostDraft) -> Unit
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current

    // ── Core state ───────────────────────────────────────────────────────
    var mode by remember { mutableStateOf(ComposeMode.SINGLE) }
    var singleText by remember { mutableStateOf(TextFieldValue("")) }
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoThumbUri by remember { mutableStateOf<Uri?>(null) }
    var videoAspect by remember { mutableStateOf(16f / 9f) }
    var videoTitle by remember { mutableStateOf(TextFieldValue("")) }
    var videoDescription by remember { mutableStateOf(TextFieldValue("")) }
    var threadPosts by remember { mutableStateOf(listOf(TextFieldValue(""))) }
    var activeThreadIndex by remember { mutableStateOf(0) }

    // Once the plain single-text post overflows 300 chars, the status
    // bubble becomes a Thread/Textshot toggle (see StatusBubble below). It
    // stays available as a toggle for as long as the composer is in either
    // of those two modes, so the person can flip back and forth freely.
    val overflowChoice = mode == ComposeMode.SINGLE && singleText.text.length > POST_CHAR_LIMIT

    fun switchToThread() {
        val seed = if (mode == ComposeMode.TEXTSHOT) singleText.text
            else threadPosts.joinToString(" ") { it.text }.ifBlank { singleText.text }
        threadPosts = splitIntoThread(seed).map { TextFieldValue(it) }
        activeThreadIndex = 0
        mode = ComposeMode.THREAD
    }

    fun switchToTextshot() {
        val seed = if (threadPosts.size > 1) threadPosts.joinToString(" ") { it.text } else singleText.text
        singleText = TextFieldValue(seed)
        mode = ComposeMode.TEXTSHOT
    }

    // ── Media pickers (Android Photo Picker — no storage permission
    // needed). One button picks either images or a single video, per spec:
    // "only one video, or up to 10 images, but not both". ────────────────
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val videoPick = uris.firstOrNull { isVideoUri(context, it) }
        if (videoPick != null) {
            videoUri = videoPick
            videoThumbUri = null
            images = emptyList()
            if (mode != ComposeMode.TEXTSHOT) mode = ComposeMode.VIDEO
        } else if (mode != ComposeMode.TEXTSHOT) {
            // NOTE: in THREAD mode this attaches to the whole draft rather
            // than per-post — true per-post media tracking (spec: "attach
            // button adds to whatever post the user is currently typing
            // in") is a follow-up; activeThreadIndex is already tracked
            // and ready for that wiring.
            val room = (MAX_IMAGES - images.size).coerceAtLeast(0)
            images = (images + uris.take(room)).take(MAX_IMAGES)
        }
    }
    val thumbnailPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) videoThumbUri = uri
    }

    LaunchedEffect(videoUri) {
        val uri = videoUri ?: return@LaunchedEffect
        val aspect = withContext(Dispatchers.IO) { probeVideoAspect(context, uri) }
        videoAspect = aspect
    }

    // ── Character budget for the field currently being typed in ────────
    val activeBudget: Pair<Int, Int> = when (mode) { // used -> limit
        ComposeMode.VIDEO -> (videoTitle.text.length + videoDescription.text.length) to POST_CHAR_LIMIT
        ComposeMode.THREAD -> threadPosts.getOrNull(activeThreadIndex)?.text?.length.orZero() to
            (POST_CHAR_LIMIT - threadSuffixLength(threadPosts.size))
        ComposeMode.TEXTSHOT -> singleText.text.length to Int.MAX_VALUE
        ComposeMode.SINGLE -> singleText.text.length to POST_CHAR_LIMIT
    }

    val canPost = when (mode) {
        ComposeMode.VIDEO -> videoUri != null && (videoTitle.text.length + videoDescription.text.length) <= POST_CHAR_LIMIT
        ComposeMode.THREAD -> threadPosts.all { it.text.length <= (POST_CHAR_LIMIT - threadSuffixLength(threadPosts.size)) } &&
            threadPosts.any { it.text.isNotBlank() }
        ComposeMode.TEXTSHOT -> singleText.text.isNotBlank()
        ComposeMode.SINGLE -> singleText.text.isNotBlank() && !overflowChoice && singleText.text.length <= POST_CHAR_LIMIT
    }

    fun handlePost() {
        if (!canPost || submitting) return
        val draft = when (mode) {
            ComposeMode.VIDEO -> ComposePostDraft(
                mode = ComposeMode.VIDEO, videoUri = videoUri, videoThumbnailUri = videoThumbUri,
                videoTitle = videoTitle.text, videoDescription = videoDescription.text
            )
            ComposeMode.THREAD -> ComposePostDraft(
                mode = ComposeMode.THREAD,
                posts = threadPosts.mapIndexed { i, tfv ->
                    val suffix = if (threadPosts.size > 1) " ${i + 1}/${threadPosts.size}" else ""
                    ThreadPostDraft(text = tfv.text + suffix)
                }
            )
            ComposeMode.TEXTSHOT -> ComposePostDraft(mode = ComposeMode.TEXTSHOT, textshotText = singleText.text)
            ComposeMode.SINGLE -> ComposePostDraft(
                mode = ComposeMode.SINGLE,
                posts = listOf(ThreadPostDraft(text = singleText.text, images = images, video = null))
            )
        }
        onSubmit(draft)
    }

    val statusLabel = when {
        mode == ComposeMode.VIDEO -> "Video"
        mode == ComposeMode.THREAD || mode == ComposeMode.TEXTSHOT || overflowChoice -> null // toggle instead
        images.isNotEmpty() -> "Media Post"
        singleText.text.isNotBlank() -> "Text Post"
        else -> "New Post"
    }

    Box(
        Modifier.fillMaxSize().zIndex(20f)
            .background(postBackgroundBrush(dominantColor))
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── Scrollable content ──────────────────────────────────────
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
            ) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Spacer(Modifier.height(6.dp))

                // Top row: X close — status bubble — Post button
                Box(Modifier.fillMaxWidth().height(40.dp)) {
                    GlassCircleButton(
                        icon = Icons.Default.Close, contentDescription = "Close",
                        liquidGlass = liquidGlass, tint = dominantColor,
                        modifier = Modifier.align(Alignment.CenterStart), onClick = onClose
                    )
                    StatusBubble(
                        label = statusLabel,
                        showToggle = mode == ComposeMode.THREAD || mode == ComposeMode.TEXTSHOT || overflowChoice,
                        isThread = mode == ComposeMode.THREAD,
                        liquidGlass = liquidGlass, tint = dominantColor,
                        modifier = Modifier.align(Alignment.Center),
                        onPickThread = { switchToThread() },
                        onPickTextshot = { switchToTextshot() }
                    )
                    PostButton(
                        enabled = canPost && !submitting, submitting = submitting,
                        liquidGlass = liquidGlass, tint = dominantColor,
                        modifier = Modifier.align(Alignment.CenterEnd), onClick = ::handlePost
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Author row
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (selfProfile?.avatarUrl != null) {
                        AsyncImage(
                            model = selfProfile.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                        )
                    } else {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(0.12f)))
                    }
                    Column {
                        Text(
                            selfProfile?.displayName?.ifBlank { selfProfile.handle } ?: "You",
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        if (selfProfile != null) {
                            Text("@${selfProfile.handle}", color = DimGray, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                when (mode) {
                    ComposeMode.VIDEO -> {
                        HubDivider("Title")
                        GrowingTextField(
                            value = videoTitle,
                            onValueChange = { videoTitle = capBudget(it, POST_CHAR_LIMIT - videoDescription.text.length) },
                            placeholder = "Title…"
                        )
                        Spacer(Modifier.height(10.dp))
                        HubDivider("Description")
                        GrowingTextField(
                            value = videoDescription,
                            onValueChange = { videoDescription = capBudget(it, POST_CHAR_LIMIT - videoTitle.text.length) },
                            placeholder = "Description…"
                        )
                        Spacer(Modifier.height(12.dp))
                        VideoAndThumbnailRow(
                            videoUri = videoUri, thumbnailUri = videoThumbUri, aspect = videoAspect,
                            onTapThumbnail = { thumbnailPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        )
                    }

                    ComposeMode.THREAD -> {
                        threadPosts.forEachIndexed { index, tfv ->
                            HubDivider("Post ${index + 1}/${threadPosts.size}")
                            GrowingTextField(
                                value = tfv,
                                onValueChange = { newVal ->
                                    activeThreadIndex = index
                                    val fullText = threadPosts.mapIndexed { i, v -> if (i == index) newVal.text else v.text }
                                        .joinToString(" ")
                                    threadPosts = splitIntoThread(fullText).map { TextFieldValue(it) }
                                },
                                placeholder = if (index == 0) "Start a thread…" else "Continue the thread…",
                                onFocus = { activeThreadIndex = index }
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    ComposeMode.TEXTSHOT -> {
                        GrowingTextField(
                            value = singleText,
                            onValueChange = { singleText = it },
                            placeholder = "What's on your mind?"
                        )
                        Spacer(Modifier.height(14.dp))
                        TextshotPreview(
                            text = singleText.text.ifBlank { "Your post will look like this." },
                            liquidGlass = liquidGlass, tint = dominantColor
                        )
                    }

                    ComposeMode.SINGLE -> {
                        GrowingTextField(
                            value = singleText,
                            onValueChange = { singleText = it },
                            placeholder = "What's on your mind?",
                            onFocus = { activeThreadIndex = 0 }
                        )
                        if (images.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            ImageGrid(images = images, onRemove = { uri -> images = images - uri })
                        }
                    }
                }

                // Room for the fixed bottom bar so the last field/image row
                // never sits underneath it while scrolling.
                Spacer(Modifier.height(72.dp))
            }

            // ── Fixed bottom bar — rides up above the keyboard via
            // imePadding() so it always sits directly on top of it. ──────
            Row(
                Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val attachEnabled = mode != ComposeMode.TEXTSHOT
                GlassCircleButton(
                    icon = Icons.Default.Image, contentDescription = "Attach image or video",
                    liquidGlass = liquidGlass, tint = dominantColor, size = 40.dp,
                    enabled = attachEnabled,
                    onClick = {
                        mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                )
                Spacer(Modifier.weight(1f))
                GlassCircleButton(
                    icon = Icons.Default.Add, contentDescription = "Add post to thread",
                    liquidGlass = liquidGlass, tint = dominantColor, size = 36.dp,
                    enabled = mode != ComposeMode.VIDEO,
                    onClick = {
                        if (mode == ComposeMode.THREAD) {
                            threadPosts = threadPosts + TextFieldValue("")
                            activeThreadIndex = threadPosts.lastIndex
                        } else switchToThread()
                    }
                )
                Spacer(Modifier.width(10.dp))
                val (used, limit) = activeBudget
                val overLimit = used > limit
                Text(
                    if (limit == Int.MAX_VALUE) "$used" else "$used/$limit",
                    color = if (overLimit) Color(0xFFE0245E) else DimGray,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── Sub-components ──────────────────────────────────────────────────────

@Composable
private fun GlassCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    liquidGlass: Boolean, tint: Color,
    modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 36.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = CircleShape
    val clickMod = modifier.size(size).clip(shape).clickable(enabled = enabled, onClick = onClick)
    val alpha = if (enabled) 1f else 0.35f
    if (liquidGlass) {
        LiquidGlassSurface(clickMod, shape = shape, tint = tint) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = contentDescription, tint = Color.White.copy(alpha = alpha), modifier = Modifier.size(size * 0.45f))
            }
        }
    } else {
        Box(clickMod.background(Color.White.copy(0.10f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White.copy(alpha = alpha), modifier = Modifier.size(size * 0.45f))
        }
    }
}

@Composable
private fun StatusBubble(
    label: String?,
    showToggle: Boolean,
    isThread: Boolean,
    liquidGlass: Boolean, tint: Color,
    modifier: Modifier = Modifier,
    onPickThread: () -> Unit,
    onPickTextshot: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)

    @Composable
    fun Content() {
        if (!showToggle) {
            Text(label ?: "New Post", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Thread", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isThread) Color.White else DimGray,
                    modifier = Modifier.clickable(onClick = onPickThread).padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Box(Modifier.width(1.dp).height(14.dp).background(Color.White.copy(0.15f)))
                Text(
                    "Textshot", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (!isThread) Color.White else DimGray,
                    modifier = Modifier.clickable(onClick = onPickTextshot).padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
    if (liquidGlass) {
        LiquidGlassSurface(modifier, shape = shape, tint = tint) { Content() }
    } else {
        Box(modifier.clip(shape).background(Color.White.copy(0.10f))) { Content() }
    }
}

@Composable
private fun PostButton(
    enabled: Boolean, submitting: Boolean,
    liquidGlass: Boolean, tint: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val clickMod = modifier.clip(shape).clickable(enabled = enabled, onClick = onClick)

    @Composable
    fun Content() {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (submitting) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(
                    "Post", color = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    if (liquidGlass) {
        LiquidGlassSurface(clickMod, shape = shape, tint = if (enabled) RepostGreen else tint) { Content() }
    } else {
        Box(clickMod.background(Color.White.copy(0.10f))) { Content() }
    }
}

/** Same divider-with-centered-label look as the Hub's SectionDivider, used
 *  here for Title/Description and per-post thread labels. */
@Composable
private fun HubDivider(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
        Text(label, color = DimGray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp))
        androidx.compose.material3.HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
    }
}

@Composable
private fun GrowingTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    onFocus: () -> Unit = {}
) {
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 28.dp)) {
        BasicTextField(
            value = value, onValueChange = onValueChange,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFocus() }
        )
        if (value.text.isEmpty()) {
            Text(placeholder, color = DimGray, fontSize = 16.sp)
        }
    }
}

/** Up to 10 attached images, 5 per row, edge-to-edge square tiles. Hand-
 *  rolled (not LazyVerticalGrid) since it's capped at 10 items and lives
 *  inside an already-scrolling Column — this way it sizes to exactly the
 *  images present instead of reserving a fixed max height. */
@Composable
private fun ImageGrid(images: List<Uri>, onRemove: (Uri) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        images.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { uri ->
                    Box(Modifier.weight(1f).aspectRatio(1f).clickable { onRemove(uri) }) {
                        AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                // Pad the last, possibly-shorter row so tiles stay square
                // instead of stretching to fill the row width.
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Video mode's attachment row: the picked video on the left, a same-
 *  aspect-ratio thumbnail-picker box on the right — tapping it opens the
 *  image picker to choose a custom thumbnail. Neither is cropped: both
 *  match the video's own aspect ratio (portrait video -> two portrait
 *  boxes side by side, per spec). */
@Composable
private fun VideoAndThumbnailRow(
    videoUri: Uri?, thumbnailUri: Uri?, aspect: Float,
    onTapThumbnail: () -> Unit
) {
    if (videoUri == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.weight(1f).aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(Color.Black)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Video", tint = Color.White.copy(0.7f),
                modifier = Modifier.align(Alignment.Center).size(32.dp))
        }
        Box(
            Modifier.weight(1f).aspectRatio(aspect).clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(0.06f)).clickable(onClick = onTapThumbnail),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailUri != null) {
                AsyncImage(model = thumbnailUri, contentDescription = "Thumbnail", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = DimGray, modifier = Modifier.size(24.dp))
                    Text("Thumbnail", color = DimGray, fontSize = 11.sp)
                }
            }
        }
    }
}

/** Textshot mode's live preview: transparent background, white text,
 *  shrinking to fit as the text grows so everything stays visible when the
 *  final image is viewed on social media. */
@Composable
private fun TextshotPreview(text: String, liquidGlass: Boolean, tint: Color) {
    val shape = RoundedCornerShape(14.dp)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val sizeDp = 320.dp
    val sizePx = with(density) { sizeDp.toPx() }.toInt()
    val padding = with(density) { 24.dp.toPx() }
    val maxTextWidth = (sizePx - padding * 2).toInt().coerceAtLeast(1)
    val maxTextHeight = (sizePx - padding * 2).toInt().coerceAtLeast(1)

    val layout = remember(text, sizePx) {
        var fontSizeSp = 34f
        var result = measurer.measure(
            AnnotatedString(text),
            style = TextStyle(color = Color.White, fontSize = fontSizeSp.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            constraints = Constraints(maxWidth = maxTextWidth)
        )
        while (result.size.height > maxTextHeight && fontSizeSp > 10f) {
            fontSizeSp -= 2f
            result = measurer.measure(
                AnnotatedString(text),
                style = TextStyle(color = Color.White, fontSize = fontSizeSp.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                constraints = Constraints(maxWidth = maxTextWidth)
            )
        }
        result
    }

    Box(Modifier.size(sizeDp).clip(shape).then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape) else Modifier.background(Color.White.copy(0.05f)))) {
        Canvas(Modifier.fillMaxSize()) {
            val x = (size.width - layout.size.width) / 2f
            val y = (size.height - layout.size.height) / 2f
            translate(x, y) { drawText(layout) }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────

private fun Int?.orZero() = this ?: 0

/** How many chars "x/x" (with a leading space) costs, given how many posts
 *  are currently in the thread — grows to " 12/34" etc. once the thread has
 *  10+ posts, per the general form of the spec's "three characters". */
private fun threadSuffixLength(postCount: Int): Int = " ${postCount}/${postCount}".length

private fun capBudget(value: TextFieldValue, remainingBudget: Int): TextFieldValue {
    if (remainingBudget < 0) return TextFieldValue("", value.selection)
    if (value.text.length <= remainingBudget) return value
    return TextFieldValue(value.text.take(remainingBudget), value.selection)
}

/** Splits [fullText] into a balanced sequence of thread posts, each within
 *  the 300-char limit minus room for its own " x/n" suffix, breaking near
 *  word boundaries so the split reads naturally. Re-run on every edit (see
 *  the THREAD branch above) so editing any post re-flows the whole thread —
 *  matching the spec's "evenly space out the text between the posts". */
private fun splitIntoThread(fullText: String): List<String> {
    if (fullText.isBlank()) return listOf("")
    var n = 1
    while (true) {
        val maxPerPost = (POST_CHAR_LIMIT - threadSuffixLength(n)).coerceAtLeast(1)
        val needed = kotlin.math.ceil(fullText.length / maxPerPost.toDouble()).toInt().coerceAtLeast(1)
        if (needed <= n || n > 50) break
        n = needed
    }
    val maxPerPost = (POST_CHAR_LIMIT - threadSuffixLength(n)).coerceAtLeast(1)
    if (n <= 1) return listOf(fullText)

    val target = kotlin.math.ceil(fullText.length / n.toDouble()).toInt().coerceAtMost(maxPerPost)
    val result = mutableListOf<String>()
    var remaining = fullText
    for (i in 0 until n - 1) {
        var cut = minOf(target, remaining.length, maxPerPost)
        if (cut < remaining.length) {
            var breakAt = cut
            while (breakAt > 0 && remaining[breakAt] != ' ') breakAt--
            if (breakAt > cut / 2) cut = breakAt
        }
        result.add(remaining.substring(0, cut).trimEnd())
        remaining = remaining.substring(cut).trimStart()
    }
    result.add(remaining)
    return result
}

private fun isVideoUri(context: android.content.Context, uri: Uri): Boolean {
    val type = context.contentResolver.getType(uri) ?: return false
    return type.startsWith("video/")
}

private fun probeVideoAspect(context: android.content.Context, uri: Uri): Float {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 16f
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 9f
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) h / w else w / h
    } catch (_: Exception) {
        16f / 9f
    } finally {
        try { retriever.release() } catch (_: Exception) {}
    }
}
