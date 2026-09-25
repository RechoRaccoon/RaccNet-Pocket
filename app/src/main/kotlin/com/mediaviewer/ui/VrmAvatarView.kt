package com.mediaviewer.ui

import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.google.android.filament.Skybox
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.mediaviewer.util.MToonMaterialParser
import com.mediaviewer.util.MToonTextureApplier
import com.mediaviewer.util.Quaternion
import com.mediaviewer.util.VrmData
import com.mediaviewer.util.VrmSpecVersion
import com.mediaviewer.util.multiplyColumnMajor4x4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the picked `.vrm` (a binary glTF) with Filament's `ModelViewer`.
 *
 * ## Lifecycle — why closing VRM mode used to crash
 * `ModelViewer` registers its own detach listener on the SurfaceView that
 * calls `destroy()`, which destroys the whole Filament Engine. Compose
 * removes (detaches) an AndroidView's View BEFORE it runs `onDispose`
 * callbacks, so the old `onDispose { viewer.destroyModel() }` ran against
 * an already-freed engine: a native crash no `runCatching` can catch. A
 * still-running background load (portrait took ages to load) could do the
 * same thing a moment later, and `buildTarget` read transforms from a
 * background thread while the main thread was rendering.
 *
 * Now: a [ViewerSession] is created per SurfaceView. We add OUR detach
 * listener before `ModelViewer` adds its own, so ours runs first — it stops
 * the frame callback, frees the few objects we created (skybox, lights),
 * and flips `released`. After that nothing in this file touches the engine:
 * every engine call goes through [ViewerSession.onMain], which checks the
 * flag on the main thread (the same thread detach happens on, so there's
 * no race). `ModelViewer` then tears the engine down exactly once.
 *
 * ## Threading
 * CPU-only work (copying the file into a direct buffer, parsing MToon
 * material JSON, decoding the rare textures gltfio can't see) runs on
 * Dispatchers.Default. Every Filament call runs on Main. Loading is a
 * LaunchedEffect keyed on the session + bytes, so it's cancelled when the
 * screen closes instead of living on in an unscoped CoroutineScope.
 *
 * ## Camera / gestures
 * The camera never moves. One-finger drag spins the MODEL around its own
 * vertical axis; pinch zooms. Zoom scales the model toward the camera
 * around a focus point near the upper chest, and that focus slides toward
 * the screen center as you zoom in — so zooming frames the face/upper body
 * instead of pushing it off the top of the screen. [DEFAULT_ZOOM] starts it
 * closer than the old whole-body framing.
 */
@Composable
fun VrmAvatarView(
    modifier: Modifier = Modifier,
    vrmBytes: ByteArray?,
    parsedVrmData: VrmData? = null,
    /** Flat background (the user's profile color), drawn as a Filament
     *  Skybox — a Compose background behind an opaque SurfaceView is never
     *  visible. */
    backgroundTint: Color = Color.Black,
    onRetargetTargetReady: (RetargetTarget?) -> Unit = {},
    onTexturesApplied: (Int) -> Unit = {}
) {
    var session by remember { mutableStateOf<ViewerSession?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var userYawDegrees by remember { mutableStateOf(0f) }
    var zoom by remember { mutableStateOf(DEFAULT_ZOOM) }
    val currentOnRetarget by rememberUpdatedState(onRetargetTargetReady)
    val currentOnTextures by rememberUpdatedState(onTexturesApplied)

    // Load (or unload) whenever the viewer or the file changes.
    LaunchedEffect(session, vrmBytes) {
        val s = session ?: return@LaunchedEffect
        // Retargeting must stop pointing at the old asset BEFORE it's
        // destroyed — otherwise the next recomposition's SideEffect writes
        // bone transforms into freed entities.
        currentOnRetarget(null)
        if (vrmBytes == null) {
            s.onMain { it.destroyModel(); s.baseTransform = null }
            loadError = null
            return@LaunchedEffect
        }
        loading = true
        userYawDegrees = 0f
        val result = loadVrm(s, vrmBytes, parsedVrmData)
        loading = false
        if (result == null) return@LaunchedEffect // released/cancelled mid-load
        loadError = result.error
        currentOnTextures(result.texturesApplied)
        // Pose the model (facing fix + zoom) now that the base transform exists.
        s.onMain { applyModelTransform(it, s, userYawDegrees, zoom) }
        currentOnRetarget(result.target)
    }

    // Cheap: one small matrix product + one setTransform on the root.
    LaunchedEffect(session, userYawDegrees, zoom) {
        val s = session ?: return@LaunchedEffect
        s.onMain { applyModelTransform(it, s, userYawDegrees, zoom) }
    }

    LaunchedEffect(session, backgroundTint) {
        val s = session ?: return@LaunchedEffect
        s.onMain { applyBackgroundColor(it, s, backgroundTint) }
    }

    // Only stops the frame loop; engine teardown is owned by the detach
    // listener (see the class doc). Never touches Filament here.
    DisposableEffect(Unit) {
        onDispose {
            session?.release()
            currentOnRetarget(null)
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                runCatching { Utils.init() }.onFailure { Log.e(TAG, "Filament Utils.init() failed", it) }
                val surfaceView = SurfaceView(ctx).apply {
                    holder.setFormat(android.graphics.PixelFormat.OPAQUE)
                }
                val newSession = ViewerSession()
                // MUST be added before ModelViewer(surfaceView) — listeners
                // fire in the order they were added, and ModelViewer's own
                // detach listener destroys the engine.
                surfaceView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) {
                        newSession.freeOwnedObjectsAndRelease()
                    }
                })
                val viewer = ModelViewer(surfaceView)
                // No setOnTouchListener(viewer): the camera stays put; the
                // gesture layer below moves the model instead.
                newSession.viewer = viewer
                newSession.lightEntities = addThreeLightRig(viewer.engine, viewer.scene)
                newSession.indirectLight = addFlatAmbientLight(viewer.engine, viewer.scene)
                applyBackgroundColor(viewer, newSession, backgroundTint)
                newSession.startFrameLoop()
                session = newSession
                surfaceView
            }
        )

        // Gesture layer ON TOP of the SurfaceView, so touches are handled
        // by Compose reliably rather than depending on AndroidView interop.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        userYawDegrees += pan.x * DRAG_DEGREES_PER_PX
                        if (gestureZoom != 1f) {
                            zoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        }
                    }
                }
        )

        if (loading) {
            Text(
                "Loading avatar…",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        loadError?.let { error ->
            Text(
                text = error,
                color = Color.Red.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }
}

private const val DRAG_DEGREES_PER_PX = 0.25f
private const val DEFAULT_ZOOM = 1.7f
private const val MIN_ZOOM = 0.6f
private const val MAX_ZOOM = 5f
/** Focus height in unit-cube space (model spans roughly -1..1): about
 *  the upper chest / neck. Zoom closes in on this point. */
private const val ZOOM_FOCUS_Y = 0.5f

/** Everything tied to one SurfaceView / Filament engine. All engine access
 *  goes through [onMain], which refuses once [released] is set. */
private class ViewerSession {
    var viewer: ModelViewer? = null
    var released = false
        private set
    var baseTransform: FloatArray? = null
    var baseYawDegrees = 0f
    var skybox: Skybox? = null
    var indirectLight: IndirectLight? = null
    var lightEntities: IntArray = IntArray(0)

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (released) return
            choreographer.postFrameCallback(this)
            val v = viewer ?: return
            runCatching {
                // render() doesn't push bone transforms to skinned meshes
                // itself; without this the avatar stays frozen in rest pose.
                v.animator?.updateBoneMatrices()
                v.render(frameTimeNanos)
            }.onFailure { Log.e(TAG, "Filament render() failed", it) }
        }
    }

    fun startFrameLoop() = choreographer.postFrameCallback(frameCallback)

    /** Main thread only. Stops rendering; idempotent. */
    fun release() {
        if (released) return
        released = true
        choreographer.removeFrameCallback(frameCallback)
    }

    /** Called from our detach listener, i.e. just BEFORE ModelViewer
     *  destroys the engine — the last moment our objects can be freed. */
    fun freeOwnedObjectsAndRelease() {
        val v = viewer
        release()
        if (v == null) return
        runCatching {
            val engine = v.engine
            skybox?.let { v.scene.skybox = null; engine.destroySkybox(it) }
            indirectLight?.let { v.scene.indirectLight = null; engine.destroyIndirectLight(it) }
            for (e in lightEntities) {
                v.scene.removeEntity(e)
                engine.destroyEntity(e)
                EntityManager.get().destroy(e)
            }
        }.onFailure { Log.e(TAG, "Freeing VRM scene objects failed", it) }
        skybox = null
        indirectLight = null
        lightEntities = IntArray(0)
        viewer = null
    }

    /** Runs [block] on the main thread only if the engine is still alive.
     *  Returns null if the session was released. */
    suspend fun <T> onMain(block: (ModelViewer) -> T): T? = withContext(Dispatchers.Main) {
        val v = viewer
        if (released || v == null) null else block(v)
    }
}

