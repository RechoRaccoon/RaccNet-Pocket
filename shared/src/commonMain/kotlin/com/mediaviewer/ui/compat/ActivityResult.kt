package com.mediaviewer.ui.compat

import androidx.compose.runtime.Composable
import com.mediaviewer.platform.PlatformUri

/*
 * The androidx.activity result API, as Stellar uses it (media pickers,
 * document pickers, one permission). Same names, so screens only change
 * their imports. Android forwards to the real contracts.
 */

interface ActivityResultLauncher<I> {
    fun launch(input: I)
}

/** A contract: what a launcher takes ([I]) and hands back ([O]). */
sealed class ResultContract<I, O>

class PickVisualMediaRequest(val mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo)

object ActivityResultContracts {
    class PickVisualMedia : ResultContract<PickVisualMediaRequest, PlatformUri?>() {
        sealed interface VisualMediaType
        object ImageOnly : VisualMediaType
        object VideoOnly : VisualMediaType
        object ImageAndVideo : VisualMediaType
    }

    class PickMultipleVisualMedia(val maxItems: Int = 10) : ResultContract<PickVisualMediaRequest, List<PlatformUri>>()

    /** Input: a MIME type filter, e.g. any image type. */
    class GetContent : ResultContract<String, PlatformUri?>()

    /** Input: accepted MIME types. */
    class OpenDocument : ResultContract<Array<String>, PlatformUri?>()

    /** Input: suggested file name. */
    class CreateDocument(val mimeType: String) : ResultContract<String, PlatformUri?>()

    /** Input: permission name (android.Manifest.permission.*). */
    class RequestPermission : ResultContract<String, Boolean>()

    /** Input: initial folder (may be null). */
    class OpenDocumentTree : ResultContract<PlatformUri?, PlatformUri?>()
}

@Composable
expect fun <I, O> rememberLauncherForActivityResult(
    contract: ResultContract<I, O>,
    onResult: (O) -> Unit,
): ActivityResultLauncher<I>
