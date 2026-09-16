package io.github.youndie.razves.gradle

import org.gradle.api.provider.Property

/**
 * The two gate rules, for one kind of binary.
 *
 * Both are optional and independent: a repository may want only a ceiling - a firmware-style
 * constraint - or only a delta, which is the "do not let it creep" one. Setting neither is a
 * decision rather than an oversight, and the gate then says out loud that it checked nothing.
 *
 * **It is a type rather than two more properties on the extension because a rule is per build
 * type.** A ceiling that fits what a service ships is roughly a third of what its debug binary
 * weighs - measured on one module at one commit, 9,227,448 against 28,580,560 - so one number
 * applied to both is a number chosen for debug, and the artefact that actually ships could triple
 * before the build noticed. See [BinarySizeExtension.debug].
 */
public abstract class SizeRules {
    /**
     * An absolute ceiling. `50.MiB`.
     *
     * It applies to the number [BinarySizeExtension.measure] names, and it is measured on the **link
     * output** - before whatever packaging strips or packs it. A repository that strips afterwards is
     * being held against a figure 19-21% larger than what it ships, which is a real trap and has to
     * be set with open eyes.
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

    /** `50.MiB` */
    public val Int.MiB: Long get() = this * 1024L * 1024L

    /** `512.KiB` */
    public val Int.KiB: Long get() = this * 1024L

    /** `3.percent` */
    public val Int.percent: Double get() = this / 100.0
}