private class VrmLoadResult(
    val error: String?,
    val texturesApplied: Int,
    val target: RetargetTarget?
)

/** Returns null if the session was released (screen closed) mid-load. */
private suspend fun loadVrm(session: ViewerSession, bytes: ByteArray, parsedVrmData: VrmData?): VrmLoadResult? {
    // ── CPU-only prep, off the main thread ──
    val prepared = withContext(Dispatchers.Default) {
        val direct = ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.nativeOrder())
        direct.put(bytes)
        direct.flip()
        val mtoon = MToonMaterialParser.parse(bytes)
        val decoded = if (mtoon != null) {
            runCatching { MToonTextureApplier.decodeTextures(mtoon) }
                .onFailure { Log.e(TAG, "Texture decode pass failed", it) }
                .getOrDefault(emptyMap())
        } else emptyMap()
        Triple(direct, mtoon, decoded)
    }
    val (direct, mtoon, decoded) = prepared

    // ── Filament work, main thread, only if the engine is still alive ──
    return session.onMain { viewer ->
        var texturesApplied = 0
        val failure = runCatching {
            viewer.destroyModel()
            session.baseTransform = null
            viewer.loadModelGlb(direct)
            val asset = viewer.asset
            if (asset != null && mtoon != null) {
                texturesApplied = MToonTextureApplier.bindTextures(viewer.engine, asset, mtoon, decoded)
            }
            viewer.transformToUnitCube()
        }.exceptionOrNull()
        val asset = viewer.asset
        when {
            failure != null -> {
                Log.e(TAG, "Filament failed to load VRM file as glTF", failure)
                VrmLoadResult("Couldn't load that .vrm file (${failure::class.simpleName})", texturesApplied, null)
            }
            asset == null -> VrmLoadResult("Couldn't parse that .vrm file (not valid glTF?)", texturesApplied, null)
            asset.entities.isEmpty() -> VrmLoadResult("That .vrm file loaded empty (no visible geometry?)", texturesApplied, null)
            else -> {
                session.baseTransform = captureRootTransform(viewer)
                // VRM 0.x models face +Z (backwards for a glTF viewer).
                session.baseYawDegrees = if (parsedVrmData?.specVersion == VrmSpecVersion.VRM_0) 180f else 0f
                // Built HERE, on the main thread, before the facing/zoom
                // transform is applied — same rest-pose capture the head
                // tracking was tuned against. (It used to run on a
                // background thread while the main thread was rendering.)
                val target = if (parsedVrmData != null) {
                    runCatching { AvatarRetargeter.buildTarget(viewer.engine, asset, parsedVrmData) }
                        .onFailure { Log.e(TAG, "Could not build retarget target", it) }
                        .getOrNull()
                } else null
                VrmLoadResult(null, texturesApplied, target)
            }
        }
    }
}

