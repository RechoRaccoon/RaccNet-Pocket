package com.mediaviewer.platform

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * App-wide one-off events for platforms whose UI shows them itself (iOS has
 * no system Toast and no process restart): the iOS app shell listens and
 * shows a snackbar / rebuilds the app state. Android doesn't use these.
 */
object AppEvents {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages
    fun postMessage(message: String) { _messages.tryEmit(message) }

    private val _restart = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Account switched: rebuild the ViewModel/UI from the saved state. */
    val restart: SharedFlow<Unit> = _restart
    fun requestRestart() { _restart.tryEmit(Unit) }
}
