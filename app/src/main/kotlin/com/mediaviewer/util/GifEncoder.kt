package com.mediaviewer.util

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * Minimal, self-contained animated GIF encoder (GIF89a) with NeuQuant color
 * quantization. Adapted from the classic public-domain implementations by
 * Anthony Dekker (NeuQuant) and Kevin Weiner (AnimatedGifEncoder).
 *
 * "Full quality" here means: every frame is quantized independently at its
 * native resolution with a full 256-color adaptive palette and NO additional
 * frame skipping, downscaling, or lossy re-encoding beyond what the GIF
 * format itself requires (GIF is inherently limited to a 256-color palette
 * per frame — that is a hard format limitation, not an extra compression
 * step this encoder applies).
 */
class GifEncoder(private val out: OutputStream) {

    private var width = 0
    private var height = 0
    private var started = false
    private var frameDelayCs = 10 // centiseconds (1/100s)

    fun setDelay(ms: Int) { frameDelayCs = (ms / 10).coerceAtLeast(2) }

    fun start() {
        started = true
    }

    fun addFrame(bitmap: Bitmap, highQuality: Boolean = false) {
        if (!started) return
        if (width == 0) {
            width = bitmap.width
            height = bitmap.height
            writeHeader()
            writeLogicalScreenDescriptor()
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val quant = NeuQuant(pixels, highQuality)
        val colorTab = quant.process()
        val indices = ByteArray(pixels.size)
        for (i in pixels.indices) indices[i] = quant.map(pixels[i]).toByte()

        writeGraphicControlExtension()
        writeImageDescriptor()
        writeColorTable(colorTab)
        writeLzwPixels(indices)
    }

    fun finish() {
        out.write(0x3B) // trailer
        out.flush()
    }

    private fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
    }

    private fun writeLogicalScreenDescriptor() {
        writeShort(width); writeShort(height)
        out.write(0x00) // no global color table
        out.write(0x00) // background color index
        out.write(0x00) // pixel aspect ratio
    }

    private fun writeGraphicControlExtension() {
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0x04) // no transparency, no disposal specified
        writeShort(frameDelayCs)
        out.write(0x00) // transparent color index
        out.write(0x00) // block terminator
    }

    private fun writeImageDescriptor() {
        out.write(0x2C)
        writeShort(0); writeShort(0) // left, top
        writeShort(width); writeShort(height)
        out.write(0x87) // local color table, 256 entries (2^(7+1))
    }

    private fun writeColorTable(colorTab: ByteArray) {
        out.write(colorTab, 0, colorTab.size)
        val pad = 768 - colorTab.size
        if (pad > 0) out.write(ByteArray(pad))
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private fun writeLzwPixels(indices: ByteArray) {
        val minCodeSize = 8
        val lzw = LzwEncoder(width, height, indices, minCodeSize)
        lzw.encode(out)
    }
}

/**
 * GIF-style LZW compressor. This is a faithful port of the classic algorithm
 * used by the Unix `compress` utility and the widely used public-domain Java
 * GIF encoder (Weiner/Poskanzer lineage) — chosen deliberately over a
 * from-scratch implementation to avoid subtle bugs that would corrupt output.
 */
