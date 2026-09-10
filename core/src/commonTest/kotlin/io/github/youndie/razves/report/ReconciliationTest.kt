package io.github.youndie.razves.report

import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.read.BinaryFormat
import io.github.youndie.razves.read.BinaryImage
import io.github.youndie.razves.read.ElfReader
import io.github.youndie.razves.read.FileRegion
import io.github.youndie.razves.read.Section
import io.github.youndie.razves.read.SectionKind
import io.github.youndie.razves.read.SizeAlgorithm
import io.github.youndie.razves.read.Symbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReconciliationTest {
    @Test
    fun everyByteOfTheFileIsAccountedFor() {
        val bytes =
            ElfBuilder()
                .text(address = 0x1000, size = 512)
                .nobits(".bss", address = 0x2000, size = 4096)
                .notAllocated(".comment", size = 32)
                .build()

        val r = Attribution.of(ElfReader.read(bytes, "fixture"))

        assertEquals(bytes.size.toLong(), r.fileSize)
        assertEquals(
            r.fileSize,
            r.headerBytes + r.allocatedBytes + r.metadataBytes + r.interRegionPadding + r.unparsedBytes,
            "the file-size identity is the oracle; if it needs a tolerance, the reader is wrong",
        )
        assertTrue(r.interRegionPadding >= 0)
        assertEquals(0, r.unparsedBytes, "an ELF section header table lists every region there is")
    }

    @Test
    fun nobitsCountsTowardsMemoryAndNotTowardsTheFile() {
        val bytes =
            ElfBuilder()
                .text(address = 0x1000, size = 512)
                .nobits(".bss", address = 0x2000, size = 4096)
                .build()

        val r = Attribution.of(ElfReader.read(bytes, "fixture"))

        assertEquals(4096, r.nobitsBytes)
        assertEquals(r.allocatedBytes + 4096, r.virtualSize)
        assertTrue(r.virtualSize > r.fileSize - r.metadataBytes - r.headerBytes)
    }

    @Test
    fun attributedAndUnattributedAddUpToTheSection() {
        val builder = ElfBuilder()
        val textIndex = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 1000)
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.covered#internal", 0x1000, 600, textIndex))
                .build()

        val r = Attribution.of(ElfReader.read(bytes, "fixture"))
        val text = r.sections.single { it.section.name == ".text" }

        assertEquals(600, text.attributed)
        assertEquals(400, text.unattributed, "the 400 bytes no symbol claims are a row, not a rounding error")
        assertEquals(1000, text.attributed + text.unattributed)
        assertEquals(0.6, text.coverage)
    }

    @Test
    fun unattributedIsNeverAbsorbed() {
        // The whole tool rests on this number being visible. A release binary really does carry a
        // fifth of its allocated bytes with no owning symbol, and a report that hides it is a report
        // nobody can check.
        val bytes = ElfBuilder().text(address = 0x1000, size = 4096).build()
        val r = Attribution.of(ElfReader.read(bytes, "fixture"))
        assertEquals(0, r.attributedBytes)
        assertEquals(r.allocatedBytes, r.unattributedBytes)
        assertEquals(r.allocatedBytes, r.attributedBytes + r.unattributedBytes)
    }

    @Test
    fun overlappingSymbolsAreChargedOnce() {
        // An alias and its definition cover the same bytes. Summing their recorded sizes reports a
        // section as more than 100% attributed, which is how a size tool loses an argument.
        val builder = ElfBuilder()
        val textIndex = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 256)
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.definition#internal", 0x1000, 256, textIndex))
                .symbol(ElfBuilder.SymbolSpec("sample_alias", 0x1000, 256, textIndex))
                .build()

        val r = Attribution.of(ElfReader.read(bytes, "fixture"))
        val text = r.sections.single { it.section.name == ".text" }

        assertEquals(256, text.attributed, "the union of the two ranges, not the sum of two sizes")
        assertEquals(0, text.unattributed)
        assertEquals(
            listOf("kfun:sample.definition#internal" to 256L),
            text.owners.map { it.symbol.name to it.bytes },
            "the earlier-sorting name wins the bytes so that two runs produce the same report",
        )
    }

    @Test
    fun aSymbolRunningPastItsSectionIsClipped() {
        val builder = ElfBuilder()
        val textIndex = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 100)
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.optimistic#internal", 0x1000, 400, textIndex))
                .build()

        val r = Attribution.of(ElfReader.read(bytes, "fixture"))
        val text = r.sections.single { it.section.name == ".text" }

        assertEquals(100, text.attributed)
    }

    @Test
    fun symbolsArePlacedByTheirSectionIndexNotByAddressLookup() {
        // `.tbss` is thread-local, occupies no file bytes, and its virtual address deliberately
        // overlaps the section that follows it. An address-keyed lookup charges the neighbour's
        // symbols to `.tbss` and reports a section as more than 100% attributed — measured on a real
        // binary before this reader existed. Placing by recorded section index cannot do that.
        val builder = ElfBuilder()
        val tbssIndex = builder.nextSectionIndex
        builder.nobits(".tbss", address = 0x4000, size = 240)
        val ctorsIndex = builder.nextSectionIndex
        val bytes =
            builder
                .section(
                    ElfBuilder.SectionSpec(
                        ".ctors",
                        ElfBuilder.SHT_PROGBITS,
                        ElfBuilder.SHF_ALLOC,
                        0x4000,
                        ByteArray(40),
                    ),
                ).symbol(ElfBuilder.SymbolSpec("ctor_entry", 0x4000, 40, ctorsIndex, type = ElfBuilder.STT_OBJECT))
                .build()

        val r = Attribution.of(ElfReader.read(bytes, "fixture"))

        val tbss = r.sections.single { it.section.name == ".tbss" }
        val ctors = r.sections.single { it.section.name == ".ctors" }
        assertEquals(0, tbss.attributed, "a NOBITS section owns nothing even when a neighbour shares its address")
        assertEquals(40, ctors.attributed)
        assertTrue(tbssIndex != ctorsIndex)
    }

    @Test
    fun aReaderThatLosesASectionFailsAtConstruction() {
        val honest = ElfReader.read(ElfBuilder().text(address = 0x1000, size = 512).build(), "fixture")
        val lying = honest.copy(sections = honest.sections.filterNot { it.name == ".text" })

        val failure = assertFailsWith<IllegalArgumentException> { Attribution.of(lying) }

        assertTrue(
            failure.message.orEmpty().contains("covered by nothing"),
            "a lost section must show up as file bytes nobody claims, got: ${failure.message}",
        )
    }

    @Test
    fun sectionsClaimingMoreThanTheFileHoldsFail() {
        val honest = ElfReader.read(ElfBuilder().text(address = 0x1000, size = 512).build(), "fixture")
        val inflated =
            honest.copy(
                sections = honest.sections.map { if (it.name == ".text") it.copy(size = it.size * 1000) else it },
            )

        val failure = assertFailsWith<IllegalArgumentException> { Attribution.of(inflated) }

        assertTrue(failure.message.orEmpty().isNotEmpty())
    }

    @Test
    fun sectionsThatOverlapInTheFileFail() {
        // Verified true of the real subjects before it was made a requirement: the shildik release
        // binaries have no overlapping file extents at all, over 34 to 43 regions each.
        //
        // The overlap is made by moving `.text` back a few bytes, into the ELF header it follows,
        // rather than forward into its successor: moving it forward would leave a hole where it used
        // to be, and the coverage check would fire on the hole first and never reach the overlap.
        val honest = ElfReader.read(ElfBuilder().text(address = 0x1000, size = 512).build(), "fixture")
        val overlapping =
            honest.copy(
                sections =
                    honest.sections.map {
                        if (it.name ==
                            ".text"
                        ) {
                            it.copy(fileOffset = it.fileOffset - 4)
                        } else {
                            it
                        }
                    },
            )

        val failure = assertFailsWith<IllegalArgumentException> { Attribution.of(overlapping) }

        assertTrue(
            failure.message.orEmpty().contains("inside a region"),
            "expected an overlap to be named, got: ${failure.message}",
        )
    }

    @Test
    fun aNegativePartIsRefused() {
        // Reached directly rather than through a reader, because no reader should be able to produce
        // it — which is exactly why the constructor has to say so.
        val section = Section(null, ".text", 0x1000, 100, 64, 8, SectionKind.ALLOCATED)
        val image =
            BinaryImage(
                name = "hand-made",
                format = BinaryFormat.ELF64,
                sizeAlgorithm = SizeAlgorithm.RECORDED,
                fileSize = 164,
                containerRegions = listOf(FileRegion("ELF header", 0, 64, 1)),
                sections = listOf(section),
                symbols = emptyList(),
                hasSymbolTable = true,
                coversEveryFileByte = true,
            )
        val tooMuch = SectionAttribution(section, listOf(SymbolExtent(Symbol("x", 0x1000, 200, 0), 200)))

        val failure = assertFailsWith<IllegalArgumentException> { Reconciliation(image, listOf(tooMuch)) }

        assertTrue(failure.message.orEmpty().contains("unattributed"))
    }
}
