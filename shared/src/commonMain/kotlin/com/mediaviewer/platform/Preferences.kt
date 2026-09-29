package com.mediaviewer.platform

/**
 * android.content.SharedPreferences for shared code. On Android this IS
 * SharedPreferences (same files, same keys — nothing moves); on iOS it's a
 * small file-backed store with the same API.
 */
expect interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun getAll(): Map<String, *>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

/** context.getSharedPreferences(name, Context.MODE_PRIVATE) on Android. */
expect fun PlatformContext.sharedPreferences(name: String): SharedPreferences
