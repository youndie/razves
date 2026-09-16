package io.github.youndie.razves.gradle

import io.github.youndie.razves.report.ReportDocument
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plugin against a project that actually links a Kotlin/Native binary.
 *
 * A unit test of the task would check that razves can read a file razves wrote, which is `core`'s
 * job and already done. What is only checkable here is the part that is the plugin's reason to
 * exist: that the report describes **the binary the link task produced** and is attributed against
 * **the klibs that link used** - neither of which can be recovered from a directory.
 *
 * It is slow because it links. It skips itself, by name, on a host that cannot.
 */
class SizeReportTaskTest {
    @TempDir
    lateinit var projectDir: File

    private fun project(
        extra: String = "",
        targets: List<String?> = listOf(HOST_TARGET),
    ) {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "subject"
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement { repositories { mavenCentral() } }
            """.trimIndent(),
        )
        File(
            projectDir,
            "gradle.properties",
        ).writeText("org.gradle.jvmargs=-Xmx2g\nkotlin.native.ignoreDisabledTargets=true\n")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("multiplatform") version "$KOTLIN_VERSION"
                id("io.github.youndie.razves")
            }
            kotlin {
                ${targets.joinToString("\n    ") { "$it { binaries.executable { entryPoint = \"subject.main\" } }" }}
                sourceSets.commonMain.dependencies {
                    implementation("org.jetbrains.kotlinx:kotlinx-datetime:$DATETIME_VERSION")
                }
            }
            $extra
            """.trimIndent(),
        )
        val source = File(projectDir, "src/commonMain/kotlin/subject")
        source.mkdirs()
        // A dependency with a package of its own, so the module table has something in it that the
        // application did not write - which is what distinguishes "read the classpath" from "read the
        // project".
        File(source, "Main.kt").writeText(
            """
            package subject

            import kotlinx.datetime.LocalDate

            fun main() {
                println(LocalDate(2026, 9, 11).dayOfYear)
            }
            """.trimIndent(),
        )
    }

    private fun runFailing(vararg arguments: String) =
        GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments)
            .forwardOutput()
            .buildAndFail()

    private fun run(vararg arguments: String) =
        GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()
            .build()

    @Test
    fun theReportDescribesTheBinaryTheLinkTaskProduced() {
        if (HOST_TARGET == null) return skipped()
        project()

        val result = run(TASK)

        assertEquals(TaskOutcome.SUCCESS, result.task(":$TASK")?.outcome)
        val document = ReportDocument.parse(File(projectDir, REPORT_JSON).readText())
        val linked = File(projectDir, "build/bin/$HOST_TARGET/debugExecutable/subject.kexe")
        assertEquals(linked.length(), document.fileSize, "the report is of the file the link task wrote")
        assertTrue(document.hasSymbolTable)
    }

    @Test
    fun theModulesComeFromTheLinkClasspathRatherThanFromADirectorySweep() {
        if (HOST_TARGET == null) return skipped()
        project()

        run(TASK)

        val document = ReportDocument.parse(File(projectDir, REPORT_JSON).readText())
        assertTrue(document.moduleAttribution, "the plugin always has the classpath, so it never stops at packages")
        val modules = document.modules.map { it.name }
        assertTrue(
            modules.any { it.contains("kotlinx-datetime") },
            "the dependency the subject uses should be a module row; got $modules",
        )
        // The failure this is guarding: a klib set carrying both a published library and a build
        // intermediate of it makes every package that library declares look declared twice. On the
        // link classpath there is exactly one of each.
        val ambiguous = document.modules.filter { it.kind == "AMBIGUOUS" }
        assertTrue(
            ambiguous.sumOf { it.bytes } * 4 < document.origins.single { it.name == "KOTLIN" }.bytes,
            "most of the Kotlin should resolve to one module; ambiguous rows: ${ambiguous.map { it.name }}",
        )
    }

    @Test
    fun theTaskIsUpToDateOnASecondRunAndCacheable() {
        if (HOST_TARGET == null) return skipped()
        project()

        run(TASK)
        val second = run(TASK)

        assertEquals(
            TaskOutcome.UP_TO_DATE,
            second.task(":$TASK")?.outcome,
            "a report that reruns on every build is a report people move to a nightly job",
        )
    }

    @Test
    fun theConfigurationCacheIsReused() {
        if (HOST_TARGET == null) return skipped()
        project()

        run(TASK, "--configuration-cache")
        val second = run(TASK, "--configuration-cache")

        assertTrue(
            "Reusing configuration cache" in second.output,
            "resolving a configuration while the build is being configured breaks this, and every " +
                "repository in this portfolio runs with it on",
        )
    }

    @Test
    fun thePackageDepthComesFromTheExtension() {
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { packageDepth = 1 }")

        run(TASK)

        val document = ReportDocument.parse(File(projectDir, REPORT_JSON).readText())
        assertEquals(1, document.packageDepth)
        assertTrue(document.packages.none { it.name.contains('.') }, "depth 1 leaves no dots")
    }

    @Test
    fun aDiffWithNoBaselineNamesTheTaskThatWritesOne() {
        if (HOST_TARGET == null) return skipped()
        project()

        val result = runFailing(DIFF)

        assertTrue(
            BASELINE_TASK in result.output,
            "a refusal that does not say what to do instead is only an obstacle",
        )
        assertTrue(
            "commit it" in result.output,
            "and a baseline nobody commits says something different on every machine",
        )
    }

    @Test
    fun theBaselineTaskIsNotPartOfCheck() {
        if (HOST_TARGET == null) return skipped()
        project()

        val result = run("check", "--dry-run")

        assertTrue(
            ":$BASELINE_TASK" !in result.output,
            "anything that both verifies and rewrites its own reference passes forever",
        )
    }

    @Test
    fun aDiffAgainstAnUnchangedBaselineIsEmpty() {
        if (HOST_TARGET == null) return skipped()
        project()

        run(BASELINE_TASK)
        val committed = File(projectDir, BASELINE)
        assertTrue(committed.isFile, "the baseline lands beside the sources, not in build/")
        val before = committed.readText()

        val result = run(DIFF)

        assertEquals(TaskOutcome.SUCCESS, result.task(":$DIFF")?.outcome)
        assertTrue("nothing moved" in result.output)
        assertEquals(before, committed.readText(), "the diff never rewrites what it compares against")
    }

    @Test
    fun aDiffAfterACodeChangeNamesThePackageThatMoved() {
        // The point of the whole feature. A gate that reports only a total gets switched off the first
        // week a dependency bump trips it.
        if (HOST_TARGET == null) return skipped()
        project()
        run(BASELINE_TASK)

        // A package the baseline has never heard of, with a body big enough to leave a mark, and
        // reachable from `main` - Kotlin/Native eliminates what nothing calls.
        File(projectDir, "src/commonMain/kotlin/subject/Extra.kt").writeText(
            listOf(
                "package subject.extra",
                "",
                "fun churn(seed: Int): Int {",
                "    var acc = seed",
                "    for (i in 1..64) acc = acc * 31 + i * i - (acc shr 3)",
                "    return acc",
                "}",
            ).joinToString("\n"),
        )
        File(projectDir, "src/commonMain/kotlin/subject/Main.kt").let { main ->
            main.writeText(
                main.readText().replace(
                    "println(LocalDate(2026, 9, 11).dayOfYear)",
                    "println(LocalDate(2026, 9, 11).dayOfYear + subject.extra.churn(3))",
                ),
            )
        }

        val result = run(DIFF)

        assertTrue("BY PACKAGE" in result.output)
        assertTrue("subject.extra" in result.output, "the new package is named, not merely counted")
        assertTrue("new" in result.output)
    }

    @Test
    fun aBinaryOverItsCeilingFailsTheBuildAndNamesWhatIsInIt() {
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { budget = 1.KiB }")

        val result = runFailing("check")

        assertTrue("over its size budget" in result.output)
        assertTrue("over by:" in result.output)
        assertTrue("The largest things in it:" in result.output, "a ceiling can be breached on the first build")
        assertEquals(
            TaskOutcome.FAILED,
            result.task(":$RELEASE_GATE")?.outcome,
            "the ceiling is the release binary's, because the release binary is the one that ships",
        )
    }

    @Test
    fun aBinaryUnderItsCeilingPassesCheck() {
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { budget = 500.MiB }")

        val result = run("check")

        assertEquals(TaskOutcome.SUCCESS, result.task(":$RELEASE_GATE")?.outcome)
        assertTrue("under a budget of" in result.output)
    }

    @Test
    fun aCeilingDoesNotApplyToTheDebugBinaryThatNothingShips() {
        // The defect. One `budget` was applied to every executable, so a service setting the ceiling
        // for what it puts in an image was also holding its debug binary to it - and the debug binary
        // of the module this was measured on is 28,580,560 bytes against 9,227,448 for the release
        // one, 3.1x. The only ceiling that lets the build through is therefore one chosen for debug,
        // and a ceiling that admits 28.6 MB is no longer watching the 9.2 MB artefact at all.
        //
        // 1.KiB rather than a realistic number on purpose: under the old behaviour *nothing* passes
        // this, so the test fails if the split is ever undone.
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { budget = 1.KiB }")

        val result = run(GATE)

        assertEquals(TaskOutcome.SUCCESS, result.task(":$GATE")?.outcome)
        assertTrue(
            "nothing was checked" in result.output,
            "and it says so - an ungated binary and a binary under budget are the same green task otherwise",
        )
        assertTrue("debug {" in result.output, "naming the block that would gate it")
    }

    @Test
    fun aDebugCeilingIsOptedIntoAndThenItApplies() {
        // The escape hatch, and the reason the default is a default rather than a refusal. A rule
        // nobody can turn back on is a rule that gets worked around by raising the other number.
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { budget = 500.MiB; debug { budget = 1.KiB } }")

        val result = runFailing(GATE)

        assertEquals(
            TaskOutcome.FAILED,
            result.task(":$GATE")?.outcome,
            "the release binary is well under its own 500 MiB, so this can only be the debug gate",
        )
        assertTrue("over its size budget" in result.output)
    }

    @Test
    fun aDebugRuleDoesNotLeakOntoTheReleaseBinary() {
        // The same wall from the other side: `debug { }` is the debug binaries' block and nothing
        // else's, so a number put there cannot fail the build for the artefact that ships.
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { debug { budget = 500.MiB } }")

        val result = run(RELEASE_GATE)

        assertEquals(TaskOutcome.SUCCESS, result.task(":$RELEASE_GATE")?.outcome)
        assertTrue(
            "nothing was checked" in result.output,
            "the release binary has no rule here, and a gate that checked nothing says so",
        )
        assertTrue("binarySize { budget" in result.output, "and names the block that would gate it")
    }

    @Test
    fun aGrowthBudgetWithNoBaselineFailsRatherThanPassingQuietly() {
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { debug { deltaPerChange = 3.percent } }")

        val result = runFailing("check")

        assertTrue("no baseline" in result.output)
        assertTrue(BASELINE_TASK in result.output)
    }

    @Test
    fun growthBeyondTheDeltaFailsAndNamesTheRowsThatCausedIt() {
        // The whole argument for the gate. A total gives the reader nothing to decide with.
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { debug { deltaPerChange = 0.percent } }")
        run(BASELINE_TASK)
        addAPackage()

        val result = runFailing("check")

        assertTrue("grew more than its budget allows" in result.output)
        assertTrue("BY PACKAGE" in result.output)
        assertTrue("subject.extra" in result.output, "the package that caused it is named")
    }

    @Test
    fun aBreachLeavesTheBaselineAlone() {
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { debug { deltaPerChange = 0.percent } }")
        run(BASELINE_TASK)
        val committed = File(projectDir, BASELINE)
        val before = committed.readText()
        addAPackage()

        runFailing("check")

        assertEquals(before, committed.readText(), "a gate that updates its own baseline passes forever")
    }

    @Test
    fun turningTheGateOffSaysSo() {
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { budget = 1.KiB }")

        val result = run("check", "-Prazves.skip=true")

        assertTrue("the size gate is off for this build" in result.output)
        assertTrue("razves.skip" in result.output, "a silent bypass becomes the default state within a quarter")
    }

    @Test
    fun twoNativeTargetsInOneModuleEachGetTheirOwnTasks() {
        // The defect this file could not see. Every other test declares one target, and the task
        // names were built from `Executable.name` alone - `debugExecutable`, which is what the binary
        // is called under *every* target. A module with two of them failed before any task ran:
        // "Cannot add task 'sizeReportDebugExecutable' as a task with that name already exists".
        //
        // Nothing here links, and it must not: declaring the second target is the whole of the
        // reproduction, which is why the test can name a target this host cannot build.
        if (HOST_TARGET == null) return skipped()
        project(targets = listOf(HOST_TARGET, OTHER_TARGET))

        val result = run("tasks")

        for (target in listOf(HOST_TARGET, OTHER_TARGET)) {
            for (prefix in listOf("sizeReport", "sizeBudgetCheck", "sizeBaselineWrite", "sizeDiff")) {
                val task = taskName(prefix, target)
                assertTrue(task in result.output, "$task was not registered")
            }
        }
    }

    @Test
    fun eachTargetWritesItsOwnReportAndItsOwnBaseline() {
        // The half that would have stayed quiet. Had only the task names carried the target, both
        // targets would still have written `razves/debugExecutable.json` and
        // `build/reports/razves/debugExecutable.json` - the second overwriting the first, a build
        // that looks like it worked while the baseline describes whichever target happened to run
        // last. That is worse than the configuration failure, not better.
        //
        // Read off the wiring rather than off the disk, because this host can link one of the two:
        // where a report goes is decided when the task is registered.
        if (HOST_TARGET == null) return skipped()
        project(targets = listOf(HOST_TARGET, OTHER_TARGET), extra = PRINT_PATHS)

        val result = run("sizePaths")

        val paths =
            result.output
                .lineSequence()
                .filter { it.startsWith(MARK) }
                .map { it.removePrefix(MARK).trim() }
                .toList()
        // Two targets, two build types, a report and a baseline each.
        assertEquals(8, paths.size, "one report and one baseline per binary; got $paths")
        assertEquals(paths.size, paths.toSet().size, "no two of them may be the same file: $paths")
        for (target in listOf(HOST_TARGET, OTHER_TARGET)) {
            for (binary in listOf("debugExecutable", "releaseExecutable")) {
                assertTrue(
                    paths.any { it.endsWith("build/reports/razves/$target/$binary.json") },
                    "no report path for $target's $binary in $paths",
                )
                assertTrue(
                    paths.any { it.endsWith("razves/$target/$binary.json") && "/build/" !in it },
                    "no committed baseline path for $target's $binary in $paths",
                )
            }
        }
    }

    @Test
    fun aTargetThisHostCannotLinkIsSkippedRatherThanFailingCheck() {
        // The other thing a second target brings, and the reason the names alone are not the whole
        // fix: one of the two targets in a real multi-target module cannot be linked on this machine.
        // The Kotlin Gradle Plugin disables that target's link task, so the binary razves is pointed
        // at is never produced - and a gate in `check` that fails with "Input file does not exist"
        // on every mac-less machine is a gate every such machine turns off.
        //
        // This one links, so it is the host's own target that is measured; the other is only there.
        val unlinkable = UNLINKABLE_TARGET ?: return skipped("every target this host declares is one it can build")
        project(targets = listOf(HOST_TARGET, unlinkable), extra = "binarySize { budget = 500.MiB }")

        val result = run("check")

        assertEquals(TaskOutcome.SUCCESS, result.task(":$GATE")?.outcome, "the host's own gate still runs")
        assertEquals(
            TaskOutcome.SKIPPED,
            result.task(":${taskName("sizeBudgetCheck", unlinkable)}")?.outcome,
            "and the other target's gate skips, the way its link task does",
        )
    }

    /** One new package with a body big enough to leave a mark, reachable from `main`. */
    private fun addAPackage() {
        File(projectDir, "src/commonMain/kotlin/subject/Extra.kt").writeText(
            listOf(
                "package subject.extra",
                "",
                "fun churn(seed: Int): Int {",
                "    var acc = seed",
                "    for (i in 1..64) acc = acc * 31 + i * i - (acc shr 3)",
                "    return acc",
                "}",
            ).joinToString("\n"),
        )
        File(projectDir, "src/commonMain/kotlin/subject/Main.kt").let { main ->
            main.writeText(
                main.readText().replace(
                    "println(LocalDate(2026, 9, 11).dayOfYear)",
                    "println(LocalDate(2026, 9, 11).dayOfYear + subject.extra.churn(3))",
                ),
            )
        }
    }

    @Test
    fun razvesIsConsumableByCoordinateRatherThanOnlyByClasspath() {
        // Every other test here hands the plugin over with `withPluginClasspath()`, which proves the
        // code works and nothing about whether the PUBLISHED artifact does. This one resolves razves
        // the way a repository would - by id, through a resolver, out of a repository this build
        // published into - which is the thing B-17 needs before a sborka convention can apply it.
        if (HOST_TARGET == null) return skipped()
        val repository = System.getProperty("RAZVES_TEST_REPOSITORY") ?: return skipped()
        val version = System.getProperty("RAZVES_VERSION") ?: return skipped()

        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "subject"
            pluginManagement {
                repositories {
                    maven { url = uri("$repository") }
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven { url = uri("$repository") }
                    mavenCentral()
                }
            }
            """.trimIndent(),
        )
        File(projectDir, "gradle.properties").writeText("org.gradle.jvmargs=-Xmx2g\n")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("multiplatform") version "$KOTLIN_VERSION"
                id("io.github.youndie.razves") version "$version"
            }
            kotlin { $HOST_TARGET { binaries.executable { entryPoint = "subject.main" } } }
            """.trimIndent(),
        )
        val source = File(projectDir, "src/commonMain/kotlin/subject")
        source.mkdirs()
        File(source, "Main.kt").writeText("package subject\n\nfun main() = println(1)\n")

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withArguments(TASK)
                .forwardOutput()
                .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":$TASK")?.outcome)
        assertTrue(File(projectDir, REPORT_JSON).isFile)
    }

    @Test
    fun aProjectWithNoKotlinPluginAppliesRazvesAndSimplyGetsNoSizeTasks() {
        // Every other test here builds a project that HAS the Kotlin plugin, because that is the only
        // kind of project razves is useful in - so the one thing none of them can see is what happens
        // when it is absent. And `withPluginClasspath()` cannot see it either: the plugin-under-test
        // metadata carries KGP, so the class resolves there whatever the plugin does. By id, out of a
        // repository, is the only shape in which this is a question at all.
        val repository = System.getProperty("RAZVES_TEST_REPOSITORY") ?: return skipped()
        val version = System.getProperty("RAZVES_VERSION") ?: return skipped()
        byId(repository, version, "plugins { base\n    id(\"io.github.youndie.razves\") version \"$version\" }")

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withArguments("tasks")
                .forwardOutput()
                .build()

        // `build()` fails the test if the build does, which is the half that used to crash.
        assertFalse("sizeReport" in result.output, "no binary, no report - and no failure either")
    }

    @Test
    fun razvesAppliedBeforeTheKotlinPluginStillGetsItsTasks() {
        // The order in a `plugins` block is the author's, not razves'. Registration hangs off
        // `plugins.withId`, which fires whenever the other plugin arrives - before or after.
        if (HOST_TARGET == null) return skipped()
        val repository = System.getProperty("RAZVES_TEST_REPOSITORY") ?: return skipped()
        val version = System.getProperty("RAZVES_VERSION") ?: return skipped()
        byId(
            repository,
            version,
            """
            plugins {
                id("io.github.youndie.razves") version "$version"
                kotlin("multiplatform") version "$KOTLIN_VERSION"
            }
            kotlin { $HOST_TARGET { binaries.executable { entryPoint = "subject.main" } } }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withArguments("tasks")
                .forwardOutput()
                .build()

        assertTrue(TASK in result.output, result.output)
        assertTrue(GATE in result.output)
    }

    /** A project that resolves razves by id from the repository this build published into. */
    private fun byId(
        repository: String,
        version: String,
        buildScript: String,
    ) {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "subject"
            pluginManagement {
                repositories {
                    maven { url = uri("$repository") }
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven { url = uri("$repository") }
                    mavenCentral()
                }
            }
            """.trimIndent(),
        )
        File(projectDir, "gradle.properties").writeText("org.gradle.jvmargs=-Xmx2g\n")
        File(projectDir, "build.gradle.kts").writeText(buildScript)
    }

    private fun skipped(reason: String = "this host has no Kotlin/Native target razves can link here") {
        println("SKIPPED SizeReportTaskTest: $reason.")
    }

    private companion object {
        /** What [eachTargetWritesItsOwnReportAndItsOwnBaseline] greps the build's output for. */
        const val MARK = "SIZE-PATH"

        /**
         * A task that prints where every report and every baseline was wired to go. Written with
         * string concatenation rather than interpolation because it lives inside a Kotlin raw string
         * here and is a Kotlin build script there, and both read `$`.
         */
        val PRINT_PATHS =
            """
            val sizePaths =
                tasks.withType(io.github.youndie.razves.gradle.SizeReportTask::class.java).map {
                    it.json.get().asFile.path
                } + tasks.withType(io.github.youndie.razves.gradle.SizeBaselineWriteTask::class.java).map {
                    it.baseline.get().asFile.path
                }
            tasks.register("sizePaths") {
                doLast { sizePaths.forEach { println("$MARK " + it) } }
            }
            """.trimIndent()

        /** Pinned rather than read from the catalog: the test project is a separate build. */
        const val KOTLIN_VERSION = "2.4.10"
        const val DATETIME_VERSION = "0.8.0"

        /**
         * The one target this host can link. A Linux runner cannot produce a Mach-O and a mac cannot
         * be relied on to have the Linux toolchain warmed, so the test builds for the host it is on.
         */
        val HOST_TARGET: String? =
            when {
                System.getProperty("os.name").startsWith("Mac") &&
                    System.getProperty("os.arch") == "aarch64" -> "macosArm64"

                System.getProperty("os.name") == "Linux" &&
                    System.getProperty("os.arch") in setOf("amd64", "x86_64") -> "linuxX64"

                else -> null
            }

        /**
         * The other one - declared alongside [HOST_TARGET] by the two-target tests, and never linked.
         * Declaring a target is a configuration-time act, which is all those tests are about.
         */
        val OTHER_TARGET: String = if (HOST_TARGET == "linuxX64") "macosArm64" else "linuxX64"

        /**
         * A target this host cannot link **at all**, or null when it has none.
         *
         * Apple targets do not cross-compile, so a Linux machine can never produce `macosArm64` and
         * the Kotlin Gradle Plugin disables its link task - which is the condition
         * [aTargetThisHostCannotLinkIsSkippedRatherThanFailingCheck] is about. A mac has no such
         * target: its own Kotlin/Native distribution ships `linux_x64` and `mingw_x64` alongside the
         * Apple ones, so on a mac there is nothing to assert and that test says so rather than
         * asserting something it has arranged itself.
         */
        val UNLINKABLE_TARGET: String? = if (HOST_TARGET == "linuxX64") "macosArm64" else null

        /**
         * Every task name and every path now carries the target, so what this test drives depends on
         * the host it runs on. Each test that reads these skips first when there is no host target,
         * and then the names are of the empty target and unused.
         */
        val TASK = taskName("sizeReport", HOST_TARGET)
        val GATE = taskName("sizeBudgetCheck", HOST_TARGET)
        val BASELINE_TASK = taskName("sizeBaselineWrite", HOST_TARGET)
        val DIFF = taskName("sizeDiff", HOST_TARGET)

        /** The other build type, which is the one `budget` and `deltaPerChange` now mean. */
        val RELEASE_GATE = taskName("sizeBudgetCheck", HOST_TARGET, "ReleaseExecutable")

        /** One directory per target, in `build/reports/razves/` and in the committed `razves/`. */
        val REPORT_JSON = "build/reports/razves/$HOST_TARGET/debugExecutable.json"
        val BASELINE = "razves/$HOST_TARGET/debugExecutable.json"

        fun taskName(
            prefix: String,
            target: String?,
            binary: String = "DebugExecutable",
        ) = prefix + target.orEmpty().replaceFirstChar { it.uppercase() } + binary
    }
}
