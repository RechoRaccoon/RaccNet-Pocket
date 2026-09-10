pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS (not FAIL_ON_PROJECT_REPOS): the Kotlin wasmJs toolchain
    // downloads Node.js from https://nodejs.org/dist and registers that
    // repository itself — the strict mode rejects it and breaks the web build.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
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
