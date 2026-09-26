package com.mediaviewer.stream

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Live streaming for VRM mode: hardware H.264 + AAC, pushed over RTMP(S)
 * with [RtmpPublisher].
 *
 * The video encoder's input is a [Surface]; the caller renders the avatar
 * into it (VRM mode points a second Filament swap chain at it, the same
 * way video recording works), so no pixels are ever copied on the CPU.
 * The mic goes through AudioRecord → AAC; muting sends silence rather than
 * dropping the track (services like YouTube warn/stall without audio).
 *
 * Tuned for cheap phones first:
 *  - [StreamQuality.auto] picks 432p/24, 576p/30 or 720p/30 from RAM, CPU
 *    cores, low-RAM flag and Android's media performance class, then
 *    checks the hardware encoder actually supports that size/rate.
 *  - Constant bitrate when the encoder supports it (steady for networks
 *    and what ingest servers expect), 2 s keyframes (Twitch/YouTube max),
 *    realtime priority, no B-frames.
 *  - When the upload can't keep up, bitrate steps down (to 40% of target)
 *    and climbs back when the backlog clears; if it still backs up, queued
 *    P-frames are dropped and a keyframe requested, so the stream stays
 *    live instead of falling minutes behind.
 *  - A dropped connection is retried 3 times (2/4/8 s) without stopping
 *    the encoders, then gives up cleanly.
 */
