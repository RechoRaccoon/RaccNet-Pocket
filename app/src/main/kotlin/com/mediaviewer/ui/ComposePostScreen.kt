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
import androidx.compose.ui.text.AnnotatedString
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
import com.mediaviewer.ui.theme.RepostGreen
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

enum class ComposeMode { SINGLE, THREAD, TEXTSHOT, VIDEO, REVIEW }

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
    val selfLabels: List<String> = emptyList()
)

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
        ?: selfProfile?.avatarUrl?.let { rememberDominantColor(it) } ?: dominantColor

    // ── Core state ───────────────────────────────────────────────────────
    var mode by remember { mutableStateOf(if (reviewTarget != null) ComposeMode.REVIEW else ComposeMode.SINGLE) }
    // Item 10: Popfeed's own native 0–10 half-star scale (0 = unrated,
    // 10 = full 5 stars) — see PopfeedReview's ratingOutOf5 doc comment for
    // why /2 is always the right conversion both ways.
    var reviewRating by remember { mutableStateOf(0) }
    // Item 12: composer-local "Mark as Spoiler" toggle for Review mode —
    // see ComposePostDraft.reviewContainsSpoilers.
    var reviewContainsSpoilers by remember { mutableStateOf(false) }
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
    var adultLabel by remember { mutableStateOf<AdultContentLabel?>(null) }
    var graphicMedia by remember { mutableStateOf(false) }
    var labelsOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

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
                ComposeMode.SINGLE, ComposeMode.TEXTSHOT, ComposeMode.REVIEW -> singleFocusRequester.requestFocus()
                ComposeMode.THREAD -> threadFocusRequesters.getOrNull(activeThreadIndex)?.requestFocus()
                ComposeMode.VIDEO -> videoTitleFocusRequester.requestFocus()
            }
        } catch (_: IllegalStateException) {
            // Field not attached to the composition yet — nothing to focus.
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
        threadPosts = computeThreadPosts(singleText.text, minPosts = if (overflowing) 1 else 2)
            .map { TextFieldValue(it) }
        activeThreadIndex = threadPosts.lastIndex
        mode = ComposeMode.THREAD
        isBlogMode = false
        autoFormat = overflowing
    }

    // Item 4: "+" tap while already threaded — appends one genuinely new
    // blank post and moves focus there, instead of re-flowing/redistributing
    // any existing text into it.
    fun addThreadPost() {
        threadPosts = threadPosts + TextFieldValue("")
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
        threadPosts = computeThreadPosts(fullText, minPosts = floor).map { TextFieldValue(it) }
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

    // [keepValue] carries the exact text *and* caret through when typing/
    // pasting past the limit triggers this automatically; the Textshot
    // button itself leaves it null and re-seeds from whatever is current.
    fun switchToTextshot(keepValue: TextFieldValue? = null) {
        singleText = keepValue ?: run {
            val seed = if (mode == ComposeMode.THREAD) threadPosts.joinToString("") { it.text } else singleText.text
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
    fun enableBlogMode() {
        if (mode == ComposeMode.THREAD) {
            singleText = TextFieldValue(threadPosts.joinToString("\n\n") { it.text.trimEnd() })
        }
        isBlogMode = true
        mode = ComposeMode.SINGLE
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
        // Item 10: "the character indicator shouldn't have a limit" — same
        // unlimited treatment as Textshot above.
        ComposeMode.REVIEW -> singleText.text.length to Int.MAX_VALUE
        ComposeMode.SINGLE -> singleText.text.length to POST_CHAR_LIMIT
    }

    val canPost = when (mode) {
        ComposeMode.VIDEO -> videoUri != null && (videoTitle.text.length + videoDescription.text.length) <= POST_CHAR_LIMIT
        ComposeMode.THREAD -> threadPosts.all { it.text.length <= (POST_CHAR_LIMIT - threadSuffixLength(threadPosts.size)) } &&
            threadPosts.any { it.text.isNotBlank() }
        ComposeMode.TEXTSHOT -> singleText.text.isNotBlank()
        // Item 10: a rating is required (the person must pick 0.5–5 stars),
        // the written review itself is optional — matches the spec ("pick
        // a rating and optionally type out a review").
        ComposeMode.REVIEW -> reviewTarget != null && reviewRating > 0
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
        }
        val selfLabels = listOfNotNull(adultLabel?.value, if (graphicMedia) GRAPHIC_MEDIA_LABEL else null)
        // Reviews are Popfeed records, not Bluesky posts — labels don't apply.
        onSubmit(if (mode == ComposeMode.REVIEW || selfLabels.isEmpty()) draft else draft.copy(selfLabels = selfLabels))
    }

    // Item 9: this is now always a plain label — Thread/Textshot are no
    // longer chosen from inside the status bubble (see StatusBubble below),
    // only from the dedicated bottom-bar buttons.
    val statusLabel = when {
        mode == ComposeMode.REVIEW -> "Review"
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
        Box(Modifier.fillMaxSize()) {
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
                    .background(postBackgroundBrush(dominantColor))
            ) {
            CompositionLocalProvider(LocalBottomBarClearance provides bottomBarHeight) {
            Column(
                Modifier.fillMaxSize().imePadding().navigationBarsPadding()
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
                        threadPosts.forEachIndexed { index, tfv ->
                            HubDivider("Post ${index + 1}/${threadPosts.size}")
                            GrowingTextField(
                                value = tfv,
                                onValueChange = { newVal ->
                                    if (!autoFormat) {
                                        val budget = POST_CHAR_LIMIT - threadSuffixLength(threadPosts.size)
                                        threadPosts = threadPosts.toMutableList().also {
                                            it[index] = capBudget(newVal, budget)
                                        }
                                        activeThreadIndex = index
                                    } else {
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
                                    }
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
                                // Held back until the post actually has text
                                // in it, otherwise it overlapped the "Continue
                                // the thread…" placeholder on every not-yet-
                                // started post.
                                visualTransformation = if (tfv.text.isNotEmpty())
                                    threadSuffixTransformation(index, threadPosts.size) else VisualTransformation.None
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
                            text = singleText.text.ifBlank { "Preview" },
                            liquidGlass = liquidGlass, tint = dominantColor
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
                            ImageGrid(images = images, onRemove = { uri -> images = images - uri })
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
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding()
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                                icon = Icons.Default.Image, contentDescription = "Attach image or video",
                                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop, size = 40.dp,
                                enabled = mode != ComposeMode.TEXTSHOT && mode != ComposeMode.REVIEW,
                                onClick = {
                                    mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                }
                            )
                            // Item 2: Blog and Textshot are text buttons, not
                            // icons — dim unless the status they set is the
                            // one currently active.
                            TextToggleButton(
                                label = "Blog",
                                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                                enabled = mode != ComposeMode.VIDEO && mode != ComposeMode.REVIEW,
                                selected = isBlogMode,
                                onClick = {
                                    if (isBlogMode) {
                                        isBlogMode = false
                                    } else if (mode != ComposeMode.VIDEO) {
                                        enableBlogMode()
                                    }
                                }
                            )
                            TextToggleButton(
                                label = "Textshot",
                                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                                enabled = mode != ComposeMode.VIDEO && mode != ComposeMode.REVIEW,
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
                                            val fullText = threadPosts.joinToString("") { it.text }
                                            threadPosts = computeThreadPosts(fullText, minPosts = threadPosts.size)
                                                .map { TextFieldValue(it) }
                                            activeThreadIndex = activeThreadIndex.coerceAtMost(threadPosts.lastIndex)
                                        }
                                    }
                                )
                            }
                        }
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
            Box(
                Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(19.dp))
                    .background(Color(0xFF1083FE))
                    .clickable { tap(); onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Text("Done", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** One row of [ContentLabelsPopup]: checkbox + title on the left, that
 *  option's description on the right (always visible). */
@Composable
private fun LabelOptionRow(title: String, description: String, checked: Boolean, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    val boxShape = RoundedCornerShape(5.dp)
    val blue = Color(0xFF1083FE)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable { tap(); onClick() }
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.width(118.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(18.dp).clip(boxShape)
                    .background(if (checked) blue else Color.White.copy(alpha = 0.06f))
                    .border(1.dp, if (checked) blue else Color.White.copy(alpha = 0.35f), boxShape),
                contentAlignment = Alignment.Center
            ) {
                if (checked) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(7.dp))
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }
        Text(description, color = DimGray, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
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
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    // The scroll area now extends behind the floating bottom bar, so the
    // default "scroll the caret into view" would park it right under the
    // buttons. Ask for the caret's rect plus the bar's height instead.
    val clearancePx = with(LocalDensity.current) { LocalBottomBarClearance.current.toPx() }
    val bringIntoView = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
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
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = visualTransformation,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth()
                .bringIntoViewRequester(bringIntoView)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
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
    val tap = rememberHapticTap()
    Column(Modifier.fillMaxWidth()) {
        images.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { uri ->
                    Box(Modifier.weight(1f).aspectRatio(1f).clickable { tap(); onRemove(uri) }) {
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
    val tap = rememberHapticTap()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.weight(1f).aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(Color.Black)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Video", tint = Color.White.copy(0.7f),
                modifier = Modifier.align(Alignment.Center).size(32.dp))
        }
        Box(
            Modifier.weight(1f).aspectRatio(aspect).clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(0.06f)).clickable(onClick = { tap(); onTapThumbnail() }),
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

/** Item 9: Textshot mode's live preview now calls the exact same
 *  [com.mediaviewer.util.TextshotRenderer.render] used to build the actual
 *  uploaded image (see MainViewModel.submitComposePost), and just displays
 *  that bitmap — rather than a second, separately-tuned Compose-based
 *  layout that could (and did) drift out of sync with the real render.
 *  Item 8: since it's the same renderer, the preview automatically picks up
 *  its tight, content-hugging padding instead of sitting in a fixed square
 *  with a lot of dead space around short posts. */
@Composable
private fun TextshotPreview(text: String, liquidGlass: Boolean, tint: Color) {
    val shape = RoundedCornerShape(14.dp)
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(text) {
        bitmap = withContext(Dispatchers.Default) {
            com.mediaviewer.util.TextshotRenderer.render(text)
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
