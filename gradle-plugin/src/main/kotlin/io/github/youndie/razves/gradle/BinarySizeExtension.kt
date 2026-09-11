package io.github.youndie.razves.gradle

import org.gradle.api.provider.Property

/**
 * ```kotlin
 * binarySize {
 *     packageDepth = 3
 * }
 * ```
 *
 * The budget and the delta arrive with [B-14](../../../../../../../docs/backlog/B-14-budget-gate.md);
 * what is here is what the report itself needs.
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
}
