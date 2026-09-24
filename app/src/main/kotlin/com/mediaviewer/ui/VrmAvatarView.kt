package com.mediaviewer.ui

import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.mediaviewer.util.VrmData
import com.mediaviewer.util.VrmParser
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
 * what tracking reports. [onParsedVrmData] separately runs the same
 * bytes through [VrmParser] (step 4) and hands back the bone/expression
 * maps. [onRetargetTargetReady] hands back a [RetargetTarget] — the
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
 * ## Unverified
 * Same caveat as the rest of this pipeline, but with a specific note:
 * `ModelViewer`'s exact public surface (`engine`/`scene`/`loadModelGlb`/
 * `transformToUnitCube`/`destroyModel`/`render` — all used below) is
 * reconstructed from the well-known Filament Android sample app's shape,
 * not verified against the pinned `filament-utils-android` AAR version in
 * `build.gradle.kts`. If a name has moved, Android Studio's compile error
 * will point at exactly which call in this file needs updating — the
 * teardown path in [VrmAvatarView]'s `onDispose` is the single piece I'm
 * least sure of (see its own comment) and worth double-checking first if
 * repeatedly entering/leaving VRM mode ends up leaking native memory.
 */
@Composable
fun VrmAvatarView(
    modifier: Modifier = Modifier,
    vrmBytes: ByteArray?,
    onParsedVrmData: (VrmData?) -> Unit = {},
    onRetargetTargetReady: (RetargetTarget?) -> Unit = {}
) {
    // Held outside the AndroidView factory so the LaunchedEffect below —
    // which reacts to vrmBytes changing — can reach the same ModelViewer
    // instance the factory created, without recreating the SurfaceView
    // (and therefore the whole Filament Engine/GL context) on every new
    // file pick.
    val viewerHolder = remember { arrayOfNulls<ModelViewer>(1) }

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
            // Best-effort teardown — this is the one part of this file
            // reconstructed with the least confidence (see this file's
            // top doc comment). `destroyModel()` + `Engine.destroy()` is
            // the shape the official sample's Activity.onDestroy uses;
            // worth confirming this actually releases the native GL
            // context and doesn't leak if a person repeatedly opens and
            // closes VRM mode in one app session.
            runCatching {
                val viewer = viewerHolder[0] ?: return@runCatching
                viewer.destroyModel()
                viewer.engine.destroy()
            }.onFailure { Log.e(TAG, "Filament teardown failed", it) }
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
            onParsedVrmData(null)
            onRetargetTargetReady(null)
            return@LaunchedEffect
        }
        val parsedVrmData = runCatching { VrmParser.parse(vrmBytes) }.getOrNull()
        loadVrmInto(viewer, vrmBytes)
        onParsedVrmData(parsedVrmData)
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
            viewerHolder[0] = viewer
            if (vrmBytes != null) {
                val parsedVrmData = runCatching { VrmParser.parse(vrmBytes) }.getOrNull()
                loadVrmInto(viewer, vrmBytes)
                onParsedVrmData(parsedVrmData)
                onRetargetTargetReady(buildRetargetTargetOrNull(viewer, parsedVrmData))
            }
            surfaceView
        }
    )
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

private fun loadVrmInto(viewer: ModelViewer, bytes: ByteArray) {
    runCatching {
        viewer.destroyModel()
        viewer.loadModelGlb(ByteBuffer.wrap(bytes))
        viewer.transformToUnitCube()
    }.onFailure { Log.e(TAG, "Filament failed to load VRM file as glTF", it) }
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

private const val TAG = "VrmAvatarView"
