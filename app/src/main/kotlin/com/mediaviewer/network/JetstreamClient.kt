package com.mediaviewer.network

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** One parsed `commit` event off Jetstream — a record was created, updated,
 *  or deleted in some account's repo. Jetstream also emits `identity` and
 *  `account` kinds; this client only surfaces commits, since that's all any
 *  current caller (FirehoseIndexer) needs. */
data class JetstreamCommitEvent(
    val did: String,
    val collection: String,
    val operation: String, // "create" | "update" | "delete"
    val rkey: String,
    val record: JsonObject?,
    val timeUs: Long
)

/**
 * Client for Bluesky's **Jetstream** — a public, low-latency WebSocket feed
 * that mirrors the AT Protocol firehose as plain JSON (rather than the raw
 * firehose's CBOR car-slices), filterable server-side by collection via
 * `wantedCollections`. This is what lets FirehoseIndexer learn about a
 * followed account's new review/blog the moment it's posted, instead of
 * polling that account's PDS.
 *
 * Reconnects with exponential backoff on drop, and resumes from the last
 * seen `time_us` cursor (Jetstream's own replay position) so a brief network
 * blip doesn't silently lose events — see the architecture note this is
 * based on.
 */
class JetstreamClient(
    private val wantedCollections: List<String>,
    // Bug fix (this session): the whole point of Jetstream's cursor is
    // catch-up replay — reconnecting with a `cursor` from a previous
    // session tells the server "send me everything I missed since this
    // point", via the same single streaming connection, no REST fan-out
    // involved. This class was already using `cursor` to survive a
    // mid-session reconnect, but never seeded it from a PREVIOUS session,
    // so every fresh app launch started listening from "now" — anything
    // posted while the app was closed was silently missed. Passing in a
    // persisted cursor here (see FirehoseIndexer.start) is what actually
    // closes that gap, without ever needing a per-account REST re-fetch to
    // "catch up".
    initialCursor: Long? = null,
    private val host: String = "jetstream2.us-east.bsky.network"
) {
    companion object { private const val TAG = "JetstreamClient" }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived stream, no read timeout
        .build()

    private var socket: WebSocket? = null
    @Volatile private var cursor: Long? = initialCursor
    @Volatile private var stopped = true
    @Volatile private var reconnectAttempt = 0
    @Volatile private var connected = false

    private val _events = MutableSharedFlow<JetstreamCommitEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<JetstreamCommitEvent> = _events

    /** Latest cursor position seen so far — FirehoseIndexer polls this
     *  periodically to persist it, so the NEXT app launch can resume from
     *  here instead of from "now". */
    fun currentCursor(): Long? = cursor

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        connected = false
        socket?.close(1000, "client stop")
        socket = null
    }

    /** Called on app foreground resume (see MainActivity.onResume /
     *  FirehoseIndexer.onAppForegrounded) — immediately attempts a fresh
     *  connection if this client isn't currently connected, rather than
     *  waiting on scheduleReconnect's own backoff timer. Android can
     *  throttle background threads (Doze/App Standby), so that timer isn't
     *  a reliable way to notice "we're back in the foreground, reconnect
     *  now" — this is. No-op if already connected or never started. */
    fun reconnectIfNeeded() {
        if (stopped || connected) return
        reconnectAttempt = 0
        connect()
    }

    private fun buildUrl(): String {
        val params = StringBuilder()
        wantedCollections.forEach { params.append("&wantedCollections=").append(it) }
        cursor?.let { params.append("&cursor=").append(it) }
        return "wss://$host/subscribe?" + params.toString().removePrefix("&")
    }

    private fun connect() {
        if (stopped) return
        val request = Request.Builder().url(buildUrl()).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                connected = true
                Log.d(TAG, "connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                Log.w(TAG, "connection failure, reconnecting: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                if (!stopped) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        reconnectAttempt++
        val delayMs = minOf(30_000L, 1000L * (1L shl minOf(reconnectAttempt, 5)))
        Thread {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
            connect()
        }.start()
    }

    private fun handleMessage(text: String) {
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        obj.get("time_us")?.takeIf { it.isJsonPrimitive }?.asLong?.let { cursor = it }
        if (obj.get("kind")?.takeIf { it.isJsonPrimitive }?.asString != "commit") return
        val did = obj.get("did")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val commit = obj.getAsJsonObject("commit") ?: return
        val collection = commit.get("collection")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val operation = commit.get("operation")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val rkey = commit.get("rkey")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val record = commit.getAsJsonObject("record")
        _events.tryEmit(JetstreamCommitEvent(did, collection, operation, rkey, record, cursor ?: 0L))
    }
}
