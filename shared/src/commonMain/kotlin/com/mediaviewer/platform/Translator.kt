package com.mediaviewer.platform

/**
 * Post translation. APP-EXCLUSIVE on Android (ML Kit on-device models);
 * the web actual is a no-op and shared UI must hide the translation toggle
 * when [isAvailable] is false.
 *
 * The outcome model and the curated language list are ported verbatim from
 * the legacy TranslationManager into shared code; only the engine differs.
 */
sealed interface TranslationOutcome {
    data class Success(
        val sourceLanguageTag: String,
        val sourceLanguageDisplayName: String,
        val translatedText: String,
    ) : TranslationOutcome

    /** Source language unknown or already the target language — show nothing. */
    data object Skipped : TranslationOutcome

    data class Failure(val message: String) : TranslationOutcome
}

expect class PlatformTranslator() {

    /** False on platforms without a translation engine (web). */
    val isAvailable: Boolean

    /** Identifies the source language and translates to [targetLang]
     *  (BCP-47 tag). Never throws — failures come back as [TranslationOutcome.Failure]. */
    suspend fun translate(text: String, targetLang: String): TranslationOutcome

    /** Human-readable display name for a BCP-47 tag, e.g. "ja" -> "Japanese". */
    fun displayNameFor(languageTag: String): String

    /** (tag, display name) pairs surfaced in the Settings language picker. */
    val supportedLanguages: List<Pair<String, String>>
}
