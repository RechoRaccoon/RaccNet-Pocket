package com.mediaviewer.util

import com.mediaviewer.platform.PlatformTranslator
import com.mediaviewer.platform.TranslationOutcome

/**
 * Shared facade preserving the legacy `TranslationManager` call sites
 * (e.g. MainFeedScreen's translate affordance).
 *
 * This is intentionally thin: the outcome model and language list live in
 * `platform/Translator.kt` ([TranslationOutcome]/[PlatformTranslator]) and
 * are NOT duplicated here — [Outcome] is a typealias so existing
 * `TranslationManager.Outcome.Success/Skipped/Failure` references keep
 * compiling unchanged. Only the engine differs per platform (ML Kit on
 * Android; unavailable on web — shared UI hides the toggle when
 * [isAvailable] is false).
 */
object TranslationManager {

    private val engine = PlatformTranslator()

    /** False on platforms without a translation engine (web). */
    val isAvailable: Boolean get() = engine.isAvailable

    /** Identifies the source language and translates to [targetLanguageTag]
     *  (BCP-47 tag). Never throws — failures come back as [Outcome.Failure]. */
    suspend fun translate(text: String, targetLanguageTag: String): Outcome =
        engine.translate(text, targetLanguageTag)

    /** Human-readable display name for a BCP-47 tag, e.g. "ja" -> "Japanese". */
    fun displayNameFor(languageTag: String): String =
        engine.displayNameFor(languageTag)

    /** (tag, display name) pairs surfaced in the Settings language picker. */
    val supportedLanguages: List<Pair<String, String>> get() = engine.supportedLanguages
}
