package com.mediaviewer.ui

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.TransformManager
import com.google.android.filament.gltfio.FilamentAsset
import com.mediaviewer.util.Quaternion
import com.mediaviewer.util.VrmData
import com.mediaviewer.util.directionBetween
import com.mediaviewer.util.multiplyColumnMajor4x4
import com.mediaviewer.util.quaternionBetweenDirections

/**
 * VRM pipeline step 6 (see VrmModeScreen.kt's doc comment / the handoff's
 * "VRM: what's next" section): retargeting — the step that turns
 * [VrmAvatarView]'s static T-pose render into an avatar actually driven by
 * tracking data.
 *
 * ## Scope of what's actually implemented here
 * **Everything**: expressions, head/neck rotation, arm rotation, and leg
 * rotation. [applyExpressions] drives the VRM's morph targets (blinks,
 * mouth shapes, ...) from MediaPipe's 52 ARKit-style blendshape scores,
 * live, every frame. [applyHeadRotation] turns MediaPipe's per-frame face
 * transformation matrix into head/neck bone rotation. [applyArmRotation]
 * and [applyLegRotation] turn `PoseLandmarker`'s world-space joint
 * positions into rotation on the remaining eight humanoid limb bones —
 * the same calibrate-then-delta pattern as the head, just built from two
 * tracked *positions* (a direction) instead of one tracked *orientation*
 * (a matrix), and reused verbatim between arms and legs via the shared
 * [applyLimb] helper. This was built in exactly that order — head first
 * (cheapest to verify: "does it turn when I turn my head?"), then arms,
 * then legs — each verified on a real device before the next reused its
 * pattern, per the handoff's own build order. With legs done, retargeting
 * — and the VRM pipeline as originally scoped — is complete; only item 1
 * (the unrelated media3 thumbnail-stitching migration) remains open
 * project-wide. See [applyHeadRotation]/[applyArmRotation]/
 * [applyLegRotation]'s own doc comments for what's still flagged as
 * needing real-device verification (axis-remap signs, matrix/landmark
 * coordinate-space assumptions, left/right mirroring) — "implemented"
 * here means "the pipeline exists end to end," not "confirmed correct on
 * a device," which this sandbox has never been able to do for any of it.
 *
 * ## The node-index → entity bridge, and how confident it is
 * [VrmData.humanBones] and every [com.mediaviewer.util.MorphTargetBind]
 * (both from step 4's [com.mediaviewer.util.VrmParser]) identify things
 * by **glTF node index** — that's what the VRM spec's JSON itself uses.
 * Filament's `gltfio` loader (step 5) instead works in terms of
 * **entities** (`RenderableManager`/`TransformManager` instances). The
 * bridge used here is: node index → that node's own glTF `name` string
 * (captured in [VrmData.nodeNames], the plain glTF `nodes` array, not a
 * VRM-specific field) → `FilamentAsset.getFirstEntityByName(name)`. This
 * is the least-certain piece of this whole VRM pipeline so far: it
 * depends on (a) `getFirstEntityByName` existing on `FilamentAsset` with
 * that exact name and behavior, and (b) the avatar's glTF nodes actually
 * having unique, non-blank names (true for every VRM export pipeline I'm
 * aware of — Unity's VRM exporters name nodes after their bones — but not
 * guaranteed by the spec itself). [buildTarget] logs a warning, not a
 * crash, if some/all names fail to resolve, and everything downstream
 * (both [applyExpressions] here and bone rotation whenever it's added)
 * degrades to "that bone/expression's binds are silently skipped" rather
 * than failing — same shape as [com.mediaviewer.util.VrmParser]'s own
 * "missing field → null, not a crash" philosophy. If this turns out not
 * to resolve anything on a real device, check `FilamentAsset`'s actual
 * Java/Kotlin API surface for whatever the real by-name (or by-node-
 * index, if one exists directly and this whole bridge turns out to be
 * unnecessary) lookup is called in the pinned Filament version first.
 *
 * `RenderableManager.getMorphTargetCount`/`setMorphWeights` (used in
 * [applyExpressions]) carry the same "reconstructed from documented
 * shape, not checked against the pinned AAR" caveat as everywhere else in
 * this pipeline.
 */
