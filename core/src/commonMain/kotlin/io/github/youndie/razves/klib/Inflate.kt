package io.github.youndie.razves.klib

/**
 * Raw DEFLATE decompression (RFC 1951), because a klib's manifest is compressed and razves may not
 * shell out to read it.
 *
 * This is here reluctantly and deliberately. The package list of a klib comes out of the zip's
 * central directory, which is never compressed — but `unique_name` and `native_targets` live in
 * `default/manifest`, and every entry of every klib in the dependency cache is deflated. Measured on
 * `ktor-http-linuxX64Main-3.5.1.klib`: 86 entries, all of them compression method 8.
 *
 * The alternatives were worse. `java.util.zip.Inflater` is JVM-only and the CLI is a native binary;
 * a cinterop binding to the system zlib adds a def file, a platform link and a dependency that is
 * present on Linux and macOS and absent from the reasoning; deriving the module coordinate from the
 * Gradle cache's directory layout is a guess about someone else's implementation detail. Two hundred
 * lines of a thirty-year-old, fully specified algorithm is the cheapest of the four, and it is the
 * only one that keeps working when the next front end appears.
 *
 * Correctness over speed: the Huffman decoder walks bit by bit through a canonical code table rather
 * than building a lookup. The inputs are manifests of a few kilobytes, read once per klib.
 */
internal object Inflate {
    private const val MAX_BITS = 15
    private const val END_OF_BLOCK = 256

    private val LENGTH_BASE =
        intArrayOf(
            3,
            4,
            5,
            6,
            7,
            8,
            9,
            10,
            11,
            13,
            15,
            17,
            19,
            23,
            27,
            31,
            35,
            43,
            51,
            59,
            67,
            83,
            99,
            115,
            131,
            163,
            195,
            227,
            258,
        )
    private val LENGTH_EXTRA =
        intArrayOf(
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            1,
            1,
            1,
            1,
            2,
            2,
            2,
            2,
            3,
            3,
            3,
            3,
            4,
            4,
            4,
            4,
            5,
            5,
            5,
            5,
            0,
        )
    private val DISTANCE_BASE =
        intArrayOf(
            1,
            2,
            3,
            4,
            5,
            7,
            9,
            13,
            17,
            25,
            33,
            49,
            65,
            97,
            129,
            193,
            257,
            385,
            513,
            769,
            1025,
            1537,
            2049,
            3073,
            4097,
            6145,
            8193,
            12289,
            16385,
            24577,
        )
    private val DISTANCE_EXTRA =
        intArrayOf(
            0,
            0,
            0,
            0,
            1,
            1,
            2,
            2,
            3,
            3,
            4,
            4,
            5,
            5,
            6,
            6,
            7,
            7,
            8,
            8,
            9,
            9,
            10,
            10,
            11,
            11,
            12,
            12,
            13,
            13,
        )

    /** The order the dynamic-block header lists its code-length code lengths in. */
    private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    fun raw(
        data: ByteArray,
        from: Int,
        length: Int,
        expectedSize: Int,
    ): ByteArray {
        val input = BitReader(data, from, from + length)
        val out = ArrayList<Byte>(if (expectedSize > 0) expectedSize else 1024)
        while (true) {
            val last = input.bits(1) == 1
            when (val type = input.bits(2)) {
                0 -> {
                    stored(input, out)
                }

                1 -> {
                    block(input, out, fixedLiterals(), fixedDistances())
                }

                2 -> {
                    val (literals, distances) = dynamicTables(input)
                    block(input, out, literals, distances)
                }

                else -> {
                    error("deflate block type $type is reserved; this stream is not DEFLATE")
                }
            }
            if (last) break
        }
        return out.toByteArray()
    }

    private fun stored(
        input: BitReader,
        out: ArrayList<Byte>,
    ) {
        input.alignToByte()
        val len = input.bits(16)
        val nlen = input.bits(16)
        require(len == nlen.inv() and 0xFFFF) { "a stored deflate block whose LEN and NLEN disagree" }
        repeat(len) { out += input.byte() }
    }

