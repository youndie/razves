package io.github.youndie.razves.read

/**
 * An ELF64 reader: the section header table, `.symtab` and its string table. Nothing else.
 *
 * There is no subprocess here, and that is the decision the whole project rests on. The brief
 * assumed `llvm-nm`, `llvm-size` and `llvm-objdump` came with Kotlin/Native and were therefore
 * free. They do not: the toolchain downloads an LLVM distribution named `…-essentials-`, whose
 * `bin/` holds `clang`, `lld`, `llvm-ar`, `llvm-cov` and `llvm-profdata` and no binary reader at
 * all. Wrapping tools that are present on a developer's mac because Xcode installed them buys a
 * tool that fails on CI — see docs/research/research-architecture.md §1.1.
 *
 * Deliberately not read: relocations, program headers beyond their size, DWARF, `.dynsym`. The
 * dynamic symbol table describes what the binary imports and exports, not what it contains, and
 * its entries duplicate `.symtab` addresses without adding an owner.
 */
public object ElfReader {
    private const val EI_CLASS = 4
    private const val EI_DATA = 5
    private const val ELFCLASS64 = 2
    private const val ELFDATA2LSB = 1
    private const val ELFDATA2MSB = 2

    private const val SHT_SYMTAB = 2L
    private const val SHT_NOBITS = 8L
    private const val SHF_ALLOC = 0x2L

    private const val SHN_UNDEF = 0
    private const val SHN_LORESERVE = 0xFF00

    private const val STT_SECTION = 3
    private const val STT_FILE = 4

    private const val SECTION_HEADER_SIZE = 64
    private const val SYMBOL_ENTRY_SIZE = 24

    public val MAGIC: ByteArray = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    public fun matches(data: ByteArray): Boolean = data.size >= 4 && MAGIC.indices.all { data[it] == MAGIC[it] }

    public fun read(
        data: ByteArray,
        name: String,
    ): BinaryImage {
        require(matches(data)) { "$name is not an ELF file" }
        require(data[EI_CLASS].toInt() == ELFCLASS64) {
            "$name is not ELF64 (EI_CLASS=${data[EI_CLASS].toInt()}); razves reads 64-bit objects only"
        }
        val endian = data[EI_DATA].toInt()
        require(endian == ELFDATA2LSB || endian == ELFDATA2MSB) { "$name has an unknown EI_DATA of $endian" }
        val b = Bytes(data, littleEndian = endian == ELFDATA2LSB)

        val phoff = b.u64(0x20)
        val shoff = b.u64(0x28)
        val ehsize = b.u16(0x34).toLong()
        val phentsize = b.u16(0x36).toLong()
        val phnum = b.u16(0x38).toLong()
        val shentsize = b.u16(0x3A)
        val shnum = b.u16(0x3C)
        val shstrndx = b.u16(0x3E)

        require(shentsize == SECTION_HEADER_SIZE) {
            "$name has a section header size of $shentsize, not $SECTION_HEADER_SIZE"
        }
        require(shnum > 0) { "$name has no section header table; razves cannot attribute a section-less object" }

        val headers = (0 until shnum).map { readSectionHeader(b, (shoff + it * SECTION_HEADER_SIZE).toInt()) }
        val shstrtabOffset = headers[shstrndx].offset.toInt()
        val sections =
            headers.map { h ->
                Section(
                    segment = null,
                    name = b.cString(shstrtabOffset + h.nameOffset.toInt()),
                    address = h.addr,
                    size = h.size,
                    fileOffset = h.offset,
                    alignment = maxOf(h.addralign, 1),
                    kind = kindOf(h),
                )
            }

        // Read from the header, not inferred: the file-size identity in Reconciliation is a check
        // rather than a definition only because these three come with their own offsets and sizes.
        val containerRegions =
            buildList {
                add(FileRegion("ELF header", 0, ehsize, 1))
                if (phoff != 0L && phnum > 0) add(FileRegion("program headers", phoff, phentsize * phnum, 8))
                add(FileRegion("section headers", shoff, shentsize.toLong() * shnum, 8))
            }

        val symtabIndex = headers.indexOfFirst { it.type == SHT_SYMTAB }
        val symbols = if (symtabIndex < 0) emptyList() else readSymbols(b, headers, symtabIndex, shnum)

        return BinaryImage(
            name = name,
            format = BinaryFormat.ELF64,
            sizeAlgorithm = SizeAlgorithm.RECORDED,
            fileSize = data.size.toLong(),
            containerRegions = containerRegions,
            sections = sections,
            symbols = symbols,
            hasSymbolTable = symtabIndex >= 0,
            // The section header table lists every byte-bearing region of an ELF file, so a byte
            // belonging to nothing is a defect in this reader rather than a property of the binary.
            coversEveryFileByte = true,
            targets = Targets.ofElf(b.u16(18)),
        )
    }

    private class SectionHeader(
        val nameOffset: Long,
        val type: Long,
        val flags: Long,
        val addr: Long,
        val offset: Long,
        val size: Long,
        val link: Long,
        val addralign: Long,
    )

    private fun readSectionHeader(
        b: Bytes,
        at: Int,
    ) = SectionHeader(
        nameOffset = b.u32(at),
        type = b.u32(at + 4),
        flags = b.u64(at + 8),
        addr = b.u64(at + 16),
        offset = b.u64(at + 24),
        size = b.u64(at + 32),
        link = b.u32(at + 40),
        addralign = b.u64(at + 48),
    )

    private fun kindOf(h: SectionHeader): SectionKind =
        when {
            h.flags and SHF_ALLOC == 0L -> SectionKind.METADATA
            h.type == SHT_NOBITS -> SectionKind.ALLOCATED_NOBITS
            else -> SectionKind.ALLOCATED
        }

    private fun readSymbols(
        b: Bytes,
        headers: List<SectionHeader>,
        symtabIndex: Int,
        shnum: Int,
    ): List<Symbol> {
        val symtab = headers[symtabIndex]
        require(symtab.size % SYMBOL_ENTRY_SIZE == 0L) {
            "the symbol table is ${symtab.size} bytes, which is not a whole number of $SYMBOL_ENTRY_SIZE-byte entries"
        }
        val strtabOffset = headers[symtab.link.toInt()].offset.toInt()
        val count = (symtab.size / SYMBOL_ENTRY_SIZE).toInt()
        val out = ArrayList<Symbol>(count)
        for (i in 0 until count) {
            val at = (symtab.offset + i.toLong() * SYMBOL_ENTRY_SIZE).toInt()
            val info = b.u8(at + 4)
            val shndx = b.u16(at + 6)
            val type = info and 0xF
            // Undefined symbols own nothing. SHN_ABS and above are not sections. STT_SECTION and
            // STT_FILE are labels for a section and for a source file name; counting a STT_SECTION
            // entry would charge the whole section to itself and double every byte in it.
            if (shndx == SHN_UNDEF || shndx >= SHN_LORESERVE || shndx >= shnum) continue
            if (type == STT_SECTION || type == STT_FILE) continue
            val size = b.u64(at + 16)
            if (size == 0L) continue
            out +=
                Symbol(
                    name = b.cString(strtabOffset + b.u32(at).toInt()),
                    address = b.u64(at + 8),
                    size = size,
                    sectionIndex = shndx,
                )
        }
        return out
    }
}
