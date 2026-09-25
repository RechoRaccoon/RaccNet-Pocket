plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mediaviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "rechoraccoon.raccnetlite"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // MediaPipe's native loader mmaps these asset files directly; if AAPT
    // compresses them (its default for any extension it doesn't
    // recognize), that mmap fails silently and FaceLandmarker/
    // HandLandmarker/PoseLandmarker.createFromOptions() all fail — which
    // reads as "tracking never starts, everything reports 0" with no
    // visible crash, since VrmModeScreen's helpers catch and log that
    // failure instead of throwing.
    androidResources {
        noCompress += listOf("task", "tflite")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-video:2.5.0")

    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    // Video-thumbnail stitching (VideoThumbnailStitcher): needs
    // media3-transformer 1.8.0+ for EditedMediaItemSequence +
    // experimentalSetForceAudioTrack, which is why compileSdk is 35.
    implementation("androidx.media3:media3-transformer:1.8.0")
    implementation("androidx.media3:media3-effect:1.8.0")
    implementation("androidx.media3:media3-muxer:1.8.0")

    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Phase 4 — on-device translation (ML Kit Translate + Language Identification).
    // Both models run fully on-device; no server round trip and no API key.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // AI Tagging feature: fully local ONNX inference for the e621-trained
    // Z3D-E621-Convnext tagger (see ImageTagger.kt) — no cloud calls, no
    // content-moderation layer, runs entirely on-device via NNAPI/XNNPACK.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // Item 8 — VRM/VTuber mode: CameraX for the live front-camera preview
    // that face/hand/body tracking runs against.
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Item 8, VRM pipeline step 1 — MediaPipe Tasks Vision. All three
    // landmarkers (Face/Hand/Pose) wired up now — see
    // util/FaceLandmarkerHelper.kt, HandLandmarkerHelper.kt,
    // PoseLandmarkerHelper.kt. Version pinned to the latest stable at the
    // time of this session — worth checking
    // https://developers.google.com/mediapipe/solutions/vision/face_landmarker
    // for anything newer before building.
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Item 8, VRM pipeline step 5 — Filament for rendering the VRM's glTF.
    // filament-android is the core renderer; filament-utils-android adds
    // `ModelViewer` (camera/manipulator/render-loop convenience wrapper)
    // and the gltfio glTF loader `ModelViewer.loadModelGlb` uses under the
    // hood — both from the same release train, kept on the same version.
    // Check https://github.com/google/filament/releases for anything newer
    // before building; Filament ships frequently.
    val filamentVersion = "1.51.6"
    implementation("com.google.android.filament:filament-android:$filamentVersion")
    implementation("com.google.android.filament:filament-utils-android:$filamentVersion")
    implementation("com.google.android.filament:gltfio-android:$filamentVersion")
}
