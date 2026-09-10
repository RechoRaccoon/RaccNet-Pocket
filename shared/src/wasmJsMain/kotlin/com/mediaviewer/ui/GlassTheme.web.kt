package com.mediaviewer.ui

import androidx.compose.ui.graphics.Color

/**
 * wasmJsMain actuals for the GlassTheme expects (see commonMain
 * ui/GlassTheme.kt).
 *
 * [fetchDominantColor] is STUBBED: the DOM image-loading pipeline
 * (<img> + canvas 2D pixel sampling) uses org.w3c.dom bindings that don't
 * resolve on Kotlin 2.2.0's wasmJs target. Returns the documented fallback
 * grey (0xFF2A2A2E) — the same value the full implementation returns for
 * blank/unreadable URLs, so callers already handle it gracefully.
 *
 * TODO(PORT): Reimplement via external interface declarations for
 * HTMLImageElement/canvas once the Kotlin 2.x JS interop pattern is
 * verified in CI. The Android implementation in GlassTheme.android.kt
 * (Coil fetch + 16x16 average) is the reference algorithm.
 */

/** Compose Multiplatform implements blur on web; the API-31 no-op case doesn't apply. */
internal actual val CAN_BLUR: Boolean = true

actual suspend fun fetchDominantColor(url: String): Color =
    Color(0xFF2A2A2E)
