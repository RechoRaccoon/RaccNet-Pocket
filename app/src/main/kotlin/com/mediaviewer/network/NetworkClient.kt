package com.mediaviewer.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

    private fun buildOkHttp(userAgent: String = "MediaViewer/1.0"): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
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

    /** Plain OkHttpClient for streaming downloads */
    val downloadClient: OkHttpClient by lazy { buildOkHttp() }
}
