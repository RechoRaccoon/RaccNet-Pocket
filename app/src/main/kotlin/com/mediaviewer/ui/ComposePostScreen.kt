package com.mediaviewer.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.em
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.TitleSearchResult
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.EmojiEntry
import com.mediaviewer.util.EmojiStore
import com.mediaviewer.util.rememberHapticTap
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
 *   - Labels: the bottom bar's "Labels" button opens [ContentLabelsPopup],
 *     which mirrors Bluesky's own "Add a content warning" menu. The choice
 *     rides along on [ComposePostDraft.selfLabels] and is written to each
 *     post record as a standard com.atproto.label.defs#selfLabels object,
 *     which is what Bluesky's official labeler/moderation UI reads.
 */

private const val POST_CHAR_LIMIT = 300
private const val MAX_IMAGES = 10

enum class ComposeMode { SINGLE, THREAD, TEXTSHOT, VIDEO, REVIEW, BLOG }

/** The three mutually-exclusive "Adult Content" self-labels from Bluesky's
 *  own content-warning menu, in Bluesky's order. [value] is the exact
 *  self-label string Bluesky's moderation system expects; [description] is
 *  the same helper text Bluesky shows under each option. */
enum class AdultContentLabel(val value: String, val title: String, val description: String) {
    SUGGESTIVE("sexual", "Suggestive", "Pictures meant for adults."),
    NUDITY("nudity", "Nudity", "Artistic or non-erotic nudity."),
    ADULT("porn", "Adult", "Sexual activity or erotic nudity.")
}

/** Bluesky's independent "Other" self-label. */
private const val GRAPHIC_MEDIA_LABEL = "graphic-media"
private const val GRAPHIC_MEDIA_DESCRIPTION = "Media that may be disturbing or inappropriate for some audiences."

/** One post's worth of content inside a [ComposeMode.THREAD] thread. */
data class ThreadPostDraft(
    val text: String,
    val images: List<Uri> = emptyList(),
    val video: Uri? = null
)

/** Item 2: one thread post's *live editing* state — its text field value
 *  plus whatever media has been attached to that post specifically. Each
 *  post in a thread carries its own up-to-[MAX_IMAGES]-images-or-one-video
 *  media, entirely independent of every other post's — this is what the
 *  attach-image button below reads/writes into (via [activeThreadIndex])
 *  instead of a single draft-wide media list. */
private data class ThreadPostState(
    val text: TextFieldValue,
    val images: List<Uri> = emptyList(),
    val video: Uri? = null
)

/** Everything the composer collected, handed to the caller on "Post". */
data class ComposePostDraft(
    val mode: ComposeMode,
    /** SINGLE: exactly one entry. THREAD: two or more, in posting order.
     *  REVIEW: exactly one entry, carrying just the typed review text (no
     *  images — see reviewTarget's own doc comment below for why). */
    val posts: List<ThreadPostDraft> = emptyList(),
    val videoUri: Uri? = null,
    val videoThumbnailUri: Uri? = null,
    val videoTitle: String = "",
    val videoDescription: String = "",
    val textshotText: String = "",
    /** Textshot mode: the post's own (regular Bluesky) text, separate from
     *  the text rendered into the image — for hashtags, a caption… */
    val textshotPostText: String = "",
    // Item 10: the title being reviewed, and the picked star rating on
    // Popfeed's own native 0–10 half-star scale (so 0 = unrated, 10 = full
    // 5 stars) — both only populated for ComposeMode.REVIEW. Image
    // attach/Textshot/Blog/thread are greyed out for the whole lifetime of
    // a review draft (see ComposePostScreen's reviewTarget param), so the
    // review itself is always exactly one plain-text post.
    val reviewTarget: TitleSearchResult? = null,
    val reviewRating: Int = 0,
    // Item 12: mirrors social.popfeed.feed.review's own "containsSpoilers"
    // boolean (see review.json) — set from the composer's "Mark as Spoiler"
    // toggle, only meaningful for ComposeMode.REVIEW.
    val reviewContainsSpoilers: Boolean = false,
    // Bluesky self-label values picked via the composer's "Labels" popup
    // (e.g. "sexual", "nudity", "porn", "graphic-media"). Empty = no labels.
    // Applied to every post the draft produces (all posts of a thread).
    val selfLabels: List<String> = emptyList(),
    // Item 12: ComposeMode.BLOG — the whole blog (title, description, rows).
    val blog: com.mediaviewer.model.BlogDraft? = null
)

/** Item 12: one live row of the blog editor. [id] is stable for the row's
 *  whole life (keys its focus requester and list position). */
private data class BlogEditorRow(
    val id: Long,
    val kind: com.mediaviewer.model.BlogRowKind = com.mediaviewer.model.BlogRowKind.TEXT,
    val text: TextFieldValue = TextFieldValue(""),
    val align: com.mediaviewer.model.LeafletAlign = com.mediaviewer.model.LeafletAlign.START,
    val imageUri: Uri? = null,
    val existingBlob: com.google.gson.JsonObject? = null,
    val existingUrl: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val alt: String = ""
)

