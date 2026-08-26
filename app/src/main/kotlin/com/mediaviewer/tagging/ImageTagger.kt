package com.mediaviewer.tagging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.nio.FloatBuffer

/** Runs the Z3D-E621-Convnext tagger (see TaggerModelManager's doc comment
 *  for why this model, not the spec's "JTP-3") on a single decoded bitmap
 *  and returns every tag whose confidence clears the spec's 0.25 threshold.
 *
 *  Everything here is 100% local inference — no network call, no telemetry,
 *  no content-moderation filtering layer of any kind, matching the spec's
 *  "Uncensored Local Inference" requirement. */
class ImageTagger(modelFile: File, tagsFile: File) : AutoCloseable {

    private data class TagEntry(val name: String, val category: Int)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val tagEntries: List<TagEntry>
    private val inputSize = 448

    init {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Prefer the NPU/DSP via NNAPI where available. Important caveat
            // on this specific graph: ConvNeXt's LayerNormalization and GELU
            // ops aren't in most Android NNAPI drivers' supported-op list
            // (Tensor G3 in the 8a included), so NNAPI often can't claim
            // this graph at all and ORT falls back to its *generic* CPU EP —
            // not XNNPACK — which is noticeably slower for fp32 conv nets on
            // ARM. Registering XNNPACK explicitly gives NNAPI first refusal
            // at whatever it *can* accelerate, with a fast CPU path (instead
            // of the slow generic one) for everything it can't, rather than
            // silently eating that cost.
            try { addNnapi() } catch (_: Throwable) { /* not available on this device */ }
            try { addXnnpack(mapOf("intra_op_num_threads" to threads.toString())) } catch (_: Throwable) { /* AAR build doesn't include it */ }
            setIntraOpNumThreads(threads)
        }
        session = env.createSession(modelFile.absolutePath, options)
        inputName = session.inputNames.iterator().next()
        tagEntries = parseTagsCsv(tagsFile)

