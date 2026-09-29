package com.mediaviewer.stream

/** H.264 Annex-B helpers (pure JVM, shared by the encoder and tests). */
object AnnexB {
    /** Splits an Annex-B buffer (00 00 01 / 00 00 00 01 start codes) into
     *  raw NAL units. Bytes before the first start code are ignored. */
    fun split(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        val end = offset + length
        val starts = ArrayList<Int>() // index of first NAL byte after each start code
        var i = offset
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) { starts.add(i + 3); i += 3; continue }
                if (i + 3 < end && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) { starts.add(i + 4); i += 4; continue }
            }
            i++
        }
        if (starts.isEmpty()) return if (length > 0) listOf(data.copyOfRange(offset, end)) else emptyList()
        val out = ArrayList<ByteArray>(starts.size)
        for ((k, s) in starts.withIndex()) {
            var e = if (k + 1 < starts.size) starts[k + 1] - 3 else end
            // A 4-byte start code leaves one extra leading zero on the previous NAL.
            if (k + 1 < starts.size && e > s && data[e - 1].toInt() == 0) e--
            if (e > s) out.add(data.copyOfRange(s, e))
        }
        return out
    }

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9
}
