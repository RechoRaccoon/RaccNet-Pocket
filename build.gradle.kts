plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.21" apply false
    // Kotlin 2.x ships the Compose compiler as a Kotlin plugin (replaces
    // composeOptions.kotlinCompilerExtensionVersion).
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    // kotlinx.serialization (JSON for the API models and caches; replaces Gson).
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21" apply false
    // Compose Multiplatform: the same Compose APIs on Android (where it
    // resolves to the regular androidx.compose artifacts) and iOS.
    id("org.jetbrains.compose") version "1.8.2" apply false
}
