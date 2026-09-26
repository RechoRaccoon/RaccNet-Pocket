package com.mediaviewer.ui

import android.os.SystemClock
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentAsset
import com.mediaviewer.util.Quaternion
import com.mediaviewer.util.VrmData
import com.mediaviewer.util.multiplyColumnMajor4x4
import com.mediaviewer.util.quaternionBetweenDirections
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Drives the loaded VRM from MediaPipe tracking so it behaves like a
 * mirror (or a video-call filter): whatever the user does, the avatar does
 * on the same side of the screen that the mirrored tracking preview shows.
 *
 * ## Why the old version was inverted / on the wrong arm
 * Three separate problems, all fixed here rather than patched with sign
 * flips:
 *  1. **No mirror.** The preview box draws landmarks as `1 - x` (a mirror),
 *     but the avatar was driven un-mirrored: the user's LEFT arm drove the
 *     avatar's LEFT arm, which — the avatar facing you — sits on the
 *     opposite side of the screen. Mirroring a pose means reflecting every
 *     position across the screen's vertical axis AND swapping left/right
 *     bones; doing only one of the two can't work.
 *  2. **Frames mixed up.** Tracking deltas were computed in camera space
 *     but applied in the model's own space. A VRM 0.x model faces -Z and is
 *     spun 180° to face the camera, so X and Z (i.e. roll, and vertical
 *     arm swings) came out inverted while yaw/pitch happened to look right
 *     — exactly the symptom reported.
 *  3. **Calibration against the first frame.** Everything was "change
 *     since tracking started", so an arm that happened to be down when
 *     tracking began was treated as a T-pose.
 *
 * ## How it works now
 * Everything is solved in **model space** (the scene after the load-time
 * unit-cube fit, before the facing/zoom/follow transform) and is
 * **absolute**, not relative to a calibration frame:
 *  - Landmark positions are mirrored into screen space, then converted to
 *    model space with the model's own facing (worked out from its skeleton:
 *    which side its left arm is on), so VRM 0.x and 1.0 need no special
 *    cases.
 *  - Each bone gets a world-space *delta* `D` (its world rotation = D · rest).
 *    [PoseContext.drive] turns that into the local transform Filament wants,
 *    accounting for whatever its parents were already rotated by this frame
 *    — so arms stay correct when the torso leans, fingers when the hand
 *    turns, and so on.
 *  - Limbs use the rest skeleton's real bone directions (shoulder→elbow on
 *    the model) and rotate them onto the tracked directions, so any rest
 *    pose works. Joints MediaPipe can't see (off-frame, low visibility) fall
 *    back to a relaxed arms-down pose instead of snapping to a T-pose.
 *  - The head uses MediaPipe's face transformation matrix directly (it's
 *    identity when you face the camera), mirrored into model space.
 *
 * If a device ever shows the whole thing mirrored the "wrong" way, flip
 * [MIRROR] — it switches positions, bone sides, the head and the face
 * blendshapes together, so nothing ends up half-mirrored.
 */
class BoneRest(
    val name: String,
    val entity: Int,
    /** Parent-relative rest transform, column-major 4x4. */
    val restLocal: FloatArray,
    /** Rest rotation in model space. */
    val restWorldRotation: Quaternion,
    /** Rest position in model space (x, y, z). */
    val restWorldPosition: FloatArray,
    /** Nearest ancestor that is itself a humanoid bone, or null. */
    val parentBone: String?
)

/** One pose landmark in MediaPipe world space (meters, hip-centred:
 *  +x image-right, +y down, +z away from the camera), plus its visibility. */
class BodyPoint(val x: Float, val y: Float, val z: Float, val visibility: Float)

/** Everything [AvatarRetargeter.applyPose] needs for one update. */
class TrackingFrame(
    /** MediaPipe facial transformation matrix (column-major 4x4), or null. */
    val faceMatrix: FloatArray?,
    /** Smoothed pose world landmarks by BlazePose index; null = pose tracking off. */
    val body: Map<Int, BodyPoint>?,
    /** "Full Body" toggle — legs/hips only move when this is on. */
    val trackLegs: Boolean,
    /** HandLandmarker's 21 world landmarks (x, y, z — same axes as the
     *  pose's world landmarks), smoothed, keyed by the AVATAR side that
     *  hand drives ("left"/"right"; mirroring already applied). */
    val hands: Map<String, List<FloatArray>> = emptyMap()
)

