package com.mediaviewer.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
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
    var trackUpperBody by remember { mutableStateOf(false) }
    var trackFullBody by remember { mutableStateOf(false) }
    // Video-call / filter framing: the avatar's head sits where yours is in
    // the (mirrored) camera frame instead of being locked to the centre.
    var followHead by remember { mutableStateOf(true) }
    // The loaded avatar's toggleable meshes, and which are hidden.
    var avatarParts by remember { mutableStateOf<List<AvatarPart>>(emptyList()) }
    var hiddenParts by remember { mutableStateOf<Set<String>>(emptySet()) }

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
                        latestFaceResult = it
                        faceResultCount++
                    },
                    onError = { faceHelperError = it }
                )
                pending[1] = HandLandmarkerHelper.create(context, onResult = { latestHandResult = it })
                pending[2] = PoseLandmarkerHelper.create(context, onResult = { latestPoseResult = it })
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
    val poseFilters = remember { OneEuroFilterBank(minCutoff = 1.0, beta = 0.3, dCutoff = 1.0) }
    val smoothedBodyLandmarks = remember(latestPoseResult) { smoothedBodyWorldLandmarks(latestPoseResult, poseFilters) }
    // Finger curls, keyed by the AVATAR's side (mirroring already applied).
    val handFilters = remember { OneEuroFilterBank(minCutoff = 1.5, beta = 0.5, dCutoff = 1.0) }
    val handPoints = remember(latestHandResult, latestPoseResult, trackUpperBody) {
        avatarHandPoints(latestHandResult, if (trackUpperBody) latestPoseResult else null, handFilters)
    }
    // Where your eyes are in the camera frame (mirrored like the preview) —
    // drives "follow my head". The last known placement is held while the
    // face is briefly lost, so the avatar doesn't snap back to the centre.
    val framingFilters = remember { OneEuroFilterBank(minCutoff = 1.2, beta = 0.8, dCutoff = 1.0) }
    val lastFraming = remember { arrayOfNulls<AvatarFraming>(1) }
    val framing = remember(latestFaceResult, trackingFrameWidth, trackingFrameHeight) {
        faceFraming(latestFaceResult, trackingFrameWidth, trackingFrameHeight, framingFilters)
            ?.also { lastFraming[0] = it } ?: lastFraming[0]
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        prefsManager.vrmAvatarUri.firstOrNull()?.let { pickedVrmUri = Uri.parse(it) }
    }
    androidx.compose.runtime.LaunchedEffect(pickedVrmUri) {
        val uri = pickedVrmUri
        // A different avatar: its parts are different, start all visible.
        hiddenParts = emptySet()
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
                    hands = handPoints
                )
            )
        }
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
                    modifier = Modifier.fillMaxSize(),
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
                    hiddenParts = hiddenParts
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
            VrmTrackingOverlay(
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
            // Small black "what the tracker sees" box, bottom-right: landmark
            // dots/skeleton only — never the camera image itself (VRM mode
            // deliberately never shows the user's real face).
            TrackingPreview(
                faceResult = latestFaceResult,
                handResult = latestHandResult,
                poseResult = if (trackUpperBody) latestPoseResult else null,
                frameWidth = trackingFrameWidth,
                frameHeight = trackingFrameHeight,
                tint = tint,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(end = 12.dp, bottom = 12.dp)
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
        // top-left close affordance in this app. Tinted with the user's
        // color so the VRM UI matches the rest of the app.
        Box(
            Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp)
                .size(40.dp).clip(CircleShape)
                .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = CircleShape) else Modifier.background(tint.copy(alpha = 0.25f)))
                .clickable { tap(); onClose() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
        }

        // Bottom bar: record/photo + settings, per spec ("a recording/
        // picture button at the bottom, and the settings next to it").
        // Tinted with the user's color.
        Row(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape)
                    .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = CircleShape) else Modifier.background(tint.copy(alpha = 0.25f)))
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
                    .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = CircleShape) else Modifier.background(tint.copy(alpha = 0.25f)))
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
                followHead = followHead, onToggleFollowHead = { followHead = it },
                avatarParts = avatarParts,
                hiddenParts = hiddenParts,
                onSetPartVisible = { id, visible -> hiddenParts = if (visible) hiddenParts - id else hiddenParts + id },
                onShowAllParts = { hiddenParts = emptySet() },
                hasAvatar = vrmBytes != null,
                onPickAvatar = { vrmAvatarPickerLauncher.launch(arrayOf("*/*")) },
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
    androidx.compose.runtime.SideEffect { trackPoseFlag.set(trackPose) }
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
            if (nowMs - lastSubmittedMs.get() < 66L) {
                imageProxy.close()
                return@setAnalyzer
            }
            lastSubmittedMs.set(nowMs)
            val upright: Bitmap = try {
                imageProxy.use { proxy -> proxyToUprightBitmap(proxy, tightBufferHolder, rowScratchHolder) }
            } catch (t: Throwable) {
                android.util.Log.e("VrmModeScreen", "Frame conversion failed", t)
                return@setAnalyzer
            }
            val mpImage = BitmapImageBuilder(upright).build()
            // Rotation 0: the bitmap is already upright (see note above).
            // uptimeMillis, not currentTimeMillis — LIVE_STREAM mode rejects
            // timestamps that ever go backwards, and wall-clock time can.
            faceHelper?.detectAsync(mpImage, 0, nowMs)
            handHelper?.detectAsync(mpImage, 0, nowMs)
            if (trackPoseFlag.get()) poseHelper?.detectAsync(mpImage, 0, nowMs)
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

