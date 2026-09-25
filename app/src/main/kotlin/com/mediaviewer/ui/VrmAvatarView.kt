package com.mediaviewer.ui

import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.mediaviewer.util.Quaternion
import com.mediaviewer.util.VrmData
import com.mediaviewer.util.VrmSpecVersion
import com.mediaviewer.util.multiplyColumnMajor4x4
import java.nio.ByteBuffer

/**
 * VRM pipeline step 5 (see VrmModeScreen.kt's doc comment / the handoff's
 * "VRM: what's next" section): renders whatever glTF/`.vrm` file
 * [vrmBytes] holds via Google's Filament, using `filament-utils-android`'s
 * `ModelViewer` — a convenience wrapper (from Filament's own
 * `android/samples/model-viewer` sample app, which is where this file's
 * shape comes from) that bundles the Engine/Renderer/Scene/View/Camera
 * boilerplate, a touch-drag orbit camera manipulator, and a `loadModelGlb`
 * call into gltfio's loader — exactly the "one `AndroidView`-wrapped
 * `SurfaceView`" the handoff describes for this step, without hand-rolling
 * Filament's lower-level API.
 *
 * ## Scope: this step renders the model and applies bone updates
 * [vrmBytes] is loaded and shown exactly as exported (T-pose or whatever
 * rest pose the avatar was authored in), with `transformToUnitCube()`
 * centering/scaling it into view and a drag gesture to orbit around it.
 * The frame callback also calls `animator?.updateBoneMatrices()` before
 * every render — without it, the `TransformManager.setTransform` calls
 * [AvatarRetargeter] makes never reach the skinned meshes (verified
 * against Filament 1.51.6's `ModelViewer.kt`: `render()` doesn't call it
 * itself), so the avatar would stay frozen in its rest pose no matter
 * what tracking reports. [parsedVrmData] is the same bytes already run
 * through [VrmParser] (step 4) off the main thread by the caller — this
 * view never parses on the UI thread itself. [onRetargetTargetReady]
 * hands back a [RetargetTarget] — the
 * node-index→entity bridge `AvatarRetargeter.kt` (step 6) needs to
 * actually drive this rendered model — built once right after a
 * successful load rather than resolved fresh every frame, since it's a
 * name lookup per node, not something to redo 60 times a second.
 *
 * ## No bundled lighting assets
 * A "proper" Filament scene usually lights itself via an image-based
 * light (IBL) baked into a `.ktx` cubemap file — but that's another
 * binary asset this sandbox has no network to fetch (same constraint as
 * the MediaPipe `.task` models — see FaceLandmarkerHelper's doc comment).
 * Instead, [addThreeLightRig] adds three plain directional lights (key/
 * fill/rim, a standard cheap lighting rig) — no bundled asset needed, at
 * the cost of a flatter look than a real IBL would give. Swapping in a
 * bundled `.ktx` IBL later is a self-contained follow-up to this function
 * alone, nothing else here depends on which lighting approach is used.
 *
 * ## Verified API surface
 * `ModelViewer`'s public surface (`engine`/`scene`/`loadModelGlb`/
 * `transformToUnitCube`/`destroyModel`/`render`/`asset`/`asset.root` — all
 * used below) was verified against the pinned `filament-utils-android`
 * 1.51.6 sources (ModelViewer.kt): `loadModelGlb(buffer)` calls
 * `destroyModel()` itself, then `assetLoader.createAsset(buffer)` +
 * `resourceLoader.asyncBeginLoad(asset)`, and `render()` drives
 * `asyncUpdateLoad()` per frame — which is why a direct (not heap)
 * `ByteBuffer` is required here: gltfio's native loader reads it via
 * `GetDirectBufferAddress`. `render()` does NOT call
 * `animator.updateBoneMatrices()` itself, hence the explicit call in the
 * frame callback.
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
    onRetargetTargetReady: (RetargetTarget?) -> Unit = {}
) {
    // Held outside the AndroidView factory so the LaunchedEffect below —
    // which reacts to vrmBytes changing — can reach the same ModelViewer
    // instance the factory created, without recreating the SurfaceView
    // (and therefore the whole Filament Engine/GL context) on every new
    // file pick.
    val viewerHolder = remember { arrayOfNulls<ModelViewer>(1) }
    // Which exact ByteArray instance is currently loaded in the viewer.
    // The AndroidView factory below loads vrmBytes on first composition;
    // LaunchedEffect(vrmBytes) also fires on first composition (after the
    // factory), so without this guard the model would load twice on every
    // cold entry. Reference equality is the right check: vrmBytes is only
    // ever reassigned when the file is actually re-read.
    val loadedBytesHolder = remember { arrayOfNulls<ByteArray>(1) }
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
            loadError = null
            onRetargetTargetReady(null)
            return@LaunchedEffect
        }
        // The factory already loaded these exact bytes during this
        // composition — don't load twice (see loadedBytesHolder).
        if (loadedBytesHolder[0] === vrmBytes) return@LaunchedEffect
        loadedBytesHolder[0] = vrmBytes
        loadError = loadVrmInto(viewer, vrmBytes, parsedVrmData)
        onRetargetTargetReady(buildRetargetTargetOrNull(viewer, parsedVrmData))
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            runCatching { Utils.init() }.onFailure { Log.e(TAG, "Filament Utils.init() failed", it) }
            val surfaceView = SurfaceView(ctx)
            val viewer = ModelViewer(surfaceView)
            surfaceView.setOnTouchListener(viewer) // drag-to-orbit, ModelViewer's own manipulator
            addThreeLightRig(viewer.engine, viewer.scene)
            // addFlatAmbientLight is intentionally NOT called: the 4-
            // directional rig alone provably rendered on-device (Sept 24
            // build), while the procedural IndirectLight added after that
            // correlates exactly with the black-screen regression — it
            // stays out until the model is visible again, then it can be
            // reintroduced carefully if shadowed areas need a lift.
            viewerHolder[0] = viewer
            if (vrmBytes != null) {
                loadedBytesHolder[0] = vrmBytes
                loadError = loadVrmInto(viewer, vrmBytes, parsedVrmData)
                onRetargetTargetReady(buildRetargetTargetOrNull(viewer, parsedVrmData))
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

/** Loads [bytes] into [viewer]; returns a human-readable error when the
 *  model can't be shown, null on success. The error is what VrmAvatarView
 *  draws on screen (see loadError) so a broken file is diagnosable. */
