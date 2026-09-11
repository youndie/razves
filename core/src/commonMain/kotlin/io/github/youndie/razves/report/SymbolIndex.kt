package io.github.youndie.razves.report

import io.github.youndie.razves.read.BinaryImage
import io.github.youndie.razves.read.Symbol

/**
 * Which symbol owns an address.
 *
 * The size report asks the opposite question - given a symbol, how many bytes - and until a profiler
 * needed this one, `core` had no way to ask it: the sweep walks symbols against sections and never
 * back. This is that direction, and it is deliberately **not** a second implementation of the
 * ownership rule.
 *
 * **It answers with the ranges the sweep produced**, so "who owns this address" and "whose bytes are
 * these" cannot disagree. That matters for aliases: two symbols at one address, or one whose recorded
 * size runs into its neighbour, are charged once by the sweep - to the one that starts earliest, and
 * on a tie to the larger - and an index built from the raw symbol table would happily return the
 * other one. A profile and a size report of the same binary would then name different functions for
 * the same bytes, and nothing would say which was right.
 *
 * **A miss is an answer.** An address in a gap between two symbols returns null rather than the
 * symbol before it, because "the nearest name" is how a profiler invents a hot function that was
 * never called. Every real binary has such addresses - padding between functions, `.eh_frame`, code
 * from a linker-generated object nobody named - and razves already has a word for bytes with no
 * owner.
 */
public class SymbolIndex private constructor(
    private val starts: LongArray,
    private val ends: LongArray,
    private val owners: List<Symbol>,
) {
    /** How many ranges the index holds, which is not the number of symbols: a symbol that lost every byte to an earlier one has no range. */
    public val size: Int get() = owners.size

    /**
     * The symbol whose bytes cover [address], or null.
     *
     * Binary search over disjoint ranges: the sweep never emits two ranges covering one byte, which
     * is what makes a single answer possible at all.
     */
    public fun at(address: Long): Symbol? {
        var low = 0
        var high = owners.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                address < starts[mid] -> high = mid - 1
                address >= ends[mid] -> low = mid + 1
                else -> return owners[mid]
            }
        }
        return null
    }

    public companion object {
        public fun of(image: BinaryImage): SymbolIndex = of(Attribution.of(image))

        /**
         * Built from a finished reconciliation, so the index costs no second sweep when the caller
         * already has one - which the profiler does, because it wants the size report beside the
         * profile.
         */
        public fun of(reconciliation: Reconciliation): SymbolIndex {
            val extents =
                reconciliation.sections
                    .flatMap { it.owners }
                    .sortedBy { it.start }
            return SymbolIndex(
                starts = LongArray(extents.size) { extents[it].start },
                ends = LongArray(extents.size) { extents[it].end },
                owners = extents.map { it.symbol },
            )
        }
    }
}
