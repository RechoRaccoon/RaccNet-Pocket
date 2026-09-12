package com.mediaviewer.network

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

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

    private fun buildOkHttp(userAgent: String = "MediaViewer/1.0"): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .dispatcher(buildDispatcher())
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .build()
                chain.proceed(req)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun buildBlueskyApi(baseUrl: String = "https://bsky.social/"): BlueskyApi {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(buildOkHttp("MediaViewer/1.0 (ATProto client)"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BlueskyApi::class.java)
    }

    fun buildE621Api(): E621Api {
        // e621 requires a descriptive User-Agent per their policy
        return Retrofit.Builder()
            .baseUrl("https://e621.net/")
            .client(buildOkHttp("MediaViewer/1.0 (by your_username)"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(E621Api::class.java)
    }

    fun buildStreamplaceApi(baseUrl: String = "https://stream.place/"): StreamplaceApi {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(buildOkHttp("MediaViewer/1.0 (ATProto client)"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StreamplaceApi::class.java)
    }

    // Compose Post (upload flow): video upload/processing lives on its own
    // service, separate from the user's PDS — see BlueskyRepository.
    // uploadVideoBlob. Longer timeouts than the default client since a
    // 300MB/10-minute video upload can legitimately take a while even with
    // Bluesky's faster 2026 upload pipeline.
    fun buildBlueskyVideoApi(): BlueskyVideoApi {
        val client = OkHttpClient.Builder()
            .dispatcher(buildDispatcher())
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .addInterceptor { chain ->
                val req = chain.request().newBuilder().header("User-Agent", "MediaViewer/1.0 (ATProto client)").build()
                chain.proceed(req)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://video.bsky.app/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BlueskyVideoApi::class.java)
    }

    /** Plain OkHttpClient for streaming downloads */
    val downloadClient: OkHttpClient by lazy { buildOkHttp() }
}
