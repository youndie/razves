package io.github.youndie.razves.report

import io.github.youndie.razves.attribute.Mangling
import io.github.youndie.razves.attribute.Origin
import io.github.youndie.razves.attribute.Packages
import io.github.youndie.razves.klib.ModuleOwner
import io.github.youndie.razves.klib.PackageToModule
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
    /**
     * The full report: the reconciliation, plus the attributed bytes split by origin.
     *
     * Every attributed byte lands in exactly one origin row, and [SizeReport] refuses to be built
     * if the rows do not add up to what the reconciliation attributed. That is the third identity of
     * the tool, after the file-size one and the per-section one, and it exists for the same reason:
     * a split that does not add up is a split that is quietly losing bytes somewhere.
     */
    public fun report(
        image: BinaryImage,
        packageDepth: Int = DEFAULT_PACKAGE_DEPTH,
        modules: PackageToModule? = null,
    ): SizeReport {
        val reconciliation = of(image)
        val owners = reconciliation.sections.flatMap { it.owners }
        val byOrigin = owners.groupBy { Mangling.originOf(it.symbol.name) }
        // Origin.entries rather than the map's keys, so the enum's own order decides the report's -
        // Kotlin first, then the runtime beneath it, then what was linked in - and so a bucket that
        // happens to be empty in this binary is still a row saying zero rather than a missing line.
        val rows =
            Origin.entries.map { origin ->
                val extents = byOrigin[origin].orEmpty()
                OriginRow(origin, extents.sumOf { it.bytes }, extents.size)
            }
        // One folded name per symbol, used for both the package rows and the module rows, so the two
        // layers can never disagree about which package a byte is in.
        val packaged = foldedPackages(owners, modules)
        val packages =
            packaged
                .groupBy({ truncate(it.first, packageDepth) }, { it.second })
                .map { (name, extents) -> PackageRow(name, extents.sumOf { it.bytes }, extents.size) }
                .sortedWith(compareByDescending<PackageRow> { it.bytes }.thenBy { it.name })
        // Modules resolve on the FULL package name and never on the truncated one. A depth-3 row
        // called `io.ktor.server` spans a dozen packages from several klibs; asking which module owns
        // that name is asking a question with no answer, and the answer it would invent is a module
        // that owns some of it.
        val moduleRows =
            modules
                ?.let { map ->
                    packaged
                        .groupBy({ moduleRowName(map, it.first) }, { it.second })
                        .map { (row, extents) ->
                            ModuleRow(row.first, extents.sumOf { it.bytes }, extents.size, row.second)
                        }.sortedWith(compareByDescending<ModuleRow> { it.bytes }.thenBy { it.name })
                }.orEmpty()
        return SizeReport(reconciliation, rows, packages, packageDepth, moduleRows)
    }

    /**
     * Every symbol's package, folded to a name some klib actually declares.
     *
     * A grammar over mangled names cannot tell a lowercase declaration from a package segment, and
     * cinterop generates plenty of the former because a C struct keeps its C name. Measured on the
     * release subject before this existed: 23 package rows of 210 named a package no klib declares -
     * `platform.posix.addrinfo`, `dev.whyoleg…internal.cinterop.ossl_param_st`,
     * `io.ktor.network.interop.selection_set` - worth 25,142 bytes.
     *
     * The klib package lists are the authority that fixes it, and folding is the whole fix: a derived
     * name that nothing declares becomes the longest prefix of it that something does.
     * `platform.posix.addrinfo` becomes `platform.posix`, which is both correct and what a reader
     * wanted.
     *
     * **The fold never invents.** With no klibs, or with no declared prefix, the derived name stands
     * as it is - which is the honest answer when there is no authority to appeal to, and the report's
     * header says module attribution was unavailable.
     */
    private fun foldedPackages(
        owners: List<SymbolExtent>,
        modules: PackageToModule?,
    ): List<Pair<String, SymbolExtent>> {
        // Pairs rather than a map keyed by the extent: two symbols can be equal as values without
        // being the same symbol, and a map would silently merge them.
        val folded = HashMap<String, String>()
        val out = ArrayList<Pair<String, SymbolExtent>>(owners.size)
        for (extent in owners) {
            val derived = Packages.of(extent.symbol.name) ?: continue
            out += folded.getOrPut(derived) { modules?.longestDeclaredPrefix(derived) ?: derived } to extent
        }
        return out
    }

    private fun truncate(
        packageName: String,
        depth: Int,
    ): String = packageName.split('.').take(depth).joinToString(".")

    private fun moduleRowName(
        map: PackageToModule,
        fqn: String,
    ): Pair<String, ModuleRowKind> =
        when (val owner = map.resolve(fqn)) {
            is ModuleOwner.One -> owner.module to ModuleRowKind.RESOLVED
            is ModuleOwner.Ambiguous -> "<ambiguous: ${owner.modules.joinToString(", ")}>" to ModuleRowKind.AMBIGUOUS
            ModuleOwner.Unknown -> "<no klib declares $fqn>" to ModuleRowKind.UNATTRIBUTED_TO_A_MODULE
        }

    /**
     * Three segments, because that is what makes the table readable rather than because it is round.
     * On the release subject, depth 1 collapses everything into four rows and full depth produces
     * hundreds; three gives `ru.workinprogress.shildik`, `io.ktor.server`, `io.ktor.client`,
     * `kotlin.text.regex` and the rest - names a reader recognises and can act on.
     */
    public const val DEFAULT_PACKAGE_DEPTH: Int = 3

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