class LiveStreamer(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        /** Called on a background thread. */
        fun onStateChanged(state: State, message: String?)
    }

    enum class State { IDLE, CONNECTING, LIVE, RECONNECTING, ENDED, FAILED }

    class Config(
        val width: Int,
        val height: Int,
        val fps: Int,
        val videoBitrate: Int,
        val audioBitrate: Int = 128_000,
        val sampleRate: Int = 44_100
    )

    @Volatile var state = State.IDLE
        private set
    @Volatile var micMuted = false

    /** Encoder input surface; valid between a successful [start] and [stop]. */
    var inputSurface: Surface? = null
        private set
    var config: Config? = null
        private set

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var publisher: RtmpPublisher? = null
    private val running = AtomicBoolean(false)
    /** Set by [stop]; a [start] still connecting sees it and backs out. */
    @Volatile private var stopRequested = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var monitorThread: Thread? = null
    private var serverUrl = ""
    private var streamKey = ""
    private var startUs = 0L
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var asc: ByteArray? = null
    @Volatile private var currentBitrate = 0
    @Volatile private var congested = false
    @Volatile private var lastCongestionMs = 0L
    private val lastVideoTs = AtomicLong(-1)
    private val lastAudioTs = AtomicLong(-1)

    /** Rough upload rate for the UI, bits per second. */
    @Volatile var measuredBps = 0L
        private set
    val droppedFrames: Long get() = publisher?.droppedFrames?.get() ?: 0L

    /**
     * Connects, then starts the encoders. Blocking — call off the main
     * thread. Returns an error message, or null on success (at which point
     * [inputSurface] is ready to render into).
     */
    fun start(serverUrl: String, streamKey: String, cfg: Config, withMic: Boolean): String? {
        if (running.get()) return null
        stopRequested = false
        this.serverUrl = serverUrl
        this.streamKey = streamKey
        config = cfg
        setState(State.CONNECTING, null)
        val pub = RtmpPublisher(publisherListener)
        try {
            pub.connect(serverUrl, streamKey)
        } catch (e: Exception) {
            Log.e(TAG, "RTMP connect failed", e)
            setState(State.FAILED, e.message ?: "Couldn't connect to the server")
            return e.message ?: "Couldn't connect to the server"
        }
        if (stopRequested) {
            // Cancelled while connecting.
            pub.close()
            setState(State.ENDED, null)
            return "Cancelled"
        }
        publisher = pub
        pub.sendMetadata(cfg.width, cfg.height, cfg.fps, cfg.videoBitrate / 1000, cfg.audioBitrate / 1000, cfg.sampleRate, true)
        try {
            startVideoEncoder(cfg)
            startAudio(cfg, withMic)
        } catch (e: Exception) {
            Log.e(TAG, "Encoder setup failed", e)
            releaseEncoders()
            pub.close()
            publisher = null
            setState(State.FAILED, "This phone's encoder couldn't start (${e.message})")
            return "This phone's encoder couldn't start"
        }
        startUs = System.nanoTime() / 1000
        running.set(true)
        videoThread = Thread({ drainVideo() }, "live-video").also { it.priority = Thread.MAX_PRIORITY - 1; it.start() }
        audioThread = Thread({ audioLoop() }, "live-audio").also { it.priority = Thread.MAX_PRIORITY; it.start() }
        monitorThread = Thread({ monitorLoop() }, "live-monitor").also { it.isDaemon = true; it.start() }
        setState(State.LIVE, null)
        return null
    }

    /** Ends the stream and frees everything. Safe from any thread, any time. */
    fun stop() {
        stopRequested = true
        val was = running.getAndSet(false)
        audioThread?.interrupt(); videoThread?.interrupt(); monitorThread?.interrupt()
        runCatching { audioThread?.join(1000) }
        runCatching { videoThread?.join(1000) }
        publisher?.close()
        publisher = null
        releaseEncoders()
        if (was || state == State.CONNECTING || state == State.RECONNECTING) setState(State.ENDED, null)
    }

    /** Ask for a keyframe (e.g. right after reconnecting). */
    fun requestKeyframe() {
        runCatching { videoCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    // ───────────────────────────────────────────────────────────── video

    private fun startVideoEncoder(cfg: Config) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = runCatching { codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull()
        fun format(withProfile: Boolean, cbr: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Keep sending frames if rendering hiccups, so players don't stall.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / cfg.fps * 3)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (withProfile) {
                // High profile = noticeably better picture per bit; only when
                // the hardware lists it, and without B-frames.
                val levels = caps?.profileLevels.orEmpty()
                val high = levels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }.maxByOrNull { it.level }
                val main = levels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain }.maxByOrNull { it.level }
                (high ?: main)?.let {
                    setInteger(MediaFormat.KEY_PROFILE, it.profile)
                    setInteger(MediaFormat.KEY_LEVEL, min(it.level, MediaCodecInfo.CodecProfileLevel.AVCLevel41))
                }
            }
        }
        val cbrOk = runCatching {
            caps?.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
        }.getOrDefault(false)
        // Most ambitious first; fall back to the plainest config that works.
        // High/Main profile only where B-frames can be switched off
        // explicitly (API 29+); older phones get the encoder's default
        // (Baseline), which never uses them.
        val profileOk = Build.VERSION.SDK_INT >= 29
        val attempts = listOf(profileOk to cbrOk, false to cbrOk, false to false).distinct()
        var configured = false
        var lastError: Exception? = null
        for ((profile, cbr) in attempts) {
            try {
                codec.configure(format(profile, cbr), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configured = true
                break
            } catch (e: Exception) {
                lastError = e
                runCatching { codec.reset() }
            }
        }
        if (!configured) { codec.release(); throw lastError ?: IllegalStateException("configure failed") }
        inputSurface = codec.createInputSurface()
        codec.start()
        videoCodec = codec
        currentBitrate = cfg.videoBitrate
    }

    private fun drainVideo() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val s = f.getByteBuffer("csd-0")?.let { bytesOf(it) }?.let { AnnexB.split(it).firstOrNull() }
                        val p = f.getByteBuffer("csd-1")?.let { bytesOf(it) }?.let { AnnexB.split(it).firstOrNull() }
                        if (s != null && p != null) { sps = s; pps = p; publisher?.sendVideoConfig(s, p) }
                    }
                    index >= 0 -> {
                        val buf = codec.getOutputBuffer(index)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val data = ByteArray(info.size).also { buf.get(it) }
                            val nals = AnnexB.split(data)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                val s = nals.firstOrNull { AnnexB.nalType(it) == AnnexB.NAL_SPS }
                                val p = nals.firstOrNull { AnnexB.nalType(it) == AnnexB.NAL_PPS }
                                if (s != null && p != null && (sps == null || !s.contentEquals(sps))) {
                                    sps = s; pps = p; publisher?.sendVideoConfig(s, p)
                                }
                            } else {
                                val frame = nals.filter {
                                    val t = AnnexB.nalType(it)
                                    t != AnnexB.NAL_SPS && t != AnnexB.NAL_PPS && t != AnnexB.NAL_AUD
                                }
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ||
                                    frame.any { AnnexB.nalType(it) == AnnexB.NAL_IDR }
                                if (frame.isNotEmpty()) publisher?.sendVideo(frame, monotonicTs(info.presentationTimeUs, lastVideoTs), key)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "Video encoder failed", e)
                fail("The video encoder stopped (${e.message})")
            }
        }
    }

    // ───────────────────────────────────────────────────────────── audio

    @SuppressLint("MissingPermission")
    private fun startAudio(cfg: Config, withMic: Boolean) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, cfg.sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec
        if (withMic) {
            val minBuf = AudioRecord.getMinBufferSize(cfg.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, cfg.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192) * 2)
            }.getOrNull()
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                runCatching { record.startRecording() }.onSuccess { audioRecord = record }.onFailure { record.release() }
            } else record?.release()
        }
    }

    /** Mic (or silence) → AAC encoder → RTMP, 1024 samples per frame. */
    private fun audioLoop() {
        val codec = audioCodec ?: return
        val cfg = config ?: return
        val record = audioRecord
        val mono = ShortArray(1024)
        val stereo = ByteArray(1024 * 4)
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        val t0 = System.nanoTime() / 1000
        try {
            while (running.get()) {
                var n = 1024
                if (record != null) {
                    n = record.read(mono, 0, 1024)
                    if (n <= 0) { Thread.sleep(5); continue }
                } else {
                    // No mic: pace silence in real time.
                    val due = t0 + samples * 1_000_000L / cfg.sampleRate
                    val wait = due - System.nanoTime() / 1000
                    if (wait > 1000) Thread.sleep(wait / 1000)
                }
                val silent = record == null || micMuted
                for (i in 0 until n) {
                    val v = if (silent) 0 else mono[i].toInt()
                    val lo = (v and 0xFF).toByte(); val hi = ((v shr 8) and 0xFF).toByte()
                    stereo[i * 4] = lo; stereo[i * 4 + 1] = hi; stereo[i * 4 + 2] = lo; stereo[i * 4 + 3] = hi
                }
                val ptsUs = t0 + samples * 1_000_000L / cfg.sampleRate
                samples += n
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)
                    if (inBuf != null) {
                        inBuf.clear()
                        val len = min(n * 4, inBuf.remaining())
                        inBuf.put(stereo, 0, len)
                        codec.queueInputBuffer(inIndex, 0, len, ptsUs, 0)
                    }
                }
                drainAudio(codec, info)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "Audio failed", e)
                fail("Audio stopped (${e.message})")
            }
        }
    }

    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.getByteBuffer("csd-0")?.let { bytesOf(it) }?.let { asc = it; publisher?.sendAudioConfig(it) }
                continue
            }
            if (index < 0) return
            val buf = codec.getOutputBuffer(index)
            if (buf != null && info.size > 0) {
                buf.position(info.offset); buf.limit(info.offset + info.size)
                val data = ByteArray(info.size).also { buf.get(it) }
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    if (asc == null) { asc = data; publisher?.sendAudioConfig(data) }
                } else {
                    publisher?.sendAudio(data, monotonicTs(info.presentationTimeUs, lastAudioTs))
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    // ───────────────────────────────────────────────────────────── network

    private val publisherListener = object : RtmpPublisher.Listener {
        override fun onDisconnected(reason: String) {
            if (!running.get()) return
            Thread({ reconnect(reason) }, "live-reconnect").start()
        }
        override fun onCongestion(queuedMs: Long) {
            congested = true
            lastCongestionMs = System.currentTimeMillis()
            val cfg = config ?: return
            setBitrate(max((cfg.videoBitrate * 0.4).toInt(), (currentBitrate * 0.7).toInt()))
        }
        override fun onCongestionCleared() { congested = false }
        override fun onKeyframeNeeded() = requestKeyframe()
    }

    private fun reconnect(reason: String) {
        Log.w(TAG, "Stream dropped: $reason — reconnecting")
        publisher = null
        setState(State.RECONNECTING, reason)
        for (delaySec in longArrayOf(2, 4, 8)) {
            try { Thread.sleep(delaySec * 1000) } catch (_: InterruptedException) { return }
            if (!running.get()) return
            val pub = RtmpPublisher(publisherListener)
            try {
                pub.connect(serverUrl, streamKey)
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect failed", e)
                continue
            }
            val cfg = config ?: return
            pub.sendMetadata(cfg.width, cfg.height, cfg.fps, cfg.videoBitrate / 1000, cfg.audioBitrate / 1000, cfg.sampleRate, true)
            val s = sps; val p = pps
            if (s != null && p != null) pub.sendVideoConfig(s, p)
            asc?.let { pub.sendAudioConfig(it) }
            publisher = pub
            requestKeyframe()
            setState(State.LIVE, null)
            return
        }
        fail("Lost connection to the server: $reason")
    }

    /** Recovers bitrate after congestion and measures upload speed. */
    private fun monitorLoop() {
        var lastBytes = 0L
        var lastT = System.currentTimeMillis()
        try {
            while (running.get()) {
                Thread.sleep(2000)
                val pub = publisher
                val now = System.currentTimeMillis()
                val bytes = pub?.bytesSent?.get() ?: 0L
                if (pub != null && bytes >= lastBytes) measuredBps = (bytes - lastBytes) * 8_000 / max(1L, now - lastT)
                lastBytes = bytes; lastT = now
                val cfg = config ?: continue
                if (!congested && now - lastCongestionMs > 8_000 && currentBitrate < cfg.videoBitrate) {
                    setBitrate(min(cfg.videoBitrate, (currentBitrate * 1.15).toInt()))
                }
            }
        } catch (_: InterruptedException) {}
    }

    private fun setBitrate(bps: Int) {
        if (bps == currentBitrate) return
        currentBitrate = bps
        runCatching { videoCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) }
        Log.i(TAG, "Video bitrate → ${bps / 1000} kbps")
    }

    // ───────────────────────────────────────────────────────────── util

    /** Encoder µs timestamps → stream ms from the start, never going back. */
    private fun monotonicTs(ptsUs: Long, last: AtomicLong): Long {
        var ts = max(0L, (ptsUs - startUs) / 1000)
        val prev = last.get()
        if (ts <= prev) ts = prev + 1
        last.set(ts)
        return ts
    }

    private fun fail(message: String) {
        if (!running.get()) return
        Thread({ stop(); setState(State.FAILED, message) }, "live-stop").start()
    }

    private fun setState(s: State, message: String?) {
        state = s
        runCatching { listener.onStateChanged(s, message) }
    }

    private fun releaseEncoders() {
        audioRecord?.let { r -> runCatching { r.stop() }; runCatching { r.release() } }
        audioRecord = null
        videoCodec?.let { c -> runCatching { c.stop() }; runCatching { c.release() } }
        videoCodec = null
        audioCodec?.let { c -> runCatching { c.stop() }; runCatching { c.release() } }
        audioCodec = null
        inputSurface?.let { runCatching { it.release() } }
        inputSurface = null
        sps = null; pps = null; asc = null
        lastVideoTs.set(-1); lastAudioTs.set(-1)
    }

    private fun bytesOf(b: java.nio.ByteBuffer): ByteArray {
        val d = b.duplicate(); d.rewind()
        return ByteArray(d.remaining()).also { d.get(it) }
    }

    companion object { private const val TAG = "LiveStreamer" }
}

