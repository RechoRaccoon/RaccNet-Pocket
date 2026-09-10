package com.mediaviewer.platform

/**
 * Web actual: no on-device translation engine exists for this codebase on web
 * (ML Kit is Android-only), so translation is unavailable. Shared UI must hide
 * the translation toggle whenever [isAvailable] is false — this is the
 * "AI stays app-exclusive" requirement.
 */
actual class PlatformTranslator actual constructor() {
    actual val isAvailable: Boolean = false

    actual suspend fun translate(text: String, targetLang: String): TranslationOutcome =
        TranslationOutcome.Failure("Translation is only available in the Android app.")

    actual fun displayNameFor(languageTag: String): String = languageTag

    actual val supportedLanguages: List<Pair<String, String>> = emptyList()
}
