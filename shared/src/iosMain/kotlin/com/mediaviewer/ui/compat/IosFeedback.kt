package com.mediaviewer.ui.compat

import platform.Foundation.*
import platform.UIKit.*

/** Android haptic constants → iOS feedback generators. */
internal object IosFeedback {
    fun perform(constant: Int) {
        runCatching {
            when (constant) {
                HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.KEYBOARD_TAP ->
                    UISelectionFeedbackGenerator().selectionChanged()
                HapticFeedbackConstants.CONFIRM ->
                    UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
                HapticFeedbackConstants.REJECT ->
                    UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
                else ->
                    UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium).impactOccurred()
            }
        }
    }
}

internal object IosLinks {
    fun open(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}
