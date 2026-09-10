package io.github.youndie.razves.report

import io.github.youndie.razves.attribute.Origin
import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.read.ElfReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SizeReportTest {
    @Test
    fun everyAttributedByteLandsInExactlyOneOrigin() {
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 1024)
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.app.handle#internal", 0x1000, 256, text))
                .symbol(ElfBuilder.SymbolSpec("_ZN6kotlin2gc5State4nameEv", 0x1100, 128, text))
                .symbol(ElfBuilder.SymbolSpec("_ZN5tokio7runtime5build17h55d0632c5a2d2c9aE", 0x1180, 256, text))
                .symbol(ElfBuilder.SymbolSpec("ossl_aes_gcm_encrypt_avx512", 0x1280, 128, text))
                .build()

        val r = Attribution.report(ElfReader.read(bytes, "fixture"))

        assertEquals(r.reconciliation.attributedBytes, r.origins.sumOf { it.bytes })
        assertEquals(256, r.bytesOf(Origin.KOTLIN))
        assertEquals(128, r.bytesOf(Origin.KOTLIN_RUNTIME))
        assertEquals(256, r.bytesOf(Origin.RUST))
        assertEquals(128, r.bytesOf(Origin.C))
        assertEquals(0, r.bytesOf(Origin.CXX), "an origin with nothing in it reports zero, not nothing")
    }

    @Test
    fun everyOriginGetsARowInTheEnumsOrderEvenWhenItIsEmpty() {
        // Two reports of two different binaries should be readable side by side without the reader
        // having to find each row first - and a row that appears and disappears between two builds
        // is noise in a diff, which is what B-13 will be reading.
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 512)
                .symbol(ElfBuilder.SymbolSpec("ossl_thing", 0x1000, 128, text))
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.app.handle#internal", 0x1080, 128, text))
                .build()

        val r = Attribution.report(ElfReader.read(bytes, "fixture"))

        assertEquals(Origin.entries.toList(), r.origins.map { it.origin })
        assertEquals(128, r.bytesOf(Origin.KOTLIN))
        assertEquals(128, r.bytesOf(Origin.C))
        assertEquals(0, r.origins.single { it.origin == Origin.RUST }.bytes)
        assertEquals(0, r.origins.single { it.origin == Origin.RUST }.symbols)
    }

    @Test
    fun anOriginRowCountsItsSymbolsAsWellAsItsBytes() {
        // A megabyte in one symbol and a megabyte in ten thousand are different problems, and the
        // row should not read the same for both.
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 512)
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.a.one#internal", 0x1000, 64, text))
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.b.two#internal", 0x1040, 64, text))
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.c.three#internal", 0x1080, 64, text))
                .build()

        val r = Attribution.report(ElfReader.read(bytes, "fixture"))

        assertEquals(3, r.origins.single { it.origin == Origin.KOTLIN }.symbols)
        assertEquals(192, r.bytesOf(Origin.KOTLIN))
    }

    @Test
    fun aSectionNoSymbolClaimsIsNamedRatherThanSummedAway() {
        // The point of the whole layer. `.eh_frame` is 6% of a real release binary and belongs to no
        // package; a reader who sees only "unattributed: 3.3 MB" learns nothing from it.
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 256)
                .section(
                    ElfBuilder.SectionSpec(
                        ".eh_frame",
                        ElfBuilder.SHT_PROGBITS,
                        ElfBuilder.SHF_ALLOC,
                        0x2000,
                        ByteArray(4096),
                    ),
                ).symbol(ElfBuilder.SymbolSpec("kfun:sample.app.handle#internal", 0x1000, 256, text))
                .build()

        val r = Attribution.report(ElfReader.read(bytes, "fixture"))

        assertEquals(listOf(".eh_frame"), r.unownedSections.map { it.section.name })
        assertEquals(
            4096,
            r.unownedSections
                .single()
                .section.size,
        )
        assertEquals(0.0, r.unownedSections.single().coverage)
        assertEquals(listOf(".text"), r.ownedSections.map { it.section.name })
    }

    @Test
    fun unownedSectionsComeLargestFirst() {
        val bytes =
            ElfBuilder()
                .section(
                    ElfBuilder.SectionSpec(
                        ".small",
                        ElfBuilder.SHT_PROGBITS,
                        ElfBuilder.SHF_ALLOC,
                        0x2000,
                        ByteArray(64),
                    ),
                ).section(
                    ElfBuilder.SectionSpec(
                        ".large",
                        ElfBuilder.SHT_PROGBITS,
                        ElfBuilder.SHF_ALLOC,
                        0x3000,
                        ByteArray(4096),
                    ),
                ).section(
                    ElfBuilder.SectionSpec(
                        ".middle",
                        ElfBuilder.SHT_PROGBITS,
                        ElfBuilder.SHF_ALLOC,
                        0x5000,
                        ByteArray(512),
                    ),
                ).build()

        val r = Attribution.report(ElfReader.read(bytes, "fixture"))

        assertEquals(listOf(".large", ".middle", ".small"), r.unownedSections.map { it.section.name })
    }

    @Test
    fun everySectionRowCarriesItsCoverage() {
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 1000)
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.app.handle#internal", 0x1000, 410, text))
                .build()

        val r = Attribution.report(ElfReader.read(bytes, "fixture"))

        // 41% is not a number picked for the test: it is what `.rodata` attribution is worth on a
        // real binary, against 98% for `.text`. A row that does not carry this is a row a reader
        // will trust as much as one that deserves it.
        assertEquals(0.41, r.ownedSections.single { it.section.name == ".text" }.coverage)
    }

    @Test
    fun aReportWhoseOriginsDoNotAddUpCannotBeBuilt() {
        val bytes = ElfBuilder().text(address = 0x1000, size = 256).build()
        val reconciliation = Attribution.of(ElfReader.read(bytes, "fixture"))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                SizeReport(reconciliation, listOf(OriginRow(Origin.KOTLIN, 999, 1)))
            }

        assertTrue(failure.message.orEmpty().contains("does not add up"))
    }

    @Test
    fun anOriginCannotAppearTwice() {
        val bytes = ElfBuilder().text(address = 0x1000, size = 256).build()
        val reconciliation = Attribution.of(ElfReader.read(bytes, "fixture"))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                SizeReport(
                    reconciliation,
                    listOf(OriginRow(Origin.KOTLIN, 0, 0), OriginRow(Origin.KOTLIN, 0, 0)),
                )
            }

        assertTrue(failure.message.orEmpty().contains("twice"))
    }

    @Test
    fun theShareIsOfTheAttributedBytesAndSaysSo() {
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 1024)
                .symbol(ElfBuilder.SymbolSpec("kfun:sample.app.handle#internal", 0x1000, 256, text))
                .symbol(ElfBuilder.SymbolSpec("ossl_thing", 0x1100, 256, text))
                .build()

        val r = Attribution.report(ElfReader.read(bytes, "fixture"))

        // Half of what has an owner, and a quarter of the section. Both are true and they are not
        // the same number; the report has to be clear which one it is printing.
        assertEquals(0.5, r.shareOf(Origin.KOTLIN))
        assertEquals(512, r.reconciliation.attributedBytes)
        assertEquals(512, r.reconciliation.unattributedBytes)
    }
}
