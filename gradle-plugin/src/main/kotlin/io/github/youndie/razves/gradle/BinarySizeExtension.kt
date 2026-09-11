package io.github.youndie.razves.gradle

import io.github.youndie.razves.report.Measure
import org.gradle.api.provider.Property

/**
 * ```kotlin
 * binarySize {
 *     budget = 50.MiB
 *     deltaPerChange = 3.percent
 * }
 * ```
 *
 * Both rules are optional and independent. A repository may want only a ceiling - a firmware-style
 * constraint - or only a delta, which is the "do not let it creep" one; configuring neither is a
 * decision rather than an oversight, and the report still runs.
 */
public abstract class BinarySizeExtension {
    /**
     * How many segments of a package name a row carries.
     *
     * Not cosmetic: measured on a real release binary, depth 1 collapses everything into four rows
     * and full depth produces hundreds. Three is what makes a table a person reads.
     */
    public abstract val packageDepth: Property<Int>

    /** How many rows of each table the console report prints. The JSON always carries all of them. */
    public abstract val rows: Property<Int>

    /**
     * Where the committed baseline lives, relative to the project directory.
     *
     * A directory rather than a file, because one project can have several executables and each needs
     * its own. It is meant to be committed: a baseline that is not in the repository is a baseline
     * that says something different on every machine, which is not a thing to fail a build on.
     */
    public abstract val baselineDirectory: Property<String>

    /**
     * An absolute ceiling. `50.MiB`.
     *
     * It applies to the number [measure] names, and it is measured on the **link output** - before
     * whatever packaging strips or packs it. A repository that strips afterwards is being held
     * against a figure 19-21% larger than what it ships, which is a real trap and has to be set with
     * open eyes.
     */
    public abstract val budget: Property<Long>

    /**
     * Growth against the committed baseline, as a fraction. `3.percent`.
     *
     * Needs a baseline; without one the gate **fails** rather than passing, because treating a
     * missing reference as zero growth is how a gate ends up green for a year while measuring
     * nothing.
     */
    public abstract val deltaPerChange: Property<Double>

    /**
     * Which number the rules apply to.
     *
     * [Measure.FILE_SIZE] by default, because it is the number that ends up in the argument. It is
     * also the jumpiest: the symbol table is a fifth of a Kotlin/Native binary and grows with every
     * symbol name added. Whether that is the right default is
     * [B-20](../../../../../../../docs/backlog/B-20-decide-the-budget-unit.md)'s question, and it is
     * a question about data that does not exist yet.
     */
    public abstract val measure: Property<Measure>

    /** `50.MiB` */
    public val Int.MiB: Long get() = this * 1024L * 1024L

    /** `512.KiB` */
    public val Int.KiB: Long get() = this * 1024L

    /** `3.percent` */
    public val Int.percent: Double get() = this / 100.0
}