/** Where transformToUnitCube() (default args) centers the model — also
 *  the camera's look-at target in filament-utils' ModelViewer. */
private val UNIT_CUBE_CENTER = floatArrayOf(0f, 0f, -4f)

private fun captureRootTransform(viewer: ModelViewer): FloatArray? {
    val asset = viewer.asset ?: return null
    val tm = viewer.engine.transformManager
    val instance = tm.getInstance(asset.root)
    if (instance == 0) return null
    return FloatArray(16).also { tm.getTransform(instance, it) }
}

/**
 * root = Zoom * Yaw * base, always rebuilt from the load-time [base] so
 * repeated calls never accumulate.
 *
 *  - Yaw spins the model about the vertical axis through its own center.
 *  - Zoom scales by `s` about a focus point F (upper chest), then moves F
 *    to `C + (F - C) / s` — at s = 1 nothing moves; as s grows, F slides
 *    toward screen center C, so zooming in frames the upper body.
 *    Combined: p' = s * (p - F) + C + (F - C) / s.
 */
private fun applyModelTransform(viewer: ModelViewer, session: ViewerSession, userYawDegrees: Float, zoom: Float) {
    val base = session.baseTransform ?: return
    val asset = viewer.asset ?: return
    val tm = viewer.engine.transformManager
    val instance = tm.getInstance(asset.root)
    if (instance == 0) return

    val cx = UNIT_CUBE_CENTER[0]; val cy = UNIT_CUBE_CENTER[1]; val cz = UNIT_CUBE_CENTER[2]
    val half = Math.toRadians((session.baseYawDegrees + userYawDegrees).toDouble()) / 2.0
    val yaw = Quaternion(0f, sin(half).toFloat(), 0f, cos(half).toFloat()).toColumnMajorMatrix()

    var m = multiplyColumnMajor4x4(translationMatrix(-cx, -cy, -cz), base)
    m = multiplyColumnMajor4x4(yaw, m)
    m = multiplyColumnMajor4x4(translationMatrix(cx, cy, cz), m)

    val s = zoom
    val fx = cx; val fy = cy + ZOOM_FOCUS_Y; val fz = cz
    m = multiplyColumnMajor4x4(translationMatrix(-fx, -fy, -fz), m)
    m = multiplyColumnMajor4x4(scaleMatrix(s), m)
    m = multiplyColumnMajor4x4(
        translationMatrix(cx + (fx - cx) / s, cy + (fy - cy) / s, cz + (fz - cz) / s), m
    )
    runCatching { tm.setTransform(instance, m) }
        .onFailure { Log.e(TAG, "setTransform failed while posing model", it) }
}

