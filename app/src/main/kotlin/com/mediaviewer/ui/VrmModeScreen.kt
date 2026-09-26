package com.mediaviewer.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.mediaviewer.util.FaceLandmarkerHelper
import com.mediaviewer.util.HandLandmarkerHelper
import com.mediaviewer.util.OneEuroFilterBank
import com.mediaviewer.util.PoseLandmarkerHelper
import java.util.concurrent.Executors
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mediaviewer.util.PreferencesManager
import com.mediaviewer.util.VrmData
import com.mediaviewer.util.VrmParser
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Item 8: VRM/VTuber mode — the camera-notch button's "VRM" action.
 *
 * ## What's real and running in this file right now
 * A live front-camera preview (CameraX) behind the same record/settings
 * chrome the finished feature will use, plus the actual permission
 * request flow. You can build and run this today and see your own camera
 * feed full-screen with working Close/Settings/Record buttons.
 *
 * ## What's deliberately NOT here yet, and why
 * The real VTuber pipeline — tracking a face/hands/body and puppeting an
 * imported VRM avatar with it, the way Prism/VSeeFace do — is a large,
 * separate engineering effort that genuinely needs a physical device to
 * tune (smoothing constants, retargeting offsets, frame timing), not
 * something to fake finish blind in one editing pass. Rather than hand back
 * something that *looks* done but silently does nothing, this scaffold is
 * honest about the boundary: everything below the preview is real; the
 * spot where tracking/rendering plugs in is one clearly marked function,
 * [VrmTrackingOverlay], with the concrete next steps in its own doc
 * comment. The plan (confirmed workable, same approach Prism/VSeeFace use):
 *
 * 1. **Tracking** — ✅ done. MediaPipe Tasks Vision, running on-device via
 *    its GPU delegate: `FaceLandmarker` (478 points + the 52 ARKit-style
 *    blendshape scores), `HandLandmarker` (21 points per hand),
 *    `PoseLandmarker` (33 body points, world-space), all fed from this
 *    screen's CameraX `ImageAnalysis` use case below.
 * 2. **Smoothing** — ✅ done for face blendshapes ([OneEuroFilter]/
 *    [OneEuroFilterBank] in `util/`, applied in [VrmTrackingOverlay]);
 *    the same filter bank applies identically to hand/pose landmark
 *    coordinates once step 4 is the thing consuming them — no separate
 *    filter implementation needed, just more keys in a bank.
 * 3. **VRM parsing** — ✅ done. [com.mediaviewer.util.VrmParser] extracts
 *    the humanoid bone map and expression morph-target map out of a
 *    `.vrm` file's glTF extension block (VRM 0.x and 1.0 both supported).
 *    Filament itself doesn't know what VRM is, only plain glTF, so this
 *    runs as a separate pass over the same file bytes, alongside step 4.
 * 4. **Rendering** — ✅ done, unposed. [VrmAvatarView] loads the picked
 *    `.vrm` file as glTF via Google's Filament and renders it centered/
 *    orbitable — proving the file loads and displays before anything
 *    drives it. The Settings sheet's "Choose VRM avatar…" row is the only
 *    way to get a file in, since nothing is bundled (see [VrmAvatarView]'s
 *    doc comment).
 * 5. **Retargeting** — ✅ done. Expressions: the 52 ARKit blendshapes
 *    drive the morph-target binds step 3 found
 *    ([AvatarRetargeter.applyExpressions], via a hand-tuned ARKit→VRM
 *    heuristic — see its own doc comment) live, every frame. Head/neck
 *    rotation: [AvatarRetargeter.applyPose] turns MediaPipe's
 *    per-frame face transformation matrix into a head (and, split
 *    proportionally, neck) bone rotation. Arm rotation:
 *    [AvatarRetargeter.applyPose] turns `PoseLandmarker`'s
 *    world-space shoulder/elbow/wrist points into upper-arm + forearm
 *    rotation on both sides, gated on [trackUpperBody]. Leg rotation:
 *    [AvatarRetargeter.applyPose] does the identical thing with
 *    hip/knee/ankle points, gated on [trackFullBody] (which the Settings
 *    sheet already keeps off unless [trackUpperBody] is also on). The
 *    avatar now visibly turns its head, raises/moves its arms, and moves
 *    its legs — not just blinks/mouths shapes in a fixed T-pose. **This
 *    was the whole VRM pipeline as originally scoped**; see
 *    [AvatarRetargeter]'s doc comment for what's still flagged as needing
 *    real-device verification (axis-remap signs, coordinate-space
 *    assumptions, left/right mirroring) before any of steps 1–6 should be
 *    trusted beyond "the pipeline exists end to end." Item 1 (the
 *    unrelated media3 thumbnail-stitching migration) is the only item
 *    left open project-wide.
 *
 * See the handoff document for the exact dependency coordinates and file
 * layout to add next.
 */
