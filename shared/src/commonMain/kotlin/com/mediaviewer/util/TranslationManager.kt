package com.mediaviewer.util

/**
 * Phase 4 — on-device translation. The shared part: the picker's language
 * list, display names and the translate() flow. The actual detection and
 * translation run in a platform [TranslationEngine] — ML Kit on Android
 * (text never leaves the phone). iOS has no engine yet (Apple's Translation
 * framework is Swift-only; the iOS app can plug one in via [engine]), so
 * there [isAvailable] is false and the setting shows as unavailable.
 */
object TranslationManager {

    sealed class Outcome {
        data class Success(
            val sourceLanguageTag: String,
            val sourceLanguageDisplayName: String,
            val translatedText: String
        ) : Outcome()
        /** Source language couldn't be determined, or was already the target
         *  language — nothing useful to translate, so no indicator should show. */
        object Skipped : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    /** The platform's detector + translator (null = not available). */
    var engine: TranslationEngine? = defaultTranslationEngine()

    val isAvailable: Boolean get() = engine != null

    /** Human-readable display name for a BCP-47 language tag, e.g. "ja" -> "Japanese". */
    fun displayNameFor(languageTag: String): String {
        val name = englishLanguageName(languageTag)
        return if (name.isBlank()) languageTag else name.replaceFirstChar { it.uppercase() }
    }

    /** Curated list of common translation targets — Spanish and Japanese are
     *  pinned first per the Phase 4 spec's stated priority languages; this
     *  list is just what's surfaced in the Settings picker. (The tags are
     *  ML Kit's TranslateLanguage codes.) */
    val SUPPORTED_LANGUAGES: List<Pair<String, String>> = listOf(
        "es" to "Spanish",
        "ja" to "Japanese",
        "en" to "English",
        "fr" to "French",
        "de" to "German",
        "pt" to "Portuguese",
        "it" to "Italian",
        "ru" to "Russian",
        "ko" to "Korean",
        "zh" to "Chinese",
        "ar" to "Arabic",
        "hi" to "Hindi",
        "nl" to "Dutch",
        "pl" to "Polish",
        "tr" to "Turkish",
        "vi" to "Vietnamese",
        "th" to "Thai",
        "id" to "Indonesian",
        "sv" to "Swedish",
        "uk" to "Ukrainian"
    )

    /** Detects [text]'s language and translates it to [targetLanguageTag] if it
     *  isn't already in that language. */
    suspend fun translate(text: String, targetLanguageTag: String): Outcome {
        if (text.isBlank()) return Outcome.Skipped
        val e = engine ?: return Outcome.Skipped
        return try {
            val detectedTag = e.identifyLanguage(text)
            if (detectedTag == "und") return Outcome.Skipped
            if (detectedTag == targetLanguageTag) return Outcome.Skipped
            val translated = e.translate(text, detectedTag, targetLanguageTag) ?: return Outcome.Skipped
            Outcome.Success(
                sourceLanguageTag = detectedTag,
                sourceLanguageDisplayName = displayNameFor(detectedTag),
                translatedText = translated
            )
        } catch (ex: Exception) {
            Outcome.Failure(ex.message ?: "Translation failed")
        }
    }
}

/** A platform's on-device language detector + translator. */
interface TranslationEngine {
    /** BCP-47 tag of [text]'s language, or "und" when unknown. */
    suspend fun identifyLanguage(text: String): String
    /** [text] translated, or null when the language pair isn't supported. */
    suspend fun translate(text: String, sourceTag: String, targetTag: String): String?
}

internal expect fun defaultTranslationEngine(): TranslationEngine?

/** The English name of a language ("ja" → "Japanese"), "" if unknown. */
internal expect fun englishLanguageName(languageTag: String): String