/** Stream quality presets (portrait 9:16 — VRM mode is a portrait screen). */
enum class StreamQuality(val label: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int) {
    LOW("432p", 432, 768, 24, 1_000_000),
    MEDIUM("576p", 576, 1024, 30, 2_000_000),
    HIGH("720p", 720, 1280, 30, 3_500_000);

    fun toConfig() = LiveStreamer.Config(width, height, fps, bitrate)

    companion object {
        /** The best preset this phone should comfortably sustain while also
         *  running face tracking and rendering the avatar. */
        fun auto(context: Context): StreamQuality {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val totalGb = mem.totalMem / (1024.0 * 1024 * 1024)
            val cores = Runtime.getRuntime().availableProcessors()
            val perfClass = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
            val wanted = when {
                am?.isLowRamDevice == true || totalGb in 0.1..3.2 || cores <= 4 -> LOW
                perfClass >= Build.VERSION_CODES.S || (totalGb >= 5.5 && cores >= 8) -> HIGH
                else -> MEDIUM
            }
            return supportedAtOrBelow(wanted)
        }

        /** Steps down until the hardware H.264 encoder reports support. */
        fun supportedAtOrBelow(q: StreamQuality): StreamQuality {
            val caps = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                    .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
            }.getOrDefault(emptyList())
            if (caps.isEmpty()) return q
            var cur = q
            while (true) {
                if (caps.any { runCatching { it.areSizeAndRateSupported(cur.width, cur.height, cur.fps.toDouble()) }.getOrDefault(false) }) return cur
                cur = entries.getOrNull(cur.ordinal - 1) ?: return LOW
            }
        }
    }
}
