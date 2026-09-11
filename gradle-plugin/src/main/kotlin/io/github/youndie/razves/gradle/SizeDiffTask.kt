package io.github.youndie.razves.gradle

import io.github.youndie.razves.report.DiffDocument
import io.github.youndie.razves.report.ReportDocument
import io.github.youndie.razves.report.TextDiff
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * What moved since the committed baseline.
 *
 * **The baseline is a file in the repository, not the previous build.** A clean CI runner has no
 * previous build and a developer machine has several, from different branches - so "the delta since
 * last time" is a question with a different answer on every machine, which is not a thing to fail a
 * build on. A committed file is also what makes the change readable in a pull request, which is where
 * a 3% growth actually gets discussed.
 *
 * This task never writes the baseline. Anything that both verifies and rewrites its own reference
 * passes forever.
 */
@CacheableTask
public abstract class SizeDiffTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val current: RegularFileProperty

    /** Absent until somebody runs the baseline task; the failure says which one. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val baseline: RegularFileProperty

    @get:Input
    public abstract val baselineTaskName: Property<String>

    @get:Input
    public abstract val rows: Property<Int>

    @get:OutputFile
    public abstract val text: RegularFileProperty

    @TaskAction
    public fun diff() {
        val baselineFile = baseline.orNull?.asFile
        require(baselineFile != null && baselineFile.isFile) {
            "there is no size baseline to compare against. Run ${baselineTaskName.get()} to write one, " +
                "and commit it - a baseline that is not in the repository is a baseline that says " +
                "something different on every machine."
        }
        val rendered =
            TextDiff.render(
                DiffDocument.of(
                    before = ReportDocument.parse(baselineFile.readText()),
                    after = ReportDocument.parse(current.get().asFile.readText()),
                ),
                rows.get(),
            )
        text.get().asFile.writeText(rendered)
        logger.lifecycle(rendered)
    }
}
