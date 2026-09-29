// check:jvm
package com.mediaviewer.util

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** OkHttp side of [BlockedHosts] (Android's HTTP clients and image loader). */
object BlockedHostsInterceptors {
    /** Sets Stellar's identifying User-Agent on Wikimedia requests, as
     *  Wikimedia's API/User-Agent policy asks. */
    val wikimediaUserAgent = Interceptor { chain ->
        val request = chain.request()
        val h = request.url.host
        if (h == "upload.wikimedia.org" || h.endsWith(".wikipedia.org") || h.endsWith(".wikidata.org")) {
            chain.proceed(request.newBuilder().header("User-Agent", BlockedHosts.WIKIMEDIA_USER_AGENT).build())
        } else chain.proceed(request)
    }

    /** OkHttp interceptor: answers a blocked host with a local 403 — the
     *  request never leaves the phone. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (BlockedHosts.isBlocked(request.url.host)) {
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Blocked by Stellar")
                .body("".toResponseBody(null))
                .build()
        } else chain.proceed(request)
    }
}
