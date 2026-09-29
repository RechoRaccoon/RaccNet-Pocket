package com.mediaviewer.network

import com.mediaviewer.json.StellarJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Base for Stellar's API classes (BlueskyApi, E621Api, …). Each method
 * builds a request the way its Retrofit annotation used to:
 *  - @Query values → query params (null values left out, lists repeated)
 *  - @Header values → headers (null values left out); a Content-Type
 *    header sets the body's media type, as in Retrofit
 *  - @Body → JSON via kotlinx.serialization (Gson's field names/rules, see
 *    StellarJson)
 *  - Response<Unit> / Response<ResponseBody> handled like Retrofit's
 *    built-in converters
 */
abstract class ApiClient(baseUrl: String, profile: HttpProfile) {
    /** Base URL with a trailing slash, like Retrofit's baseUrl. */
    val baseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val dropProxyHeader = profile == HttpProfile.DIRECT_SERVICE
    @PublishedApi internal val engine: HttpEngine = createHttpEngine(profile)

    @PublishedApi internal fun buildRequest(
        method: String,
        path: String,
        headers: List<Pair<String, String?>>,
        query: List<Pair<String, Any?>>,
        body: RequestBody?,
    ): HttpRequest {
        val q = ArrayList<Pair<String, String>>()
        for ((k, v) in query) {
            when (v) {
                null -> {}
                is Iterable<*> -> v.forEach { item -> if (item != null) q += k to item.toString() }
                else -> q += k to v.toString()
            }
        }
        var contentType = body?.contentType
        val h = ArrayList<Pair<String, String>>()
        for ((k, v) in headers) {
            if (v == null) continue
            if (dropProxyHeader && k.equals("atproto-proxy", ignoreCase = true)) continue
            if (k.equals("Content-Type", ignoreCase = true)) { contentType = v; continue }
            h += k to v
        }
        val finalBody = if (body != null && contentType != body.contentType)
            RequestBody(contentType, body.bytes, body.form, body.stream) else body
        return HttpRequest(method, baseUrl + path.removePrefix("/"), q, h, finalBody)
    }

    @PublishedApi internal fun jsonBody(text: String): RequestBody =
        RequestBody("application/json; charset=UTF-8", text.encodeToByteArray())

    @PublishedApi internal fun <T> toResponse(raw: HttpResponseData, kind: BodyKind, serializer: KSerializer<T>?): Response<T> {
        val headers = Headers(raw.headers)
        if (raw.code !in 200..299) {
            return Response(raw.code, raw.message, headers, null, ResponseBody(raw.body, raw.contentType))
        }
        @Suppress("UNCHECKED_CAST")
        val body: T? = when {
            kind == BodyKind.UNIT -> Unit as T
            kind == BodyKind.RAW -> ResponseBody(raw.body, raw.contentType) as T
            raw.code == 204 || raw.code == 205 -> null
            else -> StellarJson.default.decodeFromString(serializer!!, raw.body.decodeToString())
        }
        return Response(raw.code, raw.message, headers, body, null)
    }

    @PublishedApi internal enum class BodyKind { JSON, UNIT, RAW }

    /** One API call. [T] is the response body type. */
    @PublishedApi internal suspend inline fun <reified T> call(
        method: String,
        path: String,
        headers: List<Pair<String, String?>> = emptyList(),
        query: List<Pair<String, Any?>> = emptyList(),
        body: RequestBody? = null,
    ): Response<T> {
        val kind = when (T::class) {
            Unit::class -> BodyKind.UNIT
            ResponseBody::class -> BodyKind.RAW
            else -> BodyKind.JSON
        }
        val serializer: KSerializer<T>? = if (kind == BodyKind.JSON) serializer<T>() else null
        val raw = engine.execute(buildRequest(method, path, headers, query, body))
        return toResponse(raw, kind, serializer)
    }

    /** JSON body for a @Body parameter. */
    @PublishedApi internal inline fun <reified B> json(value: B): RequestBody =
        jsonBody(StellarJson.default.encodeToString(serializer<B>(), value))
}

/** One-off requests outside an API class (DID documents, Wikipedia, blob
 *  resolution…) — the old `NetworkClient.downloadClient.newCall(...)`. */
object PlainHttp {
    private val engine by lazy { createHttpEngine(HttpProfile.DOWNLOAD) }

    /** GET [url] with [query] parameters added (encoded like OkHttp's
     *  HttpUrl.Builder.addQueryParameter). */
    suspend fun get(url: String, query: List<Pair<String, String>>, headers: List<Pair<String, String>> = emptyList()): HttpResponseData =
        engine.execute(HttpRequest("GET", url, query, headers, null))

    suspend fun get(url: String, headers: List<Pair<String, String>> = emptyList()): HttpResponseData {
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', "").takeIf { it.isNotEmpty() }
        // Already-encoded query from a full URL: pass it through as-is.
        return engine.execute(HttpRequest("GET", if (query != null) "$base?$query" else base, emptyList(), headers, null))
    }
}

val HttpResponseData.isSuccessful: Boolean get() = code in 200..299
fun HttpResponseData.bodyString(): String = body.decodeToString()