data class RetargetTarget(
    val engine: Engine,
    val asset: FilamentAsset,
    /** glTF node index → Filament entity, resolved once per model load —
     *  see this file's top doc comment for how and how confident it is. */
    val nodeIndexToEntity: Map<Int, Int>,
    /** Each humanoid bone node's own **local** (parent-relative) rest-pose
     *  transform — column-major 16-float, [Quaternion]'s convention —
     *  captured once right after load, before anything has rotated it.
     *  [applyHeadRotation] composes a tracked delta rotation on top of
     *  *this* every frame, never on top of whatever the transform happens
     *  to already be, so per-frame deltas don't compound onto each other.
     *  Only populated for nodes in [VrmData.humanBones] — that's the only
     *  set bone rotation (current or future) ever touches. */
    val restLocalTransforms: Map<Int, FloatArray>,
    /** Head-turn calibration baseline: the (axis-remapped) rotation
     *  [applyHeadRotation] saw on the first usable frame after a model
     *  loads, or after a face was lost and reacquired. MediaPipe's matrix
     *  is an *absolute* camera-space rotation, and nothing documents that
     *  its identity orientation lines up with "this person is looking
     *  straight at the camera" for every device — calibrating against
     *  whatever the first frame reports sidesteps needing that guarantee,
     *  at the cost of assuming the person is roughly facing the camera
     *  when tracking starts (true for VTuber setups in practice). A `var`
     *  — not part of the `data class`'s equality/copy semantics in any
     *  way that matters here — because it's the one field of
     *  [RetargetTarget] that legitimately changes after [buildTarget]. */
    var headCalibration: Quaternion? = null,
    /** Per-limb calibration baseline — same idea as [headCalibration] but
     *  a plain tracked *direction* (unit 3-float vector, proximal→distal
     *  joint — e.g. shoulder→elbow) rather than a full rotation, keyed by
     *  VRM bone name (`"leftUpperArm"`, `"rightLowerArm"`, ...). See
     *  [AvatarRetargeter.applyArmRotation]'s doc comment for why
     *  direction-only calibration is what limbs use instead of a true
     *  rest-pose world direction. A `MutableMap` held in a `val` (the map
     *  itself is mutated in place; the reference never changes), same
     *  reasoning as [headCalibration] being the one part of this class
     *  that isn't fixed at [buildTarget] time. */
    val limbCalibrationDirections: MutableMap<String, FloatArray> = mutableMapOf()
)

object AvatarRetargeter {
    private const val TAG = "AvatarRetargeter"

    /** Called once right after [VrmAvatarView] loads a model — not every
     *  frame, since it's a name lookup per glTF node, not something worth
     *  redoing 60 times a second for a model that isn't changing. */
    fun buildTarget(engine: Engine, asset: FilamentAsset, vrmData: VrmData): RetargetTarget {
        val nodeIndexToEntity = mutableMapOf<Int, Int>()
        vrmData.nodeNames.forEach { (nodeIndex, name) ->
            val entity = runCatching { asset.getFirstEntityByName(name) }.getOrNull()
            if (entity != null && entity != 0) nodeIndexToEntity[nodeIndex] = entity
        }
        if (vrmData.nodeNames.isNotEmpty() && nodeIndexToEntity.size < vrmData.nodeNames.size) {
            Log.w(
                TAG,
                "Resolved ${nodeIndexToEntity.size}/${vrmData.nodeNames.size} glTF nodes to Filament " +
                    "entities by name — see AvatarRetargeter.kt's doc comment if this is 0 or unexpectedly low."
            )
        }

        // Bone-rotation rest poses (see RetargetTarget.restLocalTransforms's
        // own doc comment) — only for humanoid bones, captured once here
        // rather than re-read every frame.
        val restLocalTransforms = mutableMapOf<Int, FloatArray>()
        val transformManager = engine.transformManager
        vrmData.humanBones.values.forEach { nodeIndex ->
            val entity = nodeIndexToEntity[nodeIndex] ?: return@forEach
            runCatching {
                val instance = transformManager.getInstance(entity)
                if (instance != 0) {
                    val local = FloatArray(16)
                    transformManager.getTransform(instance, local)
                    restLocalTransforms[nodeIndex] = local
                }
            }.onFailure { Log.e(TAG, "Could not read rest-pose transform for node $nodeIndex", it) }
        }

        return RetargetTarget(engine, asset, nodeIndexToEntity, restLocalTransforms)
    }

