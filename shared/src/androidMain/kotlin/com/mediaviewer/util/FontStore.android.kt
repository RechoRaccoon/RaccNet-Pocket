package com.mediaviewer.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.mediaviewer.R
import java.io.File

@Composable
actual fun audiowideFontFamily(): FontFamily = remember { FontFamily(Font(R.font.audiowide)) }

actual object FontFiles {
    actual fun exists(path: String): Boolean = File(path).exists()
    actual fun delete(path: String) { File(path).delete() }
    actual fun family(path: String): FontFamily = FontFamily(Font(File(path)))

    actual fun copyIn(context: Context, uri: Uri): FontCopied {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "Imported Font"
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("ttf", "otf", "ttc")) return FontCopied.Error("Please choose a .ttf or .otf font file")
        val dir = File(context.filesDir, "fonts").apply { mkdirs() }
        val dest = File(dir, "imported_${System.currentTimeMillis()}.$ext")
        val ok = context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }; true
        } ?: false
        if (!ok) return FontCopied.Error("Couldn't read that font file")
        // Make sure Android can actually parse it before it becomes the
        // app font — a broken file would otherwise crash the first layout.
        val valid = runCatching { android.graphics.Typeface.Builder(dest).build() != null }.getOrDefault(false)
        if (!valid) { dest.delete(); return FontCopied.Error("That file isn't a font Android can read") }
        return FontCopied.Ok(dest.absolutePath, displayName)
    }
}
