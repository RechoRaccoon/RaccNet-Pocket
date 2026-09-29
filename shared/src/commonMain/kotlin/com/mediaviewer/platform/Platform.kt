package com.mediaviewer.platform

/** Which platform this build of Stellar is running on. Shared code checks
 *  this to hide features a platform can't support (e.g. the audio
 *  visualizer on iOS) — never to remove them from Android. */
enum class PlatformKind { ANDROID, IOS }

expect val currentPlatform: PlatformKind
