package io.github.youndie.razves.report

/**
 * A diff as a person reads it, and as a build failure quotes it.
 *
 * The shape is chosen for the failure message: the largest movements first, with the rows that
 * appeared and vanished marked, so that a reader who gets four lines of it in a red build knows what
 * to do. "The binary grew 4.1%" is not four lines anybody can act on.
 */
public object TextDiff {
    private const val NAME_WIDTH = 44
    private const val DEFAULT_ROWS = 10

    public fun render(
        diff: DiffDocument,
        rows: Int = DEFAULT_ROWS,
    ): String =
        buildString {
            appendLine("razves ${diff.binary}: ${signed(diff.delta)}${share(diff)}")
            appendLine("  ${group(diff.before)} -> ${group(diff.after)} bytes")
            appendLine()
            table("WHERE THE FILE WENT", diff.reconciliation, rows)
            table("BY ORIGIN", diff.origins, rows)
            table("BY PACKAGE", diff.packages, rows)
            if (diff.modules.isNotEmpty()) table("BY MODULE", diff.modules, rows)
            table("BY SECTION", diff.sections, rows)
        }

    /** The largest movements of one level, or a line saying there were none. */
    private fun StringBuilder.table(
        title: String,
        entries: List<DeltaRow>,
        rows: Int,
    ) {
        val moved = entries.filter { it.delta != 0L }
        appendLine(title)
        if (moved.isEmpty()) {
            appendLine("  nothing moved")
        } else {
            for (row in moved.take(rows)) {
                append("  ")
                append(fit(row.name))
                append(signed(row.delta).padStart(14))
                when {
                    row.appeared -> append("   new")
                    row.gone -> append("   gone")
                    else -> append("   ${group(row.before)} -> ${group(row.after)}")
                }
                appendLine()
            }
            val rest = moved.drop(rows)
            if (rest.isNotEmpty()) {
                appendLine("  ${fit("${rest.size} smaller movements")}${signed(rest.sumOf { it.delta }).padStart(14)}")
            }
        }
        appendLine()
    }

    private fun share(diff: DiffDocument): String {
        val fraction = diff.fraction ?: return ""
        return " (${signed1((fraction * 100 * 10).toLong() / 10.0)}%)"
    }

    private fun fit(name: String): String =
        if (name.length <= NAME_WIDTH) {
            name.padEnd(NAME_WIDTH)
        } else {
            val keep = NAME_WIDTH - 2
            name.take(keep / 2) + ".." + name.takeLast(keep - keep / 2)
        }

    /** A delta always carries its sign, including zero-crossing ones: `+0` is not a thing to print. */
    private fun signed(value: Long): String = if (value >= 0) "+${group(value)}" else "-${group(-value)}"

    private fun signed1(value: Double): String {
        val scaled = (value * 10).toLong()
        val sign = if (scaled >= 0) "+" else "-"
        val absolute = if (scaled < 0) -scaled else scaled
        return "$sign${absolute / 10}.${absolute % 10}"
    }

    private fun group(value: Long): String =
        value
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
}
