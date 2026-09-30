package com.mediaviewer.util

/**
 * A small QR Code encoder (byte mode, any error-correction level), written in
 * common Kotlin after Project Nayuki's reference implementation (MIT). iOS
 * uses it for the profile QR page; Android keeps using ZXing.
 *
 * [encode] returns the module grid (true = dark) without a quiet zone.
 */
object QrEncoder {
    enum class Ecc(val ordinal2: Int, val formatBits: Int) { L(0, 1), M(1, 0), Q(2, 3), H(3, 2) }

    fun encode(text: String, ecc: Ecc = Ecc.H): Array<BooleanArray> {
        val data = text.encodeToByteArray()
        var version = 1
        while (true) {
            val capacityBits = numDataCodewords(version, ecc) * 8
            val ccBits = if (version <= 9) 8 else 16
            val used = 4 + ccBits + data.size * 8
            if (used <= capacityBits) break
            version++
            require(version <= 40) { "Data too long for a QR code" }
        }
        val bb = BitBuffer()
        bb.append(0x4, 4)
        bb.append(data.size, if (version <= 9) 8 else 16)
        for (b in data) bb.append(b.toInt() and 0xFF, 8)
        val capacityBits = numDataCodewords(version, ecc) * 8
        bb.append(0, minOf(4, capacityBits - bb.size))
        bb.append(0, (8 - bb.size % 8) % 8)
        var pad = 0xEC
        while (bb.size < capacityBits) { bb.append(pad, 8); pad = pad xor (0xEC xor 0x11) }
        val dataCodewords = ByteArray(bb.size / 8)
        for (i in 0 until bb.size) if (bb.get(i)) {
            val idx = i ushr 3
            dataCodewords[idx] = (dataCodewords[idx].toInt() or (1 shl (7 - (i and 7)))).toByte()
        }
        return Symbol(version, ecc, dataCodewords).modules
    }

