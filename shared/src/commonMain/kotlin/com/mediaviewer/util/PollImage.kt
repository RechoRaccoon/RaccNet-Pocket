package com.mediaviewer.util

/**
 * The small picture attached to a poll post. Other Bluesky apps show it
 * next to the "Q. / A. / B." text (its alt text describes the poll); Stellar
 * recognises a poll by that alt text and draws real, tappable answers
 * instead. Drawn and PNG-encoded in plain Kotlin, so it works the same on
 * Android and iOS with no platform image code.
 */
object PollImage {
    const val WIDTH = 400
    const val HEIGHT = 210

    /** A dark card with one rounded bar per answer, in Stellar's pink. */
    fun png(optionCount: Int): ByteArray {
        val w = WIDTH
        val h = HEIGHT
        val rgb = ByteArray(w * h * 3)
        fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
            if (x < 0 || y < 0 || x >= w || y >= h) return
            val i = (y * w + x) * 3
            rgb[i] = r.toByte(); rgb[i + 1] = g.toByte(); rgb[i + 2] = b.toByte()
        }
        // Background: a soft vertical gradient (deep violet to near-black).
        for (y in 0 until h) {
            val t = y.toFloat() / (h - 1)
            val r = (34 - 20 * t).toInt()
            val g = (20 - 10 * t).toInt()
            val b = (48 - 26 * t).toInt()
            for (x in 0 until w) put(x, y, r, g, b)
        }
        val n = optionCount.coerceIn(2, 6)
        val margin = 28
        val gap = 12
        val barH = ((h - margin * 2 - gap * (n - 1)) / n).coerceAtLeast(10)
        val radius = barH / 2
        // Bars of different lengths, like results coming in.
        val fills = floatArrayOf(0.82f, 0.56f, 0.7f, 0.38f, 0.62f, 0.46f)
        for (k in 0 until n) {
            val top = margin + k * (barH + gap)
            val fullW = w - margin * 2
            val fillW = (fullW * fills[k]).toInt()
            for (yy in 0 until barH) {
                for (xx in 0 until fullW) {
                    // Rounded ends.
                    val cx = when {
                        xx < radius -> radius
                        xx > fullW - radius - 1 -> fullW - radius - 1
                        else -> xx
                    }
                    val dx = xx - cx
                    val dy = yy - radius
                    if (dx * dx + dy * dy > radius * radius) continue
                    if (xx <= fillW) {
                        val t = xx.toFloat() / fullW
                        put(margin + xx, top + yy, 255, (79 + 60 * t).toInt(), (161 + 40 * t).toInt())
                    } else {
                        put(margin + xx, top + yy, 58, 42, 72)
                    }
                }
            }
        }
        return encodePng(w, h, rgb)
    }

    // ── A minimal PNG writer (8-bit RGB, stored/uncompressed deflate) ──

    private val crcTable = IntArray(256).also { table ->
        for (n in 0 until 256) {
            var c = n
            for (k in 0 until 8) c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
            table[n] = c
        }
    }

    private class Sink(capacity: Int) {
        var data = ByteArray(capacity)
        var size = 0
        fun write(b: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = b.toByte()
        }
        fun write(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
            for (i in from until to) write(bytes[i].toInt())
        }
        fun int(v: Int) { write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v) }
    }

    private fun chunk(out: Sink, type: String, body: ByteArray, bodySize: Int) {
        out.int(bodySize)
        var crc = -1
        val typeBytes = type.encodeToByteArray()
        for (b in typeBytes) crc = crcTable[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        for (i in 0 until bodySize) crc = crcTable[(crc xor body[i].toInt()) and 0xFF] xor (crc ushr 8)
        out.write(typeBytes)
        out.write(body, 0, bodySize)
        out.int(crc.inv())
    }

    private fun encodePng(w: Int, h: Int, rgb: ByteArray): ByteArray {
        // Raw scanlines: a filter byte (0 = none) then the row's pixels.
        val stride = w * 3 + 1
        val raw = ByteArray(stride * h)
        for (y in 0 until h) rgb.copyInto(raw, y * stride + 1, y * w * 3, (y + 1) * w * 3)

        // zlib stream of stored blocks.
        val z = Sink(raw.size + raw.size / 65535 * 5 + 16)
        z.write(0x78); z.write(0x01)
        var pos = 0
        while (pos < raw.size) {
            val len = minOf(65535, raw.size - pos)
            val last = pos + len >= raw.size
            z.write(if (last) 1 else 0)
            z.write(len and 0xFF); z.write(len ushr 8)
            z.write(len.inv() and 0xFF); z.write((len.inv() ushr 8) and 0xFF)
            z.write(raw, pos, pos + len)
            pos += len
        }
        var a = 1
        var b = 0
        for (byte in raw) {
            a = (a + (byte.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        z.int((b shl 16) or a)

        val out = Sink(z.size + 64)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val header = Sink(13)
        header.int(w); header.int(h)
        header.write(8); header.write(2); header.write(0); header.write(0); header.write(0)
        chunk(out, "IHDR", header.data, header.size)
        chunk(out, "IDAT", z.data, z.size)
        chunk(out, "IEND", ByteArray(0), 0)
        return out.data.copyOf(out.size)
    }
}
