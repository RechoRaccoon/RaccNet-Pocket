package com.mediaviewer.util

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
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
 * which switch the phone into "communication" audio mode) or nothing is
 * playing on the music stream, the bars fall flat.
 *
 * Reference counted — every visible bar row [acquire]s it and [release]s
 * it when it leaves the screen; the capture runs only while at least one
 * is showing.
 */
object AudioVisualizerEngine {
    const val BAR_COUNT = 28

    /** Current bar heights, 0..1 — Compose state, read by the bar row. */
    var levels by mutableStateOf(FloatArray(BAR_COUNT))
        private set

    private var users = 0
    private var visualizer: Visualizer? = null
    private var audioManager: AudioManager? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var musicPlaying = false
    private val smoothed = FloatArray(BAR_COUNT)
    private val peak = FloatArray(BAR_COUNT) { 1f }

    private val poll = object : Runnable {
        override fun run() {
            val am = audioManager
            musicPlaying = am != null && runCatching {
                am.isMusicActive && am.mode == AudioManager.MODE_NORMAL
            }.getOrDefault(false)
            if (!musicPlaying) decay()
            if (users > 0) main.postDelayed(this, 400)
        }
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun acquire(context: Context) {
        users++
        if (users > 1) return
        if (!hasPermission(context)) return
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        visualizer = runCatching {
            Visualizer(0).apply {
                val range = Visualizer.getCaptureSizeRange()
                setCaptureSize(min(max(512, range[0]), range[1]))
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft != null) onFft(fft)
                    }
                }, min(Visualizer.getMaxCaptureRate(), 20_000), false, true)
                setEnabled(true)
            }
        }.onFailure { Log.w("AudioVisualizer", "Couldn't attach to the output mix", it) }.getOrNull()
        main.post(poll)
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        main.removeCallbacks(poll)
        visualizer?.let { v -> runCatching { v.setEnabled(false) }; runCatching { v.release() } }
        visualizer = null
        smoothed.fill(0f)
        levels = FloatArray(BAR_COUNT)
    }

    private fun decay() {
        var any = false
        for (i in 0 until BAR_COUNT) {
            smoothed[i] *= 0.6f
            if (smoothed[i] > 0.01f) any = true else smoothed[i] = 0f
        }
        levels = smoothed.copyOf()
        if (!any) levels = FloatArray(BAR_COUNT)
    }

    /** FFT bytes → log-spaced bands (≈40 Hz … 16 kHz), each normalised by
     *  a slowly falling peak so quiet and loud songs both fill the bars. */
    private fun onFft(fft: ByteArray) {
        if (!musicPlaying) return
        val bins = fft.size / 2
        if (bins < 8) return
        val out = FloatArray(BAR_COUNT)
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
            val db = (ln(1f + mag) / ln(129f)).coerceIn(0f, 1f)
            peak[b] = max(peak[b] * 0.995f, max(db, 0.25f))
            out[b] = (db / peak[b]).coerceIn(0f, 1f)
        }
        main.post {
            for (i in 0 until BAR_COUNT) {
                val target = out[i]
                // Fast attack, slower fall.
                smoothed[i] = if (target > smoothed[i]) smoothed[i] + (target - smoothed[i]) * 0.6f
                    else smoothed[i] + (target - smoothed[i]) * 0.25f
            }
            levels = smoothed.copyOf()
        }
    }
}
