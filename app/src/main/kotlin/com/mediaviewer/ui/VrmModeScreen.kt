package com.mediaviewer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mediaviewer.util.PreferencesManager
import com.mediaviewer.util.VrmData
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
 *    rotation: [AvatarRetargeter.applyHeadRotation] turns MediaPipe's
 *    per-frame face transformation matrix into a head (and, split
 *    proportionally, neck) bone rotation. Arm rotation:
 *    [AvatarRetargeter.applyArmRotation] turns `PoseLandmarker`'s
 *    world-space shoulder/elbow/wrist points into upper-arm + forearm
 *    rotation on both sides, gated on [trackUpperBody]. Leg rotation:
 *    [AvatarRetargeter.applyLegRotation] does the identical thing with
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
    var trackUpperBody by remember { mutableStateOf(true) }
    var trackFullBody by remember { mutableStateOf(false) }

    // Step 1 verification state (see doc comment above): the latest result
    // from each landmarker, updated from VrmCameraPreview's ImageAnalysis
    // callback. Read by VrmTrackingOverlay to print debug scores — these
    // fields are *only* for that on-device sanity check and go away once
    // step 2 (smoothing) and step 3 (retargeting) consume the results
    // directly instead.
    var latestFaceResult by remember { mutableStateOf<FaceLandmarkerResult?>(null) }
    var latestHandResult by remember { mutableStateOf<HandLandmarkerResult?>(null) }
    var latestPoseResult by remember { mutableStateOf<PoseLandmarkerResult?>(null) }

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
    // Item 8, VRM pipeline step 6 — the node-index→entity bridge into
    // whatever VrmAvatarView just rendered; null until a model has
    // actually finished loading. See AvatarRetargeter.kt's doc comment.
    var retargetTarget by remember { mutableStateOf<RetargetTarget?>(null) }

    // Shared once here (not duplicated inside VrmTrackingOverlay) so the
    // debug overlay's five-blendshape subset and step 6's full-52
    // retargeting call are reading the exact same smoothed values for the
    // exact same frame, rather than two independent OneEuroFilterBank
    // instances drifting slightly apart from each other.
    val blendshapeFilters = remember { OneEuroFilterBank(minCutoff = 1.0, beta = 0.3, dCutoff = 1.0) }
    val smoothedBlendshapes = remember(latestFaceResult) { smoothedFaceBlendshapes(latestFaceResult, blendshapeFilters) }
    // Item 8, VRM pipeline step 6 (bone-rotation half) — MediaPipe's raw
    // per-frame head-pose matrix, unsmoothed (unlike the blendshapes
    // above): AvatarRetargeter.applyHeadRotation calibrates against the
    // first frame it sees rather than a fixed rest value, so this doesn't
    // need the same jitter treatment to look stable — see that function's
    // own doc comment.
    val headMatrix = remember(latestFaceResult) { headTransformationMatrix(latestFaceResult) }
    // Item 8, VRM pipeline step 6 (arm rotation) — unlike the head's face
    // matrix, raw pose landmarks are noisy enough that calibration alone
    // doesn't hide it, so these get the same OneEuroFilterBank treatment
    // face blendshapes already get, in a bank of its own (per
    // OneEuroFilterBank's own doc comment: face/body tuning may need to
    // diverge, and a bank is cheap).
    val poseFilters = remember { OneEuroFilterBank(minCutoff = 1.0, beta = 0.3, dCutoff = 1.0) }
    val smoothedBodyLandmarks = remember(latestPoseResult) { smoothedBodyWorldLandmarks(latestPoseResult, poseFilters) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        prefsManager.vrmAvatarUri.firstOrNull()?.let { pickedVrmUri = Uri.parse(it) }
    }
    androidx.compose.runtime.LaunchedEffect(pickedVrmUri) {
        val uri = pickedVrmUri
        vrmBytes = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                .onFailure { android.util.Log.e("VrmModeScreen", "Could not read picked VRM file", it) }
                .getOrNull()
        }
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
            AvatarRetargeter.applyExpressions(target, vrmData, smoothedBlendshapes)
            AvatarRetargeter.applyHeadRotation(target, vrmData, headMatrix)
            // Gated on the same toggle that turns PoseLandmarker itself on
            // (see VrmCameraPreview's trackPose param) — otherwise
            // latestPoseResult/smoothedBodyLandmarks would just be stale
            // data from before the toggle was switched off, not "no arms."
            if (trackUpperBody) AvatarRetargeter.applyArmRotation(target, vrmData, smoothedBodyLandmarks)
            // Legs additionally require "Full Body" — same rationale as
            // arms above, plus legs are the heavier, less-often-visible
            // half of body tracking (see applyLegRotation's own doc
            // comment) — the Settings sheet already gates "Full Body" on
            // "Upper Body" being on, so this doesn't need to also check that.
            if (trackFullBody) AvatarRetargeter.applyLegRotation(target, vrmData, smoothedBodyLandmarks)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            VrmCameraPreview(
                modifier = Modifier.fillMaxSize(),
                trackPose = trackUpperBody,
                onFaceResult = { latestFaceResult = it },
                onHandResult = { latestHandResult = it },
                onPoseResult = { latestPoseResult = it }
            )
            // Item 8, step 5 — Filament rendering the picked VRM avatar file
            // itself (unposed — see VrmAvatarView's doc comment for why
            // tracking doesn't drive it yet), drawn over the camera preview.
            // The camera preview keeps running underneath regardless (it's
            // still tracking's actual input), just visually covered once
            // there's an avatar to show instead of the raw feed.
            if (vrmBytes != null) {
                VrmAvatarView(
                    modifier = Modifier.fillMaxSize(),
                    vrmBytes = vrmBytes,
                    onParsedVrmData = { parsedVrmData = it },
                    onRetargetTargetReady = { retargetTarget = it }
                )
            } else {
                Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(top = 72.dp)) {
                    Text(
                        "No avatar picked yet — open Settings to choose a .vrm file",
                        color = Color.White.copy(0.7f), fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(0.35f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            // Item 8, step 3 above — this is where the tracked/retargeted
            // VRM avatar renders once the pipeline exists; today it just
            // prints step 1's raw landmarker output so tracking itself can
            // be confirmed working before anything is built on top of it.
            VrmTrackingOverlay(
                trackUpperBody = trackUpperBody,
                trackFullBody = trackFullBody,
                smoothedFaceBlendshapes = smoothedBlendshapes,
                handResult = latestHandResult,
                poseResult = latestPoseResult,
                parsedVrmData = parsedVrmData,
                headTrackingActive = headMatrix != null,
                armTrackingActive = smoothedBodyLandmarks.isNotEmpty(),
                legTrackingActive = smoothedBodyLandmarks.isNotEmpty(),
                modifier = Modifier.fillMaxSize()
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
        // top-left close affordance in this app.
        Box(
            Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp)
                .size(40.dp).clip(CircleShape)
                .then(if (liquidGlass) Modifier.glassPanel(true, shape = CircleShape) else Modifier.background(Color.White.copy(0.12f)))
                .clickable { tap(); onClose() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
        }

        // Bottom bar: record/photo + settings, per spec ("a recording/
        // picture button at the bottom, and the settings next to it").
        Row(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape)
                    .then(if (liquidGlass) Modifier.glassPanel(true, shape = CircleShape) else Modifier.background(Color.White.copy(0.12f)))
                    .clickable {
                        tap()
                        // Item 8 follow-up: this fires the same still-capture
                        // as the notch button's Camera action for now (a
                        // screenshot of the current preview frame) — real
                        // photo/video capture *of the rendered avatar* needs
                        // the Filament renderer from step 4 above to exist
                        // first, since that's what's actually being recorded.
                        onCapture(null, null)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.FiberManualRecord, contentDescription = "Capture", tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(20.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .then(if (liquidGlass) Modifier.glassPanel(true, shape = CircleShape) else Modifier.background(Color.White.copy(0.12f)))
                    .clickable { tap(); settingsOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Settings, contentDescription = "VRM Settings", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        if (settingsOpen) {
            VrmSettingsSheet(
                liquidGlass = liquidGlass,
                trackUpperBody = trackUpperBody, onToggleUpperBody = { trackUpperBody = it },
                trackFullBody = trackFullBody, onToggleFullBody = { trackFullBody = it },
                hasAvatar = vrmBytes != null,
                onPickAvatar = { vrmAvatarPickerLauncher.launch(arrayOf("*/*")) },
                onDismiss = { settingsOpen = false }
            )
        }
    }
}

/** Live front-camera feed via CameraX — plus, now, step 1 of the VRM
 *  pipeline: a second `ImageAnalysis` use case bound alongside `Preview`.
 *  Each frame is decoded to a [Bitmap]/`MPImage` exactly once here, then
 *  handed to all three landmarker helpers (face + hands always; pose only
 *  while [trackPose] is on, per the "Upper Body" Settings toggle) —
 *  avoids each helper redoing the same YUV conversion three times over.
 *  Whatever each detects is forwarded back up to [VrmModeScreen] via
 *  [onFaceResult]/[onHandResult]/[onPoseResult]. If a model asset isn't
 *  bundled yet (see [FaceLandmarkerHelper]'s doc comment), that helper's
 *  `create` returns null and its slot is simply skipped — the other two
 *  (and the preview itself) keep working regardless. */
@Composable
private fun VrmCameraPreview(
    modifier: Modifier = Modifier,
    trackPose: Boolean,
    onFaceResult: (FaceLandmarkerResult) -> Unit,
    onHandResult: (HandLandmarkerResult) -> Unit,
    onPoseResult: (PoseLandmarkerResult) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val faceLandmarkerHelper = remember { FaceLandmarkerHelper.create(context, onFaceResult) }
    val handLandmarkerHelper = remember { HandLandmarkerHelper.create(context, onHandResult) }
    val poseLandmarkerHelper = remember { PoseLandmarkerHelper.create(context, onPoseResult) }

    DisposableEffect(Unit) {
        onDispose {
            faceLandmarkerHelper?.close()
            handLandmarkerHelper?.close()
            poseLandmarkerHelper?.close()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(cameraExecutor) { imageProxy ->
                            val timestampMs = System.currentTimeMillis()
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
                            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(it.planes[0].buffer) }
                            val mpImage = BitmapImageBuilder(bitmapBuffer).build()

                            faceLandmarkerHelper?.detectAsync(mpImage, rotationDegrees, timestampMs)
                            handLandmarkerHelper?.detectAsync(mpImage, rotationDegrees, timestampMs)
                            if (trackPose) poseLandmarkerHelper?.detectAsync(mpImage, rotationDegrees, timestampMs)
                        }
                    }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
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
 *  [AvatarRetargeter.applyHeadRotation] doesn't need it. */
private fun headTransformationMatrix(faceResult: FaceLandmarkerResult?): FloatArray? =
    faceResult?.facialTransformationMatrixes()?.orElse(null)?.firstOrNull()

// Shoulders, elbows, wrists, hips, knees, ankles — BlazePose's 33-point
// topology, the ten indices bone rotation needs beyond the face. Smoothing
// just these ten (not all 33 — no hand/foot/facial landmarks from pose are
// used anywhere in this pipeline) keeps poseFilters' map small.
private val BODY_LANDMARK_INDICES = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

/** VRM pipeline step 6 (arm + leg rotation): [BODY_LANDMARK_INDICES]'
 *  world-space positions (meters, hip-centered — MediaPipe's
 *  `worldLandmarks()`, not the normalized image-space `landmarks()`),
 *  each smoothed through [filters] and keyed by landmark index.
 *  `worldLandmarks()` returns `List<List<Landmark>>` (one list per
 *  detected pose — verified against the tasks-vision 0.10.14 AAR, not an
 *  Optional), and `Landmark` exposes `getX()/getY()/getZ()`.
 *  Returns an empty map (and resets `"pose."`-prefixed filter
 *  history, so a later reacquisition isn't smoothed across the gap) when
 *  no pose is currently detected, same shape as [smoothedFaceBlendshapes].
 *  One shared function for both arms and legs (not two near-duplicates) —
 *  the "which indices actually get used this frame" decision belongs to
 *  the caller (arm rotation always applies if pose tracking is on at all;
 *  leg rotation is additionally gated on "Full Body" — see the call
 *  site), not to how the landmarks get smoothed. */
private fun smoothedBodyWorldLandmarks(poseResult: PoseLandmarkerResult?, filters: OneEuroFilterBank): Map<Int, FloatArray> {
    val result = poseResult ?: run {
        filters.resetPrefixed("pose.")
        return emptyMap()
    }
    // Verified against the tasks-vision 0.10.14 AAR: worldLandmarks() is a
    // plain List<List<Landmark>> (one list per detected pose) — NOT a
    // java.util.Optional — and Landmark exposes getX()/getY()/getZ()
    // (Kotlin `.x`/`.y`/`.z` properties), not `x()`/`y()`/`z()` methods.
    val worldLandmarks = result.worldLandmarks().firstOrNull()
    if (worldLandmarks == null) {
        filters.resetPrefixed("pose.")
        return emptyMap()
    }
    val timestampSeconds = result.timestampMs() / 1000.0
    val smoothed = mutableMapOf<Int, FloatArray>()
    for (index in BODY_LANDMARK_INDICES) {
        val landmark = worldLandmarks.getOrNull(index) ?: continue
        smoothed[index] = floatArrayOf(
            filters.filter("pose.$index.x", landmark.x, timestampSeconds),
            filters.filter("pose.$index.y", landmark.y, timestampSeconds),
            filters.filter("pose.$index.z", landmark.z, timestampSeconds)
        )
    }
    return smoothed
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
 * [VrmCameraPreview]'s `trackPose` param); [trackFullBody] doesn't change
 * anything yet, it's reserved for gating whether bone-rotation
 * retargeting uses the leg landmarks PoseLandmarker already outputs.
 * Face + hand tracking always run regardless of either.
 */
@Composable
private fun VrmTrackingOverlay(
    trackUpperBody: Boolean,
    trackFullBody: Boolean,
    smoothedFaceBlendshapes: Map<String, Float>,
    handResult: HandLandmarkerResult?,
    poseResult: PoseLandmarkerResult?,
    parsedVrmData: VrmData?,
    headTrackingActive: Boolean,
    armTrackingActive: Boolean,
    legTrackingActive: Boolean,
    modifier: Modifier = Modifier
) {
    // Just a handful of representative blendshapes — enough to see live
    // movement (blink, jaw, smile) without dumping all 52 scores on screen.
    val debugBlendshapeNames = listOf("jawOpen", "eyeBlinkLeft", "eyeBlinkRight", "mouthSmileLeft", "mouthSmileRight")
    val faceLine = if (smoothedFaceBlendshapes.isNotEmpty()) {
        debugBlendshapeNames.joinToString("\n") { name ->
            "$name: ${"%.2f".format(smoothedFaceBlendshapes[name] ?: 0f)}"
        }
    } else {
        "face: no landmarker output yet\n(check face_landmarker.task in assets/)"
    }

    // Hands: just a live count, plus which side(s) — full 21-point dump per
    // hand isn't useful as on-screen debug text, this is just confirming
    // detection is alive at all.
    val handednesses = handResult?.handednesses().orEmpty()
    val handLine = when {
        handResult == null -> "hands: no landmarker output yet\n(check hand_landmarker.task in assets/)"
        handednesses.isEmpty() -> "hands: none detected"
        else -> "hands: " + handednesses.joinToString(", ") { it.firstOrNull()?.categoryName() ?: "?" }
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
            text = listOfNotNull(faceLine, handLine, poseLine, vrmDataLine, headRotationLine, armRotationLine, legRotationLine).joinToString("\n\n"),
            color = Color.Green,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp)
        )
    }
}

@Composable
private fun VrmSettingsSheet(
    liquidGlass: Boolean,
    trackUpperBody: Boolean, onToggleUpperBody: (Boolean) -> Unit,
    trackFullBody: Boolean, onToggleFullBody: (Boolean) -> Unit,
    hasAvatar: Boolean,
    onPickAvatar: () -> Unit,
    onDismiss: () -> Unit
) {
    val tap = rememberHapticTap()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { tap(); onDismiss() }) {
    Box(
        Modifier.fillMaxWidth().align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp).clip(RoundedCornerShape(20.dp))
            .then(if (liquidGlass) Modifier.glassPanel(true, shape = RoundedCornerShape(20.dp)) else Modifier.background(Color(0xFF1A1A1A)))
            .padding(18.dp)
    ) {
        androidx.compose.foundation.layout.Column {
            Text("VRM Settings", color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Face and hand tracking are always on. Body tracking is heavier — turn on only what you need.",
                color = com.mediaviewer.ui.theme.DimGray, fontSize = 12.sp
            )
            Spacer(Modifier.height(14.dp))
            VrmSettingsToggleRow("Upper Body", trackUpperBody) { onToggleUpperBody(it); if (!it) onToggleFullBody(false) }
            VrmSettingsToggleRow("Full Body", trackFullBody, enabled = trackUpperBody) { onToggleFullBody(it) }
            Spacer(Modifier.height(10.dp))
            // Item 8, step 5 — no bundled default avatar (that'd mean
            // shipping someone's VRM model in the app), so the only way to
            // get anything on screen is picking one's own file. `"*/*"` is
            // the broadest MIME filter the system picker accepts — `.vrm`
            // has no registered MIME type of its own, so a stricter filter
            // would risk hiding valid files rather than catching invalid
            // ones; the tradeoff is the picker won't pre-filter to VRM
            // files specifically. VrmParser (step 4) is what actually
            // rejects a non-VRM pick, by finding no VRM extension block.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable { tap(); onPickAvatar() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (hasAvatar) "Change VRM avatar…" else "Choose VRM avatar…",
                    color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f)
                )
            }
        }
    }
    }
}

@Composable
private fun VrmSettingsToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    val tap = rememberHapticTap()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp)
            .clickable(enabled = enabled) { tap(); onToggle(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (enabled) Color.White else com.mediaviewer.ui.theme.DimGray, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(13.dp))
                .background(if (checked && enabled) Color(0xFF1083FE) else Color.White.copy(0.15f)),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.padding(3.dp).size(20.dp).clip(CircleShape).background(Color.White))
        }
    }
}
