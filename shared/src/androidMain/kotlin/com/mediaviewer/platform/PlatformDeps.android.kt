package com.mediaviewer.platform

/** Builds the Android platform dependency bundle. */
actual fun createPlatformDeps(): PlatformDeps = PlatformDeps(
    settings = createObservableSettings(),
    downloader = PlatformDownloader(),
    notifier = PlatformNotifier(),
    translator = PlatformTranslator(),
    tagger = PlatformImageTagger(),
    tagging = TaggingController(),
)
