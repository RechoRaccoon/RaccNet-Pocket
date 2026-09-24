package com.mediaviewer.util

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * VRM pipeline step 1, sibling of [FaceLandmarkerHelper] — same LIVE_STREAM
 * pattern, MediaPipe's `HandLandmarker` instead. Face + hands both always
 * run regardless of the Settings toggles (see `VrmModeScreen.kt`'s doc
 * comment) — only pose/body tracking is gated by "Upper Body"/"Full Body".
 *
 * Needs `hand_landmarker.task` bundled at
 * `app/src/main/assets/hand_landmarker.task`, same as face_landmarker.task
 * was — download from
 * https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task
 * (also Apache 2.0, confirmed the same way face_landmarker.task was).
 * Missing asset → [create] returns null, logged, no crash.
 *
 * Same "unverified against the actual AAR" caveat as
 * [FaceLandmarkerHelper] applies here.
 */
class HandLandmarkerHelper private constructor(
    private val handLandmarker: HandLandmarker
) {
    /** Shares the [MPImage] [VrmCameraPreview] already decoded once per
     *  frame across all three landmarkers — see [FaceLandmarkerHelper]'s
     *  matching method for why. */
    fun detectAsync(mpImage: MPImage, rotationDegrees: Int, timestampMs: Long) {
        val processingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(rotationDegrees)
            .build()
        runCatching {
            handLandmarker.detectAsync(mpImage, processingOptions, timestampMs)
        }.onFailure { Log.e(TAG, "detectAsync failed", it) }
    }

    fun close() = runCatching { handLandmarker.close() }

    companion object {
        private const val TAG = "HandLandmarkerHelper"
        private const val MODEL_ASSET_PATH = "hand_landmarker.task"

        fun create(context: Context, onResult: (HandLandmarkerResult) -> Unit): HandLandmarkerHelper? =
            runCatching {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .setDelegate(BaseOptions.Delegate.GPU)
                    .build()
                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { result, _ -> onResult(result) }
                    .setErrorListener { e -> Log.e(TAG, "MediaPipe runtime error", e) }
                    .build()
                HandLandmarkerHelper(HandLandmarker.createFromOptions(context, options))
            }.onFailure {
                Log.e(TAG, "Could not create HandLandmarker — is $MODEL_ASSET_PATH in app/src/main/assets/?", it)
            }.getOrNull()
    }
}
