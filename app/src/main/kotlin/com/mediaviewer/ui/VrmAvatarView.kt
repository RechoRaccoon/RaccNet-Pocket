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
import androidx.compose.runtime.SideEffect
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
import com.google.android.filament.ColorGrading
import com.google.android.filament.ToneMapper
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.mediaviewer.util.MToonMaterialParser
import com.mediaviewer.util.MToonTextureApplier
import com.mediaviewer.util.Quaternion
import com.mediaviewer.util.VrmData
import com.mediaviewer.util.VrmGlbPatcher
import com.mediaviewer.util.VrmSpecVersion
import com.mediaviewer.util.multiplyColumnMajor4x4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

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
 *
 * ## Follow mode (video-call / filter framing)
 * With [followTracking] on and a [framing] from face tracking, the model's
 * root is moved and scaled every frame so the avatar's eyes land exactly
 * where the user's eyes are in the (mirrored, fill-cropped) camera frame,
 * at the same eye spacing — see [solveFollow]. Pinch then scales relative
 * to that and drag still spins. Without a face it holds the last
 * placement; with follow off it's the old centered framing.
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
    onTexturesApplied: (Int) -> Unit = {},
    /** What [VrmGlbPatcher] changed on load, for the debug overlay. */
    onMaterialsPatched: (String) -> Unit = {},
    /** Latest face placement from tracking, or null if none seen yet. */
    framing: AvatarFraming? = null,
    followTracking: Boolean = true,
    /** The avatar's separate meshes (clothes, hair, accessories …), reported
     *  once per load for the settings sheet's part toggles. */
    onPartsReady: (List<AvatarPart>) -> Unit = {},
    /** [AvatarPart.id]s to hide. */
    hiddenParts: Set<String> = emptySet()
) {
    var session by remember { mutableStateOf<ViewerSession?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var userYawDegrees by remember { mutableStateOf(0f) }
    var zoom by remember { mutableStateOf(DEFAULT_ZOOM) }
    val currentOnRetarget by rememberUpdatedState(onRetargetTargetReady)
    val currentOnTextures by rememberUpdatedState(onTexturesApplied)
    val currentOnPatched by rememberUpdatedState(onMaterialsPatched)
    val currentOnParts by rememberUpdatedState(onPartsReady)
    // Bumped per load so hidden parts are re-applied to a fresh model.
    var loadGeneration by remember { mutableStateOf(0) }

    // Load (or unload) whenever the viewer or the file changes.
    LaunchedEffect(session, vrmBytes) {
        val s = session ?: return@LaunchedEffect
        // Retargeting must stop pointing at the old asset BEFORE it's
        // destroyed — otherwise the next recomposition's SideEffect writes
        // bone transforms into freed entities.
        currentOnRetarget(null)
        currentOnParts(emptyList())
        if (vrmBytes == null) {
            s.onMain { it.destroyModel(); s.freeModelResources(it.engine); s.baseTransform = null; s.anchor = null }
            loadError = null
            return@LaunchedEffect
        }
        loading = true
        userYawDegrees = 0f
        val result = loadVrm(s, vrmBytes, parsedVrmData)
        loading = false
        if (result == null) return@LaunchedEffect // released/cancelled mid-load
        loadError = result.error
        currentOnTextures(if (result.error == null) -1 else 0) // "loading…" until streamed in
        currentOnPatched(result.patchSummary)
        currentOnParts(result.parts)
        loadGeneration++
        // The frame loop places the root from here on
        // (updateRootTransform); start without easing.
        s.snapFraming = true
        currentOnRetarget(result.target)
        if (result.error == null) {
            streamTextures(s, vrmBytes, result) { currentOnTextures(it) }
                ?.let { currentOnTextures(it) }
        }
    }

    // Plain field writes on the main thread; the frame loop reads them.
    SideEffect {
        session?.let { s ->
            s.userYawDegrees = userYawDegrees
            s.zoom = zoom
            s.framing = framing
            s.followTracking = followTracking
        }
    }

    LaunchedEffect(session, hiddenParts, loadGeneration) {
        val s = session ?: return@LaunchedEffect
        s.onMain { s.applyHiddenParts(it.engine, hiddenParts) }
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
                newSession.colorGrading = applyLinearToneMapping(viewer)
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
    var colorGrading: ColorGrading? = null
    var lightEntities: IntArray = IntArray(0)

    // Per-model resources we own (freed right after destroyModel()).
    var ownedTextures: List<com.google.android.filament.Texture> = emptyList()
    var parts: List<AvatarPart> = emptyList()
    /** Part id -> its original material instance, while hidden. */
    private val hiddenOriginals = HashMap<String, com.google.android.filament.MaterialInstance>()
    /** Part id -> the invisible stand-in material instance we made. */
    private val hiddenStandIns = HashMap<String, com.google.android.filament.MaterialInstance>()

    /**
     * Hides/shows parts by swapping a primitive's material instance for a
     * duplicate that writes neither color nor depth — the primitive keeps
     * skinning/morphing but draws nothing. Per primitive, so a VRoid body
     * mesh whose clothes are extra primitives can still be toggled piece
     * by piece. Main thread only.
     */
    fun applyHiddenParts(engine: Engine, hidden: Set<String>) {
        val rm = engine.renderableManager
        for (part in parts) {
            val ri = rm.getInstance(part.entity)
            if (ri == 0) continue
            val isHidden = part.id in hiddenOriginals
            val wantHidden = part.id in hidden
            if (wantHidden == isHidden) continue
            runCatching {
                if (wantHidden) {
                    val original = rm.getMaterialInstanceAt(ri, part.primitiveIndex)
                    val standIn = hiddenStandIns.getOrPut(part.id) {
                        com.google.android.filament.MaterialInstance.duplicate(original, "hidden-${part.id}").apply {
                            setColorWrite(false)
                            setDepthWrite(false)
                        }
                    }
                    rm.setMaterialInstanceAt(ri, part.primitiveIndex, standIn)
                    hiddenOriginals[part.id] = original
                } else {
                    hiddenOriginals.remove(part.id)?.let { rm.setMaterialInstanceAt(ri, part.primitiveIndex, it) }
                }
            }.onFailure { Log.e(TAG, "Toggling part ${part.label} failed", it) }
        }
    }

    /** Frees what we created for the current model. Call right AFTER
     *  destroyModel() (nothing may still reference these). */
    fun freeModelResources(engine: Engine) {
        runCatching {
            hiddenStandIns.values.forEach { engine.destroyMaterialInstance(it) }
            ownedTextures.forEach { engine.destroyTexture(it) }
        }.onFailure { Log.e(TAG, "Freeing model resources failed", it) }
        hiddenStandIns.clear()
        hiddenOriginals.clear()
        ownedTextures = emptyList()
        parts = emptyList()
    }

    // Written by the composable (SideEffect), read by the frame loop.
    var userYawDegrees = 0f
    var zoom = DEFAULT_ZOOM
    var framing: AvatarFraming? = null
    var followTracking = true
    /** Bones the follow framing anchors on; set when a model loads. */
    var anchor: FramingAnchor? = null
    /** A crash breadcrumb to clear once a few frames have rendered fine
     *  (Filament's GPU work runs a frame or two behind render()). */
    var clearBreadcrumbAfterFrame = false
        set(value) { field = value; if (value) breadcrumbFrames = 3 }
    private var breadcrumbFrames = 0
    /** Jump straight to the target placement next frame (fresh load). */
    var snapFraming = true

    // Root placement actually applied last frame — see updateRootTransform.
    val appliedT = FloatArray(3)
    var appliedS = DEFAULT_ZOOM
    var appliedYawDegrees = 0f
    private var lastFrameNanos = 0L

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (released) return
            choreographer.postFrameCallback(this)
            val v = viewer ?: return
            val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.25f)
            lastFrameNanos = frameTimeNanos
            runCatching { updateRootTransform(v, dt) }
                .onFailure { Log.e(TAG, "Placing the model failed", it) }
            runCatching {
                // render() doesn't push bone transforms to skinned meshes
                // itself; without this the avatar stays frozen in rest pose.
                v.animator?.updateBoneMatrices()
                v.render(frameTimeNanos)
                if (clearBreadcrumbAfterFrame && --breadcrumbFrames <= 0) {
                    clearBreadcrumbAfterFrame = false
                    com.mediaviewer.util.CrashBreadcrumbs.clearMark()
                }
            }.onFailure { Log.e(TAG, "Filament render() failed", it) }
        }
    }

    fun startFrameLoop() = choreographer.postFrameCallback(frameCallback)

    /** Main thread only. Stops rendering; idempotent. */
    fun release() {
        if (released) return
        released = true
        choreographer.removeFrameCallback(frameCallback)
        // Leaving the screen normally isn't a crash.
        if (clearBreadcrumbAfterFrame) {
            clearBreadcrumbAfterFrame = false
            com.mediaviewer.util.CrashBreadcrumbs.clearMark()
        }
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
            colorGrading?.let { v.view.colorGrading = null; engine.destroyColorGrading(it) }
            for (e in lightEntities) {
                v.scene.removeEntity(e)
                engine.destroyEntity(e)
                EntityManager.get().destroy(e)
            }
        }.onFailure { Log.e(TAG, "Freeing VRM scene objects failed", it) }
        skybox = null
        indirectLight = null
        colorGrading = null
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
    val target: RetargetTarget?,
    val patchSummary: String = "",
    val parts: List<AvatarPart> = emptyList(),
    val materials: com.mediaviewer.util.MToonMaterialParser.ParseResult? = null,
    val primitives: List<MToonTextureApplier.ResolvedPrimitive> = emptyList()
)

