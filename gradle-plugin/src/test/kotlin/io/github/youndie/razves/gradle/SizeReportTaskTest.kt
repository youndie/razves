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

    private fun skipped() {
        println("SKIPPED SizeReportTaskTest: this host has no Kotlin/Native target razves can link here.")
    }

    private companion object {
        const val TASK = "sizeReportDebugExecutable"

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
