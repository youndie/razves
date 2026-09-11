package io.github.youndie.razves.klib

/**
 * Just enough ZIP to read a klib: the central directory, and one entry's bytes.
 *
 * A klib is a zip, and razves reads it rather than unpacking it with something else — the same
 * decision as everywhere else in this module, for the same reason. Two things come out of it and
 * they cost very differently:
 *
 * * **the entry names**, which are the package list (`default/linkdata/package_io.ktor.http/`) and
 *   live in the central directory uncompressed, so they are nearly free;
 * * **the manifest's contents**, which carry `unique_name` and `native_targets` and are deflated
 *   like every other entry, so they cost [Inflate].
 *
 * Zip64 is refused rather than half-supported. No klib comes near four gigabytes or sixty-five
 * thousand entries, and a reader that silently misreads a format it does not implement is worse than
 * one that says so.
 */
internal object Zip {
    private const val END_OF_CENTRAL_DIRECTORY = 0x06054B50
    private const val CENTRAL_FILE_HEADER = 0x02014B50
    private const val LOCAL_FILE_HEADER = 0x04034B50
    private const val EOCD_FIXED_SIZE = 22
    private const val CENTRAL_HEADER_FIXED_SIZE = 46
    private const val LOCAL_HEADER_FIXED_SIZE = 30
    private const val ZIP64_MARKER = 0xFFFF
    private const val STORED = 0
    private const val DEFLATED = 8

    class Entry(
        val name: String,
        val compressionMethod: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Int,
    )

    /** Every entry's name and where to find its bytes. Nothing is decompressed here. */
    fun entries(data: ByteArray): List<Entry> {
        val eocd = findEndOfCentralDirectory(data)
        val entryCount = u16(data, eocd + 10)
        require(entryCount != ZIP64_MARKER) { "this archive is Zip64, which razves does not read" }
        var at = u32(data, eocd + 16)
        val out = ArrayList<Entry>(entryCount)
        repeat(entryCount) {
            require(u32(data, at) == CENTRAL_FILE_HEADER) {
                "the central directory of this archive is malformed at offset $at"
            }
            val nameLength = u16(data, at + 28)
            val extraLength = u16(data, at + 30)
            val commentLength = u16(data, at + 32)
            out +=
                Entry(
                    name =
                        data.decodeToString(
                            at + CENTRAL_HEADER_FIXED_SIZE,
                            at + CENTRAL_HEADER_FIXED_SIZE + nameLength,
                        ),
                    compressionMethod = u16(data, at + 10),
                    compressedSize = u32(data, at + 20),
                    uncompressedSize = u32(data, at + 24),
                    localHeaderOffset = u32(data, at + 42),
                )
            at += CENTRAL_HEADER_FIXED_SIZE + nameLength + extraLength + commentLength
        }
        return out
    }

    /** The bytes of one entry, inflated if it needs to be. */
    fun read(
        data: ByteArray,
        entry: Entry,
    ): ByteArray {
        val header = entry.localHeaderOffset
        require(u32(data, header) == LOCAL_FILE_HEADER) {
            "${entry.name} does not start with a local file header at offset $header"
        }
        // The local header's own name and extra lengths, not the central directory's: they are
        // allowed to differ, and a reader that uses the wrong pair lands a few bytes into the data.
        val start = header + LOCAL_HEADER_FIXED_SIZE + u16(data, header + 26) + u16(data, header + 28)
        return when (entry.compressionMethod) {
            STORED -> {
                data.copyOfRange(start, start + entry.compressedSize)
            }

            DEFLATED -> {
                Inflate.raw(data, start, entry.compressedSize, entry.uncompressedSize)
            }

            else -> {
                error(
                    "${entry.name} uses compression method ${entry.compressionMethod}, which razves does not read",
                )
            }
        }
    }

    private fun findEndOfCentralDirectory(data: ByteArray): Int {
        require(data.size >= EOCD_FIXED_SIZE) { "a file of ${data.size} bytes is too small to be a zip" }
        // Backwards, because the record sits at the end behind a comment of unknown length.
        var at = data.size - EOCD_FIXED_SIZE
        while (at >= 0) {
            if (u32(data, at) == END_OF_CENTRAL_DIRECTORY) return at
            at--
        }
        error("this file has no zip end-of-central-directory record")
    }

    private fun u16(
        data: ByteArray,
        at: Int,
    ): Int {
        require(at + 1 < data.size) { "read past the end of a ${data.size}-byte archive at $at" }
        return (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(
        data: ByteArray,
        at: Int,
    ): Int {
        require(at + 3 < data.size) { "read past the end of a ${data.size}-byte archive at $at" }
        return (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)
    }
}
