package io.github.youndie.razves.klib

/**
 * The symbols a static archive defines, out of its own index.
 *
 * This is the answer to the one part of a binary no mangling scheme can explain. 26.7% of the
 * attributed bytes of a real release binary carry names with no scheme at all — `ossl_aes_gcm_*`,
 * `nid_objs`, `sha1_multi_block` — because the C ABI has no namespaces. A grammar over names cannot
 * recover where they came from; the archive that defines them can, and the archives are right there:
 * a cinterop klib carries them at the `included` directory under `default/targets/<target>`, and
 * `cryptography-provider-openssl3-prebuilt` ships a 12,737,852-byte `libcrypto.a` that way.
 *
 * **The index, not the members.** A GNU `ar` writes a first member named `/` holding every symbol
 * its objects define, sorted, with an offset each. Reading it costs one pass over a few hundred
 * kilobytes; parsing every object file inside a 12 MB archive to learn the same thing would cost
 * thousands. `libcrypto.a` declares 9,023 symbols in 251,116 bytes of index.
 *
 * Refused rather than guessed at: the BSD variant (`__.SYMDEF`), and an archive with no index at
 * all. Both exist, neither appears in a Kotlin/Native link, and a reader that silently returns
 * nothing for them would make a library look weightless.
 */
internal object Archive {
    private val MAGIC = "!<arch>\n".encodeToByteArray()
    private const val MEMBER_HEADER_SIZE = 60
    private const val NAME_SIZE = 16
    private const val SIZE_AT = 48
    private const val SIZE_LENGTH = 10

    fun matches(data: ByteArray): Boolean = data.size >= MAGIC.size && MAGIC.indices.all { data[it] == MAGIC[it] }

    /** Every symbol the archive's index names, or an empty set when it has no index razves reads. */
    fun definedSymbols(data: ByteArray): Set<String> {
        if (!matches(data)) return emptySet()
        var at = MAGIC.size
        while (at + MEMBER_HEADER_SIZE <= data.size) {
            val name = data.decodeToString(at, at + NAME_SIZE).trimEnd()
            val size =
                data.decodeToString(at + SIZE_AT, at + SIZE_AT + SIZE_LENGTH).trim().toLongOrNull() ?: return emptySet()
            val body = at + MEMBER_HEADER_SIZE
            when {
                // GNU, 32-bit offsets. The common case and the one Kotlin/Native links.
                name == "/" -> return readIndex(data, body, size.toInt(), offsetBytes = 4)

                // GNU, 64-bit offsets: the same layout with wider numbers, used once an archive passes
                // four gigabytes of member offsets.
                name == "/SYM64/" -> return readIndex(data, body, size.toInt(), offsetBytes = 8)

                // `//` is the long-name table and always follows the index, so reaching it means there
                // was none. Anything else means the first member is an object: no index at all.
                name == "//" || !name.startsWith("/") -> return emptySet()
            }
            at = body + size.toInt() + (size.toInt() % 2)
        }
        return emptySet()
    }

    /**
     * The index: a count, that many offsets, then that many NUL-terminated names.
     *
     * Only the names are read. The offsets say which member defines each symbol, which would matter
     * to a linker and does not matter here: razves is asking which *archive* a symbol came from, and
     * every member of one archive is the same answer.
     */
    private fun readIndex(
        data: ByteArray,
        at: Int,
        size: Int,
        offsetBytes: Int,
    ): Set<String> {
        if (at + size > data.size || size < offsetBytes) return emptySet()
        val count = readBigEndian(data, at, offsetBytes).toInt()
        if (count <= 0) return emptySet()
        var cursor = at + offsetBytes + count * offsetBytes
        val end = at + size
        val out = LinkedHashSet<String>(count)
        repeat(count) {
            if (cursor >= end) return out
            var stop = cursor
            while (stop < end && data[stop].toInt() != 0) stop++
            out += data.decodeToString(cursor, stop)
            cursor = stop + 1
        }
        return out
    }

    /** Archive indexes are big-endian whatever the machine is: the format predates the argument. */
    private fun readBigEndian(
        data: ByteArray,
        at: Int,
        bytes: Int,
    ): Long {
        var value = 0L
        for (i in 0 until bytes) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value
    }
}
