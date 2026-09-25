package com.mediaviewer.util

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * VRM pipeline step 1, sibling of [FaceLandmarkerHelper] — same LIVE_STREAM
 * pattern, MediaPipe's `PoseLandmarker` instead. Unlike face/hands, this
 * one is only meant to run while the "Upper Body" Settings toggle is on
 * (see `VrmModeScreen.kt`) — [VrmCameraTracking] is what enforces that by
 * only calling [detectAsync] when that flag is set, not this class itself.
 * "Full Body" doesn't change which model runs — `PoseLandmarker` already
 * outputs all 33 body points including legs — it'll gate whether step 3's
 * retargeting *uses* the leg points once that step exists.
 *
 * Needs `pose_landmarker_full.task` bundled at
 * `app/src/main/assets/pose_landmarker_full.task` — download from
 * https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task
 * (also Apache 2.0). `_lite` is a smaller/faster variant worth trying on
 * an actual device if `_full` turns out too slow; same download path with
 * `pose_landmarker_lite` instead. Missing asset → [create] returns null,
 * logged, no crash.
 *
 * Same "unverified against the actual AAR" caveat as
 * [FaceLandmarkerHelper] applies here.
 */
class PoseLandmarkerHelper private constructor(
    private val poseLandmarker: PoseLandmarker
) {
    /** Shares the [MPImage] [VrmCameraTracking] already decoded once per
     *  frame across all three landmarkers — see [FaceLandmarkerHelper]'s
     *  matching method for why. */
    fun detectAsync(mpImage: MPImage, rotationDegrees: Int, timestampMs: Long) {
        val processingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(rotationDegrees)
            .build()
        runCatching {
            poseLandmarker.detectAsync(mpImage, processingOptions, timestampMs)
        }.onFailure { Log.e(TAG, "detectAsync failed", it) }
    }

    fun close() = runCatching { poseLandmarker.close() }

    companion object {
        private const val TAG = "PoseLandmarkerHelper"
        private const val MODEL_ASSET_PATH = "pose_landmarker_full.task"

        fun create(context: Context, onResult: (PoseLandmarkerResult) -> Unit): PoseLandmarkerHelper? =
            runCatching {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .setDelegate(Delegate.GPU)
                    .build()
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setOutputSegmentationMasks(false)
                    .setResultListener { result, _ -> onResult(result) }
                    .setErrorListener { e -> Log.e(TAG, "MediaPipe runtime error", e) }
                    .build()
                PoseLandmarkerHelper(PoseLandmarker.createFromOptions(context, options))
            }.onFailure {
                Log.e(TAG, "Could not create PoseLandmarker — is $MODEL_ASSET_PATH in app/src/main/assets/?", it)
            }.getOrNull()
    }
}
