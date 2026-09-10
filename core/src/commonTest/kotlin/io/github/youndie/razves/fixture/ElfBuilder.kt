package io.github.youndie.razves.fixture

/**
 * Builds ELF64 files in memory, byte by byte, so a reader test states the expected answer rather
 * than discovering it.
 *
 * This is the fixture the reader tests need and it is deliberately *not* the fixture B-04 asks for.
 * Both are needed and they check different things: a hand-built ELF says "given exactly these bytes,
 * the reader must produce exactly this", which a compiler cannot be asked to guarantee; a compiled
 * binary says "given real Kotlin, the attribution lands in the right package", which no hand-built
 * fixture can demonstrate.
 */
public class ElfBuilder {
    public class SectionSpec(
        public val name: String,
        public val type: Long,
        public val flags: Long,
        public val address: Long,
        public val content: ByteArray,
        /** Set for NOBITS: the section has this size but contributes no file bytes. */
        public val declaredSize: Long? = null,
    )

    public class SymbolSpec(
        public val name: String,
        public val address: Long,
        public val size: Long,
        public val sectionIndex: Int,
        public val type: Int = STT_FUNC,
    )

    public companion object {
        public const val SHT_PROGBITS: Long = 1
        public const val SHT_SYMTAB: Long = 2
        public const val SHT_STRTAB: Long = 3
        public const val SHT_NOBITS: Long = 8
        public const val SHF_ALLOC: Long = 0x2
        public const val SHF_EXEC: Long = 0x4
        public const val STT_FUNC: Int = 2
        public const val STT_OBJECT: Int = 1
        public const val STT_SECTION: Int = 3
        public const val HEADER_SIZE: Int = 64
        public const val SECTION_HEADER_SIZE: Int = 64
        public const val SYMBOL_ENTRY_SIZE: Int = 24
        public const val CONTENT_ALIGNMENT: Int = 8
    }

    private val sections = mutableListOf<SectionSpec>()
    private val symbols = mutableListOf<SymbolSpec>()
    private var withSymbolTable = true

    /** Index the next added section will have, so a test can wire a symbol to it before adding it. */
    public val nextSectionIndex: Int get() = sections.size + 1

    public fun section(spec: SectionSpec): ElfBuilder = apply { sections += spec }

    public fun text(
        name: String = ".text",
        address: Long,
        size: Int,
    ): ElfBuilder =
        section(SectionSpec(name, SHT_PROGBITS, SHF_ALLOC or SHF_EXEC, address, ByteArray(size) { 0x90.toByte() }))

    public fun nobits(
        name: String,
        address: Long,
        size: Long,
    ): ElfBuilder = section(SectionSpec(name, SHT_NOBITS, SHF_ALLOC, address, ByteArray(0), declaredSize = size))

    public fun notAllocated(
        name: String,
        size: Int,
    ): ElfBuilder = section(SectionSpec(name, SHT_PROGBITS, 0, 0, ByteArray(size)))

    public fun symbol(spec: SymbolSpec): ElfBuilder = apply { symbols += spec }

    public fun stripped(): ElfBuilder = apply { withSymbolTable = false }

