package com.mediaviewer.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.PlatformContext
import okio.Path.Companion.toPath

private val iosDataStore: DataStore<Preferences> by lazy {
    PreferenceDataStoreFactory.createWithPath(
        produceFile = { (IosPaths.filesDir() + "/media_viewer_prefs.preferences_pb").toPath() }
    )
}

actual val PlatformContext.dataStore: DataStore<Preferences> get() = iosDataStore
