package com.mediaviewer.platform

/**
 * Web actual: the Z3D-E621-Convnext tagger is a ~390MB ONNX model run via
 * onnxruntime-android — app-exclusive by product decision. All tagging
 * surfaces must be hidden in shared UI when [isAvailable] is false.
 */
actual class PlatformImageTagger actual constructor() {
    actual val isAvailable: Boolean = false

    actual suspend fun ensureReady(onProgress: (Float) -> Unit): Boolean = false

    actual suspend fun tagImage(imageBytes: ByteArray): List<PredictedTag> = emptyList()
}
