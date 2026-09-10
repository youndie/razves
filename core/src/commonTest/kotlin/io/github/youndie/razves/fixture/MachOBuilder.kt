package io.github.youndie.razves.fixture

/**
 * Builds 64-bit Mach-O files in memory.
 *
 * Smaller than [ElfBuilder] and deliberately so: what needs a deterministic fixture on this side is
 * narrow — that a symbol's size is the distance to the next one, that two sections named `__const`
 * in different segments stay two sections, and that the link-edit tables are found through their
 * load commands. Everything else about the format is checked against a binary the Kotlin/Native
 * compiler produced, because a fixture written from the same reading as the reader cannot catch a
 * misreading.
 */
public class MachOBuilder {
    public class SectionSpec(
        public val segment: String,
        public val name: String,
        public val address: Long,
        public val size: Int,
        /** Log2, as Mach-O records it. */
        public val alignPower: Int = 3,
        public val zeroFill: Boolean = false,
    )

    public class SymbolSpec(
        public val name: String,
        public val address: Long,
        /** One-based, as `n_sect` counts. */
        public val sectionOrdinal: Int,
    )

    public companion object {
        public const val HEADER_SIZE: Int = 32
        public const val SECTION_SIZE: Int = 80
        public const val SEGMENT_COMMAND_SIZE: Int = 72
        public const val SYMTAB_COMMAND_SIZE: Int = 24
        public const val NLIST_SIZE: Int = 16
        public const val LC_SEGMENT_64: Int = 0x19
        public const val LC_SYMTAB: Int = 0x2
        public const val N_SECT: Int = 0xE
        public const val SEGMENT_ALIGNMENT: Int = 16_384
    }

    private val sections = mutableListOf<SectionSpec>()
    private val symbols = mutableListOf<SymbolSpec>()
    private var withSymbolTable = true

    /** Ordinal the next added section will have, so a test can wire a symbol to it before adding it. */
    public val nextSectionOrdinal: Int get() = sections.size + 1

    public fun section(spec: SectionSpec): MachOBuilder = apply { sections += spec }

    public fun symbol(spec: SymbolSpec): MachOBuilder = apply { symbols += spec }

    public fun stripped(): MachOBuilder = apply { withSymbolTable = false }

