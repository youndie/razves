package io.github.youndie.razves.cli

import io.github.youndie.razves.report.ReportDocument
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The CLI's one piece of work, exercised as a function of strings.
 *
 * The command class is argument parsing and an exit code; neither is worth checking twice, and both
 * are awkward to check on a native target. What is worth checking is that razves picks its reader
 * from the file rather than from an option, and refuses what it cannot read.
 */
class AnalyseTest {
    private fun writeTemp(
        name: String,
        bytes: ByteArray,
    ): String {
        val path = Path(SystemTemporaryDirectory, "razves-$name")
        SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
        return path.toString()
    }

    @Test
    fun theFormatIsDecidedByTheFileRatherThanByAnOption() {
        // A caller who has to tell razves what kind of binary they handed it can tell it wrongly, and
        // the file already knows.
        val elf = writeTemp("elf.kexe", TestBinaries.elf())
        val machO = writeTemp("macho.kexe", TestBinaries.machO())

        assertTrue("ELF64" in Analyse.report(elf, emptyList(), OutputFormat.TEXT, 20))
        assertTrue("MACHO64" in Analyse.report(machO, emptyList(), OutputFormat.TEXT, 20))
    }

    @Test
    fun somethingThatIsNeitherFormatIsRefusedByName() {
        val path = writeTemp("notes.txt", "this is not an executable".encodeToByteArray())

        val failure =
            assertFailsWith<IllegalStateException> { Analyse.report(path, emptyList(), OutputFormat.TEXT, 20) }

        assertTrue(failure.message.orEmpty().contains("neither an ELF nor"))
    }

    @Test
    fun aMissingFileIsRefusedBeforeAnythingIsRead() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                Analyse.report("/definitely/not/here.kexe", emptyList(), OutputFormat.TEXT, 20)
            }
        assertTrue(failure.message.orEmpty().contains("does not exist"))
    }

    @Test
    fun aMissingKlibRootIsRefusedRatherThanSilentlyIgnored() {
        val elf = writeTemp("elf2.kexe", TestBinaries.elf())

        val failure =
            assertFailsWith<IllegalArgumentException> {
                Analyse.report(elf, listOf("/definitely/not/here"), OutputFormat.TEXT, 20)
            }

        // Ignoring it would produce a report that stops at package level for a reason the caller asked
        // it not to, and say so only in a header line they had no reason to read.
        assertTrue(failure.message.orEmpty().contains("does not exist"))
    }

    @Test
    fun theJsonOutputIsTheDocumentThePluginWillCommit() {
        val elf = writeTemp("elf3.kexe", TestBinaries.elf())

        val json = Analyse.report(elf, emptyList(), OutputFormat.JSON, 20)

        val parsed = ReportDocument.parse(json)
        assertEquals(json, parsed.toJson(), "it round-trips, because a baseline that reformats itself is a false diff")
        assertEquals(ReportDocument.FORMAT_VERSION, parsed.formatVersion)
    }

    @Test
    fun withoutKlibsTheReportSaysModuleAttributionWasNotAvailable() {
        val elf = writeTemp("elf4.kexe", TestBinaries.elf())
        val text = Analyse.report(elf, emptyList(), OutputFormat.TEXT, 20)
        assertTrue("no klibs were supplied" in text)
    }
}
