package com.mediaviewer.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.AudioEffect
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
 * The feed's audio visualizer: listens to what the phone is playing and
 * turns each spectrum into [BAR_COUNT] smoothed 0..1 bar heights. Nothing
 * is recorded or kept — the platform just hands over a spectrum (it needs
 * the RECORD_AUDIO permission to do that).
 *
 * Two ways in, tried in turn until one actually carries sound:
 *  1. **The phone's output mix** (audio session 0) — sees every app at
 *     once, but many phones hand it silence: offloaded/"deep buffer" music
 *     playback, some OEM audio stacks, or another visualizer app holding it.
 *  2. **The music app's own audio session.** Players (Spotify, YouTube
 *     Music, Poweramp, Samsung Music, …) announce their session with the
 *     standard "open audio effect session" broadcast so equalizer apps can
 *     attach — [watchPlayerSessions] listens for those from app start, and
 *     the engine attaches straight to the newest one when the mix is quiet.
 *
 * Music only: during a call (phone, or VoIP apps like Discord, which put
 * the phone in "communication" mode) the bars fall flat. Silence is flat on
 * its own (an all-zero spectrum).
 *
 * [status] is a short plain-language line for the Settings row, so it's
 * visible on the phone why the bars aren't moving if they aren't.
 *
 * Reference counted — every visible bar row [acquire]s it and [release]s
 * it when it leaves the screen; the capture runs only while one is showing.
 */