private fun loadVrmInto(viewer: ModelViewer, bytes: ByteArray, parsedVrmData: VrmData?): String? {
    // gltfio's native loader reads the buffer via GetDirectBufferAddress —
    // a heap ByteBuffer (ByteBuffer.wrap) gives it a null pointer and the
    // asset silently comes back empty. The official model-viewer sample
    // always copies into a direct, native-order buffer first; do the same.
    val direct = ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.nativeOrder())
    direct.put(bytes)
    direct.flip()
    val failure = runCatching {
        viewer.destroyModel()
        viewer.loadModelGlb(direct)
        viewer.transformToUnitCube()
        // fixVrm0Facing is intentionally NOT called: it was added after
        // the Sept 24 build that provably rendered on-device, and the
        // black-screen regression correlates with its introduction. The
        // model may face away from the camera without it — that's a
        // visible, diagnosable state, unlike a black screen. Reintroduce
        // only with a verified-correct rotation once the avatar is seen.
    }.exceptionOrNull()
    if (failure != null) {
        Log.e(TAG, "Filament failed to load VRM file as glTF", failure)
        return "Couldn't load that .vrm file (${failure::class.simpleName})"
    }
    if (viewer.asset == null) {
        Log.e(TAG, "Filament createAsset returned null for the VRM file")
        return "Couldn't parse that .vrm file (not valid glTF?)"
    }
    // A heap (non-direct) ByteBuffer used to make gltfio's native loader
    // silently produce an asset with zero entities — no exception, just
    // nothing to render. Guard here so "black screen, no error" becomes
    // an actual message instead.
    if (viewer.asset!!.entities.isEmpty()) {
        Log.e(TAG, "Filament loaded the VRM file but it contains no entities")
        return "That .vrm file loaded empty (no visible geometry?)"
    }
    return null
}

/**
 * VRM 0.x models were exported facing **+Z** — the opposite of standard
 * glTF's own -Z-forward convention (which VRM 1.0 corrected). This is a
 * well-known quirk of the VRM 0.x spec/UniVRM export pipeline, not a
 * guess: every VRM 0.x viewer has to apply this same 180°-about-Y
 * correction, or the avatar loads facing directly away from the camera.
 * Rotates [ModelViewer.asset]'s root entity by 180° about Y *in addition
 * to* whatever [ModelViewer.transformToUnitCube] already set (its own
 * scale + recenter, no rotation), rather than replacing it — order here
 * doesn't matter for a rotation about the vertical axis applied at the
 * already-recentered origin. VRM 1.0 files are untouched — they already
 * follow the standard -Z-forward convention [ModelViewer]/Filament expect.
 */
private fun fixVrm0Facing(viewer: ModelViewer) {
    val asset = viewer.asset ?: return
    val transformManager = viewer.engine.transformManager
    val instance = transformManager.getInstance(asset.root)
    if (instance == 0) return
    val current = FloatArray(16)
    transformManager.getTransform(instance, current)
    val flip180AboutY = Quaternion(0f, 1f, 0f, 0f).toColumnMajorMatrix()
    val fixed = multiplyColumnMajor4x4(flip180AboutY, current)
    // Never write a degenerate transform into the scene graph — a NaN or
    // all-zero matrix here would make the whole model vanish with no
    // error, which is worse than just leaving it facing the wrong way.
    if (fixed.any { !it.isFinite() }) {
        Log.e(TAG, "fixVrm0Facing produced a non-finite matrix — leaving the root transform alone")
        return
    }
    transformManager.setTransform(instance, fixed)
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
    // NOTE: IndirectLight intensity is a plain multiplier (1 = as-baked),
    // NOT lux — an earlier version passed 12_000 here, which would shove
    // every pixel to blown-out white. ~1.5 against the ~32k-lux key light
    // is a gentle fill: enough to keep shadowed surfaces readable, not
    // enough to wash the model out.
    val indirectLight = IndirectLight.Builder()
        .irradiance(1, floatArrayOf(0.9f, 0.9f, 0.95f)) // band 0 only: a flat, faintly cool-white ambient
        .intensity(1.5f)
        .build(engine)
    scene.indirectLight = indirectLight
}

private const val TAG = "VrmAvatarView"
