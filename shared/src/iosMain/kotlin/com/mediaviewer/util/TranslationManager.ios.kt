package com.mediaviewer.util

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode

/** No built-in engine on iOS yet (see [TranslationManager.engine]). */
internal actual fun defaultTranslationEngine(): TranslationEngine? = null

internal actual fun englishLanguageName(languageTag: String): String =
    NSLocale(localeIdentifier = "en").displayNameForKey(NSLocaleLanguageCode, languageTag) ?: ""