    public fun build(): ByteArray {
        // Index 0 is the mandatory null section; the caller's sections follow it, then the tables
        // this builder adds itself.
        val all = mutableListOf(SectionSpec("", 0, 0, 0, ByteArray(0)))
        all += sections

        val strtab = StringTable()
        val symtabBytes = if (withSymbolTable) encodeSymbols(strtab) else ByteArray(0)
        val symtabIndex = all.size
        if (withSymbolTable) {
            all += SectionSpec(".symtab", SHT_SYMTAB, 0, 0, symtabBytes)
            all += SectionSpec(".strtab", SHT_STRTAB, 0, 0, strtab.bytes())
        }
        val shstrtab = StringTable()
        all.forEach { shstrtab.offsetOf(it.name) }
        all += SectionSpec(".shstrtab", SHT_STRTAB, 0, 0, shstrtab.bytes())
        val shstrndx = all.size - 1

        // Contents after the ELF header, section header table last.
        var cursor = HEADER_SIZE
        val offsets = IntArray(all.size)
        all.forEachIndexed { i, s ->
            if (i == 0 || s.type == SHT_NOBITS) {
                offsets[i] = cursor
            } else {
                cursor = align(cursor, CONTENT_ALIGNMENT)
                offsets[i] = cursor
                cursor += s.content.size
            }
        }
        val shoff = align(cursor, CONTENT_ALIGNMENT)
        val out = ByteArray(shoff + SECTION_HEADER_SIZE * all.size)

        writeHeader(out, shoff, all.size, shstrndx)
        all.forEachIndexed { i, s -> if (s.type != SHT_NOBITS) s.content.copyInto(out, offsets[i]) }
        all.forEachIndexed { i, s ->
            writeSectionHeader(
                out,
                at = shoff + i * SECTION_HEADER_SIZE,
                nameOffset = shstrtab.offsetOf(s.name),
                type = s.type,
                flags = s.flags,
                address = s.address,
                offset = offsets[i].toLong(),
                size = s.declaredSize ?: s.content.size.toLong(),
                link = if (s.type == SHT_SYMTAB) (symtabIndex + 1).toLong() else 0,
            )
        }
        return out
    }

    private fun encodeSymbols(strtab: StringTable): ByteArray {
        // Entry 0 of a symbol table is reserved and always null.
        val out = ByteArray(SYMBOL_ENTRY_SIZE * (symbols.size + 1))
        symbols.forEachIndexed { i, s ->
            val at = SYMBOL_ENTRY_SIZE * (i + 1)
            u32(out, at, strtab.offsetOf(s.name).toLong())
            out[at + 4] = ((1 shl 4) or s.type).toByte() // STB_GLOBAL in the high nibble
            u16(out, at + 6, s.sectionIndex)
            u64(out, at + 8, s.address)
            u64(out, at + 16, s.size)
        }
        return out
    }

    private fun writeHeader(
        out: ByteArray,
        shoff: Int,
        shnum: Int,
        shstrndx: Int,
    ) {
        out[0] = 0x7F
        out[1] = 'E'.code.toByte()
        out[2] = 'L'.code.toByte()
        out[3] = 'F'.code.toByte()
        out[4] = 2 // ELFCLASS64
        out[5] = 1 // ELFDATA2LSB
        out[6] = 1 // EV_CURRENT
        u16(out, 16, 2) // ET_EXEC
        u16(out, 18, 0x3E) // EM_X86_64
        u32(out, 20, 1)
        u64(out, 32, 0) // e_phoff: a fixture carries no program headers
        u64(out, 40, shoff.toLong())
        u16(out, 52, HEADER_SIZE)
        u16(out, 54, 56)
        u16(out, 56, 0)
        u16(out, 58, SECTION_HEADER_SIZE)
        u16(out, 60, shnum)
        u16(out, 62, shstrndx)
    }

    @Suppress("LongParameterList")
    private fun writeSectionHeader(
        out: ByteArray,
        at: Int,
        nameOffset: Int,
        type: Long,
        flags: Long,
        address: Long,
        offset: Long,
        size: Long,
        link: Long,
    ) {
        u32(out, at, nameOffset.toLong())
        u32(out, at + 4, type)
        u64(out, at + 8, flags)
        u64(out, at + 16, address)
        u64(out, at + 24, offset)
        u64(out, at + 32, size)
        u32(out, at + 40, link)
        // The alignment the layout above actually uses. It has to be the truth rather than 1: the
        // coverage check in Reconciliation holds every gap against the alignment of the region that
        // follows it, and a fixture that understates its own alignment fails that check honestly.
        u64(out, at + 48, CONTENT_ALIGNMENT.toLong())
        u64(out, at + 56, if (type == SHT_SYMTAB) SYMBOL_ENTRY_SIZE.toLong() else 0)
    }

    /** NUL-terminated names, entry 0 always the empty string, exactly as ELF wants them. */
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

private fun u16(
    out: ByteArray,
    at: Int,
    v: Int,
) {
    out[at] = (v and 0xFF).toByte()
    out[at + 1] = ((v shr 8) and 0xFF).toByte()
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