class RetargetTarget(
    val engine: Engine,
    val asset: FilamentAsset,
    /** glTF node index -> Filament entity (resolved by node name). */
    val nodeIndexToEntity: Map<Int, Int>,
    /** Rest data for every humanoid bone that resolved, by VRM bone name. */
    val bones: Map<String, BoneRest>,
    /** True when the model faces -Z in its own space (normally VRM 0.x) and
     *  therefore needs a 180° turn to face the camera. Derived from the
     *  skeleton, with the spec version only as a fallback. */
    val facesNegativeZ: Boolean
) {
    internal val smoothed = HashMap<String, Quaternion>()
    internal var lastHead: Quaternion? = null
    internal var lastUpdateNanos = 0L
}

object AvatarRetargeter {
    private const val TAG = "AvatarRetargeter"

    /** Mirror mode — see the file doc. Positions, bone sides, head rotation
     *  and left/right face blendshapes all follow this one flag. */
    const val MIRROR = true

    /** Multiplies every bone smoothing time constant below (the settings
     *  smoothing slider; 1 = default, 0 = bones follow tracking instantly). */
    @Volatile var smoothingScale = 1f

    /** Pose landmarks below this visibility are treated as untracked. */
    private const val MIN_VISIBILITY = 0.5f

    // Temporal smoothing time constants (seconds). Landmarks are already
    // One-Euro filtered; this mainly softens tracked <-> fallback switches
    // and the unfiltered head matrix.
    private const val TAU_HEAD = 0.05f
    private const val TAU_TORSO = 0.08f
    private const val TAU_LIMB = 0.07f
    private const val TAU_FINGER = 0.06f
    private const val TAU_HAND = 0.06f

    /** Share of the head turn taken by the neck (when the rig has one). */
    private const val NECK_SHARE = 0.4f
    /** Wrist can't bend further than this from the forearm (radians). */
    private const val MAX_WRIST_BEND = 1.4f
    /** A finger bone can't bend further than this from its parent (radians). */
    private const val MAX_FINGER_BEND = 1.75f

    fun buildTarget(engine: Engine, asset: FilamentAsset, vrmData: VrmData): RetargetTarget {
        val nodeIndexToEntity = mutableMapOf<Int, Int>()
        vrmData.nodeNames.forEach { (nodeIndex, name) ->
            val entity = runCatching { asset.getFirstEntityByName(name) }.getOrNull()
            if (entity != null && entity != 0) nodeIndexToEntity[nodeIndex] = entity
        }
        if (nodeIndexToEntity.size < vrmData.nodeNames.size) {
            Log.w(TAG, "Resolved ${nodeIndexToEntity.size}/${vrmData.nodeNames.size} glTF nodes to entities by name")
        }

        val tm = engine.transformManager
        class Raw(val entity: Int, val local: FloatArray, val world: FloatArray)
        val raw = HashMap<String, Raw>()
        val entityToBone = HashMap<Int, String>()
        for ((boneName, nodeIndex) in vrmData.humanBones) {
            val entity = nodeIndexToEntity[nodeIndex] ?: continue
            runCatching {
                val instance = tm.getInstance(entity)
                if (instance != 0) {
                    val local = FloatArray(16).also { tm.getTransform(instance, it) }
                    val world = FloatArray(16).also { tm.getWorldTransform(instance, it) }
                    raw[boneName] = Raw(entity, local, world)
                    entityToBone[entity] = boneName
                }
            }.onFailure { Log.e(TAG, "Could not read rest transform for $boneName", it) }
        }

        // Nearest humanoid ancestor, walking Filament's own hierarchy (there
        // can be non-humanoid nodes in between, e.g. twist or armature nodes).
        fun humanoidParent(entity: Int): String? {
            var current = entity
            repeat(256) {
                val instance = tm.getInstance(current)
                if (instance == 0) return null
                val parent = runCatching { tm.getParent(instance) }.getOrDefault(0)
                if (parent == 0) return null
                entityToBone[parent]?.let { return it }
                current = parent
            }
            return null
        }

        val bones = HashMap<String, BoneRest>()
        for ((name, r) in raw) {
            bones[name] = BoneRest(
                name = name,
                entity = r.entity,
                restLocal = r.local,
                restWorldRotation = rotationOf(r.world),
                restWorldPosition = floatArrayOf(r.world[12], r.world[13], r.world[14]),
                parentBone = humanoidParent(r.entity)
            )
        }

        val leftArm = bones["leftUpperArm"]?.restWorldPosition
        val rightArm = bones["rightUpperArm"]?.restWorldPosition
        val facesNegativeZ = if (leftArm != null && rightArm != null && abs(leftArm[0] - rightArm[0]) > 1e-4f) {
            // Facing +Z, the avatar's left is +X; facing -Z it's -X.
            leftArm[0] < rightArm[0]
        } else {
            vrmData.specVersion == com.mediaviewer.util.VrmSpecVersion.VRM_0
        }
        return RetargetTarget(engine, asset, nodeIndexToEntity, bones, facesNegativeZ)
    }

