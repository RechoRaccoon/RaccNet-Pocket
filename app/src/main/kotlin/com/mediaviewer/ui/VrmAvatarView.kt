package com.mediaviewer.ui

import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import kotlinx.coroutines.launch
import com.mediaviewer.util.Quaternion
import com.mediaviewer.util.VrmData
import com.mediaviewer.util.VrmSpecVersion
import com.mediaviewer.util.multiplyColumnMajor4x4
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * VRM pipeline step 5 (see VrmModeScreen.kt's doc comment / the handoff's
 * "VRM: what's next" section): renders whatever glTF/`.vrm` file
 * [vrmBytes] holds via Google's Filament, using `filament-utils-android`'s
 * `ModelViewer` — a convenience wrapper (from Filament's own
 * `android/samples/model-viewer` sample app, which is where this file's
 * shape comes from) that bundles the Engine/Renderer/Scene/View/Camera
 * boilerplate and a `loadModelGlb` call into gltfio's loader — exactly the
 * "one `AndroidView`-wrapped `SurfaceView`" the handoff describes for this
 * step, without hand-rolling Filament's lower-level API.
 *
 * ## Rotation: the model spins in place, not the camera
 * `ModelViewer`'s own touch listener drives a camera-orbit manipulator —
 * dragging moves the *camera* around a fixed point. That reads as "the
 * scene swings around" rather than "the avatar turns," and depends on the
 * avatar's bounding-box center always landing exactly on the manipulator's
 * fixed target to look right at all. This view doesn't wire that listener
 * up at all; instead [Modifier.pointerInput] below tracks horizontal drag
 * distance directly into [applyYaw], which rotates the *model's own root
 * transform* around its own center (see that function's doc comment) —
 * the camera never moves, only the avatar turns, which is what "rotate
 * around the model" means literally. [applyYaw] is also how the VRM 0.x
 * facing fix (avatars exported from that spec face the wrong way once
 * loaded via a standard glTF viewer) is applied: as a fixed 180° baseline
 * yaw, with the user's drag added on top of it — see [BASE_YAW_VRM0_DEGREES].
 *
 * ## Scope: this step renders the model and applies bone updates
 * [vrmBytes] is loaded and shown exactly as exported (T-pose or whatever
 * rest pose the avatar was authored in). The frame callback also calls
 * `animator?.updateBoneMatrices()` before every render — without it, the
 * `TransformManager.setTransform` calls [AvatarRetargeter] makes never
 * reach the skinned meshes (verified against Filament 1.51.6's
 * `ModelViewer.kt`: `render()` doesn't call it itself), so the avatar
 * would stay frozen in its rest pose no matter what tracking reports.
 * [parsedVrmData] is the same bytes already run through [VrmParser] (step
 * 4) off the main thread by the caller — this view never parses on the UI
 * thread itself. [onRetargetTargetReady] hands back a [RetargetTarget] —
 * the node-index→entity bridge `AvatarRetargeter.kt` (step 6) needs to
 * actually drive this rendered model.
 *
 * ## Background: the user's own profile color, not Filament's black default
 * A Compose `Modifier.background` behind this view would never be
 * visible — [SurfaceView] is forced opaque (see the factory below) so it
 * fully composites over anything Compose draws behind it. The actual
 * background has to be Filament's own solid-color Skybox, set via
 * [applyBackgroundColor].
 *
 * ## No bundled lighting assets
 * A "proper" Filament scene usually lights itself via an image-based
 * light (IBL) baked into a `.ktx` cubemap file — but that's another
 * binary asset this sandbox has no network to fetch (same constraint as
 * the MediaPipe `.task` models — see FaceLandmarkerHelper's doc comment).
 * Instead, [addThreeLightRig] adds three plain directional lights (key/
 * fill/rim, a standard cheap lighting rig) — no bundled asset needed, at
 * the cost of a flatter look than a real IBL would give.
 *
 * ## Memory/perf: why texture binding is split into two phases
 * See [com.mediaviewer.util.MToonTextureApplier]'s doc comment for the
 * full reasoning — short version: decoding/downsampling texture bytes is
 * pure CPU work done off the main thread in [loadVrmInto]; only the fast
 * GL-context-bound upload happens inside the `runBlocking(Main)` block
 * below, so a multi-texture avatar no longer stalls the UI thread (and
 * therefore MediaPipe's callbacks) for as long as it used to, and no
 * longer needs three full-resolution copies of every texture in memory at
 * once.
 *
 * ## Verified API surface
 * `ModelViewer`'s public surface (`engine`/`scene`/`renderer`/`loadModelGlb`/
 * `transformToUnitCube`/`destroyModel`/`render`/`asset`/`asset.root` — all
 * used below) was verified against the pinned `filament-utils-android`
 * 1.51.6 sources (ModelViewer.kt): `loadModelGlb(buffer)` calls
 * `destroyModel()` itself, then `assetLoader.createAsset(buffer)` +
 * `resourceLoader.asyncBeginLoad(asset)`, and `render()` drives
 * `asyncUpdateLoad()` per frame — which is why a direct (not heap)
 * `ByteBuffer` is required here: gltfio's native loader reads it via
 * `GetDirectBufferAddress`. `render()` does NOT call
 * `animator.updateBoneMatrices()` itself, hence the explicit call in the
 * frame callback. `transformToUnitCube()`'s own source (`asset.boundingBox`,
 * `scale(...) * translation(...)`, default center `(0,0,-4)`) is what
 * [applyYaw]'s pivot point is derived from — see that function's doc
 * comment.
 */
@Composable
fun VrmAvatarView(
    modifier: Modifier = Modifier,
    vrmBytes: ByteArray?,
    // Parsed off the main thread by VrmModeScreen (VrmParser on a multi-MB
    // file is not free) and handed in here — this view used to parse on
    // the UI thread itself, once in the AndroidView factory and again in
    // the LaunchedEffect, which was part of why opening VRM mode stalled.
    parsedVrmData: VrmData? = null,
    // The user's own profile color (see VrmModeScreen's doc comment on its
    // own `tint` param) — used as Filament's flat background color, see
    // applyBackgroundColor's doc comment for why this can't just be a
    // Compose background Modifier.
    backgroundTint: Color = Color.Black,
    onRetargetTargetReady: (RetargetTarget?) -> Unit = {},
    // Diagnostic: reports how many MToon textures were actually bound.
    // Lets us distinguish "model has no textures" (count 0) from
    // "binding failed" (count 0 with an error) from "binding worked".
    onTexturesApplied: (Int) -> Unit = {}
) {
    // Held outside the AndroidView factory so the LaunchedEffect below —
    // which reacts to vrmBytes changing — can reach the same ModelViewer
    // instance the factory created, without recreating the SurfaceView
    // (and therefore the whole Filament Engine/GL context) on every new
    // file pick.
    val viewerHolder = remember { arrayOfNulls<ModelViewer>(1) }
    // The current background Skybox, so applyBackgroundColor can destroy
    // the previous one instead of leaking a new Filament object every
    // time backgroundTint changes.
    val skyboxHolder = remember { arrayOfNulls<com.google.android.filament.Skybox>(1) }
    // Which exact ByteArray instance is currently loaded in the viewer.
    // The AndroidView factory below loads vrmBytes on first composition;
    // LaunchedEffect(vrmBytes) also fires on first composition (after the
    // factory), so without this guard the model would load twice on every
    // cold entry. Reference equality is the right check: vrmBytes is only
    // ever reassigned when the file is actually re-read.
    val loadedBytesHolder = remember { arrayOfNulls<ByteArray>(1) }
    // The root transform transformToUnitCube() produced for whatever
    // model is currently loaded, captured once right after load — see
    // applyYaw's doc comment for why every yaw update recomputes from
    // this cached base rather than the transform's current (possibly
    // already-rotated) value.
    val baseTransformHolder = remember { arrayOfNulls<FloatArray>(1) }
    // The model's own facing-fix baseline (180° for VRM 0.x, 0° for VRM
    // 1.0 — see BASE_YAW_VRM0_DEGREES), set once per successful load.
    var baseYawDegrees by remember { mutableStateOf(0f) }
    // The user's own drag-to-rotate contribution, added on top of
    // baseYawDegrees — see the pointerInput block below. Reset to 0 on a
    // fresh model load so a new avatar always starts facing the fixed-up
    // direction rather than wherever the previous one was left spun to.
    var userYawDegrees by remember { mutableStateOf(0f) }
    // Load failures used to be log-only (Log.e) — on a device that reads
    // as "the model doesn't show up" with zero explanation. Surfaced here
    // as a small on-screen line so a failed load is diagnosable instead
    // of a silent black screen.
    var loadError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                choreographer.postFrameCallback(this)
                runCatching {
                    viewerHolder[0]?.let { viewer ->
                        // Bone transforms that AvatarRetargeter writes via
                        // TransformManager.setTransform don't reach the
                        // skinned meshes until this runs — verified against
                        // Filament 1.51.6's own ModelViewer.kt source:
                        // render() never calls updateBoneMatrices() itself
                        // (its KDoc leaves animation/bone updates to the
                        // client), so without this the avatar stays frozen
                        // in its rest pose no matter what tracking says.
                        viewer.animator?.updateBoneMatrices()
                        viewer.render(frameTimeNanos)
                    }
                }
                    .onFailure { Log.e(TAG, "Filament render() failed", it) }
            }
        }
        choreographer.postFrameCallback(frameCallback)
        onDispose {
            choreographer.removeFrameCallback(frameCallback)
            // Teardown is best-effort and NON-fatal: destroying the
            // Filament engine here used to crash the X button (a native
            // teardown racing an in-flight frame). We only destroy the
            // MODEL, not the engine — the engine survives for reuse if
            // VRM mode is reopened, and the Activity's own destroy
            // handles the final GL context release. Catches Throwable
            // because a native Filament failure surfaces as an Error,
            // not an Exception.
            runCatching {
                viewerHolder[0]?.destroyModel()
            }.onFailure { Log.e(TAG, "Filament destroyModel() failed", it) }
            runCatching {
                skyboxHolder[0]?.let { viewerHolder[0]?.engine?.destroySkybox(it) }
            }.onFailure { Log.e(TAG, "Filament destroySkybox() failed", it) }
            skyboxHolder[0] = null
            viewerHolder[0] = null
        }
    }

    LaunchedEffect(vrmBytes) {
        val viewer = viewerHolder[0]
        if (viewer == null) {
            // AndroidView's factory hasn't run yet (first composition) —
            // nothing to load into. The factory below loads the current
            // vrmBytes itself once it exists, so this isn't a lost update.
            return@LaunchedEffect
        }
        if (vrmBytes == null) {
            runCatching { viewer.destroyModel() }
            loadedBytesHolder[0] = null
            baseTransformHolder[0] = null
            loadError = null
            onRetargetTargetReady(null)
            return@LaunchedEffect
        }
        // The factory already loaded these exact bytes during this
        // composition — don't load twice (see loadedBytesHolder).
        if (loadedBytesHolder[0] === vrmBytes) return@LaunchedEffect
        loadedBytesHolder[0] = vrmBytes
        userYawDegrees = 0f // fresh avatar — don't inherit the last one's spin
        // Off the main thread — see the factory's comment for why.
        // Structured concurrency: if vrmBytes changes, this whole
        // LaunchedEffect (including the child launch) is cancelled.
        launch(kotlinx.coroutines.Dispatchers.Default) {
            val loadResult = loadVrmInto(viewer, vrmBytes, parsedVrmData)
            val target = buildRetargetTargetOrNull(viewer, parsedVrmData)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                loadError = loadResult.error
                baseTransformHolder[0] = loadResult.baseTransform
                baseYawDegrees = loadResult.baseYawDegrees
                onTexturesApplied(loadResult.texturesApplied)
                onRetargetTargetReady(target)
            }
        }
    }

    // Re-applies the combined (facing-fix + drag) yaw whenever either
    // changes. Cheap — one small matrix composition and one
    // TransformManager.setTransform call, nothing GPU-heavy — so it's
    // fine to run this on every drag-frame update, unlike a model reload.
    LaunchedEffect(baseYawDegrees, userYawDegrees) {
        val viewer = viewerHolder[0] ?: return@LaunchedEffect
        val base = baseTransformHolder[0] ?: return@LaunchedEffect
        applyYaw(viewer, base, baseYawDegrees + userYawDegrees)
    }

    // The user's own profile color as Filament's clear color — see
    // applyBackgroundColor's doc comment. Re-applied whenever the color
    // itself changes (e.g. a slow-loading avatar image resolving the
    // dominant color after this screen already opened).
    LaunchedEffect(backgroundTint) {
        viewerHolder[0]?.let { applyBackgroundColor(it, backgroundTint, skyboxHolder) }
    }

    AndroidView(
        modifier = modifier.pointerInput(Unit) {
            // Drag-to-rotate the MODEL, not the camera — see this file's
            // top doc comment. Only the horizontal component is used
            // (yaw); this is a spin-in-place turntable, not a full orbit.
            detectDragGestures(
                onDrag = { change, dragAmount ->
                    change.consume()
                    // Degrees per pixel: a full screen-width drag is
                    // roughly a full turn-and-a-bit (chosen to feel like a
                    // turntable, not a hair-trigger spin).
                    userYawDegrees += dragAmount.x * DRAG_DEGREES_PER_PX
                }
            )
        },
        factory = { ctx ->
            runCatching { Utils.init() }.onFailure { Log.e(TAG, "Filament Utils.init() failed", it) }
            val surfaceView = SurfaceView(ctx).apply {
                // Explicitly opaque: this view used to sit transparent over
                // a live camera preview (removed for privacy — the user
                // didn't want VRM mode showing their IRL face). A leftover
                // translucent surface with no video underneath renders as
                // black and can swallow the avatar entirely. Opaque forces
                // Filament to composite normally over the black Box behind.
                holder.setFormat(android.graphics.PixelFormat.OPAQUE)
            }
            // No manipulator/touch-listener wired to the SurfaceView here
            // (unlike the old `ModelViewer(surfaceView)` + `setOnTouchListener
            // (viewer)` pattern) — rotation is handled by this view's own
            // pointerInput above instead, driving the model's transform
            // directly rather than a camera orbit. ModelViewer still owns a
            // default manipulator internally (needed for its per-frame
            // camera.lookAt), it's just never fed touch events, so the
            // camera stays put at its initial look-at.
            val viewer = ModelViewer(surfaceView)
            addThreeLightRig(viewer.engine, viewer.scene)
            // The flat ambient IndirectLight is REQUIRED for textured PBR
            // materials to show their albedo — a directional-only rig leaves
            // them black.
            addFlatAmbientLight(viewer.engine, viewer.scene)
            applyBackgroundColor(viewer, backgroundTint, skyboxHolder)
            viewerHolder[0] = viewer
            if (vrmBytes != null) {
                loadedBytesHolder[0] = vrmBytes
                // Load off the main thread: GLB parsing + MToon texture
                // decoding of a multi-MB file chokes the UI thread long
                // enough to starve the MediaPipe result callbacks, which is
                // why tracking looked dead on first entry. Only the fast
                // GL-context calls happen back on main — see loadVrmInto.
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    val loadResult = loadVrmInto(viewer, vrmBytes, parsedVrmData)
                    val target = buildRetargetTargetOrNull(viewer, parsedVrmData)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        loadError = loadResult.error
                        baseTransformHolder[0] = loadResult.baseTransform
                        baseYawDegrees = loadResult.baseYawDegrees
                        onTexturesApplied(loadResult.texturesApplied)
                        onRetargetTargetReady(target)
                    }
                }
            }
            surfaceView
        }
    )

    // See loadError's declaration — a failed load is shown, not silent.
    loadError?.let { error ->
        Box(modifier, contentAlignment = Alignment.BottomCenter) {
            Text(
                text = error,
                color = Color.Red.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/** Pixels-of-drag to degrees-of-yaw for the turntable gesture above. */
private const val DRAG_DEGREES_PER_PX = 0.25f

/** Only meaningful once a model has actually loaded (needs [ModelViewer.asset]
 *  to exist) and only once [parsedVrmData] found bones/expressions to bridge
 *  in the first place. See [AvatarRetargeter.buildTarget]'s own doc comment
 *  for what the bridge it builds actually is and how confident it is. */
private fun buildRetargetTargetOrNull(viewer: ModelViewer, parsedVrmData: VrmData?): RetargetTarget? {
    if (parsedVrmData == null) return null
    val asset = viewer.asset ?: return null
    return runCatching { AvatarRetargeter.buildTarget(viewer.engine, asset, parsedVrmData) }
        .onFailure { Log.e(TAG, "Could not build retarget target", it) }
        .getOrNull()
}

/** Everything [loadVrmInto] needs to hand back to the caller. [baseTransform]
 *  is the raw root transform transformToUnitCube() produced — null if the
 *  load failed before that point — captured once so [applyYaw] never has
 *  to re-derive it (see that function's doc comment). */
private class VrmLoadResult(
    val error: String?,
    val texturesApplied: Int,
    val baseTransform: FloatArray?,
    val baseYawDegrees: Float
)

/** Loads [bytes] into [viewer]; returns a [VrmLoadResult] describing what
 *  happened. A human-readable error is what VrmAvatarView draws on screen
 *  (see loadError) so a broken file is diagnosable.
 *
 *  Split thread usage (see this file's top doc comment, "Memory/perf"):
 *  MToon parsing AND texture decoding (both pure CPU, both potentially
 *  slow on a multi-MB file with several large textures) happen on
 *  whichever thread calls this function — callers run it from
 *  Dispatchers.Default. Only the actual Filament engine calls
 *  (destroyModel, loadModelGlb, texture upload, the root transform) are
 *  inside the runBlocking(Main) block, because Filament's Engine is not
 *  safe to drive from a background thread while the Choreographer is
 *  rendering on main. Keeping that block to GL-context-only work is what
 *  keeps it short — the old version did the texture decode itself inside
 *  this same runBlocking(Main), which is what stalled the UI thread (and
 *  therefore MediaPipe's callbacks) for as long as the biggest texture
 *  took to decode. */
private fun loadVrmInto(
    viewer: ModelViewer,
    bytes: ByteArray,
    parsedVrmData: VrmData?
): VrmLoadResult {
    val direct = ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.nativeOrder())
    direct.put(bytes)
    direct.flip()
    // Parse MToon materials BEFORE loading — we need the texture data to
    // manually apply after gltfio (which doesn't support MToon) loads.
    val mtoonParseResult = com.mediaviewer.util.MToonMaterialParser.parse(bytes)
    // CPU-only decode/downsample of every referenced texture — see
    // MToonTextureApplier's doc comment. Done here, still off the main
    // thread, so the runBlocking(Main) block below only has to do the
    // fast GL upload.
    val decodedTextures = if (mtoonParseResult != null) {
        runCatching { com.mediaviewer.util.MToonTextureApplier.decodeTextures(mtoonParseResult) }
            .onFailure { Log.e(TAG, "Texture decode pass failed", it) }
            .getOrDefault(emptyMap())
    } else {
        emptyMap()
    }

    val result = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) {
        var texturesApplied = 0
        var baseTransform: FloatArray? = null
        var baseYaw = 0f
        val failure = runCatching {
            viewer.destroyModel()
            viewer.loadModelGlb(direct)
            val asset = viewer.asset
            if (asset != null && mtoonParseResult != null) {
                texturesApplied = com.mediaviewer.util.MToonTextureApplier.bindTextures(
                    viewer.engine, asset, mtoonParseResult, decodedTextures
                )
                Log.i(TAG, "Applied MToon textures to $texturesApplied materials")
            }
            viewer.transformToUnitCube()
            // VRM 0.x avatars face +Z, which is *backwards* from a
            // standard glTF viewer's forward (-Z) — see this file's top
            // doc comment. Fixed here as a baseline yaw, applied through
            // applyYaw (below, once baseTransform is captured) rather
            // than as a one-off rotation through the world origin — the
            // old attempt at this fix rotated through (0,0,0) while the
            // model was centered at (0,0,-4) by transformToUnitCube(),
            // which flung the whole model behind the camera instead of
            // just turning it around. applyYaw pivots on the model's
            // actual center instead, so this is safe to always apply.
            baseYaw = if (parsedVrmData?.specVersion == VrmSpecVersion.VRM_0) BASE_YAW_VRM0_DEGREES else 0f
            baseTransform = captureRootTransform(viewer)
        }.exceptionOrNull()
        if (failure != null) {
            Log.e(TAG, "Filament failed to load VRM file as glTF", failure)
            return@runBlocking VrmLoadResult("Couldn't load that .vrm file (${failure::class.simpleName})", texturesApplied, null, 0f)
        }
        if (viewer.asset == null) {
            Log.e(TAG, "Filament createAsset returned null for the VRM file")
            return@runBlocking VrmLoadResult("Couldn't parse that .vrm file (not valid glTF?)", texturesApplied, null, 0f)
        }
        // A heap (non-direct) ByteBuffer used to make gltfio's native loader
        // silently produce an asset with zero entities — no exception, just
        // nothing to render. Guard here so "black screen, no error" becomes
        // an actual message instead.
        if (viewer.asset!!.entities.isEmpty()) {
            Log.e(TAG, "Filament loaded the VRM file but it contains no entities")
            return@runBlocking VrmLoadResult("That .vrm file loaded empty (no visible geometry?)", texturesApplied, null, 0f)
        }
        return@runBlocking VrmLoadResult(null, texturesApplied, baseTransform, baseYaw)
    }
    return result
}

/** 180° about Y — see [loadVrmInto]'s VRM 0.x comment. */
private const val BASE_YAW_VRM0_DEGREES = 180f

/** The world-space point transformToUnitCube() (called with its default
 *  argument, as [loadVrmInto] does) places the model's own bounding-box
 *  center at — verified against filament-utils-android 1.51.6's
 *  ModelViewer.kt source: its default `centerPoint` parameter, and the
 *  default camera manipulator's own `targetPosition`, are both this exact
 *  point. [applyYaw] pivots rotation around it so the avatar spins in
 *  place at its own center rather than swinging through empty space. */
private val UNIT_CUBE_PIVOT = floatArrayOf(0f, 0f, -4f)

/** Snapshot of the asset root's current transform, in the same
 *  column-major convention [com.mediaviewer.util.Quaternion] and
 *  [multiplyColumnMajor4x4] use (that convention is documented — see
 *  Quaternion.kt's own doc comment — to match what
 *  `TransformManager.getTransform`/`setTransform` read and write
 *  directly, no transpose needed either direction, unlike kotlin-math's
 *  `Mat4.toFloatArray()`). Returns null if there's no loaded asset. */
private fun captureRootTransform(viewer: ModelViewer): FloatArray? {
    val asset = viewer.asset ?: return null
    val tm = viewer.engine.transformManager
    val instance = tm.getInstance(asset.root)
    if (instance == 0) return null
    val transform = FloatArray(16)
    tm.getTransform(instance, transform)
    return transform
}

/**
 * Rotates the model [yawDegrees] around its own vertical (Y) axis, pivoting
 * on [UNIT_CUBE_PIVOT] rather than the world origin — composed as
 * `T(pivot) * R(yaw) * T(-pivot) * base`, always recomputed from [base]
 * (transformToUnitCube()'s original output, captured once at load time via
 * [captureRootTransform]) rather than the transform's current live value.
 * Recomputing from a fixed base every call, instead of composing
 * incrementally onto whatever the transform already is, means repeated
 * calls (every drag-frame update) can't accumulate floating-point drift
 * and — more importantly — can't compound: setting `yawDegrees` back to
 * the same value always produces the exact same transform, which is what
 * lets [VrmAvatarView] freely combine the fixed VRM-0 facing offset with
 * the user's live drag delta as a single sum instead of two separately-
 * tracked rotations.
 *
 * Pivoting on a fixed point rather than the world origin is the fix for
 * the old "rotates around the scene, not the model" behavior: a rotation
 * applied through the origin only looks like it's spinning *the model* in
 * place when the model's own center happens to already be at the origin.
 * transformToUnitCube() doesn't put it there — see [UNIT_CUBE_PIVOT]'s doc
 * comment — so a same-shaped bug (this file's own git history: the
 * disabled `fixVrm0Facing()` rotated through the origin and flung the
 * model behind the camera) was always one rotation away from resurfacing
 * anywhere a transform gets rotated without accounting for where the
 * model actually ended up.
 */
private fun applyYaw(viewer: ModelViewer, base: FloatArray, yawDegrees: Float) {
    val asset = viewer.asset ?: return
    val tm = viewer.engine.transformManager
    val instance = tm.getInstance(asset.root)
    if (instance == 0) return

    val halfAngleRadians = Math.toRadians(yawDegrees.toDouble()) / 2.0
    val rotationQuaternion = Quaternion(
        0f,
        sin(halfAngleRadians).toFloat(),
        0f,
        cos(halfAngleRadians).toFloat()
    )
    val rotation = rotationQuaternion.toColumnMajorMatrix()
    val toPivot = translationMatrix(-UNIT_CUBE_PIVOT[0], -UNIT_CUBE_PIVOT[1], -UNIT_CUBE_PIVOT[2])
    val fromPivot = translationMatrix(UNIT_CUBE_PIVOT[0], UNIT_CUBE_PIVOT[1], UNIT_CUBE_PIVOT[2])

    var transform = multiplyColumnMajor4x4(toPivot, base)
    transform = multiplyColumnMajor4x4(rotation, transform)
    transform = multiplyColumnMajor4x4(fromPivot, transform)

    runCatching { tm.setTransform(instance, transform) }
        .onFailure { Log.e(TAG, "setTransform failed while applying yaw", it) }
}

/** Column-major 4x4 pure translation — same convention as
 *  [com.mediaviewer.util.Quaternion]'s matrices (translation in indices
 *  12–14). */
private fun translationMatrix(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    x, y, z, 1f
)

/**
 * Sets Filament's own background to a flat fill of [tint] — the user's
 * profile color. This can NOT be done with a Compose `Modifier.background`
 * behind this view: the `SurfaceView` above is forced
 * [android.graphics.PixelFormat.OPAQUE] (see the factory's own comment for
 * why) specifically so Filament always composites cleanly over whatever's
 * behind it — which means whatever's behind it is exactly as invisible as
 * if it weren't there at all. A solid-color [com.google.android.filament.Skybox]
 * is the actual background this view has (`scene.skybox = Skybox.Builder()
 * .color(r,g,b,a).build(engine)` — a verified, commonly-used pattern for
 * exactly this "flat color behind a Filament scene" case, not the
 * lower-level per-frame clear color).
 *
 * Replaces any previously-set skybox on [viewer]'s scene and destroys the
 * old one — called every time [backgroundTint] changes (see
 * VrmAvatarView's `LaunchedEffect(backgroundTint)`), so without explicitly
 * destroying the old skybox each call would leak one Filament object.
 */
private fun applyBackgroundColor(viewer: ModelViewer, tint: Color, previousSkybox: Array<com.google.android.filament.Skybox?>) {
    runCatching {
        val skybox = com.google.android.filament.Skybox.Builder()
            .color(tint.red, tint.green, tint.blue, 1f)
            .build(viewer.engine)
        val old = previousSkybox[0]
        viewer.scene.skybox = skybox
        previousSkybox[0] = skybox
        if (old != null) viewer.engine.destroySkybox(old)
    }.onFailure { Log.e(TAG, "Could not set Filament background skybox", it) }
}

/** A plain three-point directional-light rig — see this file's top doc
 *  comment for why there's no image-based light here. Angles/intensities
 *  are a starting guess (a portrait-lighting key/fill/rim split), not
 *  measured against a real avatar on a real device; adjust to taste once
 *  step 5 is actually visible to look at. */
private fun addThreeLightRig(engine: Engine, scene: com.google.android.filament.Scene) {
    val entityManager = EntityManager.get()

    fun directionalLight(x: Float, y: Float, z: Float, intensityLux: Float) {
        val entity = entityManager.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(intensityLux)
            .direction(x, y, z)
            .castShadows(false)
            .build(engine, entity)
        scene.addEntity(entity)
    }

    directionalLight(-0.5f, -1.0f, -0.3f, 32_000f)  // key: front-upper-left, brightest — kept well below clipping so saturated albedos (reds) don't blow out
    directionalLight(0.6f, -0.2f, -0.4f, 14_000f)   // fill: front-right, softer, keeps the key's shadow side readable
    directionalLight(0.0f, 0.3f, 1.0f, 10_000f)    // rim: from behind, separates the avatar's silhouette from the background
    directionalLight(0.0f, -0.1f, -1.0f, 9_000f)    // frontal lift: dim head-on light so unlit faces fall to dark grey, not pure black
}

/**
 * Three plain directional lights alone leave every surface that isn't
 * facing one of them completely unlit — for a PBR material that's true
 * black, no matter what the underlying albedo/base-color texture actually
 * contains. On a real device that reads as flat, blocky patches of pure
 * material color (hair, clothing) next to solid black (anything angled
 * away from all three lights, most of a face turned even slightly from
 * camera) — easy to mistake for "textures aren't loading" when it's
 * really "nothing is lighting the far side of the model at all."
 *
 * A real scene would fix this with an image-based light (IBL) baked from
 * a `.ktx` cubemap — same "no network to fetch a binary asset" constraint
 * as the MediaPipe `.task` models (see this file's top doc comment) — so
 * this builds a flat ambient term instead: [IndirectLight] accepts raw
 * spherical-harmonics coefficients directly, and passing only band 0 (a
 * single constant RGB triple, no higher-order bands) gives a uniform
 * ambient fill from every direction at once, entirely without a texture
 * asset. It's flat/directionless on purpose — a real IBL captures how
 * light varies by direction, this doesn't attempt to — but it's enough to
 * lift every surface out of true-black and let its actual texture/albedo
 * show, which a directional-only rig fundamentally can't do.
 */
private fun addFlatAmbientLight(engine: Engine, scene: com.google.android.filament.Scene) {
    // EXACT values from the old working build — do not "fix" these.
    // Band 0 only: a flat, faintly cool-white ambient. 12,000 intensity
    // is correct for this irradiance setup; it does NOT blow out to white.
    val indirectLight = IndirectLight.Builder()
        .irradiance(1, floatArrayOf(0.65f, 0.65f, 0.68f))
        .intensity(12_000f)
        .build(engine)
    scene.indirectLight = indirectLight
}

private const val TAG = "VrmAvatarView"