@Composable
fun VrmModeScreen(
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    // Non-null exactly one of these once a capture is taken — same shape as
    // the camera-notch button's own Camera action, so both funnel into the
    // same "open the composer with this attached" flow in MainActivity.
    onCapture: (imageUri: Uri?, videoUri: Uri?) -> Unit
) {
    val context = LocalContext.current
    val tap = rememberHapticTap()

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    // Settings (Item 8: "though these should be Settings" — upper/full body
    // tracking specifically; face + hands are always on). Local state for
    // now — promoting these into PreferencesManager so they persist across
    // sessions is a one-line follow-up (see the handoff document) once this
    // screen actually has something to gate with them.
    var settingsOpen by remember { mutableStateOf(false) }
    // Off by default: pose tracking is the most expensive of the three
    // landmarkers (full-body BlazePose on the GPU every frame), and the
    // phone getting hot in VRM mode traced largely to it running from
    // the moment the screen opened. The Settings sheet's "Upper Body"
    // toggle turns it on when the user actually wants it.
    // Every setting below is remembered across launches (VrmSettingsStore):
    // read once here, written back whenever it changes.
    val store = remember { com.mediaviewer.util.VrmSettingsStore(context) }
    val K = com.mediaviewer.util.VrmSettingsStore
    var trackUpperBody by remember { mutableStateOf(store.bool(K.UPPER_BODY, false)) }
    var trackFullBody by remember { mutableStateOf(store.bool(K.FULL_BODY, false)) }
    // Video-call / filter framing: the avatar's head sits where yours is in
    // the (mirrored) camera frame instead of being locked to the centre.
    var followHead by remember { mutableStateOf(store.bool(K.FOLLOW_HEAD, true)) }
    // 0 = raw tracking, 10 = heaviest smoothing.
    var smoothing by remember { mutableStateOf(store.int(K.SMOOTHING, K.DEFAULT_SMOOTHING).coerceIn(0, 10)) }
    // ~30 fps tracking instead of ~15 fps (more battery/heat).
    var fastTracking by remember { mutableStateOf(store.bool(K.FAST_TRACKING, false)) }
    // Manual eyes: blink tracking off, openness set by the slider.
    var manualEyes by remember { mutableStateOf(store.bool(K.MANUAL_EYES, false)) }
    var eyeClosed by remember { mutableStateOf(store.float(K.EYE_CLOSED, 0f).coerceIn(0f, 1f)) }
    var springBones by remember { mutableStateOf(store.bool(K.SPRING_BONES, true)) }
    var showDebug by remember { mutableStateOf(store.bool(K.SHOW_DEBUG, false)) }
    var showPreview by remember { mutableStateOf(store.bool(K.SHOW_PREVIEW, true)) }
    var videoMode by remember { mutableStateOf(store.bool(K.VIDEO_MODE, false)) }
    var fullBright by remember { mutableStateOf(store.bool(K.FULL_BRIGHT, false)) }
    var armIk by remember { mutableStateOf(store.bool(K.ARM_IK, false)) }
    androidx.compose.runtime.LaunchedEffect(armIk) { store.put(K.ARM_IK, armIk) }
    var cameraResetKey by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(videoMode) { store.put(K.VIDEO_MODE, videoMode) }
    androidx.compose.runtime.LaunchedEffect(fullBright) { store.put(K.FULL_BRIGHT, fullBright) }

    var captureError by remember { mutableStateOf<String?>(null) }
    // Mic mute: applies to video recordings (no audio track) and, live,
    // to the stream (silence is sent so the audio track never drops out).
    var micMuted by remember { mutableStateOf(store.bool(K.MIC_MUTED, false)) }
    androidx.compose.runtime.LaunchedEffect(micMuted) { store.put(K.MIC_MUTED, micMuted) }
    var lightLevel by remember { mutableStateOf(store.int(K.LIGHT_LEVEL, 5).coerceIn(0, 10)) }
    androidx.compose.runtime.LaunchedEffect(lightLevel) { store.put(K.LIGHT_LEVEL, lightLevel) }

    // ── Capture (photo / video of the rendered avatar) ──
    val captureController = remember { VrmCaptureController() }

    // ── Live streaming (RTMP) ──
    var liveDialogOpen by remember { mutableStateOf(false) }
    var endLiveConfirmOpen by remember { mutableStateOf(false) }
    var liveState by remember { mutableStateOf(com.mediaviewer.stream.LiveStreamer.State.IDLE) }
    var liveStartMs by remember { mutableStateOf(0L) }
    var liveElapsedS by remember { mutableStateOf(0L) }
    var liveKbps by remember { mutableStateOf(0L) }
    var streamUrl by remember { mutableStateOf(store.string(K.STREAM_URL)) }
    var streamKey by remember { mutableStateOf(store.string(K.STREAM_KEY)) }
    var streamQualityName by remember { mutableStateOf(store.string(K.STREAM_QUALITY)) }
    val autoQuality = remember { com.mediaviewer.stream.StreamQuality.auto(context) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val liveStreamer = remember {
        com.mediaviewer.stream.LiveStreamer(context, object : com.mediaviewer.stream.LiveStreamer.Listener {
            override fun onStateChanged(state: com.mediaviewer.stream.LiveStreamer.State, message: String?) {
                mainHandler.post {
                    liveState = state
                    if (state == com.mediaviewer.stream.LiveStreamer.State.FAILED ||
                        state == com.mediaviewer.stream.LiveStreamer.State.ENDED) {
                        captureController.stopStreamOutput()
                    }
                    if (state == com.mediaviewer.stream.LiveStreamer.State.FAILED && message != null) captureError = message
                }
            }
        })
    }
    // "Live" covers connecting/reconnecting too: the UI stays in live mode.
    val isLive = liveState == com.mediaviewer.stream.LiveStreamer.State.LIVE ||
        liveState == com.mediaviewer.stream.LiveStreamer.State.RECONNECTING ||
        liveState == com.mediaviewer.stream.LiveStreamer.State.CONNECTING
    androidx.compose.runtime.SideEffect { liveStreamer.micMuted = micMuted }
    var recording by remember { mutableStateOf(false) }
    var recordingStartMs by remember { mutableStateOf(0L) }
    var recordingElapsedS by remember { mutableStateOf(0) }
    var captureBusy by remember { mutableStateOf(false) }
    val captureScope = androidx.compose.runtime.rememberCoroutineScope()
    fun finishRecording() {
        if (!recording) return
        recording = false
        captureBusy = true
        captureScope.launch {
            val uri = captureController.stopRecording(context)
            captureBusy = false
            if (uri != null) onCapture(null, uri) else captureError = "Recording failed — nothing was saved"
        }
    }
    fun beginRecording(withAudio: Boolean) {
        if (captureController.startRecording(context, withAudio)) {
            recording = true
            recordingStartMs = android.os.SystemClock.elapsedRealtime()
            recordingElapsedS = 0
        } else captureError = "Couldn't start recording on this device"
    }
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> beginRecording(withAudio = granted) }

    fun startLive(withMic: Boolean) {
        val quality = com.mediaviewer.stream.StreamQuality.values().firstOrNull { it.name == streamQualityName }
            ?.let { com.mediaviewer.stream.StreamQuality.supportedAtOrBelow(it) } ?: autoQuality
        val cfg = quality.toConfig()
        val url = streamUrl.trim()
        val key = streamKey.trim()
        liveState = com.mediaviewer.stream.LiveStreamer.State.CONNECTING
        captureScope.launch {
            val error = withContext(Dispatchers.IO) { liveStreamer.start(url, key, cfg, withMic) }
            if (error != null) {
                liveState = com.mediaviewer.stream.LiveStreamer.State.IDLE
                if (error != "Cancelled") captureError = error
                return@launch
            }
            val surface = liveStreamer.inputSurface
            if (surface == null || !captureController.startStreamOutput(surface, cfg.width, cfg.height, cfg.fps)) {
                withContext(Dispatchers.IO) { liveStreamer.stop() }
                liveState = com.mediaviewer.stream.LiveStreamer.State.IDLE
                captureError = "Couldn't start streaming the avatar"
                return@launch
            }
            liveStartMs = android.os.SystemClock.elapsedRealtime()
            liveElapsedS = 0
        }
    }
    fun endLive() {
        captureController.stopStreamOutput()
        liveState = com.mediaviewer.stream.LiveStreamer.State.IDLE
        captureScope.launch(Dispatchers.IO) { liveStreamer.stop() }
    }
    val liveMicPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> startLive(withMic = granted) }
    fun onGoLive() {
        liveDialogOpen = false
        store.put(K.STREAM_URL, streamUrl.trim())
        store.put(K.STREAM_KEY, streamKey.trim())
        store.put(K.STREAM_QUALITY, streamQualityName)
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) startLive(withMic = true) else liveMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Stream clock: counts up for as long as the stream runs (no limit).
    androidx.compose.runtime.LaunchedEffect(isLive, liveStartMs) {
        while (isLive) {
            kotlinx.coroutines.delay(500)
            if (liveStartMs > 0L) liveElapsedS = (android.os.SystemClock.elapsedRealtime() - liveStartMs) / 1000
            liveKbps = liveStreamer.measuredBps / 1000
        }
    }
    // Never let the screen sleep mid-stream.
    val hostView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(isLive) {
        hostView.keepScreenOn = isLive
        onDispose { hostView.keepScreenOn = false }
    }
    // Leaving VRM mode ends the stream.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            captureController.stopStreamOutput()
            Thread({ liveStreamer.stop() }, "live-stop").start()
        }
    }
    // Recording timer: 0:01 … 10:00, then it stops by itself.
    androidx.compose.runtime.LaunchedEffect(recording) {
        while (recording) {
            kotlinx.coroutines.delay(250)
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - recordingStartMs
            recordingElapsedS = (elapsedMs / 1000).toInt()
            if (elapsedMs >= VrmCaptureController.MAX_RECORDING_MS) finishRecording()
        }
    }
    androidx.compose.runtime.LaunchedEffect(captureError) {
        if (captureError != null) { kotlinx.coroutines.delay(3000); captureError = null }
    }
    fun onCapturePressed() {
        if (captureBusy) return
        if (isLive) { endLiveConfirmOpen = true; return }
        if (!videoMode) {
            captureBusy = true
            captureScope.launch {
                val uri = captureController.takePhoto(context)
                captureBusy = false
                if (uri != null) onCapture(uri, null) else captureError = "Couldn't capture the photo"
            }
        } else if (recording) {
            finishRecording()
        } else if (micMuted) {
            beginRecording(withAudio = false)
        } else {
            val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (micGranted) beginRecording(withAudio = true) else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Volume up/down = the capture button (photo, or start/stop recording),
    // like the system camera. The key is swallowed so the volume doesn't
    // change; holding it down doesn't repeat. Not while settings are open.
    val onCapturePressedRef = androidx.compose.runtime.rememberUpdatedState { onCapturePressed() }
    val settingsOpenRef = androidx.compose.runtime.rememberUpdatedState(settingsOpen || liveDialogOpen || isLive)
    androidx.compose.runtime.DisposableEffect(Unit) {
        val handler: (android.view.KeyEvent) -> Boolean = handler@{ event ->
            if (event.keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP &&
                event.keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return@handler false
            if (settingsOpenRef.value) return@handler false
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
    // The loaded avatar's toggleable meshes, and which are hidden.
    var avatarParts by remember { mutableStateOf<List<AvatarPart>>(emptyList()) }
    var hiddenParts by remember { mutableStateOf(store.strings(K.HIDDEN_PARTS)) }
    androidx.compose.runtime.LaunchedEffect(trackUpperBody) { store.put(K.UPPER_BODY, trackUpperBody) }
    androidx.compose.runtime.LaunchedEffect(trackFullBody) { store.put(K.FULL_BODY, trackFullBody) }
    androidx.compose.runtime.LaunchedEffect(followHead) { store.put(K.FOLLOW_HEAD, followHead) }
    androidx.compose.runtime.LaunchedEffect(smoothing) { store.put(K.SMOOTHING, smoothing) }
    androidx.compose.runtime.LaunchedEffect(fastTracking) { store.put(K.FAST_TRACKING, fastTracking) }
    androidx.compose.runtime.LaunchedEffect(manualEyes) { store.put(K.MANUAL_EYES, manualEyes) }
    androidx.compose.runtime.LaunchedEffect(eyeClosed) { store.put(K.EYE_CLOSED, eyeClosed) }
    androidx.compose.runtime.LaunchedEffect(springBones) { store.put(K.SPRING_BONES, springBones) }
    androidx.compose.runtime.LaunchedEffect(showDebug) { store.put(K.SHOW_DEBUG, showDebug) }
    androidx.compose.runtime.LaunchedEffect(showPreview) { store.put(K.SHOW_PREVIEW, showPreview) }
    androidx.compose.runtime.LaunchedEffect(hiddenParts) { store.put(K.HIDDEN_PARTS, hiddenParts) }
    val trackerGate = remember { TrackerGate() }

    // Step 1 verification state (see doc comment above): the latest result
    // from each landmarker, updated from VrmCameraTracking's ImageAnalysis
    // callback. Read by VrmTrackingOverlay to print debug scores — these
    // fields are *only* for that on-device sanity check and go away once
    // step 2 (smoothing) and step 3 (retargeting) consume the results
    // directly instead.
    var latestFaceResult by remember { mutableStateOf<FaceLandmarkerResult?>(null) }
    var latestHandResult by remember { mutableStateOf<HandLandmarkerResult?>(null) }
    var latestPoseResult by remember { mutableStateOf<PoseLandmarkerResult?>(null) }
    var faceResultCount by remember { mutableStateOf(0) }

    // Item 8, VRM pipeline step 5 — the user's picked `.vrm` avatar file.
    // `vrmBytes` is what actually drives VrmAvatarView/VrmParser; `pickedVrmUri`
    // is just the persisted pointer to it (see PreferencesManager.setVrmAvatarUri's
    // doc comment for why it needs takePersistableUriPermission). Restored
    // once on entering this screen below, then re-read whenever the picker
    // returns a new choice.
    val prefsManager = remember { PreferencesManager(context) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var pickedVrmUri by remember { mutableStateOf<Uri?>(null) }
    var vrmBytes by remember { mutableStateOf<ByteArray?>(null) }
    var parsedVrmData by remember { mutableStateOf<VrmData?>(null) }
    // True when we had a saved/picked Uri but the file itself couldn't be
    // opened (moved, deleted, or its permission died on reinstall) — the
    // empty state then says so explicitly instead of the generic "no
    // avatar picked" hint, because "black screen, nothing explains why"
    // is exactly what a dead Uri looks like.
    var vrmFileUnreadable by remember { mutableStateOf(false) }

    // MediaPipe task files are created here, but NOT on the UI thread:
    // FaceLandmarker.createFromOptions compiles GPU shaders and loads a
    // multi-MB .task model — doing that on the main thread during
    // composition (three helpers in a row, as before) froze the screen for
    // seconds and was a big part of why entering VRM mode felt so slow.
    // They now load in the background; the camera preview/binding only
    // starts once they're ready, and the screen shows a small "Warming up"
    // line meanwhile.
    var trackersReady by remember { mutableStateOf(false) }
    var faceHelper by remember { mutableStateOf<FaceLandmarkerHelper?>(null) }
    var handHelper by remember { mutableStateOf<HandLandmarkerHelper?>(null) }
    var poseHelper by remember { mutableStateOf<PoseLandmarkerHelper?>(null) }
    var faceHelperError by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraFrameCount by remember { mutableStateOf(0) }
    // Size of the upright (already-rotated) frame MediaPipe sees — used by
    // the tracking preview box to draw landmarks at the right aspect ratio.
    var trackingFrameWidth by remember { mutableStateOf(480) }
    var trackingFrameHeight by remember { mutableStateOf(640) }

    // ONE single-thread executor owns every call into the MediaPipe helpers:
    // the camera analyzer runs on it, and the helpers are closed on it too.
    // Because it's single-threaded, "close" is queued strictly after any
    // frame that's already being analyzed — so a detectAsync() can never
    // race a close() on another thread (a native crash, and one of the ways
    // the X button used to take the app down). Nothing here ever blocks the
    // UI thread waiting on it.
    val trackingExecutor = remember { Executors.newSingleThreadExecutor() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Genuinely off the UI thread this time. The old version ran inside
        // a plain LaunchedEffect — which is the MAIN dispatcher — so building
        // three GPU landmarker pipelines froze the UI for seconds on entry
        // and delayed the avatar load right along with it. Activity context
        // is still used (not applicationContext) — only the thread changed.
        //
        // Whatever gets created is parked in `pending` until ownership is
        // handed to Compose state; if the screen closes mid-creation, the
        // finally block closes them instead of leaking three GPU graphs.
        val pending = arrayOfNulls<Any>(3)
        var handedOff = false
        try {
            withContext(Dispatchers.Default) {
                pending[0] = FaceLandmarkerHelper.create(
                    context,
                    onResult = {
                        trackerGate.release(TrackerGate.FACE)
                        latestFaceResult = it
                        faceResultCount++
                    },
                    onError = { faceHelperError = it }
                )
                pending[1] = HandLandmarkerHelper.create(context, onResult = {
                    trackerGate.release(TrackerGate.HAND)
                    latestHandResult = it
                })
                pending[2] = PoseLandmarkerHelper.create(context, onResult = {
                    trackerGate.release(TrackerGate.POSE)
                    latestPoseResult = it
                })
            }
            faceHelper = pending[0] as FaceLandmarkerHelper?
            handHelper = pending[1] as HandLandmarkerHelper?
            poseHelper = pending[2] as PoseLandmarkerHelper?
            handedOff = true
            trackersReady = true
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.e("VrmModeScreen", "Failed to create MediaPipe task helpers", t)
        } finally {
            if (!handedOff) {
                runCatching { (pending[0] as FaceLandmarkerHelper?)?.close() }
                runCatching { (pending[1] as HandLandmarkerHelper?)?.close() }
                runCatching { (pending[2] as PoseLandmarkerHelper?)?.close() }
            }
        }
    }
    // Teardown order on close (see trackingExecutor's comment): the camera
    // is unbound by VrmCameraTracking's own onDispose (it's a child, so it
    // disposes first), then the helpers are closed ON the tracking thread,
    // queued behind any in-flight frame, then the executor winds down.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val face = faceHelper
            val hand = handHelper
            val pose = poseHelper
            runCatching {
                trackingExecutor.execute {
                    runCatching { face?.close() }.onFailure { android.util.Log.e("VrmModeScreen", "faceHelper.close() failed", it) }
                    runCatching { hand?.close() }.onFailure { android.util.Log.e("VrmModeScreen", "handHelper.close() failed", it) }
                    runCatching { pose?.close() }.onFailure { android.util.Log.e("VrmModeScreen", "poseHelper.close() failed", it) }
                }
            }.onFailure { android.util.Log.e("VrmModeScreen", "Couldn't queue helper close", it) }
            trackingExecutor.shutdown()
        }
    }
    // Item 8, VRM pipeline step 6 — the node-index→entity bridge into
    // whatever VrmAvatarView just rendered; null until a model has
    // actually finished loading. See AvatarRetargeter.kt's doc comment.
    var retargetTarget by remember { mutableStateOf<RetargetTarget?>(null) }

    // Diagnostic: how many MToon textures were actually bound during load.
    // Distinguishes "model has no textures" from "binding failed".
    var texturesApplied by remember { mutableStateOf(-1) }
    var materialsPatched by remember { mutableStateOf("") }

    // Shared once here (not duplicated inside VrmTrackingOverlay) so the
    // debug overlay's five-blendshape subset and step 6's full-52
    // retargeting call are reading the exact same smoothed values for the
    // exact same frame, rather than two independent OneEuroFilterBank
    // instances drifting slightly apart from each other.
    val blendshapeFilters = remember { OneEuroFilterBank(minCutoff = 1.0, beta = 0.3, dCutoff = 1.0) }
    val smoothedBlendshapes = remember(latestFaceResult) { smoothedFaceBlendshapes(latestFaceResult, blendshapeFilters) }
    // Item 8, VRM pipeline step 6 (bone-rotation half) — MediaPipe's raw
    // per-frame head-pose matrix, unsmoothed (unlike the blendshapes
    // above): AvatarRetargeter.applyPose applies it as an absolute,
    // mirrored head rotation and smooths it over time itself.
    val headMatrix = remember(latestFaceResult) { headTransformationMatrix(latestFaceResult) }
    // Item 8, VRM pipeline step 6 (arm rotation) — unlike the head's face
    // matrix, raw pose landmarks are noisy enough that calibration alone
    // doesn't hide it, so these get the same OneEuroFilterBank treatment
    // face blendshapes already get, in a bank of its own (per
    // OneEuroFilterBank's own doc comment: face/body tuning may need to
    // diverge, and a bank is cheap).
    // beta is per unit of speed: these landmarks are in metres (an arm
    // swing is ~1-2 m/s), so beta 8 opens the filter to ~10-17 Hz during
    // fast moves — the old 0.3 left it at ~1.5 Hz, i.e. visible lag.
    val poseFilters = remember { OneEuroFilterBank(minCutoff = 1.0, beta = 8.0, dCutoff = 1.0) }
    val smoothedBodyLandmarks = remember(latestPoseResult) { smoothedBodyWorldLandmarks(latestPoseResult, poseFilters) }
    // Finger curls, keyed by the AVATAR's side (mirroring already applied).
    // Hand world landmarks are hand-centred metres (small, slower numbers).
    val handFilters = remember { OneEuroFilterBank(minCutoff = 1.5, beta = 20.0, dCutoff = 1.0) }
    val handPoints = remember(latestHandResult, latestPoseResult, trackUpperBody, trackingFrameWidth, trackingFrameHeight) {
        avatarHands(
            latestHandResult, if (trackUpperBody) latestPoseResult else null, latestFaceResult,
            trackingFrameWidth, trackingFrameHeight, handFilters
        )
    }
    // Where your eyes are in the camera frame (mirrored like the preview) —
    // drives "follow my head". The last known placement is held while the
    // face is briefly lost, so the avatar doesn't snap back to the centre.
    val framingFilters = remember { OneEuroFilterBank(minCutoff = 1.2, beta = 2.0, dCutoff = 1.0) }
    val lastFraming = remember { arrayOfNulls<AvatarFraming>(1) }
    // Smoothing slider → every filter bank + the retargeter's bone easing.
    // 5 = the tuned defaults; 0 = raw; 10 = twice as smooth.
    androidx.compose.runtime.SideEffect {
        val strength = smoothing / K.DEFAULT_SMOOTHING.toDouble()
        blendshapeFilters.strength = strength
        poseFilters.strength = strength
        handFilters.strength = strength
        framingFilters.strength = strength
        AvatarRetargeter.smoothingScale = strength.toFloat()
    }
    val framing = remember(latestFaceResult, trackingFrameWidth, trackingFrameHeight) {
        faceFraming(latestFaceResult, trackingFrameWidth, trackingFrameHeight, framingFilters)
            ?.also { lastFraming[0] = it } ?: lastFraming[0]
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        prefsManager.vrmAvatarUri.firstOrNull()?.let { pickedVrmUri = Uri.parse(it) }
    }
    androidx.compose.runtime.LaunchedEffect(pickedVrmUri) {
        val uri = pickedVrmUri
        avatarParts = emptyList()
        if (uri == null) {
            vrmBytes = null
            parsedVrmData = null
            return@LaunchedEffect
        }
        // Read the picked file off the UI thread, and parse it there too —
        // VrmParser on a multi-MB .vrm is not free. Both states are
        // assigned back-to-back AFTER the last suspension point, so no
        // recomposition can ever observe new vrmBytes with a stale
        // parsedVrmData (VrmAvatarView keys its load off vrmBytes and
        // reads parsedVrmData for the VRM 0.x facing fix — a torn update
        // there would flip the wrong way and never correct itself).
        val bytes = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                .onFailure { android.util.Log.e("VrmModeScreen", "Could not read picked VRM file", it) }
                .getOrNull()
        }
        vrmFileUnreadable = bytes == null
        val parsed = if (bytes != null) {
            withContext(Dispatchers.Default) { runCatching { VrmParser.parse(bytes) }.getOrNull() }
        } else {
            null
        }
        vrmBytes = bytes
        parsedVrmData = parsed
    }
    val vrmAvatarPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Persisted-permission grant, not the (process-lifetime-only) grant
        // OpenDocument's result Uri comes with by default — without this,
        // the stored Uri fails to open the next time the app cold-starts.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { android.util.Log.e("VrmModeScreen", "Could not persist VRM file permission", it) }
        // A newly picked avatar gets a fresh try at full-quality textures,
        // and all of its parts start visible.
        com.mediaviewer.util.CrashBreadcrumbs.resetVrmTextureMode()
        if (uri != pickedVrmUri) hiddenParts = emptySet()
        pickedVrmUri = uri
        coroutineScope.launch { prefsManager.setVrmAvatarUri(uri.toString()) }
    }

    // Item 8, VRM pipeline step 6 (expression half only — see
    // AvatarRetargeter.kt's doc comment) — pushes this frame's smoothed
    // blendshape scores onto whatever avatar is currently loaded. A plain
    // SideEffect: this is "sync the current values to an external system
    // (Filament's native scene graph)" exactly as Compose's own docs
    // describe that API for, not state Compose itself owns.
    androidx.compose.runtime.SideEffect {
        val target = retargetTarget
        val vrmData = parsedVrmData
        if (target != null && vrmData != null) {
            // Manual eyes: blink tracking replaced by the slider's value.
            val scores = if (manualEyes) smoothedBlendshapes + mapOf(
                "eyeBlinkLeft" to eyeClosed, "eyeBlinkRight" to eyeClosed,
                "eyeSquintLeft" to 0f, "eyeSquintRight" to 0f,
                "eyeWideLeft" to 0f, "eyeWideRight" to 0f
            ) else smoothedBlendshapes
            AvatarRetargeter.applyExpressions(target, vrmData, scores)
            // One call poses the whole skeleton (hips → spine → head → arms
            // → hands → fingers → legs), mirrored like the preview. Body
            // data is only passed while "Upper Body" is on (otherwise it'd be
            // stale); without it the arms rest in a relaxed arms-down pose.
            AvatarRetargeter.applyPose(
                target,
                TrackingFrame(
                    faceMatrix = headMatrix,
                    body = if (trackUpperBody) smoothedBodyLandmarks else null,
                    trackLegs = trackFullBody,
                    hands = handPoints,
                    armIk = armIk
                )
            )
        }
    }

    // Live backdrop for the glass buttons: the avatar layer re-records itself
    // into this every frame (the same technique the feed's glass uses), so
    // every bubble can blur whatever part of the avatar sits behind it.
    val backdropLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val backdrop = remember(liquidGlass, backdropLayer) {
        if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            // Headless — see VrmCameraTracking's doc comment for why this
            // renders nothing. Camera frames still drive tracking exactly
            // as before; they're just never displayed. Only bound once the
            // MediaPipe task files have finished loading in the background
            // (see trackersReady above) — binding earlier would run a
            // camera with nowhere to send its frames, pointlessly burning
            // battery while the helpers still load.
            if (trackersReady && faceHelper != null && handHelper != null) {
                VrmCameraTracking(
                    trackingExecutor = trackingExecutor,
                    faceHelper = faceHelper,
                    handHelper = handHelper,
                    poseHelper = poseHelper,
                    trackPose = trackUpperBody,
                    gate = trackerGate,
                    frameIntervalMs = if (fastTracking) 33L else 66L,
                    onFrame = { w, h ->
                        cameraFrameCount++
                        if (w != trackingFrameWidth) trackingFrameWidth = w
                        if (h != trackingFrameHeight) trackingFrameHeight = h
                    },
                    onCameraError = { cameraError = it }
                )
            } else {
                Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(top = 72.dp)) {
                    Text(
                        "Warming up trackers…",
                        color = Color.White.copy(0.7f), fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(0.35f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            // Item 8, step 5 — Filament rendering the picked VRM avatar
            // file, driven by tracking — the only visible layer in VRM
            // mode; there's no camera feed underneath it anymore.
            if (vrmBytes != null) {
                VrmAvatarView(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (backdrop != null) Modifier
                                .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                                .drawWithContent {
                                    backdropLayer.record { this@drawWithContent.drawContent() }
                                    drawContent()
                                }
                            else Modifier
                        ),
                    vrmBytes = vrmBytes,
                    parsedVrmData = parsedVrmData,
                    // Item: VRM background should be a flat fill of the
                    // user's own profile color, not Filament's default
                    // black — `tint` here is already exactly that (see
                    // MainActivity's vrmTint: the logged-in user's own
                    // avatar dominant color), same color the X button and
                    // bottom bar already wear.
                    backgroundTint = tint,
                    onRetargetTargetReady = { retargetTarget = it },
                    onTexturesApplied = { texturesApplied = it },
                    onMaterialsPatched = { materialsPatched = it },
                    framing = framing,
                    followTracking = followHead,
                    onPartsReady = { parts ->
                        avatarParts = parts
                        // Keep choices for the same file; drop ids it no longer has.
                        if (parts.isNotEmpty()) {
                            val ids = parts.map { it.id }.toSet()
                            hiddenParts = hiddenParts.filter { it in ids }.toSet()
                        }
                    },
                    hiddenParts = hiddenParts,
                    springBones = springBones,
                    maxFps = if (fastTracking) 60 else 30,
                    fullBright = fullBright,
                    cameraResetKey = cameraResetKey,
                    captureController = captureController,
                    lightLevel = 0.4f + lightLevel * 0.12f
                )
            } else {
                // Prominent, not a 12sp hint: a dead/missing avatar file is
                // otherwise just "black screen, nothing explains why". The
                // button launches the picker right here — no detour through
                // Settings needed.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (vrmFileUnreadable)
                                "Couldn't open your saved avatar file — it may have been moved, deleted, or its permission died. Pick it again:"
                            else
                                "No avatar picked yet — choose a .vrm file:",
                            color = Color.White.copy(0.85f), fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.Button(
                            onClick = { vrmAvatarPickerLauncher.launch(arrayOf("*/*")) }
                        ) {
                            Text("Choose .vrm file")
                        }
                    }
                }
            }
            // Item 8, step 3 above — this is where the tracked/retargeted
            // VRM avatar renders once the pipeline exists; today it just
            // prints step 1's raw landmarker output so tracking itself can
            // be confirmed working before anything is built on top of it.
            // Debug readout: off unless turned on in settings.
            if (showDebug) VrmTrackingOverlay(
                tint = tint,
                trackUpperBody = trackUpperBody,
                trackFullBody = trackFullBody,
                smoothedFaceBlendshapes = smoothedBlendshapes,
                faceHelperError = faceHelperError,
                faceResultCount = faceResultCount,
                texturesApplied = texturesApplied,
                materialsPatched = materialsPatched,
                cameraError = cameraError,
                cameraFrameCount = cameraFrameCount,
                handResult = latestHandResult,
                poseResult = latestPoseResult,
                parsedVrmData = parsedVrmData,
                retargetTarget = retargetTarget,
                headTrackingActive = headMatrix != null,
                armTrackingActive = smoothedBodyLandmarks.isNotEmpty(),
                legTrackingActive = smoothedBodyLandmarks.isNotEmpty(),
                modifier = Modifier.fillMaxSize()
            )
            // Small black "what the tracker sees" box, top-right: landmark
            // dots/skeleton only — never the camera image itself (VRM mode
            // deliberately never shows the user's real face). Toggleable.
            if (showPreview) TrackingPreview(
                faceResult = latestFaceResult,
                handResult = latestHandResult,
                poseResult = if (trackUpperBody) latestPoseResult else null,
                frameWidth = trackingFrameWidth,
                frameHeight = trackingFrameHeight,
                tint = tint,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(end = 12.dp, top = 12.dp)
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "RaccNet needs the camera to track your face for VRM mode.",
                    color = Color.White, fontSize = 14.sp,
                    modifier = Modifier
                        .padding(32.dp)
                        .clickable { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.CAMERA) }
        }

        // Close button, top — mirrors every other full-screen overlay's own
        // top-left close affordance in this app. Tinted glass like the rest
        // of VRM mode's buttons, blurring the avatar behind it.
        VrmGlassBubble(
            size = 40.dp, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            onClick = { tap(); onClose() }
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
        }

        // Bottom bar: [mic] [photo/video mode] [capture] [settings] [Live].
        // The capture button shows what it will do (camera or video icon;
        // stop while recording; "Live" while streaming); the second bubble
        // shows the OTHER photo/video mode and switches to it. While
        // recording or live, a timer sits above.
        androidx.compose.foundation.layout.Column(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val status = when {
                captureError != null -> captureError
                liveState == com.mediaviewer.stream.LiveStreamer.State.CONNECTING -> "Connecting…"
                liveState == com.mediaviewer.stream.LiveStreamer.State.RECONNECTING -> "Reconnecting…"
                isLive -> formatLiveClock(liveElapsedS) + if (liveKbps > 0) "  ·  ${"%.1f".format(liveKbps / 1000f)} Mbps" else ""
                captureBusy -> if (videoMode) "Saving video…" else "Saving photo…"
                recording -> "%d:%02d".format(maxOf(1, recordingElapsedS) / 60, maxOf(1, recordingElapsedS) % 60)
                else -> null
            }
            Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                if (status != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if ((recording || liveState == com.mediaviewer.stream.LiveStreamer.State.LIVE) && captureError == null) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(status, color = Color.White, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val gap = 14.dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Far left: mic mute. Locked mid-recording (a MediaRecorder
                // can't add/drop its audio track once started); live, it
                // mutes the stream instantly.
                val micEnabled = !recording && !captureBusy
                VrmGlassBubble(
                    size = 48.dp, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    enabled = micEnabled,
                    onClick = { tap(); micMuted = !micMuted }
                ) {
                    Icon(
                        if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (micMuted) "Unmute microphone" else "Mute microphone",
                        tint = (if (micMuted) Color(0xFFFF6B61) else Color.White).copy(alpha = if (micEnabled) 1f else 0.35f),
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(gap))
                // Switch photo ↔ video (disabled while recording/saving/live).
                val swapEnabled = !recording && !captureBusy && !isLive
                VrmGlassBubble(
                    size = 48.dp, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    enabled = swapEnabled,
                    onClick = { tap(); videoMode = !videoMode }
                ) {
                    Icon(
                        if (videoMode) Icons.Default.PhotoCamera else Icons.Default.Videocam,
                        contentDescription = if (videoMode) "Switch to photo" else "Switch to video",
                        tint = Color.White.copy(alpha = if (swapEnabled) 1f else 0.35f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(gap))
                // Centre: capture — or, while streaming, the Live button.
                VrmGlassBubble(
                    size = 72.dp, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    enabled = !captureBusy,
                    border = if (recording || isLive) Color(0xFFFF3B30) else null,
                    onClick = { tap(); onCapturePressed() }
                ) {
                    when {
                        liveState == com.mediaviewer.stream.LiveStreamer.State.CONNECTING -> androidx.compose.material3.CircularProgressIndicator(
                            color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(26.dp)
                        )
                        isLive -> Text("Live", color = Color(0xFFFF3B30), fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        captureBusy -> androidx.compose.material3.CircularProgressIndicator(
                            color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(26.dp)
                        )
                        recording -> Icon(Icons.Default.Stop, contentDescription = "Stop recording", tint = Color(0xFFFF3B30), modifier = Modifier.size(34.dp))
                        videoMode -> Icon(Icons.Default.Videocam, contentDescription = "Record video", tint = Color.White, modifier = Modifier.size(32.dp))
                        else -> Icon(Icons.Default.PhotoCamera, contentDescription = "Take photo", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
                Spacer(Modifier.width(gap))
                // Settings.
                VrmGlassBubble(
                    size = 48.dp, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onClick = { tap(); settingsOpen = true }
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "VRM Settings", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(gap))
                // Far right: Live (opens the stream setup popup).
                val liveEnabled = !recording && !captureBusy && !isLive && vrmBytes != null
                VrmGlassBubble(
                    size = 48.dp, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    enabled = liveEnabled,
                    onClick = { tap(); liveDialogOpen = true }
                ) {
                    Text(
                        "Live", color = Color.White.copy(alpha = if (liveEnabled) 1f else 0.35f),
                        fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            }
        }

        if (liveDialogOpen) {
            VrmLiveDialog(
                liquidGlass = liquidGlass,
                tint = tint,
                backdrop = backdrop,
                url = streamUrl, onUrl = { streamUrl = it },
                key = streamKey, onKey = { streamKey = it },
                qualityName = streamQualityName, onQuality = { streamQualityName = it },
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

        if (settingsOpen) {
            VrmSettingsSheet(
                liquidGlass = liquidGlass,
                tint = tint,
                backdrop = backdrop,
                ui = VrmSettingsUi(
                    trackUpperBody = trackUpperBody, onToggleUpperBody = { trackUpperBody = it },
                    trackFullBody = trackFullBody, onToggleFullBody = { trackFullBody = it },
                    followHead = followHead, onToggleFollowHead = { followHead = it },
                    smoothing = smoothing, onSmoothing = { smoothing = it },
                    fastTracking = fastTracking, onToggleFastTracking = { fastTracking = it },
                    manualEyes = manualEyes, onToggleManualEyes = { manualEyes = it },
                    eyeClosed = eyeClosed, onEyeClosed = { eyeClosed = it },
                    springBones = springBones, onToggleSpringBones = { springBones = it },
                    showPreview = showPreview, onTogglePreview = { showPreview = it },
                    showDebug = showDebug, onToggleDebug = { showDebug = it },
                    fullBright = fullBright, onToggleFullBright = { fullBright = it },
                    armIk = armIk, onToggleArmIk = { armIk = it },
                    lightLevel = lightLevel, onLightLevel = { lightLevel = it },
                    onResetCamera = { cameraResetKey++ },
                    avatarParts = avatarParts,
                    hiddenParts = hiddenParts,
                    onSetPartVisible = { id, visible -> hiddenParts = if (visible) hiddenParts - id else hiddenParts + id },
                    onShowAllParts = { hiddenParts = emptySet() },
                    hasAvatar = vrmBytes != null,
                    onPickAvatar = { vrmAvatarPickerLauncher.launch(arrayOf("*/*")) }
                ),
                onDismiss = { settingsOpen = false }
            )
        }
    }
}

/** VRM mode's camera input — deliberately **not visual**: this used to
 *  wrap CameraX's `PreviewView` in an `AndroidView` and draw it full-
 *  screen underneath [VrmAvatarView]'s own `SurfaceView`, on the theory
 *  that the avatar always visually "covers" the feed once one's loaded.
 *  Two problems with that: (1) VRM mode should never show the raw camera
 *  feed at all — only the virtual model — and (2) two independently
 *  hardware-composited `SurfaceView`s stacked in the same window don't
 *  reliably z-order the way regular `View`s do, which was almost
 *  certainly contributing to the avatar rendering incorrectly (see
 *  [VrmAvatarView]'s doc comment). Fixed by not binding a `Preview` use
 *  case (the one that needs a visible surface) at all — camera frames
 *  only ever need to reach `ImageAnalysis`'s analyzer, which needs no
 *  surface of its own, so there's nothing to display and nothing to
 *  composite against the Filament view. Throttled frames are decoded to a
 *  [Bitmap]/`MPImage` exactly once here, then handed to the landmarker
 *  helpers VrmModeScreen created (face + hands always; pose only while
 *  [trackPose] is on, per the "Upper Body" Settings toggle) — avoids each
 *  helper redoing the same YUV conversion three times over. Each helper
 *  was constructed with its own result listener by VrmModeScreen, so
 *  detections flow straight back up as Compose state. If a model asset
 *  isn't bundled (see [FaceLandmarkerHelper]'s doc comment), that
 *  helper's `create` returns null and its slot is simply skipped. */
@Composable
private fun VrmCameraTracking(
    trackingExecutor: java.util.concurrent.ExecutorService,
    faceHelper: FaceLandmarkerHelper?,
    handHelper: HandLandmarkerHelper?,
    poseHelper: PoseLandmarkerHelper?,
    trackPose: Boolean,
    gate: TrackerGate,
    /** 33 ms (~30 fps, "Fast tracking") or 66 ms (~15 fps). */
    frameIntervalMs: Long = 66L,
    onFrame: (uprightWidth: Int, uprightHeight: Int) -> Unit = { _, _ -> },
    onCameraError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = androidx.compose.ui.platform.LocalView.current
    // Only touched from the analyzer thread.
    val lastSubmittedMs = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    // Read on the analyzer thread, written from composition — so toggling
    // "Upper Body" no longer tears the whole camera down and rebinds it.
    val trackPoseFlag = remember { java.util.concurrent.atomic.AtomicBoolean(trackPose) }
    val intervalMs = remember { java.util.concurrent.atomic.AtomicLong(frameIntervalMs) }
    androidx.compose.runtime.SideEffect { trackPoseFlag.set(trackPose); intervalMs.set(frameIntervalMs) }
    val frameBitmaps = remember { FrameBitmaps() }
    val analysisHolder = remember { arrayOfNulls<ImageAnalysis>(1) }
    // Scratch buffers for de-striding camera rows (analyzer thread only).
    val tightBufferHolder = remember { arrayOfNulls<java.nio.ByteBuffer>(1) }
    val rowScratchHolder = remember { arrayOfNulls<ByteArray>(1) }

    // ── Why portrait was broken and landscape worked ────────────────────
    // 1. setTargetResolution(640, 480) is interpreted in the CURRENT screen
    //    orientation. Bound in portrait, CameraX went looking for something
    //    480x640-after-rotation and picked a much bigger sensor mode. Bound
    //    in landscape (tilting the phone while the trackers warmed up), it
    //    got exactly 640x480. ResolutionSelector's bound size is always in
    //    the sensor's own frame, so it's the same small size either way.
    // 2. copyPixelsFromBuffer assumed rows are packed (rowStride == width*4).
    //    Those bigger modes are often row-padded, which produced a sheared,
    //    garbage image — MediaPipe never finds a face in it, keeps running
    //    expensive full-frame face + palm DETECTION every frame, and starves
    //    Filament's GPU so the model's textures load slowly / halfway.
    // 3. The rotation was handed to MediaPipe as ImageProcessingOptions and
    //    never updated after binding. Now the frame is rotated upright here
    //    with a plain Matrix (the same approach Google's own MediaPipe
    //    samples use), and the rotation is kept current as the phone turns.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val orientationListener = object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> android.view.Surface.ROTATION_270
                    in 135 until 225 -> android.view.Surface.ROTATION_180
                    in 225 until 315 -> android.view.Surface.ROTATION_90
                    else -> android.view.Surface.ROTATION_0
                }
                analysisHolder[0]?.let { if (it.targetRotation != rotation) it.targetRotation = rotation }
            }
        }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        onDispose {
            orientationListener.disable()
            // Unbind FIRST so no new frame is ever delivered. The analyzer
            // is cleared too, so CameraX drops its reference to our executor
            // before VrmModeScreen shuts that executor down.
            runCatching { analysisHolder[0]?.clearAnalyzer() }
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }.onFailure { android.util.Log.e("VrmModeScreen", "unbindAll() on close failed", it) }
            analysisHolder[0] = null
            // Free the reused frame bitmaps on the analyzer thread, after
            // any frame still being converted there.
            runCatching { trackingExecutor.execute { frameBitmaps.release() } }
        }
    }

    // Bound exactly once per screen entry.
    LaunchedEffect(Unit) {
        val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                androidx.camera.core.resolutionselector.ResolutionStrategy(
                    android.util.Size(640, 480),
                    androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(view.display?.rotation ?: android.view.Surface.ROTATION_0)
            .build()
        analysis.setAnalyzer(trackingExecutor) { imageProxy ->
            val nowMs = SystemClock.uptimeMillis()
            // ~15fps cap into the landmarkers; OneEuro smoothing covers the gaps.
            if (nowMs - lastSubmittedMs.get() < intervalMs.get()) {
                imageProxy.close()
                return@setAnalyzer
            }
            lastSubmittedMs.set(nowMs)
            val upright: Bitmap = try {
                imageProxy.use { proxy -> proxyToUprightBitmap(proxy, tightBufferHolder, rowScratchHolder, frameBitmaps) }
            } catch (t: Throwable) {
                android.util.Log.e("VrmModeScreen", "Frame conversion failed", t)
                return@setAnalyzer
            }
            val mpImage = BitmapImageBuilder(upright).build()
            // Rotation 0: the bitmap is already upright (see note above).
            // uptimeMillis, not currentTimeMillis — LIVE_STREAM mode rejects
            // timestamps that ever go backwards, and wall-clock time can.
            // Each detector only gets the frame if its previous one is done.
            try {
                if (faceHelper != null && gate.tryAcquire(TrackerGate.FACE, nowMs)) faceHelper.detectAsync(mpImage, 0, nowMs)
                if (handHelper != null && gate.tryAcquire(TrackerGate.HAND, nowMs)) handHelper.detectAsync(mpImage, 0, nowMs)
                if (trackPoseFlag.get() && poseHelper != null && gate.tryAcquire(TrackerGate.POSE, nowMs)) poseHelper.detectAsync(mpImage, 0, nowMs)
            } finally {
                // Pixels were copied inside detectAsync; free the wrapper.
                runCatching { mpImage.close() }
            }
            onFrame(upright.width, upright.height)
        }
        analysisHolder[0] = analysis
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }.onFailure {
            android.util.Log.e("VrmModeScreen", "Could not bind CameraX ImageAnalysis", it)
            onCameraError("Camera bind failed: ${it.message}")
        }
    }
}

/**
 * At most ONE frame in flight per landmarker. MediaPipe's GPU pipelines
 * can fall behind the camera; handing them a new frame before the last
 * result came back let work queue up inside them, so tracking got
 * steadily more delayed the longer VRM mode ran. A detector that's still
 * busy simply skips this frame. A result that never arrives (a dropped or
 * failed frame) frees its slot after [STUCK_MS].
 */
private class TrackerGate {
    private val busySince = Array(3) { java.util.concurrent.atomic.AtomicLong(0L) }
    fun tryAcquire(slot: Int, nowMs: Long): Boolean {
        val since = busySince[slot].get()
        if (since != 0L && nowMs - since < STUCK_MS) return false
        busySince[slot].set(nowMs)
        return true
    }
    fun release(slot: Int) = busySince[slot].set(0L)
    companion object {
        const val FACE = 0; const val HAND = 1; const val POSE = 2
        const val STUCK_MS = 500L
    }
}

/** Reused frame bitmaps (analyzer thread only): no per-frame allocation. */
private class FrameBitmaps {
    var raw: Bitmap? = null
    var upright: Bitmap? = null
    fun release() { raw?.recycle(); upright?.recycle(); raw = null; upright = null }
}

/** RGBA_8888 ImageProxy → upright ARGB_8888 Bitmap. Handles padded rows
 *  (rowStride > width*4), which the old straight copyPixelsFromBuffer did
 *  not, then rotates by the frame's rotationDegrees so the face is upright
 *  before MediaPipe ever sees it. Runs on the tracking thread only. */
private fun proxyToUprightBitmap(
    proxy: androidx.camera.core.ImageProxy,
    tightBufferHolder: Array<java.nio.ByteBuffer?>,
    rowScratchHolder: Array<ByteArray?>,
    bitmaps: FrameBitmaps
): Bitmap {
    val width = proxy.width
    val height = proxy.height
    val plane = proxy.planes[0]
    val source = plane.buffer
    source.rewind()
    val rowBytes = width * 4
    // Reused every frame — MediaPipe copies the pixels synchronously in
    // detectAsync, so nothing holds on to these afterwards. The old code
    // allocated two fresh ~1.2 MB bitmaps per frame and never freed them.
    val raw = bitmaps.raw?.takeIf { it.width == width && it.height == height && !it.isRecycled }
        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmaps.raw?.recycle(); bitmaps.raw = it }
    if (plane.rowStride == rowBytes && plane.pixelStride == 4) {
        raw.copyPixelsFromBuffer(source)
    } else {
        var tight = tightBufferHolder[0]
        if (tight == null || tight.capacity() != rowBytes * height) {
            tight = java.nio.ByteBuffer.allocateDirect(rowBytes * height)
            tightBufferHolder[0] = tight
        }
        var row = rowScratchHolder[0]
        if (row == null || row.size != rowBytes) {
            row = ByteArray(rowBytes)
            rowScratchHolder[0] = row
        }
        tight!!.clear()
        for (y in 0 until height) {
            source.position(y * plane.rowStride)
            source.get(row, 0, rowBytes)
            tight.put(row, 0, rowBytes)
        }
        tight.rewind()
        raw.copyPixelsFromBuffer(tight)
    }
    val rotation = proxy.imageInfo.rotationDegrees
    if (rotation == 0) return raw
    val sideways = rotation == 90 || rotation == 270
    val outW = if (sideways) height else width
    val outH = if (sideways) width else height
    val upright = bitmaps.upright?.takeIf { it.width == outW && it.height == outH && !it.isRecycled }
        ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { bitmaps.upright?.recycle(); bitmaps.upright = it }
    val matrix = android.graphics.Matrix().apply {
        postRotate(rotation.toFloat(), width / 2f, height / 2f)
        postTranslate((outW - width) / 2f, (outH - height) / 2f)
    }
    android.graphics.Canvas(upright).drawBitmap(raw, matrix, null)
    return upright
}

/** VRM pipeline step 2 (smoothing): every current ARKit blendshape score,
 *  run through [filters] and keyed by blendshape name — e.g.
 *  `{"jawOpen": 0.42, "eyeBlinkLeft": 0.91, ...}`. Returns an empty map
 *  (and resets `filters`' `"face."`-prefixed history, so a later
 *  reacquisition isn't smoothed across the gap — see
 *  [OneEuroFilter.reset]'s doc comment) when no face is currently
 *  detected. Shared by both [VrmTrackingOverlay]'s debug text and
 *  [AvatarRetargeter.applyExpressions] — see the call site in
 *  [VrmModeScreen] for why computing this once matters. */
private fun smoothedFaceBlendshapes(faceResult: FaceLandmarkerResult?, filters: OneEuroFilterBank): Map<String, Float> {
    val blendshapes = faceResult?.faceBlendshapes()?.orElse(null)?.firstOrNull()
    if (blendshapes == null) {
        filters.resetPrefixed("face.")
        return emptyMap()
    }
    // MediaPipe's own result timestamp (ms), not wall-clock time — this is
    // the correct clock for the filter: it's the source the frames were
    // actually captured against, so dt stays correct even if this is
    // computed later than capture. `timestampMs()` is inherited from
    // tasks-vision's common TaskResult base class — same "unverified
    // against the pinned AAR" caveat as the rest of this pipeline (see
    // FaceLandmarkerHelper's doc comment); if the pinned version
    // disagrees, Android Studio will point at this exact line.
    val timestampSeconds = faceResult.timestampMs() / 1000.0
    return blendshapes.associate { category ->
        val name = category.categoryName()
        name to filters.filter("face.$name", category.score(), timestampSeconds)
    }
}

/** VRM pipeline step 6 (bone-rotation half): the current frame's facial
 *  transformation matrix, straight from MediaPipe — a 16-float flattened
 *  4x4 per detected face, or null if none is detected. Assumed shape is
 *  `Optional<List<FloatArray>>` (mirroring `faceBlendshapes()`'s own
 *  `Optional<List<...>>` just above, and the same "unverified against the
 *  pinned tasks-vision AAR" caveat as everywhere else in this file — see
 *  FaceLandmarkerHelper's doc comment). No smoothing here (unlike
 *  [smoothedFaceBlendshapes]) — see the call site's own comment for why
 *  [AvatarRetargeter.applyPose] smooths it itself. */
private fun headTransformationMatrix(faceResult: FaceLandmarkerResult?): FloatArray? =
    faceResult?.facialTransformationMatrixes()?.orElse(null)?.firstOrNull()

// Shoulders, elbows, wrists, hand points (pinky/index knuckles, for wrist
// orientation), hips, knees, ankles — BlazePose's 33-point topology.
private val BODY_LANDMARK_INDICES = intArrayOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 23, 24, 25, 26, 27, 28)

/** Pose world landmarks (meters, hip-centred) for [BODY_LANDMARK_INDICES],
 *  position One-Euro smoothed, plus each point's visibility so the
 *  retargeter can ignore joints MediaPipe is only guessing at (off-frame
 *  elbows/wrists are the common case in a selfie). Empty when no pose. */
private fun smoothedBodyWorldLandmarks(poseResult: PoseLandmarkerResult?, filters: OneEuroFilterBank): Map<Int, BodyPoint> {
    val result = poseResult ?: run {
        filters.resetPrefixed("pose.")
        return emptyMap()
    }
    val worldLandmarks = result.worldLandmarks().firstOrNull()
    if (worldLandmarks == null) {
        filters.resetPrefixed("pose.")
        return emptyMap()
    }
    val imageLandmarks = result.landmarks().firstOrNull()
    val timestampSeconds = result.timestampMs() / 1000.0
    val smoothed = mutableMapOf<Int, BodyPoint>()
    for (index in BODY_LANDMARK_INDICES) {
        val landmark = worldLandmarks.getOrNull(index) ?: continue
        val visibility = imageLandmarks?.getOrNull(index)?.visibility()?.orElse(null)
            ?: landmark.visibility().orElse(1f)
        smoothed[index] = BodyPoint(
            filters.filter("pose.$index.x", landmark.x(), timestampSeconds),
            filters.filter("pose.$index.y", landmark.y(), timestampSeconds),
            filters.filter("pose.$index.z", landmark.z(), timestampSeconds),
            visibility
        )
    }
    return smoothed
}

/**
 * Tracked hands per AVATAR side: HandLandmarker's 21 world landmarks
 * (wrist/finger orientation) plus where the wrist is relative to your eyes
 * (drives the arm IK). All One-Euro smoothed.
 *
 * Which physical hand is which — by position, never by MediaPipe's
 * left/right label (that label came out reversed on real devices, which is
 * why the hands swapped whenever body tracking was off):
 *  1. Body tracking on: the nearest pose wrist.
 *  2. Otherwise, relative to your face: our frames are NOT mirrored, so
 *     your right hand appears on the image's left. Two hands: the one
 *     further left in the image is your right.
 *  3. No face either: the label, as a last resort.
 * Then, mirrored, your right hand drives the avatar's left.
 */
private fun avatarHands(
    hands: HandLandmarkerResult?,
    pose: PoseLandmarkerResult?,
    face: FaceLandmarkerResult?,
    frameWidth: Int,
    frameHeight: Int,
    filters: OneEuroFilterBank
): Map<String, TrackedHand> {
    if (hands == null || hands.worldLandmarks().isEmpty()) {
        filters.resetPrefixed("finger.")
        filters.resetPrefixed("handpos.")
        return emptyMap()
    }
    val world = hands.worldLandmarks()
    val image = hands.landmarks()
    val handedness = hands.handednesses()
    val poseImage = pose?.landmarks()?.firstOrNull()?.takeIf { it.size > 16 }
    val facePoints = face?.faceLandmarks()?.firstOrNull()?.takeIf { it.size > 362 }
    val timestampSeconds = hands.timestampMs() / 1000.0
    val W = frameWidth.toFloat().coerceAtLeast(1f)
    val H = frameHeight.toFloat().coerceAtLeast(1f)

    // Person side per detected hand.
    val sides = arrayOfNulls<String>(world.size)
    val wristX = FloatArray(world.size) { i -> image.getOrNull(i)?.getOrNull(0)?.x() ?: 0.5f }
    val wristY = FloatArray(world.size) { i -> image.getOrNull(i)?.getOrNull(0)?.y() ?: 0.5f }
    if (poseImage != null) {
        for (i in world.indices) {
            val l = poseImage[15]; val r = poseImage[16]
            val dl = (wristX[i] - l.x()) * (wristX[i] - l.x()) + (wristY[i] - l.y()) * (wristY[i] - l.y())
            val dr = (wristX[i] - r.x()) * (wristX[i] - r.x()) + (wristY[i] - r.y()) * (wristY[i] - r.y())
            sides[i] = if (dl <= dr) "left" else "right"
        }
    } else if (world.size >= 2) {
        val leftmost = if (wristX[0] <= wristX[1]) 0 else 1
        sides[leftmost] = "right"; sides[1 - leftmost] = "left"
    } else if (facePoints != null) {
        val faceX = (facePoints[33].x() + facePoints[263].x()) / 2f
        sides[0] = if (wristX[0] < faceX) "right" else "left"
    } else {
        sides[0] = personSideFromLabel(handedness.getOrNull(0)?.firstOrNull()?.categoryName())
    }

    // Camera model for the position estimate: focal length ≈ 0.7 × the
    // long side (front cameras are ~70-75° across the long side).
    val f = 0.7f * maxOf(W, H)
    // Your eyes: 3-D spacing (so turning your head doesn't read as moving
    // away) against a 63 mm average interpupillary distance → depth.
    val eyes: FloatArray? = facePoints?.let { p ->
        val ax = (p[33].x() + p[133].x()) / 2f * W; val ay = (p[33].y() + p[133].y()) / 2f * H; val az = (p[33].z() + p[133].z()) / 2f * W
        val bx = (p[362].x() + p[263].x()) / 2f * W; val by = (p[362].y() + p[263].y()) / 2f * H; val bz = (p[362].z() + p[263].z()) / 2f * W
        val px = kotlin.math.sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz))
        if (px < 1f) null else {
            val z = f * 0.063f / px
            floatArrayOf(((ax + bx) / 2f - W / 2f) * z / f, ((ay + by) / 2f - H / 2f) * z / f, z)
        }
    }

    val out = HashMap<String, TrackedHand>()
    for (i in world.indices) {
        val personSide = sides[i] ?: continue
        val avatarSide = if (AvatarRetargeter.MIRROR) (if (personSide == "left") "right" else "left") else personSide
        if (out.containsKey(avatarSide)) continue
        val w = world.getOrNull(i)?.takeIf { it.size >= 21 } ?: continue
        val img = image.getOrNull(i)?.takeIf { it.size >= 21 }
        val points = w.mapIndexed { k, p ->
            floatArrayOf(
                filters.filter("finger.$avatarSide.$k.x", p.x(), timestampSeconds),
                filters.filter("finger.$avatarSide.$k.y", p.y(), timestampSeconds),
                filters.filter("finger.$avatarSide.$k.z", p.z(), timestampSeconds)
            )
        }
        // Wrist depth from the palm's apparent size: real length (world
        // landmarks, metres) over on-screen length (pixels). The least
        // foreshortened palm edge gives the truest reading.
        var offset: FloatArray? = null
        if (eyes != null && img != null) {
            var best = 0f
            for ((a, b) in PALM_EDGES) {
                val pxLen = kotlin.math.hypot((img[a].x() - img[b].x()) * W, (img[a].y() - img[b].y()) * H)
                val dx = w[a].x() - w[b].x(); val dy = w[a].y() - w[b].y(); val dz = w[a].z() - w[b].z()
                val mLen = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                if (mLen > 0.02f) best = maxOf(best, pxLen / mLen)
            }
            if (best > 0f) {
                val z = f / best
                val raw = floatArrayOf(
                    (img[0].x() * W - W / 2f) * z / f - eyes[0],
                    (img[0].y() * H - H / 2f) * z / f - eyes[1],
                    (z - eyes[2]).coerceIn(-0.7f, 0.3f) // depth is the least reliable axis
                )
                offset = FloatArray(3) { k -> filters.filter("handpos.$avatarSide.$k", raw[k], timestampSeconds) }
            }
        }
        if (offset == null) filters.resetPrefixed("handpos.$avatarSide.")
        out[avatarSide] = TrackedHand(points, offset)
    }
    for (side in listOf("left", "right")) if (!out.containsKey(side)) {
        filters.resetPrefixed("finger.$side.")
        filters.resetPrefixed("handpos.$side.")
    }
    return out
}

/** Palm edges (wrist–index, wrist–middle, wrist–pinky, index–pinky). */
private val PALM_EDGES = listOf(0 to 5, 0 to 9, 0 to 17, 5 to 17)

/** MediaPipe's handedness label → the person's actual hand. Only a last
 *  resort (see [avatarHands]); on devices tested the label matched the
 *  person's own hand directly. */
private fun personSideFromLabel(label: String?): String? = when (label) {
    "Left" -> "left"
    "Right" -> "right"
    else -> null
}

/** Eye position/spacing for "follow my head": centre of each eye from its
 *  two corners (33/133 and 362/263), mirrored like the preview, and the
 *  3-D spacing in frame pixels (3-D so turning your head doesn't shrink
 *  the avatar). One-Euro smoothed. Null when no face. */
private fun faceFraming(
    faceResult: FaceLandmarkerResult?,
    frameWidth: Int,
    frameHeight: Int,
    filters: OneEuroFilterBank
): AvatarFraming? {
    val points = faceResult?.faceLandmarks()?.firstOrNull()
    if (points == null || points.size <= 362 || frameWidth <= 0 || frameHeight <= 0) {
        filters.resetPrefixed("frame.")
        return null
    }
    fun eye(a: Int, b: Int) = floatArrayOf(
        (points[a].x() + points[b].x()) / 2f, (points[a].y() + points[b].y()) / 2f, (points[a].z() + points[b].z()) / 2f
    )
    val e1 = eye(33, 133)
    val e2 = eye(362, 263)
    val w = frameWidth.toFloat()
    val h = frameHeight.toFloat()
    val dx = (e1[0] - e2[0]) * w
    val dy = (e1[1] - e2[1]) * h
    val dz = (e1[2] - e2[2]) * w // MediaPipe's z is on roughly the same scale as x
    val spacing = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    val cx = (e1[0] + e2[0]) / 2f
    val cy = (e1[1] + e2[1]) / 2f
    val t = faceResult.timestampMs() / 1000.0
    return AvatarFraming(
        anchorX = filters.filter("frame.x", if (AvatarRetargeter.MIRROR) 1f - cx else cx, t),
        anchorY = filters.filter("frame.y", cy, t),
        eyeDistancePx = filters.filter("frame.d", spacing, t),
        frameWidth = frameWidth,
        frameHeight = frameHeight
    )
}

/**
 * Item 8, steps 1–6: where the tracked, retargeted VRM avatar renders
 * (see [VrmAvatarView]/[AvatarRetargeter]) plus a debug readout overlaid
 * on top of it. [smoothedFaceBlendshapes] is the *same* map — computed
 * once per frame in [VrmModeScreen], shared with [AvatarRetargeter]'s
 * expression retargeting — that this overlay picks five representative
 * entries out of, purely so the debug text can never show different
 * numbers than what's actually driving the avatar's face that frame.
 *
 * Hand/pose landmark coordinates aren't smoothed at all yet — there's no
 * debug value in smoothing the raw landmark *count* this overlay shows
 * for those, and bone-rotation retargeting (the still-unimplemented half
 * of step 6 — see [AvatarRetargeter]'s doc comment) is what would
 * actually consume smoothed coordinates.
 *
 * [trackUpperBody]/[trackFullBody] are threaded through from Settings —
 * [trackUpperBody] actually gates whether pose tracking runs at all (see
 * [VrmCameraTracking]'s `trackPose` param); [trackFullBody] doesn't change
 * anything yet, it's reserved for gating whether bone-rotation
 * retargeting uses the leg landmarks PoseLandmarker already outputs.
 * Face + hand tracking always run regardless of either.
 */
@Composable
private fun VrmTrackingOverlay(
    tint: Color,
    trackUpperBody: Boolean,
    trackFullBody: Boolean,
    smoothedFaceBlendshapes: Map<String, Float>,
    faceHelperError: String?,
    faceResultCount: Int,
    texturesApplied: Int,
    materialsPatched: String,
    cameraError: String?,
    cameraFrameCount: Int,
    handResult: HandLandmarkerResult?,
    poseResult: PoseLandmarkerResult?,
    parsedVrmData: VrmData?,
    retargetTarget: RetargetTarget?,
    headTrackingActive: Boolean,
    armTrackingActive: Boolean,
    legTrackingActive: Boolean,
    modifier: Modifier = Modifier
) {
    // Just a handful of representative blendshapes — enough to see live
    // movement (blink, jaw, smile) without dumping all 52 scores on screen.
    val debugBlendshapeNames = listOf("jawOpen", "eyeBlinkLeft", "eyeBlinkRight", "mouthSmileLeft", "mouthSmileRight")
    val cameraLine = when {
        cameraError != null -> "camera: FAILED\n$cameraError"
        cameraFrameCount > 0 -> "camera: streaming ($cameraFrameCount frames)"
        else -> "camera: bound, waiting for frames…"
    }
    val faceLine = if (smoothedFaceBlendshapes.isNotEmpty()) {
        debugBlendshapeNames.joinToString("\n") { name ->
            "$name: ${"%.2f".format(smoothedFaceBlendshapes[name] ?: 0f)}"
        }
    } else if (faceHelperError != null) {
        "face: FAILED to start\n$faceHelperError"
    } else if (faceResultCount > 0) {
        "face: landmarker running ($faceResultCount results), no face detected\n(is your face in the front camera frame?)"
    } else {
        "face: no landmarker output yet\n(check face_landmarker.task in assets/)"
    }

    // Textures: how many MToon textures were actually bound. -1 means the
    // model hasn't finished loading yet; 0 with no error means the model
    // has no MToon textures (or they're not in the expected format).
    val textureLine = when {
        texturesApplied == SKIPPED_AFTER_CRASH -> "textures: off — crashed in every safe mode (pick the avatar again to retry)"
        texturesApplied < 0 -> "textures: loading… (safe mode ${com.mediaviewer.util.CrashBreadcrumbs.vrmTextureMode})"
        texturesApplied == 0 -> "textures: none bound (untextured model, or decode failed — see logcat MToonApplier)"
        else -> "textures: $texturesApplied materials textured (safe mode ${com.mediaviewer.util.CrashBreadcrumbs.vrmTextureMode})"
    } + if (materialsPatched.isNotBlank()) "\nmaterials: $materialsPatched" else ""

    // Hands: just a live count, plus which side(s) — full 21-point dump per
    // hand isn't useful as on-screen debug text, this is just confirming
    // detection is alive at all.
    val handednesses = handResult?.handednesses().orEmpty()
    val handLine = when {
        handResult == null -> "hands: no landmarker output yet\n(check hand_landmarker.task in assets/)"
        handednesses.isEmpty() -> "hands: none detected"
        // MediaPipe's label assumes a mirrored image and ours isn't, so it's
        // swapped here to name the hand you actually raised.
        else -> "hands: " + handednesses.joinToString(", ") { c ->
            when (personSideFromLabel(c.firstOrNull()?.categoryName())) { "left" -> "Left"; "right" -> "Right"; else -> "?" }
        }
    }

    // Pose: only running while "Upper Body" is on. Nose landmark's presence
    // score is a simple stand-in for "is a body actually detected."
    val poseLine = if (!trackUpperBody) {
        "pose: off (enable \"Upper Body\" in Settings)"
    } else {
        val landmarks = poseResult?.landmarks()?.orEmpty()?.firstOrNull()
        if (landmarks != null) "pose: detected (${landmarks.size} points)"
        else "pose: no landmarker output yet\n(check pose_landmarker_full.task in assets/)"
    }

    // Item 8, step 4 — proves VrmParser against whatever file step 5 just
    // loaded into Filament: if this line shows bones/expressions found,
    // parsing succeeded on the same file being rendered above.
    val vrmDataLine = when {
        parsedVrmData == null -> null
        else -> "vrm file: ${parsedVrmData.specVersion} — ${parsedVrmData.humanBones.size} bones, ${parsedVrmData.expressions.size} expressions"
    }

    // Retarget bridge health — how many of the file's glTF nodes actually
    // resolved to Filament entities by name (see AvatarRetargeter's doc
    // comment). If this reads 0/N, expressions AND bone rotation are both
    // silently skipping everything; if it reads N/N and the avatar still
    // doesn't move, the problem is downstream of entity resolution.
    val retargetLine = retargetTarget?.let { target ->
        val totalNodes = parsedVrmData?.nodeNames?.size ?: 0
        "retarget: ${target.nodeIndexToEntity.size}/$totalNodes nodes → entities"
    }

    // Step 6, head/neck rotation — no numeric readout (a raw quaternion
    // wouldn't mean much at a glance the way blendshape scores do); this
    // just confirms the pipeline is receiving a head matrix at all, so
    // "avatar's head isn't turning" can be told apart from "no head data
    // arriving in the first place."
    val headRotationLine = if (parsedVrmData?.humanBones?.containsKey("head") == true) {
        if (headTrackingActive) "head rotation: tracking" else "head rotation: no face detected"
    } else null

    // Step 6, arm rotation — same "confirm data is arriving" shape as the
    // head line above. Only meaningful while pose tracking itself is on
    // ("Upper Body" in Settings); off entirely otherwise, same as poseLine.
    val armRotationLine = if (parsedVrmData?.humanBones?.containsKey("leftUpperArm") == true && trackUpperBody) {
        if (armTrackingActive) "arm rotation: tracking" else "arm rotation: no pose detected"
    } else null

    // Step 6, leg rotation — same shape again, gated on "Full Body" (which
    // the Settings sheet already keeps off unless "Upper Body" is also on).
    val legRotationLine = if (parsedVrmData?.humanBones?.containsKey("leftUpperLeg") == true && trackFullBody) {
        if (legTrackingActive) "leg rotation: tracking" else "leg rotation: no pose detected"
    } else null

    Box(modifier) {
        Text(
            text = listOfNotNull(cameraLine, faceLine, textureLine, handLine, poseLine, vrmDataLine, retargetLine, headRotationLine, armRotationLine, legRotationLine).joinToString("\n\n"),
            color = tint,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp)
        )
    }
}

/** Everything the VRM settings sheet shows and changes. */
private class VrmSettingsUi(
    val trackUpperBody: Boolean, val onToggleUpperBody: (Boolean) -> Unit,
    val trackFullBody: Boolean, val onToggleFullBody: (Boolean) -> Unit,
    val followHead: Boolean, val onToggleFollowHead: (Boolean) -> Unit,
    val smoothing: Int, val onSmoothing: (Int) -> Unit,
    val fastTracking: Boolean, val onToggleFastTracking: (Boolean) -> Unit,
    val manualEyes: Boolean, val onToggleManualEyes: (Boolean) -> Unit,
    val eyeClosed: Float, val onEyeClosed: (Float) -> Unit,
    val springBones: Boolean, val onToggleSpringBones: (Boolean) -> Unit,
    val showPreview: Boolean, val onTogglePreview: (Boolean) -> Unit,
    val showDebug: Boolean, val onToggleDebug: (Boolean) -> Unit,
    val fullBright: Boolean, val onToggleFullBright: (Boolean) -> Unit,
    val armIk: Boolean, val onToggleArmIk: (Boolean) -> Unit,
    val lightLevel: Int, val onLightLevel: (Int) -> Unit,
    val onResetCamera: () -> Unit,
    val avatarParts: List<AvatarPart>,
    val hiddenParts: Set<String>,
    val onSetPartVisible: (id: String, visible: Boolean) -> Unit,
    val onShowAllParts: () -> Unit,
    val hasAvatar: Boolean,
    val onPickAvatar: () -> Unit
)

/** The settings sheet wears the user's profile color ([tint]) like the rest
 *  of VRM mode: tinted glass (or a tint-darkened panel), tinted switches,
 *  sliders and links. */
@Composable
private fun VrmSettingsSheet(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    ui: VrmSettingsUi,
    onDismiss: () -> Unit
) {
    val tap = rememberHapticTap()
    var partsExpanded by remember { mutableStateOf(false) }
    val dim = Color.White.copy(alpha = 0.6f)
    // Flat mode: the tint mixed into near-black, so it reads as "your color".
    val flatPanel = androidx.compose.ui.graphics.lerp(Color(0xFF121212), tint, 0.22f)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { tap(); onDismiss() }) {
    VrmGlassPanel(
        liquidGlass = liquidGlass, tint = tint, backdrop = backdrop, flatColor = flatPanel,
        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp)
    ) {
    Box(
        Modifier
            .heightIn(max = 560.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(18.dp)
    ) {
        androidx.compose.foundation.layout.Column {
            Text("VRM Settings", color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Face and hand tracking are always on. Body tracking is heavier — turn on only what you need.",
                color = dim, fontSize = 12.sp
            )

            VrmSettingsSection("Tracking", tint)
            VrmSettingsToggleRow("Upper Body", ui.trackUpperBody, tint) { ui.onToggleUpperBody(it); if (!it) ui.onToggleFullBody(false) }
            VrmSettingsToggleRow("Full Body", ui.trackFullBody, tint, enabled = ui.trackUpperBody) { ui.onToggleFullBody(it) }
            VrmSettingsToggleRow("Hand IK (experimental)", ui.armIk, tint,
                hint = "Your tracked hands place the avatar's arms, even with body tracking off.") { ui.onToggleArmIk(it) }
            VrmSettingsToggleRow("Fast tracking (30 fps)", ui.fastTracking, tint,
                hint = "Keeps up with quick movements. Uses more battery and warms the phone.") { ui.onToggleFastTracking(it) }
            VrmSettingsSlider(
                label = "Smoothing", valueText = if (ui.smoothing == 0) "off" else ui.smoothing.toString(),
                value = ui.smoothing.toFloat(), range = 0f..10f, steps = 9, tint = tint,
                hint = "0 = raw and instant, 10 = smoothest. Fast moves stay responsive at any setting."
            ) { ui.onSmoothing(kotlin.math.round(it).toInt()) }

            VrmSettingsSection("Avatar", tint)
            VrmSettingsToggleRow("Follow my head", ui.followHead, tint) { ui.onToggleFollowHead(it) }
            VrmSettingsToggleRow("Physics (hair, ears, tails)", ui.springBones, tint,
                hint = "The avatar's spring bones, if it has any.") { ui.onToggleSpringBones(it) }
            VrmSettingsToggleRow("Manual eyes", ui.manualEyes, tint,
                hint = "Ignores blinking; set how open the eyes are below.") { ui.onToggleManualEyes(it) }
            if (ui.manualEyes) {
                VrmSettingsSlider(
                    label = "Eyes", valueText = when {
                        ui.eyeClosed <= 0.02f -> "open"
                        ui.eyeClosed >= 0.98f -> "closed"
                        else -> "${((1f - ui.eyeClosed) * 100).toInt()}% open"
                    },
                    value = ui.eyeClosed, range = 0f..1f, steps = 0, tint = tint,
                    startLabel = "Open", endLabel = "Closed"
                ) { ui.onEyeClosed(it) }
            }
            // Avatar parts: every mesh piece (clothes, hair, accessories …)
            // can be hidden. Collapsed to one row; tap to list them.
            if (ui.avatarParts.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable { tap(); partsExpanded = !partsExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Avatar parts", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    val shown = ui.avatarParts.count { it.id !in ui.hiddenParts }
                    Text("$shown/${ui.avatarParts.size} shown  ${if (partsExpanded) "▴" else "▾"}", color = dim, fontSize = 12.sp)
                }
                if (partsExpanded) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                        for (part in ui.avatarParts) {
                            VrmSettingsToggleRow(part.label, part.id !in ui.hiddenParts, tint) { ui.onSetPartVisible(part.id, it) }
                        }
                    }
                    if (ui.hiddenParts.isNotEmpty()) {
                        Text(
                            "Show all", color = tint, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp).clickable { tap(); ui.onShowAllParts() }
                        )
                    }
                }
            }
            // Item 8, step 5 — no bundled default avatar, so the only way to
            // get anything on screen is picking one's own file. "*/*" because
            // .vrm has no registered MIME type; VrmParser rejects non-VRM picks.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable { tap(); ui.onPickAvatar() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (ui.hasAvatar) "Change VRM avatar…" else "Choose VRM avatar…",
                    color = tint, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }

            VrmSettingsSection("Display", tint)
            VrmSettingsToggleRow("Full bright", ui.fullBright, tint,
                hint = "No lighting or shadows — every texture shown at full brightness.") { ui.onToggleFullBright(it) }
            if (!ui.fullBright) {
                VrmSettingsSlider(
                    label = "Brightness", valueText = if (ui.lightLevel == 5) "default" else "${ui.lightLevel}",
                    value = ui.lightLevel.toFloat(), range = 0f..10f, steps = 9, tint = tint,
                    hint = "How strongly the lights hit the avatar's face and body."
                ) { ui.onLightLevel(kotlin.math.round(it).toInt()) }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable { tap(); ui.onResetCamera() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text("Reset camera", color = tint, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text("Undo any spinning or zooming of the view.", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }
            VrmSettingsToggleRow("Tracking preview", ui.showPreview, tint) { ui.onTogglePreview(it) }
            VrmSettingsToggleRow("Debug info", ui.showDebug, tint) { ui.onToggleDebug(it) }
        }
    }
    }
    }
}

@Composable
private fun VrmSettingsSection(title: String, tint: Color) {
    Spacer(Modifier.height(14.dp))
    Text(title.uppercase(), color = tint, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun VrmSettingsToggleRow(
    label: String, checked: Boolean, tint: Color, enabled: Boolean = true, hint: String? = null, onToggle: (Boolean) -> Unit
) {
    val tap = rememberHapticTap()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .clickable(enabled = enabled) { tap(); onToggle(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f), fontSize = 14.sp)
            if (hint != null) Text(hint, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(13.dp))
                .background(if (checked && enabled) tint else Color.White.copy(0.15f)),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.padding(3.dp).size(20.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun VrmSettingsSlider(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, tint: Color,
    hint: String? = null, startLabel: String? = null, endLabel: String? = null, onChange: (Float) -> Unit
) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = tint, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
        androidx.compose.material3.Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = tint, activeTrackColor = tint, inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                activeTickColor = Color.White.copy(alpha = 0.5f), inactiveTickColor = Color.White.copy(alpha = 0.3f)
            )
        )
        if (startLabel != null || endLabel != null) {
            Row {
                Text(startLabel ?: "", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(endLabel ?: "", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            }
        }
        if (hint != null) Text(hint, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}

// BlazePose / hand topology — just the connections worth drawing.
private val HAND_CONNECTIONS = intArrayOf(
    0, 1, 1, 2, 2, 3, 3, 4,
    0, 5, 5, 6, 6, 7, 7, 8,
    5, 9, 9, 10, 10, 11, 11, 12,
    9, 13, 13, 14, 14, 15, 15, 16,
    13, 17, 0, 17, 17, 18, 18, 19, 19, 20
)
private val POSE_CONNECTIONS = intArrayOf(
    11, 12, 11, 13, 13, 15, 12, 14, 14, 16,
    11, 23, 12, 24, 23, 24,
    23, 25, 25, 27, 24, 26, 26, 28
)

/**
 * Picture-in-picture debug view of what MediaPipe is tracking: a small
 * black box drawn at the same aspect ratio as the (upright) frame the
 * landmarkers receive, with face points, hand skeletons and (when "Upper
 * Body" is on) the pose skeleton. Mirrored horizontally so it moves like a
 * mirror — raise your right hand and the dots on the right move. Draws
 * landmarks only, never camera pixels.
 */
@Composable
private fun TrackingPreview(
    faceResult: FaceLandmarkerResult?,
    handResult: HandLandmarkerResult?,
    poseResult: PoseLandmarkerResult?,
    frameWidth: Int,
    frameHeight: Int,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val boxWidth = 104.dp
    val aspect = if (frameWidth > 0 && frameHeight > 0) frameHeight.toFloat() / frameWidth else 4f / 3f
    val faceColor = Color(0xFF7CFFB2)
    val handColor = Color(0xFF6FD3FF)
    val poseColor = Color(0xFFFFD166)
    androidx.compose.foundation.Canvas(
        modifier
            .width(boxWidth)
            .height(boxWidth * aspect)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
    ) {
        val w = size.width
        val h = size.height
        fun px(x: Float, y: Float) = androidx.compose.ui.geometry.Offset((1f - x) * w, y * h)

        faceResult?.faceLandmarks()?.firstOrNull()?.let { points ->
            val offsets = ArrayList<androidx.compose.ui.geometry.Offset>(points.size)
            for (p in points) offsets.add(px(p.x(), p.y()))
            drawPoints(
                points = offsets,
                pointMode = androidx.compose.ui.graphics.PointMode.Points,
                color = faceColor,
                strokeWidth = 1.2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
        handResult?.landmarks()?.forEach { hand ->
            var i = 0
            while (i < HAND_CONNECTIONS.size) {
                val a = hand.getOrNull(HAND_CONNECTIONS[i]); val b = hand.getOrNull(HAND_CONNECTIONS[i + 1])
                if (a != null && b != null) drawLine(handColor, px(a.x(), a.y()), px(b.x(), b.y()), strokeWidth = 1.dp.toPx())
                i += 2
            }
            for (p in hand) drawCircle(handColor, radius = 1.6.dp.toPx(), center = px(p.x(), p.y()))
        }
        poseResult?.landmarks()?.firstOrNull()?.let { body ->
            var i = 0
            while (i < POSE_CONNECTIONS.size) {
                val a = body.getOrNull(POSE_CONNECTIONS[i]); val b = body.getOrNull(POSE_CONNECTIONS[i + 1])
                if (a != null && b != null) drawLine(poseColor, px(a.x(), a.y()), px(b.x(), b.y()), strokeWidth = 1.5.dp.toPx())
                i += 2
            }
        }
    }
}

/** Stream clock: m:ss, then h:mm:ss — it just keeps counting. */
private fun formatLiveClock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * A round VRM-mode button. With liquid glass on it's the app's real glass:
 * the avatar behind it is blurred through [backdrop] (API 31+), tinted with
 * the user's color and rimmed like every other glass button in the app.
 */
@Composable
private fun VrmGlassBubble(
    size: androidx.compose.ui.unit.Dp,
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    border: Color? = null,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val base = modifier.size(size).clip(CircleShape)
    val ring = if (border != null) Modifier.border(3.dp, border, CircleShape) else Modifier
    val click = Modifier.clickable(enabled = enabled, onClick = onClick)
    if (liquidGlass) {
        LiquidGlassSurface(
            modifier = base.then(ring).then(click),
            shape = CircleShape,
            tint = tint,
            backdrop = backdrop,
            contentAlignment = Alignment.Center,
            content = content
        )
    } else {
        Box(base.background(tint.copy(alpha = 0.25f)).then(ring).then(click), contentAlignment = Alignment.Center, content = content)
    }
}

/** A VRM-mode sheet/popup surface: blurred glass (or the flat tint-mixed
 *  panel), and it swallows taps so they don't dismiss what's behind it. */
@Composable
private fun VrmGlassPanel(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    flatColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val swallow = Modifier.clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null
    ) {}
    if (liquidGlass) {
        LiquidGlassSurface(modifier = modifier.then(swallow), shape = shape, tint = tint, backdrop = backdrop) {
            // The page behind is dimmed, but the blurred crop isn't — this
            // keeps the panel's text as readable as the old flat sheet.
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.38f)))
            content()
        }
    } else {
        Box(
            modifier.clip(shape).background(flatColor).border(1.dp, tint.copy(alpha = 0.45f), shape).then(swallow)
        ) { content() }
    }
}

@Composable
private fun VrmTextField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    tint: Color,
    secret: Boolean = false,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Next,
    trailing: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.35f), shape)
            .padding(start = 12.dp, end = if (trailing != null) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(tint),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = keyboardType, imeAction = imeAction, autoCorrect = false
            ),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.35f), fontSize = 14.sp, maxLines = 1)
                    inner()
                }
            }
        )
        trailing?.invoke()
    }
}