    // ---------------------------------------------------------------- pose

    private class Side(
        val shoulder: Int, val elbow: Int, val wrist: Int, val pinky: Int, val index: Int,
        val hip: Int, val knee: Int, val ankle: Int
    )
    // BlazePose indices from the TRACKED PERSON's own point of view.
    private val PERSON_LEFT = Side(11, 13, 15, 17, 19, 23, 25, 27)
    private val PERSON_RIGHT = Side(12, 14, 16, 18, 20, 24, 26, 28)

    /** Which of the person's sides drives the avatar's [avatarSide]. In a
     *  mirror, your right hand is on the right of the screen — where the
     *  (camera-facing) avatar's LEFT hand is. */
    private fun sourceFor(avatarSide: String): Side = if (MIRROR) {
        if (avatarSide == "left") PERSON_RIGHT else PERSON_LEFT
    } else {
        if (avatarSide == "left") PERSON_LEFT else PERSON_RIGHT
    }

    fun applyPose(target: RetargetTarget, frame: TrackingFrame) {
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (target.lastUpdateNanos == 0L) 1f else ((now - target.lastUpdateNanos) / 1e9f).coerceIn(0f, 1f)
        target.lastUpdateNanos = now
        val ctx = PoseContext(target, dt)
        val body = frame.body
        val bones = target.bones
        val flip = target.facesNegativeZ
        fun point(i: Int): FloatArray? = body?.get(i)?.takeIf { it.visibility >= MIN_VISIBILITY }?.let { modelPoint(it, flip) }

        // ── Hips (only with Full Body: turning/tilting the pelvis) ──
        val hipsDelta = if (frame.trackLegs) run {
            val l = point(sourceFor("left").hip); val r = point(sourceFor("right").hip)
            val restL = bones["leftUpperLeg"]?.restWorldPosition; val restR = bones["rightUpperLeg"]?.restWorldPosition
            if (l == null || r == null || restL == null || restR == null) null
            else frameRotation(sub(restL, restR), UP, sub(l, r), UP)
        } else null
        ctx.drive("hips", hipsDelta ?: Quaternion.IDENTITY, TAU_TORSO)

        // ── Torso lean/twist from the shoulder line (spine; chest etc. follow) ──
        val spineBone = if (bones.containsKey("spine")) "spine" else "chest"
        val torso = run {
            val ls = point(sourceFor("left").shoulder); val rs = point(sourceFor("right").shoulder)
            val restL = bones["leftUpperArm"]?.restWorldPosition; val restR = bones["rightUpperArm"]?.restWorldPosition
            val restHips = bones["hips"]?.restWorldPosition
            if (ls == null || rs == null || restL == null || restR == null || restHips == null) return@run null
            val restUp = sub(mid(restL, restR), restHips)
            val lh = point(sourceFor("left").hip); val rh = point(sourceFor("right").hip)
            val up = if (lh != null && rh != null) sub(mid(ls, rs), mid(lh, rh)) else restUp
            frameRotation(sub(restL, restR), restUp, sub(ls, rs), up)
        }
        ctx.drive(spineBone, torso ?: ctx.parentDelta(spineBone), TAU_TORSO)

        // ── Head / neck ──
        frame.faceMatrix?.takeIf { it.size == 16 }?.let { m ->
            val r = rotationOf(m)
            val view = if (MIRROR) Quaternion(r.x, -r.y, -r.z, r.w) else r
            target.lastHead = viewToModel(view, flip)
        }
        if (bones.containsKey("neck")) {
            val parent = ctx.parentDelta("neck")
            val head = target.lastHead ?: parent
            ctx.drive("neck", Quaternion.slerp(parent, head, NECK_SHARE), TAU_HEAD)
        }
        ctx.drive("head", target.lastHead ?: ctx.parentDelta("head"), TAU_HEAD)

        // ── Arms, hands, fingers ──
        for (side in SIDES) {
            val hand = frame.hands[side]?.takeIf { it.size >= 21 }?.map { modelPoint(it, flip) }
            driveArm(ctx, side, ::point, hand)
        }

        // ── Legs (rest pose unless Full Body is on and they're visible) ──
        for (side in SIDES) {
            val s = sourceFor(side)
            val hip = if (frame.trackLegs) point(s.hip) else null
            val knee = if (frame.trackLegs) point(s.knee) else null
            val ankle = if (frame.trackLegs) point(s.ankle) else null
            driveSegment(ctx, "${side}UpperLeg", "${side}LowerLeg", if (hip != null && knee != null) direction(hip, knee) else null, TAU_LIMB)
            driveSegment(ctx, "${side}LowerLeg", "${side}Foot", if (knee != null && ankle != null) direction(knee, ankle) else null, TAU_LIMB)
        }
    }

