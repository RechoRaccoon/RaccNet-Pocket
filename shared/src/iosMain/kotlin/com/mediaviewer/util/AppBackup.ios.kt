package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri

actual object AppBackup {
    actual suspend fun export(context: PlatformContext, uri: PlatformUri): String =
        throw UnsupportedOperationException("App data backup isn't available on iOS yet")

    actual suspend fun import(context: PlatformContext, uri: PlatformUri): String =
        throw UnsupportedOperationException("App data backup isn't available on iOS yet")
}
