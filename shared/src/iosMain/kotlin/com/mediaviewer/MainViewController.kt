package com.mediaviewer

import androidx.compose.ui.window.ComposeUIViewController
import com.mediaviewer.platform.IosAppPlatform
import com.mediaviewer.platform.IosContext
import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.readLocalFile
import com.mediaviewer.platform.writeLocalFile
import com.mediaviewer.ui.SharedAppHost
import com.mediaviewer.ui.compat.IosNativePickers
import com.mediaviewer.util.IosImageLoading
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi

/** Entry point the Swift app (iosApp/iosApp/iOSApp.swift) hosts. */
fun MainViewController(): UIViewController {
    IosCrashLog.install()
    IosImageLoading.install()
    IosNativePickers.install()
    val crash = IosCrashLog.read()
    return ComposeUIViewController {
        SharedAppHost(
            context = IosContext,
            createPlatform = { _, _ -> IosAppPlatform() },
            crashLog = crash,
            onCrashLogDismissed = { IosCrashLog.clear() }
        )
    }
}

/**
 * Same idea as Android's crash screen: an uncaught Kotlin exception is
 * written to a file before the app closes, and shown as copyable text the
 * next time Stellar opens (there's no Xcode console in this workflow).
 */
@OptIn(ExperimentalNativeApi::class)
private object IosCrashLog {
    private val path get() = IosPaths.filesDir() + "/last_crash.txt"

    fun install() {
        val previous = getUnhandledExceptionHook()
        setUnhandledExceptionHook { t ->
            runCatching { writeLocalFile(path, t.stackTraceToString().encodeToByteArray()) }
            previous?.invoke(t)
        }
    }

    fun read(): String? = readLocalFile(path)?.decodeToString()?.takeIf { it.isNotBlank() }

    fun clear() { platform.posix.remove(path) }
}
