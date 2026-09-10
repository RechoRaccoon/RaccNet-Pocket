package com.mediaviewer.platform

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation, ported from legacy util/TranslationManager.kt onto
 * the [PlatformTranslator] contract — ML Kit's on-device Translate + Language
 * Identification APIs (`com.google.mlkit:translate`, `com.google.mlkit:language-id`).
 * Both run entirely on-device: text never leaves the phone, and each language
 * pair's ~30MB model is fetched once (dynamically, on first use) and then
 * cached by ML Kit itself for fully-offline reuse afterward — see the "Explicitly
 * manage translation models" section of ML Kit's docs if per-language storage
 * management (pre-downloading, deleting unused packs) is ever needed; this
 * relies on ML Kit's own default dynamic management rather than adding
 * a manual pack manager, since the whole point of "on-device" here is that it
 * should just work without the user having to think about it.
 */
actual class PlatformTranslator actual constructor() {

    actual val isAvailable: Boolean = true

    // One Translator per (source, target) language pair, reused for the life of
    // the process — avoids re-downloading/re-initializing a model on every call
    // for the same pair. Never explicitly closed since the app has no clean
    // single point to do so; ML Kit translators are lightweight once their
    // model is already resident, so this is a deliberate, small, bounded leak
    // rather than a real one (at most a handful of language pairs get used in
    // a single session).
    private val translators = ConcurrentHashMap<String, Translator>()
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    /** Human-readable display name for a BCP-47 language tag, e.g. "ja" -> "Japanese". */
    actual fun displayNameFor(languageTag: String): String {
        val locale = Locale(languageTag)
        val name = locale.getDisplayLanguage(Locale.ENGLISH)
        return if (name.isBlank()) languageTag else name.replaceFirstChar { it.uppercase() }
    }

    /** Curated list of common translation targets — Spanish and Japanese are
     *  pinned first per the original spec's stated priority languages, but any
     *  BCP-47 tag ML Kit supports will work if added here later; this list is
     *  just what's surfaced in the Settings picker, not a hard restriction.
     *
     *  Tags are hardcoded BCP-47 strings (the values of ML Kit's
     *  TranslateLanguage.* constants) rather than referencing the constants,
     *  so the list stays a plain-data contract. */
    actual val supportedLanguages: List<Pair<String, String>> = listOf(
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

    private suspend fun identifyLanguage(text: String): String =
        suspendCancellableCoroutine { cont ->
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { tag -> if (cont.isActive) cont.resume(tag) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }

    private suspend fun translatorFor(sourceTag: String, targetTag: String): Translator {
        val key = "$sourceTag>$targetTag"
        translators[key]?.let { return it }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceTag)
            .setTargetLanguage(targetTag)
            .build()
        val translator = Translation.getClient(options)
        // No requireWifi() here — unlike the codelab default, this app's posts
        // are usually already being fetched over whatever connection the user
        // has, and gating a ~30MB one-time model download behind Wi-Fi-only
        // would just make the feature silently do nothing on mobile data the
        // first time it's used. Once downloaded, later translations for the
        // same language pair need no network at all.
        suspendCancellableCoroutine<Unit> { cont ->
            translator.downloadModelIfNeeded()
                .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }
        translators[key] = translator
        return translator
    }

    private suspend fun runTranslate(translator: Translator, text: String): String =
        suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }

    /** Detects [text]'s language and translates it to [targetLang] if it
     *  isn't already in that language. Safe to call repeatedly — models are
     *  cached both by ML Kit (on disk) and by this object (open [Translator]s).
     *  Never throws — failures come back as [TranslationOutcome.Failure]. */
    actual suspend fun translate(text: String, targetLang: String): TranslationOutcome {
        if (text.isBlank()) return TranslationOutcome.Skipped
        return try {
            val detectedTag = identifyLanguage(text)
            if (detectedTag == "und") return TranslationOutcome.Skipped
            if (detectedTag == targetLang) return TranslationOutcome.Skipped
            val sourceTranslateTag = TranslateLanguage.fromLanguageTag(detectedTag)
            val targetTranslateTag = TranslateLanguage.fromLanguageTag(targetLang)
            if (sourceTranslateTag == null || targetTranslateTag == null) return TranslationOutcome.Skipped
            val translator = translatorFor(sourceTranslateTag, targetTranslateTag)
            val translated = runTranslate(translator, text)
            TranslationOutcome.Success(
                sourceLanguageTag = detectedTag,
                sourceLanguageDisplayName = displayNameFor(detectedTag),
                translatedText = translated
            )
        } catch (e: Exception) {
            TranslationOutcome.Failure(e.message ?: "Translation failed")
        }
    }
}
