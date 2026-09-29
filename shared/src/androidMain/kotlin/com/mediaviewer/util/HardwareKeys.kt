package com.mediaviewer.util

import android.view.KeyEvent

/**
 * Lets a screen claim hardware keys (VRM mode uses the volume buttons as a
 * shutter). MainActivity offers every key event here first; a screen sets
 * [handler] while it's showing and clears it when it leaves.
 */
object HardwareKeys {
    @Volatile var handler: ((KeyEvent) -> Boolean)? = null

    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}
