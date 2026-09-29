package com.mediaviewer.platform

import platform.Foundation.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.mkdir

/** Where Stellar keeps its files on iOS (inside the app's sandbox). */
@OptIn(ExperimentalForeignApi::class)
object IosPaths {
    private fun ensure(dir: String): String { mkdir(dir, 0x1ED.convert()); return dir }

    /** Library/Application Support/Stellar — app data (like Android's filesDir). */
    fun filesDir(): String {
        val lib = ensure(NSHomeDirectory() + "/Library")
        val support = ensure("$lib/Application Support")
        return ensure("$support/Stellar")
    }

    /** Library/Caches/Stellar — like Android's cacheDir. */
    fun cacheDir(): String {
        val lib = ensure(NSHomeDirectory() + "/Library")
        val caches = ensure("$lib/Caches")
        return ensure("$caches/Stellar")
    }

    /** SharedPreferences files. */
    fun preferencesDir(): String = ensure(filesDir() + "/prefs")
}
