package com.mediaviewer.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Fix 9: every button/clickable across the app should give haptic feedback,
 * and it should all feel identical — one light tap everywhere. This is the
 * single shared helper for that: call `val tap = rememberHapticTap()` once
 * per composable, then invoke `tap()` at the top of each onClick handler
 * (before the handler's real work).
 *
 * Uses [HapticFeedbackType.TextHandleMove] — the light tick — rather than
 * the heavier [HapticFeedbackType.LongPress] some older call sites used, so
 * the whole app has one consistent, subtle tap feel.
 */
@Composable
fun rememberHapticTap(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) {
        { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
}
