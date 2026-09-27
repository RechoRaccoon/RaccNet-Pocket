package com.mediaviewer.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * VRM mode's "Voice pitch" setting: a small real-time pitch shifter for
 * 16-bit mono mic audio (recordings and live streams).
 *
 * Classic two-tap delay-line shifter: the input is written into a ring
 * buffer and read back by two taps whose delay sweeps through a short
 * window at a rate that makes the read speed equal the pitch ratio. The
 * taps sit half a window apart and are cross-faded (sin²/cos²) so each one
 * is silent at the moment it jumps back. No FFT, constant latency (one
 * window), cheap enough for the audio thread. It's a "fun voice" effect,
 * not a studio formant-preserving shifter — deep voices sound deep, high
 * voices sound chipmunk-y, as intended.
 *
 * Not thread-safe: one instance per audio thread. [semitones] may be
 * changed from another thread at any time (it's only read).
 */
class PitchShifter(sampleRate: Int) {
    /** -12 … +12; 0 = passthrough. */
    @Volatile var semitones: Float = 0f

    private val window = (sampleRate * 0.045f).toInt().coerceAtLeast(256)
    private val size = window * 2 + 8
    private val ring = FloatArray(size)
    private var write = 0
    private var phase = 0f

    val isActive: Boolean get() = abs(semitones) >= 0.05f

    /** Pitch-shifts [samples] (first [count] entries) in place. */
    fun process(samples: ShortArray, count: Int) {
        val st = semitones
        val ratio = 2f.pow(st / 12f)
        val active = abs(st) >= 0.05f
        val step = (1f - ratio) / window
        for (i in 0 until count) {
            val x = samples[i].toFloat()
            ring[write] = x
            if (active) {
                val pA = phase
                val pB = (phase + 0.5f).let { if (it >= 1f) it - 1f else it }
                val a = read(pA * window)
                val b = read(pB * window)
                val g = sin(PI.toFloat() * pA)
                val gA = g * g
                val y = a * gA + b * (1f - gA)
                samples[i] = y.coerceIn(-32768f, 32767f).toInt().toShort()
                phase += step
                if (phase >= 1f) phase -= 1f
                if (phase < 0f) phase += 1f
            }
            write++
            if (write >= size) write = 0
        }
    }

    /** Linear-interpolated sample [delay] samples behind the write head. */
    private fun read(delay: Float): Float {
        var pos = write - delay
        while (pos < 0f) pos += size
        val i0 = pos.toInt() % size
        val i1 = (i0 + 1) % size
        val frac = pos - pos.toInt()
        return ring[i0] + (ring[i1] - ring[i0]) * frac
    }
}