private class LzwEncoder(
    private val imgW: Int, private val imgH: Int,
    private val pixels: ByteArray, private val colorDepth: Int
) {
    private val EOF = -1
    private val BITS = 12
    private val HSIZE = 5003
    private val masks = intArrayOf(
        0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F, 0x00FF,
        0x01FF, 0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
    )

    private var initCodeSize = 0
    private var curPixelIdx = 0
    private var nBits = 0
    private var maxCode = 0
    private var maxMaxCode = 1 shl BITS
    private val htab = IntArray(HSIZE)
    private val codeTab = IntArray(HSIZE)
    private var hSize = HSIZE
    private var freeEnt = 0
    private var clearFlg = false
    private var gInitBits = 0
    private var clearCode = 0
    private var eofCode = 0
    private var curAccum = 0
    private var curBits = 0
    private var aCount = 0
    private val accum = ByteArray(256)
    private lateinit var outStream: OutputStream

    private fun nextPixel(): Int {
        if (curPixelIdx >= pixels.size) return EOF
        return (pixels[curPixelIdx++].toInt() and 0xFF)
    }

    private fun maxCode(nBits: Int) = (1 shl nBits) - 1

    fun encode(out: OutputStream) {
        outStream = out
        initCodeSize = maxOf(2, colorDepth)
        out.write(initCodeSize)
        curPixelIdx = 0
        compress(initCodeSize + 1, out)
        out.write(0) // block terminator
    }

    private fun compress(initBitsIn: Int, out: OutputStream) {
        gInitBits = initBitsIn
        clearFlg = false
        nBits = gInitBits
        maxCode = maxCode(nBits)
        clearCode = 1 shl (initBitsIn - 1)
        eofCode = clearCode + 1
        freeEnt = clearCode + 2
        aCount = 0

        var ent = nextPixel()
        var hshift = 0
        var fcode = hSize
        while (fcode < 65536) { fcode *= 2; hshift++ }
        hshift = 8 - hshift
        val hshiftFinal = hshift
        var hsizeReg = hSize
        clearHash(hsizeReg)
        output(clearCode)

        outer@ while (true) {
            val c = nextPixel()
            if (c == EOF) break
            val fcode2 = (c shl BITS) + ent
            var i = (c shl hshiftFinal) xor ent
            if (htab[i] == fcode2) {
                ent = codeTab[i]
                continue
            } else if (htab[i] >= 0) {
                var disp = hsizeReg - i
                if (i == 0) disp = 1
                while (true) {
                    i -= disp
                    if (i < 0) i += hsizeReg
                    if (htab[i] == fcode2) { ent = codeTab[i]; continue@outer }
                    if (htab[i] < 0) break
                }
            }
            output(ent)
            ent = c
            if (freeEnt < maxMaxCode) {
                codeTab[i] = freeEnt++
                htab[i] = fcode2
            } else {
                clearHash(hsizeReg)
                freeEnt = clearCode + 2
                clearFlg = true
                output(clearCode)
                nBits = gInitBits
                maxCode = maxCode(nBits)
            }
        }
        output(ent)
        output(eofCode)
    }

    private fun clearHash(size: Int) { for (i in 0 until size) htab[i] = -1 }

    private fun output(codeIn: Int) {
        curAccum = curAccum and masks[curBits]
        curAccum = if (curBits > 0) curAccum or (codeIn shl curBits) else codeIn
        curBits += nBits
        while (curBits >= 8) {
            charOut((curAccum and 0xFF).toByte())
            curAccum = curAccum ushr 8
            curBits -= 8
        }
        if (freeEnt > maxCode || clearFlg) {
            if (clearFlg) {
                nBits = gInitBits
                maxCode = maxCode(nBits)
                clearFlg = false
            } else {
                nBits++
                maxCode = if (nBits == BITS) maxMaxCode else maxCode(nBits)
            }
        }
        if (codeIn == eofCode) {
            while (curBits > 0) {
                charOut((curAccum and 0xFF).toByte())
                curAccum = curAccum ushr 8
                curBits -= 8
            }
            flushChar()
        }
    }

    private fun charOut(c: Byte) {
        accum[aCount++] = c
        if (aCount >= 254) flushChar()
    }

    private fun flushChar() {
        if (aCount > 0) {
            outStream.write(aCount)
            outStream.write(accum, 0, aCount)
            aCount = 0
        }
    }
}

/**
 * NeuQuant color quantization (simplified). Produces a 256-color palette
 * tuned to the actual frame content — full quality, no naive/fixed palette.
 */
private class NeuQuant(private val pixels: IntArray, private val highQuality: Boolean = false) {
    private val netSize = 256
    private val network = Array(netSize) { DoubleArray(3) }
    private val bias = DoubleArray(netSize)
    private val freq = DoubleArray(netSize) { 1.0 / netSize }

    init {
        for (i in 0 until netSize) {
            val v = i * 256.0 / netSize
            network[i][0] = v; network[i][1] = v; network[i][2] = v
        }
    }

    fun process(): ByteArray {
        learn()
        val tab = ByteArray(netSize * 3)
        for (i in 0 until netSize) {
            tab[i * 3]     = network[i][0].toInt().coerceIn(0, 255).toByte()
            tab[i * 3 + 1] = network[i][1].toInt().coerceIn(0, 255).toByte()
            tab[i * 3 + 2] = network[i][2].toInt().coerceIn(0, 255).toByte()
        }
        return tab
    }

    private fun learn() {
        if (pixels.isEmpty()) return
        val sampleCount = pixels.size
        // Video frames use a tighter cap to keep multi-frame encodes tractable;
        // a single still image is a one-shot cost, so train on far more of it.
        val cap = if (highQuality) 400_000 else 8_000
        val step = maxOf(1, sampleCount / cap)
        var alpha = 0.3
        val alphaDecay = alpha / (sampleCount / step).coerceAtLeast(1)
        var idx = 0
        var i = 0
        while (i < sampleCount) {
            val p = pixels[idx]
            val r = (p shr 16 and 0xFF).toDouble()
            val g = (p shr 8 and 0xFF).toDouble()
            val b = (p and 0xFF).toDouble()
            var best = 0; var bestDist = Double.MAX_VALUE
            for (n in 0 until netSize) {
                val dr = network[n][0] - r; val dg = network[n][1] - g; val db = network[n][2] - b
                val d = dr * dr + dg * dg + db * db
                if (d < bestDist) { bestDist = d; best = n }
            }
            network[best][0] += alpha * (r - network[best][0])
            network[best][1] += alpha * (g - network[best][1])
            network[best][2] += alpha * (b - network[best][2])
            alpha = (alpha - alphaDecay).coerceAtLeast(0.01)
            idx = (idx + step) % sampleCount
            i++
        }
    }

    private val mapCache = HashMap<Int, Int>()

    fun map(rgb: Int): Int {
        mapCache[rgb]?.let { return it }
        val r = (rgb shr 16 and 0xFF).toDouble()
        val g = (rgb shr 8 and 0xFF).toDouble()
        val b = (rgb and 0xFF).toDouble()
        var best = 0; var bestDist = Double.MAX_VALUE
        for (n in 0 until netSize) {
            val dr = network[n][0] - r; val dg = network[n][1] - g; val db = network[n][2] - b
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) { bestDist = d; best = n }
        }
        mapCache[rgb] = best
        return best
    }
}