    // ---- tables -------------------------------------------------------

    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    )
    private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
    )

    private fun numRawDataModules(ver: Int): Int {
        var result = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val numAlign = ver / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (ver >= 7) result -= 36
        }
        return result
    }

    private fun numDataCodewords(ver: Int, ecl: Ecc): Int =
        numRawDataModules(ver) / 8 -
            ECC_CODEWORDS_PER_BLOCK[ecl.ordinal2][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal2][ver]

    // ---- Reed-Solomon ---------------------------------------------------

    private fun rsMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun rsDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = rsMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < result.size) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
            }
            root = rsMultiply(root, 0x02)
        }
        return result
    }

    private fun rsRemainder(data: ByteArray, off: Int, len: Int, divisor: ByteArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (k in off until off + len) {
            val factor = (data[k].toInt() xor result[0].toInt()) and 0xFF
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices)
                result[i] = (result[i].toInt() xor rsMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
        }
        return result
    }

    // ---- bits -----------------------------------------------------------

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size: Int get() = bits.size
        fun get(i: Int) = bits[i]
        fun append(value: Int, len: Int) { for (i in len - 1 downTo 0) bits.add(((value ushr i) and 1) != 0) }
    }

    // ---- the symbol -----------------------------------------------------

    private class Symbol(val version: Int, val ecc: Ecc, dataCodewords: ByteArray) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        private val isFunction = Array(size) { BooleanArray(size) }

        init {
            drawFunctionPatterns()
            val all = addEccAndInterleave(dataCodewords)
            drawCodewords(all)
            var bestMask = 0
            var minPenalty = Int.MAX_VALUE
            for (i in 0 until 8) {
                applyMask(i)
                drawFormatBits(i)
                val p = penaltyScore()
                if (p < minPenalty) { bestMask = i; minPenalty = p }
                applyMask(i)
            }
            applyMask(bestMask)
            drawFormatBits(bestMask)
        }

        private fun setFunction(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; isFunction[y][x] = true }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) { setFunction(6, i, i % 2 == 0); setFunction(i, 6, i % 2 == 0) }
            drawFinder(3, 3); drawFinder(size - 4, 3); drawFinder(3, size - 4)
            val align = alignmentPositions()
            val n = align.size
            for (i in 0 until n) for (j in 0 until n) {
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) drawAlignment(align[i], align[j])
            }
            drawFormatBits(0)
            drawVersion()
        }

        private fun drawFormatBits(mask: Int) {
            val data = (ecc.formatBits shl 3) or mask
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0x5412
            for (i in 0..5) setFunction(8, i, bit(bits, i))
            setFunction(8, 7, bit(bits, 6)); setFunction(8, 8, bit(bits, 7)); setFunction(7, 8, bit(bits, 8))
            for (i in 9 until 15) setFunction(14 - i, 8, bit(bits, i))
            for (i in 0 until 8) setFunction(size - 1 - i, 8, bit(bits, i))
            for (i in 8 until 15) setFunction(8, size - 15 + i, bit(bits, i))
            setFunction(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val b = bit(bits, i)
                val a = size - 11 + i % 3
                val c = i / 3
                setFunction(a, c, b); setFunction(c, a, b)
            }
        }

        private fun drawFinder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) setFunction(xx, yy, dist != 2 && dist != 4)
            }
        }

        private fun drawAlignment(x: Int, y: Int) {
            for (dy in -2..2) for (dx in -2..2)
                setFunction(x + dx, y + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var i = result.size - 1
            var pos = size - 7
            while (i >= 1) { result[i] = pos; i--; pos -= step }
            return result
        }

        private fun addEccAndInterleave(data: ByteArray): ByteArray {
            val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal2][version]
            val blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc.ordinal2][version]
            val rawCodewords = numRawDataModules(version) / 8
            val numShortBlocks = numBlocks - rawCodewords % numBlocks
            val shortBlockLen = rawCodewords / numBlocks
            val blocks = ArrayList<ByteArray>()
            val div = rsDivisor(blockEccLen)
            var k = 0
            for (i in 0 until numBlocks) {
                val datLen = shortBlockLen - blockEccLen + (if (i < numShortBlocks) 0 else 1)
                val ecc = rsRemainder(data, k, datLen, div)
                val block = ByteArray(shortBlockLen + 1)
                data.copyInto(block, 0, k, k + datLen)
                ecc.copyInto(block, block.size - blockEccLen)
                k += datLen
                blocks.add(block)
            }
            val result = ByteArray(rawCodewords)
            var r = 0
            for (i in 0 until blocks[0].size) {
                for (j in blocks.indices) {
                    if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                        result[r++] = blocks[j][i]
                    }
                }
            }
            return result
        }

        private fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = bit(data[i ushr 3].toInt(), 7 - (i and 7))
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                modules[y][x] = modules[y][x] xor (invert && !isFunction[y][x])
            }
        }

        private fun penaltyScore(): Int {
            var result = 0
            for (y in 0 until size) {
                var runColor = false; var runX = 0
                val hist = IntArray(7)
                for (x in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runX++
                        if (runX == 5) result += 3 else if (runX > 5) result++
                    } else {
                        finderPenaltyAddHistory(runX, hist)
                        if (!runColor) result += finderPenaltyCountPatterns(hist) * 40
                        runColor = modules[y][x]; runX = 1
                    }
                }
                result += finderPenaltyTerminateAndCount(runColor, runX, hist) * 40
            }
            for (x in 0 until size) {
                var runColor = false; var runY = 0
                val hist = IntArray(7)
                for (y in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runY++
                        if (runY == 5) result += 3 else if (runY > 5) result++
                    } else {
                        finderPenaltyAddHistory(runY, hist)
                        if (!runColor) result += finderPenaltyCountPatterns(hist) * 40
                        runColor = modules[y][x]; runY = 1
                    }
                }
                result += finderPenaltyTerminateAndCount(runColor, runY, hist) * 40
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            var dark = 0
            for (row in modules) for (m in row) if (m) dark++
            val total = size * size
            val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * 10
            return result
        }

        private fun finderPenaltyCountPatterns(h: IntArray): Int {
            val n = h[1]
            val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
            return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) +
                (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
        }

        private fun finderPenaltyTerminateAndCount(currentRunColor: Boolean, currentRunLength: Int, h: IntArray): Int {
            var len = currentRunLength
            if (currentRunColor) { finderPenaltyAddHistory(len, h); len = 0 }
            len += size
            finderPenaltyAddHistory(len, h)
            return finderPenaltyCountPatterns(h)
        }

        private fun finderPenaltyAddHistory(currentRunLength: Int, h: IntArray) {
            var len = currentRunLength
            if (h[0] == 0) len += size
            for (i in h.size - 1 downTo 1) h[i] = h[i - 1]
            h[0] = len
        }

        private fun bit(x: Int, i: Int) = ((x ushr i) and 1) != 0
    }
}
