package io.github.youndie.razves.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Rewrites the committed baseline, and is **deliberately not part of `check`**.
 *
 * Anything that both verifies and rewrites its own reference passes forever. `sborka` makes the same
 * split for `mutationTest`, for the same reason: a gate whose baseline moves with the thing it
 * measures is a gate that has never failed and never will.
 */
public abstract class SizeBaselineWriteTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val report: RegularFileProperty

    @get:OutputFile
    public abstract val baseline: RegularFileProperty

    @TaskAction
    public fun write() {
        val target = baseline.get().asFile
        target.parentFile?.mkdirs()
        target.writeText(report.get().asFile.readText())
        logger.lifecycle(
            "razves: baseline written to ${target.path}. Commit it - a baseline that is not in the repository says something different on every machine.",
        )
    }
}
