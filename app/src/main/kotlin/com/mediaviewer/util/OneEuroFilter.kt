package com.mediaviewer.util

import kotlin.math.PI
import kotlin.math.abs

/**
 * VRM pipeline step 3 (see VrmModeScreen.kt's doc comment / the handoff's
 * "VRM: what's next" section): a One Euro Filter, applied to every raw
 * landmark coordinate / blendshape score before it goes anywhere near
 * retargeting (step 4+). Without this, per-frame MediaPipe jitter goes
 * straight onto the avatar's bones and reads as a constant tremor — this
 * is what step 6's retargeting will lean on to look stable instead.
 *
 * ## The algorithm
 * Casiez, Roussel & Vogel, "1€ Filter: A Simple Speed-based Low-pass Filter
 * for Noisy Input in Interactive Systems" (CHI 2012). The core idea: a
 * plain low-pass filter always trades responsiveness for smoothness. This
 * filter instead low-pass-filters the signal's *derivative* first, and
 * uses that (the estimated speed of movement) to pick a *bigger* cutoff —
 * i.e. less smoothing, less lag — when the signal is moving fast, and a
 * smaller cutoff — more smoothing — when it's nearly still. So a fast head
 * turn stays responsive, and a still face doesn't flicker from sensor
 * noise. That's the one thing a fixed-cutoff low-pass can't do.
 *
 * ## Tuning
 * - [minCutoff] — the cutoff (Hz) used when the signal isn't moving. Lower
 *   = smoother/laggier at rest. Start around 1.0 and lower it if a resting
 *   face/hand still looks jittery.
 * - [beta] — how much speed increases the cutoff. Higher = less lag during
 *   fast motion, but less smoothing during it too. Start at 0.0 (no speed
 *   adaptation, i.e. a plain fixed low-pass) and increase in small steps
 *   (0.007, 0.01, ...) only if fast motion feels laggy.
 * - [dCutoff] — cutoff used to smooth the derivative itself. 1.0 is the
 *   value the paper uses in essentially every example and rarely needs
 *   changing.
 * These three are exactly the three knobs the paper (and every
 * implementation of it, e.g. Apple's ARKit face tracking sample) exposes —
 * no others are needed.
 *
 * Not thread-safe and holds per-signal state (last value, last derivative,
 * last timestamp), so each independent scalar being smoothed — one face
 * blendshape, one landmark's X coordinate, one bone's rotation component —
 * needs its own instance. Use [OneEuroFilterBank] below rather than
 * managing instances by hand.
 */
class OneEuroFilter(
    /** Mutable so a bank can retune live filters (smoothing slider). */
    var minCutoff: Double = 1.0,
    private val beta: Double = 0.0,
    private val dCutoff: Double = 1.0
) {
    private var lastValue: Double? = null
    private var lastDerivative: Double = 0.0
    private var lastTimestampSeconds: Double? = null

    /** Feed one new raw sample and get back the smoothed value. [timestampSeconds]
     *  must be strictly increasing between calls — pass the same clock the
     *  caller already has for the frame (MediaPipe result timestamps
     *  converted to seconds is the intended source here). */
    fun filter(value: Double, timestampSeconds: Double): Double {
        val prevTimestamp = lastTimestampSeconds
        val prevValue = lastValue
        lastTimestampSeconds = timestampSeconds
        if (prevTimestamp == null || prevValue == null) {
            // First sample ever: nothing to smooth against yet.
            lastValue = value
            return value
        }
        // Guard against a non-positive/zero dt (duplicate or out-of-order
        // timestamps) rather than dividing by ~0 and producing a derivative
        // spike; treat it as "no time passed" by clamping to a tiny minimum.
        val dt = (timestampSeconds - prevTimestamp).coerceAtLeast(1.0 / 240.0)

        // Smooth the derivative first (fixed cutoff dCutoff), so the speed
        // estimate itself isn't just raw per-frame noise.
        val rawDerivative = (value - prevValue) / dt
        val dAlpha = smoothingFactor(dt, dCutoff)
        val filteredDerivative = dAlpha * rawDerivative + (1 - dAlpha) * lastDerivative
        lastDerivative = filteredDerivative

        // Faster estimated movement -> higher cutoff -> less smoothing (less
        // lag); nearly still -> cutoff falls back to minCutoff (more smoothing).
        val cutoff = minCutoff + beta * abs(filteredDerivative)
        val alpha = smoothingFactor(dt, cutoff)
        val filteredValue = alpha * value + (1 - alpha) * prevValue
        lastValue = filteredValue
        return filteredValue
    }

    /** Drops all history — call when tracking is lost/reacquired (e.g. a
     *  face/hand disappears and reappears) so the filter doesn't smooth
     *  across a gap that wasn't actually continuous motion. */
    fun reset() {
        lastValue = null
        lastDerivative = 0.0
        lastTimestampSeconds = null
    }

    private fun smoothingFactor(dt: Double, cutoff: Double): Double {
        val r = 2 * PI * cutoff * dt
        return r / (r + 1)
    }
}

