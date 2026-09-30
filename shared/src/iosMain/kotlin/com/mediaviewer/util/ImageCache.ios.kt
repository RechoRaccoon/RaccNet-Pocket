package com.mediaviewer.util

import coil3.SingletonImageLoader

actual fun removeFromImageCache(context: com.mediaviewer.platform.PlatformContext, urls: List<String>): Int {
    val loader = SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
    var removed = 0
    val disk = loader.diskCache
    for (u in urls) {
        if (runCatching { disk?.remove(u) }.getOrNull() == true) removed++
    }
    runCatching { loader.memoryCache?.clear() }
    return removed
}
