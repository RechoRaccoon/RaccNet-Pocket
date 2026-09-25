package com.mediaviewer.util

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * VRM pipeline step 1 (see VrmModeScreen.kt's doc comment): wraps MediaPipe's
 * FaceLandmarker in LIVE_STREAM mode, fed frames from VrmCameraTracking's
 * ImageAnalysis use case. Deliberately just the face landmarker for now, per
 * the handoff's build order — get this visibly working before adding
 * HandLandmarkerHelper / PoseLandmarkerHelper as siblings of this same
 * pattern.
 *
 * ## One piece of setup this file can't do for you
 * MediaPipe Tasks Vision loads its model from a `.task` file bundled as a
 * raw asset, not pulled in as a Maven artifact — it's a ~a few MB binary,
 * not source, and this sandbox has no network to fetch it. Before this
 * builds and actually detects anything:
 * 1. Download `face_landmarker.task` (float16 build is fine) from
 *    https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task
 * 2. Place it at `app/src/main/assets/face_landmarker.task` — see the
 *    `.gitkeep`-style placeholder left in that folder.
 * Until it's there, [create] catches the load failure, logs why, and
 * returns null — the camera preview still runs, there's just no tracking
 * data to log yet.
 *
 * ## Unverified against the actual AAR
 * Written against the documented MediaPipe Tasks Vision API from memory,
 * with no way to compile-check it in this sandbox (no Android SDK/network
 * here — same constraint noted in HANDOFF.md). The class/package paths
 * below (`BaseOptions.Delegate`, `FaceLandmarker.FaceLandmarkerOptions`,
 * `ImageProcessingOptions`) are the ones documented for recent
 * `tasks-vision` releases, but if the pinned version in `build.gradle.kts`
 * disagrees, Android Studio's error will point at exactly which one moved.
 */
class FaceLandmarkerHelper private constructor(
    private val faceLandmarker: FaceLandmarker
) {
    /** Runs detection on a frame [VrmCameraTracking] has already decoded once
     *  (see its analyzer) and shares across all three landmarkers, rather
     *  than each helper redoing its own YUV→Bitmap→MPImage conversion on
     *  every frame. Results arrive later, asynchronously, via the
     *  [create] result listener — this is LIVE_STREAM mode. */
    fun detectAsync(mpImage: MPImage, rotationDegrees: Int, timestampMs: Long) {
        val processingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(rotationDegrees)
            .build()
        runCatching {
            faceLandmarker.detectAsync(mpImage, processingOptions, timestampMs)
        }.onFailure { Log.e(TAG, "detectAsync failed", it) }
    }

    fun close() = runCatching { faceLandmarker.close() }

    companion object {
        private const val TAG = "FaceLandmarkerHelper"
        private const val MODEL_ASSET_PATH = "face_landmarker.task"

        /** Returns null (and logs why) instead of throwing if the model
         *  asset above isn't bundled yet, so callers — VrmCameraTracking —
         *  can fall back to "no tracking data" instead of crashing the
         *  whole VRM screen over a missing asset file. */
        fun create(context: Context, onResult: (FaceLandmarkerResult) -> Unit): FaceLandmarkerHelper? {
            // Try GPU first, fall back to CPU. The GPU delegate can fail
            // on specific devices/models even when the task file is fine —
            // without this fallback, face tracking silently never starts
            // ("no landmarker output yet") while hand tracking works.
            return tryCreate(context, Delegate.GPU, onResult)
                ?: tryCreate(context, Delegate.CPU, onResult).also {
                    if (it != null) Log.w(TAG, "FaceLandmarker GPU failed, using CPU")
                }
        }

        private fun tryCreate(
            context: Context,
            delegate: Delegate,
            onResult: (FaceLandmarkerResult) -> Unit
        ): FaceLandmarkerHelper? =
            runCatching {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .setDelegate(delegate)
                    .build()
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    // VRM pipeline step 6 (retargeting): head rotation needs
                    // more than the 2D landmark positions — this asks
                    // MediaPipe for the actual estimated 3D head pose (a
                    // 4x4 transform per detected face, camera-space) instead
                    // of hand-rolling a PnP-style estimate from landmark
                    // points ourselves. Read via FaceLandmarkerResult's own
                    // `facialTransformationMatrixes()` — see
                    // AvatarRetargeter.kt's doc comment for exactly how
                    // that's consumed and what's least certain about it.
                    .setOutputFacialTransformationMatrixes(true)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { result, _ -> onResult(result) }
                    .setErrorListener { e -> Log.e(TAG, "MediaPipe runtime error", e) }
                    .build()
                FaceLandmarkerHelper(FaceLandmarker.createFromOptions(context, options))
            }.onFailure {
                Log.e(TAG, "Could not create FaceLandmarker — is $MODEL_ASSET_PATH in app/src/main/assets/?", it)
            }.getOrNull()
    }
}