    /** Drives every VRM expression [target]'s file defines from
     *  [arkitBlendshapeScores] (MediaPipe's smoothed 0.0–1.0 ARKit-style
     *  scores, keyed by ARKit blendshape name — see
     *  [VrmModeScreen]'s smoothing code for where these come from). Safe
     *  to call every frame; does nothing (cheaply) if either map is empty,
     *  e.g. no face currently detected or the file had no expressions. */
    fun applyExpressions(target: RetargetTarget, vrmData: VrmData, arkitBlendshapeScores: Map<String, Float>) {
        if (arkitBlendshapeScores.isEmpty() || vrmData.expressions.isEmpty()) return
        val renderableManager = target.engine.renderableManager

        // nodeIndex -> (morphTargetIndex -> accumulated weight). Grouped by
        // node first because a single mesh node commonly receives binds
        // from *multiple* expressions (e.g. a "surprised" mouth-open bind
        // and an "aa" vowel bind can land on the same morph target), and
        // RenderableManager.setMorphWeights sets every weight on a
        // renderable in one call — so all of a node's contributions need
        // to be summed before that one call, not applied as separate calls
        // that would each stomp the previous expression's weights.
        val weightsByNode = mutableMapOf<Int, MutableMap<Int, Float>>()
        for ((expressionName, binds) in vrmData.expressions) {
            val intensity = arkitIntensityForExpression(expressionName, arkitBlendshapeScores)
            if (intensity <= 0.001f) continue
            for (bind in binds) {
                val nodeWeights = weightsByNode.getOrPut(bind.nodeIndex) { mutableMapOf() }
                val contribution = intensity * bind.weight
                nodeWeights[bind.morphTargetIndex] = (nodeWeights[bind.morphTargetIndex] ?: 0f) + contribution
            }
        }

        for ((nodeIndex, morphWeights) in weightsByNode) {
            val entity = target.nodeIndexToEntity[nodeIndex] ?: continue
            val instance = renderableManager.getInstance(entity)
            if (instance == 0) continue
            val morphCount = runCatching { renderableManager.getMorphTargetCount(instance) }.getOrDefault(0)
            if (morphCount <= 0) continue
            val weights = FloatArray(morphCount)
            morphWeights.forEach { (index, weight) ->
                if (index in weights.indices) weights[index] = weight.coerceIn(0f, 1f)
            }
            runCatching { renderableManager.setMorphWeights(instance, weights, 0) }
                .onFailure { Log.e(TAG, "setMorphWeights failed for node $nodeIndex", it) }
        }
    }

