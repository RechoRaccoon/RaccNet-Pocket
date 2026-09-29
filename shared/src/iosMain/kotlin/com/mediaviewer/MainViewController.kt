package com.mediaviewer

import platform.UIKit.*
import androidx.compose.ui.window.ComposeUIViewController

/** Entry point the Swift app (iosApp/iosApp/iOSApp.swift) hosts. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    IosPreviewScreen()
}
