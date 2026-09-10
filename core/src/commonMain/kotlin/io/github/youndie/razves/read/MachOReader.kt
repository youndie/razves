package io.github.youndie.razves.read

/**
 * A Mach-O 64-bit reader: segments, their sections, the symbol table, and the link-edit tables that
 * a Mach-O keeps outside any section.
 *
 * **Symbol sizes do not exist in this format.** `llvm-nm --print-size` on a Mach-O binary warns
 * `sizes with --print-size for Mach-O files are always zero`, and it is telling the truth: `nlist_64`
 * has no size field. A symbol's size here is the distance to the next symbol in the same section,
 * clamped at the section's end — which means it silently includes whatever alignment padding follows
 * it. That is why [BinaryImage.sizeAlgorithm] exists and why the diff refuses to compare an
 * address-derived report against a recorded-size one.
 *
 * **Sections are keyed by segment and name.** A real Kotlin/Native binary carries two sections
 * called `__const`, one in `__TEXT` and one in `__DATA_CONST`. A map keyed by name loses one of them,
 * and loses it only on Apple targets.
 *
 * **`__LINKEDIT` is modelled as metadata, not as program content.** It is mapped into memory, so
 * "allocated" would be the letter of the format, but what is in it — the symbol table, the string
 * table, the chained-fixup and export data, the code signature — is the same role ELF gives to its
 * non-allocated sections, and the same thing `strip` removes. razves classifies by role so that a
 * Mach-O report and an ELF report answer the same question.
 */
public object MachOReader {
    private const val MH_MAGIC_64 = 0xFEEDFACFu
    private const val MH_CIGAM_64 = 0xCFFAEDFEu

    private const val LC_SEGMENT_64 = 0x19
    private const val LC_SYMTAB = 0x2
    private const val LC_DYSYMTAB = 0xB
    private const val LC_DYLD_INFO = 0x22
    private const val LC_DYLD_INFO_ONLY = -0x7FFFFFDE // 0x80000022
    private const val LC_DYLD_EXPORTS_TRIE = -0x7FFFFFCD // 0x80000033
    private const val LC_DYLD_CHAINED_FIXUPS = -0x7FFFFFCC // 0x80000034
    private const val LC_FUNCTION_STARTS = 0x26
    private const val LC_DATA_IN_CODE = 0x29
    private const val LC_CODE_SIGNATURE = 0x1D

    private const val S_ZEROFILL = 0x1
    private const val S_GB_ZEROFILL = 0xC
    private const val S_THREAD_LOCAL_ZEROFILL = 0x12

    private const val N_STAB = 0xE0
    private const val N_TYPE = 0x0E
    private const val N_SECT = 0xE

    private const val HEADER_SIZE = 32
    private const val SECTION_SIZE = 80
    private const val NLIST_SIZE = 16

    /**
     * A segment's file offset is page-aligned, and the largest page Apple uses is 16 KiB, so the gap
     * in front of the first region of a segment cannot exceed that. Measured on a real
     * `macosArm64` binary: the three segment-boundary gaps are 8,768, 5,720 and 15,936 bytes, all
     * under the bound and all of them padding rather than anything lost.
     */
    private const val PAGE_ALIGNMENT = 16_384L

    public fun matches(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val le = Bytes(data, littleEndian = true).u32(0).toULong().toUInt()
        return le == MH_MAGIC_64 || le == MH_CIGAM_64
    }

