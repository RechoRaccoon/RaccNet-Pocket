package com.mediaviewer.platform

/** One styled line of a "textshot" (text rendered to an image for posting). */
data class TextshotLine(
    val text: String,
    val isBold: Boolean = false,
)

/**
 * Renders textshot lines to PNG bytes.
 *
 * androidMain: android.graphics Canvas + StaticLayout (ported from the legacy
 *              TextshotRenderer).
 * wasmJsMain:  canvas 2D API (best effort).
 */
expect fun renderTextshot(lines: List<TextshotLine>, widthPx: Int): ByteArray

/**
 * Encodes decoded image frames (PNG/JPEG bytes) into an animated GIF.
 *
 * androidMain: ported legacy GifEncoder over Bitmap-decoded frames.
 * wasmJsMain:  not implemented (no GIF pipeline on web) — callers must
 *              handle the failure (e.g. fall back to downloading the video).
 */
expect suspend fun encodeGif(frames: List<ByteArray>, delayMs: Int): ByteArray