actual object AudioVisualizerEngine {
    const val BAR_COUNT = 28
    private const val TAG = "AudioVisualizer"
    private const val MIX = 0

    /** Current bar heights, 0..1 — Compose state. Read it inside a draw
     *  block so new frames only redraw the bars, never recompose them. */
    actual var levels by mutableStateOf(FloatArray(BAR_COUNT))
        private set

    /** Plain-language state for the Settings row — how it's going now, or
     *  how it went the last time the bars were on screen ("" = never ran). */
    actual var status by mutableStateOf("")
        private set

    private var users = 0
    private var visualizer: Visualizer? = null
    private var attachedSession = -1
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var inCall = false
    /** True while spectra should be ignored: on a call, unless "Keep going
     *  during calls" is on AND we're attached to a music app's own session
     *  (which carries only the music, never the call). */
    @Volatile private var muted = false
    private val smoothed = FloatArray(BAR_COUNT)
    private val peak = FloatArray(BAR_COUNT) { 1f }

    /** Player sessions announced by music apps, oldest first (main thread). */
    private val playerSessions = LinkedHashMap<Int, String>()
    private var receiverRegistered = false
    /** Session ids that were attached and stayed silent while music played. */
    private val silentSessions = HashMap<Int, Long>()

    private var lastSignalMs = 0L
    private var lastCallbackMs = 0L
    private var createdAtMs = 0L
    private var nextCreateAttemptMs = 0L
    private var consecutiveFailures = 0

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val session = intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR) ?: return
            if (session <= 0) return
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    playerSessions.remove(session)
                    playerSessions[session] = pkg
                    silentSessions.remove(session)
                    while (playerSessions.size > 8) playerSessions.remove(playerSessions.keys.first())
                    // A new song/player just started: if we're showing and
                    // the current source is quiet, try the new session now.
                    if (users > 0 && SystemClock.uptimeMillis() - lastSignalMs > 1_000) {
                        nextCreateAttemptMs = 0L
                        teardownVisualizer()
                    }
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    playerSessions.remove(session)
                    silentSessions.remove(session)
                    if (attachedSession == session) teardownVisualizer()
                }
            }
        }
    }

    /** Call once at app start: remembers the audio sessions music apps
     *  announce, so the visualizer can attach to them later even if the
     *  music started before the feed was opened. Harmless if nothing ever
     *  broadcasts. */
    fun watchPlayerSessions(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        runCatching {
            ContextCompat.registerReceiver(context.applicationContext, sessionReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }.onFailure { Log.w(TAG, "Couldn't listen for player sessions", it) }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (users <= 0) return
            val am = audioManager
            val onCall = am != null && runCatching {
                val mode = am.mode
                mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
                    mode == AudioManager.MODE_RINGTONE
            }.getOrDefault(false)
            val callMode = UiToggles.visualizerCallMode
            // "All Audio" during calls: carry on exactly as if there were no call.
            inCall = onCall && callMode != UiToggles.VisualizerCallMode.ALL_AUDIO
            val now = SystemClock.uptimeMillis()
            val ctx = appContext
            if (ctx == null || !hasPermission(ctx)) {
                status = "Needs the microphone permission (nothing is recorded)."
                teardownVisualizer()
                decay()
                main.postDelayed(this, 1_000)
                return
            }
            // On a call with "Keep going during calls": the phone's overall
            // output would carry the call's voices too, so only a music
            // app's own session is allowed.
            val callSafe = inCall && callMode == UiToggles.VisualizerCallMode.MUSIC_ONLY
            if (callSafe && attachedSession == MIX) teardownVisualizer()
            if (callSafe && visualizer == null && pickPlayerSession(now) == null) {
                muted = true
                status = "On a call — waiting for your music app's own audio. Try pausing and playing your music."
                decay()
                main.postDelayed(this, 400)
                return
            }
            muted = inCall && !(callSafe && attachedSession > 0)
            val v = visualizer
            val enabled = v != null && runCatching { v.getEnabled() }.getOrDefault(false)
            val playing = am != null && runCatching { am.isMusicActive }.getOrDefault(false)
            val sinceCreate = now - createdAtMs
            // Attached, music is playing, but nothing's coming through: this
            // source is a dud on this phone (or the output changed under it).
            val stalled = v != null && playing && (!inCall || callSafe) && sinceCreate > 2_500 &&
                (now - lastCallbackMs > 2_500 || now - lastSignalMs > 3_000)
            if (stalled) {
                silentSessions[attachedSession] = now
                Log.i(TAG, "Session $attachedSession silent while music plays — trying another source")
            }
            if ((v == null || !enabled || stalled) && now >= nextCreateAttemptMs) {
                teardownVisualizer()
                val next = if (callSafe) pickPlayerSession(now) else pickSession(now)
                if (next != null) createVisualizer(next)
            }
            muted = inCall && !(callSafe && attachedSession > 0)
            status = when {
                inCall && !callSafe -> "Paused during calls."
                callSafe && visualizer == null -> "On a call — waiting for your music app's own audio. Try pausing and playing your music."
                callSafe && now - lastSignalMs < 1_500 -> "Listening to your music app only (on a call)."
                onCall && now - lastSignalMs < 1_500 -> "Listening to all audio, call included."
                visualizer == null -> "Couldn't reach your phone's audio. Try pausing and playing your music."
                now - lastSignalMs < 1_500 -> "Listening."
                playing -> "Music is playing but no sound is reaching the bars yet…"
                else -> "Waiting for music."
            }
            if (muted || visualizer == null) decay()
            main.postDelayed(this, 400)
        }
    }

    /** Which session to attach to next: the output mix unless it has
     *  recently proved silent, then the newest announced player session
     *  that hasn't; if everything has, start over from the mix. */
    private fun pickSession(now: Long): Int {
        silentSessions.entries.removeAll { now - it.value > 30_000 }
        if (MIX !in silentSessions) return MIX
        val candidate = playerSessions.keys.reversed().firstOrNull { it !in silentSessions }
        if (candidate != null) return candidate
        silentSessions.clear()
        return MIX
    }

    /** The newest announced music-app session that hasn't proved silent. */
    private fun pickPlayerSession(now: Long): Int? {
        silentSessions.entries.removeAll { now - it.value > 30_000 }
        return playerSessions.keys.reversed().firstOrNull { it !in silentSessions }
            ?: playerSessions.keys.lastOrNull()?.also { silentSessions.remove(it) }
    }

    actual fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Main thread. */
    actual fun acquire(context: Context) {
        users++
        if (users > 1) return
        main.removeCallbacks(releaseLater)
        appContext = context.applicationContext
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        watchPlayerSessions(context)
        nextCreateAttemptMs = 0L
        consecutiveFailures = 0
        main.removeCallbacks(poll)
        // A short delay: the previous post's row releases a frame before
        // the next one acquires, and re-creating instantly can be refused
        // while the audio server is still tearing the old one down.
        main.postDelayed(poll, 50)
    }

    /** Main thread. */
    actual fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        main.removeCallbacks(poll)
        // Keep the capture for a moment: swiping to the next post releases
        // and re-acquires within a frame or two.
        main.postDelayed(releaseLater, 600)
    }

    private val releaseLater = Runnable {
        if (users > 0) {
            main.removeCallbacks(poll); main.post(poll)
            return@Runnable
        }
        teardownVisualizer()
        smoothed.fill(0f)
        levels = FloatArray(BAR_COUNT)
        // [status] is kept: Settings shows how it went last time.
    }

    private fun createVisualizer(session: Int) {
        val now = SystemClock.uptimeMillis()
        main.removeCallbacks(releaseLater)
        visualizer = runCatching {
            Visualizer(session).apply {
                runCatching { setEnabled(false) }
                val range = Visualizer.getCaptureSizeRange()
                setCaptureSize(min(max(512, range[0]), range[1]))
                runCatching { setScalingMode(Visualizer.SCALING_MODE_NORMALIZED) }
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        if (waveform != null) onWaveform(waveform)
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft != null) onFft(fft)
                    }
                }, min(Visualizer.getMaxCaptureRate(), 20_000), true, true)
                setEnabled(true)
            }
        }.onFailure { Log.w(TAG, "Couldn't attach to audio session $session", it) }.getOrNull()
        if (visualizer != null) {
            attachedSession = session
            createdAtMs = now
            lastCallbackMs = now
            consecutiveFailures = 0
            nextCreateAttemptMs = now + 1_000
        } else {
            attachedSession = -1
            // Don't hammer the audio server if it keeps refusing.
            if (session != MIX || playerSessions.isEmpty()) consecutiveFailures++
            silentSessions[session] = now
            nextCreateAttemptMs = now + min(10_000L, 1_500L * (1 + consecutiveFailures))
        }
    }

    private fun teardownVisualizer() {
        visualizer?.let { v -> runCatching { v.setEnabled(false) }; runCatching { v.release() } }
        visualizer = null
        attachedSession = -1
    }

    private fun decay() {
        var any = false
        for (i in 0 until BAR_COUNT) {
            smoothed[i] *= 0.6f
            if (smoothed[i] > 0.01f) any = true else smoothed[i] = 0f
        }
        if (!any && levels.all { it == 0f }) return
        levels = if (any) smoothed.copyOf() else FloatArray(BAR_COUNT)
    }

    /** The waveform only proves there's sound (some phones deliver an
     *  all-zero FFT for a moment after attaching, but real samples). */
    private fun onWaveform(wave: ByteArray) {
        val now = SystemClock.uptimeMillis()
        lastCallbackMs = now
        if (muted || wave.isEmpty()) return
        // 8-bit PCM. Judged by the spread of the samples, not their offset
        // from 128, because some phones hand back a flat all-zero buffer
        // when nothing is actually coming through.
        var lo = 255
        var hi = 0
        var k = 0
        while (k < wave.size) {
            val v = wave[k].toInt() and 0xFF
            if (v < lo) lo = v
            if (v > hi) hi = v
            k += 2
        }
        if (hi - lo >= 4) lastSignalMs = now
    }

    /** FFT bytes → log-spaced bands (≈40 Hz … 16 kHz), each normalised by
     *  a slowly falling peak so quiet and loud songs both fill the bars.
     *  Delivered on the main thread (the Visualizer was created there). */
    private fun onFft(fft: ByteArray) {
        if (users <= 0) return
        val now = SystemClock.uptimeMillis()
        lastCallbackMs = now
        if (muted) return
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
        // Silence stays silence: don't hand the bars a "new" all-zero
        // array ~20 times a second (each one would redraw them for nothing).
        val silent = smoothed.all { it <= 0.001f }
        if (silent && levels.all { it <= 0.001f }) return
        levels = smoothed.copyOf()
    }
}
