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

internal actual fun defaultTranslationEngine(): TranslationEngine? = MlKitTranslationEngine

internal actual fun englishLanguageName(languageTag: String): String =
    Locale(languageTag).getDisplayLanguage(Locale.ENGLISH)

/**
 * ML Kit's on-device Translate + Language Identification. Both run entirely
 * on-device: each language pair's ~30MB model is fetched once (on first use)
 * and then cached by ML Kit itself for offline reuse.
 */
internal object MlKitTranslationEngine : TranslationEngine {
    // One Translator per (source, target) pair, reused for the life of the
    // process — a deliberate, small, bounded "leak" (a handful of pairs).
    private val translators = ConcurrentHashMap<String, Translator>()
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    override suspend fun identifyLanguage(text: String): String =
        suspendCancellableCoroutine { cont ->
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { tag -> if (cont.isActive) cont.resume(tag) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }

    override suspend fun translate(text: String, sourceTag: String, targetTag: String): String? {
        val source = TranslateLanguage.fromLanguageTag(sourceTag) ?: return null
        val target = TranslateLanguage.fromLanguageTag(targetTag) ?: return null
        val translator = translatorFor(source, target)
        return suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }
    }

    private suspend fun translatorFor(sourceTag: String, targetTag: String): Translator {
        val key = "$sourceTag>$targetTag"
        translators[key]?.let { return it }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceTag)
            .setTargetLanguage(targetTag)
            .build()
        val translator = Translation.getClient(options)
        // No requireWifi(): gating the one-time model download behind Wi-Fi
        // would make the feature silently do nothing on mobile data.
        suspendCancellableCoroutine<Unit> { cont ->
            translator.downloadModelIfNeeded()
                .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }
        translators[key] = translator
        return translator
    }
}
