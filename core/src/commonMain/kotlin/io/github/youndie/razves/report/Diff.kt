package io.github.youndie.razves.report

import kotlinx.serialization.Serializable

/**
 * What moved between two reports, row by row.
 *
 * **The row deltas are the product, not the total.** A gate that reports "the binary grew 4.1%" gives
 * the reader nothing to decide with, and gets switched off the first week a dependency bump trips it;
 * "`io.ktor.client` +180 KB, `openssl` +1.2 MB" turns a red build into a decision. That is why this
 * exists before the gate that uses it rather than after.
 *
 * **A row present on one side only is shown, never dropped.** A package that appeared and a package
 * that vanished are the two most interesting rows in any diff, and both have a zero on one side.
 */
@Serializable
public data class DiffDocument(
    val binary: String,
    val sizeAlgorithm: String,
    val before: Long,
    val after: Long,
    /** Where the file went, on both sides: the terms that sum to the file size. */
    val reconciliation: List<DeltaRow>,
    val origins: List<DeltaRow>,
    val packages: List<DeltaRow>,
    val modules: List<DeltaRow>,
    val sections: List<DeltaRow>,
) {
    public val delta: Long get() = after - before

    /** Growth as a fraction of the baseline. `null` when the baseline is zero: nothing to be a share of. */
    public val fraction: Double? get() = if (before == 0L) null else delta.toDouble() / before

    public companion object {
        /**
         * Compares two reports of the same binary.
         *
         * Refuses to compare across size algorithms. An ELF symbol table records a size and Mach-O's
         * does not, so an address-derived number includes the alignment that follows a symbol where a
         * recorded one does not — the two are not comparable at byte precision, and subtracting them
         * produces a number with no meaning and no warning attached.
         */
        public fun of(
            before: ReportDocument,
            after: ReportDocument,
        ): DiffDocument {
            require(before.sizeAlgorithm == after.sizeAlgorithm) {
                "these two reports were measured differently - ${before.binary} by " +
                    "${before.sizeAlgorithm} and ${after.binary} by ${after.sizeAlgorithm} - and " +
                    "subtracting one from the other produces a number with no meaning. A recorded " +
                    "symbol size and an address-derived one are not the same quantity."
            }
            require(before.targets.isEmpty() || after.targets.isEmpty() || before.targets == after.targets) {
                "these two reports are of different targets - ${before.targets} and ${after.targets}"
            }
            return DiffDocument(
                binary = after.binary,
                sizeAlgorithm = after.sizeAlgorithm,
                before = before.fileSize,
                after = after.fileSize,
                reconciliation = reconciliationRows(before, after),
                origins = join(before.origins.map { it.name to it.bytes }, after.origins.map { it.name to it.bytes }),
                packages =
                    join(
                        before.packages.map { it.name to it.bytes },
                        after.packages.map { it.name to it.bytes },
                    ),
                modules =
                    join(
                        before.modules.map { it.name to it.bytes },
                        after.modules.map { it.name to it.bytes },
                    ),
                sections =
                    join(
                        before.sections.map { it.name to it.size },
                        after.sections.map { it.name to it.size },
                    ),
            )
        }

        /**
         * The five terms that sum to the file size, so their deltas sum to the change in it.
         *
         * That identity is what makes the diff checkable rather than merely plausible, and it is
         * asserted rather than assumed.
         */
        private fun reconciliationRows(
            before: ReportDocument,
            after: ReportDocument,
        ): List<DeltaRow> =
            listOf(
                DeltaRow("container headers", before.headerBytes, after.headerBytes, true, true),
                DeltaRow("allocated sections", before.allocatedBytes, after.allocatedBytes, true, true),
                DeltaRow("metadata", before.metadataBytes, after.metadataBytes, true, true),
                DeltaRow("padding", before.paddingBytes, after.paddingBytes, true, true),
                DeltaRow("unparsed", before.unparsedBytes, after.unparsedBytes, true, true),
            )

        /**
         * Joins two sets of rows by name, largest movement first.
         *
         * Sorted by the absolute delta rather than by size, because the question a diff answers is
         * "what moved", and a row that did not move is not an answer to it however large it is.
         */
        private fun join(
            before: List<Pair<String, Long>>,
            after: List<Pair<String, Long>>,
        ): List<DeltaRow> {
            val old = before.toMap()
            val new = after.toMap()
            return (old.keys + new.keys)
                .map { name ->
                    DeltaRow(
                        name = name,
                        before = old[name] ?: 0,
                        after = new[name] ?: 0,
                        inBefore = name in old,
                        inAfter = name in new,
                    )
                }.filterNot { it.delta == 0L && it.inBefore && it.inAfter }
                .sortedWith(compareByDescending<DeltaRow> { it.magnitude }.thenBy { it.name })
        }
    }
}

/**
 * One row on both sides.
 *
 * [inBefore] and [inAfter] are carried rather than inferred from zeroes: a row can legitimately be
 * present and empty, and "appeared" is a different fact from "grew from nothing".
 */
@Serializable
public data class DeltaRow(
    val name: String,
    val before: Long,
    val after: Long,
    val inBefore: Boolean,
    val inAfter: Boolean,
) {
    val delta: Long get() = after - before

    val appeared: Boolean get() = !inBefore && inAfter

    val gone: Boolean get() = inBefore && !inAfter

    /** How far this row moved, in either direction. What the tables are sorted by. */
    val magnitude: Long get() = if (delta < 0) -delta else delta
}
