package com.mediaviewer.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
fun CapturePreviewScreen(
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
            mediaSize = if (isVideo) videoSize(context, uri) else imageSize(context, uri)
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

    // Page color: your profile color melting into black, behind the media.
    val pageBrush = Brush.verticalGradient(listOf(lerp(Color.Black, tint, 0.45f), lerp(Color.Black, tint, 0.18f), Color.Black))
    // Live backdrop for the glass buttons (the media behind them blurs).
    val backdropLayer = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    val backdrop = remember(liquidGlass, backdropLayer) { if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null }

    suspend fun cropped(): Uri? = withContext(Dispatchers.IO) {
        val ratio = crop.ratio ?: return@withContext uri
        runCatching {
            if (isVideo) cropVideo(context, uri, ratio, mediaSize) else cropImage(context, uri, ratio)
        }.onFailure { android.util.Log.e("CapturePreview", "Crop failed", it) }.getOrNull()
    }

    Box(
        Modifier.fillMaxSize().background(pageBrush)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { cropMenu = false }
    ) {
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
                Box(
                    Modifier.size(w, h)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Black)
                        .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                        .drawWithContent {
                            if (backdrop != null) backdropLayer.record { this@drawWithContent.drawContent() }
                            drawContent()
                        }
                ) {
                    if (isVideo) {
                        CaptureVideo(uri)
                    } else {
                        poster?.let {
                            androidx.compose.foundation.Image(
                                it.asImageBitmap(), contentDescription = "Captured photo",
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            // Bottom: interaction-bar style, two halves.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 18.dp),
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
                Icon(Icons.Default.Close, contentDescription = "Back to VRM mode", tint = Color.White, modifier = Modifier.size(20.dp))
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
private fun CaptureVideo(uri: Uri) {
    val context = LocalContext.current
    var paused by remember { mutableStateOf(false) }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().clickable {
        paused = !paused
        player.playWhenReady = !paused
    }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    // Zoom = centre-crop, i.e. exactly what the chosen crop keeps.
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
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

/** Centre crop of a photo to [ratio] (w/h), saved as a new JPEG. */
private fun cropImage(context: Context, uri: Uri, ratio: Float): Uri {
    val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        ?: error("Couldn't read the photo")
    val (cw, ch) = if (src.width.toFloat() / src.height > ratio) {
        (src.height * ratio).toInt() to src.height
    } else {
        src.width to (src.width / ratio).toInt()
    }
    val out = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw.coerceAtLeast(1), ch.coerceAtLeast(1))
    val file = captureFile(context, "jpg")
    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    if (out !== src) out.recycle()
    src.recycle()
    return providerUri(context, file)
}

/** Centre crop of a video to [ratio] (w/h) with media3 Transformer. */
private suspend fun cropVideo(context: Context, uri: Uri, ratio: Float, size: Pair<Int, Int>?): Uri {
    val (w, h) = size ?: videoSize(context, uri) ?: error("Unknown video size")
    val videoAspect = w.toFloat() / h
    // Crop takes normalised device coordinates (-1 … 1).
    val (xExtent, yExtent) = if (ratio < videoAspect) (ratio / videoAspect) to 1f else 1f to (videoAspect / ratio)
    val file = captureFile(context, "mp4")
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val crop = androidx.media3.effect.Crop(-xExtent, xExtent, -yExtent, yExtent)
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
