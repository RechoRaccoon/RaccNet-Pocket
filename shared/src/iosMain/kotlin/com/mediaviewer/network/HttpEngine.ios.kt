package com.mediaviewer.network

import com.mediaviewer.util.BlockedHosts
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.takeFrom

/** iOS: Ktor on NSURLSession, configured like the Android OkHttp clients
 *  (User-Agent per profile, blocked hosts, similar timeouts). */
private class DarwinHttpEngine(private val profile: HttpProfile) : HttpEngine {
    private val client = HttpClient(Darwin) {
        expectSuccess = false
        followRedirects = true
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = if (profile == HttpProfile.VIDEO) 5 * 60_000L else 60_000L
            requestTimeoutMillis = if (profile == HttpProfile.VIDEO) 30 * 60_000L else 3 * 60_000L
        }
    }

    override suspend fun execute(request: HttpRequest): HttpResponseData {
        val url = URLBuilder().apply {
            takeFrom(request.url)
            for ((k, v) in request.query) parameters.append(k, v)
        }.buildString()
        val host = com.mediaviewer.platform.uriHost(url)
        if (BlockedHosts.isBlocked(host)) {
            return HttpResponseData(403, "Blocked by Stellar", emptyList(), ByteArray(0), null)
        }
        val userAgent = if (host != null && BlockedHosts.isWikimediaHost(host)) BlockedHosts.WIKIMEDIA_USER_AGENT else profile.userAgent
        val response: HttpResponse = try {
            client.request(url) {
                method = HttpMethod.parse(request.method)
                header("User-Agent", userAgent)
                for ((k, v) in request.headers) {
                    if (k.equals("User-Agent", ignoreCase = true)) continue
                    header(k, v)
                }
                val body = request.body
                when {
                    body == null -> {}
                    body.form != null -> setBody(FormDataContent(Parameters.build { body.form.forEach { (k, v) -> append(k, v) } }))
                    body.stream != null -> {
                        val src = body.stream as IosFileStreamSource
                        setBody(ByteArrayContent(src.readAll(), contentTypeOf(body.contentType)))
                    }
                    else -> setBody(ByteArrayContent(body.bytes ?: ByteArray(0), contentTypeOf(body.contentType)))
                }
            }
        } catch (e: com.mediaviewer.platform.IOException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Ktor/NSURLSession failures → IOException, as OkHttp reports them.
            throw com.mediaviewer.platform.IOException(e.message ?: "Network error", e)
        }
        val bytes: ByteArray = response.body()
        val headers = response.headers.entries().flatMap { (k, vs) -> vs.map { k to it } }
        return HttpResponseData(
            code = response.status.value,
            message = response.status.description,
            headers = headers,
            body = bytes,
            contentType = response.headers["Content-Type"],
        )
    }

    private fun contentTypeOf(type: String?): ContentType =
        type?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Application.OctetStream
}

actual fun createHttpEngine(profile: HttpProfile): HttpEngine = DarwinHttpEngine(profile)