    /** Rotates [bone] so its rest direction (towards [childBone]) points
     *  along [targetDirection]; null keeps it at rest relative to its parent. */
    private fun driveSegment(ctx: PoseContext, bone: String, childBone: String, targetDirection: FloatArray?, tau: Float) {
        val info = ctx.target.bones[bone] ?: return
        val parent = ctx.parentDelta(bone)
        val child = ctx.target.bones[childBone]
        if (targetDirection == null || child == null) {
            ctx.drive(bone, parent, tau)
            return
        }
        val restDir = direction(info.restWorldPosition, child.restWorldPosition)
        val current = rotate(parent, restDir)
        ctx.drive(bone, quaternionBetweenDirections(current, targetDirection) * parent, tau)
    }

    private fun driveArm(ctx: PoseContext, side: String, point: (Int) -> FloatArray?, hand: List<FloatArray>?) {
        val bones = ctx.target.bones
        val flip = ctx.target.facesNegativeZ
        val s = sourceFor(side)
        val shoulder = point(s.shoulder); val elbow = point(s.elbow); val wrist = point(s.wrist)
        // Screen-right (+X in view space) is the avatar's left, because it faces you.
        val sx = if (side == "left") 1f else -1f
        val relaxedUpper = viewDirToModel(normalize(floatArrayOf(0.30f * sx, -1f, 0.05f)), flip)
        val relaxedLower = viewDirToModel(normalize(floatArrayOf(0.12f * sx, -1f, 0.30f)), flip)

        val upper = "${side}UpperArm"; val lower = "${side}LowerArm"
        driveSegment(ctx, upper, lower,
            if (shoulder != null && elbow != null) direction(shoulder, elbow) else relaxedUpper, TAU_LIMB)
        driveSegment(ctx, lower, "${side}Hand",
            if (elbow != null && wrist != null) direction(elbow, wrist) else relaxedLower, TAU_LIMB)

        // Hand orientation. Best source: HandLandmarker's own 3-D points
        // (wrist, index/middle/pinky knuckles) — far steadier than the
        // pose's three rough hand points, which are only a fallback.
        val handName = "${side}Hand"
        val handInfo = bones[handName] ?: return
        val forearm = ctx.parentDelta(handName)
        val indexBase = bones["${side}IndexProximal"]; val littleBase = bones["${side}LittleProximal"]
        val middleBase = bones["${side}MiddleProximal"]
        var handDelta = forearm
        if (indexBase != null && littleBase != null) {
            val restAcross = sub(indexBase.restWorldPosition, littleBase.restWorldPosition)
            val restDir = sub(
                middleBase?.restWorldPosition ?: mid(indexBase.restWorldPosition, littleBase.restWorldPosition),
                handInfo.restWorldPosition
            )
            val tracked = if (hand != null) {
                frameRotation(restDir, restAcross, sub(hand[9], hand[0]), sub(hand[5], hand[17]))
            } else {
                val idx = point(s.index); val pinky = point(s.pinky)
                if (wrist != null && idx != null && pinky != null)
                    frameRotation(restDir, restAcross, sub(mid(idx, pinky), wrist), sub(idx, pinky))
                else null
            }
            tracked?.let { handDelta = clampRelative(forearm, it, MAX_WRIST_BEND) }
        }
        ctx.drive(handName, handDelta, TAU_HAND)
        driveFingers(ctx, side, hand)
    }