    /**
     * VRM pipeline step 6, second half: the first bone rotation in the
     * pipeline. Turns MediaPipe's per-frame **facial transformation
     * matrix** — a camera-space head-pose estimate, distinct from the
     * blendshape scores [applyExpressions] uses — into a rotation on the
     * VRM's `head` bone (and, proportionally, `neck` if the file has one),
     * via [target]'s pre-captured rest-pose transforms and Filament's
     * `TransformManager`. Safe to call every frame; a no-op if the head
     * bone doesn't resolve or no face is currently tracked.
     *
     * [facialTransformationMatrix] is [com.google.mediapipe.tasks.vision
     * .facelandmarker.FaceLandmarkerResult.facialTransformationMatrixes]'s
     * per-face entry for the current frame — a 16-float flattened 4x4, or
     * null if no face is detected this frame (in which case
     * [RetargetTarget.headCalibration] is reset, so re-detection later
     * recalibrates cleanly instead of snapping to a stale baseline).
     *
     * ## The two things most likely to be backwards on a real device
     * 1. **Flattening order.** [Quaternion.fromRotationColumnMajorMatrix]
     *    assumes [facialTransformationMatrix] is **column-major**
     *    (`android.opengl.Matrix`/OpenGL convention — matches Filament's
     *    own `TransformManager`, which is why the rest of this function
     *    doesn't need to convert). MediaPipe's docs describe the matrix's
     *    *meaning* (a camera-facing coordinate space: +X right, +Y up, +Z
     *    toward the camera) but not its flattening order explicitly. If
     *    head rotation on a real device looks like nonsense (not just
     *    mirrored, but genuinely wrong shapes of motion), try transposing
     *    the incoming array before it reaches that function first.
     * 2. **Axis remap sign/axis choice**, just below — MediaPipe's
     *    camera-facing space and VRM's bone-local space are two
     *    conventions that were never designed against each other, same
     *    situation as the ARKit→VRM expression table above. The signs
     *    chosen here are a starting guess (flip yaw+roll, keep pitch); if
     *    a real device shows nodding working but left/right turning
     *    mirrored (or vice versa), that's this remap, not the calibration
     *    or slerp logic beneath it.
     *
     * Both are exactly the kind of thing this whole pipeline has been
     * honest about needing a real device to confirm — see this file's top
     * doc comment.
     */
    fun applyHeadRotation(target: RetargetTarget, vrmData: VrmData, facialTransformationMatrix: FloatArray?) {
        if (facialTransformationMatrix == null || facialTransformationMatrix.size != 16) {
            target.headCalibration = null // face lost — recalibrate fresh next time one's found, don't snap to a stale baseline
            return
        }
        val headNode = vrmData.humanBones["head"] ?: return
        val headRest = target.restLocalTransforms[headNode] ?: return
        val transformManager = target.engine.transformManager

        val tracked = runCatching {
            Quaternion.fromRotationColumnMajorMatrix(facialTransformationMatrix)
        }.getOrNull() ?: return

        // See this function's doc comment, point 2 — flip yaw (Y) and
        // roll (Z), keep pitch (X), as the starting axis remap.
        val remapped = Quaternion(tracked.x, -tracked.y, -tracked.z, tracked.w)

        val calibration = target.headCalibration
        if (calibration == null) {
            target.headCalibration = remapped.conjugate()
            return // nothing to apply yet on the very frame a new baseline is set
        }

        // Rotation relative to the calibrated "neutral" pose. If turning
        // your head rotates the avatar the wrong way on a real device,
        // swap this multiplication order (remapped * calibration) before
        // touching the axis remap above — composition order is the other
        // place a mirrored result can come from.
        val delta = (calibration * remapped).normalized()

        applyDeltaToBone(target, transformManager, headNode, headRest, delta, fraction = HEAD_ROTATION_FRACTION)

        // Neck is optional — not every VRM humanoid rig defines one (it's
        // not in VRM's *required* bone set) — split the same delta
        // proportionally onto it if it exists, purely so the turn doesn't
        // look like it hinges unnaturally at the very top of the spine.
        // A single-bone ("head" only, fraction 1.0) version of this is a
        // one-line simplification if the split ever looks wrong.
        val neckNode = vrmData.humanBones["neck"]
        val neckRest = neckNode?.let { target.restLocalTransforms[it] }
        if (neckNode != null && neckRest != null) {
            applyDeltaToBone(target, transformManager, neckNode, neckRest, delta, fraction = 1f - HEAD_ROTATION_FRACTION)
        }
    }

    /** How much of the tracked head-turn delta lands on the `head` bone
     *  itself vs. the `neck` bone (when present) — a plain fixed split,
     *  not measured against a real rig. Adjust to taste once step 6 is
     *  actually visible on a device. */
    private const val HEAD_ROTATION_FRACTION = 0.6f

