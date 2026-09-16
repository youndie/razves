package io.github.youndie.razves.gradle

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

/**
 * Every task razves registers, and the only place that names a Kotlin Gradle Plugin type.
 *
 * **It is a separate class so that the plugin class has no Kotlin types in it at all.** Gradle
 * decorates a plugin type on apply, and decoration reads the declared methods of the class -
 * including the synthetic ones lambdas compile to. One `Action<Executable>` in `RazvesPlugin` was
 * enough to turn "this project has no Kotlin plugin, so there is nothing to report on" into "could
 * not generate a decorated class", before the `plugins.withId` guard could decline to do anything.
 * A guard is only a guard if the class it protects is loaded no earlier than the guard passes.
 *
 * **Registration happens through `all { }` at plugin-application time, not in `afterEvaluate`.**
 * Targets and binaries are declared after the `plugins` block, and a convention plugin applied later
 * can add one after that - `afterEvaluate` is a single pass over whatever happened to exist when it
 * ran, and the binary added by the next plugin is the one nobody notices is missing a report. The
 * container's own `all { }` fires for what is there now and for what arrives later, which is the
 * only spelling of "every binary" that stays true.
 */
internal object NativeBinaries {
    fun wire(
        project: Project,
        extension: BinarySizeExtension,
    ) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.targets.withType(KotlinNativeTarget::class.java).all { nativeTarget ->
            nativeTarget.binaries.withType(Executable::class.java).all { binary ->
                register(project, extension, binary)
            }
        }
    }

    private fun register(
        project: Project,
        extension: BinarySizeExtension,
        binary: Executable,
    ) {
        // THE TARGET IS PART OF EVERY NAME HERE - TASKS, REPORTS AND THE COMMITTED BASELINE - and
        // that is not symmetry for its own sake. `Executable.name` is the build type and the output
        // kind (`debugExecutable`), which is unique within one target and identical across targets:
        // a module with `linuxX64` and `macosArm64` produced the same four task names twice and
        // failed to configure at all ("Cannot add task 'sizeReportDebugExecutable'"), and the report
        // and baseline paths would have collided silently afterwards - the second target overwriting
        // the first, which looks like it worked. `target.name` plus `binary.name` is unique by
        // construction, because that pair is what names a binary in the Kotlin Gradle Plugin too.
        val target = binary.target.name
        val name = taskName("sizeReport", binary)
        val reports = project.layout.buildDirectory.dir("reports/razves/$target")

        // A SECOND TARGET ALSO MEANS A TARGET THIS HOST CANNOT LINK, and the Kotlin Gradle Plugin
        // says so by disabling that target's link task. Its output is then never produced, so a
        // report wired to it fails with Gradle's own "Input file does not exist" - and since the gate
        // is in `check`, every Linux machine in a repository that has a `macosArm64` target would
        // fail `check` for a binary it was never going to build. razves follows the link task rather
        // than deciding for itself: whatever made that task skip, including a hand-disabled one,
        // makes its report skip too.
        val linkable = project.provider { binary.linkTaskProvider.get().enabled }
        val report =
            project.tasks.register(name, SizeReportTask::class.java) { task ->
                task.enabled = linkable.get()
                task.group = "verification"
                task.description = "What is in $target's ${binary.name}, by origin, package and module."

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
                project.layout.projectDirectory.file("$directory/$target/${binary.name}.json")
            }
        val baselineTaskName = taskName("sizeBaselineWrite", binary)

        // Not wired into `check`, and that is the whole of it: anything that both verifies and
        // rewrites its own reference passes forever.
        project.tasks.register(baselineTaskName, SizeBaselineWriteTask::class.java) { task ->
            task.enabled = linkable.get()
            task.group = "verification"
            task.description = "Rewrite the committed size baseline for $target's ${binary.name}."
            task.report.set(report.flatMap { it.json })
            task.baseline.set(baseline)
        }

        project.tasks.register(taskName("sizeDiff", binary), SizeDiffTask::class.java) { task ->
            task.enabled = linkable.get()
            task.group = "verification"
            task.description = "What moved in $target's ${binary.name} since the committed baseline."
            task.current.set(report.flatMap { it.json })
            // Only an existing file is wired in: an absent baseline is the normal state before anybody
            // has written one, and the task then says which task to run rather than failing on a
            // missing input with Gradle's own message.
            task.baseline.fileProvider(baseline.map { it.asFile }.filter { it.isFile })
            task.baselineTaskName.set(baselineTaskName)
            task.rows.set(extension.rows)
            task.text.set(reports.map { it.file("${binary.name}-diff.txt") })
        }

        // WHICH RULES APPLY TO THIS BINARY IS DECIDED BY ITS BUILD TYPE, and the debug ones are empty
        // until somebody asks for them. A debug binary is not a bigger version of the one that ships,
        // it is a different order of size - 28,580,560 against 9,227,448 for the same module at the
        // same commit - and only the release one is ever staged into an image. One number covering
        // both has to clear the debug figure, and a ceiling that admits 28.6 MB has stopped watching
        // the 9.2 MB artefact: it could triple before the build went red. So the number written
        // without a thought is spent on what ships, and debug is opted into through `debug { }`.
        val rules = if (binary.buildType == NativeBuildType.DEBUG) extension.debug else extension
        val hint = if (binary.buildType == NativeBuildType.DEBUG) DEBUG_HINT else RELEASE_HINT

        val gate =
            project.tasks.register(taskName("sizeBudgetCheck", binary), SizeBudgetCheckTask::class.java) { task ->
                task.enabled = linkable.get()
                task.group = "verification"
                task.description = "Fail the build if $target's ${binary.name} is over budget or grew too much."
                task.report.set(report.flatMap { it.json })
                task.baseline.fileProvider(baseline.map { it.asFile }.filter { it.isFile })
                task.budget.set(rules.budget)
                task.deltaPerChange.set(rules.deltaPerChange)
                task.rulesHint.set(hint)
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

    /** What to write to give this binary a rule, printed by a gate that has none to apply. */
    private const val DEBUG_HINT = "binarySize { debug { budget = 40.MiB } }"
    private const val RELEASE_HINT = "binarySize { budget = 25.MiB }"

    /** `sizeReportLinuxX64DebugExecutable`: the pair that names a binary in KGP, in razves' spelling. */
    private fun taskName(
        prefix: String,
        binary: Executable,
    ): String =
        prefix +
            binary.target.name.replaceFirstChar { it.uppercase() } +
            binary.name.replaceFirstChar { it.uppercase() }
}
