package com.mediaviewer

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point the Swift app (iosApp/iosApp/iOSApp.swift) hosts. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    IosPreviewScreen()
}
