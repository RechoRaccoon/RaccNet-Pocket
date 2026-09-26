package com.mediaviewer.stream

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocketFactory

/**
 * A small, dependency-free RTMP / RTMPS publisher — just enough of the
 * protocol to push one live H.264 + AAC stream to YouTube, Twitch, Kick,
 * Facebook (rtmps), Owncast, nginx-rtmp, OBS-style ingest servers, etc.
 *
 * Pure JVM on purpose (no android.* imports): the whole handshake →
 * connect → createStream → publish → FLV-tag path can be exercised on a
 * desktop JVM against `ffmpeg -listen 1 -i rtmp://…`, which is how it was
 * verified.
 *
 * Threading: [connect] blocks (call it off the main thread). After that,
 * [sendVideo]/[sendAudio]/config calls only enqueue — one writer thread
 * owns the socket, one reader thread answers pings/acks. When the network
 * can't keep up, queued non-key video frames are dropped (never audio,
 * never keyframes) and [Listener.onCongestion] fires so the encoder can
 * lower its bitrate — the stream stutters briefly instead of drifting
 * minutes behind real time.
 */
class RtmpPublisher(private val listener: Listener) {

    interface Listener {
        /** The socket died or the server hung up after publishing started. */
        fun onDisconnected(reason: String)
        /** Queued-but-unsent media is backing up: [queuedMs] of video waiting. */
        fun onCongestion(queuedMs: Long) {}
        /** The backlog has drained again. */
        fun onCongestionCleared() {}
        /** Queued frames were dropped; the next frame should be a keyframe. */
        fun onKeyframeNeeded() {}
    }

    class Endpoint(
        val secure: Boolean,
        val host: String,
        val port: Int,
        val app: String,
        val tcUrl: String,
        val streamName: String
    )

    private class Packet(
        val type: Int,
        val csid: Int,
        val timestampMs: Long,
        val payload: ByteArray,
        val isVideo: Boolean,
        val isKeyframe: Boolean,
        val mediaTimeMs: Long
    )

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var input: DataInputStream? = null
    private var streamId = 1
    private var outChunkSize = 128
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingDeque<Packet>()
    private var writer: Thread? = null
    private var reader: Thread? = null
    private val lastVideoQueuedMs = AtomicLong(-1)
    private val lastVideoSentMs = AtomicLong(-1)
    @Volatile private var dropUntilKeyframe = false
    @Volatile private var congested = false
    @Volatile private var disconnectReported = false

    /** Bytes actually written to the socket (for the bitrate readout). */
    val bytesSent = AtomicLong(0)
    /** Video frames thrown away because the network couldn't keep up. */
    val droppedFrames = AtomicLong(0)

    val isConnected: Boolean get() = running.get()

    // ───────────────────────────────────────────────────────────── connect