        // NNAPI/XNNPACK compile+cache the graph on their first Run() call —
        // anywhere from ~100ms to a couple seconds depending on device/
        // driver. Paid here, once, right after the session is built (this
        // constructor already only ever runs on a background thread — see
        // TaggingRepository.ensureTagger), it's invisible. Left alone, it's
        // paid on the very first liked post tagged instead, which is what
        // was actually making that first image (and the overlay's early
        // "scanned" rate) look much slower than the real steady-state speed.
        try {
            val warmBuffer = FloatBuffer.allocate(inputSize * inputSize * 3)
            val warmShape = longArrayOf(1, inputSize.toLong(), inputSize.toLong(), 3)
            OnnxTensor.createTensor(env, warmBuffer, warmShape).use { t ->
                session.run(mapOf(inputName to t)).close()
            }
        } catch (_: Throwable) { /* best-effort — a failed warm-up just means the first real image pays the cost instead */ }
    }

    /** WD/Z3D-family taggers ship a `selected_tags.csv`/`tags-selected.csv`
     *  with a header row and columns like `tag_id,name,category,count` —
     *  this pulls whichever columns are literally named "name"/"category"
     *  (falling back to positions 1/2, where they live in every known
     *  variant of this file) so a header reshuffle in a future model update
     *  doesn't silently break parsing. Category matters here (see [tag]'s
     *  doc comment on why character/species get their own threshold), e621
     *  category ids: 0 general, 1 artist, 3 copyright, 4 character,
     *  5 species, 7 meta, 8 lore. */
    private fun parseTagsCsv(file: File): List<TagEntry> {
        val lines = BufferedReader(FileReader(file)).readLines()
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().split(",")
        val nameIdx = header.indexOfFirst { it.trim().equals("name", ignoreCase = true) }
            .let { if (it >= 0) it else 1.coerceAtMost(header.lastIndex) }
        val categoryIdx = header.indexOfFirst { it.trim().equals("category", ignoreCase = true) }
            .let { if (it >= 0) it else 2.coerceAtMost(header.lastIndex) }
        return lines.drop(1).map { line ->
            val cols = line.split(",")
            val name = cols.getOrNull(nameIdx)?.trim()?.trim('"') ?: ""
            val category = cols.getOrNull(categoryIdx)?.trim()?.toIntOrNull() ?: -1
            TagEntry(name, category)
        }
    }

    // Reused across tag() calls instead of freshly allocated (and then
    // garbage-collected) every single image — a batch run tags hundreds of
    // posts back to back, and repeatedly allocating a 448x448 Bitmap +
    // IntArray + FloatBuffer (~2.5MB combined) per image is enough sustained
    // garbage to cause real GC-pause stutter on a phone's heap. ThreadLocal
    // rather than plain instance fields because TaggingRepository's
    // "posts tagged at once" concurrency setting can call tag() on this same
    // ImageTagger from several coroutines at once — shared mutable buffers
    // would let concurrent calls corrupt each other's pixel data.
    private val letterboxTargetTL = ThreadLocal.withInitial { Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888) }
    private val pixelsTL = ThreadLocal.withInitial { IntArray(inputSize * inputSize) }
    private val floatBufferTL = ThreadLocal.withInitial { FloatBuffer.allocate(inputSize * inputSize * 3) }

    /** Preprocesses [bitmap] to a 448x448 NHWC float tensor with RAW [0,255]
     *  pixel values in BGR channel order (this model was trained via a
     *  cv2-based Keras pipeline that loads images BGR by default, and that
     *  channel order is baked into the graph's learned weights — the
     *  reference `app.py` for this model explicitly does
     *  `image_array[:, :, ::-1]` to flip PIL's native RGB to BGR before
     *  inference), runs one forward pass, and returns every tag that clears
     *  its threshold, sorted highest confidence first.
     *
     *  Two separate thresholds, not one: character/species tags (e621
     *  categories 4/5) are picking one answer out of ~8,800 mostly-similar
     *  candidate classes, so even a *correct* prediction structurally lands
     *  at a much lower raw score than a general tag like "solo" or "anthro"
     *  ever needs to — there's just more probability mass split across more
     *  plausible-looking options. A single flat cutoff tuned for general
     *  tags (this model's reference demo uses 0.35) starves character/
     *  species recall regardless of preprocessing correctness. Giving them
     *  their own, much more lenient threshold is the standard fix for this
     *  exact "character tags don't show up" complaint in this tagger
     *  family — not something to "solve" by lowering the general threshold
     *  across the board (which would just add noise everywhere else) or
     *  raising it (which only removes tags). generalThreshold stays at this
     *  app's original 0.25 default (not the reference demo's 0.35 — that
     *  was a red herring, not the fix). */
    fun tag(
        bitmap: Bitmap,
        generalThreshold: Float = 0.25f,
        characterThreshold: Float = 0.15f
    ): List<Pair<String, Float>> {
        val letterboxed = letterbox(bitmap, inputSize, letterboxTargetTL.get())
        val floatBuffer = floatBufferTL.get().also { it.clear() }
        val pixels = pixelsTL.get()
        letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            // NHWC, BGR (see doc comment above), RAW [0,255] floats.
            floatBuffer.put((pixel and 0xFF).toFloat())            // B
            floatBuffer.put(((pixel shr 8) and 0xFF).toFloat())    // G
            floatBuffer.put(((pixel shr 16) and 0xFF).toFloat())   // R
        }
        floatBuffer.rewind()

        val shape = longArrayOf(1, inputSize.toLong(), inputSize.toLong(), 3)
        OnnxTensor.createTensor(env, floatBuffer, shape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { results ->
                val output = results[0].value
                val scores: FloatArray = when (output) {
                    is Array<*> -> (output[0] as FloatArray)
                    is FloatArray -> output
                    else -> return emptyList()
                }
                val tagged = mutableListOf<Pair<String, Float>>()
                for (i in scores.indices) {
                    val confidence = scores[i]
                    val entry = tagEntries.getOrNull(i) ?: continue
                    val threshold = if (entry.category == 4 || entry.category == 5) characterThreshold else generalThreshold
                    if (confidence >= threshold && entry.name.isNotBlank()) {
                        tagged.add(entry.name to confidence)
                    }
                }
                return tagged.sortedByDescending { it.second }
            }
        }
    }

    /** Aspect-ratio-preserving resize of [src] onto [target] (a [size]x[size]
     *  canvas, reused across calls — see the ThreadLocal buffers above),
     *  filled white (white, not black/transparent, since this tagger family
     *  is trained on e621 posts composited on white — matching the fill
     *  color a squashed/naive resize implicitly doesn't). Non-square source
     *  images get letterboxed rather than stretched, which otherwise
     *  distorts proportions the model was trained to recognize (e.g.
     *  squashing a portrait image measurably hurts species/body-shape tags).
     *  Safe to reuse [target] between calls because drawColor(WHITE) below
     *  always repaints every pixel before anything else is drawn — nothing
     *  from a previous call can show through. */
    private fun letterbox(src: Bitmap, size: Int, target: Bitmap): Bitmap {
        val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height)
        val scaledW = (src.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        Canvas(target).apply {
            drawColor(Color.WHITE)
            val left = (size - scaledW) / 2f
            val top = (size - scaledH) / 2f
            drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (scaled !== src) scaled.recycle()
        return target
    }

    override fun close() {
        session.close()
    }
}
