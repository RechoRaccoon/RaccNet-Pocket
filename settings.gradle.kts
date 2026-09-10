pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Do NOT set repositoriesMode here. The Kotlin wasmJs toolchain downloads
    // Node.js from https://nodejs.org/dist and registers that repository
    // itself at build time. FAIL_ON_PROJECT_REPOS rejects it outright, and
    // PREFER_SETTINGS silently ignores it (then Node.js can't be found).
    // The default mode lets the plugin's repository work.
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RaccNetPocketKMP"

// One codebase, two products:
//   :shared     -> all UI + logic (commonMain / androidMain / wasmJsMain)
//   :androidApp -> thin native Android shell (Application + MainActivity)
//   :webApp     -> thin web shell (wasmJs entry + index.html)
include(":shared")
include(":androidApp")
include(":webApp")