/**
 * Holds one [OneEuroFilter] per string key, created lazily on first use.
 * This is the intended way to use the filter for landmark/blendshape data:
 * a face has 52 blendshapes, a hand has 21 landmarks × 3 axes, a pose has
 * 33 × 3 — rather than hand-declaring an instance per value, key each one
 * by something stable (e.g. `"face.jawOpen"`, `"hand.left.landmark.4.x"`)
 * and let the bank manage instances.
 *
 * All filters in a bank share the same [minCutoff]/[beta]/[dCutoff] tuning.
 * If face blendshapes and body landmarks ever need different tuning (likely
 * once retargeting is live and arm motion feels laggy vs. face motion
 * feeling jittery, or vice versa), use one bank per landmarker instead of
 * one shared bank — cheap, since a bank is just a map.
 */
class OneEuroFilterBank(
    private val minCutoff: Double = 1.0,
    private val beta: Double = 0.0,
    private val dCutoff: Double = 1.0
) {
    private val filters = mutableMapOf<String, OneEuroFilter>()

    /**
     * Smoothing strength, 1 = the bank's tuned default. The resting cutoff
     * scales as `minCutoff / strength` (stronger = smoother when still);
     * `beta` is left alone, so fast movements still cut through with little
     * lag at any strength. 0 (or less) = no smoothing at all.
     */
    var strength: Double = 1.0
        set(value) {
            if (value == field) return
            field = value
            if (value > 0) filters.values.forEach { it.minCutoff = minCutoff / value }
            else filters.values.forEach { it.reset() }
        }

    /** Smooths [value] under [key], creating that key's filter on first use. */
    fun filter(key: String, value: Double, timestampSeconds: Double): Double {
        if (strength <= 0.0) return value
        return filters.getOrPut(key) { OneEuroFilter(minCutoff / strength, beta, dCutoff) }
            .filter(value, timestampSeconds)
    }

    /** Convenience for Float-valued sources (blendshape scores, landmark
     *  coordinates are all Float in MediaPipe's result types). */
    fun filter(key: String, value: Float, timestampSeconds: Double): Float =
        filter(key, value.toDouble(), timestampSeconds).toFloat()

    /** Call when a tracked subject (a face, a specific hand) is lost, so a
     *  reappearance after [key]'s filter(s) go stale doesn't get smoothed
     *  across the gap. [keyPrefix] drops every filter whose key starts with
     *  it — e.g. `"hand.left."` when the left hand drops out of frame. */
    fun resetPrefixed(keyPrefix: String) {
        filters.keys.filter { it.startsWith(keyPrefix) }.forEach { filters[it]?.reset() }
    }

    fun resetAll() = filters.values.forEach { it.reset() }
}
