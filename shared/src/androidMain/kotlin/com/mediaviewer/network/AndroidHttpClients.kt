// check:jvm
package com.mediaviewer.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okio.source
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android's OkHttp clients — the same configuration the old Retrofit
 * builders in NetworkClient used, one client per API object.
 */
object AndroidHttpClients {

    // Bug fix (this session, part of the "autoloading is inconsistent" fix):
    // OkHttp's default Dispatcher caps concurrent requests at 5 per host and
    // 64 total. At app cold start this app fires off roughly half a dozen
    // independent ViewModel-level loads at once (feed, available feeds,
    // user lists, DM conversations/mutuals, From Friends preload, self
    // profile) — several of which (getMutuals, getAllFollows) are
    // themselves multi-page paginated fetches making several sequential
    // calls each. All of it goes through the single shared `api` client's
    // connection pool to the same bsky.social host, so the default 5-per-
    // host cap meant most of this cold-start traffic was queueing behind
    // itself rather than actually running concurrently, making the whole
    // window meaningfully slower and more likely to hit a timeout/transient
    // failure than it needed to be — this is a real contributing factor to
    // the intermittent "Mutuals sometimes doesn't load on launch" bug (see
    // MainViewModel.ensureDmConversationsLoaded's comment for the full
    // picture). Raising both limits gives cold-start traffic room to
    // actually run in parallel instead of queueing.
    private fun buildDispatcher(): Dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    }

    fun buildOkHttp(userAgent: String = "MediaViewer/1.0"): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .dispatcher(buildDispatcher())
            .addInterceptor(com.mediaviewer.util.BlockedHostsInterceptors.interceptor)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .build()
                chain.proceed(req)
            }
            // After the generic one: Wikimedia requests identify as Stellar.
            .addInterceptor(com.mediaviewer.util.BlockedHostsInterceptors.wikimediaUserAgent)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** A client for calling a Bluesky service (the AppView, the chat
     *  service) DIRECTLY with a service-auth token, instead of through the
     *  user's PDS. The PDS-routing "atproto-proxy" header some endpoints
     *  carry is dropped — it only means something to a PDS. */
    private fun buildDirectServiceClient(): OkHttpClient =
        buildOkHttp("Stellar/1.0 (ATProto client)").newBuilder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().removeHeader("atproto-proxy").build()) }
            .build()

    // Compose Post (upload flow): video upload/processing lives on its own
    // service, separate from the user's PDS — see BlueskyRepository.
    // uploadVideoBlob. Longer timeouts than the default client since a
    // 300MB/10-minute video upload can legitimately take a while even with
    // Bluesky's faster 2026 upload pipeline.
    private fun buildVideoClient(): OkHttpClient = OkHttpClient.Builder()
        .dispatcher(buildDispatcher())
        .addInterceptor(com.mediaviewer.util.BlockedHostsInterceptors.interceptor)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .addInterceptor { chain ->
            val req = chain.request().newBuilder().header("User-Agent", "MediaViewer/1.0 (ATProto client)").build()
            chain.proceed(req)
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    fun clientFor(profile: HttpProfile): OkHttpClient = when (profile) {
        HttpProfile.DIRECT_SERVICE -> buildDirectServiceClient()
        HttpProfile.VIDEO -> buildVideoClient()
        HttpProfile.DOWNLOAD -> downloadClient
        else -> buildOkHttp(profile.userAgent)
    }

    /** Plain OkHttpClient for streaming downloads */
    val downloadClient: OkHttpClient by lazy { buildOkHttp() }
}

/** A content:// (or file://) Uri streamed as a request body. */
class AndroidUriStreamSource(
    val context: android.content.Context,
    val uri: android.net.Uri,
    override val contentLength: Long,
) : StreamSource

private class OkHttpEngine(private val client: OkHttpClient) : HttpEngine {
    override suspend fun execute(request: HttpRequest): HttpResponseData {
        val urlBuilder = request.url.toHttpUrl().newBuilder()
        for ((k, v) in request.query) urlBuilder.addQueryParameter(k, v)
        val builder = Request.Builder().url(urlBuilder.build())
        for ((k, v) in request.headers) builder.addHeader(k, v)
        val body = request.body
        val okBody: okhttp3.RequestBody? = when {
            body == null -> null
            body.form != null -> FormBody.Builder().apply { body.form.forEach { (k, v) -> add(k, v) } }.build()
            body.stream != null -> {
                val src = body.stream as AndroidUriStreamSource
                val type = body.contentType?.toMediaTypeOrNull()
                object : okhttp3.RequestBody() {
                    override fun contentType() = type
                    override fun contentLength() = src.contentLength
                    override fun writeTo(sink: okio.BufferedSink) {
                        val input = src.context.contentResolver.openInputStream(src.uri) ?: throw java.io.IOException("Couldn't read the video")
                        input.use { stream -> sink.writeAll(stream.source()) }
                    }
                }
            }
            else -> (body.bytes ?: ByteArray(0)).toRequestBody(body.contentType?.toMediaTypeOrNull())
        }
        builder.method(request.method, okBody ?: if (request.method == "POST" || request.method == "PUT" || request.method == "PATCH") ByteArray(0).toRequestBody(null) else null)
        val call = client.newCall(builder.build())
        val response = suspendCancellableCoroutine<okhttp3.Response> { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) { cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: okhttp3.Response) { cont.resume(response) }
            })
        }
        return response.use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            HttpResponseData(
                code = r.code,
                message = r.message,
                headers = r.headers.map { it.first to it.second },
                body = bytes,
                contentType = r.body?.contentType()?.toString(),
            )
        }
    }
}

actual fun createHttpEngine(profile: HttpProfile): HttpEngine = OkHttpEngine(AndroidHttpClients.clientFor(profile))