/**
 * Textures the freshly loaded model one texture at a time: decode (off the
 * main thread) → upload + bind (main) → drop → next. Only one decoded
 * texture is ever alive, which is what keeps the Java heap from filling
 * (see MToonTextureApplier's doc). The model is already on screen while
 * this runs; untextured parts show their flat base color for a moment.
 * Returns how many materials got textured, or null if the session/model
 * went away mid-way.
 */
/** [streamTextures] result: texturing skipped because it crashed last run. */
const val SKIPPED_AFTER_CRASH = -2

private suspend fun streamTextures(
    session: ViewerSession,
    bytes: ByteArray,
    result: VrmLoadResult,
    onProgress: (Int) -> Unit
): Int? {
    val parse = result.materials ?: return 0
    if (result.primitives.isEmpty()) return 0
    // The previous run died while texturing: show the avatar untextured
    // this once rather than crash again (the crash screen has the details).
    if (com.mediaviewer.util.CrashBreadcrumbs.skipVrmTextures) return SKIPPED_AFTER_CRASH
    var textured = 0
    val mode = com.mediaviewer.util.CrashBreadcrumbs.vrmTextureMode
    val needed = MToonTextureApplier.neededTextures(parse)
    for ((n, texIndex) in needed.withIndex()) {
        val decoded = withContext(Dispatchers.Default) { MToonTextureApplier.decodeOne(bytes, parse, texIndex, mode) } ?: continue
        val step = "${com.mediaviewer.util.CrashBreadcrumbs.VRM_TEXTURE_STEP} ${n + 1}/${needed.size} " +
            "(glTF texture $texIndex, ${decoded.width}x${decoded.height}, safe mode $mode)"
        val bound = session.onMain { viewer ->
            if (viewer.asset == null) return@onMain null
            com.mediaviewer.util.CrashBreadcrumbs.around(step) {
                MToonTextureApplier.uploadAndBind(viewer.engine, result.primitives, parse, texIndex, decoded, mode) { detail ->
                    com.mediaviewer.util.CrashBreadcrumbs.mark("$step\n  at: $detail")
                }
            }
                ?.also { (texture, _) ->
                    session.ownedTextures = session.ownedTextures + texture
                    // Keep the note until a frame has actually been drawn
                    // with this texture — a bad texture can also die in render().
                    com.mediaviewer.util.CrashBreadcrumbs.mark("$step — first frame drawn with it")
                    session.clearBreadcrumbAfterFrame = true
                }
                ?: (null to 0)
        } ?: return null
        textured += bound.second
        onProgress(textured)
    }
    return textured
}

