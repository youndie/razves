package io.github.youndie.razves.read

import io.github.youndie.razves.fixture.MachOBuilder
import io.github.youndie.razves.report.Attribution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MachOReaderTest {
    @Test
    fun twoSectionsNamedConstStayTwoSections() {
        // A real Kotlin/Native binary carries `__TEXT,__const` and `__DATA_CONST,__const`. A map keyed
        // by name loses one of them, and loses it only on Apple targets.
        val bytes =
            MachOBuilder()
                .section(MachOBuilder.SectionSpec("__TEXT", "__const", 0x1000, 256))
                .section(MachOBuilder.SectionSpec("__DATA_CONST", "__const", 0x8000, 512))
                .build()

        val image = MachOReader.read(bytes, "fixture")
        val consts = image.sections.filter { it.name == "__const" }

        assertEquals(2, consts.size)
        assertEquals(setOf("__TEXT,__const", "__DATA_CONST,__const"), consts.map { it.qualifiedName }.toSet())
        assertEquals(setOf(256L, 512L), consts.map { it.size }.toSet())
    }

    @Test
    fun symbolSizesAreTheDistanceToTheNextSymbol() {
        // `llvm-nm --print-size` on a Mach-O warns that sizes are always zero, and it is right:
        // nlist_64 has no size field. The size here is derived, and the report says so.
        val builder = MachOBuilder()
        val text = builder.nextSectionOrdinal
        val bytes =
            builder
                .section(MachOBuilder.SectionSpec("__TEXT", "__text", 0x1000, 300))
                .symbol(MachOBuilder.SymbolSpec("_kfun:sample.first#internal", 0x1000, text))
                .symbol(MachOBuilder.SymbolSpec("_kfun:sample.second#internal", 0x1064, text))
                .build()

        val image = MachOReader.read(bytes, "fixture")

        assertEquals(SizeAlgorithm.ADDRESS_DELTA, image.sizeAlgorithm)
        assertEquals(100, image.symbols.single { it.name.endsWith("first#internal") }.size)
        // The last symbol runs to the end of its section: 0x1000 + 300 − 0x1064.
        assertEquals(200, image.symbols.single { it.name.endsWith("second#internal") }.size)
    }

    @Test
    fun theLastSymbolIsClampedAtTheEndOfItsSection() {
        val builder = MachOBuilder()
        val text = builder.nextSectionOrdinal
        val bytes =
            builder
                .section(MachOBuilder.SectionSpec("__TEXT", "__text", 0x1000, 64))
                .section(MachOBuilder.SectionSpec("__DATA", "__data", 0x9000, 64))
                .symbol(MachOBuilder.SymbolSpec("_only", 0x1000, text))
                .build()

        val image = MachOReader.read(bytes, "fixture")

        assertEquals(
            64,
            image.symbols.single().size,
            "without the clamp the last symbol of a section swallows the distance to the next segment",
        )
    }

    @Test
    fun symbolsSharingAnAddressAreChargedOnce() {
        val builder = MachOBuilder()
        val text = builder.nextSectionOrdinal
        val bytes =
            builder
                .section(MachOBuilder.SectionSpec("__TEXT", "__text", 0x1000, 128))
                .symbol(MachOBuilder.SymbolSpec("_definition", 0x1000, text))
                .symbol(MachOBuilder.SymbolSpec("_alias", 0x1000, text))
                .build()

        val r = Attribution.of(MachOReader.read(bytes, "fixture"))
        val text2 = r.sections.single { it.section.name == "__text" }

        assertEquals(128, text2.attributed, "an alias and its definition cover the same bytes, once")
        assertEquals(0, text2.unattributed)
    }

    @Test
    fun theSymbolAndStringTablesAreMetadataRatherThanProgramContent() {
        val builder = MachOBuilder()
        val text = builder.nextSectionOrdinal
        val bytes =
            builder
                .section(MachOBuilder.SectionSpec("__TEXT", "__text", 0x1000, 128))
                .symbol(MachOBuilder.SymbolSpec("_kfun:sample.only#internal", 0x1000, text))
                .build()

        val r = Attribution.of(MachOReader.read(bytes, "fixture"))

        val metadata = r.sections.filter { it.section.kind == SectionKind.METADATA }.map { it.section.name }
        assertTrue("symbol table" in metadata)
        assertTrue("string table" in metadata)
        assertTrue(r.metadataBytes > 0)
    }

    @Test
    fun aZeroFillSectionCostsNoFileBytes() {
        val bytes =
            MachOBuilder()
                .section(MachOBuilder.SectionSpec("__TEXT", "__text", 0x1000, 64))
                .section(MachOBuilder.SectionSpec("__DATA", "__bss", 0x9000, 4096, zeroFill = true))
                .build()

        val r = Attribution.of(MachOReader.read(bytes, "fixture"))

        assertEquals(4096, r.nobitsBytes)
        assertEquals(
            0,
            r.sections
                .single { it.section.name == "__bss" }
                .section.fileBytes,
        )
    }

    @Test
    fun theIdentitiesHoldAndUnaccountedBytesAreNamed() {
        val builder = MachOBuilder()
        val text = builder.nextSectionOrdinal
        val bytes =
            builder
                .section(MachOBuilder.SectionSpec("__TEXT", "__text", 0x1000, 512))
                .section(MachOBuilder.SectionSpec("__DATA_CONST", "__const", 0x8000, 256))
                .symbol(MachOBuilder.SymbolSpec("_kfun:sample.only#internal", 0x1000, text))
                .build()

        val r = Attribution.of(MachOReader.read(bytes, "fixture"))

        assertEquals(
            r.fileSize,
            r.headerBytes + r.allocatedBytes + r.metadataBytes + r.interRegionPadding + r.unparsedBytes,
        )
        assertEquals(r.allocatedBytes, r.attributedBytes + r.unattributedBytes)
        assertEquals(
            0,
            r.unparsedBytes,
            "a fixture carries nothing razves does not parse; a real binary's link-edit may, and that " +
                "is a row rather than a failure",
        )
    }

    @Test
    fun aStrippedMachOSaysSo() {
        val bytes =
            MachOBuilder()
                .section(MachOBuilder.SectionSpec("__TEXT", "__text", 0x1000, 64))
                .stripped()
                .build()

        val image = MachOReader.read(bytes, "fixture")

        assertFalse(image.hasSymbolTable)
        assertTrue(image.symbols.isEmpty())
    }

    @Test
    fun matchesOnlyOnTheMagic() {
        assertTrue(
            MachOReader.matches(
                MachOBuilder().section(MachOBuilder.SectionSpec("__TEXT", "__text", 0x1000, 8)).build(),
            ),
        )
        assertFalse(MachOReader.matches(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())))
        assertFalse(MachOReader.matches(ByteArray(0)))
    }
}
