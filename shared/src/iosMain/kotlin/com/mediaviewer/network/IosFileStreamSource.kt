package com.mediaviewer.network

import com.mediaviewer.platform.readLocalFile

/** A local file uploaded as a request body (iOS: videos picked from the
 *  library are copied to a temp file first). */
class IosFileStreamSource(val path: String, override val contentLength: Long) : StreamSource {
    fun readAll(): ByteArray = readLocalFile(path) ?: throw com.mediaviewer.platform.IOException("Couldn't read $path")
}