    public fun build(): ByteArray {
        // One segment per distinct segment name, in first-appearance order, because that is the order
        // `n_sect` counts sections in.
        val segments = sections.map { it.segment }.distinct()
        // A real Mach-O keeps its symbol and string tables in a `__LINKEDIT` segment that carries no
        // sections at all. The fixture does the same, because the reader's page bound is what
        // explains the gap in front of a segment — without the segment, the tables look like bytes
        // razves failed to account for.
        val commandCount = segments.size + if (withSymbolTable) 2 else 0
        val sizeOfCommands =
            segments.sumOf { seg ->
                SEGMENT_COMMAND_SIZE + SECTION_SIZE * sections.count { it.segment == seg }
            } + if (withSymbolTable) SYMTAB_COMMAND_SIZE + SEGMENT_COMMAND_SIZE else 0

        // Every segment starts on a page, which is what the reader's page bound is there to explain.
        var cursor = align(HEADER_SIZE + sizeOfCommands, SEGMENT_ALIGNMENT)
        val sectionOffsets = mutableMapOf<SectionSpec, Int>()
        val segmentRanges = mutableMapOf<String, Pair<Int, Int>>()
        for (seg in segments) {
            val start = cursor
            for (s in sections.filter { it.segment == seg }) {
                if (s.zeroFill) {
                    sectionOffsets[s] = cursor
                } else {
                    cursor = align(cursor, 1 shl s.alignPower)
                    sectionOffsets[s] = cursor
                    cursor += s.size
                }
            }
            cursor = align(cursor, SEGMENT_ALIGNMENT)
            segmentRanges[seg] = start to (cursor - start)
        }

        val stringTable = StringTable()
        val symbolBytes = if (withSymbolTable) encodeSymbols(stringTable) else ByteArray(0)
        val symbolOffset = cursor
        cursor += symbolBytes.size
        val stringBytes = stringTable.bytes()
        val stringOffset = cursor
        cursor += stringBytes.size

        val out = ByteArray(cursor)
        writeHeader(out, commandCount, sizeOfCommands)

        var at = HEADER_SIZE
        for (seg in segments) {
            val members = sections.filter { it.segment == seg }
            val (start, length) = segmentRanges.getValue(seg)
            u32(out, at, LC_SEGMENT_64.toLong())
            u32(out, at + 4, (SEGMENT_COMMAND_SIZE + SECTION_SIZE * members.size).toLong())
            name16(out, at + 8, seg)
            u64(out, at + 24, members.first().address) // vmaddr
            u64(out, at + 32, length.toLong()) // vmsize
            u64(out, at + 40, start.toLong())
            u64(out, at + 48, length.toLong())
            u32(out, at + 64, members.size.toLong())
            members.forEachIndexed { i, s ->
                val so = at + SEGMENT_COMMAND_SIZE + i * SECTION_SIZE
                name16(out, so, s.name)
                name16(out, so + 16, s.segment)
                u64(out, so + 32, s.address)
                u64(out, so + 40, s.size.toLong())
                u32(out, so + 48, sectionOffsets.getValue(s).toLong())
                u32(out, so + 52, s.alignPower.toLong())
                u32(out, so + 64, if (s.zeroFill) 0x1 else 0x0)
            }
            at += SEGMENT_COMMAND_SIZE + SECTION_SIZE * members.size
        }
        if (withSymbolTable) {
            u32(out, at, LC_SEGMENT_64.toLong())
            u32(out, at + 4, SEGMENT_COMMAND_SIZE.toLong())
            name16(out, at + 8, "__LINKEDIT")
            u64(out, at + 24, 0x1_0000_0000L)
            u64(out, at + 32, (cursor - symbolOffset).toLong())
            u64(out, at + 40, symbolOffset.toLong())
            u64(out, at + 48, (cursor - symbolOffset).toLong())
            u32(out, at + 64, 0)
            at += SEGMENT_COMMAND_SIZE

            u32(out, at, LC_SYMTAB.toLong())
            u32(out, at + 4, SYMTAB_COMMAND_SIZE.toLong())
            u32(out, at + 8, symbolOffset.toLong())
            u32(out, at + 12, symbols.size.toLong())
            u32(out, at + 16, stringOffset.toLong())
            u32(out, at + 20, stringBytes.size.toLong())
            symbolBytes.copyInto(out, symbolOffset)
            stringBytes.copyInto(out, stringOffset)
        }
        sections.filterNot { it.zeroFill }.forEach { s ->
            ByteArray(s.size) { 0x1F }.copyInto(out, sectionOffsets.getValue(s))
        }
        return out
    }

    private fun encodeSymbols(strings: StringTable): ByteArray {
        val out = ByteArray(NLIST_SIZE * symbols.size)
        symbols.forEachIndexed { i, s ->
            val at = NLIST_SIZE * i
            u32(out, at, strings.offsetOf(s.name).toLong())
            out[at + 4] = N_SECT.toByte()
            out[at + 5] = s.sectionOrdinal.toByte()
            u64(out, at + 8, s.address)
        }
        return out
    }

    private fun writeHeader(
        out: ByteArray,
        commandCount: Int,
        sizeOfCommands: Int,
    ) {
        // MH_MAGIC_64, little-endian on disk.
        out[0] = 0xCF.toByte()
        out[1] = 0xFA.toByte()
        out[2] = 0xED.toByte()
        out[3] = 0xFE.toByte()
        u32(out, 4, 0x0100_000CL) // CPU_TYPE_ARM64
        u32(out, 8, 0)
        u32(out, 12, 2) // MH_EXECUTE
        u32(out, 16, commandCount.toLong())
        u32(out, 20, sizeOfCommands.toLong())
    }

    private class StringTable {
        private val data = mutableListOf<Byte>(0.toByte())
        private val known = mutableMapOf("" to 0)

        fun offsetOf(s: String): Int =
            known.getOrPut(s) {
                val at = data.size
                s.encodeToByteArray().forEach { data += it }
                data += 0
                at
            }

        fun bytes(): ByteArray = data.toByteArray()
    }
}

private fun align(
    value: Int,
    to: Int,
): Int = if (value % to == 0) value else value + (to - value % to)

private fun name16(
    out: ByteArray,
    at: Int,
    name: String,
) {
    val bytes = name.encodeToByteArray()
    require(bytes.size <= 16) { "a Mach-O segment or section name is at most 16 bytes: $name" }
    bytes.copyInto(out, at)
}

private fun u32(
    out: ByteArray,
    at: Int,
    v: Long,
) {
    for (i in 0 until 4) out[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
}

private fun u64(
    out: ByteArray,
    at: Int,
    v: Long,
) {
    for (i in 0 until 8) out[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
}
