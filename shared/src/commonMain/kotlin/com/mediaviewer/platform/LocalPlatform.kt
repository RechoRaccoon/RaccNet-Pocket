package com.mediaviewer.platform

/**
 * Small platform hooks behind the supporter features: keeping picked media
 * in the app's own storage (drafts, notes, folder covers), the timer's
 * alarm sound, Battery Saver's frame-rate cap, and Android's background
 * notification check.
 */
expect object LocalPlatform {
    /** Copies the picked file at [uri] into the app's private storage (so a
     *  draft or note keeps it even if the original is moved or deleted) and
     *  returns where the copy lives, or null if it couldn't be read. */
    fun importMedia(context: PlatformContext, uri: PlatformUri, folder: String): PlatformUri?

    /** A [PlatformUri] for a saved uri string (file://, content://, https://). */
    fun parseUri(text: String): PlatformUri

    /** Deletes a file previously returned by [importMedia]. */
    fun deleteMedia(context: PlatformContext, uri: String)

    /** Battery Saver: caps the screen's refresh rate while on. */
    fun setBatterySaver(context: PlatformContext, on: Boolean)

    /** Plays [wav] (a complete WAV file) on a loop until [stopSound]. */
    fun playLoopingWav(context: PlatformContext, wav: ByteArray)
    fun stopSound()

    /** Starts or stops the background notification check to match the
     *  Supporter Settings toggles (Android only; a no-op elsewhere). Asks
     *  for the notification permission when it's being switched on. */
    fun syncNotifications(context: PlatformContext, requestPermission: Boolean)
}
