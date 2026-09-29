package com.mediaviewer.platform

import platform.UIKit.*

object IosHaptics {
    fun tick() {
        runCatching {
            val generator = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
            generator.impactOccurred()
        }
    }
}
