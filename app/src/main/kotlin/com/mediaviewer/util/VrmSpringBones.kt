package com.mediaviewer.util

import android.util.Log
import com.google.android.filament.Engine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * VRM spring bones ("physics bones"): hair, ears, tails, skirts and
 * accessories that swing and settle when the avatar moves.
 *
 * Both flavours are read from the glTF JSON:
 *  - VRM 0.x `extensions.VRM.secondaryAnimation` — bone groups whose
 *    listed nodes, and everything below them, are springs; Unity-space
 *    vectors (collider offsets, gravity) have Z negated, as three-vrm does.
 *  - VRM 1.0 `extensions.VRMC_springBone` — explicit joint chains.
 *
 * The simulation is the standard VRM one (same as UniVRM / three-vrm):
 * each joint's tail is a Verlet point pulled by inertia, stiffness (back
 * towards its rest direction) and gravity, kept at bone length, and pushed
 * out of sphere/capsule colliders; the joint is then rotated to point at
 * its tail. It runs in the model's own glTF space, so moving/scaling the
 * whole avatar on screen (follow mode, pinch) doesn't whip the hair around,
 * but turning your head or body does.
 */
object VrmSpringBones {
    private const val TAG = "VrmSpringBones"

    class JointDef(
        val node: Int,
        /** The node the joint points at, or null for a VRM 0.x leaf
         *  (which gets a short virtual tail, as UniVRM does). */
        val tailNode: Int?,
        val stiffness: Float,
        val gravityPower: Float,
        val gravityDir: FloatArray,
        val dragForce: Float,
        val hitRadius: Float,
        val colliderGroups: IntArray
    )

    class ColliderDef(val node: Int, val offset: FloatArray, val radius: Float, val tail: FloatArray?)

    class SpringData(val joints: List<JointDef>, val colliderGroups: List<List<ColliderDef>>) {
        val isEmpty get() = joints.isEmpty()
    }

    fun parse(root: JSONObject): SpringData? = runCatching {
        val ext = root.optJSONObject("extensions") ?: return null
        ext.optJSONObject("VRMC_springBone")?.let { return parseVrm1(it) }
        ext.optJSONObject("VRM")?.optJSONObject("secondaryAnimation")?.let { return parseVrm0(it, root.optJSONArray("nodes")) }
        null
    }.onFailure { Log.e(TAG, "Couldn't read spring bones", it) }.getOrNull()

