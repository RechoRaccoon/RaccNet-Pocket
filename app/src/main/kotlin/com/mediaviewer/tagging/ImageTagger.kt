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

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val tagNames: List<String>
    private val inputSize = 448

    init {
        val options = OrtSession.SessionOptions().apply {
            // Prefer the NPU/DSP via NNAPI where available; silently falls
            // back to pure-CPU XNNPACK-optimized execution on devices/
            // emulators without an NNAPI driver for this graph, ONNX
            // Runtime Mobile handles that fallback internally.
            try { addNnapi() } catch (_: Throwable) { /* not available on this device */ }
            setIntraOpNumThreads(4)
        }
        session = env.createSession(modelFile.absolutePath, options)
        inputName = session.inputNames.iterator().next()
        tagNames = parseTagsCsv(tagsFile)
    }

    /** WD/Z3D-family taggers ship a `selected_tags.csv`/`tags-selected.csv`
     *  with a header row and columns like `tag_id,name,category,count` —
     *  this pulls whichever column is literally named "name" (falling back
     *  to the second column, which is where it lives in every known variant
     *  of this file) so a header reshuffle in a future model update doesn't
     *  silently break tag names. */
    private fun parseTagsCsv(file: File): List<String> {
        val lines = BufferedReader(FileReader(file)).readLines()
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().split(",")
        val nameIdx = header.indexOfFirst { it.trim().equals("name", ignoreCase = true) }
            .let { if (it >= 0) it else 1.coerceAtMost(header.lastIndex) }
        return lines.drop(1).map { line ->
            val cols = line.split(",")
            cols.getOrNull(nameIdx)?.trim()?.trim('"') ?: ""
        }
    }

    /** Preprocesses [bitmap] to a 448x448 NHWC float tensor with RAW [0,255]
     *  pixel values (this Keras/ConvNeXt graph has its own baked-in
     *  Normalization layer — see [tag]'s doc for why), runs one forward pass,
     *  and returns tags above [confidenceThreshold] sorted highest confidence
     *  first. */
    fun tag(bitmap: Bitmap, confidenceThreshold: Float = 0.25f): List<Pair<String, Float>> {
        val letterboxed = letterbox(bitmap, inputSize)
        val floatBuffer = FloatBuffer.allocate(inputSize * inputSize * 3)
        val pixels = IntArray(inputSize * inputSize)
        letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            // NHWC, RGB, RAW [0,255] floats — this Keras/ConvNeXt graph has its
            // own built-in Normalization layer (see Keras ConvNeXt docs: "models
            // expect their inputs to be float or uint8 tensors of pixels with
            // values in the [0-255] range"). Pre-scaling to [0,1] here starves
            // that layer of the input range it was trained on, and the model
            // collapses to its tag-frequency prior — which is exactly the
            // generic/high-frequency-tag garbage this was producing.
            floatBuffer.put(((pixel shr 16) and 0xFF).toFloat())
            floatBuffer.put(((pixel shr 8) and 0xFF).toFloat())
            floatBuffer.put((pixel and 0xFF).toFloat())
        }
        floatBuffer.rewind()
        letterboxed.recycle()

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
                    if (confidence >= confidenceThreshold) {
                        val name = tagNames.getOrNull(i) ?: continue
                        if (name.isNotBlank()) tagged.add(name to confidence)
                    }
                }
                return tagged.sortedByDescending { it.second }
            }
        }
    }

    /** Aspect-ratio-preserving resize of [src] onto a [size]x[size] white
     *  canvas (white, not black/transparent, since this tagger family is
     *  trained on e621 posts composited on white — matching the fill color
     *  a squashed/naive resize implicitly doesn't). Non-square source images
     *  get letterboxed rather than stretched, which otherwise distorts
     *  proportions the model was trained to recognize (e.g. squashing a
     *  portrait image measurably hurts species/body-shape tags). */
    private fun letterbox(src: Bitmap, size: Int): Bitmap {
        val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height)
        val scaledW = (src.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            val left = (size - scaledW) / 2f
            val top = (size - scaledH) / 2f
            drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (scaled !== src) scaled.recycle()
        return out
    }

    override fun close() {
        session.close()
    }
}
