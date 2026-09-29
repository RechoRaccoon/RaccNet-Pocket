package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext

actual fun removeFromImageCache(context: PlatformContext, urls: List<String>): Int =
    com.mediaviewer.platform.ImageCacheHooks.remove(urls)
