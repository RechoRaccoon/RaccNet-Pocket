plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget()

    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "raccnet-pocket.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            // Compose Multiplatform UI
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            // Networking: Ktor replaces Retrofit/OkHttp/Gson
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)

            // Async / serialization / time
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Preferences: multiplatform-settings replaces DataStore
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)

            // Images: Coil 3 Compose Multiplatform replaces Coil 2
            implementation(libs.coil3.compose)
            implementation(libs.compose.material.icons.extended)
            // Cross-platform back-press handling (replaces androidx.activity's Android-only BackHandler)
            implementation(libs.compose.ui.backhandler)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
            // Platform actuals (kept from the legacy app)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.ui)
            implementation(libs.onnxruntime.android)
            implementation(libs.mlkit.translate)
            implementation(libs.mlkit.language.id)
            implementation(libs.androidx.core.ktx)
        }

        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

android {
    // Library namespace; the user-facing applicationId lives in :androidApp.
    // Package com.mediaviewer is kept deliberately (legacy rebrand incomplete).
    namespace = "com.mediaviewer.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
