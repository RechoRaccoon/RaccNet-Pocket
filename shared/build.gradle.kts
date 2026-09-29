// Shared code module (Kotlin Multiplatform).
//
// Targets: Android (the real app, all code in src/androidMain for now) and
// iOS (iosArm64 = iPhone, iosSimulatorArm64 = Simulator on Apple-silicon
// Macs). Portable code moves from src/androidMain into src/commonMain
// package by package; Android-only pieces stay in androidMain behind
// expect/actual declarations with iOS versions in src/iosMain.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Kotlin/Native iOS targets only build on macOS (the iOS workflow); on
    // the Linux Android runner they are skipped automatically.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
            languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi")
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            // Compose Multiplatform 1.8.2 = androidx Compose 1.8.x /
            // Material3 1.3.x on Android.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)

            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.9.1")
            // Settings (PreferencesManager): same "media_viewer_prefs" file
            // on Android as before, a file in the app sandbox on iOS.
            implementation("androidx.datastore:datastore-preferences-core:1.1.7")
        }
        iosMain.dependencies {
            // HTTP on iOS (NSURLSession underneath) — see HttpEngine.ios.kt.
            implementation("io.ktor:ktor-client-core:3.1.3")
            implementation("io.ktor:ktor-client-darwin:3.1.3")
            // Locks for the iOS ConcurrentHashMap / SharedPreferences stand-ins.
            implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation("androidx.activity:activity-compose:1.8.2")
            implementation("androidx.core:core-ktx:1.12.0")
            // Performance: installs the baseline profiles Compose/Material/Media3
            // ship inside their AARs, so their hot paths get AOT-compiled on a
            // sideloaded install instead of running interpreted/JIT for the first
            // several launches (the main cause of first-open jank on menus).
            implementation("androidx.profileinstaller:profileinstaller:1.3.1")

            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")

            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

            implementation("io.coil-kt:coil-compose:2.5.0")
            implementation("io.coil-kt:coil-video:2.5.0")

            implementation("androidx.media3:media3-exoplayer:1.8.0")
            implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
            implementation("androidx.media3:media3-ui:1.8.0")
            // Feed video preloading + on-disk cache (FeedVideoPool): SimpleCache /
            // CacheDataSource live in datasource, its index DB in database. Both are
            // already transitive deps of exoplayer; declared so the imports are stable.
            implementation("androidx.media3:media3-datasource:1.8.0")
            implementation("androidx.media3:media3-database:1.8.0")
            // Video-thumbnail stitching (VideoThumbnailStitcher): needs
            // media3-transformer 1.8.0+ for EditedMediaItemSequence +
            // experimentalSetForceAudioTrack, which is why compileSdk is 35.
            implementation("androidx.media3:media3-transformer:1.8.0")
            implementation("androidx.media3:media3-effect:1.8.0")
            implementation("androidx.media3:media3-muxer:1.8.0")

            implementation("androidx.datastore:datastore-preferences:1.1.7")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            implementation("com.google.code.gson:gson:2.10.1")
            // Profile QR codes (ProfileQrScreen): ZXing's encoder only — the QR
            // matrix it produces is drawn by Compose in Stellar's own style.
            implementation("com.google.zxing:core:3.5.3")
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
            val cameraxVersion = "1.4.2"
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
    }
}

android {
    // Same namespace as the old :app module so com.mediaviewer.R keeps
    // working unchanged.
    namespace = "com.mediaviewer"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    // lifecycle 2.9's bundled lint checks were compiled against a newer lint
    // than AGP 8.7 ships and crash lintVital (IncompatibleClassChangeError in
    // NonNullableMutableLiveDataDetector). Lint never changes the APK, so
    // skip the release-time lint pass and that detector.
    lint {
        disable += "NullSafeMutableLiveData"
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
