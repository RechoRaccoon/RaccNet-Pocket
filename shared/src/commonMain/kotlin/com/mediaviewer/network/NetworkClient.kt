package com.mediaviewer.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

// KMP port of the Android app's NetworkClient: same factory functions,
// Ktor-backed. The HttpClient engine is deliberately unnamed in common code —
// OkHttp on Android and Js on web resolve automatically via the platform
// source sets' dependencies (see shared/build.gradle.kts).
object NetworkClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    // Port note: OkHttp's Dispatcher (maxRequests=64, maxRequestsPerHost=16,
    // raised from the defaults to stop this app's cold-start traffic from
    // queueing behind itself — see the original comment) has no Ktor
    // equivalent; Ktor manages its own connection pooling internally. The
    // timeouts and per-client User-Agent strings below are preserved
    // exactly.
    private fun buildClient(
        userAgent: String = "MediaViewer/1.0",
        requestTimeoutMillis: Long = 60_000,
        socketTimeoutMillis: Long = 60_000
    ): HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            this.requestTimeoutMillis = requestTimeoutMillis
            this.socketTimeoutMillis = socketTimeoutMillis
        }
        defaultRequest {
            header("User-Agent", userAgent)
        }
        // No engine named here: resolves per-platform automatically.
    }

    fun buildBlueskyApi(baseUrl: String = "https://bsky.social/"): BlueskyApi =
        BlueskyApi(buildClient("MediaViewer/1.0 (ATProto client)"), baseUrl)

    fun buildE621Api(): E621Api {
        // e621 requires a descriptive User-Agent per their policy
        return E621Api(buildClient("MediaViewer/1.0 (by your_username)"), "https://e621.net/")
    }

    fun buildStreamplaceApi(baseUrl: String = "https://stream.place/"): StreamplaceApi =
        StreamplaceApi(buildClient("MediaViewer/1.0 (ATProto client)"), baseUrl)

    // Compose Post (upload flow): video upload/processing lives on its own
    // service, separate from the user's PDS — see BlueskyRepository.
    // uploadVideoBlob. Longer timeouts than the default client since a
    // 300MB/10-minute video upload can legitimately take a while even with
    // Bluesky's faster 2026 upload pipeline.
    fun buildBlueskyVideoApi(): BlueskyVideoApi =
        BlueskyVideoApi(
            buildClient(
                userAgent = "MediaViewer/1.0 (ATProto client)",
                requestTimeoutMillis = 5 * 60_000,
                socketTimeoutMillis = 5 * 60_000
            ),
            "https://video.bsky.app/"
        )

    /** Plain HttpClient for streaming downloads */
    val downloadClient: HttpClient by lazy { buildClient() }
}