    private val FINGERS = listOf("Thumb", "Index", "Middle", "Ring", "Little")
    private val RELAXED_CURL = floatArrayOf(
        0.10f, 0.15f, 0.10f,   // thumb
        0.20f, 0.30f, 0.20f,   // index
        0.25f, 0.35f, 0.20f,   // middle
        0.30f, 0.40f, 0.25f,   // ring
        0.35f, 0.45f, 0.25f    // little
    )

    /** Landmark chains per finger (MediaPipe hand topology), thumb first. */
    private val FINGER_CHAINS = arrayOf(
        intArrayOf(1, 2, 3, 4), intArrayOf(5, 6, 7, 8), intArrayOf(9, 10, 11, 12),
        intArrayOf(13, 14, 15, 16), intArrayOf(17, 18, 19, 20)
    )

    /**
     * Fingers. With a tracked hand, each finger bone is swung onto the
     * direction between its two landmarks (knuckle→next knuckle), exactly
     * like the arms — so curl, spread and thumb opposition all come through,
     * with no per-rig bend-axis guessing. Thumb bones map to landmarks
     * 1→2→3→4 on both VRM 0.x (Proximal/Intermediate/Distal) and 1.0
     * (Metacarpal/Proximal/Distal). A bone may bend at most
     * [MAX_FINGER_BEND] away from its parent, which hides the occasional
     * landmark glitch. Without a hand, the fingers rest gently curled.
     */
    private fun driveFingers(ctx: PoseContext, side: String, hand: List<FloatArray>?) {
        val bones = ctx.target.bones
        val handBone = bones["${side}Hand"] ?: return
        val indexBase = bones["${side}IndexProximal"]; val littleBase = bones["${side}LittleProximal"]
        // Palm normal (out of the palm) from the rest skeleton; the cross
        // order differs per side because the hands are mirror images.
        val palmNormal = if (indexBase != null && littleBase != null) {
            val dir = sub(mid(indexBase.restWorldPosition, littleBase.restWorldPosition), handBone.restWorldPosition)
            val across = sub(indexBase.restWorldPosition, littleBase.restWorldPosition)
            normalize(if (side == "left") cross(dir, across) else cross(across, dir))
        } else floatArrayOf(0f, -1f, 0f)

        for ((fi, finger) in FINGERS.withIndex()) {
            val segments = if (finger == "Thumb" && bones.containsKey("${side}ThumbMetacarpal"))
                listOf("Metacarpal", "Proximal", "Distal") else listOf("Proximal", "Intermediate", "Distal")
            val names = segments.map { "$side$finger$it" }
            val chain = FINGER_CHAINS[fi]
            var previousRestDir = direction(handBone.restWorldPosition, bones[names[0]]?.restWorldPosition ?: continue)
            for (k in 0 until 3) {
                val info = bones[names[k]] ?: break
                val next = bones.getOrNull(names.getOrNull(k + 1))
                // The last bone has no humanoid child: it continues its parent's line.
                val restDir = if (next != null) direction(info.restWorldPosition, next.restWorldPosition) else previousRestDir
                val parent = ctx.parentDelta(names[k])
                val delta = if (hand != null) {
                    val target = direction(hand[chain[k]], hand[chain[k + 1]])
                    val swung = quaternionBetweenDirections(rotate(parent, restDir), target) * parent
                    clampRelative(parent, swung, MAX_FINGER_BEND)
                } else {
                    var angle = RELAXED_CURL[fi * 3 + k]
                    if (finger == "Thumb") angle *= 0.7f
                    parent * axisAngle(normalize(cross(restDir, palmNormal)), angle)
                }
                ctx.drive(names[k], delta, TAU_FINGER)
                previousRestDir = restDir
            }
        }
    }

    private fun Map<String, BoneRest>.getOrNull(key: String?): BoneRest? = key?.let { this[it] }

