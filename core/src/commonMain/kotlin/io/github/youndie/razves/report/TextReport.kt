package io.github.youndie.razves.report

import io.github.youndie.razves.read.SectionKind

/**
 * The report as a person reads it: a header that says what it is, then three levels the reader can
 * stop at.
 *
 * **The header is not decoration.** Three of its lines change what every number below them means.
 * An address-derived size includes the alignment that follows a symbol and a recorded one does not,
 * so the two are not comparable at byte precision. A report built without klibs stops at package
 * level, and a reader who does not know that will read the absence of module rows as an absence of
 * modules. And a truncated package depth means several packages share a row.
 *
 * **Coverage is printed beside every section.** Measured on a real release binary, `.text`
 * attribution is worth 97.6% and `.rodata` 40.7%; a table where those two rows look alike is a table
 * that will be quoted wrongly.
 */
public object TextReport {
    private const val NAME_WIDTH = 42
    private const val BYTES_WIDTH = 14
    private const val DEFAULT_ROWS = 20

    public fun render(
        document: ReportDocument,
        rows: Int = DEFAULT_ROWS,
    ): String =
        buildString {
            header(document)
            appendLine()
            reconciliation(document)
            appendLine()
            origins(document)
            appendLine()
            packages(document, rows)
            if (document.moduleAttribution) {
                appendLine()
                modules(document, rows)
            }
            appendLine()
            sections(document, rows)
        }

    private fun StringBuilder.header(d: ReportDocument) {
        appendLine("razves ${d.binary}")
        appendLine("  ${d.format}${if (d.targets.isEmpty()) "" else ", ${d.targets.joinToString(" or ")}"}")
        appendLine(
            "  symbol sizes: " +
                when (d.sizeAlgorithm) {
                    "RECORDED" -> "recorded by the symbol table"
                    "ADDRESS_DELTA" -> "derived from the distance to the next symbol, so they include trailing padding"
                    else -> d.sizeAlgorithm
                },
        )
        appendLine(
            "  modules: " +
                if (d.moduleAttribution) {
                    "attributed from the klibs supplied"
                } else {
                    "not available - no klibs were supplied, so this report stops at package level"
                },
        )
        d.packageDepth?.let {
            appendLine(
                "  package names truncated to $it segments, so a row may span several packages",
            )
        }
    }

    private fun StringBuilder.reconciliation(d: ReportDocument) {
        appendLine("WHERE THE FILE WENT")
        row("file", d.fileSize, d.fileSize)
        row("  container headers", d.headerBytes, d.fileSize)
        row("  allocated sections", d.allocatedBytes, d.fileSize)
        row("  metadata (symbol tables, debug info)", d.metadataBytes, d.fileSize)
        row("  padding between regions", d.paddingBytes, d.fileSize)
        if (d.unparsedBytes > 0) row("  unparsed by razves", d.unparsedBytes, d.fileSize)
        appendLine("${fit("  in memory only (NOBITS)")}${d.nobitsBytes.pad()}   costs no download")
    }

    private fun StringBuilder.origins(d: ReportDocument) {
        appendLine("WHERE THE ATTRIBUTED BYTES CAME FROM")
        row("attributed", d.attributedBytes, d.allocatedBytes)
        for (origin in d.origins) row("  ${origin.name.lowercase()}", origin.bytes, d.attributedBytes, origin.symbols)
        row("unattributed - no symbol claims these", d.unattributedBytes, d.allocatedBytes)
    }

    private fun StringBuilder.packages(
        d: ReportDocument,
        rows: Int,
    ) {
        appendLine("KOTLIN, BY PACKAGE")
        listed(d.packages.map { Triple(it.name, it.bytes, it.symbols) }, d.attributedBytes, rows)
    }

    private fun StringBuilder.modules(
        d: ReportDocument,
        rows: Int,
    ) {
        appendLine("KOTLIN, BY MODULE")
        listed(d.modules.map { Triple(it.name, it.bytes, it.symbols) }, d.attributedBytes, rows)
    }

    private fun StringBuilder.listed(
        entries: List<Triple<String, Long, Int>>,
        total: Long,
        rows: Int,
    ) {
        for ((name, bytes, symbols) in entries.take(rows)) row("  $name", bytes, total, symbols)
        val rest = entries.drop(rows)
        if (rest.isNotEmpty()) row("  ${rest.size} more rows", rest.sumOf { it.second }, total)
    }

    private fun StringBuilder.sections(
        d: ReportDocument,
        rows: Int,
    ) {
        appendLine("SECTIONS, AND HOW MUCH OF EACH HAS AN OWNER")
        for (section in d.sections.take(rows)) {
            val coverage =
                when {
                    section.kind != SectionKind.ALLOCATED.name -> "not attributed"
                    section.attributed == 0L -> "no owner at all"
                    else -> "${percent(section.attributed, section.size)} has an owner"
                }
            appendLine("${fit("  ${section.name}")}${section.size.pad()}   $coverage")
        }
        val rest = d.sections.drop(rows)
        if (rest.isNotEmpty()) appendLine("${fit("  ${rest.size} more sections")}${rest.sumOf { it.size }.pad()}")
    }

    private fun StringBuilder.row(
        name: String,
        bytes: Long,
        total: Long,
        symbols: Int? = null,
    ) {
        append(fit(name))
        append(bytes.pad())
        append("  ")
        append(percent(bytes, total).padStart(6))
        if (symbols != null) append("  $symbols symbols")
        appendLine()
    }

    /**
     * A name in the column, cut from the middle when it does not fit.
     *
     * From the middle rather than the end, because the ends are what identify a row: an ambiguous
     * module row lists every module that declares the package and can run to a hundred characters,
     * and a name cut at the right loses the last module while a name cut in the middle loses the part
     * two long coordinates have in common.
     */
    private fun fit(name: String): String =
        if (name.length <= NAME_WIDTH) {
            name.padEnd(NAME_WIDTH)
        } else {
            val keep = NAME_WIDTH - 2
            name.take(keep / 2) + ".." + name.takeLast(keep - keep / 2)
        }

    /**
     * Exact bytes first, with the human-readable size in brackets after it.
     *
     * That order round, rather than the other way: "19.6 MiB" is what a person remembers and
     * 20,543,736 is what they can check, and a tool whose whole claim is that its totals add up
     * should put the addable number first.
     */
    private fun Long.pad(): String = "${group(this)} (${readable(this)})".padStart(BYTES_WIDTH + 12)

    private fun group(value: Long): String =
        value
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()

    private fun readable(bytes: Long): String {
        val units = listOf("B", "KiB", "MiB", "GiB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "$bytes B" else "${round1(value)} ${units[unit]}"
    }

    private fun percent(
        part: Long,
        whole: Long,
    ): String = if (whole == 0L) "-" else "${round1(100.0 * part / whole)}%"

    /** One decimal place, without a platform formatter: `toString` on a Double differs across targets. */
    private fun round1(value: Double): String {
        val scaled = ((value * 10).toLong() + if (value * 10 - (value * 10).toLong() >= 0.5) 1 else 0)
        return "${scaled / 10}.${scaled % 10}"
    }
}
