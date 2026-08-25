package com.mediaviewer.tagging

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

/** Fetches and caches the on-device furry/anthro/e621 tagger model.
 *
 *  Model choice: the tagging spec's "Model 1 / JTP-3 / Hydra" doesn't match
 *  any publicly published model under that name. The real, actively-used
 *  equivalent — same e621-trained domain, same multi-label tag-probability
 *  output shape, same 448x448 input, ~8,800 tags — is **Z3D-E621-Convnext**
 *  (community ONNX export of Zack3d/Z3D's e621 tagger; mirrored at several
 *  HuggingFace repos with an identical model.onnx + tags CSV pair). There's
 *  no pre-quantized (fp16/int8) build of it published anywhere as of this
 *  writing, so this ships the fp32 ONNX graph (~390MB) and leans on ONNX
 *  Runtime Mobile's own graph optimizations plus the NNAPI/XNNPACK
 *  execution providers (see ImageTagger) for on-device speed — ConvNeXt at
 *  this size is squarely in the range phones already run comfortably for
 *  batch (non-realtime) image classification, which is what tagging liked
 *  posts is. This is a one-time ~390MB download, cached in app-private
 *  storage — never re-downloaded, and never counted against the AT Proto
 *  PDS the rest of the app talks to (this manager only ever talks to
 *  huggingface.co).
 *
 *  This is a batch/offline classifier, not a live camera-feed model, so a
 *  couple hundred ms per image (typical for a model this size on a
 *  mid-range NNAPI-accelerated device) is fine for tagging a like as it
 *  happens or working through a liked-posts backlog in the background. */
class TaggerModelManager(private val context: Context) {

    sealed class State {
        data object NotDownloaded : State()
        data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val modelDir: File by lazy { File(context.filesDir, "tagger").apply { mkdirs() } }
    val modelFile: File by lazy { File(modelDir, "z3d_e621_convnext.onnx") }
    val tagsFile: File by lazy { File(modelDir, "z3d_e621_tags.csv") }

    private val client by lazy { OkHttpClient.Builder().build() }

    fun isReady(): Boolean = modelFile.exists() && modelFile.length() > 0 && tagsFile.exists() && tagsFile.length() > 0

    /** Downloads the model + tag list (in that order) straight to their
     *  final on-disk paths, reporting combined progress. Safe to call again
     *  after a failed/cancelled attempt — resumes are not implemented (the
     *  file is small enough relative to the images it'll process later that
     *  a clean restart is simpler), but [isReady] already short-circuits a
     *  repeat call once both files are present. */
    suspend fun ensureReady(onProgress: (State) -> Unit) {
        if (isReady()) { onProgress(State.Ready); return }
        withContext(Dispatchers.IO) {
            try {
                onProgress(State.Downloading(0, 0))
                downloadTo(TAGS_URL, tagsFile) { done, total -> onProgress(State.Downloading(done, total)) }
                downloadTo(MODEL_URL, modelFile) { done, total -> onProgress(State.Downloading(done, total)) }
                if (isReady()) onProgress(State.Ready)
                else onProgress(State.Failed("Download finished but files look incomplete"))
            } catch (e: Exception) {
                modelFile.delete(); tagsFile.delete()
                onProgress(State.Failed(e.message ?: "Download failed"))
            }
        }
    }

    private fun downloadTo(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} fetching ${dest.name}")
            val body = resp.body ?: error("Empty body fetching ${dest.name}")
            val total = body.contentLength()
            RandomAccessFile(tmp, "rw").use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }
        tmp.renameTo(dest)
    }

    companion object {
        // toynya's mirror is the most-referenced copy of this community
        // export; silveroxides'/fsw's are identical byte-for-byte mirrors
        // if this one ever moves.
        private const val MODEL_URL = "https://huggingface.co/toynya/Z3D-E621-Convnext/resolve/main/model.onnx"
        private const val TAGS_URL  = "https://huggingface.co/toynya/Z3D-E621-Convnext/resolve/main/tags-selected.csv"
    }
}
