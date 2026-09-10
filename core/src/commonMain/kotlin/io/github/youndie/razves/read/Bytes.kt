package io.github.youndie.razves.read

/**
 * Endian-aware reads over a whole file held in memory.
 *
 * Whole-file rather than streaming on purpose: the subjects are tens of megabytes, the parse jumps
 * between the section header table, the string table and the symbol table, and a seekable
 * abstraction that works the same on the JVM and on both native targets is more moving parts than
 * the memory is worth.
 */
internal class Bytes(
    val data: ByteArray,
    val littleEndian: Boolean,
) {
    val size: Int get() = data.size

    fun u8(at: Int): Int {
        require(at in data.indices) { "read past the end of the file: byte $at of ${data.size}" }
        return data[at].toInt() and 0xFF
    }

    fun u16(at: Int): Int =
        if (littleEndian) {
            u8(at) or (u8(at + 1) shl 8)
        } else {
            (u8(at) shl 8) or u8(at + 1)
        }

    fun u32(at: Int): Long {
        val b = IntArray(4) { u8(at + it) }
        return if (littleEndian) {
            (b[0].toLong()) or (b[1].toLong() shl 8) or (b[2].toLong() shl 16) or (b[3].toLong() shl 24)
        } else {
            (b[3].toLong()) or (b[2].toLong() shl 8) or (b[1].toLong() shl 16) or (b[0].toLong() shl 24)
        }
    }

    /**
     * Returned as [Long] and rejected when the top bit is set. Nothing razves reads — a file offset,
     * a section size, a virtual address in an executable — legitimately exceeds 2^63, and a value
     * that does is a misparse rather than a very large binary. Saying so here is cheaper than
     * chasing a negative size through the arithmetic later.
     */
    fun u64(at: Int): Long {
        var acc = 0L
        if (littleEndian) {
            for (i in 7 downTo 0) acc = (acc shl 8) or u8(at + i).toLong()
        } else {
            for (i in 0..7) acc = (acc shl 8) or u8(at + i).toLong()
        }
        require(acc >= 0) { "unsigned 64-bit value at $at does not fit in a signed Long: this is a misparse" }
        return acc
    }

    /**
     * A fixed-width, NUL-padded name of at most 16 bytes: Mach-O's `segname` and `sectname`, which
     * are not NUL-terminated when the name fills the field.
     */
    fun cString16(at: Int): String {
        var end = at
        while (end < at + 16 && end < data.size && data[end].toInt() != 0) end++
        return data.decodeToString(at, end)
    }

    /** A NUL-terminated string starting at [at]; used for the section and symbol string tables. */
    fun cString(at: Int): String {
        require(at in data.indices) { "string offset $at is outside the file" }
        var end = at
        while (end < data.size && data[end].toInt() != 0) end++
        return data.decodeToString(at, end)
    }
}