/** RGBA_8888 ImageProxy → upright ARGB_8888 Bitmap. Handles padded rows
 *  (rowStride > width*4), which the old straight copyPixelsFromBuffer did
 *  not, then rotates by the frame's rotationDegrees so the face is upright
 *  before MediaPipe ever sees it. Runs on the tracking thread only. */
private fun proxyToUprightBitmap(
    proxy: androidx.camera.core.ImageProxy,
    tightBufferHolder: Array<java.nio.ByteBuffer?>,
    rowScratchHolder: Array<ByteArray?>
): Bitmap {
    val width = proxy.width
    val height = proxy.height
    val plane = proxy.planes[0]
    val source = plane.buffer
    source.rewind()
    val rowBytes = width * 4
    val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
    val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(raw, 0, 0, width, height, matrix, false)
    if (rotated !== raw) raw.recycle()
    return rotated
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
 * HandLandmarker's 21 world landmarks per AVATAR side, One-Euro smoothed —
 * they drive the avatar's wrist orientation and every finger bone.
 *
 * Which physical hand is which: when the pose is tracked, each hand goes to
 * whichever pose wrist it's closest to (robust). Otherwise MediaPipe's
 * handedness label is used — it's documented as assuming a mirrored selfie
 * image, and our frames are NOT mirrored, so its "Left" is the person's
 * right hand. Then, mirrored, the person's right hand drives the avatar's
 * left (see AvatarRetargeter.MIRROR).
 */
private fun avatarHandPoints(
    hands: HandLandmarkerResult?,
    pose: PoseLandmarkerResult?,
    filters: OneEuroFilterBank
): Map<String, List<FloatArray>> {
    if (hands == null) {
        filters.resetPrefixed("finger.")
        return emptyMap()
    }
    val world = hands.worldLandmarks()
    val image = hands.landmarks()
    val handedness = hands.handednesses()
    val poseImage = pose?.landmarks()?.firstOrNull()?.takeIf { it.size > 16 }
    val timestampSeconds = hands.timestampMs() / 1000.0
    val out = HashMap<String, List<FloatArray>>()
    for (i in world.indices) {
        val wrist = image.getOrNull(i)?.getOrNull(0)
        val personSide = if (poseImage != null && wrist != null) {
            val l = poseImage[15]; val r = poseImage[16]
            val dl = (wrist.x() - l.x()) * (wrist.x() - l.x()) + (wrist.y() - l.y()) * (wrist.y() - l.y())
            val dr = (wrist.x() - r.x()) * (wrist.x() - r.x()) + (wrist.y() - r.y()) * (wrist.y() - r.y())
            if (dl <= dr) "left" else "right"
        } else {
            personSideFromLabel(handedness.getOrNull(i)?.firstOrNull()?.categoryName())
        } ?: continue
        val avatarSide = if (AvatarRetargeter.MIRROR) (if (personSide == "left") "right" else "left") else personSide
        if (out.containsKey(avatarSide)) continue
        val points = world.getOrNull(i)?.takeIf { it.size >= 21 } ?: continue
        out[avatarSide] = points.mapIndexed { k, p ->
            floatArrayOf(
                filters.filter("finger.$avatarSide.$k.x", p.x(), timestampSeconds),
                filters.filter("finger.$avatarSide.$k.y", p.y(), timestampSeconds),
                filters.filter("finger.$avatarSide.$k.z", p.z(), timestampSeconds)
            )
        }
    }
    for (side in listOf("left", "right")) if (!out.containsKey(side)) filters.resetPrefixed("finger.$side.")
    return out
}

/** MediaPipe's handedness label → the person's actual hand, for our
 *  un-mirrored camera frames (see [avatarHandPoints]). */
private fun personSideFromLabel(label: String?): String? = when (label) {
    "Left" -> "right"
    "Right" -> "left"
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
        texturesApplied < 0 -> "textures: loading…"
        texturesApplied == 0 -> "textures: none bound (untextured model, or decode failed — see logcat MToonApplier)"
        else -> "textures: $texturesApplied materials textured"
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

@Composable
private fun VrmSettingsSheet(
    liquidGlass: Boolean,
    trackUpperBody: Boolean, onToggleUpperBody: (Boolean) -> Unit,
    trackFullBody: Boolean, onToggleFullBody: (Boolean) -> Unit,
    followHead: Boolean, onToggleFollowHead: (Boolean) -> Unit,
    avatarParts: List<AvatarPart>,
    hiddenParts: Set<String>,
    onSetPartVisible: (id: String, visible: Boolean) -> Unit,
    onShowAllParts: () -> Unit,
    hasAvatar: Boolean,
    onPickAvatar: () -> Unit,
    onDismiss: () -> Unit
) {
    val tap = rememberHapticTap()
    var partsExpanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { tap(); onDismiss() }) {
    Box(
        Modifier.fillMaxWidth().align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp).clip(RoundedCornerShape(20.dp))
            .then(if (liquidGlass) Modifier.glassPanel(true, shape = RoundedCornerShape(20.dp)) else Modifier.background(Color(0xFF1A1A1A)))
            // Swallow taps on the sheet itself so they don't dismiss it.
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
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
            VrmSettingsToggleRow("Follow my head", followHead) { onToggleFollowHead(it) }
            // Avatar parts: every mesh piece (clothes, hair, accessories …)
            // can be hidden. Collapsed to one row; tap to list them.
            if (avatarParts.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable { tap(); partsExpanded = !partsExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Avatar parts", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    val shown = avatarParts.count { it.id !in hiddenParts }
                    Text(
                        "$shown/${avatarParts.size} shown  ${if (partsExpanded) "▴" else "▾"}",
                        color = com.mediaviewer.ui.theme.DimGray, fontSize = 12.sp
                    )
                }
                if (partsExpanded) {
                    androidx.compose.foundation.layout.Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(start = 12.dp)
                    ) {
                        for (part in avatarParts) {
                            VrmSettingsToggleRow(part.label, part.id !in hiddenParts) { onSetPartVisible(part.id, it) }
                        }
                    }
                    if (hiddenParts.isNotEmpty()) {
                        Text(
                            "Show all", color = Color(0xFF1083FE), fontSize = 13.sp,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp).clickable { tap(); onShowAllParts() }
                        )
                    }
                }
            }
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
