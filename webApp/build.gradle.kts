plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "raccnet-pocket.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            // Main.kt references ComposeViewport/@Composable directly; :shared only
            // exposes them as `implementation`, so they must be declared here too.
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.kotlinx.browser)
        }
    }
}
