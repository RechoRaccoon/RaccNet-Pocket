package com.mediaviewer.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite

/** Reads a whole local file (posix), or null if it can't be opened. */
@OptIn(ExperimentalForeignApi::class)
fun readLocalFile(path: String): ByteArray? {
    val f = fopen(path, "rb") ?: return null
    try {
        fseek(f, 0, SEEK_END)
        val size = ftell(f).toInt()
        fseek(f, 0, SEEK_SET)
        if (size <= 0) return ByteArray(0)
        val out = ByteArray(size)
        var read = 0
        out.usePinned { pinned ->
            while (read < size) {
                val n = fread(pinned.addressOf(read), 1u, (size - read).toULong(), f).toInt()
                if (n <= 0) break
                read += n
            }
        }
        return if (read == size) out else out.copyOf(read)
    } finally {
        fclose(f)
    }
}

/** Writes [bytes] to a local file (posix). Returns false on failure. */
@OptIn(ExperimentalForeignApi::class)
fun writeLocalFile(path: String, bytes: ByteArray): Boolean {
    val f = fopen(path, "wb") ?: return false
    try {
        if (bytes.isEmpty()) return true
        var written = 0
        bytes.usePinned { pinned ->
            while (written < bytes.size) {
                val n = fwrite(pinned.addressOf(written), 1u, (bytes.size - written).toULong(), f).toInt()
                if (n <= 0) break
                written += n
            }
        }
        return written == bytes.size
    } finally {
        fclose(f)
    }
}

/** True if [path] exists (posix access). */
@OptIn(ExperimentalForeignApi::class)
fun localFileExists(path: String): Boolean = platform.posix.access(path, platform.posix.F_OK) == 0

/** Deletes [path] if it exists. */
@OptIn(ExperimentalForeignApi::class)
fun deleteLocalFile(path: String) { platform.posix.remove(path) }
