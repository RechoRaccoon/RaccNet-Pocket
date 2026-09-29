package com.mediaviewer.util

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
 * Phase 4 — on-device translation, via ML Kit's on-device Translate + Language
 * Identification APIs (`com.google.mlkit:translate`, `com.google.mlkit:language-id`).
 * Both run entirely on-device: text never leaves the phone, and each language
 * pair's ~30MB model is fetched once (dynamically, on first use) and then
 * cached by ML Kit itself for fully-offline reuse afterward — see the "Explicitly
 * manage translation models" section of ML Kit's docs if per-language storage
 * management (pre-downloading, deleting unused packs) is ever needed; this
 * phase relies on ML Kit's own default dynamic management rather than adding
 * a manual pack manager, since the whole point of "on-device" here is that it
 * should just work without the user having to think about it.
 */
object TranslationManager {

    // One Translator per (source, target) language pair, reused for the life of
    // the process — avoids re-downloading/re-initializing a model on every call
    // for the same pair. Never explicitly closed since the app has no clean
    // single point to do so; ML Kit translators are lightweight once their
    // model is already resident, so this is a deliberate, small, bounded leak
    // rather than a real one (at most a handful of language pairs get used in
    // a single session).
    private val translators = ConcurrentHashMap<String, Translator>()
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

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

    /** Human-readable display name for a BCP-47 language tag, e.g. "ja" -> "Japanese". */
    fun displayNameFor(languageTag: String): String {
        val locale = Locale(languageTag)
        val name = locale.getDisplayLanguage(Locale.ENGLISH)
        return if (name.isBlank()) languageTag else name.replaceFirstChar { it.uppercase() }
    }

    /** Curated list of common translation targets — Spanish and Japanese are
     *  pinned first per the Phase 4 spec's stated priority languages, but any
     *  BCP-47 tag ML Kit supports will work if added here later; this list is
     *  just what's surfaced in the Settings picker, not a hard restriction. */
    val SUPPORTED_LANGUAGES: List<Pair<String, String>> = listOf(
        TranslateLanguage.SPANISH to "Spanish",
        TranslateLanguage.JAPANESE to "Japanese",
        TranslateLanguage.ENGLISH to "English",
        TranslateLanguage.FRENCH to "French",
        TranslateLanguage.GERMAN to "German",
        TranslateLanguage.PORTUGUESE to "Portuguese",
        TranslateLanguage.ITALIAN to "Italian",
        TranslateLanguage.RUSSIAN to "Russian",
        TranslateLanguage.KOREAN to "Korean",
        TranslateLanguage.CHINESE to "Chinese",
        TranslateLanguage.ARABIC to "Arabic",
        TranslateLanguage.HINDI to "Hindi",
        TranslateLanguage.DUTCH to "Dutch",
        TranslateLanguage.POLISH to "Polish",
        TranslateLanguage.TURKISH to "Turkish",
        TranslateLanguage.VIETNAMESE to "Vietnamese",
        TranslateLanguage.THAI to "Thai",
        TranslateLanguage.INDONESIAN to "Indonesian",
        TranslateLanguage.SWEDISH to "Swedish",
        TranslateLanguage.UKRAINIAN to "Ukrainian"
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

    /** Detects [text]'s language and translates it to [targetLanguageTag] if it
     *  isn't already in that language. Safe to call repeatedly — models are
     *  cached both by ML Kit (on disk) and by this object (open [Translator]s). */
    suspend fun translate(text: String, targetLanguageTag: String): Outcome {
        if (text.isBlank()) return Outcome.Skipped
        return try {
            val detectedTag = identifyLanguage(text)
            if (detectedTag == "und") return Outcome.Skipped
            if (detectedTag == targetLanguageTag) return Outcome.Skipped
            val sourceTranslateTag = TranslateLanguage.fromLanguageTag(detectedTag)
            val targetTranslateTag = TranslateLanguage.fromLanguageTag(targetLanguageTag)
            if (sourceTranslateTag == null || targetTranslateTag == null) return Outcome.Skipped
            val translator = translatorFor(sourceTranslateTag, targetTranslateTag)
            val translated = runTranslate(translator, text)
            Outcome.Success(
                sourceLanguageTag = detectedTag,
                sourceLanguageDisplayName = displayNameFor(detectedTag),
                translatedText = translated
            )
        } catch (e: Exception) {
            Outcome.Failure(e.message ?: "Translation failed")
        }
    }
}
