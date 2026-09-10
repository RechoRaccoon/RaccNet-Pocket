plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget()
}

android {
    // Kept from the legacy app on purpose: the rebrand from "MediaViewer" /
    // "RaccNet Lite" to "RaccNet Pocket" was never finished in code
    // (package com.mediaviewer, id rechoraccoon.raccnetlite). Changing these
    // now would break updates for existing installs, so they stay.
    namespace = "com.mediaviewer"
    compileSdk = 34

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

    // Release signing comes ONLY from environment variables, populated in CI
    // from GitHub Secrets (see .github/workflows/build.yml). Nothing secret is
    // ever committed to this repo. When the variables are absent (local dev,
    // PR builds from forks), the release build falls back to debug signing so
    // `assembleRelease` still compiles everywhere.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_STORE_PASSWORD")
                keyAlias = System.getenv("KEYSTORE_KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = if (!System.getenv("KEYSTORE_PATH").isNullOrBlank())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}
