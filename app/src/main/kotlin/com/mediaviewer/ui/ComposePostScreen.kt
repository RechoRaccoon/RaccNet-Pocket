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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
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

    // Item 2: match the rest of the app and reflect the signed-in person's
    // own profile color here, instead of whatever post the feed happened to
    // be showing when the composer was opened (that's what the incoming
    // `dominantColor` param actually carries — see MainActivity's
    // `currentDominantColor`). Same shadowing pattern SettingsSheet's Hub
    // uses for its own `dominantColor` param.
    val dominantColor = selfProfile?.avatarUrl?.let { rememberDominantColor(it) } ?: dominantColor

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
    // Item 7/9: Blog is a standalone status toggle (not a full mode with its
    // own editor — the composer keeps using the same single-field editor
    // underneath it), separate from the Thread/Textshot mode switch below.
    var isBlogMode by remember { mutableStateOf(false) }

    // Item 3: focus targets so a tap anywhere on the blank background can
    // land the keyboard caret in whichever field is actually active, rather
    // than requiring the person to tap the exact spot the (possibly empty,
    // barely-tall) text field occupies.
    val singleFocusRequester = remember { FocusRequester() }
    val videoTitleFocusRequester = remember { FocusRequester() }
    val threadFocusRequesters = remember(threadPosts.size) { List(threadPosts.size) { FocusRequester() } }
    fun focusActiveField() {
        try {
            when (mode) {
                ComposeMode.SINGLE, ComposeMode.TEXTSHOT -> singleFocusRequester.requestFocus()
                ComposeMode.THREAD -> threadFocusRequesters.getOrNull(activeThreadIndex)?.requestFocus()
                ComposeMode.VIDEO -> videoTitleFocusRequester.requestFocus()
            }
        } catch (_: IllegalStateException) {
            // Field not attached to the composition yet — nothing to focus.
        }
    }

    // Item 4: explicit "+" tap from a non-thread mode — keeps whatever's
    // already been typed as post 1 and opens a blank post 2 right after it
    // ("the next sub post after the main one"), instead of the old code's
    // habit of collapsing straight back down to a single "1/1" post because
    // short seed text didn't actually need a second one.
    fun startThreadFromSingle() {
        threadPosts = computeThreadPosts(singleText.text, minPosts = 2).map { TextFieldValue(it) }
        activeThreadIndex = threadPosts.lastIndex
        mode = ComposeMode.THREAD
        isBlogMode = false
    }

    // Item 4: "+" tap while already threaded — appends one genuinely new
    // blank post and moves focus there, instead of re-flowing/redistributing
    // any existing text into it.
    fun addThreadPost() {
        threadPosts = threadPosts + TextFieldValue("")
        activeThreadIndex = threadPosts.lastIndex
    }

    // Item 9: typing past the limit in a plain post now turns it straight
    // into a thread — no more "Thread/Textshot" choice sitting in the
    // status bubble. `minPosts` is floored at the thread's *current* size
    // (see the doc comment on computeThreadPosts) purely so this is safe to
    // reuse below for re-flowing an already-started thread too.
    fun growTextIntoThread(fullText: String, floor: Int) {
        threadPosts = computeThreadPosts(fullText, minPosts = floor).map { TextFieldValue(it) }
        activeThreadIndex = threadPosts.lastIndex
        mode = ComposeMode.THREAD
        isBlogMode = false
    }

    fun switchToTextshot() {
        val seed = if (mode == ComposeMode.THREAD) threadPosts.joinToString("") { it.text } else singleText.text
        singleText = TextFieldValue(seed)
        isBlogMode = false
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
        ComposeMode.SINGLE -> singleText.text.isNotBlank() && singleText.text.length <= POST_CHAR_LIMIT
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
                    // trimEnd(): the lossless chunker (see computeThreadPosts)
                    // can leave a trailing space at a post's own break point;
                    // strip it here so the posted text doesn't end up with a
                    // double space before the counter suffix.
                    ThreadPostDraft(text = tfv.text.trimEnd() + suffix)
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

    // Item 9: this is now always a plain label — Thread/Textshot are no
    // longer chosen from inside the status bubble (see StatusBubble below),
    // only from the dedicated bottom-bar buttons.
    val statusLabel = when {
        mode == ComposeMode.VIDEO -> "Video"
        mode == ComposeMode.THREAD -> "Thread"
        mode == ComposeMode.TEXTSHOT -> "Textshot"
        isBlogMode -> "Blog"
        images.isNotEmpty() -> "Media Post"
        singleText.text.isNotBlank() -> "Text Post"
        else -> "New Post"
    }

    Box(
        Modifier.fillMaxSize().zIndex(20f)
            .background(postBackgroundBrush(dominantColor))
            // Item 1: without this, blank space here (Spacers, dividers,
            // anything with no click handler of its own) isn't claimed by
            // this overlay at all, so the tap falls straight through to
            // whatever's still composed behind it — in this case, the Hub
            // page's own buttons at that same screen position. See the doc
            // comment on blockClicksBehind() in GlassTheme.kt for the full
            // story; every other full-screen overlay in the app already
            // does this. Item 3: reuse the same tap to also focus whichever
            // field is actually active, so tapping the blank canvas starts
            // typing there immediately instead of requiring the person to
            // hit the exact (possibly tiny/empty) field.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusActiveField() }
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
                        liquidGlass = liquidGlass, tint = dominantColor,
                        modifier = Modifier.align(Alignment.Center)
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
                            placeholder = "Title…",
                            focusRequester = videoTitleFocusRequester
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

                    // Item 4: every post re-flows from one canonical, lossless
                    // full-text string (see computeThreadPosts's doc comment)
                    // instead of joining the *already-chunked* per-post texts
                    // back together — that used to eat/duplicate the spaces
                    // right at each post's own break point. The edited
                    // field's caret position is carried through the re-flow
                    // in absolute-offset terms and mapped back onto whichever
                    // post it now lands in, so typing anywhere in the thread
                    // (including a freshly-added blank post) keeps the caret
                    // exactly where it was instead of snapping to position 0.
                    ComposeMode.THREAD -> {
                        threadPosts.forEachIndexed { index, tfv ->
                            HubDivider("Post ${index + 1}/${threadPosts.size}")
                            GrowingTextField(
                                value = tfv,
                                onValueChange = { newVal ->
                                    val priorLength = threadPosts.take(index).sumOf { it.text.length }
                                    val absoluteCaret = priorLength + newVal.selection.end
                                    val fullText = threadPosts.mapIndexed { i, v -> if (i == index) newVal.text else v.text }
                                        .joinToString("")
                                    val chunks = computeThreadPosts(fullText, minPosts = threadPosts.size)

                                    var remainingCaret = absoluteCaret
                                    var caretChunk = 0
                                    for ((i, chunk) in chunks.withIndex()) {
                                        caretChunk = i
                                        if (remainingCaret <= chunk.length) break
                                        remainingCaret -= chunk.length
                                    }
                                    remainingCaret = remainingCaret.coerceIn(0, chunks.getOrElse(caretChunk) { "" }.length)

                                    threadPosts = chunks.mapIndexed { i, text ->
                                        if (i == caretChunk) TextFieldValue(text, TextRange(remainingCaret)) else TextFieldValue(text)
                                    }
                                    activeThreadIndex = caretChunk
                                },
                                placeholder = if (index == 0) "Start a thread…" else "Continue the thread…",
                                onFocus = { activeThreadIndex = index },
                                focusRequester = threadFocusRequesters.getOrNull(index),
                                // Item 4: shows the "x/n" counter as trailing,
                                // non-editable text right inside the post
                                // itself, matching what actually gets posted
                                // (see handlePost's own suffix) — purely
                                // visual, so it can't be tapped into or
                                // accidentally deleted from the real content.
                                visualTransformation = threadSuffixTransformation(index, threadPosts.size)
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    ComposeMode.TEXTSHOT -> {
                        GrowingTextField(
                            value = singleText,
                            onValueChange = { singleText = it },
                            placeholder = "What's on your mind?",
                            focusRequester = singleFocusRequester
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
                            onValueChange = { newVal ->
                                // Item 9: overflowing the limit here turns
                                // this straight into a thread — no more
                                // in-between "choose Thread or Textshot"
                                // state living in the status bubble.
                                if (newVal.text.length > POST_CHAR_LIMIT) {
                                    growTextIntoThread(newVal.text, floor = threadPosts.size)
                                } else {
                                    singleText = newVal
                                }
                            },
                            placeholder = "What's on your mind?",
                            onFocus = { activeThreadIndex = 0 },
                            focusRequester = singleFocusRequester
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
                Spacer(Modifier.width(6.dp))
                // Item 7: Blog and Textshot toggle buttons — dim unless the
                // status they set is the one currently active.
                GlassCircleButton(
                    icon = Icons.Default.Article, contentDescription = "Blog",
                    liquidGlass = liquidGlass, tint = dominantColor, size = 40.dp,
                    enabled = mode != ComposeMode.VIDEO,
                    selected = isBlogMode,
                    onClick = {
                        if (isBlogMode) {
                            isBlogMode = false
                        } else {
                            isBlogMode = true
                            if (mode != ComposeMode.VIDEO) mode = ComposeMode.SINGLE
                        }
                    }
                )
                GlassCircleButton(
                    icon = Icons.Default.Screenshot, contentDescription = "Textshot",
                    liquidGlass = liquidGlass, tint = dominantColor, size = 40.dp,
                    enabled = mode != ComposeMode.VIDEO,
                    selected = mode == ComposeMode.TEXTSHOT,
                    onClick = {
                        if (mode == ComposeMode.TEXTSHOT) {
                            mode = ComposeMode.SINGLE
                        } else {
                            switchToTextshot()
                        }
                    }
                )
                Spacer(Modifier.weight(1f))
                GlassCircleButton(
                    icon = Icons.Default.Add, contentDescription = "Add post to thread",
                    liquidGlass = liquidGlass, tint = dominantColor, size = 36.dp,
                    enabled = mode != ComposeMode.VIDEO,
                    onClick = {
                        if (mode == ComposeMode.THREAD) addThreadPost() else startThreadFromSingle()
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
    // Item 7: for toggle-style buttons (Blog, Textshot) — dim unless the
    // button represents the currently-active status, same visual treatment
    // as a disabled button but independent of `enabled`.
    selected: Boolean = true,
    onClick: () -> Unit
) {
    val shape = CircleShape
    val clickMod = modifier.size(size).clip(shape).clickable(enabled = enabled, onClick = onClick)
    val alpha = if (enabled && selected) 1f else 0.35f
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

/** Item 9: just a plain label now — Thread is entered automatically once
 *  typing overflows the limit, and Blog/Textshot are their own dedicated
 *  bottom-bar buttons (see GlassCircleButton's `selected` param below), so
 *  there's no toggle living inside this bubble anymore. */
@Composable
private fun StatusBubble(
    label: String?,
    liquidGlass: Boolean, tint: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)

    @Composable
    fun Content() {
        Text(label ?: "New Post", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
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
    onFocus: () -> Unit = {},
    // Item 3: lets the background-tap handler (see focusActiveField above)
    // request focus into this exact field.
    focusRequester: FocusRequester? = null,
    // Item 4: used by the thread fields to show the "x/n" counter as
    // trailing display-only text without it being part of the editable
    // content.
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 28.dp)) {
        BasicTextField(
            value = value, onValueChange = onValueChange,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
 *  images present instead of reserving a fixed max height.
 *
 *  Item 5: every row's tiles are weighted against that row's own item count
 *  (not a fixed 5), so a row of 1–4 images stretches edge-to-edge and grows
 *  a little bigger instead of only filling that fraction of the row width
 *  with the rest padded out as empty space — the old behavior looked
 *  "perfect" only when the count happened to be an exact multiple of 5. */
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

/** Item 4: greedily packs [text] into chunks of at most [budget] characters
 *  each — the *first* chunk is filled as close to full as possible before
 *  anything spills into the second, and so on — instead of evenly balancing
 *  length across every chunk. Breaks right after the last space at or
 *  before the budget so words aren't split mid-word (falls back to a hard
 *  cut only for a single "word" longer than the whole budget). The space
 *  itself stays at the *end* of the earlier chunk rather than being
 *  trimmed away, so `chunks.joinToString("")` always losslessly
 *  reconstructs the original [text] — that's what lets the THREAD editor
 *  above safely rebuild one canonical full-text string by just concatenating
 *  every post's current text back together on every keystroke. */
private fun greedyChunks(text: String, budget: Int): List<String> {
    if (budget <= 0 || text.length <= budget) return listOf(text)
    val chunks = mutableListOf<String>()
    var start = 0
    while (text.length - start > budget) {
        var cut = start + budget
        var breakAt = cut
        while (breakAt > start && text[breakAt - 1] != ' ') breakAt--
        if (breakAt > start) cut = breakAt
        chunks.add(text.substring(start, cut))
        start = cut
    }
    chunks.add(text.substring(start))
    return chunks
}

/** Item 4: turns [fullText] into the thread's actual list of per-post
 *  strings — greedily filling each post before spilling into the next (see
 *  [greedyChunks]) rather than evenly balancing the text across every post,
 *  and never dropping below [minPosts] posts even if the text has since
 *  gotten short enough to technically fit in fewer. That floor is what
 *  fixes the old "typing in a new/blank post collapses the whole thread
 *  back down to one post" bug — the post count only ever grows to fit more
 *  content, it never shrinks out from under whatever the person already
 *  explicitly created (there's no per-post remove affordance in this UI, so
 *  there's never a legitimate reason for the count to drop on its own). Each
 *  post's own budget accounts for its "x/n" counter suffix, and — since
 *  that suffix's own width depends on the final post count once posts reach
 *  double digits — this re-derives the post count until the budget and the
 *  count it produces agree with each other. */
private fun computeThreadPosts(fullText: String, minPosts: Int): List<String> {
    val floor = minPosts.coerceAtLeast(1)
    if (fullText.isEmpty() && floor <= 1) return listOf("")
    var n = floor
    while (true) {
        val budget = (POST_CHAR_LIMIT - threadSuffixLength(n)).coerceAtLeast(1)
        val needed = maxOf(greedyChunks(fullText, budget).size, floor)
        if (needed <= n || n > 50) break
        n = needed
    }
    val budget = (POST_CHAR_LIMIT - threadSuffixLength(n)).coerceAtLeast(1)
    val chunks = greedyChunks(fullText, budget).toMutableList()
    while (chunks.size < n) chunks.add("")
    return chunks
}

/** Item 4: displays " x/n" right after a thread post's real text — for
 *  preview only, so it can't be tapped into, selected, or edited, and never
 *  becomes part of the actual stored post content (the real suffix is
 *  appended separately at submit time — see handlePost). */
private fun threadSuffixTransformation(index: Int, total: Int): VisualTransformation {
    val suffix = if (total > 1) " ${index + 1}/$total" else ""
    if (suffix.isEmpty()) return VisualTransformation.None
    return VisualTransformation { text ->
        TransformedText(
            AnnotatedString(text.text + suffix),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = offset.coerceIn(0, text.length)
                override fun transformedToOriginal(offset: Int) = offset.coerceIn(0, text.length)
            }
        )
    }
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
