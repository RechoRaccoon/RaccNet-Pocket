package com.mediaviewer.ui.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.activity.result.contract.ActivityResultContracts as AX
import androidx.activity.result.PickVisualMediaRequest as AXRequest

private fun ActivityResultContracts.PickVisualMedia.VisualMediaType.toAndroid(): AX.PickVisualMedia.VisualMediaType = when (this) {
    ActivityResultContracts.PickVisualMedia.ImageOnly -> AX.PickVisualMedia.ImageOnly
    ActivityResultContracts.PickVisualMedia.VideoOnly -> AX.PickVisualMedia.VideoOnly
    ActivityResultContracts.PickVisualMedia.ImageAndVideo -> AX.PickVisualMedia.ImageAndVideo
}

private fun PickVisualMediaRequest.toAndroid(): AXRequest = AXRequest(mediaType.toAndroid())

private class Launcher<I, AI>(
    private val real: androidx.activity.result.ActivityResultLauncher<AI>,
    private val map: (I) -> AI,
) : ActivityResultLauncher<I> {
    override fun launch(input: I) = real.launch(map(input))
}

@Suppress("UNCHECKED_CAST")
@Composable
actual fun <I, O> rememberLauncherForActivityResult(
    contract: ResultContract<I, O>,
    onResult: (O) -> Unit,
): ActivityResultLauncher<I> = when (contract) {
    is ActivityResultContracts.PickVisualMedia -> {
        val real = androidx.activity.compose.rememberLauncherForActivityResult(AX.PickVisualMedia()) { onResult(it as O) }
        remember(real) { Launcher<PickVisualMediaRequest, AXRequest>(real) { it.toAndroid() } } as ActivityResultLauncher<I>
    }
    is ActivityResultContracts.PickMultipleVisualMedia -> {
        val real = androidx.activity.compose.rememberLauncherForActivityResult(AX.PickMultipleVisualMedia(contract.maxItems)) { onResult(it as O) }
        remember(real) { Launcher<PickVisualMediaRequest, AXRequest>(real) { it.toAndroid() } } as ActivityResultLauncher<I>
    }
    is ActivityResultContracts.GetContent -> {
        val real = androidx.activity.compose.rememberLauncherForActivityResult(AX.GetContent()) { onResult(it as O) }
        remember(real) { Launcher<String, String>(real) { it } } as ActivityResultLauncher<I>
    }
    is ActivityResultContracts.OpenDocument -> {
        val real = androidx.activity.compose.rememberLauncherForActivityResult(AX.OpenDocument()) { onResult(it as O) }
        remember(real) { Launcher<Array<String>, Array<String>>(real) { it } } as ActivityResultLauncher<I>
    }
    is ActivityResultContracts.CreateDocument -> {
        val real = androidx.activity.compose.rememberLauncherForActivityResult(AX.CreateDocument(contract.mimeType)) { onResult(it as O) }
        remember(real) { Launcher<String, String>(real) { it } } as ActivityResultLauncher<I>
    }
    is ActivityResultContracts.RequestPermission -> {
        val real = androidx.activity.compose.rememberLauncherForActivityResult(AX.RequestPermission()) { onResult(it as O) }
        remember(real) { Launcher<String, String>(real) { it } } as ActivityResultLauncher<I>
    }
    is ActivityResultContracts.OpenDocumentTree -> {
        val real = androidx.activity.compose.rememberLauncherForActivityResult(AX.OpenDocumentTree()) { onResult(it as O) }
        remember(real) { Launcher<com.mediaviewer.platform.PlatformUri?, android.net.Uri?>(real) { it } } as ActivityResultLauncher<I>
    }
}