private var blogRowIds = 0L
private fun nextBlogRowId() = ++blogRowIds

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ComposePostScreen(
    selfProfile: AuthorInfo?,
    liquidGlass: Boolean,
    dominantColor: Color = NeutralGlassTint,
    submitting: Boolean = false,
    // Item 10: non-null the moment this composer was opened via a title
    // page's "Review" bar (see MainViewModel.openReviewCompose) — forces
    // Review mode/status for the composer's whole lifetime (switching back
    // to Post/Thread/etc. mid-draft would leave a half-written review with
    // nowhere sensible to go) and shows the cover/title/rating row below
    // the author row.
    reviewTarget: TitleSearchResult? = null,
    // Item 8: set when this composer was opened from the camera-notch
    // button's "Camera" action — the just-captured photo/video is attached
    // the moment the composer opens, same as if the person had picked it
    // from the media picker themselves. At most one of these is ever set.
    initialImageUri: Uri? = null,
    initialVideoUri: Uri? = null,
    // Item 12: set when editing an existing blog — opens in Blog mode,
    // filled in, with "Save" instead of "Post". Second = its labels.
    editBlog: Pair<com.mediaviewer.model.BlogDraft, List<String>>? = null,
    onClose: () -> Unit,
    onSubmit: (ComposePostDraft) -> Unit
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current

    // Item 2 / Item 10: match the rest of the app and reflect the signed-in
    // person's own profile color here, instead of whatever post the feed
    // happened to be showing when the composer was opened (that's what the
    // incoming `dominantColor` param actually carries — see MainActivity's
    // `currentDominantColor`). Same shadowing pattern SettingsSheet's Hub
    // uses for its own `dominantColor` param. While reviewing, the title's
    // own poster color takes priority over the profile avatar shadow, since
    // the title being reviewed is far more the visual subject here than the
    // reviewer's own avatar is.
    val dominantColor = reviewTarget?.posterUrl?.let { rememberDominantColor(it) }
        ?: rememberSelfTint(selfProfile?.avatarUrl, dominantColor)

    // ── Core state ───────────────────────────────────────────────────────
    var mode by remember {
        mutableStateOf(
            when {
                reviewTarget != null -> ComposeMode.REVIEW
                editBlog != null -> ComposeMode.BLOG
                initialVideoUri != null -> ComposeMode.VIDEO
                else -> ComposeMode.SINGLE
            }
        )
    }
    // Item 10: Popfeed's own native 0–10 half-star scale (0 = unrated,
    // 10 = full 5 stars) — see PopfeedReview's ratingOutOf5 doc comment for
    // why /2 is always the right conversion both ways.
    var reviewRating by remember { mutableStateOf(0) }
    // Item 12: composer-local "Mark as Spoiler" toggle for Review mode —
    // see ComposePostDraft.reviewContainsSpoilers.
    var reviewContainsSpoilers by remember { mutableStateOf(false) }
    var singleText by remember { mutableStateOf(TextFieldValue("")) }
    // Textshot mode's separate post text (see ComposePostDraft.textshotPostText).
    var textshotPostText by remember { mutableStateOf(TextFieldValue("")) }
    var textshotPostFocused by remember { mutableStateOf(false) }
    val textshotPostFocus = remember { FocusRequester() }
    // A pending "remove this?" question (attached media, a thread post).
    var confirmRemoval by remember { mutableStateOf<RemovalRequest?>(null) }
    // Item 8: pre-seeded straight from the camera-notch button's "Camera"
    // action, if that's how this composer was opened.
    var images by remember { mutableStateOf(initialImageUri?.let { listOf(it) } ?: emptyList()) }
    var videoUri by remember { mutableStateOf(initialVideoUri) }
    var videoThumbUri by remember { mutableStateOf<Uri?>(null) }
    var videoAspect by remember { mutableStateOf(16f / 9f) }
    var videoTitle by remember { mutableStateOf(TextFieldValue("")) }
    var videoDescription by remember { mutableStateOf(TextFieldValue("")) }
    var threadPosts by remember { mutableStateOf(listOf(ThreadPostState(TextFieldValue("")))) }
    var activeThreadIndex by remember { mutableStateOf(0) }
    // The camera-notch bubble works on this page too: a photo (or a VRM
    // capture) taken while the composer is already open arrives as a NEW
    // initial*Uri — attach it to the draft instead of ignoring it. The
    // first values were already seeded into the state above.
    val seededMedia = remember { mutableSetOf<Uri>().apply { initialImageUri?.let(::add); initialVideoUri?.let(::add) } }
    LaunchedEffect(initialImageUri, initialVideoUri) {
        initialImageUri?.takeIf { seededMedia.add(it) }?.let { uri ->
            if (mode == ComposeMode.THREAD) {
                val i = activeThreadIndex.coerceIn(0, threadPosts.lastIndex)
                val post = threadPosts[i]
                if (post.video == null && post.images.size < MAX_IMAGES) {
                    threadPosts = threadPosts.toMutableList().also { it[i] = post.copy(images = post.images + uri) }
                }
            } else if (videoUri == null && images.size < MAX_IMAGES) {
                images = images + uri
            }
        }
        initialVideoUri?.takeIf { seededMedia.add(it) }?.let { uri ->
            if (mode == ComposeMode.THREAD) {
                val i = activeThreadIndex.coerceIn(0, threadPosts.lastIndex)
                val post = threadPosts[i]
                if (post.images.isEmpty()) {
                    threadPosts = threadPosts.toMutableList().also { it[i] = post.copy(video = uri) }
                }
            } else if (mode != ComposeMode.REVIEW) {
                videoUri = uri
                mode = ComposeMode.VIDEO
            }
        }
    }
    // Item 7/9: Blog is a standalone status toggle (not a full mode with its
    // own editor — the composer keeps using the same single-field editor
    // underneath it), separate from the Thread/Textshot mode switch below.
    var isBlogMode by remember { mutableStateOf(editBlog != null) }
    // ── Item 12: blog editor state ──────────────────────────────────────
    // Row 1 = title, row 2 = description/tagline, then the body rows, each
    // a paragraph, a header (H1–H3) or an image, with its own alignment.
    var blogTitle by remember { mutableStateOf(TextFieldValue(editBlog?.first?.title ?: "")) }
    var blogDescription by remember { mutableStateOf(TextFieldValue(editBlog?.first?.description ?: "")) }
    var blogRows by remember {
        mutableStateOf(
            editBlog?.first?.rows?.map { r ->
                BlogEditorRow(
                    id = nextBlogRowId(), kind = r.kind, text = TextFieldValue(r.text), align = r.align,
                    imageUri = r.imageUri, existingBlob = r.existingBlob, existingUrl = r.existingUrl,
                    imageWidth = r.imageWidth, imageHeight = r.imageHeight, alt = r.alt
                )
            }?.ifEmpty { null } ?: listOf(BlogEditorRow(nextBlogRowId()))
        )
    }
    // Which row the buttons act on: -2 = title, -1 = description, 0+ = body row.
    var blogSelected by remember { mutableStateOf(0) }
    val blogFocusRequesters = remember { HashMap<Long, FocusRequester>() }
    fun blogRequester(id: Long) = blogFocusRequesters.getOrPut(id) { FocusRequester() }
    val blogTitleFocus = remember { FocusRequester() }
    val blogDescriptionFocus = remember { FocusRequester() }
    var blogFocusTarget by remember { mutableStateOf<Long?>(null) }
    // Item 3/6/7: whether the thread re-flows text across posts as a single
    // continuous stream (greedy-packing every post full before spilling into
    // the next) or leaves each post exactly as the person typed it. On by
    // default whenever the thread was created *for* the person (typing past
    // the limit, or turning off Textshot/Blog over the limit) since there's
    // real text that genuinely needs auto-splitting; off whenever they
    // started the thread themselves via "+", since re-flowing would otherwise
    // yank whatever they type in post 2 or 3 back into post 1 the moment it
    // could technically still fit there.
    var autoFormat by remember { mutableStateOf(true) }
    // Bluesky self-labels: at most one of the three Adult Content options,
    // plus an independent Graphic Media toggle.
    var adultLabel by remember { mutableStateOf(editBlog?.second?.let { l -> AdultContentLabel.values().firstOrNull { it.value in l } }) }
    var graphicMedia by remember { mutableStateOf(editBlog?.second?.contains(GRAPHIC_MEDIA_LABEL) == true) }
    var labelsOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // ── Emoji menu (Textshot mode) ───────────────────────────────────────
    // The raccoon button swaps the keyboard for the emoji menu, which is sized
    // to match the keyboard exactly (its last-seen height). While it's open the
    // keyboard is hidden and the screen reserves the menu's height instead of
    // the keyboard's, so nothing jumps when one replaces the other.
    val emojiStore = remember { EmojiStore.get(context) }
    LaunchedEffect(Unit) { emojiStore.load() }
    var emojiPanelOpen by remember { mutableStateOf(false) }
    // True while a folder tab is being renamed: the keyboard is needed then, so
    // the menu shrinks to its tab row and rides on top of it.
    var emojiTabEditing by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeDensity = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(imeDensity)
    var keyboardPx by remember { mutableStateOf(rememberedKeyboardPx) }
    var lastImePx by remember { mutableStateOf(0) }
    LaunchedEffect(imeBottomPx) {
        // The keyboard growing again (the person tapped the text) means they want
        // it back, so the menu steps aside. Shrinking (we just hid it) is ignored.
        if (imeBottomPx > lastImePx && emojiPanelOpen && !emojiTabEditing) emojiPanelOpen = false
        lastImePx = imeBottomPx
        if (imeBottomPx > keyboardPx) {
            keyboardPx = imeBottomPx
            rememberedKeyboardPx = imeBottomPx
        }
    }
    LaunchedEffect(mode) {
        if (mode != ComposeMode.TEXTSHOT) { emojiPanelOpen = false; emojiTabEditing = false }
    }
    // Registered after the composer's own BackHandler, so Back closes the menu first.
    BackHandler(enabled = emojiPanelOpen) { emojiPanelOpen = false; emojiTabEditing = false }
    val emojiPanelHeight = with(imeDensity) { if (keyboardPx > 0) keyboardPx.toDp() else 300.dp }.coerceIn(240.dp, 480.dp)
    val imeDp = with(imeDensity) { imeBottomPx.toDp() }
    // What the scrolling content and the bottom bar reserve at the screen's bottom.
    val bottomInsetModifier: Modifier = when {
        emojiPanelOpen && emojiTabEditing -> Modifier.padding(bottom = imeDp + EmojiPanelCompactHeight)
        emojiPanelOpen -> Modifier.padding(bottom = emojiPanelHeight)
        else -> Modifier.imePadding().navigationBarsPadding()
    }

    // Emoji only exist inside Textshot text (each is one private-use character).
    // Anywhere else that character would show as a blank box, so when a draft
    // leaves Textshot mode they become readable :name: shortcodes instead.
    fun emojiTokensToShortcodes() {
        val converted = emojiStore.toShortcodes(singleText.text)
        if (converted != singleText.text) singleText = TextFieldValue(converted, TextRange(converted.length))
    }

    // Item 3: focus targets so a tap anywhere on the blank background can
    // land the keyboard caret in whichever field is actually active, rather
    // than requiring the person to tap the exact spot the (possibly empty,
    // barely-tall) text field occupies.
    val singleFocusRequester = remember { FocusRequester() }
    val videoTitleFocusRequester = remember { FocusRequester() }
    // Item 2: a stable, only-ever-growing list of FocusRequesters — one per
    // thread post, keyed by *position*, never recreated once created. The
    // old `remember(threadPosts.size)` re-ran this constructor on every
    // keystroke that changed the post count — which, with Auto Format on,
    // is nearly every keystroke near a post boundary — swapping out the
    // exact FocusRequester object that was attached to the field the person
    // was mid-typing into. Compose treats that as the field losing its
    // focus target entirely, which is what silently dropped the keyboard
    // and the caret indicator and forced a second tap to resume typing.
    // A plain (non-observable) MutableList that only ever appends new
    // requesters — recomputed once per recomposition, before it's read
    // below — keeps every existing post's requester identity stable across
    // any resize.
    val threadFocusRequesters = remember { mutableListOf<FocusRequester>() }
    while (threadFocusRequesters.size < threadPosts.size) threadFocusRequesters.add(FocusRequester())
    fun focusActiveField() {
        try {
            when (mode) {
                ComposeMode.SINGLE, ComposeMode.TEXTSHOT, ComposeMode.REVIEW -> singleFocusRequester.requestFocus()
                ComposeMode.THREAD -> threadFocusRequesters.getOrNull(activeThreadIndex)?.requestFocus()
                ComposeMode.VIDEO -> videoTitleFocusRequester.requestFocus()
                ComposeMode.BLOG -> when (blogSelected) {
                    -2 -> blogTitleFocus.requestFocus()
                    -1 -> blogDescriptionFocus.requestFocus()
                    else -> blogRows.getOrNull(blogSelected)?.takeIf { it.kind != com.mediaviewer.model.BlogRowKind.IMAGE }
                        ?.let { blogRequester(it.id).requestFocus() }
                }
            }
        } catch (_: IllegalStateException) {
            // Field not attached to the composition yet — nothing to focus.
        }
    }
    // Item 2: Auto Format's reflow can move the caret into a *different*
    // post than the one that physically has keyboard focus right now (e.g.
    // typing right up against one post's limit spills the tail of what was
    // just typed into the next post) — activeThreadIndex updates to follow
    // the caret, but nothing was asking the keyboard to follow it too. This
    // keeps the two in sync so typing can continue seamlessly across a
    // spillover instead of the keyboard staying behind on the old post.
    LaunchedEffect(activeThreadIndex, mode) {
        if (mode == ComposeMode.THREAD) {
            withFrameNanos { }
            focusActiveField()
        }
    }

    // Item 4/6: explicit "+" tap from a non-thread mode — keeps whatever's
    // already been typed as post 1 and opens a blank post 2 right after it
    // ("the next sub post after the main one"), instead of the old code's
    // habit of collapsing straight back down to a single "1/1" post because
    // short seed text didn't actually need a second one. Item 6: if that
    // text (e.g. a long Blog draft) already overflows a single post on its
    // own, this is really an auto-split rather than "add one more post to
    // continue typing", so Auto Format starts on for it same as typing past
    // the limit does — otherwise it stays off, the normal manual-add case.
    fun startThreadFromSingle() {
        val overflowing = singleText.text.length > POST_CHAR_LIMIT
        // Whatever was already attached to the single post carries over onto
        // the thread's first post rather than being dropped.
        threadPosts = computeThreadPosts(singleText.text, minPosts = if (overflowing) 1 else 2)
            .mapIndexed { i, text -> ThreadPostState(TextFieldValue(text), images = if (i == 0) images else emptyList(), video = if (i == 0) videoUri else null) }
        images = emptyList()
        videoUri = null
        activeThreadIndex = threadPosts.lastIndex
        mode = ComposeMode.THREAD
        isBlogMode = false
        autoFormat = overflowing
    }

    // Item 4: "+" tap while already threaded — appends one genuinely new
    // blank post and moves focus there, instead of re-flowing/redistributing
    // any existing text into it.
    fun addThreadPost() {
        threadPosts = threadPosts + ThreadPostState(TextFieldValue(""))
        activeThreadIndex = threadPosts.lastIndex
    }

    // Item 9: turns text into a thread with Auto Format on. Typing past the
    // limit in a plain post no longer lands here (it switches to Textshot —
    // see the SINGLE editor below); this is now reached by turning Textshot
    // off over the limit, or by growing an already-started thread. `minPosts` is floored at the thread's *current* size
    // (see the doc comment on computeThreadPosts) purely so this is safe to
    // reuse below for re-flowing an already-started thread too. Always turns
    // Auto Format on — this is always the "genuinely needs splitting" path,
    // never the manual "+" one.
    fun growTextIntoThread(fullText: String, floor: Int) {
        val oldMedia = threadPosts
        threadPosts = computeThreadPosts(fullText, minPosts = floor).mapIndexed { i, text ->
            ThreadPostState(TextFieldValue(text), images = oldMedia.getOrNull(i)?.images ?: emptyList(), video = oldMedia.getOrNull(i)?.video)
        }
        activeThreadIndex = threadPosts.lastIndex
        mode = ComposeMode.THREAD
        isBlogMode = false
        autoFormat = true
    }

    // Bumped whenever the editor is swapped out from under the person mid-
    // typing (see the auto-Textshot switch below) so focus/keyboard are put
    // back into the new field instead of being dropped with the old one.
    var refocusTick by remember { mutableStateOf(0) }
    LaunchedEffect(refocusTick) {
        if (refocusTick > 0) {
            withFrameNanos { }
            focusActiveField()
        }
    }

    /** The X on a thread divider: drops that post (text and media). Down to
     *  one post again, the draft goes back to being a plain post. */
    fun removeThreadPost(index: Int) {
        if (threadPosts.size <= 1 || index !in threadPosts.indices) return
        val list = threadPosts.toMutableList().also { it.removeAt(index) }
        if (list.size == 1 && list[0].video == null) {
            val only = list[0]
            singleText = TextFieldValue(only.text.text, TextRange(only.text.text.length))
            images = only.images
            threadPosts = listOf(ThreadPostState(TextFieldValue("")))
            activeThreadIndex = 0
            mode = ComposeMode.SINGLE
            refocusTick++
        } else {
            threadPosts = list
            activeThreadIndex = (if (activeThreadIndex >= index) activeThreadIndex - 1 else activeThreadIndex).coerceIn(0, list.lastIndex)
        }
    }

    // [keepValue] carries the exact text *and* caret through when typing/
    // pasting past the limit triggers this automatically; the Textshot
    // button itself leaves it null and re-seeds from whatever is current.
    fun switchToTextshot(keepValue: TextFieldValue? = null) {
        singleText = keepValue ?: run {
            val seed = if (mode == ComposeMode.THREAD) threadPosts.joinToString("") { it.text.text } else singleText.text
            TextFieldValue(seed)
        }
        isBlogMode = false
        mode = ComposeMode.TEXTSHOT
    }

    // Item 7: turning Textshot back off doesn't just dump the person back
    // into a single post that's silently over the limit — if the text won't
    // fit in one post any more, it goes straight into a thread with Auto
    // Format on, same as typing past the limit does anywhere else.
    fun disableTextshot() {
        emojiTokensToShortcodes()
        // The separate post text (hashtags etc.) isn't lost on the way out:
        // it joins the end of the text.
        val extra = textshotPostText.text.trim()
        if (extra.isNotEmpty()) {
            val joined = singleText.text.trimEnd() + (if (singleText.text.isBlank()) "" else "\n\n") + extra
            singleText = TextFieldValue(joined, TextRange(joined.length))
            textshotPostText = TextFieldValue("")
        }
        if (singleText.text.length > POST_CHAR_LIMIT) {
            growTextIntoThread(singleText.text, floor = 1)
        } else {
            mode = ComposeMode.SINGLE
        }
    }

    // Item 6: entering Blog from a work-in-progress thread used to just show
    // whatever stale text `singleText` still held from before the thread was
    // ever started — everything actually typed into post 2, 3, etc. was
    // effectively gone. Folding every post's real text into one blob (with a
    // blank line between each, so the original post breaks are still
    // visible) keeps all of it.
    // Item 5: unreachable for now — the Blog button itself is disabled below
    // until Blog mode is actually finished — but left intact so re-wiring it
    // back up later is just re-enabling that button.
    fun enableBlogMode() {
        if (mode == ComposeMode.TEXTSHOT) emojiTokensToShortcodes()
        val seed = if (mode == ComposeMode.THREAD) threadPosts.joinToString("\n\n") { it.text.text.trimEnd() } else singleText.text
        // Whatever was typed becomes the blog's body, one paragraph per
        // blank-line-separated chunk; attached images follow it.
        val paragraphs = seed.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        blogRows = (paragraphs.map { BlogEditorRow(nextBlogRowId(), text = TextFieldValue(it)) } +
            images.map { BlogEditorRow(nextBlogRowId(), kind = com.mediaviewer.model.BlogRowKind.IMAGE, imageUri = it) })
            .ifEmpty { listOf(BlogEditorRow(nextBlogRowId())) }
        images = emptyList()
        blogSelected = -2
        isBlogMode = true
        mode = ComposeMode.BLOG
        refocusTick++
    }

    /** Blog → plain post: the body text comes back as the post's text. */
    fun disableBlogMode() {
        val body = blogRows.filter { it.kind != com.mediaviewer.model.BlogRowKind.IMAGE }.joinToString("\n\n") { it.text.text.trim() }.trim()
        val text = listOf(blogTitle.text.trim(), body).filter { it.isNotEmpty() }.joinToString("\n\n")
        images = blogRows.mapNotNull { it.imageUri }.take(MAX_IMAGES)
        isBlogMode = false
        if (text.length > POST_CHAR_LIMIT) {
            singleText = TextFieldValue(text)
            switchToTextshot(TextFieldValue(text))
        } else {
            singleText = TextFieldValue(text, TextRange(text.length))
            mode = ComposeMode.SINGLE
        }
    }

    // ── Blog row editing ─────────────────────────────────────────────────
    fun updateBlogRow(index: Int, transform: (BlogEditorRow) -> BlogEditorRow) {
        blogRows = blogRows.toMutableList().also { list -> list.getOrNull(index)?.let { list[index] = transform(it) } }
    }
    /** Typing Enter in a body row starts a new paragraph row there. */
    fun onBlogRowText(index: Int, value: TextFieldValue) {
        val nl = value.text.indexOf('\n')
        if (nl < 0) { updateBlogRow(index) { it.copy(text = value) }; return }
        val before = value.text.substring(0, nl)
        val after = value.text.substring(nl + 1)
        val current = blogRows.getOrNull(index) ?: return
        val newRow = BlogEditorRow(nextBlogRowId(), text = TextFieldValue(after, TextRange(0)), align = current.align)
        blogRows = blogRows.toMutableList().also {
            it[index] = current.copy(text = TextFieldValue(before, TextRange(before.length)))
            it.add(index + 1, newRow)
        }
        blogSelected = index + 1
        blogFocusTarget = newRow.id
    }
    /** An emptied text row goes away once you leave it (there's always at
     *  least one row). */
    fun pruneEmptyBlogRows(keepIndex: Int) {
        val keepId = blogRows.getOrNull(keepIndex)?.id
        val pruned = blogRows.filter { r ->
            r.id == keepId || r.kind == com.mediaviewer.model.BlogRowKind.IMAGE || r.text.text.isNotEmpty()
        }.ifEmpty { listOf(BlogEditorRow(nextBlogRowId())) }
        if (pruned.size != blogRows.size) {
            blogRows = pruned
            blogSelected = pruned.indexOfFirst { it.id == keepId }.let { if (it < 0) blogSelected.coerceAtMost(pruned.lastIndex) else it }
        }
    }
    fun cycleBlogAlignment() {
        if (blogSelected < 0) return
        updateBlogRow(blogSelected) {
            it.copy(align = when (it.align) {
                com.mediaviewer.model.LeafletAlign.START -> com.mediaviewer.model.LeafletAlign.CENTER
                com.mediaviewer.model.LeafletAlign.CENTER -> com.mediaviewer.model.LeafletAlign.END
                com.mediaviewer.model.LeafletAlign.END -> com.mediaviewer.model.LeafletAlign.START
            })
        }
    }
    fun setBlogRowKind(kind: com.mediaviewer.model.BlogRowKind) {
        if (blogSelected < 0) return
        updateBlogRow(blogSelected) { if (it.kind == com.mediaviewer.model.BlogRowKind.IMAGE) it else it.copy(kind = kind) }
    }

    // Tapping an emoji in the menu drops it into the Textshot text at the caret
    // (replacing any selection), like typing one.
    fun insertEmoji(entry: EmojiEntry) {
        val t = singleText
        val start = t.selection.min.coerceIn(0, t.text.length)
        val end = t.selection.max.coerceIn(start, t.text.length)
        val token = emojiStore.charFor(entry).toString()
        singleText = TextFieldValue(t.text.substring(0, start) + token + t.text.substring(end), TextRange(start + token.length))
    }

    // The raccoon button: keyboard -> emoji menu -> back to the keyboard.
    fun toggleEmojiPanel() {
        if (emojiPanelOpen) {
            emojiPanelOpen = false
            emojiTabEditing = false
            focusActiveField()
            keyboardController?.show()
        } else {
            emojiPanelOpen = true
            keyboardController?.hide()
        }
    }

    // ── Media pickers (Android Photo Picker — no storage permission
    // needed). One button picks either images or a single video, per spec:
    // "only one video, or up to 10 images, but not both". ────────────────
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val videoPick = uris.firstOrNull { isVideoUri(context, it) }
        if (mode == ComposeMode.THREAD) {
            // Item 2: attaches to whichever post is currently selected/being
            // typed in — each post in a thread carries its own up-to-10-
            // images-or-1-video media, independent of every other post's.
            val idx = activeThreadIndex.coerceIn(0, threadPosts.lastIndex)
            threadPosts = threadPosts.toMutableList().also { list ->
                val post = list[idx]
                list[idx] = if (videoPick != null) {
                    post.copy(video = videoPick, images = emptyList())
                } else {
                    val room = (MAX_IMAGES - post.images.size).coerceAtLeast(0)
                    post.copy(images = (post.images + uris.filterNot { it in post.images }.take(room)).take(MAX_IMAGES))
                }
            }
        } else if (videoPick != null) {
            videoUri = videoPick
            videoThumbUri = null
            images = emptyList()
            if (mode != ComposeMode.TEXTSHOT) mode = ComposeMode.VIDEO
        } else if (mode != ComposeMode.TEXTSHOT) {
            val room = (MAX_IMAGES - images.size).coerceAtLeast(0)
            images = (images + uris.filterNot { it in images }.take(room)).take(MAX_IMAGES)
        }
    }
    // Item 12: blog images (no videos) land right after the selected row,
    // in picked order.
    val blogImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        val picked = uris.filterNot { isVideoUri(context, it) }
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        val at = (blogSelected + 1).coerceIn(0, blogRows.size)
        val newRows = picked.map { BlogEditorRow(nextBlogRowId(), kind = com.mediaviewer.model.BlogRowKind.IMAGE, imageUri = it) }
        blogRows = blogRows.toMutableList().also { it.addAll(at, newRows) }
        blogSelected = at + newRows.size - 1
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
        ComposeMode.THREAD -> threadPosts.getOrNull(activeThreadIndex)?.text?.text?.length.orZero() to POST_CHAR_LIMIT
        ComposeMode.TEXTSHOT -> if (textshotPostFocused) textshotPostText.text.length to POST_CHAR_LIMIT
            else singleText.text.length to Int.MAX_VALUE
        // Item 10: "the character indicator shouldn't have a limit" — same
        // unlimited treatment as Textshot above.
        ComposeMode.REVIEW -> singleText.text.length to Int.MAX_VALUE
        ComposeMode.SINGLE -> singleText.text.length to POST_CHAR_LIMIT
        ComposeMode.BLOG -> when (blogSelected) {
            -2 -> blogTitle.text.length to 300
            -1 -> blogDescription.text.length to 3000
            else -> (blogRows.getOrNull(blogSelected)?.text?.text?.length ?: 0) to Int.MAX_VALUE
        }
    }

    val canPost = when (mode) {
        ComposeMode.VIDEO -> videoUri != null && (videoTitle.text.length + videoDescription.text.length) <= POST_CHAR_LIMIT
        ComposeMode.THREAD -> threadPosts.all { it.text.text.length <= POST_CHAR_LIMIT } &&
            threadPosts.any { it.text.text.isNotBlank() || it.images.isNotEmpty() || it.video != null }
        ComposeMode.TEXTSHOT -> singleText.text.isNotBlank()
        // Item 10: a rating is required (the person must pick 0.5–5 stars),
        // the written review itself is optional — matches the spec ("pick
        // a rating and optionally type out a review").
        ComposeMode.REVIEW -> reviewTarget != null && reviewRating > 0
        ComposeMode.SINGLE -> singleText.text.isNotBlank() && singleText.text.length <= POST_CHAR_LIMIT
        ComposeMode.BLOG -> blogTitle.text.isNotBlank() && blogTitle.text.length <= 300 &&
            blogRows.any { it.kind == com.mediaviewer.model.BlogRowKind.IMAGE || it.text.text.isNotBlank() }
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
                // Item 2: no more "x/n" counter suffix appended to the real
                // posted text — trimEnd() still matters on its own though,
                // since the lossless chunker (see computeThreadPosts) can
                // leave a trailing space right at a post's own break point.
                posts = threadPosts.map { post ->
                    ThreadPostDraft(text = post.text.text.trimEnd(), images = post.images, video = post.video)
                }
            )
            ComposeMode.TEXTSHOT -> ComposePostDraft(
                mode = ComposeMode.TEXTSHOT, textshotText = singleText.text,
                textshotPostText = textshotPostText.text.trim()
            )
            ComposeMode.REVIEW -> ComposePostDraft(
                mode = ComposeMode.REVIEW,
                posts = listOf(ThreadPostDraft(text = singleText.text)),
                reviewTarget = reviewTarget, reviewRating = reviewRating,
                reviewContainsSpoilers = reviewContainsSpoilers
            )
            ComposeMode.SINGLE -> ComposePostDraft(
                mode = ComposeMode.SINGLE,
                posts = listOf(ThreadPostDraft(text = singleText.text, images = images, video = null))
            )
            ComposeMode.BLOG -> ComposePostDraft(
                mode = ComposeMode.BLOG,
                blog = com.mediaviewer.model.BlogDraft(
                    title = blogTitle.text.trim(),
                    description = blogDescription.text.trim(),
                    rows = blogRows.map { r ->
                        com.mediaviewer.model.BlogRowDraft(
                            kind = r.kind, text = r.text.text.trimEnd(), align = r.align,
                            imageUri = r.imageUri, existingBlob = r.existingBlob, existingUrl = r.existingUrl,
                            imageWidth = r.imageWidth, imageHeight = r.imageHeight, alt = r.alt
                        )
                    },
                    editingUri = editBlog?.first?.editingUri
                )
            )
        }
        val selfLabels = listOfNotNull(adultLabel?.value, if (graphicMedia) GRAPHIC_MEDIA_LABEL else null)
        // Reviews are Popfeed records, not Bluesky posts — labels don't apply.
        onSubmit(if (mode == ComposeMode.REVIEW || selfLabels.isEmpty()) draft else draft.copy(selfLabels = selfLabels))
        focusManager.clearFocus()
    }

    // Item 9: this is now always a plain label — Thread/Textshot are no
    // longer chosen from inside the status bubble (see StatusBubble below),
    // only from the dedicated bottom-bar buttons.
    val statusLabel = when {
        mode == ComposeMode.REVIEW -> "Review"
        mode == ComposeMode.BLOG -> if (editBlog != null) "Editing Blog" else "Blog"
        mode == ComposeMode.VIDEO -> "Video"
        mode == ComposeMode.THREAD -> "Thread"
        mode == ComposeMode.TEXTSHOT -> "Textshot"
        isBlogMode -> "Blog"
        images.isNotEmpty() -> "Media Post"
        singleText.text.isNotBlank() -> "Text Post"
        else -> "New Post"
    }

    // zIndex 10.5: above every other page and the loading overlays (10),
    // but BELOW the camera-notch bubble (11) — it was 20, which buried the
    // notch on the posting page.
    Box(
        Modifier.fillMaxSize().zIndex(10.5f)
            .background(dimSpaceColor(dominantColor))
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
        // Height of the floating bottom bar (counter row + button row), measured
        // below. The text scrolls *behind* the bar, so this is used to leave
        // matching room at the end of the content and to keep the caret
        // above the bar while typing.
        var bottomBarHeight by remember { mutableStateOf(84.dp) }
        val barDensity = LocalDensity.current
        // Live glass backdrop for the bottom bar's buttons — same "record what's
        // drawn, read it back through a blurred glass panel" system the rest of
        // the app uses (see GlassBackdrop). Only the scrolling content is
        // recorded; nothing inside it reads [backdrop] (the bottom bar is a
        // sibling, not a child), which would otherwise recurse mid-recording.
        val backdropLayer = rememberGraphicsLayer()
        var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
        val backdrop = if (liquidGlass) remember(backdropLayer) { GlassBackdrop(backdropLayer) { backdropOrigin } } else null
        // The draft blurs softly behind a "remove this?" question.
        val pageBlur by androidx.compose.animation.core.animateDpAsState(if (confirmRemoval != null) 10.dp else 0.dp, label = "composeBlur")
        Box(Modifier.fillMaxSize().then(if (pageBlur > 0.dp) Modifier.blur(pageBlur) else Modifier)) {
            // ── Scrollable content ──────────────────────────────────────
            // Fills the whole screen down to the keyboard/nav bar (instead
            // of stopping at the top of the button bar) so text scrolls
            // visibly behind the buttons rather than being cut off in a
            // hard edge above them.
            Box(
                Modifier.fillMaxSize()
                    .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                    .drawWithContent {
                        if (liquidGlass) backdropLayer.record { this@drawWithContent.drawContent() }
                        drawContent()
                    }
            ) {
            // Dim profile color + stars behind the draft (recorded, so the
            // bottom bar's glass blurs them).
            SpaceSky(dominantColor, Modifier.matchParentSize())
            CompositionLocalProvider(LocalBottomBarClearance provides bottomBarHeight) {
            Column(
                Modifier.fillMaxSize().then(bottomInsetModifier)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
            ) {
                Spacer(Modifier.height(rememberTopCutoutClearance()))
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
                        label = if (editBlog != null && mode == ComposeMode.BLOG) "Save" else "Post",
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

                // Item 10: cover/title/rating row — sits between the author
                // row and the text field, only while reviewing. Kept
                // deliberately short (the row itself, not the cover) since
                // spec calls this "somewhat short" — the cover is sized off
                // that row height rather than the other way around.
                if (mode == ComposeMode.REVIEW && reviewTarget != null) {
                    ReviewTargetRow(
                        target = reviewTarget, rating = reviewRating,
                        liquidGlass = liquidGlass, tint = dominantColor,
                        onRatingChange = { reviewRating = it }
                    )
                    Spacer(Modifier.height(12.dp))
                }

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
                    // Item 3: all of that re-flowing only happens when Auto
                    // Format is on; off, each post is just its own field —
                    // typing in post 2 stays in post 2 instead of getting
                    // pulled back into post 1 the moment it could still fit
                    // there.
                    ComposeMode.THREAD -> {
                        threadPosts.forEachIndexed { index, post ->
                            // Item 2: the "Post x/n" divider and the "x/n"
                            // counter (both here and in the actual posted
                            // text — see handlePost) are gone entirely now.
                            // A plain, unlabeled divider still separates one
                            // post from the next visually — skipped before
                            // the very first post, same as SINGLE mode has
                            // no divider above its own field.
                            if (index > 0) {
                                // The divider's X (right end) removes the post
                                // below it — asking first if it has anything in it.
                                ThreadPostDivider(tint = dominantColor, onRemove = {
                                    val p = threadPosts.getOrNull(index)
                                    if (p == null || (p.text.text.isBlank() && p.images.isEmpty() && p.video == null)) removeThreadPost(index)
                                    else confirmRemoval = RemovalRequest(
                                        title = "Remove this post?",
                                        message = "It'll be taken out of the thread, along with anything attached to it.",
                                        preview = p.images.firstOrNull()
                                    ) { removeThreadPost(index) }
                                })
                            }
                            GrowingTextField(
                                value = post.text,
                                onValueChange = { newVal ->
                                    if (!autoFormat) {
                                        threadPosts = threadPosts.toMutableList().also {
                                            it[index] = post.copy(text = capBudget(newVal, POST_CHAR_LIMIT))
                                        }
                                        activeThreadIndex = index
                                    } else if (newVal.text == post.text.text) {
                                        // Only the selection/caret (or the
                                        // keyboard's composing region) moved —
                                        // nothing to re-flow. Re-flowing here
                                        // used to collapse every selection
                                        // back to a caret, so text couldn't be
                                        // selected with Auto Format on.
                                        threadPosts = threadPosts.toMutableList().also { it[index] = post.copy(text = newVal) }
                                        activeThreadIndex = index
                                    } else {
                                        val priorLength = threadPosts.take(index).sumOf { it.text.text.length }
                                        val absoluteCaret = priorLength + newVal.selection.end
                                        val fullText = threadPosts.mapIndexed { i, v -> if (i == index) newVal.text else v.text.text }
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

                                        // Each post keeps whatever media it already had — a
                                        // reflow only ever moves text between posts, never media.
                                        threadPosts = chunks.mapIndexed { i, text ->
                                            val old = threadPosts.getOrNull(i)
                                            val tfv = when {
                                                // The edit stayed inside this post: keep the
                                                // field's own value (selection + IME state).
                                                i == index && caretChunk == index && text == newVal.text -> newVal
                                                i == caretChunk -> TextFieldValue(text, TextRange(remainingCaret))
                                                else -> TextFieldValue(text)
                                            }
                                            ThreadPostState(tfv, images = old?.images ?: emptyList(), video = old?.video)
                                        }
                                        activeThreadIndex = caretChunk
                                    }
                                },
                                placeholder = if (index == 0) "Start a thread…" else "Continue the thread…",
                                onFocus = { activeThreadIndex = index },
                                focusRequester = threadFocusRequesters.getOrNull(index)
                            )
                            // Item 2: each post displays its own attached
                            // media right underneath its own text field —
                            // up to 10 images or exactly one video per post.
                            if (post.video != null) {
                                Spacer(Modifier.height(8.dp))
                                ThreadVideoPreview(uri = post.video, onRemove = {
                                    confirmRemoval = RemovalRequest("Remove this video?", "It'll be taken off this post.") {
                                        threadPosts = threadPosts.toMutableList().also { list ->
                                            list.getOrNull(index)?.let { list[index] = it.copy(video = null) }
                                        }
                                    }
                                })
                            } else if (post.images.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                ImageGrid(
                                    images = post.images,
                                    onRemove = { uri ->
                                        confirmRemoval = RemovalRequest("Remove this image?", "It'll be taken off this post.", preview = uri) {
                                            threadPosts = threadPosts.toMutableList().also { list ->
                                                list.getOrNull(index)?.let { list[index] = it.copy(images = it.images - uri) }
                                            }
                                        }
                                    },
                                    onMove = { from, to ->
                                        threadPosts = threadPosts.toMutableList().also { list ->
                                            list.getOrNull(index)?.let { list[index] = it.copy(images = it.images.moved(from, to)) }
                                        }
                                    }
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    ComposeMode.TEXTSHOT -> {
                        // The post's own text (hashtags, a caption…) — posted
                        // as regular text alongside the image. Its own field
                        // and focus target, so the auto-switch into Textshot
                        // (typing past the limit) keeps typing flowing into
                        // the Textshot text below, never into this one.
                        HubDivider("Post Text")
                        GrowingTextField(
                            value = textshotPostText,
                            onValueChange = { textshotPostText = capBudget(it, POST_CHAR_LIMIT) },
                            placeholder = "Post text (optional) — #hashtags, a caption…",
                            onFocus = { textshotPostFocused = true },
                            focusRequester = textshotPostFocus,
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                        )
                        HubDivider("Textshot")
                        GrowingTextField(
                            value = singleText,
                            onValueChange = { singleText = it },
                            placeholder = "What's on your mind?",
                            onFocus = { textshotPostFocused = false },
                            focusRequester = singleFocusRequester,
                            emojiStore = emojiStore
                        )
                        Spacer(Modifier.height(14.dp))
                        TextshotPreview(
                            text = singleText.text.ifBlank { "Preview" },
                            liquidGlass = liquidGlass, tint = dominantColor,
                            emojiStore = emojiStore
                        )
                    }

                    ComposeMode.REVIEW -> {
                        GrowingTextField(
                            value = singleText,
                            onValueChange = { singleText = it },
                            placeholder = "Write a review (optional)…",
                            focusRequester = singleFocusRequester
                        )
                    }

                    ComposeMode.SINGLE -> {
                        GrowingTextField(
                            value = singleText,
                            onValueChange = { newVal ->
                                // Overflowing the limit here now switches
                                // to Textshot by default (long text becomes
                                // one image post instead of a thread). A
                                // thread is still one tap away via "+", or
                                // by turning Textshot off again (see
                                // disableTextshot). Item 6: Blog is long-
                                // form on purpose, so it's left alone here
                                // — going over the limit while blogging
                                // doesn't interrupt typing; it's only
                                // handled when the person actually taps
                                // "+" (see startThreadFromSingle).
                                if (!isBlogMode && newVal.text.length > POST_CHAR_LIMIT) {
                                    switchToTextshot(keepValue = newVal)
                                    refocusTick++
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
                            ImageGrid(
                                images = images,
                                onRemove = { uri ->
                                    confirmRemoval = RemovalRequest("Remove this image?", "It'll be taken off your post.", preview = uri) {
                                        images = images - uri
                                    }
                                },
                                onMove = { from, to -> images = images.moved(from, to) }
                            )
                        }
                    }

                    // Item 12: Blog — title, then description, then the body
                    // rows. Tapping a row selects it for the bottom bar's
                    // header/alignment/image buttons.
                    ComposeMode.BLOG -> {
                        LaunchedEffect(blogFocusTarget) {
                            val id = blogFocusTarget ?: return@LaunchedEffect
                            withFrameNanos { }
                            runCatching { blogRequester(id).requestFocus() }
                            blogFocusTarget = null
                        }
                        GrowingTextField(
                            value = blogTitle,
                            onValueChange = { v -> blogTitle = if (v.text.contains('\n')) v.copy(text = v.text.replace("\n", " ")) else v },
                            placeholder = "Title",
                            onFocus = { blogSelected = -2 },
                            focusRequester = blogTitleFocus,
                            textStyle = TextStyle(color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                        )
                        Spacer(Modifier.height(6.dp))
                        GrowingTextField(
                            value = blogDescription,
                            onValueChange = { blogDescription = it },
                            placeholder = "Description / tagline",
                            onFocus = { blogSelected = -1 },
                            focusRequester = blogDescriptionFocus,
                            textStyle = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, lineHeight = 21.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.12f))
                        blogRows.forEachIndexed { index, row ->
                            androidx.compose.runtime.key(row.id) {
                                val selected = index == blogSelected
                                val textAlign = when (row.align) {
                                    com.mediaviewer.model.LeafletAlign.START -> androidx.compose.ui.text.style.TextAlign.Start
                                    com.mediaviewer.model.LeafletAlign.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
                                    com.mediaviewer.model.LeafletAlign.END -> androidx.compose.ui.text.style.TextAlign.End
                                }
                                val rowShape = RoundedCornerShape(10.dp)
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                        .clip(rowShape)
                                        .then(if (selected) Modifier.background(Color.White.copy(alpha = 0.05f)) else Modifier)
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    if (row.kind == com.mediaviewer.model.BlogRowKind.IMAGE) {
                                        BlogEditorImage(
                                            model = row.imageUri ?: row.existingUrl,
                                            align = row.align, selected = selected,
                                            onSelect = { focusManager.clearFocus(); blogSelected = index },
                                            onRemove = {
                                                blogRows = blogRows.filterNot { it.id == row.id }.ifEmpty { listOf(BlogEditorRow(nextBlogRowId())) }
                                                blogSelected = blogSelected.coerceAtMost(blogRows.lastIndex)
                                            }
                                        )
                                    } else {
                                        val style = when (row.kind) {
                                            com.mediaviewer.model.BlogRowKind.H1 -> TextStyle(color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, textAlign = textAlign)
                                            com.mediaviewer.model.BlogRowKind.H2 -> TextStyle(color = Color.White, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, textAlign = textAlign)
                                            com.mediaviewer.model.BlogRowKind.H3 -> TextStyle(color = Color.White, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, textAlign = textAlign)
                                            else -> TextStyle(color = Color.White, fontSize = 16.sp, lineHeight = 22.sp, textAlign = textAlign)
                                        }
                                        GrowingTextField(
                                            value = row.text,
                                            onValueChange = { onBlogRowText(index, it) },
                                            placeholder = if (index == 0 && blogRows.size == 1) "Write your blog…" else "",
                                            onFocus = {
                                                if (blogSelected != index) pruneEmptyBlogRows(index)
                                                blogSelected = blogRows.indexOfFirst { it.id == row.id }.coerceAtLeast(0)
                                            },
                                            focusRequester = blogRequester(row.id),
                                            textStyle = style
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Room for the floating bottom bar so the last field/image
                // row can always be scrolled up clear of it.
                Spacer(Modifier.height(bottomBarHeight + 12.dp))
            }
            }
            }

            // ── Fixed bottom bar — rides up above the keyboard via
            // imePadding() so it always sits directly on top of it.
            // Item 5: the char counter moved up to its own row, far
            // right, so the "+" (add post to thread) button can sit on
            // the far right of the button row underneath it instead of
            // squeezed in next to the counter text. ────────────────────
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(bottomInsetModifier)
                    .onSizeChanged { bottomBarHeight = with(barDensity) { it.height.toDp() } }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Item 12: Review mode has no attach-image/Blog/Textshot/
                // new-thread row at all (none of those apply to a review),
                // so the char counter drops down to share the one remaining
                // row with the new "Mark as Spoiler" toggle instead of
                // sitting alone above an otherwise-empty row.
                if (mode == ComposeMode.REVIEW) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // Item 12: same lexicon field postPopfeedReview
                        // already sends — social.popfeed.feed.review's own
                        // "containsSpoilers" boolean (see review.json).
                        TextToggleButton(
                            label = "Mark as Spoiler",
                            liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                            selected = reviewContainsSpoilers,
                            onClick = { reviewContainsSpoilers = !reviewContainsSpoilers }
                        )
                        Spacer(Modifier.weight(1f))
                        val (used, limit) = activeBudget
                        val overLimit = used > limit
                        Text(
                            if (limit == Int.MAX_VALUE) "$used" else "$used/$limit",
                            color = if (overLimit) Color(0xFFE0245E) else DimGray,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        // Item 12: header-type buttons for the selected blog
                        // row, as short as the counter beside them.
                        if (mode == ComposeMode.BLOG) {
                            val selectedKind = blogRows.getOrNull(blogSelected)?.kind
                            val rowEditable = blogSelected >= 0 && selectedKind != com.mediaviewer.model.BlogRowKind.IMAGE
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((label, kind) in listOf(
                                    "Text" to com.mediaviewer.model.BlogRowKind.TEXT,
                                    "H1" to com.mediaviewer.model.BlogRowKind.H1,
                                    "H2" to com.mediaviewer.model.BlogRowKind.H2,
                                    "H3" to com.mediaviewer.model.BlogRowKind.H3
                                )) {
                                    BlogKindChip(
                                        label = label, selected = rowEditable && selectedKind == kind, enabled = rowEditable,
                                        tint = dominantColor, onClick = { setBlogRowKind(kind) }
                                    )
                                }
                            }
                        }
                        val (used, limit) = activeBudget
                        val overLimit = used > limit
                        Text(
                            if (limit == Int.MAX_VALUE) "$used" else "$used/$limit",
                            color = if (overLimit) Color(0xFFE0245E) else DimGray,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // Item 1: one consistent gap between every button in
                        // this cluster instead of a mix of a 6dp Spacer
                        // between some and none between others.
                        // Scrolls sideways as a safety net: with Labels and
                        // (in thread mode) Auto Format the row can be wider
                        // than a small phone. The "+" stays pinned at right.
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassCircleButton(
                                icon = Icons.Default.Image, contentDescription = if (mode == ComposeMode.BLOG) "Add image" else "Attach image or video",
                                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop, size = 40.dp,
                                enabled = mode != ComposeMode.TEXTSHOT && mode != ComposeMode.REVIEW &&
                                    (mode != ComposeMode.THREAD || threadPosts.getOrNull(activeThreadIndex)?.let { it.video == null && it.images.size < MAX_IMAGES } != false),
                                onClick = {
                                    if (mode == ComposeMode.BLOG) {
                                        blogImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    } else {
                                        mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                    }
                                }
                            )
                            // Item 2: Blog and Textshot are text buttons, not
                            // icons — dim unless the status they set is the
                            // one currently active.
                            // Item 5: Blog isn't finished yet — temporarily
                            // greyed out and unclickable rather than opening
                            // a mode that doesn't have anywhere to go.
                            TextToggleButton(
                                label = "Blog",
                                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                                // Editing an existing blog stays a blog.
                                enabled = mode != ComposeMode.VIDEO && mode != ComposeMode.REVIEW && editBlog == null,
                                selected = mode == ComposeMode.BLOG,
                                onClick = { if (mode == ComposeMode.BLOG) disableBlogMode() else enableBlogMode() }
                            )
                            TextToggleButton(
                                label = "Textshot",
                                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                                enabled = mode != ComposeMode.VIDEO && mode != ComposeMode.REVIEW && mode != ComposeMode.BLOG,
                                selected = mode == ComposeMode.TEXTSHOT,
                                onClick = {
                                    if (mode == ComposeMode.TEXTSHOT) {
                                        disableTextshot()
                                    } else {
                                        switchToTextshot()
                                    }
                                }
                            )
                            // Bluesky content-warning self-labels. Lit whenever
                            // any label is currently applied. Focus is dropped
                            // first so the keyboard is out of the way of the
                            // centered popup.
                            TextToggleButton(
                                label = "Labels",
                                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                                selected = adultLabel != null || graphicMedia,
                                onClick = {
                                    focusManager.clearFocus()
                                    labelsOpen = true
                                }
                            )
                            // Item 3: Auto Format is thread-mode exclusive —
                            // it only appears once a thread actually exists,
                            // right after Textshot.
                            if (mode == ComposeMode.THREAD) {
                                TextToggleButton(
                                    label = "Auto Format",
                                    liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                                    selected = autoFormat,
                                    onClick = {
                                        autoFormat = !autoFormat
                                        if (autoFormat) {
                                            // Re-flowing back on immediately
                                            // packs whatever's there right
                                            // now, same greedy fill as typing
                                            // normally would have produced.
                                            // Media stays with its own post
                                            // by position; text is the only
                                            // thing that actually re-flows.
                                            val oldMedia = threadPosts
                                            val fullText = threadPosts.joinToString("") { it.text.text }
                                            threadPosts = computeThreadPosts(fullText, minPosts = threadPosts.size)
                                                .mapIndexed { i, text ->
                                                    ThreadPostState(TextFieldValue(text), images = oldMedia.getOrNull(i)?.images ?: emptyList(), video = oldMedia.getOrNull(i)?.video)
                                                }
                                            activeThreadIndex = activeThreadIndex.coerceAtMost(threadPosts.lastIndex)
                                        }
                                    }
                                )
                            }
                        }
                        if (mode == ComposeMode.TEXTSHOT) {
                            // Textshot mode: no new-thread button — a raccoon
                            // emoji button opens the custom emoji menu instead.
                            GlassEmojiButton(
                                emoji = "\uD83E\uDD9D",
                                contentDescription = if (emojiPanelOpen) "Show keyboard" else "Open emoji menu",
                                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop, size = 36.dp,
                                onClick = { toggleEmojiPanel() }
                            )
                        } else if (mode == ComposeMode.BLOG) {
                            // Item 12: in Blog mode the new-thread button is
                            // the selected row's alignment: left → centre → right.
                            val align = blogRows.getOrNull(blogSelected)?.align ?: com.mediaviewer.model.LeafletAlign.START
                            GlassCircleButton(
                                icon = when (align) {
                                    com.mediaviewer.model.LeafletAlign.START -> Icons.AutoMirrored.Filled.FormatAlignLeft
                                    com.mediaviewer.model.LeafletAlign.CENTER -> Icons.Default.FormatAlignCenter
                                    com.mediaviewer.model.LeafletAlign.END -> Icons.AutoMirrored.Filled.FormatAlignRight
                                },
                                contentDescription = "Change alignment",
                                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop, size = 36.dp,
                                enabled = blogSelected >= 0,
                                onClick = { cycleBlogAlignment() }
                            )
                        } else {
                            GlassCircleButton(
                                icon = Icons.Default.Add, contentDescription = "Add post to thread",
                                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop, size = 36.dp,
                                enabled = mode != ComposeMode.VIDEO && mode != ComposeMode.REVIEW,
                                onClick = {
                                    if (mode == ComposeMode.THREAD) addThreadPost() else startThreadFromSingle()
                                }
                            )
                        }
                    }
                }
            }

            // ── Emoji menu — takes the keyboard's place (see the state block
            // near the top of this function). When a tab is being renamed it
            // is only the tab row, sitting right on top of the keyboard.
            if (mode == ComposeMode.TEXTSHOT && emojiPanelOpen) {
                EmojiPanel(
                    store = emojiStore,
                    height = emojiPanelHeight,
                    compact = emojiTabEditing,
                    liquidGlass = liquidGlass,
                    tint = dominantColor,
                    onPickEmoji = { insertEmoji(it) },
                    onEditingChange = { emojiTabEditing = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = if (emojiTabEditing) imeDp else 0.dp)
                )
            }
        }

        confirmRemoval?.let { request ->
            RemovalConfirmPopup(
                request = request, liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                onConfirm = { confirmRemoval = null; request.onConfirm() },
                onDismiss = { confirmRemoval = null }
            )
        }

        if (labelsOpen) {
            ContentLabelsPopup(
                adult = adultLabel, graphic = graphicMedia,
                liquidGlass = liquidGlass, tint = dominantColor,
                // Only one Adult Content option at a time; tapping the
                // selected one clears it. Graphic Media is independent.
                onAdultChange = { picked -> adultLabel = if (adultLabel == picked) null else picked },
                onGraphicChange = { graphicMedia = it },
                onDismiss = { labelsOpen = false }
            )
        }
    }
}

// ── Sub-components ──────────────────────────────────────────────────────

/** Compact, centered version of Bluesky's "Add a content warning" menu.
 *  Same options in the same order as Bluesky, except every option's
 *  description is visible at once on its right instead of only under the
 *  selected one. Rendered in-place (no separate Dialog window) like the
 *  app's other popups. */
@Composable
private fun ContentLabelsPopup(
    adult: AdultContentLabel?, graphic: Boolean,
    liquidGlass: Boolean, tint: Color,
    onAdultChange: (AdultContentLabel) -> Unit,
    onGraphicChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // Registered after the composer's own BackHandler, so Back closes this
    // popup first instead of closing the whole composer.
    BackHandler(onBack = onDismiss)
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(18.dp)
    val base = Color(red = tint.red * 0.22f, green = tint.green * 0.22f, blue = tint.blue * 0.22f, alpha = 0.94f)

    Box(
        Modifier.fillMaxSize().zIndex(5f).background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 400.dp).fillMaxWidth()
                .clip(shape).background(base).glassPanel(liquidGlass, tint = tint, shape = shape)
                // Swallow taps on the panel itself so only the scrim dismisses.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Text(
                "Add a content warning", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            HubDivider("Adult Content")
            AdultContentLabel.values().forEach { option ->
                LabelOptionRow(
                    title = option.title, description = option.description,
                    checked = adult == option, onClick = { onAdultChange(option) }
                )
            }

            HubDivider("Other")
            LabelOptionRow(
                title = "Graphic Media", description = GRAPHIC_MEDIA_DESCRIPTION,
                checked = graphic, onClick = { onGraphicChange(!graphic) }
            )

            Spacer(Modifier.height(10.dp))
            // Item 3: a color-adaptive glass/outline button matching every
            // other button in this composer (see PostButton/TextToggleButton
            // above) instead of a flat, un-themed Bluesky-blue button that
            // didn't fit the rest of the app's look.
            val doneShape = RoundedCornerShape(19.dp)
            val doneMod = Modifier.fillMaxWidth().height(38.dp).clip(doneShape).clickable { tap(); onDismiss() }
            if (liquidGlass) {
                LiquidGlassSurface(doneMod, shape = doneShape, tint = tint) {
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                        Text("Done", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Box(
                    doneMod.background(Color.White.copy(0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), doneShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Done", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** One row of [ContentLabelsPopup]: a checkbox, then the option's title and
 *  description flowing as one continuous line of text — the description
 *  starts right after the title's own text (Item 3), instead of every
 *  row's description being pinned to the same fixed-width column no matter
 *  how short or long that row's title happened to be. */
@Composable
private fun LabelOptionRow(title: String, description: String, checked: Boolean, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    val boxShape = RoundedCornerShape(5.dp)
    val blue = Color(0xFF1083FE)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable { tap(); onClick() }
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(18.dp).clip(boxShape)
                .background(if (checked) blue else Color.White.copy(alpha = 0.06f))
                .border(1.dp, if (checked) blue else Color.White.copy(alpha = 0.35f), boxShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)) { append(title) }
                append(" ")
                withStyle(SpanStyle(color = DimGray, fontWeight = FontWeight.Normal, fontSize = 11.sp)) { append(description) }
            },
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Item 10: the cover/title/rating row shown between the author row and the
 *  text field while reviewing. Deliberately short (spec: "this row is
 *  somewhat short so the cover shouldn't be that big") — the cover's own
 *  size is derived from [rowHeight] rather than a fixed portrait size, so
 *  it always reads as a small thumbnail rather than a mini poster card. */
@Composable
private fun ReviewTargetRow(
    target: TitleSearchResult, rating: Int,
    liquidGlass: Boolean, tint: Color,
    onRatingChange: (Int) -> Unit
) {
    val rowHeight = 52.dp
    val shape = RoundedCornerShape(14.dp)
    @Composable
    fun Content() {
        Row(
            Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val coverShape = RoundedCornerShape(6.dp)
            Box(Modifier.fillMaxHeight().aspectRatio(2f / 3f).clip(coverShape).background(Color.White.copy(0.10f))) {
                if (target.posterUrl != null) {
                    AsyncImage(model = target.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                target.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            ReviewStarPicker(rating = rating, onRatingChange = onRatingChange)
        }
    }
    if (liquidGlass) {
        LiquidGlassSurface(Modifier.fillMaxWidth(), shape = shape, tint = tint) { Content() }
    } else {
        Box(Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(0.06f))) { Content() }
    }
}

/** Item 10: a tappable .5–5 star picker — each half of each star is its own
 *  tap target (left half = X.5, right half = X.0) so every half-star value
 *  is reachable, matching Popfeed's own 0–10 (half-star granularity) rating
 *  scale. Shows the numeric rating to the right, per spec. */
@Composable
private fun ReviewStarPicker(rating: Int, onRatingChange: (Int) -> Unit) {
    val tap = rememberHapticTap()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row {
            repeat(5) { i ->
                // rating is on Popfeed's 0–10 scale; each star covers 2
                // points (a left-half tap = 2*i+1, a right-half/full tap =
                // 2*i+2).
                val starFloor = i * 2
                val icon = when {
                    rating >= starFloor + 2 -> Icons.Filled.Star
                    rating == starFloor + 1 -> Icons.Filled.StarHalf
                    else -> Icons.Filled.StarBorder
                }
                Box(Modifier.size(22.dp)) {
                    Icon(icon, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.fillMaxSize())
                    // Two invisible tap targets stacked over the one icon —
                    // left half picks the half-star value, right half picks
                    // the full-star value.
                    Row(Modifier.matchParentSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null
                        ) { tap(); onRatingChange(starFloor + 1) })
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null
                        ) { tap(); onRatingChange(starFloor + 2) })
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (rating > 0) "${rating / 2f}" else "–",
            color = Color.White.copy(alpha = if (rating > 0) 0.9f else 0.4f),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

/** Last keyboard height seen (px), kept across composer sessions so the emoji
 *  menu can match the keyboard's size even the first time it is opened. */
private var rememberedKeyboardPx = 0

/** [GlassCircleButton]'s twin for an emoji glyph instead of an icon (the
 *  raccoon button in Textshot mode). Same size, glass and haptic tap. */
@Composable
private fun GlassEmojiButton(
    emoji: String,
    contentDescription: String,
    liquidGlass: Boolean, tint: Color,
    modifier: Modifier = Modifier, size: Dp = 36.dp,
    backdrop: GlassBackdrop? = null,
    onClick: () -> Unit
) {
    val tap = rememberHapticTap()
    val shape = CircleShape
    val clickMod = modifier.size(size).clip(shape)
        .semantics { this.contentDescription = contentDescription }
        .clickable(onClick = { tap(); onClick() })
    val fontSize = (size.value * 0.5f).sp
    if (liquidGlass) {
        LiquidGlassSurface(clickMod, shape = shape, tint = tint, backdrop = backdrop) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Text(emoji, fontSize = fontSize, lineHeight = fontSize)
            }
        }
    } else {
        Box(clickMod.background(Color.White.copy(0.10f)), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = fontSize, lineHeight = fontSize)
        }
    }
}

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
    // Live blur source — only passed for buttons that sit *outside* the
    // recorded content (the bottom bar).
    backdrop: GlassBackdrop? = null,
    onClick: () -> Unit
) {
    val tap = rememberHapticTap()
    val shape = CircleShape
    val clickMod = modifier.size(size).clip(shape).clickable(enabled = enabled, onClick = { tap(); onClick() })
    val alpha = if (enabled && selected) 1f else 0.35f
    if (liquidGlass) {
        LiquidGlassSurface(clickMod, shape = shape, tint = tint, backdrop = backdrop) {
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

/** Item 2: Blog/Textshot/Auto Format as text pills instead of icons — same
 *  dim-unless-selected treatment as GlassCircleButton above, just a label
 *  in a rounded glass pill rather than an icon in a circle. */
@Composable
private fun TextToggleButton(
    label: String,
    liquidGlass: Boolean, tint: Color,
    enabled: Boolean = true,
    selected: Boolean = true,
    backdrop: GlassBackdrop? = null,
    onClick: () -> Unit
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(14.dp)
    val clickMod = Modifier.clip(shape).clickable(enabled = enabled, onClick = { tap(); onClick() })
    val alpha = if (enabled && selected) 1f else 0.35f

    @Composable
    fun Content() {
        Text(
            label, color = Color.White.copy(alpha = alpha), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
    if (liquidGlass) {
        LiquidGlassSurface(clickMod, shape = shape, tint = tint, backdrop = backdrop) { Content() }
    } else {
        Box(clickMod.background(Color.White.copy(0.10f))) { Content() }
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
    label: String = "Post",
    liquidGlass: Boolean, tint: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(16.dp)
    val clickMod = modifier.clip(shape).clickable(enabled = enabled, onClick = { tap(); onClick() })

    @Composable
    fun Content() {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (submitting) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(
                    label, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    if (liquidGlass) {
        LiquidGlassSurface(clickMod, shape = shape, tint = tint) { Content() }
    } else {
        Box(clickMod.background(Color.White.copy(0.10f))) { Content() }
    }
}

/** Item 12: the short header-type chips beside the blog editor's counter. */
@Composable
private fun BlogKindChip(label: String, selected: Boolean, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.height(20.dp).clip(shape)
            .background(if (selected) tint.copy(alpha = 0.85f) else Color.White.copy(alpha = if (enabled) 0.12f else 0.05f))
            .clickable(enabled = enabled) { tap(); onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else 0.35f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 11.sp)
    }
}

/** Item 12: an image row in the blog editor — shown at its own aspect
 *  ratio (transparent PNGs stay transparent), aligned like the row, with a
 *  remove button while selected. */
@Composable
private fun BlogEditorImage(model: Any?, align: com.mediaviewer.model.LeafletAlign, selected: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    val tap = rememberHapticTap()
    val alignment = when (align) {
        com.mediaviewer.model.LeafletAlign.START -> Alignment.CenterStart
        com.mediaviewer.model.LeafletAlign.CENTER -> Alignment.Center
        com.mediaviewer.model.LeafletAlign.END -> Alignment.CenterEnd
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Box(
            Modifier.fillMaxWidth(0.92f).clip(RoundedCornerShape(12.dp))
                .then(if (selected) Modifier.border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(12.dp)) else Modifier)
                .clickable { tap(); onSelect() }
        ) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)).clickable { tap(); onRemove() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Close, contentDescription = "Remove image", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
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

/** How much of the screen's bottom the floating bar covers — text fields use
 *  it to keep the caret scrolled clear of the bar while typing. */
private val LocalBottomBarClearance = compositionLocalOf { 0.dp }

@OptIn(ExperimentalFoundationApi::class)
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
    // Textshot mode only: draws custom emoji (each stored as one private-use
    // character) as pictures over their spot in the text.
    emojiStore: EmojiStore? = null,
    // Item 12: blog rows (title/description/headers) use their own style.
    textStyle: TextStyle? = null
) {
    val fieldStyle = textStyle ?: TextStyle(color = Color.White, fontSize = 16.sp, lineHeight = 22.sp)
    // The scroll area now extends behind the floating bottom bar, so the
    // default "scroll the caret into view" would park it right under the
    // buttons. Ask for the caret's rect plus the bar's height instead.
    val clearancePx = with(LocalDensity.current) { LocalBottomBarClearance.current.toPx() }
    val bringIntoView = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    val emojiState = emojiStore?.state // read here so the field recomposes when emoji change
    val effectiveTransformation = if (emojiStore != null) {
        remember(emojiStore, emojiState) { emojiVisualTransformation(emojiStore) }
    } else visualTransformation
    LaunchedEffect(value.selection, value.text, layout, focused, clearancePx) {
        val l = layout ?: return@LaunchedEffect
        if (!focused || clearancePx <= 0f) return@LaunchedEffect
        val end = l.layoutInput.text.length
        val r = l.getCursorRect(value.selection.end.coerceIn(0, end))
        bringIntoView.bringIntoView(Rect(r.left, r.top, r.right, r.bottom + clearancePx))
    }
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 28.dp)) {
        BasicTextField(
            value = value, onValueChange = onValueChange,
            textStyle = fieldStyle,
            cursorBrush = SolidColor(Color.White),
            visualTransformation = effectiveTransformation,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth()
                .bringIntoViewRequester(bringIntoView)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        )
        // Custom emoji: each one is a blank, roughly one-em-wide slot in the
        // text (see emojiVisualTransformation) — put its picture right there.
        val l = layout
        if (emojiStore != null && l != null && l.layoutInput.text.length == value.text.length) {
            val density = LocalDensity.current
            value.text.forEachIndexed { i, c ->
                val bmp = emojiStore.imageBitmapForChar(c) ?: return@forEachIndexed
                val box = l.getBoundingBox(i)
                val side = min(box.width, box.height) * 0.92f
                if (side <= 0f) return@forEachIndexed
                Image(
                    bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .offset { IntOffset((box.left + (box.width - side) / 2f).roundToInt(), (box.top + (box.height - side) / 2f).roundToInt()) }
                        .size(with(density) { side.toDp() })
                )
            }
        }
        if (value.text.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, color = DimGray, fontSize = fieldStyle.fontSize, fontWeight = fieldStyle.fontWeight,
                textAlign = fieldStyle.textAlign, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Stand-in for an emoji's spot in the *displayed* text: a CJK ideograph is
 *  one em wide in every font and is a real (non-space) character, so the layout
 *  gives it a solid, wrappable slot; it's painted transparent and the emoji
 *  picture is drawn over it. A little letter-spacing widens the slot to the
 *  ~1.25em that a normal emoji glyph takes. */
private const val EMOJI_SLOT_CHAR = '\u4E00'
private val EmojiSlotStyle = SpanStyle(color = Color.Transparent, letterSpacing = 0.25.em)

/** Swaps every emoji token for a slot — one character for one character, so
 *  caret/selection offsets map straight across (OffsetMapping.Identity). */
private fun emojiVisualTransformation(store: EmojiStore) = VisualTransformation { text ->
    val shown = buildAnnotatedString {
        for (c in text.text) {
            if (store.entryFor(c) != null) withStyle(EmojiSlotStyle) { append(EMOJI_SLOT_CHAR) } else append(c)
        }
    }
    TransformedText(shown, OffsetMapping.Identity)
}

/** Up to 10 attached images as square tiles: one stretched row while
 *  there are 5 or fewer; past 5, a fixed 5-column grid, so the 6th–10th are
 *  exactly the same size as the first five instead of stretching to fill
 *  their row.
 *
 *  Tap a tile to remove it (after a confirmation); press and hold one to
 *  pick it up and drag it to a new spot — the others slide out of the way,
 *  with a haptic tick each time it takes a new place. */
@Composable
private fun ImageGrid(images: List<Uri>, onRemove: (Uri) -> Unit, onMove: (from: Int, to: Int) -> Unit) {
    val tap = rememberHapticTap()
    val view = androidx.compose.ui.platform.LocalView.current
    val columns = if (images.size > 5) 5 else images.size.coerceAtLeast(1)
    val rows = (images.size + columns - 1) / columns
    val latestImages by rememberUpdatedState(images)
    val latestOnMove by rememberUpdatedState(onMove)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellPx = constraints.maxWidth / columns.toFloat()
        val cellDp = maxWidth / columns
        // Read by the (long-lived) drag gesture, so it always has the current size.
        val latestCell by rememberUpdatedState(cellPx)
        var dragging by remember { mutableStateOf<Uri?>(null) }
        var dragPos by remember { mutableStateOf(Offset.Zero) }
        fun slotOf(i: Int) = Offset((i % columns) * cellPx, (i / columns) * cellPx)
        Box(Modifier.fillMaxWidth().height(cellDp * rows)) {
            images.forEachIndexed { index, uri ->
                key(uri) {
                    val isDragged = dragging == uri
                    val slot by androidx.compose.animation.core.animateOffsetAsState(slotOf(index), label = "imageSlot")
                    val lift by androidx.compose.animation.core.animateFloatAsState(if (isDragged) 1f else 0f, label = "imageLift")
                    Box(
                        Modifier
                            .offset {
                                val p = if (isDragged) dragPos else slot
                                IntOffset(p.x.roundToInt(), p.y.roundToInt())
                            }
                            .size(cellDp)
                            .zIndex(if (isDragged) 2f else if (lift > 0f) 1f else 0f)
                            .graphicsLayer {
                                val sc = 1f + 0.08f * lift
                                scaleX = sc; scaleY = sc
                                shadowElevation = 14f * lift
                                shape = RoundedCornerShape(6.dp)
                                clip = lift > 0f
                            }
                            .pointerInput(uri) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        val from = latestImages.indexOf(uri)
                                        if (from >= 0) {
                                            val cols = if (latestImages.size > 5) 5 else latestImages.size.coerceAtLeast(1)
                                            dragPos = Offset((from % cols) * latestCell, (from / cols) * latestCell)
                                            dragging = uri
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                        }
                                    },
                                    onDragEnd = {
                                        if (dragging == uri) view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                        dragging = null
                                    },
                                    onDragCancel = { dragging = null }
                                ) { change, amount ->
                                    if (dragging != uri) return@detectDragGesturesAfterLongPress
                                    change.consume()
                                    val list = latestImages
                                    val cols = if (list.size > 5) 5 else list.size.coerceAtLeast(1)
                                    val rowCount = (list.size + cols - 1) / cols
                                    val maxX = (cols - 1) * latestCell
                                    val maxY = (rowCount - 1) * latestCell
                                    dragPos = Offset((dragPos.x + amount.x).coerceIn(-latestCell * 0.3f, maxX + latestCell * 0.3f), (dragPos.y + amount.y).coerceIn(-latestCell * 0.3f, maxY + latestCell * 0.3f))
                                    val col = ((dragPos.x + latestCell / 2f) / latestCell).toInt().coerceIn(0, cols - 1)
                                    val row = ((dragPos.y + latestCell / 2f) / latestCell).toInt().coerceIn(0, rowCount - 1)
                                    val target = (row * cols + col).coerceIn(0, list.lastIndex)
                                    val from = list.indexOf(uri)
                                    if (from >= 0 && target != from) {
                                        latestOnMove(from, target)
                                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                }
                            }
                            .clickable { tap(); onRemove(uri) }
                    ) {
                        AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** A thread divider with its remove-post X at the right end. */
@Composable
private fun ThreadPostDivider(tint: Color, onRemove: () -> Unit) {
    val tap = rememberHapticTap()
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth().padding(end = 30.dp).align(Alignment.CenterStart),
            color = Color.White.copy(alpha = 0.12f)
        )
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(androidx.compose.ui.graphics.lerp(Color(0xFF16161A), tint, 0.3f).copy(alpha = 0.85f))
                .border(1.dp, tint.copy(alpha = 0.55f), CircleShape)
                .clickable { tap(); onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "Remove this post from the thread", tint = Color.White.copy(0.85f), modifier = Modifier.size(13.dp))
        }
    }
}

/** A "remove this?" question from the composer. */
private class RemovalRequest(
    val title: String,
    val message: String,
    /** An image to show in the popup (the one being removed). */
    val preview: Any? = null,
    val onConfirm: () -> Unit
)

/** The composer's confirmation popup: centred glass in the profile color
 *  that blurs the draft behind it (the draft itself is softly blurred and
 *  dimmed too), popping in with a haptic. Cancel / Remove. */
@Composable
private fun RemovalConfirmPopup(
    request: RemovalRequest,
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val tap = rememberHapticTap()
    val view = androidx.compose.ui.platform.LocalView.current
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(request) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 520f))
    }
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.fillMaxSize().zIndex(6f)
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        val cardModifier = Modifier
            .padding(horizontal = 36.dp).widthIn(max = 360.dp).fillMaxWidth()
            .graphicsLayer {
                val sc = 0.88f + 0.12f * appear.value
                scaleX = sc; scaleY = sc
            }
            // Swallow taps on the card itself so only the scrim dismisses.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
        val content: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (request.preview != null) {
                    AsyncImage(
                        model = request.preview, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
                            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(request.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(request.message, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pill = RoundedCornerShape(19.dp)
                    val cancelMod = Modifier.weight(1f).height(38.dp).clip(pill).clickable { tap(); onDismiss() }
                    val removeMod = Modifier.weight(1f).height(38.dp).clip(pill).clickable {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        onConfirm()
                    }
                    if (liquidGlass) {
                        LiquidGlassSurface(cancelMod, shape = pill, tint = tint, contentAlignment = Alignment.Center) {
                            Text("Cancel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        LiquidGlassSurface(removeMod, shape = pill, tint = Color(0xFFE0245E), contentAlignment = Alignment.Center) {
                            Text("Remove", color = Color(0xFFFF6B8A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Box(cancelMod.background(Color.White.copy(0.10f)).border(1.dp, Color.White.copy(0.18f), pill), contentAlignment = Alignment.Center) {
                            Text("Cancel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(removeMod.background(Color(0xFFE0245E).copy(0.22f)).border(1.dp, Color(0xFFE0245E).copy(0.6f), pill), contentAlignment = Alignment.Center) {
                            Text("Remove", color = Color(0xFFFF6B8A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(cardModifier, shape = shape, tint = tint, backdrop = backdrop) {
                // A little extra dark behind the text, like the app's other
                // popups, so it reads over any photo.
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.3f)))
                content()
            }
        } else {
            Box(
                cardModifier.clip(shape)
                    .background(androidx.compose.ui.graphics.lerp(Color(0xFF141418), tint, 0.25f))
                    .border(1.dp, tint.copy(alpha = 0.5f), shape)
            ) { content() }
        }
    }
}

/** [from]'s item moved to [to], everything between shifting over. */
private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}

/** Item 2: one thread post's attached video — a small 16:9 preview with a
 *  tap-to-remove affordance, same visual language as [ImageGrid]'s tiles. */
@Composable
private fun ThreadVideoPreview(uri: Uri, onRemove: () -> Unit) {
    val tap = rememberHapticTap()
    val context = LocalContext.current
    var aspect by remember(uri) { mutableStateOf(16f / 9f) }
    LaunchedEffect(uri) { aspect = withContext(Dispatchers.IO) { probeVideoAspect(context, uri) } }
    Box(
        Modifier.fillMaxWidth(0.55f).aspectRatio(aspect).clip(RoundedCornerShape(10.dp))
    ) {
        // Tap the video to play it; the X removes it.
        InlineVideoPlayer(uri, Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape)
                .background(Color.Black.copy(0.5f)).clickable { tap(); onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "Remove video", tint = Color.White, modifier = Modifier.size(16.dp))
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
    val tap = rememberHapticTap()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InlineVideoPlayer(videoUri, Modifier.weight(1f).aspectRatio(aspect).clip(RoundedCornerShape(10.dp)))
        Box(
            Modifier.weight(1f).aspectRatio(aspect).clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(0.06f)).clickable(onClick = { tap(); onTapThumbnail() }),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailUri != null) {
                // Cropped to the video's shape — exactly how it's stitched in.
                AsyncImage(model = thumbnailUri, contentDescription = "Thumbnail", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = DimGray, modifier = Modifier.size(24.dp))
                    Text("Thumbnail", color = DimGray, fontSize = 11.sp)
                }
            }
        }
    }
}

/** Item 9: Textshot mode's live preview now calls the exact same
 *  [com.mediaviewer.util.TextshotRenderer.render] used to build the actual
 *  uploaded image (see MainViewModel.submitComposePost), and just displays
 *  that bitmap — rather than a second, separately-tuned Compose-based
 *  layout that could (and did) drift out of sync with the real render.
 *  Item 8: since it's the same renderer, the preview automatically picks up
 *  its tight, content-hugging padding instead of sitting in a fixed square
 *  with a lot of dead space around short posts. */
@Composable
private fun TextshotPreview(text: String, liquidGlass: Boolean, tint: Color, emojiStore: EmojiStore) {
    val shape = RoundedCornerShape(14.dp)
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Re-render when the emoji library changes too (e.g. after an import).
    val emojiState = emojiStore.state
    LaunchedEffect(text, emojiState) {
        bitmap = withContext(Dispatchers.Default) {
            com.mediaviewer.util.TextshotRenderer.render(text, emojiBitmap = emojiStore::bitmapForChar)
        }
    }
    val current = bitmap
    val aspect = if (current != null && current.height > 0) current.width.toFloat() / current.height.toFloat() else 1f

    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect).clip(shape)
            .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape) else Modifier.background(Color.White.copy(0.05f)))
    ) {
        if (current != null) {
            Image(bitmap = current.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────

private fun Int?.orZero() = this ?: 0

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
 *  post's own budget is simply [POST_CHAR_LIMIT] — there's no "x/n" counter
 *  suffix to reserve room for any more (see Item 2: the indicator was
 *  removed entirely, both on screen and from the actual posted text). */
private fun computeThreadPosts(fullText: String, minPosts: Int): List<String> {
    val floor = minPosts.coerceAtLeast(1)
    if (fullText.isEmpty() && floor <= 1) return listOf("")
    var n = floor
    while (true) {
        val needed = maxOf(greedyChunks(fullText, POST_CHAR_LIMIT).size, floor)
        if (needed <= n || n > 50) break
        n = needed
    }
    val chunks = greedyChunks(fullText, POST_CHAR_LIMIT).toMutableList()
    while (chunks.size < n) chunks.add("")
    return chunks
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
