package io.github.youndie.razves.report

import io.github.youndie.razves.read.BinaryImage
import io.github.youndie.razves.read.FileRegion
import io.github.youndie.razves.read.Section
import io.github.youndie.razves.read.SectionKind
import io.github.youndie.razves.read.Symbol

/**
 * Where every byte of the file went, and the arithmetic that says so.
 *
 * This class is the project's primary oracle, and it is a constructor invariant rather than a
 * section of the output on purpose: it runs on every invocation on every machine, with nothing to
 * install. An oracle that has to be installed is an oracle that gets skipped, and a skipped check
 * that reports success is worse than no check — see docs/research/research-architecture.md D7.
 *
 * **The padding is checked, not defined.** The first version of this class computed
 * `padding = fileSize − headers − sections`, which made the file-size identity true by construction:
 * a reader that dropped a whole section still balanced, because the section's bytes silently became
 * padding. The test written to catch exactly that is what caught it. So padding is now the sum of
 * the *measured* gaps between adjacent file regions, and each gap is held against the alignment of
 * the region that follows it — a linker pads a region's offset up to its own alignment and no
 * further, so that bound is exact rather than a guess. Verified on all four `shildik` ELF subjects
 * before it was made a requirement: zero violations, over 34 to 43 regions each.
 *
 * **A gap the alignment cannot explain means different things in the two formats**, and the
 * difference is a property of the formats rather than a convenience. An ELF section header table
 * lists every byte-bearing region there is, so such a gap is a defect in the reader and the walk
 * refuses it. A Mach-O describes its link-edit area through load commands, and a reader that has not
 * implemented one of them legitimately will not know what some bytes are — so there the gap becomes
 * the [unparsedBytes] row, which is honest, rather than padding, which would be a lie.
 */
