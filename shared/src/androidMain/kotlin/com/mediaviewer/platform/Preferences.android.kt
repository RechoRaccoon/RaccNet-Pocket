package com.mediaviewer.platform

actual typealias SharedPreferences = android.content.SharedPreferences

actual fun PlatformContext.sharedPreferences(name: String): SharedPreferences =
    getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
