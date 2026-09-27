package com.mediaviewer.util

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The feed's audio visualizer: taps the phone's mixed audio output (the
 * global audio session, which needs the RECORD_AUDIO permission — nothing
 * is recorded or kept, the platform just hands over a spectrum) and turns
 * each FFT into [BAR_COUNT] smoothed 0..1 bar heights.
 *
 * Music only: while a call is going on (phone, or VoIP apps like Discord,
 * which switch the phone into "communication" audio mode) the bars fall
 * flat. Silence is flat on its own (an all-zero spectrum), so there's no
 * separate "is music playing" gate any more — that check
 * (AudioManager.isMusicActive) missed players that don't use the music
 * stream and left the bars dead.
 *
 * Why it used to stay flat: the output-mix visualizer is fragile. It can
 * fail to attach (another app holding one, the audio server not ready),
 * and it silently stops delivering data when the output changes under it
 * (headphones/Bluetooth connecting, offloaded playback starting). It's now
 * re-created whenever it's missing, disabled, or has gone quiet while the
 * phone says something is playing, and it starts as soon as permission is
 * granted instead of only on the first bar row ever shown.
 *
 * Reference counted — every visible bar row [acquire]s it and [release]s
 * it when it leaves the screen; the capture runs only while at least one
 * is showing.
 */
object AudioVisualizerEngine {
    const val BAR_COUNT = 28
    private const val TAG = "AudioVisualizer"

    /** Current bar heights, 0..1 — Compose state. Read it inside a draw
     *  block so new frames only redraw the bars, never recompose them. */
    var levels by mutableStateOf(FloatArray(BAR_COUNT))
        private set

    private var users = 0
    private var visualizer: Visualizer? = null
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var inCall = false
    private val smoothed = FloatArray(BAR_COUNT)
    private val peak = FloatArray(BAR_COUNT) { 1f }

    /** Last time a capture with any real signal arrived. */
    private var lastSignalMs = 0L
    /** Last time any capture callback arrived at all. */
    private var lastCallbackMs = 0L
    private var createdAtMs = 0L
    private var nextCreateAttemptMs = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (users <= 0) return
            val am = audioManager
            inCall = am != null && runCatching {
                val mode = am.mode
                mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
                    mode == AudioManager.MODE_RINGTONE
            }.getOrDefault(false)
            val now = SystemClock.uptimeMillis()
            val ctx = appContext
            val v = visualizer
            if (ctx != null && hasPermission(ctx)) {
                val enabled = v != null && runCatching { v.getEnabled() }.getOrDefault(false)
                val playing = am != null && runCatching { am.isMusicActive }.getOrDefault(false)
                // Something is playing but the capture has gone silent or
                // stopped calling back: the output changed under it.
                val stalled = v != null && playing && !inCall && now - createdAtMs > 2_500 &&
                    (now - lastCallbackMs > 2_500 || now - lastSignalMs > 4_000)
                if ((v == null || !enabled || stalled) && now >= nextCreateAttemptMs) {
                    if (stalled) Log.i(TAG, "Capture went quiet while audio is playing — re-attaching")
                    teardownVisualizer()
                    createVisualizer()
                }
            }
            if (inCall || visualizer == null) decay()
            main.postDelayed(this, 400)
        }
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Main thread. */
    fun acquire(context: Context) {
        users++
        if (users > 1) return
        appContext = context.applicationContext
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        nextCreateAttemptMs = 0L
        if (hasPermission(context)) createVisualizer()
        main.removeCallbacks(poll)
        main.post(poll)
    }

    /** Main thread. */
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        main.removeCallbacks(poll)
        teardownVisualizer()
        smoothed.fill(0f)
        levels = FloatArray(BAR_COUNT)
    }

    private fun createVisualizer() {
        val now = SystemClock.uptimeMillis()
        // Don't hammer the audio server if it keeps refusing.
        nextCreateAttemptMs = now + 3_000
        visualizer = runCatching {
            Visualizer(0).apply {
                val range = Visualizer.getCaptureSizeRange()
                setCaptureSize(min(max(512, range[0]), range[1]))
                runCatching { setScalingMode(Visualizer.SCALING_MODE_NORMALIZED) }
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft != null) onFft(fft)
                    }
                }, min(Visualizer.getMaxCaptureRate(), 20_000), false, true)
                setEnabled(true)
            }
        }.onFailure { Log.w(TAG, "Couldn't attach to the output mix", it) }.getOrNull()
        if (visualizer != null) {
            createdAtMs = now
            lastCallbackMs = now
            lastSignalMs = now
        }
    }

    private fun teardownVisualizer() {
        visualizer?.let { v -> runCatching { v.setEnabled(false) }; runCatching { v.release() } }
        visualizer = null
    }

    private fun decay() {
        var any = false
        for (i in 0 until BAR_COUNT) {
            smoothed[i] *= 0.6f
            if (smoothed[i] > 0.01f) any = true else smoothed[i] = 0f
        }
        levels = if (any) smoothed.copyOf() else FloatArray(BAR_COUNT)
    }

    /** FFT bytes → log-spaced bands (≈40 Hz … 16 kHz), each normalised by
     *  a slowly falling peak so quiet and loud songs both fill the bars.
     *  Delivered on the main thread (the Visualizer was created there). */
    private fun onFft(fft: ByteArray) {
        if (users <= 0) return
        val now = SystemClock.uptimeMillis()
        lastCallbackMs = now
        if (inCall) return
        val bins = fft.size / 2
        if (bins < 8) return
        val out = FloatArray(BAR_COUNT)
        var energy = 0f
        val lo = 2.0; val hi = (bins - 1).toDouble()
        for (b in 0 until BAR_COUNT) {
            val start = (lo * (hi / lo).pow(b.toDouble() / BAR_COUNT)).toInt().coerceIn(1, bins - 1)
            val end = (lo * (hi / lo).pow((b + 1).toDouble() / BAR_COUNT)).toInt().coerceIn(start + 1, bins)
            var sum = 0f
            for (k in start until end) {
                val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                sum += sqrt(re * re + im * im)
            }
            val mag = sum / (end - start)
            energy += mag
            val db = (ln(1f + mag) / ln(129f)).coerceIn(0f, 1f)
            peak[b] = max(peak[b] * 0.995f, max(db, 0.25f))
            out[b] = (db / peak[b]).coerceIn(0f, 1f)
        }
        if (energy > 0.5f) lastSignalMs = now
        for (i in 0 until BAR_COUNT) {
            val target = out[i]
            // Fast attack, slower fall.
            smoothed[i] = if (target > smoothed[i]) smoothed[i] + (target - smoothed[i]) * 0.6f
                else smoothed[i] + (target - smoothed[i]) * 0.25f
        }
        levels = smoothed.copyOf()
    }
}