    public fun read(
        data: ByteArray,
        name: String,
    ): BinaryImage {
        require(matches(data)) { "$name is not a 64-bit Mach-O file" }
        val littleEndian = Bytes(data, littleEndian = true).u32(0).toULong().toUInt() == MH_MAGIC_64
        val b = Bytes(data, littleEndian)

        val ncmds = b.u32(16).toInt()
        val sizeOfCmds = b.u32(20)

        val sections = mutableListOf<Section>()
        val metadata = mutableListOf<Section>()
        val segmentStarts = mutableListOf<Long>()
        var symtab: SymtabCommand? = null

        var at = HEADER_SIZE
        repeat(ncmds) {
            val cmd = b.u32(at).toInt()
            val cmdSize = b.u32(at + 4).toInt()
            require(cmdSize > 0) { "$name has a load command of size $cmdSize at offset $at" }
            when (cmd) {
                LC_SEGMENT_64 -> readSegment(b, at, sections, segmentStarts)
                LC_SYMTAB -> symtab = readSymtab(b, at, metadata)
                LC_DYSYMTAB -> readDysymtab(b, at, metadata)
                LC_DYLD_INFO, LC_DYLD_INFO_ONLY -> readDyldInfo(b, at, metadata)
                LC_DYLD_EXPORTS_TRIE -> linkEditData(b, at, "dyld export trie", metadata)
                LC_DYLD_CHAINED_FIXUPS -> linkEditData(b, at, "dyld chained fixups", metadata)
                LC_FUNCTION_STARTS -> linkEditData(b, at, "function starts", metadata)
                LC_DATA_IN_CODE -> linkEditData(b, at, "data in code", metadata)
                LC_CODE_SIGNATURE -> linkEditData(b, at, "code signature", metadata)
                else -> Unit
            }
            at += cmdSize
        }

        val all = sections + metadata
        return BinaryImage(
            name = name,
            format = BinaryFormat.MACHO64,
            sizeAlgorithm = SizeAlgorithm.ADDRESS_DELTA,
            fileSize = data.size.toLong(),
            containerRegions =
                listOf(
                    FileRegion("Mach-O header and load commands", 0, HEADER_SIZE + sizeOfCmds, 1),
                ),
            sections = raiseSegmentStartAlignment(all, segmentStarts),
            symbols = symtab?.let { readSymbols(b, it, sections) }.orEmpty(),
            hasSymbolTable = symtab != null,
            // Mach-O's link-edit area is described by load commands rather than by a table of every
            // region, and razves does not parse all of them. A byte no named region claims is
            // reported as unparsed rather than treated as a defect — see BinaryImage.
            coversEveryFileByte = false,
        )
    }

    /**
     * A segment's first region inherits the page alignment that put the segment where it is.
     *
     * Without this the gap in front of the first section of `__DATA_CONST` — 8,768 bytes on the
     * measured subject — is larger than that section's own 16-byte alignment, and the coverage walk
     * reports as unparsed something that is plainly padding.
     */
    private fun raiseSegmentStartAlignment(
        sections: List<Section>,
        segmentStarts: List<Long>,
    ): List<Section> {
        if (segmentStarts.isEmpty()) return sections
        val firstInSegment =
            sections
                .filter { it.fileBytes > 0 }
                .groupBy { section -> segmentStarts.filter { it <= section.fileOffset }.maxOrNull() }
                .values
                .mapNotNull { group -> group.minByOrNull { it.fileOffset } }
                .toSet()
        return sections.map { if (it in firstInSegment) it.copy(alignment = PAGE_ALIGNMENT) else it }
    }

    private fun readSegment(
        b: Bytes,
        at: Int,
        into: MutableList<Section>,
        segmentStarts: MutableList<Long>,
    ) {
        val segmentName = b.cString16(at + 8)
        val fileOffset = b.u64(at + 40)
        val fileSize = b.u64(at + 48)
        val sectionCount = b.u32(at + 64).toInt()
        if (fileSize > 0) segmentStarts += fileOffset
        for (i in 0 until sectionCount) {
            val s = at + 72 + i * SECTION_SIZE
            val flags = b.u32(s + 64).toInt()
            val zeroFill = (flags and 0xFF) in setOf(S_ZEROFILL, S_GB_ZEROFILL, S_THREAD_LOCAL_ZEROFILL)
            into +=
                Section(
                    segment = b.cString16(s + 16).ifEmpty { segmentName },
                    name = b.cString16(s),
                    address = b.u64(s + 32),
                    size = b.u64(s + 40),
                    fileOffset = b.u32(s + 48),
                    // Mach-O records the alignment as a power of two rather than as the value itself.
                    alignment = 1L shl b.u32(s + 52).toInt(),
                    kind = if (zeroFill) SectionKind.ALLOCATED_NOBITS else SectionKind.ALLOCATED,
                )
        }
    }

    private class SymtabCommand(
        val symOffset: Long,
        val symbolCount: Int,
        val stringOffset: Long,
    )

