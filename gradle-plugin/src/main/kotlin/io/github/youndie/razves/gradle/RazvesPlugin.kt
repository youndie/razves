package io.github.youndie.razves.gradle

import io.github.youndie.razves.report.Measure
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * `sizeReport<Target><BuildType>` for every Kotlin/Native executable in the project.
 *
 * **Registration happens through `all { }` at plugin-application time, not in `afterEvaluate`.**
 * Targets and binaries are declared after the `plugins` block, and a convention plugin applied later
 * can add one after that — `afterEvaluate` is a single pass over whatever happened to exist when it
 * ran, and the binary added by the next plugin is the one nobody notices is missing a report. The
 * container's own `all { }` fires for what is there now and for what arrives later, which is the
 * only spelling of "every binary" that stays true.
 */
public class RazvesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("binarySize", BinarySizeExtension::class.java)
        extension.packageDepth.convention(DEFAULT_PACKAGE_DEPTH)
        extension.rows.convention(DEFAULT_ROWS)
        extension.baselineDirectory.convention(DEFAULT_BASELINE_DIRECTORY)
        extension.measure.convention(Measure.FILE_SIZE)

        target.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val kotlin = target.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.targets.withType(KotlinNativeTarget::class.java).all { nativeTarget ->
                nativeTarget.binaries.withType(Executable::class.java).all { binary ->
                    register(target, extension, binary)
                }
            }
        }
    }

    private fun register(
        project: Project,
        extension: BinarySizeExtension,
        binary: Executable,
    ) {
        val name = "sizeReport${binary.name.replaceFirstChar { it.uppercase() }}"
        val reports = project.layout.buildDirectory.dir("reports/razves")
        val report =
            project.tasks.register(name, SizeReportTask::class.java) { task ->
                task.group = "verification"
                task.description = "What is in ${binary.name}, by origin, package and module."

                // The link task, not the compile one, and its output rather than a path guessed from the
                // layout: a hard-coded path is how a report ends up describing yesterday's binary.
                //
                // The ordering has to be said separately. `KotlinNativeLink.outputFile` is a plain
                // `Provider<File>` that carries no producer, so wiring it alone gets "Input file does not
                // exist" - the report is scheduled before the link. The provider supplies the path; the
                // explicit dependency supplies the order.
                task.binary.fileProvider(binary.linkTaskProvider.map { it.outputFile.get() })
                task.dependsOn(binary.linkTaskProvider)

                // THE LINK CLASSPATH. This is what the plugin is for - see SizeReportTask. Resolved
                // lazily: touching a configuration while the build is being configured breaks the
                // configuration cache and forces a resolution nobody asked for.
                task.klibs.from(project.provider { binary.linkTaskProvider.get().libraries })

                task.packageDepth.set(extension.packageDepth)
                task.rows.set(extension.rows)
                task.json.set(reports.map { it.file("${binary.name}.json") })
                task.text.set(reports.map { it.file("${binary.name}.txt") })
            }

        val baseline =
            extension.baselineDirectory.map { directory ->
                project.layout.projectDirectory.file("$directory/${binary.name}.json")
            }
        val baselineTaskName = "sizeBaselineWrite${binary.name.replaceFirstChar { it.uppercase() }}"

        // Not wired into `check`, and that is the whole of it: anything that both verifies and
        // rewrites its own reference passes forever.
        project.tasks.register(baselineTaskName, SizeBaselineWriteTask::class.java) { task ->
            task.group = "verification"
            task.description = "Rewrite the committed size baseline for ${binary.name}."
            task.report.set(report.flatMap { it.json })
            task.baseline.set(baseline)
        }

        project.tasks.register(
            "sizeDiff${binary.name.replaceFirstChar { it.uppercase() }}",
            SizeDiffTask::class.java,
        ) { task ->
            task.group = "verification"
            task.description = "What moved in ${binary.name} since the committed baseline."
            task.current.set(report.flatMap { it.json })
            // Only an existing file is wired in: an absent baseline is the normal state before anybody
            // has written one, and the task then says which task to run rather than failing on a
            // missing input with Gradle's own message.
            task.baseline.fileProvider(baseline.map { it.asFile }.filter { it.isFile })
            task.baselineTaskName.set(baselineTaskName)
            task.rows.set(extension.rows)
            task.text.set(reports.map { it.file("${binary.name}-diff.txt") })
        }

        val gate =
            project.tasks.register(
                "sizeBudgetCheck${binary.name.replaceFirstChar { it.uppercase() }}",
                SizeBudgetCheckTask::class.java,
            ) { task ->
                task.group = "verification"
                task.description = "Fail the build if ${binary.name} is over budget or grew too much."
                task.report.set(report.flatMap { it.json })
                task.baseline.fileProvider(baseline.map { it.asFile }.filter { it.isFile })
                task.budget.set(extension.budget)
                task.deltaPerChange.set(extension.deltaPerChange)
                task.measure.set(extension.measure)
                task.baselineTaskName.set(baselineTaskName)
                task.rows.set(extension.rows)
                task.skipped.set(
                    project.providers
                        .gradleProperty(SizeBudgetCheckTask.SKIP_PROPERTY)
                        .map { it != "false" }
                        .orElse(false),
                )
                task.verdict.set(reports.map { it.file("${binary.name}-budget.txt") })
            }

        // In `check`, which is the point of it - and only this one. `sizeBaselineWrite` stays out,
        // because anything that both verifies and rewrites its own reference passes forever.
        //
        // `matching` rather than `named`: a project without a lifecycle plugin has no `check` task at
        // all, and `named` on a missing task fails the configuration of every build that applies
        // razves to a module that happens not to have one.
        project.tasks.matching { it.name == "check" }.configureEach { it.dependsOn(gate) }
    }

    private companion object {
        /**
         * Three segments, measured rather than round: depth 1 collapses a real binary into four rows
         * and full depth produces hundreds.
         */
        const val DEFAULT_PACKAGE_DEPTH = 3
        const val DEFAULT_ROWS = 20

        /** Beside the sources rather than in `build/`: the point of a baseline is that it is committed. */
        const val DEFAULT_BASELINE_DIRECTORY = "razves"
    }
}
