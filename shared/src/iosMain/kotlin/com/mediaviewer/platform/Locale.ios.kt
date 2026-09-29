package com.mediaviewer.platform

import platform.Foundation.*

actual fun defaultLanguageCode(): String =
    (NSLocale.currentLocale.objectForKey(NSLocaleLanguageCode) as? String) ?: "en"
