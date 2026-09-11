package io.github.youndie.razves.profile

/**
 * A gzip container whose DEFLATE payload is **stored** - that is, not compressed at all.
 *
 * pprof requires the serialised proto to be gzipped, and razves has an inflater and no deflater.
 * Writing one would be a Huffman encoder; a stored block needs a five-byte header, a CRC32 and a
 * length, and RFC 1951 says a decoder must accept it.
 *
 * **That was a hypothesis and it was checked before anything was built on it**: a 90-byte container
 * written this way, holding a 67-byte profile, was read by `go tool pprof -top`, which printed the
 * sample it contained. See [B-33](../../../../../../../docs/backlog/B-33-pprof-writer.md).
 *
 * The cost is size: a profile compresses well, and this gives up all of it. That is a trade to
 * revisit with a measurement, not a defect - and until a profile is large enough for anybody to
 * notice, a Huffman encoder is a day spent on nothing.
 */
internal object GzipStored {
    private const val MAX_BLOCK = 65_535

    fun wrap(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size + 64)
        // Magic, DEFLATE, no flags, no mtime, no extra flags, OS unknown.
        listOf(0x1F, 0x8B, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF).forEach { out += it.toByte() }

        var at = 0
        if (data.isEmpty()) {
            // An empty payload still needs a final block, or the reader waits for one that never comes.
            listOf(0x01, 0x00, 0x00, 0xFF, 0xFF).forEach { out += it.toByte() }
        }
        while (at < data.size) {
            val length = minOf(MAX_BLOCK, data.size - at)
            val last = at + length >= data.size
            out += (if (last) 1 else 0).toByte()
            // LEN and its ones complement, little-endian, which is what makes a stored block checkable.
            out += (length and 0xFF).toByte()
            out += ((length shr 8) and 0xFF).toByte()
            val inverted = length.inv() and 0xFFFF
            out += (inverted and 0xFF).toByte()
            out += ((inverted shr 8) and 0xFF).toByte()
            for (i in 0 until length) out += data[at + i]
            at += length
        }

        val crc = crc32(data)
        listOf(crc, data.size.toLong()).forEach { value ->
            for (shift in 0 until 32 step 8) out += ((value shr shift) and 0xFF).toByte()
        }
        return out.toByteArray()
    }

    /** The ordinary table-less CRC32 of RFC 1952. Slow per byte and irrelevant beside the file it checks. */
    private fun crc32(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (byte in data) {
            crc = crc xor (byte.toLong() and 0xFF)
            repeat(8) {
                crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1
            }
        }
        return crc xor 0xFFFFFFFFL
    }
}