/**
 * The Live button's popup: server URL + stream key (remembered), a quality
 * choice (Auto picks what this phone sustains — see StreamQuality.auto),
 * and "Go Live". Compact and centered; it rides up above the keyboard.
 */
@Composable
private fun VrmLiveDialog(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    url: String, onUrl: (String) -> Unit,
    key: String, onKey: (String) -> Unit,
    qualityName: String, onQuality: (String) -> Unit,
    autoQuality: com.mediaviewer.stream.StreamQuality,
    onGoLive: () -> Unit,
    onDismiss: () -> Unit
) {
    val tap = rememberHapticTap()
    val dim = Color.White.copy(alpha = 0.6f)
    var showKey by remember { mutableStateOf(false) }
    val urlError = remember(url, key) {
        if (url.isBlank()) null
        else runCatching { com.mediaviewer.stream.RtmpPublisher.parseEndpoint(url, key); null }.getOrElse { it.message }
    }
    val canGo = url.isNotBlank() && urlError == null
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        VrmGlassPanel(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            flatColor = androidx.compose.ui.graphics.lerp(Color(0xFF121212), tint, 0.22f),
            modifier = Modifier.padding(horizontal = 28.dp).widthIn(max = 380.dp).fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Go Live", color = Color.White, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Stream your avatar to YouTube, Twitch, Kick or any RTMP server. Copy both from your platform's stream settings.",
                    color = dim, fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))
                Text("SERVER URL", color = tint, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                VrmTextField(
                    value = url, onValue = onUrl, placeholder = "rtmp://a.rtmp.youtube.com/live2", tint = tint,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
                )
                Spacer(Modifier.height(10.dp))
                Text("STREAM KEY", color = tint, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                VrmTextField(
                    value = key, onValue = onKey, placeholder = "xxxx-xxxx-xxxx-xxxx", tint = tint,
                    secret = !showKey,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    trailing = {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).clickable { tap(); showKey = !showKey },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key",
                                tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
                if (urlError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(urlError, color = Color(0xFFFF8A80), fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("QUALITY", color = tint, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    val options = listOf("" to "Auto") + com.mediaviewer.stream.StreamQuality.values().map { it.name to it.label }
                    for ((id, label) in options) {
                        val selected = qualityName == id
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (selected) tint else Color.White.copy(alpha = 0.08f))
                                .border(1.dp, tint.copy(alpha = if (selected) 0f else 0.35f), RoundedCornerShape(10.dp))
                                .clickable { tap(); onQuality(id) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                val shown = com.mediaviewer.stream.StreamQuality.values().firstOrNull { it.name == qualityName } ?: autoQuality
                Text(
                    (if (qualityName.isEmpty()) "Auto picked ${autoQuality.label} for this phone · " else "") +
                        "${shown.width}×${shown.height}, ${shown.fps} fps, ${"%.1f".format(shown.bitrate / 1_000_000f)} Mbps. " +
                        "Lowers itself automatically if your connection struggles.",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (canGo) tint else Color.White.copy(alpha = 0.12f))
                        .clickable(enabled = canGo) { tap(); onGoLive() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (canGo) Color(0xFFFF3B30) else Color.White.copy(alpha = 0.3f)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Go Live", color = Color.White.copy(alpha = if (canGo) 1f else 0.4f),
                            fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VrmConfirmDialog(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val tap = rememberHapticTap()
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        VrmGlassPanel(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            flatColor = androidx.compose.ui.graphics.lerp(Color(0xFF121212), tint, 0.22f),
            modifier = Modifier.padding(horizontal = 40.dp).widthIn(max = 340.dp).fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(message, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .clickable { tap(); onDismiss() },
                        contentAlignment = Alignment.Center
                    ) { Text("Cancel", color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
                    Box(
                        Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFFF3B30))
                            .clickable { tap(); onConfirm() },
                        contentAlignment = Alignment.Center
                    ) { Text(confirmLabel, color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                }
            }
        }
    }
}
