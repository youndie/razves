package io.github.youndie.razves.report

import io.github.youndie.razves.attribute.Origin
import io.github.youndie.razves.read.SectionKind

/**
 * What is in this binary, in three layers a reader can stop at.
 *
 * 1. [reconciliation] — the file split into headers, allocated sections, metadata and padding.
 * 2. [origins] — the attributed bytes split by where the code came from.
 * 3. The Kotlin bucket, split by package and module. That layer is B-07 and B-08.
 *
 * **The top level is origin, not package**, and that is a finding rather than a preference. Measured
 * over four real release binaries, Kotlin is 36–40% of the attributed bytes and statically linked C
 * is the majority. A tool whose main table covers Kotlin and calls the rest "other" answers a third
 * of the question, and the third it answers is the one you can do least about: "OpenSSL is 6.3 MB"
 * points at a Ktor engine choice, while "ktor is 1.3 MB" points at nothing.
 */
public class SizeReport internal constructor(
    public val reconciliation: Reconciliation,
    public val origins: List<OriginRow>,
    /** The Kotlin bucket, split by package. Empty when the binary carries no Kotlin at all. */
    public val packages: List<PackageRow> = emptyList(),
    /** How many segments of a package name the rows were truncated to. */
    public val packageDepth: Int = Int.MAX_VALUE,
    /**
     * The Kotlin bucket, split by the klib each package came from. Empty when no klibs were supplied,
     * and the report says so rather than leaving a reader to wonder.
     */
    public val modules: List<ModuleRow> = emptyList(),
) {
    /** False when no klibs were supplied: the report stops at package level and says which. */
    public val hasModuleAttribution: Boolean = modules.isNotEmpty()

    public val image: io.github.youndie.razves.read.BinaryImage get() = reconciliation.image

    init {
        require(origins.sumOf { it.bytes } == reconciliation.attributedBytes) {
            "the origin split does not add up to the attributed bytes of ${image.name}: " +
                "${origins.sumOf { it.bytes }} != ${reconciliation.attributedBytes}"
        }
        require(origins.map { it.origin }.distinct().size == origins.size) {
            "an origin appears twice in the report for ${image.name}"
        }
        // The fourth identity. A package split that does not add up to the Kotlin bucket is losing
        // bytes in the one layer a Kotlin developer will actually read, and losing them silently.
        require(packages.isEmpty() || packages.sumOf { it.bytes } == bytesOf(Origin.KOTLIN)) {
            "the package split of ${image.name} does not add up to its Kotlin bytes: " +
                "${packages.sumOf { it.bytes }} != ${bytesOf(Origin.KOTLIN)}"
        }
        require(packages.map { it.name }.distinct().size == packages.size) {
            "a package appears twice in the report for ${image.name}"
        }
        require(modules.isEmpty() || modules.sumOf { it.bytes } == bytesOf(Origin.KOTLIN)) {
            "the module split of ${image.name} does not add up to its Kotlin bytes: " +
                "${modules.sumOf { it.bytes }} != ${bytesOf(Origin.KOTLIN)}"
        }
        require(modules.map { it.name }.distinct().size == modules.size) {
            "a module appears twice in the report for ${image.name}"
        }
    }

    /**
     * Allocated sections that no symbol claims a single byte of, largest first.
     *
     * Named individually and never summed into one anonymous remainder. On the measured release
     * binary these are `.eh_frame`, `.eh_frame_hdr` and `.gcc_except_table` — exception unwinding —
     * and `.dynsym`, `.dynstr` and `.gnu.hash` — dynamic linking: 2.2 MB that belongs to no package
     * and would be invisible as a single "unattributed" figure. A reader who sees "unattributed:
     * 3.3 MB" learns nothing; one who sees "`.eh_frame`: 1.29 MB" learns that unwind tables cost
     * them 6% of the binary.
     */
    public val unownedSections: List<SectionAttribution> =
        reconciliation.sections
            .filter { it.section.kind == SectionKind.ALLOCATED && it.section.size > 0 && it.attributed == 0L }
            .sortedByDescending { it.section.size }

    /**
     * Allocated sections with at least one owner, largest first.
     *
     * Each carries [SectionAttribution.coverage], and printing it is not decoration: `.text`
     * attribution is worth about 98% and `.rodata` about 41%, so a conclusion drawn about data rests
     * on less than half of the section it is drawn from. A number without its coverage is the way
     * this tool would mislead someone.
     */
    public val ownedSections: List<SectionAttribution> =
        reconciliation.sections
            .filter { it.section.kind == SectionKind.ALLOCATED && it.attributed > 0 }
            .sortedByDescending { it.section.size }

    public fun bytesOf(origin: Origin): Long = origins.firstOrNull { it.origin == origin }?.bytes ?: 0

    /** The share of the *attributed* bytes, which is not the share of the file. Both matter; say which. */
    public fun shareOf(origin: Origin): Double =
        if (reconciliation.attributedBytes == 0L) 0.0 else bytesOf(origin).toDouble() / reconciliation.attributedBytes
}

/**
 * One Kotlin package and what it costs.
 *
 * [name] is truncated to the report's depth, so several packages can share a row - which is the
 * point of the depth: at full depth a real binary produces hundreds of rows and at depth 3 it
 * produces a table a person reads.
 */
public data class PackageRow(
    val name: String,
    val bytes: Long,
    val symbols: Int,
)

/**
 * One module and what it costs.
 *
 * [name] is a klib's `unique_name` - `io.ktor:ktor-http` - or one of the two honest answers: a row
 * naming every module that declares an ambiguous package, or the row for code no supplied klib
 * accounts for, which is usually the application's own.
 */
public data class ModuleRow(
    val name: String,
    val bytes: Long,
    val symbols: Int,
    val kind: ModuleRowKind,
)

/** Which of the three answers a module row is. */
public enum class ModuleRowKind {
    /** Exactly one klib declares the package these bytes came from. */
    RESOLVED,

    /** More than one klib declares it, and razves will not pick. */
    AMBIGUOUS,

    /** No supplied klib declares it: the application's own code, or a klib that was not supplied. */
    UNATTRIBUTED_TO_A_MODULE,
}

/** One origin and what it costs. [symbols] is there so a huge row of tiny symbols reads differently. */
public data class OriginRow(
    val origin: Origin,
    val bytes: Long,
    val symbols: Int,
)
