package com.mediaviewer.ui

import com.mediaviewer.ui.compat.navBarSpace

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A crop choice on the review page; [ratio] = width / height, null = none. */
data class CaptureCrop(val label: String, val ratio: Float?)

private val PORTRAIT_CROPS = listOf(
    CaptureCrop("Default", null), CaptureCrop("9:16", 9f / 16f), CaptureCrop("2:3", 2f / 3f),
    CaptureCrop("3:4", 3f / 4f), CaptureCrop("4:5", 4f / 5f), CaptureCrop("1:1", 1f)
)
private val LANDSCAPE_CROPS = listOf(
    CaptureCrop("Default", null), CaptureCrop("16:9", 16f / 9f), CaptureCrop("3:2", 3f / 2f),
    CaptureCrop("4:3", 4f / 3f), CaptureCrop("5:4", 5f / 4f), CaptureCrop("1:1", 1f)
)

/**
 * Item 13: the page a VRM-mode photo/video lands on. VRM mode is already
 * closed (camera, trackers and renderer freed) by the time this shows.
 *
 *  - Top left: X — back into VRM mode.
 *  - Top right: crop — Default plus the common aspect ratios for the
 *    capture's orientation. The preview shows exactly the chosen crop.
 *  - Middle: the capture (videos play, looping; tap to pause).
 *  - Bottom: "Save to Device" and "Create Post", side by side. Both apply
 *    the crop first.
 */
