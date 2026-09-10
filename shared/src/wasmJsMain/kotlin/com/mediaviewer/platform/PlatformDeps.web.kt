package com.mediaviewer.platform

/** Builds the web platform dependency bundle (no-op AI surfaces). */
actual fun createPlatformDeps(): PlatformDeps = PlatformDeps(
    settings = createObservableSettings(),
    downloader = PlatformDownloader(),
    notifier = PlatformNotifier(),
    translator = PlatformTranslator(),
    tagger = PlatformImageTagger(),
    tagging = TaggingController(),
)
