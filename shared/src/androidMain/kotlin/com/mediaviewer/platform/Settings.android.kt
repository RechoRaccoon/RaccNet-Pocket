package com.mediaviewer.platform

import android.content.Context
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Android Settings backend — the same "media_viewer_prefs" store the legacy
 * DataStore build used, so existing installs keep their values.
 *
 * NOTE: [AndroidAppContext] is provided by the platform worker in androidMain
 * platform/; it did not exist yet when this file was written. If CI reports
 * an unresolved reference here, the platform worker hasn't landed it yet.
 */
actual fun createObservableSettings(): ObservableSettings {
    val prefs = AndroidAppContext.app.getSharedPreferences("media_viewer_prefs", Context.MODE_PRIVATE)
    return SharedPreferencesSettings(prefs)
}
