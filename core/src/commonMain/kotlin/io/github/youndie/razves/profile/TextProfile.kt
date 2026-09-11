package io.github.youndie.razves.profile

/**
 * The profile as a person reads it, in the shape the size report already taught them.
 *
 * **Self and total in separate columns, always both.** A row with a large total and no self is a
 * caller; one with both is where the work is. Printing one of them and calling it "time" is how a
 * reader ends up optimising a function that was only on the stack.
 *
 * **The header says what the numbers rest on**: how many samples there are, what the ring dropped,
 * what rate was actually achieved, and how much of the profile razves could name at all. A profile
 * that lost two thirds of its samples, or that could name a fifth of them, is not wrong - it is
 * quotable only if the reader knows.
 */
public object TextProfile {
    private const val NAME_WIDTH = 44
    private const val DEFAULT_ROWS = 20

    public fun render(
        profile: Profile,
        dump: SampleDump? = null,
        rows: Int = DEFAULT_ROWS,
    ): String =
        buildString {
            appendLine("razves ${profile.binary}")
            appendLine("  ${group(profile.samples)} samples")
            if (profile.dropped > 0) {
                val share = percent(profile.dropped, profile.samples + profile.dropped)
                appendLine("  ${group(profile.dropped)} dropped by the ring - $share of what was taken")
            }
            dump?.let { d ->
                d.hz?.let { appendLine("  $it Hz requested on the ${d.clock ?: "unknown"} clock") }
                if (d.taken > 0 && d.stacksKept.toLong() != d.taken) {
                    appendLine("  ${group(d.taken)} signals delivered, ${group(d.stacksKept.toLong())} kept")
                }
            }
            appendLine("  ${(profile.namedShare * 1000).toInt() / 10.0}% of the leaves have a name")
            appendLine()

            table("BY ORIGIN", profile.origins, profile.samples, rows)
            appendLine()
            table("BY PACKAGE", profile.packages, profile.samples, rows)
            if (profile.modules.isNotEmpty()) {
                appendLine()
                table("BY MODULE", profile.modules, profile.samples, rows)
            }
        }

    private fun StringBuilder.table(
        title: String,
        all: List<ProfileRow>,
        samples: Long,
        rows: Int,
    ) {
        appendLine(title)
        appendLine("  ${"".padEnd(NAME_WIDTH)}${"self".padStart(10)}${"total".padStart(12)}")
        val shown = all.filter { it.self > 0 || it.total > 0 }.take(rows)
        for (row in shown) {
            append("  ")
            append(row.name.take(NAME_WIDTH).padEnd(NAME_WIDTH))
            append("${group(row.self)} ${percent(row.self, samples)}".padStart(16))
            append("${group(row.total)} ${percent(row.total, samples)}".padStart(18))
            appendLine()
        }
        // The tail is a row rather than a silence: a reader who sees twenty rows and no remainder has
        // no way to know whether they are looking at all of it.
        val rest = all.filter { it.self > 0 || it.total > 0 }.drop(rows)
        if (rest.isNotEmpty()) {
            appendLine("  ${"${rest.size} more rows".padEnd(NAME_WIDTH)}${group(rest.sumOf { it.self }).padStart(10)}")
        }
    }

    private fun percent(
        part: Long,
        whole: Long,
    ): String = if (whole == 0L) "0.0%" else "${(1000.0 * part / whole).toInt() / 10.0}%"

    private fun group(value: Long): String =
        value
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
}