    /**
     * Opens the connection and gets the server into "publishing" state.
     * Throws [IOException] with a human-readable message on failure.
     */
    fun connect(serverUrl: String, streamKey: String, timeoutMs: Int = 10_000) {
        val ep = parseEndpoint(serverUrl, streamKey)
        val s: Socket = if (ep.secure) {
            val raw = Socket()
            raw.connect(InetSocketAddress(ep.host, ep.port), timeoutMs)
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, ep.host, ep.port, true)
                .also { (it as javax.net.ssl.SSLSocket).startHandshake() }
        } else {
            Socket().also { it.connect(InetSocketAddress(ep.host, ep.port), timeoutMs) }
        }
        s.tcpNoDelay = true
        s.soTimeout = timeoutMs
        runCatching { s.sendBufferSize = 256 * 1024 }
        socket = s
        out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        input = DataInputStream(BufferedInputStream(s.getInputStream(), 16 * 1024))
        try {
            handshake()
            // Big chunks: far less header overhead than the default 128.
            writeMessage(TYPE_SET_CHUNK_SIZE, 2, 0, 0, int32(OUT_CHUNK_SIZE))
            outChunkSize = OUT_CHUNK_SIZE
            sendCommand(0, 3, "connect", 1.0, linkedMapOf(
                "app" to ep.app,
                "type" to "nonprivate",
                "flashVer" to "FMLE/3.0 (compatible; FMSc/1.0)",
                "swfUrl" to ep.tcUrl,
                "tcUrl" to ep.tcUrl
            ))
            out!!.flush()
            awaitResult(1.0, "connect")
            sendCommand(0, 3, "releaseStream", 2.0, null, ep.streamName)
            sendCommand(0, 3, "FCPublish", 3.0, null, ep.streamName)
            sendCommand(0, 3, "createStream", 4.0, null)
            out!!.flush()
            val created = awaitResult(4.0, "createStream")
            streamId = (created.getOrNull(3) as? Double)?.toInt() ?: 1
            sendCommand(streamId, 4, "publish", 5.0, null, ep.streamName, "live")
            out!!.flush()
            awaitPublishStart()
        } catch (e: Exception) {
            closeQuietly()
            throw if (e is IOException) e else IOException(e.message ?: e.toString(), e)
        }
        s.soTimeout = 0
        running.set(true)
        writer = Thread({ writeLoop() }, "rtmp-writer").also { it.priority = Thread.MAX_PRIORITY - 1; it.start() }
        reader = Thread({ readLoop() }, "rtmp-reader").also { it.isDaemon = true; it.start() }
    }

    /** `@setDataFrame onMetaData` — optional but YouTube/Twitch like it. */
    fun sendMetadata(width: Int, height: Int, fps: Int, videoKbps: Int, audioKbps: Int, sampleRate: Int, stereo: Boolean) {
        val body = ByteArrayOutputStream()
        Amf0.writeString(body, "@setDataFrame")
        Amf0.writeString(body, "onMetaData")
        Amf0.writeEcmaArray(body, linkedMapOf(
            "duration" to 0.0,
            "width" to width.toDouble(),
            "height" to height.toDouble(),
            "videodatarate" to videoKbps.toDouble(),
            "framerate" to fps.toDouble(),
            "videocodecid" to 7.0,
            "audiodatarate" to audioKbps.toDouble(),
            "audiosamplerate" to sampleRate.toDouble(),
            "audiosamplesize" to 16.0,
            "stereo" to stereo,
            "audiocodecid" to 10.0,
            "encoder" to "RaccNet Pocket"
        ))
        enqueue(Packet(TYPE_DATA_AMF0, 4, 0, body.toByteArray(), false, false, 0))
    }

    // ───────────────────────────────────────────────────────────── media

    /** AVC sequence header from the encoder's SPS + PPS (no start codes). */
    fun sendVideoConfig(sps: ByteArray, pps: ByteArray) {
        val b = ByteArrayOutputStream()
        b.write(0x17); b.write(0x00); b.write(0); b.write(0); b.write(0)
        b.write(1); b.write(sps[1].toInt()); b.write(sps[2].toInt()); b.write(sps[3].toInt())
        b.write(0xFF) // 4-byte NAL lengths
        b.write(0xE1) // 1 SPS
        b.write((sps.size shr 8) and 0xFF); b.write(sps.size and 0xFF); b.write(sps)
        b.write(1)    // 1 PPS
        b.write((pps.size shr 8) and 0xFF); b.write(pps.size and 0xFF); b.write(pps)
        enqueue(Packet(TYPE_VIDEO, 6, 0, b.toByteArray(), true, true, 0))
    }

    /** One encoded frame: [nals] are raw NAL units without start codes. */
    fun sendVideo(nals: List<ByteArray>, timestampMs: Long, keyframe: Boolean) {
        if (!running.get()) return
        if (dropUntilKeyframe && !keyframe) { droppedFrames.incrementAndGet(); return }
        if (keyframe) dropUntilKeyframe = false
        var size = 5
        for (n in nals) size += 4 + n.size
        val p = ByteArray(size)
        p[0] = (if (keyframe) 0x17 else 0x27).toByte()
        p[1] = 1 // AVC NALU
        // p[2..4] composition time = 0 (no B-frames)
        var o = 5
        for (n in nals) {
            p[o] = (n.size ushr 24).toByte(); p[o + 1] = (n.size ushr 16).toByte()
            p[o + 2] = (n.size ushr 8).toByte(); p[o + 3] = n.size.toByte()
            System.arraycopy(n, 0, p, o + 4, n.size)
            o += 4 + n.size
        }
        lastVideoQueuedMs.set(timestampMs)
        enqueue(Packet(TYPE_VIDEO, 6, timestampMs, p, true, keyframe, timestampMs))
        checkCongestion()
    }

    /** AAC sequence header: the encoder's 2-byte AudioSpecificConfig. */
    fun sendAudioConfig(audioSpecificConfig: ByteArray) {
        enqueue(Packet(TYPE_AUDIO, 5, 0, byteArrayOf(0xAF.toByte(), 0x00) + audioSpecificConfig, false, false, 0))
    }

    /** One raw AAC frame (no ADTS header). */
    fun sendAudio(frame: ByteArray, timestampMs: Long) {
        if (!running.get()) return
        val p = ByteArray(frame.size + 2)
        p[0] = 0xAF.toByte(); p[1] = 1
        System.arraycopy(frame, 0, p, 2, frame.size)
        enqueue(Packet(TYPE_AUDIO, 5, timestampMs, p, false, false, timestampMs))
    }

    /** Sends FCUnpublish/deleteStream and closes. Safe to call any time. */
    fun close() {
        val wasRunning = running.getAndSet(false)
        disconnectReported = true
        writer?.interrupt()
        runCatching { writer?.join(1500) }
        if (wasRunning) {
            runCatching {
                sendCommand(streamId, 3, "FCUnpublish", 6.0, null, "")
                sendCommand(0, 3, "deleteStream", 7.0, null, streamId.toDouble())
                out?.flush()
            }
        }
        closeQuietly()
        queue.clear()
    }

    // ───────────────────────────────────────────────────────────── internals

    private fun enqueue(p: Packet) {
        if (running.get()) queue.offer(p)
    }

    /** Backlog measured in media time: newest queued video vs. last sent. */
    private fun checkCongestion() {
        val queued = lastVideoQueuedMs.get()
        val sent = lastVideoSentMs.get()
        if (queued < 0 || sent < 0) return
        val backlog = queued - sent
        if (backlog > DROP_THRESHOLD_MS) {
            // Throw away queued P-frames; the next keyframe resyncs cleanly.
            var dropped = 0
            val it = queue.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.isVideo && !p.isKeyframe) { it.remove(); dropped++ }
            }
            droppedFrames.addAndGet(dropped.toLong())
            dropUntilKeyframe = true
            lastVideoSentMs.set(queued)
            listener.onKeyframeNeeded()
        }
        if (backlog > CONGESTION_MS && !congested) {
            congested = true
            listener.onCongestion(backlog)
        } else if (backlog < CONGESTION_CLEAR_MS && congested) {
            congested = false
            listener.onCongestionCleared()
        }
    }

    private fun writeLoop() {
        try {
            while (running.get()) {
                val p = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                writeMessage(p.type, p.csid, streamId, p.timestampMs, p.payload)
                // Coalesce: only flush when nothing else is waiting.
                if (queue.isEmpty()) out?.flush()
                if (p.isVideo) lastVideoSentMs.set(p.mediaTimeMs)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            reportDisconnect("Connection lost: ${e.message ?: e::class.java.simpleName}")
        }
    }

    private fun readLoop() {
        try {
            val chunks = pendingReader ?: ChunkReader(input!!)
            while (running.get()) {
                val msg = chunks.next() ?: break
                handleIncoming(msg, chunks)
            }
            if (running.get()) reportDisconnect("The server closed the connection")
        } catch (e: Exception) {
            if (running.get()) reportDisconnect("Connection lost: ${e.message ?: e::class.java.simpleName}")
        }
    }

    private fun reportDisconnect(reason: String) {
        if (disconnectReported) return
        disconnectReported = true
        running.set(false)
        closeQuietly()
        listener.onDisconnected(reason)
    }

    private fun handleIncoming(msg: Message, chunks: ChunkReader) {
        when (msg.type) {
            TYPE_SET_CHUNK_SIZE -> chunks.chunkSize = readInt32(msg.body, 0) and 0x7FFFFFFF
            TYPE_WINDOW_ACK -> chunks.windowAckSize = readInt32(msg.body, 0).toLong()
            TYPE_USER_CONTROL -> {
                // Ping request (6) → ping response (7) with the same timestamp.
                if (msg.body.size >= 6 && readInt16(msg.body, 0) == 6) {
                    val resp = byteArrayOf(0, 7) + msg.body.copyOfRange(2, 6)
                    synchronized(this) { runCatching { writeMessage(TYPE_USER_CONTROL, 2, 0, 0, resp); out?.flush() } }
                }
            }
            TYPE_COMMAND_AMF0 -> {
                val values = runCatching { Amf0.readAll(msg.body) }.getOrDefault(emptyList())
                val name = values.firstOrNull() as? String
                if (name == "onStatus") {
                    val info = values.getOrNull(3) as? Map<*, *>
                    val level = info?.get("level") as? String
                    val code = info?.get("code") as? String ?: ""
                    if (level == "error" || code == "NetStream.Publish.BadName" || code.contains("Unpublish")) {
                        reportDisconnect(info?.get("description") as? String ?: code)
                    }
                }
            }
        }
        if (chunks.needsAck()) {
            val ack = int32(chunks.bytesRead.toInt())
            synchronized(this) { runCatching { writeMessage(TYPE_ACK, 2, 0, 0, ack); out?.flush() } }
        }
    }

    private fun handshake() {
        val o = out!!
        val c1 = ByteArray(HANDSHAKE_SIZE)
        java.util.Random().nextBytes(c1)
        for (i in 0 until 8) c1[i] = 0 // time + zero
        o.write(3); o.write(c1); o.flush()
        val i = input!!
        val s0 = i.readUnsignedByte()
        if (s0 != 3) throw IOException("Not an RTMP server (handshake version $s0)")
        val s1 = ByteArray(HANDSHAKE_SIZE); i.readFully(s1)
        o.write(s1); o.flush() // C2 = echo of S1
        val s2 = ByteArray(HANDSHAKE_SIZE); i.readFully(s2)
    }

    /** Reads messages until the `_result`/`_error` for [txn]. */
    private fun awaitResult(txn: Double, what: String): List<Any?> {
        val chunks = pendingReader ?: ChunkReader(input!!).also { pendingReader = it }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val msg = chunks.next() ?: throw IOException("Server closed the connection during $what")
            if (msg.type == TYPE_COMMAND_AMF0 || msg.type == TYPE_COMMAND_AMF3) {
                val body = if (msg.type == TYPE_COMMAND_AMF3 && msg.body.isNotEmpty()) msg.body.copyOfRange(1, msg.body.size) else msg.body
                val values = Amf0.readAll(body)
                val name = values.firstOrNull() as? String
                val id = values.getOrNull(1) as? Double
                if (id == txn && name == "_result") return values
                if (id == txn && name == "_error") {
                    val info = values.getOrNull(3) as? Map<*, *>
                    throw IOException("Server refused $what: ${info?.get("description") ?: info?.get("code") ?: "error"}")
                }
            } else {
                handleIncoming(msg, chunks)
            }
        }
        throw IOException("Timed out waiting for the server ($what)")
    }

    private fun awaitPublishStart() {
        val chunks = pendingReader ?: ChunkReader(input!!).also { pendingReader = it }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val msg = try {
                chunks.next()
            } catch (e: java.net.SocketTimeoutException) {
                return // some servers never send onStatus; carry on
            } ?: throw IOException("Server closed the connection — check the stream key")
            if (msg.type == TYPE_COMMAND_AMF0) {
                val values = Amf0.readAll(msg.body)
                if (values.firstOrNull() == "onStatus") {
                    val info = values.getOrNull(3) as? Map<*, *>
                    val code = info?.get("code") as? String ?: ""
                    val level = info?.get("level") as? String ?: ""
                    if (code == "NetStream.Publish.Start") return
                    if (level == "error" || code.contains("BadName") || code.contains("Failed")) {
                        throw IOException("Server refused the stream: ${info?.get("description") ?: code}")
                    }
                } else if (values.firstOrNull() == "_error") {
                    throw IOException("Server refused the stream — check the stream key")
                }
            } else {
                handleIncoming(msg, chunks)
            }
        }
    }

    private var pendingReader: ChunkReader? = null

    private fun sendCommand(msgStreamId: Int, csid: Int, name: String, txn: Double, obj: Map<String, Any?>?, vararg args: Any?) {
        val b = ByteArrayOutputStream()
        Amf0.writeString(b, name)
        Amf0.writeNumber(b, txn)
        if (obj != null) Amf0.writeObject(b, obj) else Amf0.writeNull(b)
        for (a in args) Amf0.write(b, a)
        writeMessage(TYPE_COMMAND_AMF0, csid, msgStreamId, 0, b.toByteArray())
    }

    /** Every message goes out with a full (type 0) header; continuation
     *  chunks use type 3. Simple and accepted by every server. */
    @Synchronized
    private fun writeMessage(type: Int, csid: Int, msgStreamId: Int, timestampMs: Long, payload: ByteArray) {
        val o = out ?: throw IOException("Not connected")
        val ts = timestampMs and 0xFFFFFFFFL
        val extended = ts >= 0xFFFFFF
        val h = ByteArray(12 + if (extended) 4 else 0)
        h[0] = (csid and 0x3F).toByte()
        val t3 = if (extended) 0xFFFFFF else ts.toInt()
        h[1] = (t3 ushr 16).toByte(); h[2] = (t3 ushr 8).toByte(); h[3] = t3.toByte()
        h[4] = (payload.size ushr 16).toByte(); h[5] = (payload.size ushr 8).toByte(); h[6] = payload.size.toByte()
        h[7] = type.toByte()
        h[8] = msgStreamId.toByte(); h[9] = (msgStreamId ushr 8).toByte()
        h[10] = (msgStreamId ushr 16).toByte(); h[11] = (msgStreamId ushr 24).toByte()
        if (extended) {
            h[12] = (ts ushr 24).toByte(); h[13] = (ts ushr 16).toByte(); h[14] = (ts ushr 8).toByte(); h[15] = ts.toByte()
        }
        o.write(h)
        var off = 0
        var written = h.size.toLong()
        while (off < payload.size) {
            if (off > 0) {
                o.write(0xC0 or (csid and 0x3F))
                written++
                if (extended) { o.write(h, 12, 4); written += 4 }
            }
            val n = minOf(outChunkSize, payload.size - off)
            o.write(payload, off, n)
            off += n
            written += n
        }
        bytesSent.addAndGet(written)
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
    }

    // ───────────────────────────────────────────────────────────── reading

    private class Message(val type: Int, val streamId: Int, val body: ByteArray)

    private class ChunkReader(private val input: DataInputStream) {
        private class State(var ts: Long = 0, var length: Int = 0, var type: Int = 0, var streamId: Int = 0,
                            var buf: ByteArrayOutputStream? = null, var extended: Boolean = false)
        private val states = HashMap<Int, State>()
        var chunkSize = 128
        var windowAckSize = 2_500_000L
        var bytesRead = 0L
        private var lastAck = 0L

        fun needsAck() = windowAckSize > 0 && bytesRead - lastAck >= windowAckSize && run { lastAck = bytesRead; true }

        private fun u8(): Int { val v = input.read(); if (v < 0) throw java.io.EOFException(); bytesRead++; return v }
        private fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
        private fun u32be(): Long = (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()

        /** Next complete message, or null at end of stream. */
        fun next(): Message? {
            while (true) {
                val first = input.read()
                if (first < 0) return null
                bytesRead++
                val fmt = first ushr 6
                var csid = first and 0x3F
                if (csid == 0) csid = 64 + u8()
                else if (csid == 1) csid = 64 + u8() + (u8() shl 8)
                val st = states.getOrPut(csid) { State() }
                when (fmt) {
                    0 -> {
                        var ts = u24().toLong()
                        st.length = u24(); st.type = u8()
                        st.streamId = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
                        st.extended = ts == 0xFFFFFFL
                        if (st.extended) ts = u32be()
                        st.ts = ts
                    }
                    1 -> {
                        var d = u24().toLong(); st.length = u24(); st.type = u8()
                        st.extended = d == 0xFFFFFFL
                        if (st.extended) d = u32be()
                        st.ts += d
                    }
                    2 -> {
                        var d = u24().toLong()
                        st.extended = d == 0xFFFFFFL
                        if (st.extended) d = u32be()
                        st.ts += d
                    }
                    else -> if (st.extended) u32be()
                }
                val buf = st.buf ?: ByteArrayOutputStream(st.length).also { st.buf = it }
                val remaining = st.length - buf.size()
                val n = minOf(chunkSize, remaining)
                val tmp = ByteArray(n)
                input.readFully(tmp)
                bytesRead += n
                buf.write(tmp)
                if (buf.size() >= st.length) {
                    st.buf = null
                    val msg = Message(st.type, st.streamId, buf.toByteArray())
                    if (msg.type == TYPE_SET_CHUNK_SIZE && msg.body.size >= 4) chunkSize = readInt32(msg.body, 0) and 0x7FFFFFFF
                    return msg
                }
            }
        }
    }

    companion object {
        private const val HANDSHAKE_SIZE = 1536
        private const val OUT_CHUNK_SIZE = 4096
        private const val TYPE_SET_CHUNK_SIZE = 1
        private const val TYPE_ACK = 3
        private const val TYPE_USER_CONTROL = 4
        private const val TYPE_WINDOW_ACK = 5
        private const val TYPE_AUDIO = 8
        private const val TYPE_VIDEO = 9
        private const val TYPE_DATA_AMF0 = 18
        private const val TYPE_COMMAND_AMF3 = 17
        private const val TYPE_COMMAND_AMF0 = 20

        /** Video waiting longer than this → tell the encoder to back off. */
        private const val CONGESTION_MS = 1_200L
        private const val CONGESTION_CLEAR_MS = 300L
        /** …and past this, drop queued P-frames outright. */
        private const val DROP_THRESHOLD_MS = 3_000L

        private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun readInt32(b: ByteArray, o: Int) =
            ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        private fun readInt16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        /**
         * Server URL + key → endpoint. Accepts what services hand out:
         *  - `rtmp://a.rtmp.youtube.com/live2` + key
         *  - `rtmps://live-api-s.facebook.com:443/rtmp/` + key
         *  - a full URL with the key already on the end and the key box empty.
         */
        fun parseEndpoint(serverUrl: String, streamKey: String): Endpoint {
            val raw = serverUrl.trim()
            val uri = runCatching { URI(raw) }.getOrNull() ?: throw IOException("That server URL isn't valid")
            val scheme = uri.scheme?.lowercase()
            val secure = when (scheme) {
                "rtmp" -> false
                "rtmps" -> true
                else -> throw IOException("The server URL must start with rtmp:// or rtmps://")
            }
            val host = uri.host ?: throw IOException("The server URL has no host")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 1935
            var path = (uri.rawPath ?: "").trim('/')
            var key = streamKey.trim()
            if (key.isEmpty()) {
                // Key pasted onto the URL: the last path segment is the stream.
                val cut = path.lastIndexOf('/')
                if (cut <= 0) throw IOException("Enter your stream key")
                key = path.substring(cut + 1)
                path = path.substring(0, cut)
            }
            if (path.isEmpty()) throw IOException("The server URL is missing its app path (e.g. /live2)")
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            val app = path + query
            val portPart = if (uri.port > 0) ":${uri.port}" else ""
            val tcUrl = "$scheme://$host$portPart/$app"
            return Endpoint(secure, host, port, app, tcUrl, key)
        }
    }
}