private fun translationMatrix(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    x, y, z, 1f
)

private fun scaleMatrix(s: Float): FloatArray = floatArrayOf(
    s, 0f, 0f, 0f,
    0f, s, 0f, 0f,
    0f, 0f, s, 0f,
    0f, 0f, 0f, 1f
)

/** Solid-color skybox = flat background. Destroys the previous one. */
private fun applyBackgroundColor(viewer: ModelViewer, session: ViewerSession, tint: Color) {
    runCatching {
        val skybox = Skybox.Builder()
            .color(tint.red, tint.green, tint.blue, 1f)
            .build(viewer.engine)
        val old = session.skybox
        viewer.scene.skybox = skybox
        session.skybox = skybox
        if (old != null) viewer.engine.destroySkybox(old)
    }.onFailure { Log.e(TAG, "Could not set Filament background skybox", it) }
}

/** A plain three-point directional-light rig — see this file's top doc
 *  comment for why there's no image-based light here. Angles/intensities
 *  are a starting guess (a portrait-lighting key/fill/rim split), not
 *  measured against a real avatar on a real device; adjust to taste once
 *  step 5 is actually visible to look at. */
private fun addThreeLightRig(engine: Engine, scene: com.google.android.filament.Scene): IntArray {
    val entityManager = EntityManager.get()
    val created = ArrayList<Int>(4)

    fun directionalLight(x: Float, y: Float, z: Float, intensityLux: Float) {
        val entity = entityManager.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(intensityLux)
            .direction(x, y, z)
            .castShadows(false)
            .build(engine, entity)
        scene.addEntity(entity)
        created.add(entity)
    }

    directionalLight(-0.5f, -1.0f, -0.3f, 32_000f)  // key: front-upper-left, brightest — kept well below clipping so saturated albedos (reds) don't blow out
    directionalLight(0.6f, -0.2f, -0.4f, 14_000f)   // fill: front-right, softer, keeps the key's shadow side readable
    directionalLight(0.0f, 0.3f, 1.0f, 10_000f)    // rim: from behind, separates the avatar's silhouette from the background
    directionalLight(0.0f, -0.1f, -1.0f, 9_000f)    // frontal lift: dim head-on light so unlit faces fall to dark grey, not pure black
    return created.toIntArray()
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
private fun addFlatAmbientLight(engine: Engine, scene: com.google.android.filament.Scene): IndirectLight {
    // EXACT values from the old working build — do not "fix" these.
    // Band 0 only: a flat, faintly cool-white ambient. 12,000 intensity
    // is correct for this irradiance setup; it does NOT blow out to white.
    val indirectLight = IndirectLight.Builder()
        .irradiance(1, floatArrayOf(0.65f, 0.65f, 0.68f))
        .intensity(12_000f)
        .build(engine)
    scene.indirectLight = indirectLight
    return indirectLight
}

private const val TAG = "VrmAvatarView"
