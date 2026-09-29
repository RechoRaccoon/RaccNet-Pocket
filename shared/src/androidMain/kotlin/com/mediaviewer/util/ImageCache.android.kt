package com.mediaviewer.util

import coil.Coil
import com.mediaviewer.platform.PlatformContext

actual fun removeFromImageCache(context: PlatformContext, urls: List<String>): Int {
    val loader = Coil.imageLoader(context.applicationContext)
    var removed = 0
    val disk = loader.diskCache
    for (u in urls) {
        if (runCatching { disk?.remove(u) }.getOrNull() == true) removed++
    }
    runCatching { loader.memoryCache?.clear() }
    return removed
}
