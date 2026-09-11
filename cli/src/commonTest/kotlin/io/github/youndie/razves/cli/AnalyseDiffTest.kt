package io.github.youndie.razves.cli

import io.github.youndie.razves.report.ReportDocument
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The CLI's diff, exercised as a function of two paths.
 *
 * The inputs are reports rather than binaries on purpose, so these tests write documents rather than
 * ELF images: what is worth checking here is which of the two files a refusal names, and that a row
 * that moved is in the output.
 */
class AnalyseDiffTest {
    private fun write(
        name: String,
        text: String,
    ): String {
        val path = Path(SystemTemporaryDirectory, "razves-diff-$name")
        SystemFileSystem.sink(path).buffered().use { it.write(text.encodeToByteArray()) }
        return path.toString()
    }

    private fun report(): ReportDocument =
        ReportDocument.parse(Analyse.report(binary(), emptyList(), OutputFormat.JSON, 20))

    private fun binary(): String {
        val path = Path(SystemTemporaryDirectory, "razves-diff-subject.kexe")
        SystemFileSystem.sink(path).buffered().use { it.write(TestBinaries.elf()) }
        return path.toString()
    }

    @Test
    fun aDiffNamesTheRowsThatMovedAndNotJustTheTotal() {
        // The reason the diff exists at all: a total gives the reader nothing to decide with.
        val before = report()
        val grown = before.packages.first()
        val after =
            before.copy(
                fileSize = before.fileSize + 4096,
                packages = listOf(grown.copy(bytes = grown.bytes + 4096)) + before.packages.drop(1),
            )

        val rendered = Analyse.diff(write("before.json", before.toJson()), write("after.json", after.toJson()), 20)

        assertTrue("+4,096" in rendered, "the movement, in bytes: $rendered")
        assertTrue(grown.name in rendered, "the row that moved is named, not counted")
        assertTrue("BY PACKAGE" in rendered)
    }

    @Test
    fun twoReportsMeasuredDifferentlyAreRefusedRatherThanSubtracted() {
        // An ELF symbol table records a size and Mach-O's does not, so the two numbers are not the
        // same quantity and subtracting them produces a figure with no meaning and no warning.
        val before = report()
        val after = before.copy(sizeAlgorithm = "ADDRESS_DELTA")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                Analyse.diff(write("elf.json", before.toJson()), write("macho.json", after.toJson()), 20)
            }

        assertTrue("measured differently" in failure.message.orEmpty())
    }

    @Test
    fun somethingThatIsNotAReportIsRefusedByNameAndSaysWhatWritesOne() {
        val path = write("notes.json", "{\"hello\":1}")

        val failure = assertFailsWith<IllegalStateException> { Analyse.diff(path, path, 20) }

        assertTrue(path in failure.message.orEmpty(), "which of the two files was wrong: ${failure.message}")
        assertTrue("is not a razves report" in failure.message.orEmpty())
        assertTrue("--format json" in failure.message.orEmpty())
    }

    @Test
    fun aFormatVersionThisReleaseDoesNotKnowIsRefusedByVersion() {
        // The field was in the document from the first commit, for a baseline written by one release
        // and read by the next - and until this command nothing had ever read it. Without the check a
        // future document parses field by field until it does not, and the message then describes a
        // missing field rather than a version.
        val json = report().toJson().replace("\"formatVersion\": 1", "\"formatVersion\": 2")
        val path = write("future.json", json)

        val failure = assertFailsWith<IllegalStateException> { Analyse.diff(path, path, 20) }

        assertTrue("format version 2" in failure.message.orEmpty())
        assertTrue("reads ${ReportDocument.FORMAT_VERSION}" in failure.message.orEmpty())
    }

    @Test
    fun aMissingFileIsRefusedBeforeAnythingIsParsed() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                Analyse.diff("/definitely/not/here.json", "/also/not/here.json", 20)
            }

        assertTrue("/definitely/not/here.json" in failure.message.orEmpty())
        assertTrue("does not exist" in failure.message.orEmpty())
    }
}
