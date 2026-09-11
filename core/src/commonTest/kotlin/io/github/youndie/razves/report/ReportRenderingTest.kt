package io.github.youndie.razves.report

import io.github.youndie.razves.attribute.Origin
import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.klib.Klib
import io.github.youndie.razves.read.ElfReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportRenderingTest {
    private fun sample(withModules: Boolean = false): SizeReport {
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        val bytes =
            builder
                .text(address = 0x1000, size = 2048)
                .section(
                    ElfBuilder.SectionSpec(
                        ".eh_frame",
                        ElfBuilder.SHT_PROGBITS,
                        ElfBuilder.SHF_ALLOC,
                        0x8000,
                        ByteArray(512),
                    ),
                ).nobits(".bss", address = 0x9000, size = 4096)
                .symbol(ElfBuilder.SymbolSpec("kfun:io.ktor.http#parse(kotlin.String){}", 0x1000, 600, text))
                .symbol(ElfBuilder.SymbolSpec("ossl_aes_gcm_encrypt_avx512", 0x1258, 400, text))
                .build()
        val image = ElfReader.read(bytes, "sample.kexe")
        val modules =
            if (withModules) {
                listOf(Klib("io.ktor:ktor-http", listOf("linux_x64"), setOf("io.ktor.http")))
            } else {
                null
            }
        return Attribution.report(image, klibs = modules)
    }

    @Test
    fun theJsonRoundTripsByteForByte() {
        // The same file is what `--format json` prints and what the plugin commits as a baseline, so
        // a format that reformats itself would produce a diff with no change in it.
        val once = ReportDocument.of(sample()).toJson()
        val twice = ReportDocument.parse(once).toJson()
        assertEquals(once, twice)
    }

    @Test
    fun theDocumentCarriesNoDerivedNumbers() {
        // Coverage and percentages are computed, never stored: a rounded float in a file that must
        // round-trip is a bug waiting for a locale, and a stored derived value can disagree with the
        // computed one.
        val json = ReportDocument.of(sample()).toJson()
        assertTrue("coverage" !in json)
        assertTrue("percent" !in json)
        assertTrue("share" !in json)
    }

    @Test
    fun everyTotalOfTheModelSurvivesIntoTheDocument() {
        val report = sample()
        val d = ReportDocument.of(report)

        assertEquals(report.reconciliation.fileSize, d.fileSize)
        assertEquals(report.reconciliation.attributedBytes, d.attributedBytes)
        assertEquals(report.reconciliation.unattributedBytes, d.unattributedBytes)
        assertEquals(report.reconciliation.nobitsBytes, d.nobitsBytes)
        assertEquals(d.fileSize, d.headerBytes + d.allocatedBytes + d.metadataBytes + d.paddingBytes + d.unparsedBytes)
        assertEquals(d.allocatedBytes, d.attributedBytes + d.unattributedBytes)
        assertEquals(d.attributedBytes, d.origins.sumOf { it.bytes })
    }

    @Test
    fun aSectionCarriesItsOwnCoverage() {
        val d = ReportDocument.of(sample())
        val text = d.sections.single { it.name == ".text" }
        assertEquals(2048, text.size)
        assertEquals(1000, text.attributed)
        assertEquals(1048, text.unattributed)
        assertEquals(1000.0 / 2048, text.coverage)
    }

    @Test
    fun aSectionNoSymbolClaimsIsListedSeparately() {
        val d = ReportDocument.of(sample())
        assertEquals(listOf(".eh_frame"), d.unownedSections.map { it.name })
        assertEquals(0.0, d.unownedSections.single().coverage)
    }

    @Test
    fun theTextHeaderSaysWhatWouldOtherwiseBeInvisible() {
        val text = TextReport.render(ReportDocument.of(sample()))

        assertTrue("recorded by the symbol table" in text, "which size algorithm produced the numbers")
        assertTrue("no klibs were supplied" in text, "that the absence of module rows is not an absence of modules")
        assertTrue("truncated to 3 segments" in text, "that a package row may span several packages")
        // Sorted, so two reports of two binaries can be compared without the reader wondering whether
        // the order means anything.
        assertTrue("android_x64 or linux_x64" in text, "what the binary is, in the names a klib uses")
    }

    @Test
    fun theTextSaysTheModulesCameFromKlibsWhenTheyDid() {
        val text = TextReport.render(ReportDocument.of(sample(withModules = true)))
        assertTrue("attributed from the klibs supplied" in text)
        assertTrue("KOTLIN, BY MODULE" in text)
        assertTrue("io.ktor:ktor-http" in text)
    }

    @Test
    fun theModuleSectionIsAbsentRatherThanEmptyWithoutKlibs() {
        val text = TextReport.render(ReportDocument.of(sample()))
        assertTrue(
            "KOTLIN, BY MODULE" !in text,
            "an empty table reads as an answer; its absence plus a header line does not",
        )
    }

    @Test
    fun unattributedIsPrintedEvenWhenItIsMostOfTheBinary() {
        val text = TextReport.render(ReportDocument.of(sample()))
        assertTrue("unattributed - no symbol claims these" in text)
    }

    @Test
    fun aSectionWithNoOwnerSaysSoRatherThanPrintingZeroPercent() {
        val text = TextReport.render(ReportDocument.of(sample()))
        assertTrue(".eh_frame" in text)
        assertTrue("no owner at all" in text)
    }

    @Test
    fun bytesAreExactFirstAndReadableSecond() {
        val text = TextReport.render(ReportDocument.of(sample()))
        // A tool whose whole claim is that its totals add up puts the addable number first.
        assertTrue(Regex("""2,048 \(2\.0 KiB\)""").containsMatchIn(text), text.lines().first { ".text" in it })
    }

    @Test
    fun aNameTooLongForItsColumnIsCutFromTheMiddle() {
        // An ambiguous module row lists every module declaring the package and runs to a hundred
        // characters. Cutting at the right loses the last module; cutting in the middle loses the part
        // two long coordinates have in common, which is the part that identifies neither of them.
        val report = sample(withModules = true)
        val long =
            ReportDocument.of(report).copy(
                modules =
                    listOf(
                        ReportDocument.ModuleEntry(
                            "<ambiguous: io.github.youndie.first:module-one, io.github.youndie.second:module-two>",
                            100,
                            1,
                            "AMBIGUOUS",
                        ),
                    ),
            )

        val line = TextReport.render(long).lines().single { "ambiguous" in it }

        assertTrue("<ambiguous: io" in line, "the left end identifies the row")
        assertTrue("module-two>" in line, "and so does the right one")
        assertTrue(".." in line)
        assertTrue("100 (100 B)" in line, "the bytes column still lines up")
    }

    @Test
    fun aReportWithNothingInItStillRenders() {
        val image = ElfReader.read(ElfBuilder().text(address = 0x1000, size = 64).build(), "empty.kexe")
        val text = TextReport.render(ReportDocument.of(Attribution.report(image)))
        assertTrue("WHERE THE FILE WENT" in text)
        assertTrue(Origin.KOTLIN.name.lowercase() in text, "an origin with nothing in it is still a row saying zero")
    }
}