    private fun vec(a: JSONArray?, d: FloatArray) =
        if (a == null || a.length() < 3) d else floatArrayOf(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2).toFloat())

    private fun unityVec(o: JSONObject?, d: FloatArray) =
        if (o == null) d else floatArrayOf(o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(), -o.optDouble("z", 0.0).toFloat())

    private fun ints(a: JSONArray?) = IntArray(a?.length() ?: 0) { a!!.optInt(it, -1) }

    private fun parseVrm1(sb: JSONObject): SpringData {
        val colliders = ArrayList<ColliderDef?>()
        sb.optJSONArray("colliders")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i)
                val node = c?.optInt("node", -1) ?: -1
                val shape = c?.optJSONObject("shape")
                val sphere = shape?.optJSONObject("sphere")
                val capsule = shape?.optJSONObject("capsule")
                colliders.add(when {
                    node < 0 -> null
                    sphere != null -> ColliderDef(node, vec(sphere.optJSONArray("offset"), FloatArray(3)), sphere.optDouble("radius", 0.0).toFloat(), null)
                    capsule != null -> ColliderDef(node, vec(capsule.optJSONArray("offset"), FloatArray(3)), capsule.optDouble("radius", 0.0).toFloat(),
                        vec(capsule.optJSONArray("tail"), FloatArray(3)))
                    else -> null
                })
            }
        }
        val groups = ArrayList<List<ColliderDef>>()
        sb.optJSONArray("colliderGroups")?.let { arr ->
            for (i in 0 until arr.length()) {
                groups.add(ints(arr.optJSONObject(i)?.optJSONArray("colliders")).mapNotNull { colliders.getOrNull(it) })
            }
        }
        val joints = ArrayList<JointDef>()
        sb.optJSONArray("springs")?.let { springs ->
            for (i in 0 until springs.length()) {
                val spring = springs.optJSONObject(i) ?: continue
                val cg = ints(spring.optJSONArray("colliderGroups"))
                val js = spring.optJSONArray("joints") ?: continue
                // Every joint but the last is simulated; the last is its tail.
                for (j in 0 until js.length() - 1) {
                    val jo = js.optJSONObject(j) ?: continue
                    val tail = js.optJSONObject(j + 1)?.optInt("node", -1) ?: -1
                    val node = jo.optInt("node", -1)
                    if (node < 0 || tail < 0) continue
                    joints.add(JointDef(
                        node, tail,
                        jo.optDouble("stiffness", 1.0).toFloat(),
                        jo.optDouble("gravityPower", 0.0).toFloat(),
                        vec(jo.optJSONArray("gravityDir"), floatArrayOf(0f, -1f, 0f)),
                        jo.optDouble("dragForce", 0.5).toFloat(),
                        jo.optDouble("hitRadius", 0.0).toFloat(),
                        cg
                    ))
                }
            }
        }
        return SpringData(joints, groups)
    }

    private fun parseVrm0(sa: JSONObject, nodes: JSONArray?): SpringData {
        val groups = ArrayList<List<ColliderDef>>()
        sa.optJSONArray("colliderGroups")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i)
                val node = g?.optInt("node", -1) ?: -1
                val list = ArrayList<ColliderDef>()
                g?.optJSONArray("colliders")?.let { cs ->
                    for (k in 0 until cs.length()) {
                        val c = cs.optJSONObject(k) ?: continue
                        if (node >= 0) list.add(ColliderDef(node, unityVec(c.optJSONObject("offset"), FloatArray(3)), c.optDouble("radius", 0.0).toFloat(), null))
                    }
                }
                groups.add(list)
            }
        }
        val joints = ArrayList<JointDef>()
        val seen = HashSet<Int>()
        sa.optJSONArray("boneGroups")?.let { bgs ->
            for (i in 0 until bgs.length()) {
                val bg = bgs.optJSONObject(i) ?: continue
                // (sic) UniVRM's key is "stiffiness".
                val stiffness = (if (bg.has("stiffiness")) bg.optDouble("stiffiness") else bg.optDouble("stiffness", 1.0)).toFloat()
                val gravityPower = bg.optDouble("gravityPower", 0.0).toFloat()
                val gravityDir = unityVec(bg.optJSONObject("gravityDir"), floatArrayOf(0f, -1f, 0f)).let {
                    if (it[0] == 0f && it[1] == 0f && it[2] == 0f) floatArrayOf(0f, -1f, 0f) else it
                }
                val drag = bg.optDouble("dragForce", 0.4).toFloat()
                val hit = bg.optDouble("hitRadius", 0.02).toFloat()
                val cg = ints(bg.optJSONArray("colliderGroups"))
                // Each listed node and every descendant is a joint aimed at its first child.
                val stack = ArrayDeque(ints(bg.optJSONArray("bones")).filter { it >= 0 }.toList())
                while (stack.isNotEmpty()) {
                    val n = stack.removeLast()
                    if (!seen.add(n)) continue
                    val children = ints(nodes?.optJSONObject(n)?.optJSONArray("children")).filter { it >= 0 }
                    joints.add(JointDef(n, children.firstOrNull(), stiffness, gravityPower, gravityDir, drag, hit, cg))
                    children.forEach { stack.addLast(it) }
                }
            }
        }
        return SpringData(joints, groups)
    }

    // ───────────────────────────────────────────────────────── simulation

    /**
     * Live simulation for one loaded model. Main thread only (it reads and
     * writes Filament transforms). [update] once per rendered frame, after
     * the tracking pose has been applied; [reset] puts every joint back.
     */
    class Simulation(
        private val engine: Engine,
        private val rootEntity: Int,
        data: SpringData,
        nodeEntity: (Int) -> Int?,
        /** Entities the tracking retargeter owns — never simulated. */
        excluded: Set<Int>
    ) {
        private val tm = engine.transformManager

        private inner class Collider(val entity: Int, val offset: FloatArray, val radius: Float, val tail: FloatArray?)

        private inner class Joint(
            val entity: Int,
            val parent: Int,
            val restLocal: FloatArray,
            val restRot: Quaternion,
            val localT: FloatArray,
            val localS: FloatArray,
            /** Rest direction to the tail, in the joint's own space. */
            val axis: FloatArray,
            /** Tail distance in the joint's own (unscaled) space. */
            val length: Float,
            val def: JointDef,
            val colliders: List<Collider>
        ) {
            var cur: FloatArray? = null
            var prev: FloatArray? = null
        }

        private val joints: List<Joint>
        val jointCount get() = joints.size

        init {
            val groups = data.colliderGroups.map { g ->
                g.mapNotNull { c -> nodeEntity(c.node)?.let { Collider(it, c.offset, c.radius, c.tail) } }
            }
            val built = ArrayList<Pair<Int, Joint>>()
            for (def in data.joints) {
                val entity = nodeEntity(def.node) ?: continue
                if (entity in excluded) continue
                val inst = tm.getInstance(entity)
                if (inst == 0) continue
                val parent = runCatching { tm.getParent(inst) }.getOrDefault(0)
                if (parent == 0) continue
                val local = FloatArray(16).also { tm.getTransform(inst, it) }
                val localT = floatArrayOf(local[12], local[13], local[14])
                val localS = floatArrayOf(colLen(local, 0), colLen(local, 1), colLen(local, 2))
                val restRot = rotationOf(local)
                val tailEntity = def.tailNode?.let(nodeEntity)
                val (axis, length) = if (tailEntity != null) {
                    val ti = tm.getInstance(tailEntity)
                    val tl = FloatArray(16).also { tm.getTransform(ti, it) }
                    val v = floatArrayOf(tl[12], tl[13], tl[14])
                    normalize(v) to len(v)
                } else {
                    // VRM 0.x leaf: virtual 7 cm tail, continuing the bone's line.
                    val s = localS[0].coerceAtLeast(1e-6f)
                    normalize(localT) to 0.07f / s
                }
                if (length < 1e-6f || len(axis) < 0.5f) continue
                val colliders = def.colliderGroups.flatMap { groups.getOrNull(it).orEmpty() }
                built.add(depth(entity) to Joint(entity, parent, local, restRot, localT, localS, axis, length, def, colliders))
            }
            // Parents before children, so each joint sees its parent's new pose.
            joints = built.sortedBy { it.first }.map { it.second }
            Log.i(TAG, "Spring bones: ${joints.size} joints simulated")
        }

        private fun depth(entity: Int): Int {
            var d = 0; var e = entity
            while (d < 512) {
                val p = runCatching { tm.getParent(tm.getInstance(e)) }.getOrDefault(0)
                if (p == 0) break
                e = p; d++
            }
            return d
        }

        /** Puts every joint back to its rest pose and forgets its motion. */
        fun reset() {
            for (j in joints) {
                val inst = tm.getInstance(j.entity)
                if (inst != 0) runCatching { tm.setTransform(inst, j.restLocal) }
                j.cur = null; j.prev = null
            }
        }

        fun update(frameDt: Float) {
            if (joints.isEmpty()) return
            val rootInst = tm.getInstance(rootEntity)
            if (rootInst == 0) return
            val rootWorld = FloatArray(16).also { tm.getWorldTransform(rootInst, it) }
            val toSim = invert(rootWorld) ?: return
            // Fixed-ish steps keep it stable when a frame is slow.
            val steps = (frameDt / (1f / 60f)).toInt().coerceIn(1, 3)
            val dt = (frameDt / steps).coerceIn(1e-4f, 1f / 30f)
            val scratch = FloatArray(16)
            fun sim(entity: Int): FloatArray? {
                val inst = tm.getInstance(entity)
                if (inst == 0) return null
                tm.getWorldTransform(inst, scratch)
                return mul(toSim, scratch)
            }
            repeat(steps) {
                for (j in joints) {
                    val parentM = sim(j.parent) ?: continue
                    val parentRot = rotationOf(parentM)
                    val head = point(parentM, j.localT)
                    val restWorldRot = parentRot * j.restRot
                    val scale = colLen(parentM, 0) * j.localS[0]
                    val boneLen = j.length * scale
                    val restDir = rotate(restWorldRot, j.axis)
                    val cur = j.cur ?: add(head, scl(restDir, boneLen))
                    val prev = j.prev ?: cur
                    val d = j.def
                    var next = add(cur, scl(sub(cur, prev), 1f - d.dragForce))
                    next = add(next, scl(restDir, d.stiffness * dt))
                    next = add(next, scl(d.gravityDir, d.gravityPower * dt))
                    next = add(head, scl(normalize(sub(next, head)), boneLen))
                    for (c in j.colliders) {
                        val cm = sim(c.entity) ?: continue
                        var center = point(cm, c.offset)
                        if (c.tail != null) center = closestOnSegment(center, point(cm, c.tail), next)
                        val r = c.radius * colLen(cm, 0) + d.hitRadius * scale
                        val away = sub(next, center)
                        val dist = len(away)
                        if (dist < r && dist > 1e-6f) {
                            next = add(center, scl(away, r / dist))
                            next = add(head, scl(normalize(sub(next, head)), boneLen))
                        }
                    }
                    j.prev = cur
                    j.cur = next
                    // Aim the joint at its tail.
                    val to = rotate(restWorldRot.conjugate(), normalize(sub(next, head)))
                    val q = (j.restRot * quaternionBetweenDirections(j.axis, to)).normalized()
                    val inst = tm.getInstance(j.entity)
                    if (inst != 0) tm.setTransform(inst, compose(j.localT, q, j.localS))
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────── math

    private fun colLen(m: FloatArray, c: Int) = sqrt(m[c * 4] * m[c * 4] + m[c * 4 + 1] * m[c * 4 + 1] + m[c * 4 + 2] * m[c * 4 + 2])

    private fun rotationOf(m: FloatArray): Quaternion {
        val n = m.copyOf()
        for (c in 0 until 3) {
            val l = colLen(m, c)
            if (l > 1e-8f) for (r in 0 until 3) n[c * 4 + r] /= l
        }
        return Quaternion.fromRotationColumnMajorMatrix(n)
    }

    private fun compose(t: FloatArray, q: Quaternion, s: FloatArray): FloatArray {
        val r = q.toColumnMajorMatrix()
        for (c in 0 until 3) for (row in 0 until 3) r[c * 4 + row] *= s[c]
        r[12] = t[0]; r[13] = t[1]; r[14] = t[2]; r[15] = 1f
        return r
    }

    private fun point(m: FloatArray, p: FloatArray) = floatArrayOf(
        m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
        m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
        m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14]
    )

    private fun mul(a: FloatArray, b: FloatArray) = multiplyColumnMajor4x4(a, b)

    /** General 4x4 inverse (column-major); null if singular. */
    private fun invert(m: FloatArray): FloatArray? {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (kotlin.math.abs(det) < 1e-12f) return null
        for (i in 0 until 16) inv[i] /= det
        return inv
    }

    private fun closestOnSegment(a: FloatArray, b: FloatArray, p: FloatArray): FloatArray {
        val ab = sub(b, a)
        val l2 = dot(ab, ab)
        if (l2 < 1e-12f) return a
        val t = (dot(sub(p, a), ab) / l2).coerceIn(0f, 1f)
        return add(a, scl(ab, t))
    }

    private fun rotate(q: Quaternion, v: FloatArray): FloatArray {
        val ux = q.x; val uy = q.y; val uz = q.z
        val tx = 2f * (uy * v[2] - uz * v[1]); val ty = 2f * (uz * v[0] - ux * v[2]); val tz = 2f * (ux * v[1] - uy * v[0])
        return floatArrayOf(
            v[0] + q.w * tx + (uy * tz - uz * ty),
            v[1] + q.w * ty + (uz * tx - ux * tz),
            v[2] + q.w * tz + (ux * ty - uy * tx)
        )
    }

    private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun scl(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun len(a: FloatArray) = sqrt(dot(a, a))
    private fun normalize(a: FloatArray): FloatArray { val l = len(a); return if (l < 1e-8f) a else scl(a, 1f / l) }
}
