pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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
