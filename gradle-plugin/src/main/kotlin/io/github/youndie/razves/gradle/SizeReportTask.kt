package io.github.youndie.razves.gradle

import io.github.youndie.razves.klib.Klib
import io.github.youndie.razves.klib.KlibReader
import io.github.youndie.razves.read.BinaryImage
import io.github.youndie.razves.read.ElfReader
import io.github.youndie.razves.read.MachOReader
import io.github.youndie.razves.report.Attribution
import io.github.youndie.razves.report.ReportDocument
import io.github.youndie.razves.report.TextReport
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Attributes one linked binary and writes the report.
 *
 * **The klibs are the link classpath, and that is the task's one irreplaceable job.** Everything else
 * here — reading the file, the grammar, the arithmetic — lives in `core` and a CLI can do it from a
 * directory. Which klibs took part cannot be recovered from a directory: a project's build tree
 * holds `kotlinTransformedMetadataLibraries` copies of its dependencies whose `unique_name` is the
 * source-set form, and feeding both in makes every package that library declares look declared
 * twice. Measured on `shildik`: 69 ambiguous rows worth 3.5 MB of 5.1 MB of Kotlin, against 8 worth
 * 0.6 MB from the real classpath, with nothing in the report to say which one you are reading.
 *
 * **Nothing in the task action reaches back into the project.** Every value is a property resolved
 * before execution, which is what lets the configuration cache serialise the task — and what stops
 * the first `Project` reference from being added in a hurry later.
 */
@CacheableTask
public abstract class SizeReportTask : DefaultTask() {
    /**
     * The link output, unstripped.
     *
     * `PathSensitivity.NONE` because only the bytes matter: the same binary under a different
     * absolute path is the same answer, and a build on another machine should hit the cache.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val binary: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val klibs: ConfigurableFileCollection

    @get:Input
    public abstract val packageDepth: Property<Int>

    @get:Input
    public abstract val rows: Property<Int>

    @get:OutputFile
    public abstract val json: RegularFileProperty

    @get:OutputFile
    public abstract val text: RegularFileProperty

    @TaskAction
    public fun report() {
        val file = binary.get().asFile
        val image = read(file.readBytes(), file.name)
        val document =
            ReportDocument.of(
                Attribution.report(
                    image = image,
                    packageDepth = packageDepth.get(),
                    klibs = klibs.files.mapNotNull(::readKlib).ifEmpty { null },
                ),
            )
        val rendered = TextReport.render(document, rows.get())
        json.get().asFile.writeText(document.toJson())
        text.get().asFile.writeText(rendered)
        logger.lifecycle(rendered)
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
     * A klib from the classpath, in either shape.
     *
     * A file that is not a klib is skipped rather than fatal — a native link classpath carries
     * `.a` archives and the odd directory beside the libraries — but a *readable* klib that fails to
     * parse is a defect razves wants to hear about, so only the recognisable shapes are attempted.
     */
    private fun readKlib(file: File): Klib? =
        runCatching {
            when {
                file.isDirectory && File(file, "default/manifest").isFile -> {
                    KlibReader.readUnpacked(
                        manifestText = File(file, "default/manifest").readText(),
                        entryNames = file.walkTopDown().map { it.relativeTo(file).path }.toList(),
                        name = file.name,
                        contentOf = { relative -> File(file, relative).takeIf { it.isFile }?.readBytes() },
                    )
                }

                file.isFile && file.name.endsWith(".klib") -> {
                    KlibReader.readArchive(file.readBytes(), file.name)
                }

                else -> {
                    null
                }
            }
        }.getOrNull()
}
