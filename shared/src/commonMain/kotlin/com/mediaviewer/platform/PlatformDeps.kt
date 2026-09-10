package com.mediaviewer.platform

import com.russhwolf.settings.ObservableSettings

/**
 * Everything platform-specific the shared app graph needs, bundled so
 * MainViewModel (and App composition) can take a single constructor param
 * instead of an Android Application / Context.
 */
data class PlatformDeps(
    val settings: ObservableSettings,
    val downloader: PlatformDownloader,
    val notifier: PlatformNotifier,
    val translator: PlatformTranslator,
    val tagger: PlatformImageTagger,
    val tagging: TaggingController,
)

/** Builds the platform dependency bundle (actuals in androidMain/wasmJsMain). */
expect fun createPlatformDeps(): PlatformDeps