@Composable
actual fun CapturePreviewScreen(
    uri: Uri,
    isVideo: Boolean,
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCreatePost: (Uri) -> Unit
) {
    val context = LocalContext.current
    val tap = rememberHapticTap()
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    var mediaSize by remember(uri) { mutableStateOf<Pair<Int, Int>?>(null) }
    var poster by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            val probed = if (isVideo) videoSize(context, uri) else imageSize(context, uri)
            // A video's player may already have reported its real size.
            if (mediaSize == null) mediaSize = probed
            if (!isVideo) poster = decodeScaled(context, uri, 1600)
        }
    }
    val portrait = mediaSize?.let { it.second >= it.first } ?: true
    val crops = if (portrait) PORTRAIT_CROPS else LANDSCAPE_CROPS
    var crop by remember(uri) { mutableStateOf(crops.first()) }
    var cropMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    val mediaAspect = mediaSize?.let { it.first.toFloat() / it.second.coerceAtLeast(1) } ?: (9f / 16f)
    val shownAspect = crop.ratio ?: mediaAspect
    val animatedAspect by animateFloatAsState(shownAspect, label = "cropAspect")

    // Live backdrop for the glass buttons (the media behind them blurs).
    val backdropLayer = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    val backdrop = remember(liquidGlass, backdropLayer) { if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null }

    // Positioning inside the crop: drag to move, pinch to zoom (1× = the
    // media just filling the crop). Reset whenever the crop changes.
    var cropZoom by remember(uri, crop) { mutableStateOf(1f) }
    var cropPan by remember(uri, crop) { mutableStateOf(Offset.Zero) }
    /** The part of the media the crop box shows, as fractions of the whole
     *  frame (left, top, width, height) — what the export keeps. */
    var cropWindow by remember(uri, crop) { mutableStateOf<FloatArray?>(null) }

    suspend fun cropped(): Uri? = withContext(Dispatchers.IO) {
        val ratio = crop.ratio ?: return@withContext uri
        val window = cropWindow
        runCatching {
            if (isVideo) cropVideo(context, uri, ratio, mediaSize, window) else cropImage(context, uri, ratio, window)
        }.onFailure { android.util.Log.e("CapturePreview", "Crop failed", it) }.getOrNull()
    }

    Box(
        Modifier.fillMaxSize().background(dimSpaceColor(tint))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { cropMenu = false }
    ) {
        SpaceSky(tint, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(rememberTopCutoutClearance() + 56.dp))
            // The capture, as large as fits, at the chosen crop.
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                val maxW = maxWidth
                val maxH = maxHeight
                val w = if (maxW / maxH > animatedAspect) maxH * animatedAspect else maxW
                val h = w / animatedAspect
                // The media's own size inside the box: exactly the box with
                // no crop; with a crop, just big enough to cover it (the
                // overflow is what you can drag into view).
                val cropping = crop.ratio != null
                val boxAspect = w / h
                val dispW = if (!cropping) w else if (mediaAspect > boxAspect) h * mediaAspect else w
                val dispH = if (!cropping) h else if (mediaAspect > boxAspect) h else w / mediaAspect
                val density = androidx.compose.ui.platform.LocalDensity.current
                val boxWpx = with(density) { w.toPx() }; val boxHpx = with(density) { h.toPx() }
                val dispWpx = with(density) { dispW.toPx() }; val dispHpx = with(density) { dispH.toPx() }
                fun clampPan(p: Offset, z: Float): Offset {
                    val mx = ((dispWpx * z - boxWpx) / 2f).coerceAtLeast(0f)
                    val my = ((dispHpx * z - boxHpx) / 2f).coerceAtLeast(0f)
                    return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
                }
                // Keep the export window in step with what's on screen.
                androidx.compose.runtime.SideEffect {
                    cropWindow = if (!cropping || dispWpx <= 0f || dispHpx <= 0f) null else {
                        val cw = dispWpx * cropZoom; val ch = dispHpx * cropZoom
                        val left = ((cw - boxWpx) / 2f - cropPan.x) / cw
                        val top = ((ch - boxHpx) / 2f - cropPan.y) / ch
                        floatArrayOf(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), (boxWpx / cw).coerceIn(0f, 1f), (boxHpx / ch).coerceIn(0f, 1f))
                    }
                }
                val hapticView = androidx.compose.ui.platform.LocalView.current
                Box(
                    Modifier.size(w, h)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Black)
                        .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                        .drawWithContent {
                            // Recorded clipped to the crop box: the media can
                            // now be bigger than the box (zoomed/dragged), and
                            // the unclipped overflow was showing up inside the
                            // glass buttons around it.
                            if (backdrop != null) backdropLayer.record {
                                clipRect(0f, 0f, size.width, size.height) { this@drawWithContent.drawContent() }
                            }
                            drawContent()
                        }
                        .then(if (cropping) Modifier.pointerInput(crop, dispWpx, dispHpx) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                val before = cropZoom
                                val z = (cropZoom * zoomChange).coerceIn(1f, 5f)
                                if ((before > 1f && z == 1f) || (before < 5f && z == 5f)) {
                                    hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                }
                                cropZoom = z
                                cropPan = clampPan(cropPan + panChange, z)
                            }
                        } else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.requiredSize(dispW, dispH).graphicsLayer {
                            scaleX = cropZoom; scaleY = cropZoom
                            translationX = cropPan.x; translationY = cropPan.y
                        }
                    ) {
                    if (isVideo) {
                        CaptureVideo(
                            uri = uri,
                            // The player fills its own box exactly (same
                            // shape as the video); the crop box around it
                            // shows the part you've dragged/zoomed to —
                            // exactly what the export keeps.
                            zoom = false,
                            // The decoder's real frame size is the truth; the
                            // file metadata can disagree (rotation flags,
                            // encoder padding), which is what threw the box's
                            // aspect off and made the video look zoomed/offset.
                            onVideoSize = { w, h -> if (w > 0 && h > 0 && mediaSize != (w to h)) mediaSize = w to h }
                        )
                    } else {
                        poster?.let {
                            androidx.compose.foundation.Image(
                                it.asImageBitmap(), contentDescription = "Captured photo",
                                contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    }
                    // A hint the first moments after picking a crop.
                    if (cropping) {
                        var hintVisible by remember(crop) { mutableStateOf(true) }
                        LaunchedEffect(crop) { kotlinx.coroutines.delay(2600); hintVisible = false }
                        val hintAlpha by animateFloatAsState(if (hintVisible) 1f else 0f, label = "cropHint")
                        if (hintAlpha > 0f) Text(
                            "Drag to position · pinch to zoom",
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                                .graphicsLayer { alpha = hintAlpha }
                                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            // Bottom: interaction-bar style, two halves.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .windowInsetsPadding(WindowInsets.navBarSpace).padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CaptureActionButton(
                    label = if (busy == "save") "Saving…" else "Save to Device",
                    icon = Icons.Default.Download,
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop, filled = false,
                    enabled = busy == null, busy = busy == "save", modifier = Modifier.weight(1f)
                ) {
                    tap(); busy = "save"
                    scope.launch {
                        val out = cropped()
                        val ok = out != null && withContext(Dispatchers.IO) { saveToGallery(context, out, isVideo) }
                        busy = null
                        Toast.makeText(context, if (ok) "Saved to DCIM/Stellar" else "Couldn't save", Toast.LENGTH_SHORT).show()
                    }
                }
                CaptureActionButton(
                    label = if (busy == "post") "Preparing…" else "Create Post",
                    icon = Icons.Default.Edit,
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop, filled = true,
                    enabled = busy == null, busy = busy == "post", modifier = Modifier.weight(1f)
                ) {
                    tap(); busy = "post"
                    scope.launch {
                        val out = cropped()
                        busy = null
                        if (out != null) onCreatePost(out)
                        else Toast.makeText(context, "Couldn't crop that — try Default", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Top row: close (left), crop (right) — just under the camera cutout.
        Row(
            Modifier.fillMaxWidth().padding(top = rememberTopCutoutClearance(), start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CaptureGlassBubble(liquidGlass, tint, backdrop, onClick = { tap(); onClose() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to VRM mode", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            Box {
                CaptureGlassBubble(liquidGlass, tint, backdrop, wide = true, onClick = { tap(); cropMenu = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Icon(Icons.Default.Crop, contentDescription = "Aspect ratio", tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(crop.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                DropdownMenu(
                    expanded = cropMenu, onDismissRequest = { cropMenu = false },
                    modifier = Modifier.background(lerp(Color(0xFF151518), tint, 0.25f))
                ) {
                    for (c in crops) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    c.label, color = Color.White,
                                    fontWeight = if (c == crop) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = { tap(); crop = c; cropMenu = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureVideo(uri: Uri, zoom: Boolean, onVideoSize: (Int, Int) -> Unit) {
    val context = LocalContext.current
    var paused by remember { mutableStateOf(false) }
    val latestOnVideoSize = androidx.compose.runtime.rememberUpdatedState(onVideoSize)
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                // Displayed size: pixel aspect applied, rotation the
                // renderer didn't apply itself accounted for.
                val w = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                val h = videoSize.height
                @Suppress("DEPRECATION")
                val rotated = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
                if (rotated) latestOnVideoSize.value(h, w) else latestOnVideoSize.value(w, h)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    Box(Modifier.fillMaxSize().clickable {
        paused = !paused
        player.playWhenReady = !paused
    }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // TextureView-backed (inflated — the surface type can only be
                // set from XML): a SurfaceView ignores this box's rounded
                // clip and lags behind its size animation, so the video sat
                // offset/oversized inside the frame. A TextureView draws
                // like a normal view, and the glass buttons can blur it.
                (android.view.LayoutInflater.from(ctx).inflate(com.mediaviewer.R.layout.player_view_texture, null) as PlayerView).apply {
                    this.player = player
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { view ->
                val mode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                if (view.resizeMode != mode) view.resizeMode = mode
            },
            onRelease = { it.player = null }
        )
        if (paused) {
            Box(
                Modifier.align(Alignment.Center).size(58.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(34.dp)) }
        }
    }
}

@Composable
private fun CaptureGlassBubble(
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, wide: Boolean = false,
    onClick: () -> Unit, content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    val base = Modifier.height(44.dp).then(if (wide) Modifier else Modifier.width(44.dp)).clip(shape).clickable(onClick = onClick)
    if (liquidGlass) {
        LiquidGlassSurface(modifier = base, shape = shape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.Center) { content() }
    } else {
        Box(base.background(lerp(Color(0xFF121212), tint, 0.3f)), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun CaptureActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    filled: Boolean, enabled: Boolean, busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    val base = modifier.height(52.dp).clip(shape).clickable(enabled = enabled, onClick = onClick)
    val inner: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
            if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            else Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    when {
        filled -> Box(base.background(lerp(tint, Color.Black, 0.15f)), contentAlignment = Alignment.Center) { inner() }
        liquidGlass -> LiquidGlassSurface(modifier = base, shape = shape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.Center) { inner() }
        else -> Box(base.background(lerp(Color(0xFF121212), tint, 0.3f)), contentAlignment = Alignment.Center) { inner() }
    }
}

// ── media helpers ───────────────────────────────────────────────────────────

private fun imageSize(context: Context, uri: Uri): Pair<Int, Int>? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
}.getOrNull()

private fun videoSize(context: Context, uri: Uri): Pair<Int, Int>? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return@runCatching null
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return@runCatching null
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) h to w else w to h
    } finally {
        runCatching { r.release() }
    }
}.getOrNull()

private fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    val size = imageSize(context, uri) ?: return@runCatching null
    var sample = 1
    while (maxOf(size.first, size.second) / (sample * 2) >= maxSide) sample *= 2
    val o = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
}.getOrNull()

private fun captureFile(context: Context, ext: String): File {
    val dir = File(context.cacheDir, "camera_capture").also { it.mkdirs() }
    return File(dir, "vrm_crop_${System.currentTimeMillis()}.$ext")
}

private fun providerUri(context: Context, file: File): Uri =
    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/** Crop of a photo to [ratio] (w/h), saved as a new JPEG: the [window]
 *  (left, top, width, height as fractions of the frame) you positioned, or
 *  the centre when there isn't one. */
private fun cropImage(context: Context, uri: Uri, ratio: Float, window: FloatArray? = null): Uri {
    val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        ?: error("Couldn't read the photo")
    val (x, y, cw, ch) = if (window != null) {
        val cw = (window[2] * src.width).toInt().coerceIn(1, src.width)
        val ch = (window[3] * src.height).toInt().coerceIn(1, src.height)
        listOf((window[0] * src.width).toInt().coerceIn(0, src.width - cw), (window[1] * src.height).toInt().coerceIn(0, src.height - ch), cw, ch)
    } else {
        val (cw, ch) = if (src.width.toFloat() / src.height > ratio) {
            (src.height * ratio).toInt() to src.height
        } else {
            src.width to (src.width / ratio).toInt()
        }
        listOf((src.width - cw) / 2, (src.height - ch) / 2, cw.coerceAtLeast(1), ch.coerceAtLeast(1))
    }
    val out = Bitmap.createBitmap(src, x, y, cw, ch)
    val file = captureFile(context, "jpg")
    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    if (out !== src) out.recycle()
    src.recycle()
    return providerUri(context, file)
}

/** Centre crop of a video to [ratio] (w/h) with media3 Transformer. */
private suspend fun cropVideo(context: Context, uri: Uri, ratio: Float, size: Pair<Int, Int>?, window: FloatArray? = null): Uri {
    val (w, h) = size ?: videoSize(context, uri) ?: error("Unknown video size")
    val videoAspect = w.toFloat() / h
    // Crop takes normalised device coordinates (-1 … 1, y pointing up).
    val (xExtent, yExtent) = if (ratio < videoAspect) (ratio / videoAspect) to 1f else 1f to (videoAspect / ratio)
    val bounds = if (window != null) floatArrayOf(
        -1f + 2f * window[0], -1f + 2f * (window[0] + window[2]),
        1f - 2f * (window[1] + window[3]), 1f - 2f * window[1]
    ) else floatArrayOf(-xExtent, xExtent, -yExtent, yExtent)
    val file = captureFile(context, "mp4")
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val crop = androidx.media3.effect.Crop(bounds[0], bounds[1], bounds[2], bounds[3])
            val edited = androidx.media3.transformer.EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(androidx.media3.transformer.Effects(emptyList(), listOf<androidx.media3.common.Effect>(crop)))
                .build()
            val transformer = androidx.media3.transformer.Transformer.Builder(context.applicationContext).build()
            val listener = object : androidx.media3.transformer.Transformer.Listener {
                override fun onCompleted(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult
                ) { if (cont.isActive) cont.resume(Unit) }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exportException: androidx.media3.transformer.ExportException
                ) { if (cont.isActive) cont.resumeWithException(exportException) }
            }
            transformer.addListener(listener)
            cont.invokeOnCancellation { transformer.removeListener(listener); transformer.cancel() }
            transformer.start(edited, file.absolutePath)
        }
    }
    return providerUri(context, file)
}

/** Copies [uri] into the shared gallery under DCIM/Stellar. */
private fun saveToGallery(context: Context, uri: Uri, isVideo: Boolean): Boolean = runCatching {
    val ext = if (isVideo) "mp4" else "jpg"
    val mime = if (isVideo) "video/mp4" else "image/jpeg"
    val name = "Stellar_${System.currentTimeMillis()}.$ext"
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Stellar")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(collection, values) ?: return@runCatching false
        resolver.openOutputStream(target)?.use { out ->
            resolver.openInputStream(uri)?.use { it.copyTo(out) } ?: error("Couldn't read the capture")
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(target, values, null, null)
        true
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Stellar").also { it.mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { out -> resolver.openInputStream(uri)?.use { it.copyTo(out) } }
        android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        true
    }
}.getOrDefault(false)