    private fun applyDeltaToBone(
        target: RetargetTarget,
        transformManager: TransformManager,
        nodeIndex: Int,
        restLocal: FloatArray,
        delta: Quaternion,
        fraction: Float
    ) {
        val entity = target.nodeIndexToEntity[nodeIndex] ?: return
        val scaled = if (fraction >= 0.999f) delta else Quaternion.slerp(Quaternion.IDENTITY, delta, fraction)
        val newLocal = multiplyColumnMajor4x4(restLocal, scaled.toColumnMajorMatrix())
        runCatching {
            val instance = transformManager.getInstance(entity)
            if (instance != 0) transformManager.setTransform(instance, newLocal)
        }.onFailure { Log.e(TAG, "setTransform failed for node $nodeIndex", it) }
    }

    // ---- Arm rotation (step 6, third piece) --------------------------

    // BlazePose's 33-point topology (what MediaPipe's PoseLandmarker
    // outputs) — the six indices arm rotation needs. "left"/"right" here
    // are the *tracked person's own* left/right, matching VRM's
    // `left*`/`right*` bone-name convention — see applyArmRotation's doc
    // comment for the mirror-flip uncertainty that assumption carries.
    private const val LEFT_SHOULDER = 11
    private const val RIGHT_SHOULDER = 12
    private const val LEFT_ELBOW = 13
    private const val RIGHT_ELBOW = 14
    private const val LEFT_WRIST = 15
    private const val RIGHT_WRIST = 16

    /**
     * VRM pipeline step 6, third piece (after expressions, head/neck):
     * upper-arm and forearm rotation, both sides, from `PoseLandmarker`'s
     * world-space shoulder/elbow/wrist points (meters, hip-centered —
     * see [com.mediaviewer.ui.VrmModeScreen]'s `smoothedArmWorldLandmarks`
     * for where [worldLandmarks] comes from and why it's smoothed, unlike
     * the head's face matrix). Safe to call every frame; each of the four
     * limbs (`leftUpperArm`/`leftLowerArm`/`rightUpperArm`/`rightLowerArm`)
     * degrades independently to "not moved this frame" if its two
     * landmarks, or the VRM bone itself, aren't available.
     *
     * ## Why this doesn't need each bone's true rest-pose *world* direction
     * A proper retarget would rotate each bone so its direction matches
     * the tracked shoulder→elbow / elbow→wrist vector exactly. Computing a
     * bone's true rest-pose **world** direction needs walking its full
     * parent chain (`hips` → `spine` → ... → `upperArm`), composing every
     * ancestor's local transform — this pipeline has never needed to do
     * that (everything else here works in local space alone), and
     * building it just for this would be a third mostly-independent piece
     * of uncertain math stacked on top of the two this function already
     * has (landmark→direction, direction→rotation). Instead, same as
     * [applyHeadRotation]: calibrate against whatever direction each limb
     * reports on the first usable frame, then apply only the *change*
     * since then on top of the bone's local rest pose
     * ([applyLimb]/[applyDeltaToBone]). This is honestly a coarser
     * approximation for limbs than for the head (a limb's true swing
     * range also depends on its parent bone's own current orientation,
     * which this ignores) — but it needs no skeleton solve, which is the
     * same tradeoff VSeeFace-class single-camera tools make. Verify
     * visually before trusting it further than "does the avatar's arm
     * move roughly where mine does."
     *
     * ## What's most likely backwards on a real device
     * Same axis-remap caveat as [applyHeadRotation] (this function reuses
     * the identical flip-Y/Z-keep-X starting guess, in [applyLimb]) — plus
     * one new one: whether MediaPipe's `left_shoulder`/`left_elbow`/...
     * landmark indices (per BlazePose's own topology) actually correspond
     * to the *avatar's* `leftUpperArm` without a mirror flip. If arms
     * visibly drive the wrong side of the avatar on a real device, swap
     * the `LEFT_*`/`RIGHT_*` index constants above rather than touching
     * any of the rotation math.
     */
    fun applyArmRotation(target: RetargetTarget, vrmData: VrmData, worldLandmarks: Map<Int, FloatArray>) {
        applyLimb(target, vrmData, "leftUpperArm", worldLandmarks[LEFT_SHOULDER], worldLandmarks[LEFT_ELBOW])
        applyLimb(target, vrmData, "leftLowerArm", worldLandmarks[LEFT_ELBOW], worldLandmarks[LEFT_WRIST])
        applyLimb(target, vrmData, "rightUpperArm", worldLandmarks[RIGHT_SHOULDER], worldLandmarks[RIGHT_ELBOW])
        applyLimb(target, vrmData, "rightLowerArm", worldLandmarks[RIGHT_ELBOW], worldLandmarks[RIGHT_WRIST])
    }