    private fun block(
        input: BitReader,
        out: ArrayList<Byte>,
        literals: Huffman,
        distances: Huffman,
    ) {
        while (true) {
            val symbol = literals.decode(input)
            when {
                symbol < END_OF_BLOCK -> {
                    out += symbol.toByte()
                }

                symbol == END_OF_BLOCK -> {
                    return
                }

                else -> {
                    val lengthCode = symbol - 257
                    require(lengthCode < LENGTH_BASE.size) { "length code $symbol is outside the alphabet" }
                    val length = LENGTH_BASE[lengthCode] + input.bits(LENGTH_EXTRA[lengthCode])
                    val distanceCode = distances.decode(input)
                    require(distanceCode < DISTANCE_BASE.size) { "distance code $distanceCode is outside the alphabet" }
                    val distance = DISTANCE_BASE[distanceCode] + input.bits(DISTANCE_EXTRA[distanceCode])
                    require(
                        distance <= out.size,
                    ) { "a back-reference $distance bytes before a ${out.size}-byte window" }
                    val start = out.size - distance
                    // One byte at a time on purpose: a run may overlap itself, which is how DEFLATE
                    // spells "repeat this pattern" and why a bulk copy would be wrong.
                    repeat(length) { out += out[start + it] }
                }
            }
        }
    }

    private fun dynamicTables(input: BitReader): Pair<Huffman, Huffman> {
        val literalCount = input.bits(5) + 257
        val distanceCount = input.bits(5) + 1
        val codeLengthCount = input.bits(4) + 4

        val codeLengthLengths = IntArray(CODE_LENGTH_ORDER.size)
        for (i in 0 until codeLengthCount) codeLengthLengths[CODE_LENGTH_ORDER[i]] = input.bits(3)
        val codeLengths = Huffman(codeLengthLengths)

        val lengths = IntArray(literalCount + distanceCount)
        var at = 0
        while (at < lengths.size) {
            when (val symbol = codeLengths.decode(input)) {
                16 -> {
                    require(at > 0) { "a repeat-previous code with nothing before it" }
                    val previous = lengths[at - 1]
                    repeat(3 + input.bits(2)) { lengths[at++] = previous }
                }

                17 -> {
                    repeat(3 + input.bits(3)) { lengths[at++] = 0 }
                }

                18 -> {
                    repeat(11 + input.bits(7)) { lengths[at++] = 0 }
                }

                else -> {
                    lengths[at++] = symbol
                }
            }
        }
        return Huffman(lengths.copyOfRange(0, literalCount)) to
            Huffman(lengths.copyOfRange(literalCount, lengths.size))
    }

    private fun fixedLiterals(): Huffman {
        val lengths = IntArray(288)
        for (i in 0..143) lengths[i] = 8
        for (i in 144..255) lengths[i] = 9
        for (i in 256..279) lengths[i] = 7
        for (i in 280..287) lengths[i] = 8
        return Huffman(lengths)
    }

    private fun fixedDistances(): Huffman = Huffman(IntArray(30) { 5 })

    /**
     * A canonical Huffman table, decoded by the count-and-offset walk of RFC 1951 §3.2.2.
     *
     * The code lengths alone determine the codes, so the table is two small arrays rather than a
     * tree: how many codes there are of each length, and the symbols in canonical order.
     */
    private class Huffman(
        lengths: IntArray,
    ) {
        private val countOfLength = IntArray(MAX_BITS + 1)
        private val symbols: IntArray

        init {
            for (length in lengths) if (length > 0) countOfLength[length]++
            val offsets = IntArray(MAX_BITS + 2)
            for (length in 1..MAX_BITS) offsets[length + 1] = offsets[length] + countOfLength[length]
            symbols = IntArray(lengths.count { it > 0 })
            for ((symbol, length) in lengths.withIndex()) {
                if (length > 0) symbols[offsets[length]++] = symbol
            }
        }

        fun decode(input: BitReader): Int {
            var code = 0
            var first = 0
            var index = 0
            for (length in 1..MAX_BITS) {
                code = code or input.bits(1)
                val count = countOfLength[length]
                if (code - first < count) return symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            error("a Huffman code longer than $MAX_BITS bits; this stream is not DEFLATE")
        }
    }

    /** LSB-first bit reader, which is the order DEFLATE packs its codes in. */
    private class BitReader(
        private val data: ByteArray,
        private var at: Int,
        private val end: Int,
    ) {
        private var bitBuffer = 0
        private var bitCount = 0

        fun bits(count: Int): Int {
            var value = 0
            for (i in 0 until count) {
                if (bitCount == 0) {
                    require(at < end) { "the deflate stream ended in the middle of a code" }
                    bitBuffer = data[at++].toInt() and 0xFF
                    bitCount = 8
                }
                value = value or ((bitBuffer and 1) shl i)
                bitBuffer = bitBuffer shr 1
                bitCount--
            }
            return value
        }

        fun alignToByte() {
            bitBuffer = 0
            bitCount = 0
        }

        fun byte(): Byte {
            require(at < end) { "the deflate stream ended inside a stored block" }
            return data[at++]
        }
    }
}
