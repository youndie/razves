package io.github.youndie.razves.gradle

import io.github.youndie.razves.report.Measure
import org.gradle.api.Plugin
import org.gradle.api.Project

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

        // NOTHING WITH A KOTLIN TYPE IN IT LIVES IN THIS CLASS, and that is not tidiness. Gradle
        // decorates a plugin type when it applies it, and decoration reads the declared methods -
        // including the synthetic ones a lambda compiles to. A single `Action<Executable>` here is
        // enough to make `apply` fail with "could not generate a decorated class" in a build that
        // does not have the Kotlin plugin at all, which is a crash where the honest answer is "no
        // binaries, no reports". The guard below reads as though it prevented that; it never ran.
        //
        // So the whole of the Kotlin-facing wiring is [NativeBinaries], named only inside the block
        // that knows KGP is there, and loaded only when it is.
        target.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            NativeBinaries.wire(target, extension)
        }
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