    // BlazePose's remaining lower-body indices — hips, knees, ankles.
    // Same "person's own left/right, matching VRM's left*/right* bone
    // names" assumption and mirror-flip caveat as the arm indices above.
    private const val LEFT_HIP = 23
    private const val RIGHT_HIP = 24
    private const val LEFT_KNEE = 25
    private const val RIGHT_KNEE = 26
    private const val LEFT_ANKLE = 27
    private const val RIGHT_ANKLE = 28

    /**
     * VRM pipeline step 6, fourth and final piece: upper-leg and lower-leg
     * rotation, both sides, from `PoseLandmarker`'s world-space hip/knee/
     * ankle points — otherwise identical to [applyArmRotation] (same
     * calibrate-then-delta [applyLimb] helper, same axis-remap and
     * mirror-flip caveats, same reasoning for why this doesn't need each
     * bone's true rest-pose world direction — see that function's own doc
     * comment for the full detail, none of it repeated here since none of
     * it changed for legs). Gated by the "Full Body" Settings toggle at
     * the call site in [com.mediaviewer.ui.VrmModeScreen] — legs are the
     * heavier, less-often-needed half of body tracking (a VTuber sitting
     * at a desk has no visible legs to track in the first place), same
     * reasoning the Settings sheet's own copy already gives for gating
     * pose tracking generally.
     *
     * With this, **every humanoid bone this pipeline set out to retarget
     * is implemented** — see this file's top doc comment: expressions,
     * head/neck, arms, and now legs. What's left project-wide is item 1
     * (the media3 thumbnail-stitching migration), which is unrelated to
     * VRM entirely.
     */
    fun applyLegRotation(target: RetargetTarget, vrmData: VrmData, worldLandmarks: Map<Int, FloatArray>) {
        applyLimb(target, vrmData, "leftUpperLeg", worldLandmarks[LEFT_HIP], worldLandmarks[LEFT_KNEE])
        applyLimb(target, vrmData, "leftLowerLeg", worldLandmarks[LEFT_KNEE], worldLandmarks[LEFT_ANKLE])
        applyLimb(target, vrmData, "rightUpperLeg", worldLandmarks[RIGHT_HIP], worldLandmarks[RIGHT_KNEE])
        applyLimb(target, vrmData, "rightLowerLeg", worldLandmarks[RIGHT_KNEE], worldLandmarks[RIGHT_ANKLE])
    }

    /** Shared by every limb [applyArmRotation]/[applyLegRotation] drive:
     *  one bone, one proximal→distal direction, calibrate-then-delta
     *  exactly like [applyHeadRotation] but for a direction vector instead
     *  of a full rotation matrix. [proximal]/[distal] are plain 3-float
     *  (x, y, z) world-space points; either being null means that joint
     *  wasn't tracked this frame. */
    private fun applyLimb(
        target: RetargetTarget,
        vrmData: VrmData,
        boneName: String,
        proximal: FloatArray?,
        distal: FloatArray?
    ) {
        if (proximal == null || distal == null) {
            target.limbCalibrationDirections.remove(boneName) // lost tracking — recalibrate fresh, don't snap to a stale baseline
            return
        }
        val boneNode = vrmData.humanBones[boneName] ?: return
        val rest = target.restLocalTransforms[boneNode] ?: return

        val tracked = directionBetween(proximal, distal)
        // Same starting axis remap as applyHeadRotation's point 2 — flip
        // Y/Z, keep X. See that function's doc comment for what to try
        // first if a limb's motion looks backwards on a real device.
        val remapped = floatArrayOf(tracked[0], -tracked[1], -tracked[2])

        val calibration = target.limbCalibrationDirections[boneName]
        if (calibration == null) {
            target.limbCalibrationDirections[boneName] = remapped
            return // nothing to apply yet on the very frame a new baseline is set
        }

        val delta = quaternionBetweenDirections(calibration, remapped)
        applyDeltaToBone(target, target.engine.transformManager, boneNode, rest, delta, fraction = 1f)
    }

