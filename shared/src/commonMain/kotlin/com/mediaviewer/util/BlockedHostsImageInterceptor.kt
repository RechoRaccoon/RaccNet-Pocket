package com.mediaviewer.util

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult

/** Coil interceptor: never loads an image from a blocked host (TMDB — see
 *  [BlockedHosts]). Used by the iOS image loader; Android blocks the same
 *  hosts in its OkHttp client. */
object BlockedHostsImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val url = data as? String ?: data.toString().takeIf { it.startsWith("http") }
        if (url != null && BlockedHosts.isBlockedUrl(url)) {
            return ErrorResult(null, chain.request, IllegalStateException("Blocked by Stellar"))
        }
        return chain.proceed()
    }
}
