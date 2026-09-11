package io.github.youndie.razves.gradle

import io.github.youndie.razves.report.ReportDocument
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun project(extra: String = "") {
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
                $HOST_TARGET { binaries.executable { entryPoint = "subject.main" } }
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
        val document = ReportDocument.parse(File(projectDir, "build/reports/razves/debugExecutable.json").readText())
        val linked = File(projectDir, "build/bin/$HOST_TARGET/debugExecutable/subject.kexe")
        assertEquals(linked.length(), document.fileSize, "the report is of the file the link task wrote")
        assertTrue(document.hasSymbolTable)
    }

    @Test
    fun theModulesComeFromTheLinkClasspathRatherThanFromADirectorySweep() {
        if (HOST_TARGET == null) return skipped()
        project()

        run(TASK)

        val document = ReportDocument.parse(File(projectDir, "build/reports/razves/debugExecutable.json").readText())
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

        val document = ReportDocument.parse(File(projectDir, "build/reports/razves/debugExecutable.json").readText())
        assertEquals(1, document.packageDepth)
        assertTrue(document.packages.none { it.name.contains('.') }, "depth 1 leaves no dots")
    }

    @Test
    fun aDiffWithNoBaselineNamesTheTaskThatWritesOne() {
        if (HOST_TARGET == null) return skipped()
        project()

        val result = runFailing("sizeDiffDebugExecutable")

        assertTrue(
            "sizeBaselineWriteDebugExecutable" in result.output,
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
            ":sizeBaselineWriteDebugExecutable" !in result.output,
            "anything that both verifies and rewrites its own reference passes forever",
        )
    }

    @Test
    fun aDiffAgainstAnUnchangedBaselineIsEmpty() {
        if (HOST_TARGET == null) return skipped()
        project()

        run("sizeBaselineWriteDebugExecutable")
        val committed = File(projectDir, "razves/debugExecutable.json")
        assertTrue(committed.isFile, "the baseline lands beside the sources, not in build/")
        val before = committed.readText()

        val result = run("sizeDiffDebugExecutable")

        assertEquals(TaskOutcome.SUCCESS, result.task(":sizeDiffDebugExecutable")?.outcome)
        assertTrue("nothing moved" in result.output)
        assertEquals(before, committed.readText(), "the diff never rewrites what it compares against")
    }

    @Test
    fun aDiffAfterACodeChangeNamesThePackageThatMoved() {
        // The point of the whole feature. A gate that reports only a total gets switched off the first
        // week a dependency bump trips it.
        if (HOST_TARGET == null) return skipped()
        project()
        run("sizeBaselineWriteDebugExecutable")

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

        val result = run("sizeDiffDebugExecutable")

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
    }

    @Test
    fun aBinaryUnderItsCeilingPassesCheck() {
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { budget = 500.MiB }")

        val result = run("check")

        assertEquals(TaskOutcome.SUCCESS, result.task(":$GATE")?.outcome)
        assertTrue("under a budget of" in result.output)
    }

    @Test
    fun aGrowthBudgetWithNoBaselineFailsRatherThanPassingQuietly() {
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { deltaPerChange = 3.percent }")

        val result = runFailing("check")

        assertTrue("no baseline" in result.output)
        assertTrue("sizeBaselineWriteDebugExecutable" in result.output)
    }

    @Test
    fun growthBeyondTheDeltaFailsAndNamesTheRowsThatCausedIt() {
        // The whole argument for the gate. A total gives the reader nothing to decide with.
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { deltaPerChange = 0.percent }")
        run("sizeBaselineWriteDebugExecutable")
        addAPackage()

        val result = runFailing("check")

        assertTrue("grew more than its budget allows" in result.output)
        assertTrue("BY PACKAGE" in result.output)
        assertTrue("subject.extra" in result.output, "the package that caused it is named")
    }

    @Test
    fun aBreachLeavesTheBaselineAlone() {
        if (HOST_TARGET == null) return skipped()
        project(extra = "binarySize { deltaPerChange = 0.percent }")
        run("sizeBaselineWriteDebugExecutable")
        val committed = File(projectDir, "razves/debugExecutable.json")
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
        assertTrue(File(projectDir, "build/reports/razves/debugExecutable.json").isFile)
    }

    private fun skipped() {
        println("SKIPPED SizeReportTaskTest: this host has no Kotlin/Native target razves can link here.")
    }

    private companion object {
        const val TASK = "sizeReportDebugExecutable"
        const val GATE = "sizeBudgetCheckDebugExecutable"

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
    }
}
