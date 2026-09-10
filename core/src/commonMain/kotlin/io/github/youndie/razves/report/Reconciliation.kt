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
 * further, so a gap at least as large as that alignment means a region was lost or misread. Verified
 * on all four `shildik` subjects before it was made a requirement: zero violations, over 34 to 43
 * regions each.
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

    /** In the file, never in memory: `.symtab`, `.strtab`, `.comment`, `.debug_*`. */
    public val notAllocatedBytes: Long =
        sections.sumOf { if (it.section.kind == SectionKind.NOT_ALLOCATED) it.section.size else 0 }

    /** The gaps alignment leaves between file regions. Measured from the offsets, not left over. */
    public val interRegionPadding: Long

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
        interRegionPadding = measurePadding()

        require(headerBytes + allocatedBytes + notAllocatedBytes + interRegionPadding == fileSize) {
            "the file-size identity does not hold for ${image.name}: " +
                "$headerBytes header + $allocatedBytes allocated + $notAllocatedBytes non-allocated + " +
                "$interRegionPadding padding = " +
                "${headerBytes + allocatedBytes + notAllocatedBytes + interRegionPadding}, but the file is $fileSize"
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

    /**
     * Walks the file in offset order over the container's own tables and every section that occupies
     * file bytes, and returns the gaps between them.
     *
     * Every failure this can report is a reader defect rather than a property of the binary: regions
     * that overlap, a region past the end of the file, or a gap too large to be alignment.
     */
    private fun measurePadding(): Long {
        val regions =
            (image.containerRegions + sections.map { it.section.asFileRegion() })
                .filter { it.size > 0 }
                .sortedBy { it.offset }
        if (regions.isEmpty()) return fileSize

        var padding = 0L
        var cursor = 0L
        for (region in regions) {
            val gap = region.offset - cursor
            require(gap >= 0) {
                "${image.name} is misread: ${region.name} starts at ${region.offset}, " +
                    "inside a region that runs to $cursor"
            }
            require(gap < region.alignment) {
                "${image.name} is misread: $gap bytes before ${region.name} are covered by nothing, " +
                    "and its alignment of ${region.alignment} cannot account for more than " +
                    "${region.alignment - 1}. A region was lost or an offset was read wrongly."
            }
            padding += gap
            cursor = region.offset + region.size
        }
        require(cursor <= fileSize) {
            "${image.name} is misread: ${regions.last().name} ends at $cursor, past the end of a $fileSize-byte file"
        }
        return padding + (fileSize - cursor)
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
    val bytes: Long,
)
