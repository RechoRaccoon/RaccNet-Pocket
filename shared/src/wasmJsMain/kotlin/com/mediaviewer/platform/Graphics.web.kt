package com.mediaviewer.platform

/**
 * wasmJs textshot rendering — STUBBED.
 *
 * The canvas 2D API bindings (fillStyle/textAlign/textBaseline on the 2D
 * context, CanvasTextAlign/CanvasTextBaseline enums) do not resolve on
 * Kotlin 2.2.0's wasmJs target, and asDynamic() is deprecated (error-level).
 * The new @JsFun/external-interface interop pattern needs compiler
 * verification that isn't available here, so this is a stub rather than a
 * broken "full" implementation.
 *
 * TODO(PORT): Reimplement via external interface declarations for
 * CanvasRenderingContext2D once the Kotlin 2.x JS interop pattern is
 * verified in CI. The Android implementation in Graphics.android.kt is the
 * reference (shrink-to-fit white centered text on transparent square).
 */
actual fun renderTextshot(lines: List<TextshotLine>, widthPx: Int): ByteArray =
    TODO("PORT: canvas 2D textshot rendering not implemented on web yet")

/**
 * Animated GIF encoding has no web equivalent in this codebase (the Android
 * build carries a hand-rolled GIF89a/NeuQuant encoder). Callers must handle
 * the failure — e.g. fall back to downloading the source video.
 */
actual suspend fun encodeGif(frames: List<ByteArray>, delayMs: Int): ByteArray =
    TODO("PORT: no GIF pipeline on web")
