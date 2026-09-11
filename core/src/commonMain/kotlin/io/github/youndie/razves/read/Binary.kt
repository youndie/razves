package io.github.youndie.razves.read

/** Which container was read. The two razves supports, and the reason there is no third. */
public enum class BinaryFormat { ELF64, MACHO64 }

/**
 * How a symbol's size was obtained.
 *
 * Not cosmetic. ELF symbol tables record a size; Mach-O's `nlist` has no size field at all, so on
 * Apple targets a symbol's size is the distance to the next one and therefore includes whatever
 * alignment padding follows it. The two are not comparable at byte precision, which is why the
 * report prints this and the diff refuses to mix them.
 */
public enum class SizeAlgorithm { RECORDED, ADDRESS_DELTA }

/**
 * Whether a section occupies address space, file bytes, both, or neither.
 *
 * The distinction is what makes the file-size arithmetic work: `.bss` has a size and no file bytes,
 * `.symtab` has file bytes and no address.
 */
public enum class SectionKind {
    /** In memory and in the file: `.text`, `.rodata`, `__TEXT,__text`. */
    ALLOCATED,

    /** In memory, no file bytes: `.bss`, `.tbss`, `.relro_padding`. */
    ALLOCATED_NOBITS,

    /**
     * File bytes that are metadata rather than program content: ELF's `.symtab`, `.strtab`,
     * `.comment` and `.debug_*`, and everything a Mach-O keeps in `__LINKEDIT`.
     *
     * Classified by role rather than by whether the loader maps it. `__LINKEDIT` *is* mapped, so
     * "allocated" would be the letter of the format — but it holds the symbol table, the string
     * table and the fixup data, which is exactly what ELF puts in non-allocated sections and exactly
     * what `strip` removes. Classifying by role is what lets a Mach-O report and an ELF report
     * answer the same question.
     */
    METADATA,
}

/**
 * A run of file bytes with a name and the alignment that decides how much padding may precede it.
 *
 * The alignment is load-bearing rather than informational. It is what makes the file-coverage check
 * exact: a linker pads a region's file offset up to its own alignment and no further, so the gap in
 * front of a region is always strictly smaller than that alignment. A gap larger than it means a
 * region was lost or misread — which is precisely the failure a coverage check exists to catch, and
 * precisely what a check built on a residual cannot see.
 */
public data class FileRegion(
    val name: String,
    val offset: Long,
    val size: Long,
    val alignment: Long,
)

/**
 * One section. [segment] is null on ELF and the Mach-O segment name otherwise — Mach-O has two
 * distinct sections named `__const`, so the identity of a section is the pair, never the name.
 */
public data class Section(
    val segment: String?,
    val name: String,
    val address: Long,
    val size: Long,
    val fileOffset: Long,
    val alignment: Long,
    val kind: SectionKind,
) {
    /** How many bytes of the file this section occupies. Zero for NOBITS. */
    public val fileBytes: Long get() = if (kind == SectionKind.ALLOCATED_NOBITS) 0 else size

    public val qualifiedName: String get() = if (segment == null) name else "$segment,$name"

    internal fun asFileRegion(): FileRegion = FileRegion(qualifiedName, fileOffset, fileBytes, maxOf(alignment, 1))
}

/** A defined symbol with an address, a size and the section it lives in. */
public data class Symbol(
    val name: String,
    val address: Long,
    val size: Long,
    val sectionIndex: Int,
)

/**
 * Everything razves reads out of a binary, in a form both readers produce and nothing downstream
 * has to know the format to use.
 *
 * [containerRegions] are the file's own tables — the ELF header, the program header table, the
 * section header table — read from the header fields rather than inferred. That matters: if the
 * bytes they occupy were the leftover after subtracting the sections, the file-size identity would
 * be true by construction and would check nothing at all.
 */
public data class BinaryImage(
    val name: String,
    val format: BinaryFormat,
    val sizeAlgorithm: SizeAlgorithm,
    val fileSize: Long,
    val containerRegions: List<FileRegion>,
    val sections: List<Section>,
    val symbols: List<Symbol>,
    /** False when the container carried no symbol table at all: a stripped binary. */
    val hasSymbolTable: Boolean,
    /**
     * Whether the format enumerates every byte-bearing region of the file.
     *
     * True for ELF: the section header table lists every section, so a byte belonging to nothing is
     * a reader defect and the coverage walk refuses it. False for Mach-O, whose link-edit area is
     * described by load commands razves does not all parse — there, a byte no named region claims is
     * reported as unparsed, which is a row of the report rather than a failure.
     */
    val coversEveryFileByte: Boolean,
    /**
     * The Kotlin/Native targets this binary could have been built for, in the names a klib manifest
     * uses. Empty when the container and CPU are not enough to say - in which case nothing is refused
     * on the strength of them.
     */
    val targets: Set<String> = emptySet(),
) {
    /** Bytes taken by the container's own tables rather than by anything it describes. */
    public val headerBytes: Long get() = containerRegions.sumOf { it.size }
}