    /**
     * A hand-picked ARKit-blendshape → VRM-expression heuristic, **not**
     * part of either spec — no standard defines this mapping, because
     * ARKit and VRM's expression presets come from two different
     * ecosystems (iOS TrueDepth face tracking vs. VTuber avatar rigs) that
     * were never designed against each other. This is the same kind of
     * approximate mapping indie VTuber tools (VSeeFace, etc.) hand-tune —
     * a reasonable starting point, not a correctness guarantee, and
     * exactly the kind of thing worth adjusting once a real face/avatar
     * pair shows what actually looks right. Matches against **both** VRM
     * 1.0's preset names (`"happy"`, `"aa"`, `"blinkLeft"`, ...) and VRM
     * 0.x's (`"joy"`, `"a"`, ...) case-insensitively, since
     * [com.mediaviewer.util.VrmParser] doesn't normalize between them (see
     * its own doc comment) — an author-defined custom expression name that
     * happens not to match anything below simply never activates, which is
     * the correct fallback (there's no ARKit input that should drive an
     * arbitrary custom expression by default anyway).
     */
    private fun arkitIntensityForExpression(expressionName: String, scores: Map<String, Float>): Float {
        fun score(name: String) = scores[name] ?: 0f
        fun avg(vararg names: String) = names.sumOf { score(it).toDouble() }.toFloat() / names.size

        val intensity = when (expressionName.lowercase()) {
            "happy", "joy" -> avg("mouthSmileLeft", "mouthSmileRight")
            "angry", "anger" -> avg("browDownLeft", "browDownRight")
            "sad", "sorrow" -> avg("mouthFrownLeft", "mouthFrownRight")
            "surprised", "surprise" -> avg("browInnerUp", "browOuterUpLeft", "browOuterUpRight")
            "blink" -> avg("eyeBlinkLeft", "eyeBlinkRight")
            "blinkleft", "blink_l" -> score("eyeBlinkLeft")
            "blinkright", "blink_r" -> score("eyeBlinkRight")
            // Vowel/viseme presets — rough shape matches, not phonetic
            // accuracy (real lip-sync would drive these from audio, not
            // ARKit's face-shape blendshapes at all; this just gives some
            // mouth movement while talking instead of a static mouth).
            "aa", "a" -> score("jawOpen")
            "ih", "i" -> avg("mouthStretchLeft", "mouthStretchRight")
            "ou", "u" -> score("mouthPucker")
            "ee", "e" -> avg("mouthSmileLeft", "mouthSmileRight") * 0.5f
            "oh", "o" -> score("mouthFunnel")
            "lookup" -> avg("eyeLookUpLeft", "eyeLookUpRight")
            "lookdown" -> avg("eyeLookDownLeft", "eyeLookDownRight")
            // ARKit names each eye from the subject's own perspective, same
            // convention VRM uses — "lookLeft" is the avatar's left, i.e.
            // that eye looking outward + the other eye looking inward.
            "lookleft" -> avg("eyeLookOutLeft", "eyeLookInRight")
            "lookright" -> avg("eyeLookInLeft", "eyeLookOutRight")
            // "relaxed"/"neutral" and any author-defined custom name: no
            // ARKit input maps to these by default.
            else -> 0f
        }
        return intensity.coerceIn(0f, 1f)
    }
}
