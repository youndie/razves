package io.github.youndie.razves.cli

import io.github.youndie.razves.klib.Klib
import io.github.youndie.razves.klib.KlibReader
import io.github.youndie.razves.read.BinaryImage
import io.github.youndie.razves.read.ElfReader
import io.github.youndie.razves.read.MachOReader
import io.github.youndie.razves.report.Attribution
import io.github.youndie.razves.report.DiffDocument
import io.github.youndie.razves.report.ReportDocument
import io.github.youndie.razves.report.TextDiff
import io.github.youndie.razves.report.TextReport

/** How the report is printed. `json` is the same document the Gradle plugin commits as a baseline. */
public enum class OutputFormat { TEXT, JSON }

/**
 * The CLI's one piece of work, as a function of strings rather than of a terminal.
 *
 * Keeping it here rather than inside the command class is what lets the tests run it: a clikt command
 * is about argument parsing and an exit code, and neither is the thing worth checking twice.
 */
public object Analyse {
    /**
     * Reads a binary, optionally a set of klibs, and renders the report.
     *
     * The format is decided by the file's own magic bytes rather than by an option. A caller who has
     * to tell razves what kind of binary they handed it can tell it wrongly, and the file already
     * knows.
     */
    public fun report(
        binaryPath: String,
        klibRoots: List<String>,
        format: OutputFormat,
        rows: Int,
    ): String {
        require(Files.exists(binaryPath)) { "$binaryPath does not exist" }
        val image = read(Files.read(binaryPath), binaryPath.substringAfterLast('/'))
        val klibs = klibRoots.flatMap { readKlibs(it) }
        val document = ReportDocument.of(Attribution.report(image, klibs = klibs.ifEmpty { null }))
        return when (format) {
            OutputFormat.TEXT -> TextReport.render(document, rows)
            OutputFormat.JSON -> document.toJson()
        }
    }

    /**
     * What moved between two reports.
     *
     * **Two reports, not two binaries.** A report is the format the plugin already writes as a
     * baseline, so a job can compare a local build against a committed one without a Gradle daemon -
     * and reading binaries here instead would quietly re-measure them, which is how a diff ends up
     * subtracting an address-derived size from a recorded one. [DiffDocument.of] refuses that pair;
     * this surfaces the refusal rather than preventing it.
     */
    public fun diff(
        beforePath: String,
        afterPath: String,
        rows: Int,
    ): String = TextDiff.render(DiffDocument.of(document(beforePath), document(afterPath)), rows)

    /**
     * One report off the disk, with both ways of not being one named.
     *
     * A stack trace from a serialisation library names a field and a character offset, which tells a
     * reader nothing about which of the two files they passed was wrong. And [ReportDocument.formatVersion]
     * existed from the first commit for exactly this moment and had never been read by anything: a
     * baseline written by a later release parses field by field until it does not, and the message
     * then describes a missing field rather than a version.
     */
    private fun document(path: String): ReportDocument {
        require(Files.exists(path)) { "$path does not exist" }
        val document =
            runCatching { ReportDocument.parse(Files.read(path).decodeToString()) }
                .getOrElse {
                    // The first line only. A serialisation library explains itself to whoever wrote
                    // the `Json` builder - "use ignoreUnknownKeys" is advice for razves, not for the
                    // person holding the file, and it buries the one line that names theirs.
                    val reason =
                        it.message
                            .orEmpty()
                            .lineSequence()
                            .firstOrNull { line -> line.isNotBlank() }
                    error(
                        "$path is not a razves report: $reason " +
                            "One is written by `razves report <binary> --format json`, and by the " +
                            "Gradle plugin as a baseline.",
                    )
                }
        check(document.formatVersion == ReportDocument.FORMAT_VERSION) {
            "$path is format version ${document.formatVersion} and this razves reads " +
                "${ReportDocument.FORMAT_VERSION}: it was written by a different release, and the " +
                "rows are not known to mean the same thing."
        }
        return document
    }

    private fun read(
        data: ByteArray,
        name: String,
    ): BinaryImage =
        when {
            ElfReader.matches(data) -> ElfReader.read(data, name)
            MachOReader.matches(data) -> MachOReader.read(data, name)
            else -> error("$name is neither an ELF nor a 64-bit Mach-O file; razves reads those two")
        }

    /**
     * Every klib under a root, in both of the shapes one comes in.
     *
     * A klib that cannot be read is skipped rather than fatal: a root handed to razves on the command
     * line is a directory a person chose, and it will contain things that are not klibs. What must
     * not be skipped quietly is *all* of them, and the caller checks that.
     */
    public fun readKlibs(root: String): List<Klib> {
        require(Files.exists(root)) { "$root does not exist" }
        return Files
            .walk(root, ".klib")
            .mapNotNull { path ->
                runCatching {
                    if (Files.isDirectory(path)) {
                        val entries = Files.listUnder(path).map { it.removePrefix("$path/") }
                        KlibReader.readUnpacked(
                            manifestText = Files.read("$path/default/manifest").decodeToString(),
                            entryNames = entries,
                            name = path.substringAfterLast('/'),
                            contentOf = { relative -> runCatching { Files.read("$path/$relative") }.getOrNull() },
                        )
                    } else {
                        KlibReader.readArchive(Files.read(path), path.substringAfterLast('/'))
                    }
                }.getOrNull()
            }.toList()
    }
}
