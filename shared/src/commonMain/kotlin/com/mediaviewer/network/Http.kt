package com.mediaviewer.network

/*
 * Stellar's HTTP layer. Replaces Retrofit so the network code can be shared
 * with iOS, while keeping Retrofit's shapes the rest of the app was written
 * against: API calls return [Response]<T> with isSuccessful / code() /
 * body() / errorBody()?.string() / message(), exactly like
 * retrofit2.Response.
 *
 * The actual sending is done per platform by an [HttpEngine]: on Android
 * that's OkHttp with the very same client setup Stellar has always used
 * (dispatcher limits, blocked hosts, User-Agents, timeouts — see
 * AndroidHttpClients); on iOS it's Ktor's Darwin (NSURLSession) engine.
 */

/** Which client configuration a request goes through (User-Agent,
 *  timeouts, interceptors) — one per former Retrofit builder. */
enum class HttpProfile(val userAgent: String) {
    /** bsky.social / the account's PDS (NetworkClient.buildBlueskyApi). */
    BLUESKY("MediaViewer/1.0 (ATProto client)"),
    /** api.bsky.app / api.bsky.chat with service auth (buildDirectServiceApi);
     *  drops the PDS-only `atproto-proxy` header. */
    DIRECT_SERVICE("Stellar/1.0 (ATProto client)"),
    E621("MediaViewer/1.0 (by your_username)"),
    STREAMPLACE("MediaViewer/1.0 (ATProto client)"),
    ROCKSKY("Stellar/1.0"),
    /** video.bsky.app uploads: long timeouts. */
    VIDEO("MediaViewer/1.0 (ATProto client)"),
    /** Plain downloads and one-off requests (NetworkClient.downloadClient). */
    DOWNLOAD("MediaViewer/1.0"),
}

/** A request body: raw bytes, a URL-encoded form, or a platform stream
 *  (large uploads read straight from disk). */
class RequestBody internal constructor(
    val contentType: String?,
    val bytes: ByteArray?,
    val form: List<Pair<String, String>>? = null,
    val stream: StreamSource? = null,
)

/** A body read from a file/content URI while it's being sent. Created by
 *  platform code (see MediaBridge.uploadStreamFor). */
interface StreamSource {
    /** Total bytes, or -1 if unknown. */
    val contentLength: Long
}

/** Same call shape as OkHttp's `bytes.toRequestBody(mediaType)`. */
fun ByteArray.toRequestBody(contentType: String? = null): RequestBody = RequestBody(contentType, this)
fun String.toRequestBody(contentType: String? = null): RequestBody = RequestBody(contentType, encodeToByteArray())
/** OkHttp's `"image/png".toMediaType()` — media types are plain strings here. */
fun String.toMediaType(): String = this
fun streamRequestBody(contentType: String?, source: StreamSource): RequestBody = RequestBody(contentType, null, stream = source)
fun formRequestBody(fields: List<Pair<String, String>>): RequestBody =
    RequestBody("application/x-www-form-urlencoded", null, form = fields)

class HttpRequest(
    val method: String,
    /** Full URL without the query string. */
    val url: String,
    /** Query parameters in order (repeated names allowed), not yet encoded. */
    val query: List<Pair<String, String>>,
    val headers: List<Pair<String, String>>,
    val body: RequestBody?,
)

class HttpResponseData(
    val code: Int,
    val message: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val contentType: String?,
)

/** Sends requests for one client configuration. */
interface HttpEngine {
    /** Throws [com.mediaviewer.platform.IOException] (or a subclass) when the
     *  request can't be completed, like OkHttp/Retrofit. */
    suspend fun execute(request: HttpRequest): HttpResponseData
}

/** A new client for [profile] — each API object gets its own, as each
 *  Retrofit instance used to have its own OkHttpClient. */
expect fun createHttpEngine(profile: HttpProfile): HttpEngine

/** retrofit2's ResponseBody equivalent (fully buffered). */
class ResponseBody(private val data: ByteArray, private val type: String?) {
    fun string(): String = data.decodeToString()
    fun bytes(): ByteArray = data
    fun contentLength(): Long = data.size.toLong()
    fun contentType(): String? = type
    fun close() {}
}

class Headers(private val pairs: List<Pair<String, String>>) {
    operator fun get(name: String): String? = pairs.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
    fun values(name: String): List<String> = pairs.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
    fun names(): Set<String> = pairs.mapTo(LinkedHashSet()) { it.first }
}

/** retrofit2.Response<T> equivalent. */
class Response<T>(
    private val code: Int,
    private val message: String,
    private val headers: Headers,
    private val body: T?,
    private val errorBody: ResponseBody?,
) {
    val isSuccessful: Boolean get() = code in 200..299
    fun code(): Int = code
    fun message(): String = message
    fun headers(): Headers = headers
    fun body(): T? = body
    fun errorBody(): ResponseBody? = errorBody
    override fun toString(): String = "Response{code=$code, message=$message}"
}