/** Minimal AMF0 — only the types RTMP command messages actually use. */
internal object Amf0 {
    fun write(o: OutputStream, v: Any?) {
        when (v) {
            null -> writeNull(o)
            is String -> writeString(o, v)
            is Number -> writeNumber(o, v.toDouble())
            is Boolean -> { o.write(1); o.write(if (v) 1 else 0) }
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") writeObject(o, v as Map<String, Any?>)
            else -> writeString(o, v.toString())
        }
    }
    fun writeNumber(o: OutputStream, d: Double) {
        o.write(0)
        val bits = java.lang.Double.doubleToLongBits(d)
        for (i in 7 downTo 0) o.write((bits ushr (i * 8)).toInt() and 0xFF)
    }
    fun writeString(o: OutputStream, s: String) { o.write(2); writeUtf(o, s) }
    fun writeNull(o: OutputStream) = o.write(5)
    private fun writeUtf(o: OutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.write((b.size shr 8) and 0xFF); o.write(b.size and 0xFF); o.write(b)
    }
    fun writeObject(o: OutputStream, m: Map<String, Any?>) {
        o.write(3)
        for ((k, v) in m) { writeUtf(o, k); write(o, v) }
        o.write(0); o.write(0); o.write(9)
    }
    fun writeEcmaArray(o: OutputStream, m: Map<String, Any?>) {
        o.write(8)
        o.write(0); o.write(0); o.write((m.size shr 8) and 0xFF); o.write(m.size and 0xFF)
        for ((k, v) in m) { writeUtf(o, k); write(o, v) }
        o.write(0); o.write(0); o.write(9)
    }

