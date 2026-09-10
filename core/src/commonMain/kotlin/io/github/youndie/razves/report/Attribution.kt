package io.github.youndie.razves.report

import io.github.youndie.razves.read.BinaryImage
import io.github.youndie.razves.read.SectionKind
import io.github.youndie.razves.read.Symbol

/**
 * Turns a [BinaryImage] into a [Reconciliation]: every byte of every allocated section charged to
 * at most one symbol.
 *
 * **Symbols are placed by their recorded section index, never by looking their address up in the
 * section table.** An address lookup looks obviously right and is wrong on real binaries: `.tbss`
 * is thread-local storage, occupies no file bytes, and its virtual address deliberately overlaps
 * the section that follows it — so an address-keyed lookup charges `.ctors` symbols to `.tbss` and
 * reports a section as more than 100% attributed. The symbol table already says which section each
 * symbol belongs to; asking it is both cheaper and correct.
 *
 * **Overlapping symbols are charged once.** Aliases, weak definitions and the occasional symbol
 * whose recorded size runs into its neighbour would otherwise make a section sum to more than it
 * holds. The sweep below assigns each byte to the symbol that starts earliest, so the per-owner
 * totals add up to the union of the covered ranges rather than to the sum of the sizes, and the
 * identity in [Reconciliation] holds without a clamp anywhere else.
 */
public object Attribution {
    public fun of(image: BinaryImage): Reconciliation {
        val bySection = image.symbols.groupBy { it.sectionIndex }
        val sections =
            image.sections.mapIndexed { index, section ->
                if (section.kind != SectionKind.ALLOCATED) {
                    // Non-allocated sections hold no code or data a symbol can own, and NOBITS sections
                    // hold no file bytes. Both are reported by size; neither is attributed.
                    SectionAttribution(section, emptyList())
                } else {
                    SectionAttribution(section, sweep(section.address, section.size, bySection[index].orEmpty()))
                }
            }
        return Reconciliation(image, sections)
    }

    private fun sweep(
        sectionStart: Long,
        sectionSize: Long,
        symbols: List<Symbol>,
    ): List<SymbolExtent> {
        if (symbols.isEmpty() || sectionSize == 0L) return emptyList()
        val sectionEnd = sectionStart + sectionSize
        // Earliest address first; on a tie the larger symbol wins the bytes, so an alias of the same
        // size does not steal them from the definition it aliases. The name breaks the remaining tie
        // so that the report is the same on every run and a diff of two identical binaries is empty.
        val ordered = symbols.sortedWith(compareBy({ it.address }, { -it.size }, { it.name }))
        val out = ArrayList<SymbolExtent>(ordered.size)
        var cursor = sectionStart
        for (s in ordered) {
            val start = maxOf(s.address, cursor, sectionStart)
            val end = minOf(s.address + s.size, sectionEnd)
            val bytes = if (end > start) end - start else 0L
            if (bytes > 0) {
                out += SymbolExtent(s, bytes)
                cursor = end
            }
        }
        return out
    }
}