/** One separately toggleable piece of the avatar: a mesh primitive. */
class AvatarPart(
    val id: String,
    val label: String,
    internal val entity: Int,
    internal val primitiveIndex: Int
)

/** "Hair", or "Body · Tops" when a mesh has several materials. Unity/VRoid
 *  material names carry noise like "N00_001_01_Tops_01_CLOTH (Instance)". */
private fun partLabel(ref: com.mediaviewer.util.MToonMaterialParser.PrimitiveMaterialRef): String {
    val node = ref.nodeName.replace('_', ' ').trim()
    if (ref.primitiveCount <= 1) return node
    val material = ref.materialName
        .replace(Regex("\\s*\\(Instance\\)"), "")
        .replace(Regex("^N\\d+_\\d+_\\d+_"), "")
        .replace(Regex("_\\d+_[A-Z]+$"), "")
        .replace('_', ' ').trim()
        .ifEmpty { "part ${ref.primitiveIndex + 1}" }
    return "$node · $material"
}

/** Returns null if the session was released (screen closed) mid-load. */
private suspend fun loadVrm(session: ViewerSession, bytes: ByteArray, parsedVrmData: VrmData?): VrmLoadResult? {
    var patchSummary = ""
    // ── CPU-only prep, off the main thread ──
    val prepared = withContext(Dispatchers.Default) {
        // The glTF JSON is rewritten so gltfio renders VRM materials the way
        // VRM viewers do — without this the avatar is a black silhouette
        // (vertex-colour masks, default metallic = 1, MToon). See VrmGlbPatcher.
        val patched = VrmGlbPatcher.patchToDirectBuffer(bytes)
        patchSummary = patched.stats.toString()
        patched.buffer to MToonMaterialParser.parse(bytes)
    }
    // Held only until loadModelGlb has copied it into native memory.
    var direct: java.nio.ByteBuffer? = prepared.first
    val mtoon = prepared.second

    // ── Filament work, main thread, only if the engine is still alive ──
    return session.onMain { viewer ->
        var parts = emptyList<AvatarPart>()
        var primitives = emptyList<MToonTextureApplier.ResolvedPrimitive>()
        val failure = runCatching {
            viewer.destroyModel()
            session.freeModelResources(viewer.engine)
            session.baseTransform = null
            com.mediaviewer.util.CrashBreadcrumbs.around("VRM model load (Filament loadModelGlb)") {
                viewer.loadModelGlb(direct!!)
            }
            direct = null // Filament has its own copy now; let this ~file-sized buffer go
            val asset = viewer.asset
            if (asset != null && mtoon != null) {
                primitives = MToonTextureApplier.resolvePrimitives(viewer.engine, asset, mtoon.primitiveMaterials)
                parts = primitives.map { p ->
                    AvatarPart("${p.ref.nodeIndex}:${p.ref.primitiveIndex}", partLabel(p.ref), p.entity, p.ref.primitiveIndex)
                }
                session.parts = parts
            }
            viewer.transformToUnitCube()
        }.exceptionOrNull()
        val asset = viewer.asset
        when {
            failure != null -> {
                Log.e(TAG, "Filament failed to load VRM file as glTF", failure)
                VrmLoadResult("Couldn't load that .vrm file (${failure::class.simpleName})", 0, null, patchSummary)
            }
            asset == null -> VrmLoadResult("Couldn't parse that .vrm file (not valid glTF?)", 0, null, patchSummary)
            asset.entities.isEmpty() -> VrmLoadResult("That .vrm file loaded empty (no visible geometry?)", 0, null, patchSummary)
            else -> {
                session.baseTransform = captureRootTransform(viewer)
                // Built HERE, on the main thread, while the root still holds
                // only the unit-cube fit — so every rest pose is captured in
                // the "model space" the retargeter solves in.
                val target = if (parsedVrmData != null) {
                    runCatching { AvatarRetargeter.buildTarget(viewer.engine, asset, parsedVrmData) }
                        .onFailure { Log.e(TAG, "Could not build retarget target", it) }
                        .getOrNull()
                } else null
                // Turn the model to face the camera. The skeleton says which
                // way it faces; the spec version is only a fallback.
                val facesNegativeZ = target?.facesNegativeZ ?: (parsedVrmData?.specVersion == VrmSpecVersion.VRM_0)
                session.baseYawDegrees = if (facesNegativeZ) 180f else 0f
                session.anchor = target?.let { FramingAnchor.from(it) }
                VrmLoadResult(null, 0, target, patchSummary, parts, mtoon, primitives)
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

/** Where the user's eyes are in the upright tracking frame. [anchorX] and
 *  [anchorY] are 0..1 of the frame, ALREADY mirrored the same way as the
 *  tracking preview box; [eyeDistancePx] is the spacing between the two
 *  eye centres in frame pixels. */
class AvatarFraming(
    val anchorX: Float,
    val anchorY: Float,
    val eyeDistancePx: Float,
    val frameWidth: Int,
    val frameHeight: Int
)

/** Which avatar bones stand in for the user's eyes when following. */
class FramingAnchor(
    val leftEye: Int?,
    val rightEye: Int?,
    val head: Int?,
    /** Model-space eye spacing to assume for rigs without eye bones. */
    val fallbackEyeDistance: Float
) {
    companion object {
        fun from(target: RetargetTarget): FramingAnchor {
            val b = target.bones
            val head = b["head"]
            val footY = listOfNotNull(b["leftFoot"], b["rightFoot"]).minOfOrNull { it.restWorldPosition[1] }
            val eyeHeight = if (head != null && footY != null) head.restWorldPosition[1] - footY else 0f
            return FramingAnchor(
                leftEye = b["leftEye"]?.entity,
                rightEye = b["rightEye"]?.entity,
                head = head?.entity,
                fallbackEyeDistance = if (eyeHeight > 1e-3f) eyeHeight * 0.042f else 0.06f
            )
        }
    }
}

/** Rigs without eye bones anchor on the head bone, which sits roughly this
 *  many eye-spacings below the eye line. */
private const val HEAD_BONE_BELOW_EYES = 0.8f
private const val FOLLOW_TAU_SECONDS = 0.08f
private const val MIN_ROOT_SCALE = 0.15f
private const val MAX_ROOT_SCALE = 25f

/**
 * Applies the model's root transform, every frame:
 *
 *     root = T(C + t) · S(s) · Yaw · T(-C) · base
 *
 * C is the unit-cube centre, `base` the load-time unit-cube fit, Yaw the
 * facing fix + the user's drag. Centered mode uses t = (0, F·(1/s − s), 0),
 * s = zoom — algebraically the same "zoom toward the upper chest" framing
 * as before. Follow mode gets t and s from [solveFollow]. Either way t/s
 * ease towards their target, so tracking at ~15 Hz still moves smoothly at
 * the display's frame rate.
 */
private fun ViewerSession.updateRootTransform(viewer: ModelViewer, dt: Float) {
    val base = baseTransform ?: return
    val asset = viewer.asset ?: return
    val tm = viewer.engine.transformManager
    val instance = tm.getInstance(asset.root)
    if (instance == 0) return
    val yaw = baseYawDegrees + userYawDegrees

    var targetS = zoom
    var targetT = floatArrayOf(0f, ZOOM_FOCUS_Y * (1f / zoom - zoom), 0f)
    if (followTracking) {
        solveFollow(viewer, yaw)?.let { (t, s) -> targetT = t; targetS = s }
    }

    if (snapFraming) {
        targetT.copyInto(appliedT)
        appliedS = targetS
        snapFraming = false
    } else {
        val a = 1f - exp(-dt / FOLLOW_TAU_SECONDS)
        for (i in 0 until 3) appliedT[i] += (targetT[i] - appliedT[i]) * a
        // Ease scale in log space so growing and shrinking feel the same.
        appliedS = exp(kotlin.math.ln(appliedS) + (kotlin.math.ln(targetS) - kotlin.math.ln(appliedS)) * a)
    }
    appliedYawDegrees = yaw

    val c = UNIT_CUBE_CENTER
    val half = Math.toRadians(yaw.toDouble()) / 2.0
    val yawMatrix = Quaternion(0f, sin(half).toFloat(), 0f, cos(half).toFloat()).toColumnMajorMatrix()
    var m = multiplyColumnMajor4x4(translationMatrix(-c[0], -c[1], -c[2]), base)
    m = multiplyColumnMajor4x4(yawMatrix, m)
    m = multiplyColumnMajor4x4(scaleMatrix(appliedS), m)
    m = multiplyColumnMajor4x4(translationMatrix(c[0] + appliedT[0], c[1] + appliedT[1], c[2] + appliedT[2]), m)
    tm.setTransform(instance, m)
}

/**
 * Follow mode: the root translation/scale that puts the avatar's eyes on
 * the user's eyes as they appear full-screen.
 *
 * The camera frame is mapped to the screen like a video call — scaled to
 * cover it and centre-cropped — and mirrored (the framing's x already is).
 * Scale: the avatar's eye spacing, projected at its depth, must equal the
 * user's eye spacing on screen. Translation: moves the eye point sideways
 * in camera space (no depth change) until it projects onto the user's eye
 * point. Both come straight from Filament's own camera matrices, so they
 * stay right whatever FOV/viewport ModelViewer picked.
 */
private fun ViewerSession.solveFollow(viewer: ModelViewer, yawDegrees: Float): Pair<FloatArray, Float>? {
    val f = framing ?: return null
    val a = anchor ?: return null
    if (f.frameWidth <= 0 || f.frameHeight <= 0 || f.eyeDistancePx <= 0f) return null
    val tm = viewer.engine.transformManager
    val scratch = FloatArray(16)
    fun modelPos(entity: Int?): FloatArray? {
        if (entity == null) return null
        val instance = tm.getInstance(entity)
        if (instance == 0) return null
        tm.getWorldTransform(instance, scratch)
        return worldToModel(scratch[12], scratch[13], scratch[14])
    }
    val leftEye = modelPos(a.leftEye)
    val rightEye = modelPos(a.rightEye)
    val eyeSpacing = if (leftEye != null && rightEye != null) distance(leftEye, rightEye) else 0f
    val usingEyes = eyeSpacing > 1e-5f
    val anchorModel = if (usingEyes) midpoint(leftEye!!, rightEye!!) else modelPos(a.head) ?: return null
    val avatarEyeDistance = if (usingEyes) eyeSpacing else a.fallbackEyeDistance

    val viewport = viewer.view.viewport
    val w = viewport.width.toFloat()
    val h = viewport.height.toFloat()
    if (w <= 0f || h <= 0f) return null
    val fw = f.frameWidth.toFloat()
    val fh = f.frameHeight.toFloat()
    val fill = max(w / fw, h / fh)
    val eyePx = f.eyeDistancePx * fill
    val px = f.anchorX * fw * fill - (fw * fill - w) / 2f
    var py = f.anchorY * fh * fill - (fh * fill - h) / 2f
    if (!usingEyes) py += HEAD_BONE_BELOW_EYES * eyePx
    val ndcX = 2f * px / w - 1f
    val ndcY = 1f - 2f * py / h

    val view = viewer.camera.getViewMatrix(FloatArray(16))
    val proj = viewer.camera.getProjectionMatrix(DoubleArray(16))
    val p00 = proj[0].toFloat()
    val p11 = proj[5].toFloat()
    val p20 = proj[8].toFloat()
    val p21 = proj[9].toFloat()
    if (kotlin.math.abs(p00) < 1e-6f || kotlin.math.abs(p11) < 1e-6f) return null

    val c = UNIT_CUBE_CENTER
    val rel = rotateAboutY(floatArrayOf(anchorModel[0] - c[0], anchorModel[1] - c[1], anchorModel[2] - c[2]), yawDegrees)
    fun cameraPoint(s: Float) = transformPoint(view, c[0] + s * rel[0], c[1] + s * rel[1], c[2] + s * rel[2])

    var s = appliedS.coerceIn(MIN_ROOT_SCALE, MAX_ROOT_SCALE)
    repeat(3) { // depth depends (weakly) on scale; converges in a couple of steps
        val depth = -cameraPoint(s)[2]
        if (depth <= 1e-3f) return null
        s = (eyePx * 2f * depth / (avatarEyeDistance * p00 * w)).coerceIn(MIN_ROOT_SCALE, MAX_ROOT_SCALE)
    }
    // Pinch still works in follow mode, as a multiplier on the matched size.
    s = (s * (zoom / DEFAULT_ZOOM)).coerceIn(MIN_ROOT_SCALE, MAX_ROOT_SCALE)

    val q = cameraPoint(s)
    val depth = -q[2]
    if (depth <= 1e-3f) return null
    val dx = depth * (ndcX + p20) / p00 - q[0]
    val dy = depth * (ndcY + p21) / p11 - q[1]
    // Camera-space offset -> world. The view rotation is orthonormal, so its
    // inverse is its transpose.
    val t = floatArrayOf(
        view[0] * dx + view[1] * dy,
        view[4] * dx + view[5] * dy,
        view[8] * dx + view[9] * dy
    )
    return t to s
}

/** Inverse of the root placement last applied: world -> model space. */
private fun ViewerSession.worldToModel(x: Float, y: Float, z: Float): FloatArray {
    val c = UNIT_CUBE_CENTER
    val s = if (appliedS > 1e-6f) appliedS else 1f
    val local = floatArrayOf((x - c[0] - appliedT[0]) / s, (y - c[1] - appliedT[1]) / s, (z - c[2] - appliedT[2]) / s)
    val back = rotateAboutY(local, -appliedYawDegrees)
    return floatArrayOf(c[0] + back[0], c[1] + back[1], c[2] + back[2])
}

private fun rotateAboutY(v: FloatArray, degrees: Float): FloatArray {
    val a = Math.toRadians(degrees.toDouble())
    val cs = cos(a).toFloat()
    val sn = sin(a).toFloat()
    return floatArrayOf(cs * v[0] + sn * v[2], v[1], -sn * v[0] + cs * v[2])
}

private fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(
    m[0] * x + m[4] * y + m[8] * z + m[12],
    m[1] * x + m[5] * y + m[9] * z + m[13],
    m[2] * x + m[6] * y + m[10] * z + m[14]
)

private fun distance(a: FloatArray, b: FloatArray): Float {
    val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun midpoint(a: FloatArray, b: FloatArray) =
    floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)

/** Toon (MToon → unlit) materials should show their texture colours as
 *  authored; the default ACES-style curve darkens and shifts them. */
private fun applyLinearToneMapping(viewer: ModelViewer): ColorGrading? = runCatching {
    ColorGrading.Builder()
        .toneMapper(ToneMapper.Linear())
        .build(viewer.engine)
        .also { viewer.view.colorGrading = it }
}.onFailure { Log.e(TAG, "Could not set linear tone mapping", it) }.getOrNull()

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
