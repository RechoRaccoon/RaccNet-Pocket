package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri

/** Settings → Export / Import App Data: settings, VRM & Live settings,
 *  tagged posts and subscriptions in one JSON file (never logins). */
expect object AppBackup {
    /** Writes the backup to [uri]. Returns a short summary. */
    suspend fun export(context: PlatformContext, uri: PlatformUri): String

    /** Restores a backup from [uri]. Returns a short summary; the app then
     *  restarts to load it. Throws with a readable message on failure. */
    suspend fun import(context: PlatformContext, uri: PlatformUri): String
}