    private val SIDES = listOf("left", "right")
    private val UP = floatArrayOf(0f, 1f, 0f)

    /** Per-update bone state: which world deltas have been applied so far,
     *  so children can account for their already-rotated parents. */
    private class PoseContext(val target: RetargetTarget, val dt: Float) {
        private val deltas = HashMap<String, Quaternion>()
        private val tm = target.engine.transformManager

        /** World delta currently carried by [bone]'s parent chain. */
        fun parentDelta(bone: String): Quaternion {
            var p = target.bones[bone]?.parentBone
            var guard = 0
            while (p != null && guard++ < 64) {
                deltas[p]?.let { return it }
                p = target.bones[p]?.parentBone
            }
            return Quaternion.IDENTITY
        }

        /** Makes [bone]'s model-space rotation `desired · rest`, smoothed. */
        fun drive(bone: String, desired: Quaternion, tau: Float): Quaternion {
            val info = target.bones[bone] ?: return desired
            val previous = target.smoothed[bone]
            val t = tau * smoothingScale
            val d = if (previous == null || t <= 1e-4f) desired
            else Quaternion.slerp(previous, desired, 1f - exp(-dt / t))
            target.smoothed[bone] = d
            deltas[bone] = d
            // world = P·Wparent_rest·L_rest·X = P·W_rest·X  ⇒  X = W⁻¹·P⁻¹·D·W
            val wr = info.restWorldRotation
            val x = (wr.conjugate() * (parentDelta(bone).conjugate() * d) * wr).normalized()
            val local = multiplyColumnMajor4x4(info.restLocal, x.toColumnMajorMatrix())
            runCatching {
                val instance = tm.getInstance(info.entity)
                if (instance != 0) tm.setTransform(instance, local)
            }.onFailure { Log.e(TAG, "setTransform failed for $bone", it) }
            return d
        }
    }

    // ---------------------------------------------------------- expressions

    /** Drives the VRM's expressions from MediaPipe's ARKit-style scores.
     *  With [MIRROR], left/right blendshapes are swapped so winking your
     *  left eye closes the avatar eye on the same side of the screen. */
    fun applyExpressions(target: RetargetTarget, vrmData: VrmData, arkitBlendshapeScores: Map<String, Float>) {
        if (arkitBlendshapeScores.isEmpty() || vrmData.expressions.isEmpty()) return
        val scores = if (MIRROR) mirrorSides(arkitBlendshapeScores) else arkitBlendshapeScores
        val renderableManager = target.engine.renderableManager
        val weightsByNode = mutableMapOf<Int, MutableMap<Int, Float>>()
        for ((expressionName, binds) in vrmData.expressions) {
            val intensity = arkitIntensityForExpression(expressionName, scores)
            if (intensity <= 0.001f) continue
            for (bind in binds) {
                val nodeWeights = weightsByNode.getOrPut(bind.nodeIndex) { mutableMapOf() }
                nodeWeights[bind.morphTargetIndex] = (nodeWeights[bind.morphTargetIndex] ?: 0f) + intensity * bind.weight
            }
        }
        // Nodes that had weights last time but none now must be zeroed, or a
        // blink would stay closed once the score drops to 0.
        for (nodeIndex in vrmData.expressions.values.flatten().map { it.nodeIndex }.toSet()) {
            weightsByNode.getOrPut(nodeIndex) { mutableMapOf() }
        }
        for ((nodeIndex, morphWeights) in weightsByNode) {
            val entity = target.nodeIndexToEntity[nodeIndex] ?: continue
            val instance = renderableManager.getInstance(entity)
            if (instance == 0) continue
            val morphCount = runCatching { renderableManager.getMorphTargetCount(instance) }.getOrDefault(0)
            if (morphCount <= 0) continue
            val weights = FloatArray(morphCount)
            morphWeights.forEach { (index, weight) -> if (index in weights.indices) weights[index] = weight.coerceIn(0f, 1f) }
            runCatching { renderableManager.setMorphWeights(instance, weights, 0) }
                .onFailure { Log.e(TAG, "setMorphWeights failed for node $nodeIndex", it) }
        }
    }

