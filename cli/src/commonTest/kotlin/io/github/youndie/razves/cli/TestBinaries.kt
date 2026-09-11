package io.github.youndie.razves.cli

/**
 * Minimal binaries of each format, assembled here rather than shared from `core`.
 *
 * `core`'s builders live in its own test source set and are not published, and a test fixture is not
 * worth an artifact. What this needs is two files with the right magic bytes and enough structure to
 * be read.
 *
 * Both carry a symbol table, and that is not decoration: the first version of these fixtures had
 * none, and razves refused them as stripped - correctly, and exactly as B-15 intends. A fixture a
 * tool is right to reject is not a fixture.
 */
internal object TestBinaries {
    private const val HEADER_SIZE = 64
    private const val SECTION_HEADER_SIZE = 64
    private const val SYMBOL_ENTRY_SIZE = 24
    private const val NLIST_SIZE = 16

    fun elf(): ByteArray {
        // An ELF string table is NUL-separated and starts with an empty name, so it is assembled as
        // bytes rather than written as text.
        val names = mutableListOf<Byte>(0)
        val textName = names.size
        ".text".encodeToByteArray().forEach { names += it }
        names += 0
        val stringTableName = names.size
        ".shstrtab".encodeToByteArray().forEach { names += it }
        names += 0
        val symbolTableName = names.size
        ".symtab".encodeToByteArray().forEach { names += it }
        names += 0
        val symbolNamesName = names.size
        ".strtab".encodeToByteArray().forEach { names += it }
        names += 0
        val sectionNameBytes = names.toByteArray()

        // One Kotlin symbol, so the report has something to attribute and the refusal of a stripped
        // binary does not fire on a fixture that is merely small.
        val symbolNames = mutableListOf<Byte>(0)
        "kfun:sample.app#run(){}".encodeToByteArray().forEach { symbolNames += it }
        symbolNames += 0
        val symbolNameBytes = symbolNames.toByteArray()
        val symbols = ByteArray(SYMBOL_ENTRY_SIZE * 2)
        u32(symbols, SYMBOL_ENTRY_SIZE, 1)
        symbols[SYMBOL_ENTRY_SIZE + 4] = ((1 shl 4) or 2).toByte() // STB_GLOBAL, STT_FUNC
        u16(symbols, SYMBOL_ENTRY_SIZE + 6, 1) // the .text section
        u64(symbols, SYMBOL_ENTRY_SIZE + 8, 0x1000)
        u64(symbols, SYMBOL_ENTRY_SIZE + 16, 64)

        val sectionCount = 5
        val textSize = 64
        val textOffset = HEADER_SIZE
        val namesOffset = textOffset + textSize
        val symbolsOffset = align(namesOffset + sectionNameBytes.size, 8)
        val symbolNamesOffset = symbolsOffset + symbols.size
        val shoff = align(symbolNamesOffset + symbolNameBytes.size, 8)
        val out = ByteArray(shoff + SECTION_HEADER_SIZE * sectionCount)

        out[0] = 0x7F
        out[1] = 'E'.code.toByte()
        out[2] = 'L'.code.toByte()
        out[3] = 'F'.code.toByte()
        out[4] = 2 // ELFCLASS64
        out[5] = 1 // ELFDATA2LSB
        out[6] = 1
        u16(out, 16, 2) // ET_EXEC
        u16(out, 18, 0x3E) // EM_X86_64
        u32(out, 20, 1)
        u64(out, 40, shoff.toLong())
        u16(out, 52, HEADER_SIZE)
        u16(out, 54, 56)
        u16(out, 58, SECTION_HEADER_SIZE)
        u16(out, 60, sectionCount)
        u16(out, 62, 2)
        sectionNameBytes.copyInto(out, namesOffset)
        symbols.copyInto(out, symbolsOffset)
        symbolNameBytes.copyInto(out, symbolNamesOffset)

        section(out, shoff, 0, 0, 0, 0, 0, 0)
        section(out, shoff + SECTION_HEADER_SIZE, textName, 1, 0x6, 0x1000, textOffset.toLong(), textSize.toLong())
        section(
            out,
            shoff + 2 * SECTION_HEADER_SIZE,
            stringTableName,
            3,
            0,
            0,
            namesOffset.toLong(),
            sectionNameBytes.size.toLong(),
        )
        section(
            out,
            shoff + 3 * SECTION_HEADER_SIZE,
            symbolTableName,
            2,
            0,
            0,
            symbolsOffset.toLong(),
            symbols.size.toLong(),
        )
        u32(out, shoff + 3 * SECTION_HEADER_SIZE + 40, 4) // sh_link: the string table below
        section(
            out,
            shoff + 4 * SECTION_HEADER_SIZE,
            symbolNamesName,
            3,
            0,
            0,
            symbolNamesOffset.toLong(),
            symbolNameBytes.size.toLong(),
        )
        return out
    }