    private fun readSymtab(
        b: Bytes,
        at: Int,
        metadata: MutableList<Section>,
    ): SymtabCommand {
        val symOffset = b.u32(at + 8)
        val symbolCount = b.u32(at + 12).toInt()
        val stringOffset = b.u32(at + 16)
        val stringSize = b.u32(at + 20)
        metadata += linkEditSection("symbol table", symOffset, symbolCount.toLong() * NLIST_SIZE, 8)
        metadata += linkEditSection("string table", stringOffset, stringSize, 1)
        return SymtabCommand(symOffset, symbolCount, stringOffset)
    }

    private fun readDysymtab(
        b: Bytes,
        at: Int,
        metadata: MutableList<Section>,
    ) {
        val indirectOffset = b.u32(at + 8 + 12 * 4)
        val indirectCount = b.u32(at + 8 + 13 * 4)
        if (indirectCount > 0) {
            metadata += linkEditSection("indirect symbols", indirectOffset, indirectCount * 4, 4)
        }
    }

    private fun readDyldInfo(
        b: Bytes,
        at: Int,
        metadata: MutableList<Section>,
    ) {
        val parts = listOf("dyld rebase", "dyld bind", "dyld weak bind", "dyld lazy bind", "dyld export trie")
        parts.forEachIndexed { i, label ->
            val offset = b.u32(at + 8 + i * 8)
            val size = b.u32(at + 12 + i * 8)
            if (size > 0) metadata += linkEditSection(label, offset, size, 1)
        }
    }

    private fun linkEditData(
        b: Bytes,
        at: Int,
        label: String,
        metadata: MutableList<Section>,
    ) {
        val offset = b.u32(at + 8)
        val size = b.u32(at + 12)
        if (size > 0) metadata += linkEditSection(label, offset, size, 1)
    }

    private fun linkEditSection(
        label: String,
        offset: Long,
        size: Long,
        alignment: Long,
    ) = Section(
        segment = "__LINKEDIT",
        name = label,
        address = 0,
        size = size,
        fileOffset = offset,
        alignment = alignment,
        kind = SectionKind.METADATA,
    )

    /**
     * Sizes by address delta, because the format records none.
     *
     * Symbols that share an address — an alias and its definition — are given the same end, so they
     * overlap and the attribution sweep charges the bytes once. Doing it the other way round, giving
     * the first of them a size of zero, would make which symbol owns the bytes depend on the order
     * the symbol table happens to be in.
     */
    private fun readSymbols(
        b: Bytes,
        symtab: SymtabCommand,
        sections: List<Section>,
    ): List<Symbol> {
        val defined = mutableListOf<Symbol>()
        for (i in 0 until symtab.symbolCount) {
            val at = (symtab.symOffset + i.toLong() * NLIST_SIZE).toInt()
            val type = b.u8(at + 4)
            if (type and N_STAB != 0) continue // a debugger entry, not a definition
            if (type and N_TYPE != N_SECT) continue
            val sectionOrdinal = b.u8(at + 5)
            if (sectionOrdinal == 0 || sectionOrdinal > sections.size) continue
            defined +=
                Symbol(
                    name = b.cString(symtab.stringOffset.toInt() + b.u32(at).toInt()),
                    address = b.u64(at + 8),
                    size = 0,
                    sectionIndex = sectionOrdinal - 1,
                )
        }

        return defined
            .groupBy { it.sectionIndex }
            .flatMap { (index, group) -> sizeByAddressDelta(group, sections[index]) }
    }

    private fun sizeByAddressDelta(
        group: List<Symbol>,
        section: Section,
    ): List<Symbol> {
        val sorted = group.sortedBy { it.address }
        val sectionEnd = section.address + section.size
        val addresses = sorted.map { it.address }
        return sorted
            .mapIndexed { i, symbol ->
                var next = i + 1
                while (next < addresses.size && addresses[next] == symbol.address) next++
                val end = if (next < addresses.size) minOf(addresses[next], sectionEnd) else sectionEnd
                symbol.copy(size = maxOf(0, end - symbol.address))
            }.filter { it.size > 0 }
    }
}
