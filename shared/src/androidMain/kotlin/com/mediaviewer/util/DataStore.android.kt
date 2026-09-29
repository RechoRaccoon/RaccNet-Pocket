package com.mediaviewer.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.mediaviewer.platform.PlatformContext

actual val PlatformContext.dataStore: DataStore<Preferences> by preferencesDataStore(name = "media_viewer_prefs")