    fun machO(): ByteArray {
        val headerSize = 32
        val segmentCommandSize = 72
        val sectionSize = 80
        val symtabCommandSize = 24
        val commandSize = segmentCommandSize + sectionSize + symtabCommandSize
        val textOffset = headerSize + commandSize
        val symbolsOffset = textOffset + 64
        val symbolNames = mutableListOf<Byte>(0)
        "_kfun:sample.app#run(){}".encodeToByteArray().forEach { symbolNames += it }
        symbolNames += 0
        val symbolNameBytes = symbolNames.toByteArray()
        val symbolNamesOffset = symbolsOffset + NLIST_SIZE
        val out = ByteArray(symbolNamesOffset + symbolNameBytes.size)

        out[0] = 0xCF.toByte()
        out[1] = 0xFA.toByte()
        out[2] = 0xED.toByte()
        out[3] = 0xFE.toByte()
        u32(out, 4, 0x0100_000CL) // CPU_TYPE_ARM64
        u32(out, 12, 2) // MH_EXECUTE
        u32(out, 16, 2) // two load commands: the segment and the symbol table
        u32(out, 20, commandSize.toLong())

        u32(out, headerSize, 0x19) // LC_SEGMENT_64
        // Its own size, not the total of every command: cmdsize is how far the reader steps to reach
        // the next one.
        u32(out, headerSize + 4, (segmentCommandSize + sectionSize).toLong())
        name16(out, headerSize + 8, "__TEXT")
        u64(out, headerSize + 24, 0x1000)
        u64(out, headerSize + 32, 64)
        u64(out, headerSize + 40, textOffset.toLong())
        u64(out, headerSize + 48, 64)
        u32(out, headerSize + 64, 1)

        val sectionAt = headerSize + segmentCommandSize
        name16(out, sectionAt, "__text")
        name16(out, sectionAt + 16, "__TEXT")
        u64(out, sectionAt + 32, 0x1000)
        u64(out, sectionAt + 40, 64)
        u32(out, sectionAt + 48, textOffset.toLong())
        u32(out, sectionAt + 52, 3)

        val symtabAt = sectionAt + sectionSize
        u32(out, symtabAt, 0x2) // LC_SYMTAB
        u32(out, symtabAt + 4, symtabCommandSize.toLong())
        u32(out, symtabAt + 8, symbolsOffset.toLong())
        u32(out, symtabAt + 12, 1)
        u32(out, symtabAt + 16, symbolNamesOffset.toLong())
        u32(out, symtabAt + 20, symbolNameBytes.size.toLong())

        u32(out, symbolsOffset, 1) // n_strx
        out[symbolsOffset + 4] = 0xE // N_SECT
        out[symbolsOffset + 5] = 1 // the first section
        u64(out, symbolsOffset + 8, 0x1000)
        symbolNameBytes.copyInto(out, symbolNamesOffset)
        return out
    }

    private fun align(
        value: Int,
        to: Int,
    ): Int = if (value % to == 0) value else value + (to - value % to)

    @Suppress("LongParameterList")
    private fun section(
        out: ByteArray,
        at: Int,
        nameOffset: Int,
        type: Long,
        flags: Long,
        address: Long,
        offset: Long,
        size: Long,
    ) {
        u32(out, at, nameOffset.toLong())
        u32(out, at + 4, type)
        u64(out, at + 8, flags)
        u64(out, at + 16, address)
        u64(out, at + 24, offset)
        u64(out, at + 32, size)
        u64(out, at + 48, 8)
    }

    private fun name16(
        out: ByteArray,
        at: Int,
        name: String,
    ) = name.encodeToByteArray().copyInto(out, at)

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
}
