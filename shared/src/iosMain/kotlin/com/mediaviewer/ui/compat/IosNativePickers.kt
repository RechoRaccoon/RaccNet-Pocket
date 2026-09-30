package com.mediaviewer.ui.compat

import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.IosUri
import com.mediaviewer.platform.PlatformUri
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * The real iOS pickers behind [rememberLauncherForActivityResult]:
 *  - photos / videos → the system photo picker (PHPicker; no permission
 *    needed), each pick copied into Stellar's cache as a file;
 *  - "open a file" → the Files picker;
 *  - "save a file" (dataset export) → a file in Stellar's own folder in the
 *    Files app (On My iPhone › Stellar);
 *  - permissions (only the Android audio visualizer asks) → denied.
 */
@OptIn(ExperimentalForeignApi::class)
object IosNativePickers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // UIKit only keeps weak references to delegates: hold the live one here.
    private var photoDelegate: NSObject? = null
    private var documentDelegate: NSObject? = null

    fun install() {
        IosPickers.handler = { contract, input, deliver -> launch(contract, input, deliver) }
    }

    private fun launch(contract: ResultContract<*, *>, input: Any?, deliver: (Any?) -> Unit) {
        when (contract) {
            is ActivityResultContracts.PickVisualMedia -> {
                val type = (input as? PickVisualMediaRequest)?.mediaType ?: ActivityResultContracts.PickVisualMedia.ImageAndVideo
                pickMedia(type, 1) { deliver(it.firstOrNull()) }
            }
            is ActivityResultContracts.PickMultipleVisualMedia -> {
                val type = (input as? PickVisualMediaRequest)?.mediaType ?: ActivityResultContracts.PickVisualMedia.ImageAndVideo
                pickMedia(type, contract.maxItems) { deliver(it) }
            }
            is ActivityResultContracts.GetContent -> {
                val mime = input as? String ?: "*/*"
                if (mime.startsWith("image/")) pickMedia(ActivityResultContracts.PickVisualMedia.ImageOnly, 1) { deliver(it.firstOrNull()) }
                else openDocument(arrayOf(mime)) { deliver(it) }
            }
            is ActivityResultContracts.OpenDocument -> {
                @Suppress("UNCHECKED_CAST")
                openDocument(input as? Array<String> ?: arrayOf("*/*")) { deliver(it) }
            }
            is ActivityResultContracts.CreateDocument -> deliver(createDocument(input as? String ?: "Stellar-file"))
            is ActivityResultContracts.RequestPermission -> deliver(false)
            is ActivityResultContracts.OpenDocumentTree -> {
                showPlatformToast("Choosing a folder isn't available on iOS")
                deliver(null)
            }
        }
    }

    // ── Photos / videos ─────────────────────────────────────────────────

    private fun pickMedia(
        type: ActivityResultContracts.PickVisualMedia.VisualMediaType,
        limit: Int,
        onPicked: (List<PlatformUri>) -> Unit
    ) {
        val config = PHPickerConfiguration()
        config.selectionLimit = limit.toLong()
        config.filter = when (type) {
            ActivityResultContracts.PickVisualMedia.ImageOnly -> PHPickerFilter.imagesFilter
            ActivityResultContracts.PickVisualMedia.VideoOnly -> PHPickerFilter.videosFilter
            else -> PHPickerFilter.anyFilterMatchingSubfilters(listOf(PHPickerFilter.imagesFilter, PHPickerFilter.videosFilter))
        }
        val picker = PHPickerViewController(configuration = config)
        val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, completion = null)
                photoDelegate = null
                val results = didFinishPicking.filterIsInstance<PHPickerResult>()
                scope.launch {
                    val uris = results.mapNotNull { copyPickedItem(it) }
                    onPicked(uris)
                }
            }
        }
        photoDelegate = delegate
        picker.delegate = delegate
        present(picker)
    }

    /** Copies one picked photo/video out of the picker's temporary file
     *  (deleted as soon as the callback returns) into Stellar's cache. */
    private suspend fun copyPickedItem(result: PHPickerResult): PlatformUri? = suspendCancellableCoroutine { cont ->
        val provider = result.itemProvider
        val typeId = when {
            provider.hasItemConformingToTypeIdentifier(UTTypeMovie.identifier) -> UTTypeMovie.identifier
            provider.hasItemConformingToTypeIdentifier(UTTypeImage.identifier) -> UTTypeImage.identifier
            else -> null
        }
        if (typeId == null) { cont.resume(null); return@suspendCancellableCoroutine }
        provider.loadFileRepresentationForTypeIdentifier(typeId) { url, _ ->
            val copied = url?.let { copyIntoCache(it, "picked") }
            cont.resume(copied)
        }
    }

    // ── Files ───────────────────────────────────────────────────────────

    private fun openDocument(mimeTypes: Array<String>, onPicked: (PlatformUri?) -> Unit) {
        val types = mimeTypes.mapNotNull { mime ->
            when {
                mime == "*/*" -> UTTypeItem
                mime == "application/json" -> UTTypeJSON
                else -> UTType.typeWithMIMEType(mime)
            }
        }.ifEmpty { listOf(UTTypeItem) }
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        picker.allowsMultipleSelection = false
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                documentDelegate = null
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
                onPicked(url?.let { copyIntoCache(it, "opened") })
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                documentDelegate = null
                onPicked(null)
            }
        }
        documentDelegate = delegate
        picker.delegate = delegate
        present(picker)
    }

    /** "Save as": the file goes into Stellar's own folder in the Files app. */
    private fun createDocument(suggestedName: String): PlatformUri {
        val docs = NSHomeDirectory() + "/Documents"
        NSFileManager.defaultManager.createDirectoryAtPath(docs, withIntermediateDirectories = true, attributes = null, error = null)
        val safe = suggestedName.replace('/', '-').ifBlank { "Stellar-file" }
        var path = "$docs/$safe"
        var n = 2
        while (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            val base = safe.substringBeforeLast('.')
            val ext = safe.substringAfterLast('.', "")
            path = "$docs/$base ($n)" + if (ext.isNotEmpty()) ".$ext" else ""
            n++
        }
        showPlatformToast("Saved to the Files app › On My iPhone › Stellar")
        return IosUri("file://$path")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun copyIntoCache(source: NSURL, prefix: String): PlatformUri? {
        val name = source.lastPathComponent ?: "file"
        val ext = name.substringAfterLast('.', "")
        val dir = IosPaths.cacheDir() + "/picked"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val dest = "$dir/${prefix}_${com.mediaviewer.platform.randomUuidString()}" + if (ext.isNotEmpty()) ".$ext" else ""
        val ok = NSFileManager.defaultManager.copyItemAtURL(source, toURL = NSURL.fileURLWithPath(dest), error = null)
        return if (ok) IosUri("file://$dest") else null
    }

    private fun present(controller: UIViewController) {
        var top = IosScreen.keyWindow()?.rootViewController ?: return
        while (true) top = top.presentedViewController ?: break
        top.presentViewController(controller, animated = true, completion = null)
    }
}