    fun readAll(b: ByteArray): List<Any?> {
        val input = DataInputStream(b.inputStream())
        val out = ArrayList<Any?>()
        while (input.available() > 0) out.add(read(input))
        return out
    }

    private object End

    private fun read(i: DataInputStream): Any? {
        return when (val t = i.readUnsignedByte()) {
            0 -> i.readDouble()
            1 -> i.readUnsignedByte() != 0
            2 -> readUtf(i)
            3 -> readProps(i, LinkedHashMap())
            5, 6 -> null
            8 -> { i.readInt(); readProps(i, LinkedHashMap()) }
            9 -> End
            10 -> { val n = i.readInt(); List(n) { read(i) } }
            11 -> { val d = i.readDouble(); i.readShort(); d }
            12 -> { val n = i.readInt(); val bytes = ByteArray(n); i.readFully(bytes); String(bytes, Charsets.UTF_8) }
            else -> throw IOException("Unsupported AMF0 type $t")
        }
    }
    private fun readUtf(i: DataInputStream): String {
        val n = i.readUnsignedShort()
        val bytes = ByteArray(n); i.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
    private fun readProps(i: DataInputStream, m: LinkedHashMap<String, Any?>): Map<String, Any?> {
        while (true) {
            val key = readUtf(i)
            val v = read(i)
            if (key.isEmpty() && v === End) return m
            m[key] = v
        }
    }
}
