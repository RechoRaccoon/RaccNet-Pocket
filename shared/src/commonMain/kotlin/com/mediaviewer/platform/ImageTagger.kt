package com.mediaviewer.platform

/** One predicted tag from the image tagger. */
data class PredictedTag(
    val name: String,
    val confidence: Float,
)

/**
 * AI image tagging (the Z3D-E621-Convnext tagger). APP-EXCLUSIVE on Android:
 * the model is a ~390MB ONNX file run via onnxruntime-android with NNAPI /
 * XNNPACK. There is no web equivalent in this codebase, so the wasmJs actual
 * reports [isAvailable] = false and shared UI must hide all tagging surfaces
 * (tag-on-like toggle, TaggingOverlay, tag search) when it is false.
 *
 * The legacy TaggerModelManager (one-time HuggingFace download with progress)
 * maps to [ensureReady]; the legacy ImageTagger (bitmap in, tags out) maps to
 * [tagImage], taking encoded image bytes instead of an Android Bitmap so the
 * signature can live in common code.
 */
expect class PlatformImageTagger() {

    /** False on platforms without the tagging engine (web). */
    val isAvailable: Boolean

    /**
     * Makes sure the model is present and loaded (downloading it on first use).
     * Reports 0..1 progress. Returns false when tagging cannot be provided.
     */
    suspend fun ensureReady(onProgress: (Float) -> Unit = {}): Boolean

    /**
     * Runs the tagger over encoded image bytes (JPEG/PNG/…) and returns every
     * tag clearing the confidence threshold. Returns emptyList() when unavailable.
     */
    suspend fun tagImage(imageBytes: ByteArray): List<PredictedTag>
}
