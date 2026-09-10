package com.mediaviewer.platform

import com.russhwolf.settings.ObservableSettings

/**
 * Creates the platform-appropriate [ObservableSettings] backend.
 *
 * androidMain: SharedPreferencesSettings over "media_viewer_prefs" (same
 *              name as the legacy DataStore file).
 * wasmJsMain:  small in-memory implementation (see Settings.web.kt) until
 *              multiplatform-settings publishes a persistent wasmJs target.
 */
expect fun createObservableSettings(): ObservableSettings
