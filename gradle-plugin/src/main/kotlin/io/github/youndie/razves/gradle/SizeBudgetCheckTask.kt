package io.github.youndie.razves.gradle

import io.github.youndie.razves.report.Budget
import io.github.youndie.razves.report.BudgetRequest
import io.github.youndie.razves.report.DiffDocument
import io.github.youndie.razves.report.Measure
import io.github.youndie.razves.report.ReportDocument
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
 * The gate. This is the task that makes razves get installed.
 *
 * A report is interesting; a red build is acted on. And the difference between a gate that survives
 * a year and one that is commented out in a week is the **failure message**: it names the rows that
 * moved rather than the total, because a 3% budget is tripped by a dependency bump as easily as by a
 * mistake and "grew 4.1%" gives the reader nothing to decide with.
 *
 * Every decision worth arguing about lives in `core`'s [Budget]; what is here is the wiring and the
 * skip switch.
 */
@CacheableTask
public abstract class SizeBudgetCheckTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val report: RegularFileProperty

    /** Absent until somebody writes one. Absent is not the same as unchanged, and the gate says so. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val baseline: RegularFileProperty

    @get:Input
    @get:Optional
    public abstract val budget: Property<Long>

    @get:Input
    @get:Optional
    public abstract val deltaPerChange: Property<Double>

    @get:Input
    public abstract val measure: Property<Measure>

    @get:Input
    public abstract val baselineTaskName: Property<String>

    @get:Input
    public abstract val rows: Property<Int>

    /**
     * Off, and saying so.
     *
     * A silent bypass property becomes a repository's default state within a quarter and nobody
     * remembers it is set. This one logs at `lifecycle`, in the same place the report would have
     * appeared, so its absence is visible.
     */
    @get:Input
    public abstract val skipped: Property<Boolean>

    @get:OutputFile
    public abstract val verdict: RegularFileProperty

    @TaskAction
    public fun check() {
        if (skipped.get()) {
            val message = "razves: the size gate is off for this build ($SKIP_PROPERTY). Nothing was checked."
            verdict.get().asFile.writeText(message)
            logger.lifecycle(message)
            return
        }

        val current = ReportDocument.parse(report.get().asFile.readText())
        val baselineFile = baseline.orNull?.asFile?.takeIf { it.isFile }
        val result =
            Budget.check(
                BudgetRequest(
                    report = current,
                    diff = baselineFile?.let { DiffDocument.of(ReportDocument.parse(it.readText()), current) },
                    budgetBytes = budget.orNull,
                    deltaFraction = deltaPerChange.orNull,
                    measure = measure.get(),
                    baselineTaskName = baselineTaskName.get(),
                    rows = rows.get(),
                ),
            )
        verdict.get().asFile.writeText(result.message)
        if (result.breached) throw GradleException(result.message)
        logger.lifecycle("razves: ${result.message}")
    }

    public companion object {
        public const val SKIP_PROPERTY: String = "razves.skip"
    }
}
