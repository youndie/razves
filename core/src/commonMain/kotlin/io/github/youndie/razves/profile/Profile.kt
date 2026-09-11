package io.github.youndie.razves.profile

import io.github.youndie.razves.attribute.Mangling
import io.github.youndie.razves.attribute.Origin
import io.github.youndie.razves.read.BinaryImage
import io.github.youndie.razves.read.SectionKind

/**
 * What a profile says, in the units the size report already speaks.
 *
 * **Self and total are both here because they answer different questions.** A row's `self` counts the
 * samples whose *leaf* frame was in it - where the program was actually executing. Its `total` counts
 * the samples whose stack passed through it anywhere, which is what "this dependency costs 40% of
 * the run" means. A profiler that prints one and calls it the other is the reason people distrust
 * profilers.
 *
 * **`total` counts a row once per stack, not once per frame.** Recursion would otherwise let one
 * sample add ten to a row and make the totals exceed the samples taken - a number no reader can
 * interpret and no invariant can catch afterwards.
 */
public data class ProfileRow(
    val name: String,
    val self: Long,
    val total: Long,
)

/**
 * The aggregation: stacks of addresses become rows a Kotlin developer can act on.
 *
 * **This is why the profiler is built on razves rather than beside it.** The mangling grammar, the
 * package fold, the klib module map and the ambiguity rules already exist here and are tested
 * against real binaries; every one of them is reached through the same functions the size report
 * uses, so the two cannot disagree about which package an address is in.
 *
 * **Nothing is dropped, and the two ways of having no owner are different rows.** An address inside
 * an allocated section that no symbol covers is [NO_SYMBOL] - the same situation the size report
 * calls `unattributed`, measured at 2.3% of `.text` addresses on a real release binary. An address
 * outside every section of this binary is [OUTSIDE] - libc, the dynamic loader, a shared library -
 * and on a sampled stack there are always some: three frames of eight in the first stack the spike
 * captured. Folding either into the nearest Kotlin frame would invent a hot function nobody called.
 */
public class Profile internal constructor(
    public val binary: String,
    /** Stacks aggregated. Each contributes exactly one `self` and one or more `total`. */
    public val samples: Long,
    /** What the sampler could not keep. Carried so a short profile cannot read as a complete one. */
    public val dropped: Long,
    /** Every frame of every stack, before deduplication. */
    public val frames: Long,
    public val origins: List<ProfileRow>,
    public val packages: List<ProfileRow>,
    public val modules: List<ProfileRow>,
) {
    init {
        // THE IDENTITY, and it is a constructor invariant for the same reason the size report has
        // three: a split that does not add up is a split quietly losing samples, and by the time it
        // reaches a percentage nobody can tell.
        val selfByOrigin = origins.sumOf { it.self }
        require(selfByOrigin == samples) {
            "$binary: the origin rows account for $selfByOrigin samples of $samples. Every stack has " +
                "exactly one leaf, so every sample belongs to exactly one origin row - including the " +
                "rows for addresses nothing owns."
        }
        val selfByPackage = packages.sumOf { it.self }
        require(selfByPackage == samples) {
            "$binary: the package rows account for $selfByPackage samples of $samples"
        }
        require(samples >= 0 && dropped >= 0 && frames >= samples) {
            "$binary: $frames frames cannot be fewer than $samples samples"
        }
    }

    /** The share of samples whose leaf razves could name at all. Printed beside every conclusion. */
    public val namedShare: Double
        get() {
            if (samples == 0L) return 0.0
            val unnamed = origins.filter { it.name == NO_SYMBOL || it.name == OUTSIDE }.sumOf { it.self }
            return (samples - unnamed).toDouble() / samples
        }

    public companion object {
        /** Inside this binary, and no symbol covers it. The size report calls the same thing `unattributed`. */
        public const val NO_SYMBOL: String = "<no symbol owns this address>"

        /** Not in this binary at all: libc, the loader, a shared library. Always present in a real stack. */
        public const val OUTSIDE: String = "<outside the binary>"
    }
}

/** Where a sampled address turned out to be. Kept as a type so that the two kinds of miss stay apart. */
internal sealed interface Site {
    data class Named(
        val origin: Origin,
        val symbol: String,
    ) : Site

    data object NoSymbol : Site

    data object Outside : Site
}

/**
 * The allocated address ranges of one binary, so that "not in any section" is a fact rather than a
 * guess about how large a binary is.
 */
internal class Loaded(
    image: BinaryImage,
) {
    private val ranges =
        image.sections
            .filter { it.kind == SectionKind.ALLOCATED || it.kind == SectionKind.ALLOCATED_NOBITS }
            .filter { it.size > 0 }
            .map { it.address to it.address + it.size }
            .sortedBy { it.first }

    fun contains(address: Long): Boolean = ranges.any { address >= it.first && address < it.second }
}

internal fun originOf(symbolName: String): Origin = Mangling.originOf(symbolName)
