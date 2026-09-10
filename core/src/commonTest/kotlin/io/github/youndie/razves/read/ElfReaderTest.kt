package io.github.youndie.razves.read

import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.fixture.ElfBuilder.Companion.SHF_ALLOC
import io.github.youndie.razves.fixture.ElfBuilder.Companion.SHT_PROGBITS
import io.github.youndie.razves.fixture.ElfBuilder.Companion.STT_SECTION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElfReaderTest {
    @Test
    fun readsSectionsWithTheirNamesSizesAndKinds() {
        val bytes =
            ElfBuilder()
                .text(address = 0x1000, size = 512)
                .nobits(".bss", address = 0x2000, size = 4096)
                .notAllocated(".comment", size = 32)
                .build()

        val image = ElfReader.read(bytes, "fixture")

        assertEquals(BinaryFormat.ELF64, image.format)
        assertEquals(SizeAlgorithm.RECORDED, image.sizeAlgorithm)
        assertEquals(bytes.size.toLong(), image.fileSize)

        val text = image.sections.single { it.name == ".text" }
        assertEquals(512, text.size)
        assertEquals(SectionKind.ALLOCATED, text.kind)
        assertEquals(512, text.fileBytes)
        assertNull(text.segment, "ELF sections have no segment; the field exists for Mach-O")

        val bss = image.sections.single { it.name == ".bss" }
        assertEquals(4096, bss.size)
        assertEquals(SectionKind.ALLOCATED_NOBITS, bss.kind)
        assertEquals(0, bss.fileBytes, "a NOBITS section has a size and costs no file bytes")

        val comment = image.sections.single { it.name == ".comment" }
        assertEquals(SectionKind.METADATA, comment.kind)
        assertEquals(32, comment.fileBytes)
    }

    @Test
    fun headerBytesAreReadFromTheHeaderRatherThanLeftOver() {
        // No program headers in a fixture, so this is the ELF header plus the section header table.
        // Read, not inferred: if it were the remainder after subtracting the sections, the file-size
        // identity in Reconciliation would be true by construction and would check nothing.
        val bytes = ElfBuilder().text(address = 0x1000, size = 16).build()
        val image = ElfReader.read(bytes, "fixture")
        val sectionCount = image.sections.size
        assertEquals(64L + 64L * sectionCount, image.headerBytes)
    }

    @Test
    fun readsSymbolsWithTheirRecordedSizes() {
        val builder = ElfBuilder()
        val textIndex = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 300)
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.first#internal", 0x1000, 100, textIndex))
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.second#internal", 0x1064, 200, textIndex))
                .build()

        val image = ElfReader.read(bytes, "fixture")

        assertTrue(image.hasSymbolTable)
        assertEquals(2, image.symbols.size)
        val first = image.symbols.single { it.name == "kfun:sample.first#internal" }
        assertEquals(0x1000, first.address)
        assertEquals(100, first.size)
        assertEquals(textIndex, first.sectionIndex)
    }

    @Test
    fun aStrippedBinaryIsReadableAndSaysItHasNoSymbolTable() {
        // The refusal itself belongs to B-15; what the reader owes here is the honest flag rather
        // than an empty symbol list that looks like a binary containing nothing.
        val bytes = ElfBuilder().text(address = 0x1000, size = 64).stripped().build()
        val image = ElfReader.read(bytes, "fixture")
        assertFalse(image.hasSymbolTable)
        assertTrue(image.symbols.isEmpty())
    }

    @Test
    fun sectionAndFileSymbolsAreNotOwners() {
        // A STT_SECTION entry labels a whole section. Counting it would charge the section to itself
        // and double every byte in it.
        val builder = ElfBuilder()
        val textIndex = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 128)
                .symbol(ElfBuilder.SymbolSpec(".text", 0x1000, 128, textIndex, type = STT_SECTION))
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.only#internal", 0x1000, 128, textIndex))
                .build()

        val image = ElfReader.read(bytes, "fixture")

        assertEquals(listOf("kfun:sample.only#internal"), image.symbols.map { it.name })
    }

    @Test
    fun undefinedAndZeroSizedSymbolsOwnNothing() {
        val builder = ElfBuilder()
        val textIndex = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 64)
                .symbol(ElfBuilder.SymbolSpec("imported", 0, 0, sectionIndex = 0))
                .symbol(ElfBuilder.SymbolSpec("marker", 0x1000, 0, textIndex))
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.real#internal", 0x1000, 64, textIndex))
                .build()

        val image = ElfReader.read(bytes, "fixture")

        assertEquals(listOf("kfun:sample.real#internal"), image.symbols.map { it.name })
    }

    @Test
    fun aByteOrderFlagThatDoesNotMatchThePayloadFails() {
        // Nothing Kotlin/Native targets is big-endian today, so the byte order is read rather than
        // assumed and the wrong answer is a failure rather than a plausible report.
        val little = ElfBuilder().text(address = 0x1000, size = 64).build()
        val big = little.copyOf()
        big[5] = 2 // ELFDATA2MSB, without swapping the payload
        val failure = assertFailsWith<IllegalArgumentException> { ElfReader.read(big, "fixture") }
        assertTrue(
            failure.message.orEmpty().isNotEmpty(),
            "a byte-order flag that does not match the payload must fail with a message, not silently",
        )
    }

    @Test
    fun refusesSomethingThatIsNotAnElfFile() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ElfReader.read("not an executable at all".encodeToByteArray(), "note.txt")
            }
        assertTrue(failure.message.orEmpty().contains("not an ELF"))
    }

    @Test
    fun refusesA32BitObject() {
        val bytes = ElfBuilder().text(address = 0x1000, size = 64).build()
        bytes[4] = 1 // ELFCLASS32
        val failure = assertFailsWith<IllegalArgumentException> { ElfReader.read(bytes, "fixture") }
        assertTrue(failure.message.orEmpty().contains("ELF64"))
    }

    @Test
    fun matchesOnlyOnTheMagic() {
        assertTrue(ElfReader.matches(ElfBuilder().text(address = 0x1000, size = 8).build()))
        assertFalse(ElfReader.matches(byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte())))
        assertFalse(ElfReader.matches(ByteArray(0)))
    }

    @Test
    fun readsAnAllocatedSectionThatIsNotCodeAsAllocated() {
        val bytes =
            ElfBuilder()
                .section(ElfBuilder.SectionSpec(".rodata", SHT_PROGBITS, SHF_ALLOC, 0x3000, ByteArray(96)))
                .build()
        val image = ElfReader.read(bytes, "fixture")
        assertEquals(SectionKind.ALLOCATED, image.sections.single { it.name == ".rodata" }.kind)
    }
}