    private fun mirrorSides(scores: Map<String, Float>): Map<String, Float> {
        val out = HashMap<String, Float>(scores.size)
        for ((k, v) in scores) {
            val key = when {
                k.endsWith("Left") -> k.removeSuffix("Left") + "Right"
                k.endsWith("Right") -> k.removeSuffix("Right") + "Left"
                else -> k
            }
            out[key] = v
        }
        return out
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

    // ---------------------------------------------------------------- math

    /** Mirrored (if [MIRROR]) screen-space position → model space. */
    private fun modelPoint(p: BodyPoint, flip: Boolean): FloatArray {
        // Camera view space: +X right, +Y up, +Z towards the viewer.
        val vx = if (MIRROR) -p.x else p.x
        return viewDirToModel(floatArrayOf(vx, -p.y, -p.z), flip)
    }

    /** Same conversion for a raw (x, y, z) world landmark. */
    private fun modelPoint(p: FloatArray, flip: Boolean): FloatArray =
        modelPoint(BodyPoint(p[0], p[1], p[2], 1f), flip)

    private fun viewDirToModel(v: FloatArray, flip: Boolean): FloatArray =
        if (flip) floatArrayOf(-v[0], v[1], -v[2]) else v

    private fun viewToModel(q: Quaternion, flip: Boolean): Quaternion =
        if (flip) Quaternion(-q.x, q.y, -q.z, q.w) else q

    /** Rotation part of a (possibly uniformly scaled) column-major 4x4. */
    fun rotationOf(m: FloatArray): Quaternion {
        val n = m.copyOf()
        for (c in 0 until 3) {
            val len = sqrt(n[c * 4] * n[c * 4] + n[c * 4 + 1] * n[c * 4 + 1] + n[c * 4 + 2] * n[c * 4 + 2])
            if (len > 1e-8f) for (r in 0 until 3) n[c * 4 + r] /= len
        }
        return Quaternion.fromRotationColumnMajorMatrix(n)
    }

    /** Rotation taking the frame (across1, up1) onto (across2, up2). */
    private fun frameRotation(across1: FloatArray, up1: FloatArray, across2: FloatArray, up2: FloatArray): Quaternion? {
        val a = basis(across1, up1) ?: return null
        val b = basis(across2, up2) ?: return null
        val m = FloatArray(16)
        for (r in 0 until 3) for (c in 0 until 3) {
            var sum = 0f
            for (k in 0 until 3) sum += b[k][r] * a[k][c]
            m[c * 4 + r] = sum
        }
        m[15] = 1f
        return Quaternion.fromRotationColumnMajorMatrix(m)
    }

    private fun basis(across: FloatArray, up: FloatArray): Array<FloatArray>? {
        if (length(across) < 1e-6f) return null
        val x = normalize(across)
        val zRaw = cross(x, up)
        if (length(zRaw) < 1e-6f) return null
        val z = normalize(zRaw)
        return arrayOf(x, cross(z, x), z)
    }

    /** Limits how far [q] may rotate away from [reference]. */
    private fun clampRelative(reference: Quaternion, q: Quaternion, maxAngle: Float): Quaternion {
        val rel = (reference.conjugate() * q).normalized()
        val angle = 2f * acos(abs(rel.w).coerceIn(0f, 1f))
        return if (angle <= maxAngle) q else Quaternion.slerp(reference, q, maxAngle / angle)
    }

    private fun axisAngle(axis: FloatArray, angle: Float): Quaternion {
        val h = angle / 2f; val s = sin(h)
        return Quaternion(axis[0] * s, axis[1] * s, axis[2] * s, cos(h)).normalized()
    }

    private fun rotate(q: Quaternion, v: FloatArray): FloatArray {
        val u = floatArrayOf(q.x, q.y, q.z)
        val t = cross(u, v).map { it * 2f }.toFloatArray()
        val c = cross(u, t)
        return floatArrayOf(v[0] + q.w * t[0] + c[0], v[1] + q.w * t[1] + c[1], v[2] + q.w * t[2] + c[2])
    }

    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun mid(a: FloatArray, b: FloatArray) = floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun length(a: FloatArray) = sqrt(dot(a, a))
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
    )
    private fun normalize(a: FloatArray): FloatArray {
        val l = length(a)
        return if (l < 1e-8f) a else floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
    }
    private fun direction(from: FloatArray, to: FloatArray) = normalize(sub(to, from))
}
