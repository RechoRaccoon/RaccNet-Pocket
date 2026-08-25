package com.mediaviewer.tagging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
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

    /** Preprocesses [bitmap] to a 448x448 NHWC float tensor in [0,1] (the
     *  Keras/TF preprocessing this model family expects), runs one forward
     *  pass, and returns tags above [confidenceThreshold] sorted highest
     *  confidence first. */
    fun tag(bitmap: Bitmap, confidenceThreshold: Float = 0.25f): List<Pair<String, Float>> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val floatBuffer = FloatBuffer.allocate(inputSize * inputSize * 3)
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            // NHWC, RGB, normalized 0..1 — standard for this Keras-derived graph.
            floatBuffer.put(((pixel shr 16) and 0xFF) / 255f)
            floatBuffer.put(((pixel shr 8) and 0xFF) / 255f)
            floatBuffer.put((pixel and 0xFF) / 255f)
        }
        floatBuffer.rewind()
        if (resized !== bitmap) resized.recycle()

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

    override fun close() {
        session.close()
    }
}
