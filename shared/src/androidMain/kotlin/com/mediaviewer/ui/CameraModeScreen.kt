package com.mediaviewer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.view.TextureView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mediaviewer.util.VrmSettingsStore
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The camera-notch bubble's "Camera" page: VRM mode's page and controls, but
 * showing your real camera instead of an avatar — no trackers, no model.
 *
 *  - Same shared bottom bar as VRM mode ([CaptureControlsBar]): mic, photo/
 *    video, capture, Live, and — where VRM mode has Settings — a flip-camera
 *    button.
 *  - Photos, videos (with the mic, voice pitch and any browser windows set
 *    to show in captures) and live streams all work exactly like VRM mode,
 *    and share its saved stream settings and browser windows.
 *  - The camera image goes through [CameraGlRenderer]: one GPU pass per
 *    output (screen, recorder, stream), mirrored for the selfie camera.
 */
@Composable
actual fun CameraModeScreen(
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCapture: (imageUri: Uri?, videoUri: Uri?) -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val hostView = LocalView.current
    val tap = rememberHapticTap()
    val store = remember { VrmSettingsStore(context) }
    val K = VrmSettingsStore

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    var frontCamera by remember { mutableStateOf(store.bool(KEY_FRONT, true)) }
    LaunchedEffect(frontCamera) { store.put(KEY_FRONT, frontCamera) }
    var videoMode by remember { mutableStateOf(store.bool(KEY_VIDEO_MODE, false)) }
    LaunchedEffect(videoMode) { store.put(KEY_VIDEO_MODE, videoMode) }
    var micMuted by remember { mutableStateOf(store.bool(K.MIC_MUTED, false)) }
    LaunchedEffect(micMuted) { store.put(K.MIC_MUTED, micMuted) }
    val voicePitch = remember { store.int(K.VOICE_PITCH, 0).coerceIn(-8, 8) }
    // Browser windows set up in VRM mode show (and capture) here too.
    val overlaysEnabled = remember { BrowserOverlayStore.enabled(store) }
    var browserOverlays by remember { mutableStateOf(BrowserOverlayStore.load(store)) }
    LaunchedEffect(browserOverlays) { BrowserOverlayStore.save(store, browserOverlays) }
    val overlayRegistry = remember { BrowserOverlayRegistry() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { overlayRegistry.release() } }

    val renderer = remember { CameraGlRenderer() }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    // Flip: a quick dip to black so the switch doesn't show a torn frame.
    val flipCover = remember { Animatable(1f) }
    SideEffect { renderer.mirror = frontCamera }
    // Opening: the cover lifts once the camera's first frame is on screen
    // (or after a few seconds regardless, so it can never stay black).
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withTimeoutOrNull(4000) {
            while (renderer.lastDrawnStream == 0) kotlinx.coroutines.delay(16)
        }
        flipCover.animateTo(0f, tween(220))
    }

    val capture = remember { CameraCaptureSession(renderer) }
    DisplayRefreshCap(60f)
    val scope = rememberCoroutineScope()

    // The bound Preview, so its target rotation can follow the screen: the
    // activity isn't recreated on rotation, so without this the preview
    // kept the rotation it was bound with and showed up 90° off in
    // landscape.
    var boundPreview by remember { mutableStateOf<Preview?>(null) }
    DisposableEffect(hostView) {
        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val display = hostView.display ?: return
                if (display.displayId != displayId) return
                boundPreview?.let { p -> if (p.targetRotation != display.rotation) p.targetRotation = display.rotation }
            }
        }
        dm?.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { dm?.unregisterDisplayListener(listener) }
    }
    // Also on every configuration change (covers the 90° turns straight away).
    val rotationConfig = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(rotationConfig.orientation, rotationConfig.screenWidthDp) {
        val display = hostView.display ?: return@LaunchedEffect
        boundPreview?.let { p -> if (p.targetRotation != display.rotation) p.targetRotation = display.rotation }
    }

    // ── Camera binding ──────────────────────────────────────────────────
    LaunchedEffect(hasCameraPermission, frontCamera) {
        if (!hasCameraPermission) return@LaunchedEffect
        val provider = runCatching { withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() } }
            .onFailure { cameraError = "Camera unavailable" }.getOrNull() ?: return@LaunchedEffect
        val wanted = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val selector = if (runCatching { provider.hasCamera(wanted) }.getOrDefault(false)) wanted
            else if (frontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val resolution = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                androidx.camera.core.resolutionselector.ResolutionStrategy(
                    android.util.Size(1920, 1080),
                    androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(hostView.display?.rotation ?: android.view.Surface.ROTATION_0)
            .build()
        preview.setSurfaceProvider(renderer.surfaceProvider)
        boundPreview = preview
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview)
            cameraError = null
        }.onFailure {
            Log.e(TAG, "Couldn't bind the camera", it)
            cameraError = "Couldn't open the camera"
        }
    }

    // ── Live streaming (same flow and saved settings as VRM mode) ───────
    var liveDialogOpen by remember { mutableStateOf(false) }
    var endLiveConfirmOpen by remember { mutableStateOf(false) }
    var liveState by remember { mutableStateOf(com.mediaviewer.stream.LiveStreamer.State.IDLE) }
    var liveStartMs by remember { mutableStateOf(0L) }
    var liveElapsedS by remember { mutableStateOf(0L) }
    var liveKbps by remember { mutableStateOf(0L) }
    var streamUrl by remember { mutableStateOf(store.string(K.STREAM_URL)) }
    var streamKey by remember { mutableStateOf(store.string(K.STREAM_KEY)) }
    var streamQualityName by remember { mutableStateOf(store.string(K.STREAM_QUALITY)) }
    var streamLink by remember { mutableStateOf(store.string(K.STREAM_LINK)) }
    var liveBadgeUrl by remember { mutableStateOf<String?>(null) }
    val autoQuality = remember { com.mediaviewer.stream.StreamQuality.auto(context) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val liveStreamer = remember {
        com.mediaviewer.stream.LiveStreamer(context, object : com.mediaviewer.stream.LiveStreamer.Listener {
            override fun onStateChanged(state: com.mediaviewer.stream.LiveStreamer.State, message: String?) {
                mainHandler.post {
                    liveState = state
                    if (state == com.mediaviewer.stream.LiveStreamer.State.FAILED ||
                        state == com.mediaviewer.stream.LiveStreamer.State.ENDED) {
                        capture.stopStream()
                    }
                    if (state == com.mediaviewer.stream.LiveStreamer.State.FAILED && message != null) captureError = message
                }
            }
        })
    }
    val isLive = liveState == com.mediaviewer.stream.LiveStreamer.State.LIVE ||
        liveState == com.mediaviewer.stream.LiveStreamer.State.RECONNECTING ||
        liveState == com.mediaviewer.stream.LiveStreamer.State.CONNECTING
    SideEffect {
        liveStreamer.micMuted = micMuted
        liveStreamer.pitchSemitones = voicePitch.toFloat()
    }
    val captureOverlayIds = if (overlaysEnabled) browserOverlays.filter { it.inCapture }.map { it.id } else emptyList()
    fun refreshCaptureOverlays() {
        capture.overlays = if (captureOverlayIds.isEmpty()) emptyList()
            else overlayRegistry.snapshot(captureOverlayIds, hostView.width, hostView.height)
    }
    fun clearLiveBadge() {
        if (liveBadgeUrl == null) return
        liveBadgeUrl = null
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { com.mediaviewer.util.LiveLinkManager.clearStreamLive(appContext) }
    }
    fun startLive(withMic: Boolean) {
        val quality = com.mediaviewer.stream.StreamQuality.values().firstOrNull { it.name == streamQualityName }
            ?.let { com.mediaviewer.stream.StreamQuality.supportedAtOrBelow(it) } ?: autoQuality
        val cfg = quality.toConfig()
        val url = streamUrl.trim()
        val key = streamKey.trim()
        liveState = com.mediaviewer.stream.LiveStreamer.State.CONNECTING
        scope.launch {
            val error = withContext(Dispatchers.IO) { liveStreamer.start(url, key, cfg, withMic) }
            if (error != null) {
                liveState = com.mediaviewer.stream.LiveStreamer.State.IDLE
                if (error != "Cancelled") captureError = error
                return@launch
            }
            val surface = liveStreamer.inputSurface
            refreshCaptureOverlays()
            if (surface == null || !capture.startStream(surface, cfg.width, cfg.height, hostView.width to hostView.height, captureOverlayIds.isNotEmpty())) {
                withContext(Dispatchers.IO) { liveStreamer.stop() }
                liveState = com.mediaviewer.stream.LiveStreamer.State.IDLE
                captureError = "Couldn't start streaming the camera"
                return@launch
            }
            liveStartMs = android.os.SystemClock.elapsedRealtime()
            liveElapsedS = 0
            val link = com.mediaviewer.util.LiveLinkManager.normalizeStreamLink(streamLink, url)
            if (link != null) {
                liveBadgeUrl = link
                launch(Dispatchers.IO) {
                    com.mediaviewer.util.LiveLinkManager.setStreamLive(appContext, link, "Live now")
                        .onFailure { e -> withContext(Dispatchers.Main) { captureError = "Stream is live, but the Bluesky Live badge didn't update — ${e.message?.removePrefix("setLiveNowStatus failed: ")?.take(160) ?: "unknown error"}" } }
                }
            }
        }
    }
    fun endLive() {
        capture.stopStream()
        liveState = com.mediaviewer.stream.LiveStreamer.State.IDLE
        scope.launch(Dispatchers.IO) { liveStreamer.stop() }
        clearLiveBadge()
    }
    val liveMicPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> startLive(withMic = granted) }
    fun onGoLive() {
        liveDialogOpen = false
        store.put(K.STREAM_URL, streamUrl.trim())
        store.put(K.STREAM_KEY, streamKey.trim())
        store.put(K.STREAM_QUALITY, streamQualityName)
        store.put(K.STREAM_LINK, streamLink.trim())
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) startLive(withMic = true) else liveMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(isLive, liveStartMs) {
        var lastBadgeBump = android.os.SystemClock.elapsedRealtime()
        while (isLive) {
            kotlinx.coroutines.delay(500)
            if (liveStartMs > 0L) liveElapsedS = (android.os.SystemClock.elapsedRealtime() - liveStartMs) / 1000
            liveKbps = liveStreamer.measuredBps / 1000
            val badge = liveBadgeUrl
            if (badge != null && android.os.SystemClock.elapsedRealtime() - lastBadgeBump > 60 * 60_000L) {
                lastBadgeBump = android.os.SystemClock.elapsedRealtime()
                launch(Dispatchers.IO) { com.mediaviewer.util.LiveLinkManager.setStreamLive(appContext, badge, "Live now") }
            }
        }
    }
    LaunchedEffect(liveState) {
        if (liveState == com.mediaviewer.stream.LiveStreamer.State.FAILED ||
            liveState == com.mediaviewer.stream.LiveStreamer.State.ENDED) clearLiveBadge()
    }
    DisposableEffect(isLive) {
        hostView.keepScreenOn = isLive
        onDispose { hostView.keepScreenOn = false }
    }

    // ── Photo / video ───────────────────────────────────────────────────
    var recording by remember { mutableStateOf(false) }
    var recordingStartMs by remember { mutableStateOf(0L) }
    var recordingElapsedS by remember { mutableStateOf(0) }
    var captureBusy by remember { mutableStateOf(false) }
    fun finishRecording() {
        if (!recording) return
        recording = false
        captureBusy = true
        scope.launch {
            val uri = capture.stopRecording(context)
            captureBusy = false
            if (uri != null) onCapture(null, uri) else captureError = "Recording failed — nothing was saved"
        }
    }
    fun beginRecording(withAudio: Boolean) {
        refreshCaptureOverlays()
        if (capture.startRecording(context, hostView.width to hostView.height, withAudio, voicePitch.toFloat(), captureOverlayIds.isNotEmpty())) {
            recording = true
            recordingStartMs = android.os.SystemClock.elapsedRealtime()
            recordingElapsedS = 0
        } else captureError = "Couldn't start recording on this device"
    }
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> beginRecording(withAudio = granted) }
    LaunchedEffect(recording) {
        while (recording) {
            kotlinx.coroutines.delay(250)
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - recordingStartMs
            recordingElapsedS = (elapsedMs / 1000).toInt()
            if (elapsedMs >= VrmCaptureController.MAX_RECORDING_MS) finishRecording()
        }
    }
    LaunchedEffect(recording, isLive, captureOverlayIds) {
        while ((recording || isLive) && captureOverlayIds.isNotEmpty()) {
            refreshCaptureOverlays()
            kotlinx.coroutines.delay(100)
        }
    }
    LaunchedEffect(captureError) {
        if (captureError != null) { kotlinx.coroutines.delay(if ((captureError?.length ?: 0) > 60) 7000L else 3000L); captureError = null }
    }
    fun onCapturePressed() {
        if (captureBusy) return
        if (isLive) { endLiveConfirmOpen = true; return }
        if (!videoMode) {
            val tv = textureView ?: return
            captureBusy = true
            refreshCaptureOverlays()
            scope.launch {
                val uri = capture.takePhoto(context, tv, unmirror = frontCamera)
                captureBusy = false
                if (uri != null) onCapture(uri, null) else captureError = "Couldn't capture the photo"
            }
        } else if (recording) {
            finishRecording()
        } else if (micMuted) {
            beginRecording(withAudio = false)
        } else {
            val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (micGranted) beginRecording(withAudio = true) else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Volume keys = shutter, like the system camera (and VRM mode).
    val onCapturePressedRef = rememberUpdatedState { onCapturePressed() }
    val blockKeysRef = rememberUpdatedState(liveDialogOpen || isLive)
    DisposableEffect(Unit) {
        val handler: (android.view.KeyEvent) -> Boolean = handler@{ event ->
            if (event.keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP &&
                event.keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return@handler false
            if (blockKeysRef.value) return@handler false
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                tap()
                onCapturePressedRef.value()
            }
            true
        }
        com.mediaviewer.util.HardwareKeys.handler = handler
        onDispose {
            if (com.mediaviewer.util.HardwareKeys.handler === handler) com.mediaviewer.util.HardwareKeys.handler = null
        }
    }

    // Leaving: stop everything, then let the camera and GL thread go.
    val liveBadgeRef = rememberUpdatedState(liveBadgeUrl)
    DisposableEffect(Unit) {
        onDispose {
            capture.stopStream()
            capture.abandonRecording()
            Thread({ liveStreamer.stop() }, "live-stop").start()
            if (liveBadgeRef.value != null) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { com.mediaviewer.util.LiveLinkManager.clearStreamLive(appContext) }
            }
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            renderer.release()
        }
    }

    // Back: closes a popup first, then the page.
    androidx.activity.compose.BackHandler(onBack = {
        when {
            endLiveConfirmOpen -> endLiveConfirmOpen = false
            liveDialogOpen -> liveDialogOpen = false
            else -> onClose()
        }
    })

    // Live glass backdrop: the camera view, recorded so every bubble blurs
    // the real image behind it.
    val backdropLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val backdrop = remember(liquidGlass, backdropLayer) {
        if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
    }

    Box(Modifier.fillMaxSize().background(Color.Black).blockClicksBehind()) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                renderer.setDisplay(st, w, h)
                            }
                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                renderer.setDisplaySize(w, h)
                            }
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                // The GL thread lets go of it first, then it's freed.
                                renderer.setDisplay(null, 0, 0) { runCatching { st.release() } }
                                return false
                            }
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                        }
                        textureView = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (backdrop != null) Modifier
                            .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                            .drawWithContent {
                                backdropLayer.record { this@drawWithContent.drawContent() }
                                drawLayer(backdropLayer)
                            }
                        else Modifier
                    )
            )
            // Hides the switch between cameras (and the very first frame).
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = flipCover.value }.background(Color.Black))
            cameraError?.let { err ->
                Text(
                    err, color = Color.White.copy(0.85f), fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Stellar needs the camera for the Camera page.",
                    color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.padding(32.dp).clickable { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )
            }
            LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.CAMERA) }
        }

        VrmGlassBubble(
            size = 40.dp, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            modifier = Modifier.align(Alignment.TopStart).padding(top = rememberTopCutoutClearance(), start = 16.dp),
            onClick = { tap(); onClose() }
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
        }

        CaptureControlsBar(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            status = when {
                captureError != null -> captureError
                liveState == com.mediaviewer.stream.LiveStreamer.State.CONNECTING -> "Connecting…"
                liveState == com.mediaviewer.stream.LiveStreamer.State.RECONNECTING -> "Reconnecting…"
                isLive -> formatLiveClock(liveElapsedS) + if (liveKbps > 0) "  ·  ${"%.1f".format(liveKbps / 1000f)} Mbps" else ""
                captureBusy -> if (videoMode) "Saving video…" else "Saving photo…"
                recording -> "%d:%02d".format(maxOf(1, recordingElapsedS) / 60, maxOf(1, recordingElapsedS) % 60)
                else -> null
            },
            statusDot = (recording || liveState == com.mediaviewer.stream.LiveStreamer.State.LIVE) && captureError == null,
            micMuted = micMuted, micEnabled = !recording && !captureBusy, onToggleMic = { tap(); micMuted = !micMuted },
            videoMode = videoMode, swapEnabled = !recording && !captureBusy && !isLive, onToggleVideoMode = { tap(); videoMode = !videoMode },
            connecting = liveState == com.mediaviewer.stream.LiveStreamer.State.CONNECTING,
            isLive = isLive, captureBusy = captureBusy, recording = recording,
            onCapture = { tap(); onCapturePressed() },
            liveEnabled = hasCameraPermission && !recording && !captureBusy && !isLive,
            onLive = { tap(); liveDialogOpen = true },
            rightIcon = Icons.Default.Cameraswitch, rightDescription = "Flip camera",
            // Flipping mid-recording/stream would restart the camera under
            // the encoder — allowed only when idle.
            rightEnabled = !recording && !captureBusy && !isLive,
            onRight = {
                tap()
                scope.launch {
                    flipCover.animateTo(1f, tween(120))
                    // Wait for a frame from the NEW camera stream specifically
                    // (a stale frame from the old one used to lift — or, if
                    // it landed at the wrong moment, permanently stick — the
                    // cover), with a timeout so it can never stay black.
                    val before = renderer.streamCount
                    frontCamera = !frontCamera
                    kotlinx.coroutines.withTimeoutOrNull(3000) {
                        while (renderer.lastDrawnStream <= before) kotlinx.coroutines.delay(16)
                    }
                    flipCover.animateTo(0f, tween(220))
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (overlaysEnabled && browserOverlays.isNotEmpty()) {
            VrmBrowserOverlays(
                overlays = browserOverlays,
                tint = tint,
                registry = overlayRegistry,
                onChange = { updated -> browserOverlays = browserOverlays.map { if (it.id == updated.id) updated else it } },
                onRemove = { id -> browserOverlays = browserOverlays.filterNot { it.id == id } },
                hidden = liveDialogOpen || endLiveConfirmOpen
            )
        }

        if (liveDialogOpen) {
            VrmLiveDialog(
                liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                url = streamUrl, onUrl = { streamUrl = it },
                key = streamKey, onKey = { streamKey = it },
                qualityName = streamQualityName, onQuality = { streamQualityName = it },
                link = streamLink, onLink = { streamLink = it },
                autoQuality = autoQuality,
                onGoLive = { onGoLive() },
                onDismiss = { liveDialogOpen = false }
            )
        }
        if (endLiveConfirmOpen) {
            VrmConfirmDialog(
                liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                title = "End stream?",
                message = "You've been live for ${formatLiveClock(liveElapsedS)}.",
                confirmLabel = "End stream",
                onConfirm = { endLiveConfirmOpen = false; endLive() },
                onDismiss = { endLiveConfirmOpen = false }
            )
        }
    }
}

private const val TAG = "CameraModeScreen"
private const val KEY_FRONT = "camera_page_front"
private const val KEY_VIDEO_MODE = "camera_page_video_mode"

/** Photo / recording / stream outputs for the Camera page (main thread). */
private class CameraCaptureSession(private val renderer: CameraGlRenderer) {
    @Volatile var overlays: List<CaptureOverlay> = emptyList()

    private class Recording(
        val recorder: android.media.MediaRecorder,
        val targetId: Int,
        val file: java.io.File,
        val pitched: com.mediaviewer.stream.PitchedAudioRecorder?,
        val audioFile: java.io.File?,
        val compositor: OverlayCompositor?
    )
    private var recording: Recording? = null
    private var streamTargetId = 0
    private var streamCompositor: OverlayCompositor? = null

    suspend fun takePhoto(context: android.content.Context, view: TextureView, unmirror: Boolean = false): Uri? {
        if (!view.isAvailable || view.width <= 0) return null
        val shot = runCatching { view.bitmap }.getOrNull() ?: return null
        // The selfie preview is a mirror image; the saved photo is flipped
        // back to the true picture (like the phone's own camera app).
        val bitmap = if (!unmirror) shot else runCatching {
            val m = android.graphics.Matrix().apply { preScale(-1f, 1f, shot.width / 2f, shot.height / 2f) }
            val flipped = android.graphics.Bitmap.createBitmap(shot, 0, 0, shot.width, shot.height, m, true)
            val mutable = if (flipped.isMutable) flipped else flipped.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { flipped.recycle() }
            if (mutable !== shot) shot.recycle()
            mutable
        }.getOrDefault(shot)
        val shown = overlays
        if (shown.isNotEmpty()) runCatching {
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            for (o in shown) if (!o.bitmap.isRecycled) {
                canvas.drawBitmap(o.bitmap, null, overlayRectInFrame(o, view.width, view.height, bitmap.width, bitmap.height), paint)
            }
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = newFile(context, "jpg")
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                bitmap.recycle()
                uriFor(context, file)
            }.onFailure { Log.e(TAG, "Saving photo failed", it) }.getOrNull()
        }
    }

    fun startRecording(
        context: android.content.Context,
        screen: Pair<Int, Int>,
        withAudio: Boolean,
        pitchSemitones: Float,
        withOverlays: Boolean
    ): Boolean {
        if (recording != null) return true
        val (sw, sh) = screen
        // Same shape as the screen, long side ≤ 1280, sides multiple of 16.
        val scale = minOf(1f, 1280f / maxOf(sw, sh).coerceAtLeast(1))
        val w = ((sw * scale).toInt() / 16 * 16).coerceAtLeast(16)
        val h = ((sh * scale).toInt() / 16 * 16).coerceAtLeast(16)
        fun build(audio: Boolean): Pair<android.media.MediaRecorder, java.io.File>? {
            val file = newFile(context, "mp4")
            @Suppress("DEPRECATION")
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(context) else android.media.MediaRecorder()
            return try {
                if (audio) r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                r.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                r.setOutputFile(file.absolutePath)
                r.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
                r.setVideoSize(w, h)
                r.setVideoFrameRate(30)
                r.setVideoEncodingBitRate(8_000_000)
                if (audio) {
                    r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    r.setAudioSamplingRate(44_100)
                    r.setAudioEncodingBitRate(128_000)
                }
                r.setMaxDuration(VrmCaptureController.MAX_RECORDING_MS + 2_000)
                r.prepare()
                r to file
            } catch (e: Exception) {
                Log.e(TAG, "MediaRecorder setup failed (audio=$audio)", e)
                runCatching { r.release() }
                file.delete()
                null
            }
        }
        val pitched = withAudio && kotlin.math.abs(pitchSemitones) >= 0.05f
        val (recorder, file) = (if (withAudio && !pitched) build(true) else null) ?: build(false) ?: return false
        var compositor: OverlayCompositor? = null
        var audio: com.mediaviewer.stream.PitchedAudioRecorder? = null
        var audioFile: java.io.File? = null
        return try {
            val target = if (withOverlays && android.os.Build.VERSION.SDK_INT >= 29) {
                OverlayCompositor(recorder.surface, w, h, { screen }) { overlays }.also { compositor = it }.inputSurface
            } else recorder.surface
            recorder.start()
            val id = renderer.addEncoderTarget(target, w, h)
            if (pitched) {
                val af = newFile(context, "m4a")
                val a = com.mediaviewer.stream.PitchedAudioRecorder(af, pitchSemitones)
                if (a.start()) { audio = a; audioFile = af } else af.delete()
            }
            recording = Recording(recorder, id, file, audio, audioFile, compositor)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Starting recording failed", e)
            runCatching { compositor?.release() }
            runCatching { audio?.stop() }
            audioFile?.delete()
            runCatching { recorder.release() }
            file.delete()
            false
        }
    }

    suspend fun stopRecording(context: android.content.Context): Uri? {
        val rec = recording ?: return null
        recording = null
        renderer.removeTargetBlocking(rec.targetId)
        runCatching { rec.compositor?.release() }
        var finalFile = rec.file
        val ok = withContext(Dispatchers.IO) {
            val stopped = runCatching { rec.recorder.stop() }.onFailure { Log.e(TAG, "MediaRecorder.stop failed", it) }.isSuccess
            runCatching { rec.recorder.release() }
            val audioOk = rec.pitched?.stop() == true
            val audioFile = rec.audioFile
            if (stopped && audioOk && audioFile != null) {
                val merged = newFile(context, "mp4")
                if (com.mediaviewer.stream.PitchedAudioRecorder.muxVideoAndAudio(rec.file, audioFile, merged)) {
                    rec.file.delete()
                    finalFile = merged
                }
            }
            audioFile?.delete()
            stopped && finalFile.length() > 0
        }
        if (!ok) { finalFile.delete(); return null }
        return runCatching { uriFor(context, finalFile) }.getOrNull()
    }

    /** Closing the page mid-recording: stop and throw the file away. */
    fun abandonRecording() {
        val rec = recording ?: return
        recording = null
        renderer.removeTargetBlocking(rec.targetId)
        runCatching { rec.compositor?.release() }
        Thread({
            runCatching { rec.recorder.stop() }
            runCatching { rec.recorder.release() }
            runCatching { rec.pitched?.stop() }
            rec.audioFile?.delete()
            rec.file.delete()
        }, "camera-rec-abandon").start()
    }

    fun startStream(surface: android.view.Surface, width: Int, height: Int, screen: Pair<Int, Int>, withOverlays: Boolean): Boolean {
        if (streamTargetId != 0) return true
        return try {
            val target = if (withOverlays && android.os.Build.VERSION.SDK_INT >= 29) {
                OverlayCompositor(surface, width, height, { screen }) { overlays }.also { streamCompositor = it }.inputSurface
            } else surface
            streamTargetId = renderer.addEncoderTarget(target, width, height)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't render into the stream encoder", e)
            runCatching { streamCompositor?.release() }
            streamCompositor = null
            false
        }
    }

    /** Call BEFORE the stream encoder's surface is released. */
    fun stopStream() {
        val id = streamTargetId
        if (id == 0) return
        streamTargetId = 0
        renderer.removeTargetBlocking(id)
        runCatching { streamCompositor?.release() }
        streamCompositor = null
    }

    companion object {
        private fun newFile(context: android.content.Context, ext: String): java.io.File {
            val dir = java.io.File(context.cacheDir, "camera_capture").also { it.mkdirs() }
            return java.io.File(dir, "camera_${System.currentTimeMillis()}.$ext")
        }

        private fun uriFor(context: android.content.Context, file: java.io.File): Uri =
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
