package io.github.youndie.razves.gradle

import io.github.youndie.razves.report.Measure
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * ```kotlin
 * binarySize {
 *     budget = 25.MiB            // the release binary, which is the one that ships
 *     deltaPerChange = 3.percent
 *
 *     debug {                    // opt in, with a number of its own, or leave debug ungated
 *         budget = 40.MiB
 *     }
 * }
 * ```
 *
 * The rules themselves, and why there are two sets of them, are in [SizeRules].
 */
public abstract class BinarySizeExtension
    @Inject
    constructor(
        objects: ObjectFactory,
    ) : SizeRules() {
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
         * A directory rather than a file, because one project can have several executables and each
         * needs its own. It is meant to be committed: a baseline that is not in the repository is a
         * baseline that says something different on every machine, which is not a thing to fail a
         * build on.
         */
        public abstract val baselineDirectory: Property<String>

        /**
         * Which number the rules apply to.
         *
         * [Measure.FILE_SIZE] by default, because it is the number that ends up in the argument. It is
         * also the jumpiest: the symbol table is a fifth of a Kotlin/Native binary and grows with every
         * symbol name added. Whether that is the right default is
         * [B-20](../../../../../../../docs/backlog/B-20-decide-the-budget-unit.md)'s question, and it is
         * a question about data that does not exist yet.
         *
         * One setting for every binary, unlike the rules: it says what is being counted, not how much
         * of it is allowed.
         */
        public abstract val measure: Property<Measure>

        /**
         * Rules for the **debug** binaries, which are ungated until this block sets one.
         *
         * The inherited [budget] and [deltaPerChange] apply to release executables only, and this is
         * why. A debug binary is not a larger version of the one that ships, it is a different order of
         * size: 28,580,560 bytes against 9,227,448 for the same module at the same commit - 3.1x - and
         * only the second one is ever staged into an image. A single number covering both therefore has
         * to clear 28.6 MB, and a ceiling that admits 28.6 MB is no longer watching the 9.2 MB artefact
         * at all; what ships could triple before the build noticed. So the number a repository writes
         * without thinking about it is spent on what it ships, and debug is opted into - with its own
         * number, because it is a different number.
         *
         * Nothing is inherited here. An empty block is the same as no block: a debug gate that checks
         * nothing says so at `lifecycle`, rather than passing quietly.
         */
        public val debug: SizeRules = objects.newInstance(SizeRules::class.java)

        /** `debug { budget = 40.MiB }` */
        public fun debug(action: Action<in SizeRules>) {
            action.execute(debug)
        }
    }