public class Reconciliation internal constructor(
    public val image: BinaryImage,
    public val sections: List<SectionAttribution>,
) {
    public val fileSize: Long get() = image.fileSize

    /** Bytes of the file taken by the container's own tables: the ELF header, program and section headers. */
    public val headerBytes: Long get() = image.headerBytes

    /** Sections that are mapped into memory *and* stored in the file. */
    public val allocatedBytes: Long =
        sections.sumOf { if (it.section.kind == SectionKind.ALLOCATED) it.section.size else 0 }

    /** Mapped into memory, stored nowhere: `.bss`, `.tbss`. They have a size and cost no download. */
    public val nobitsBytes: Long =
        sections.sumOf { if (it.section.kind == SectionKind.ALLOCATED_NOBITS) it.section.size else 0 }

    /** Metadata rather than program content: `.symtab`, `.strtab`, `.debug_*`, Mach-O `__LINKEDIT`. */
    public val metadataBytes: Long =
        sections.sumOf { if (it.section.kind == SectionKind.METADATA) it.section.size else 0 }

    /** The gaps alignment leaves between file regions. Measured from the offsets, not left over. */
    public val interRegionPadding: Long

    /**
     * File bytes no named region claims, and no alignment explains.
     *
     * Always zero on ELF, where a byte belonging to nothing is a reader defect and the walk refuses
     * it. On Mach-O it is a real row: the link-edit area is described by load commands rather than by
     * a table of every region, and razves does not parse all of them. A row saying "razves did not
     * account for 30 KB" is honest; folding those bytes into padding, or into a neighbouring section,
     * is the failure this whole class exists to prevent.
     */
    public val unparsedBytes: Long

    /** What the binary occupies once loaded, which is not what it costs to ship. */
    public val virtualSize: Long = allocatedBytes + nobitsBytes

    /** Bytes of allocated sections that a symbol claims. */
    public val attributedBytes: Long =
        sections.sumOf { if (it.section.kind == SectionKind.ALLOCATED) it.attributed else 0 }

    /**
     * Bytes of allocated sections that no symbol claims. A required row of every report and never a
     * remainder folded into another line: on a real release binary this is a fifth of the allocated
     * bytes, structured mostly as unwind tables and dynamic-linking metadata, and hiding it is how a
     * size tool stops being believed.
     */
    public val unattributedBytes: Long = allocatedBytes - attributedBytes

    init {
        require(fileSize >= 0) { "${image.name} has a negative file size" }
        val coverage = walkTheFile()
        interRegionPadding = coverage.padding
        unparsedBytes = coverage.unparsed

        require(headerBytes + allocatedBytes + metadataBytes + interRegionPadding + unparsedBytes == fileSize) {
            "the file-size identity does not hold for ${image.name}: " +
                "$headerBytes header + $allocatedBytes allocated + $metadataBytes metadata + " +
                "$interRegionPadding padding + $unparsedBytes unparsed = " +
                "${headerBytes + allocatedBytes + metadataBytes + interRegionPadding + unparsedBytes}, " +
                "but the file is $fileSize"
        }
        for (s in sections) {
            require(s.attributed >= 0 && s.unattributed >= 0) {
                "section ${s.section.qualifiedName} of ${image.name} has a negative part: " +
                    "attributed=${s.attributed}, unattributed=${s.unattributed}"
            }
            require(s.attributed + s.unattributed == s.section.size) {
                "section ${s.section.qualifiedName} of ${image.name}: " +
                    "${s.attributed} attributed + ${s.unattributed} unattributed != ${s.section.size}"
            }
        }
        require(attributedBytes + unattributedBytes == allocatedBytes) {
            "the attribution identity does not hold for ${image.name}"
        }
    }

    private class Coverage(
        val padding: Long,
        val unparsed: Long,
    )

    /**
     * Walks the file in offset order over the container's own tables and every region that occupies
     * file bytes, and sorts what lies between them into padding and unparsed bytes.
     *
     * A gap smaller than the alignment of the region that follows it is padding: a linker pads a
     * region's offset up to its own alignment and no further, so that bound is exact rather than a
     * guess. Anything larger has no such explanation. On a format that enumerates every region — ELF
     * — that is a defect in this reader and the walk says so; on one that does not — Mach-O — it is
     * a row of the report.
     *
     * Overlap and running past the end of the file are defects in either format.
     */
    private fun walkTheFile(): Coverage {
        val regions =
            (image.containerRegions + sections.map { it.section.asFileRegion() })
                .filter { it.size > 0 }
                .sortedBy { it.offset }
        if (regions.isEmpty()) return Coverage(padding = 0, unparsed = fileSize)

        var padding = 0L
        var unparsed = 0L
        var cursor = 0L
        for (region in regions) {
            val gap = region.offset - cursor
            require(gap >= 0) {
                "${image.name} is misread: ${region.name} starts at ${region.offset}, " +
                    "inside a region that runs to $cursor"
            }
            if (gap < region.alignment) {
                padding += gap
            } else {
                require(!image.coversEveryFileByte) {
                    "${image.name} is misread: $gap bytes before ${region.name} are covered by nothing, " +
                        "and its alignment of ${region.alignment} cannot account for more than " +
                        "${region.alignment - 1}. A region was lost or an offset was read wrongly."
                }
                unparsed += gap
            }
            cursor = region.offset + region.size
        }
        require(cursor <= fileSize) {
            "${image.name} is misread: ${regions.last().name} ends at $cursor, past the end of a $fileSize-byte file"
        }
        val tail = fileSize - cursor
        require(tail == 0L || !image.coversEveryFileByte) {
            "${image.name} is misread: $tail bytes after ${regions.last().name} belong to no region, " +
                "and an ELF section header table lists every region there is."
        }
        return Coverage(padding, unparsed + tail)
    }
}

/** One section and how much of it has an owner. [owners] sums to [attributed], exactly. */
public data class SectionAttribution(
    val section: Section,
    val owners: List<SymbolExtent>,
) {
    val attributed: Long = owners.sumOf { it.bytes }
    val unattributed: Long = section.size - attributed

    /** What share of this section a symbol claims. Printed next to every section conclusion. */
    val coverage: Double get() = if (section.size == 0L) 1.0 else attributed.toDouble() / section.size
}

/** A symbol and the bytes charged to it after overlaps are resolved. */
public data class SymbolExtent(
    val symbol: Symbol,
    /**
     * Where the owned range begins, which is **not** always `symbol.address`.
     *
     * The sweep hands each byte to the symbol that reached it first, so a symbol overlapping one
     * that started earlier owns only the tail it added. Carrying the start rather than recomputing
     * it is what lets [SymbolIndex] answer "who owns this address" with the same rule that decided
     * who owns the byte - one rule, in one place, instead of two that agree until they do not.
     */
    val start: Long,
    val bytes: Long,
) {
    /** Exclusive, like every end in this codebase. */
    public val end: Long get() = start + bytes
}
