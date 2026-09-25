package com.mediaviewer.util

import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * VRM pipeline step 6, bone-rotation half (see `AvatarRetargeter.kt`'s doc
 * comment): the small quaternion/4x4-matrix math that turns a tracked
 * rotation into something `Filament.TransformManager.setTransform` can
 * consume. Split out of `AvatarRetargeter.kt` because arm/leg rotation
 * (not yet implemented — see that file) will reuse every function here
 * unchanged; only the ARKit/MediaPipe-specific "which bone, from which
 * tracking signal" logic is bone-specific.
 *
 * All matrices here are **column-major 16-float 4x4**, i.e. `android.
 * opengl.Matrix`/OpenGL/Filament's own convention: column `c`, row `r`
 * lives at index `c*4+r`, and translation is indices 12–14. This matches
 * what `TransformManager.getTransform`/`setTransform` are documented to
 * use — see `AvatarRetargeter.applyPose`'s doc comment for the one
 * place this convention is actually load-bearing (MediaPipe's own
 * flattening order isn't confirmed against the pinned `tasks-vision`
 * release).
 */
data class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(other: Quaternion): Quaternion = Quaternion(
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
        w * other.w - x * other.x - y * other.y - z * other.z
    )

    /** Inverse, for a unit quaternion (true of everything this file
     *  produces): negate the vector part, leave w. */
    fun conjugate(): Quaternion = Quaternion(-x, -y, -z, w)

    fun normalized(): Quaternion {
        val length = sqrt(x * x + y * y + z * z + w * w)
        return if (length < 1e-8f) IDENTITY else Quaternion(x / length, y / length, z / length, w / length)
    }

    /** Rotation-only 4x4 (translation zero, scale 1) in this file's
     *  column-major convention. */
    fun toColumnMajorMatrix(): FloatArray {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return floatArrayOf(
            1f - 2f * (yy + zz), 2f * (xy + wz), 2f * (xz - wy), 0f,
            2f * (xy - wz), 1f - 2f * (xx + zz), 2f * (yz + wx), 0f,
            2f * (xz + wy), 2f * (yz - wx), 1f - 2f * (xx + yy), 0f,
            0f, 0f, 0f, 1f
        )
    }

    companion object {
        val IDENTITY = Quaternion(0f, 0f, 0f, 1f)

        /** Extracts the rotation as a quaternion from the upper-left 3x3
         *  of a column-major 4x4 [m] (Shepperd's method — numerically
         *  stable regardless of which diagonal term is largest, unlike the
         *  naive trace-only formula). Ignores translation/scale — callers
         *  that need a rigid rotation out of a transform carrying both
         *  (e.g. MediaPipe's facial transformation matrix) get exactly
         *  that, nothing else. */
        fun fromRotationColumnMajorMatrix(m: FloatArray): Quaternion {
            val m00 = m[0]; val m10 = m[1]; val m20 = m[2]
            val m01 = m[4]; val m11 = m[5]; val m21 = m[6]
            val m02 = m[8]; val m12 = m[9]; val m22 = m[10]
            val trace = m00 + m11 + m22
            val q = when {
                trace > 0f -> {
                    val s = sqrt(trace + 1f) * 2f
                    Quaternion((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
                }
                m00 > m11 && m00 > m22 -> {
                    val s = sqrt(1f + m00 - m11 - m22) * 2f
                    Quaternion(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
                }
                m11 > m22 -> {
                    val s = sqrt(1f + m11 - m00 - m22) * 2f
                    Quaternion((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
                }
                else -> {
                    val s = sqrt(1f + m22 - m00 - m11) * 2f
                    Quaternion((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
                }
            }
            return q.normalized()
        }

        /** Spherical linear interpolation, [t] in `[0,1]` — 0 is [a], 1 is
         *  [b]. Used to apply only a *fraction* of a tracked rotation to a
         *  bone (e.g. splitting a head turn between the neck and head
         *  bones) rather than dumping the whole delta onto one bone. */
        fun slerp(a: Quaternion, b: Quaternion, t: Float): Quaternion {
            var bx = b.x; var by = b.y; var bz = b.z; var bw = b.w
            // Quaternions double-cover rotations (q and -q represent the
            // same orientation) — always interpolate along the shorter arc.
            var cosHalfTheta = a.x * bx + a.y * by + a.z * bz + a.w * bw
            if (cosHalfTheta < 0f) {
                bx = -bx; by = -by; bz = -bz; bw = -bw
                cosHalfTheta = -cosHalfTheta
            }
            if (cosHalfTheta > 0.9995f) {
                // Nearly identical rotations — linear interpolation avoids
                // a division by ~0 in sinHalfTheta below and is visually
                // indistinguishable from true slerp at this distance.
                return Quaternion(
                    a.x + t * (bx - a.x), a.y + t * (by - a.y),
                    a.z + t * (bz - a.z), a.w + t * (bw - a.w)
                ).normalized()
            }
            val halfTheta = acos(cosHalfTheta.coerceIn(-1f, 1f))
            val sinHalfTheta = sqrt(1f - cosHalfTheta * cosHalfTheta)
            val ratioA = sin((1f - t) * halfTheta) / sinHalfTheta
            val ratioB = sin(t * halfTheta) / sinHalfTheta
            return Quaternion(
                a.x * ratioA + bx * ratioB, a.y * ratioA + by * ratioB,
                a.z * ratioA + bz * ratioB, a.w * ratioA + bw * ratioB
            ).normalized()
        }
    }
}

/** Column-major 4x4 matrix multiply, `a * b` (i.e. `b`'s transform applied
 *  first, then `a`'s — same convention as OpenGL/Filament). Used to
 *  compose a tracked rotation delta onto a bone's rest-pose local
 *  transform: `restLocal * deltaRotation`. */
fun multiplyColumnMajor4x4(a: FloatArray, b: FloatArray): FloatArray {
    val result = FloatArray(16)
    for (col in 0 until 4) {
        for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            result[col * 4 + row] = sum
        }
    }
    return result
}

// ---- Plain 3-float vector helpers, for limb (arm/leg) rotation --------
// Everything above works with rotations directly (a matrix, a quaternion);
// limb rotation (AvatarRetargeter.applyPose) instead starts from
// two tracked *positions* (e.g. a shoulder and an elbow) and needs to turn
// the direction between them into a rotation — these are the small,
// dependency-free building blocks for that. Every vector here is a plain
// 3-float array (x, y, z); nothing fancier is needed for this pipeline.

/** Normalized direction from 3-float point [a] to 3-float point [b]. */
fun directionBetween(a: FloatArray, b: FloatArray): FloatArray = normalizeVec3(
    floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
)

/** Minimal rotation that rotates unit vector [from] onto unit vector [to]
 *  — the standard "quaternion between two vectors" construction (derived
 *  from the half-angle identity: the quaternion halfway between two unit
 *  vectors, before normalizing, is `(cross(from,to), 1+dot(from,to))`).
 *  Used instead of a full orientation (unlike [Quaternion
 *  .fromRotationColumnMajorMatrix]) because a tracked limb only ever
 *  gives a *direction* (two joint positions), never a full 3-axis pose —
 *  there's no twist/roll information in two points, so this only ever
 *  produces the "swing" part of a rotation, never twist. Falls back to an
 *  arbitrary perpendicular axis in the (near-impossible for a real limb,
 *  but numerically real) case the two vectors point exactly opposite. */
fun quaternionBetweenDirections(from: FloatArray, to: FloatArray): Quaternion {
    val dot = from[0] * to[0] + from[1] * to[1] + from[2] * to[2]
    if (dot < -0.999999f) {
        val arbitraryAxis = if (kotlin.math.abs(from[0]) < 0.9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
        val perpendicular = normalizeVec3(crossVec3(from, arbitraryAxis))
        return Quaternion(perpendicular[0], perpendicular[1], perpendicular[2], 0f)
    }
    val cross = crossVec3(from, to)
    val s = sqrt((1f + dot) * 2f)
    val invS = 1f / s
    return Quaternion(cross[0] * invS, cross[1] * invS, cross[2] * invS, s * 0.5f).normalized()
}

private fun crossVec3(a: FloatArray, b: FloatArray) = floatArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0]
)

private fun normalizeVec3(v: FloatArray): FloatArray {
    val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    return if (length < 1e-8f) v else floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
}
