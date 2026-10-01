package com.mediaviewer.stream

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import com.mediaviewer.util.PitchShifter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VRM mode recordings with a changed "Voice pitch": MediaRecorder can't
 * process the mic, so while the pitch isn't natural the video is recorded
 * silent by MediaRecorder and the mic goes through this instead —
 * AudioRecord → [PitchShifter] → AAC → its own .m4a — and the two are
 * joined by [muxVideoAndAudio] when recording stops.
 */
class PitchedAudioRecorder(private val file: File, semitones: Float) {
    private val sampleRate = 44_100
    private val shifter = PitchShifter(sampleRate).also { it.semitones = semitones }
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxerStarted = false
    private var thread: Thread? = null
    @Volatile var muted = false
    /** The pitch, changeable while recording (applies from the next block). */
    var semitones: Float
        get() = shifter.semitones
        set(value) { shifter.semitones = value }

    @SuppressLint("MissingPermission")
    fun start(): Boolean = try {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192) * 2)
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("AudioRecord init failed") }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        r.startRecording()
        record = r; codec = c
        running.set(true)
        thread = Thread({ loop() }, "vrm-pitched-audio").also { it.priority = Thread.MAX_PRIORITY; it.start() }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't start pitched audio", e)
        release()
        false
    }

    private fun loop() {
        val r = record ?: return
        val c = codec ?: return
        val buf = ShortArray(1024)
        val bytes = ByteArray(2048)
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        try {
            while (running.get()) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(5); continue }
                shifter.process(buf, n)
                val silent = muted
                for (i in 0 until n) {
                    val v = if (silent) 0 else buf[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte(); bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                queue(c, bytes, n * 2, samples * 1_000_000L / sampleRate, 0)
                samples += n
                drain(c, info, false)
            }
            queue(c, bytes, 0, samples * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(c, info, true)
        } catch (e: Exception) {
            Log.e(TAG, "Pitched audio loop failed", e)
        }
    }

    private fun queue(c: MediaCodec, data: ByteArray, len: Int, ptsUs: Long, flags: Int) {
        val index = c.dequeueInputBuffer(20_000)
        if (index < 0) return
        val inBuf = c.getInputBuffer(index) ?: return
        inBuf.clear()
        val l = minOf(len, inBuf.remaining())
        inBuf.put(data, 0, l)
        c.queueInputBuffer(index, 0, l, ptsUs, flags)
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo, untilEos: Boolean) {
        val m = muxer ?: return
        var idleSpins = 0
        while (true) {
            val index = c.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) { track = m.addTrack(c.outputFormat); m.start(); muxerStarted = true }
                continue
            }
            if (index < 0) {
                if (!untilEos || ++idleSpins > 100) return
                continue
            }
            val out = c.getOutputBuffer(index)
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (out != null && info.size > 0 && muxerStarted && !isConfig) {
                out.position(info.offset); out.limit(info.offset + info.size)
                m.writeSampleData(track, out, info)
            }
            c.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    /** Stops and finalises the .m4a. True if audio was written. */
    fun stop(): Boolean {
        running.set(false)
        runCatching { thread?.join(3000) }
        val ok = muxerStarted
        release()
        return ok && file.length() > 0
    }

    private fun release() {
        runCatching { record?.stop() }; runCatching { record?.release() }; record = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }; muxer = null
    }

    companion object {
        private const val TAG = "PitchedAudioRecorder"

        /** Copies the video track of [video] and the audio track of [audio]
         *  into [out]. False (and [out] deleted) on any failure. */
        fun muxVideoAndAudio(video: File, audio: File, out: File): Boolean {
            val vEx = MediaExtractor(); val aEx = MediaExtractor()
            var muxer: MediaMuxer? = null
            var started = false
            val ok = try {
                vEx.setDataSource(video.absolutePath); aEx.setDataSource(audio.absolutePath)
                fun pick(ex: MediaExtractor, prefix: String): Int {
                    for (i in 0 until ex.trackCount) {
                        if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
                    }
                    return -1
                }
                val vi = pick(vEx, "video/"); val ai = pick(aEx, "audio/")
                if (vi < 0) throw IllegalStateException("no video track")
                val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = m
                vEx.selectTrack(vi)
                val vt = m.addTrack(vEx.getTrackFormat(vi))
                val at = if (ai >= 0) { aEx.selectTrack(ai); m.addTrack(aEx.getTrackFormat(ai)) } else -1
                m.start(); started = true
                val buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                fun copy(ex: MediaExtractor, trackIndex: Int, limitUs: Long) {
                    while (true) {
                        buffer.clear()
                        val size = ex.readSampleData(buffer, 0)
                        if (size < 0) break
                        val t = ex.sampleTime
                        if (limitUs > 0 && t > limitUs) break
                        info.offset = 0; info.size = size; info.presentationTimeUs = t
                        info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        m.writeSampleData(trackIndex, buffer, info)
                        ex.advance()
                    }
                }
                val videoDurationUs = runCatching { vEx.getTrackFormat(vi).getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                copy(vEx, vt, 0)
                if (at >= 0) copy(aEx, at, videoDurationUs)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Muxing failed", e)
                false
            } finally {
                if (started) runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
                runCatching { vEx.release() }; runCatching { aEx.release() }
            }
            if (!ok) out.delete()
            return ok
        }
    }
}
