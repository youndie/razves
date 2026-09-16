package io.github.youndie.razves.report

/**
 * Which number the budget is set on.
 *
 * Three defensible answers and the choice is not settled — see
 * [B-20](../../../../../../../docs/backlog/B-20-decide-the-budget-unit.md). [FILE_SIZE] is the
 * default because it is the number that ends up in the argument; it is also the jumpiest, moving
 * with the symbol table, which is 19–21% of a Kotlin/Native binary and grows with every symbol name
 * added.
 */
public enum class Measure {
    /** What a user downloads. */
    FILE_SIZE,

    /** What the loader maps, minus everything `strip` would remove. The most stable, and not what ships. */
    ALLOCATED,
}

/**
 * The gate: is this binary over budget, and if so, what moved.
 *
 * **The message names the rows.** That is the whole design and the reason this exists after
 * [DiffDocument] rather than before it. A 3% budget is tripped by a Ktor patch release as easily as
 * by a mistake, and "the total grew 4.1%" gives the reader nothing to decide with — so the gate gets
 * commented out the first week it fires. "`io.ktor.client` +180 KB, `openssl` +1.2 MB" turns a red
 * build into a decision.
 *
 * No Gradle types here: the comparison is arithmetic and a string, and keeping it in `core` is what
 * lets it be tested without a daemon.
 */
public object Budget {
    public fun check(request: BudgetRequest): BudgetVerdict {
        // A GATE THAT CHECKS NOTHING SAYS SO. Not having a rule is a legitimate state - a repository
        // may want the report and not the gate, and a debug binary is ungated until somebody asks -
        // but it is indistinguishable, in a build log, from a rule that passed. That is how a gate
        // stays green for a year while measuring nothing, so the one case where razves can tell the
        // difference gets a sentence of its own, and it names what to write to end it.
        if (request.budgetBytes == null && request.deltaFraction == null) {
            return BudgetVerdict(breached = false, message = unruled(request))
        }
        val rules =
            buildList {
                request.budgetBytes?.let { add(checkCeiling(request, it)) }
                request.deltaFraction?.let { add(checkDelta(request, it)) }
            }
        val breaches = rules.filterNotNull()
        return if (breaches.isEmpty()) {
            BudgetVerdict(breached = false, message = passed(request))
        } else {
            BudgetVerdict(breached = true, message = breaches.joinToString("\n\n"))
        }
    }

    private fun checkCeiling(
        request: BudgetRequest,
        budget: Long,
    ): String? {
        val actual = request.measured
        if (actual <= budget) return null
        return buildString {
            appendLine("${request.binary} is over its size budget.")
            appendLine("  ${request.measure.label}: ${group(actual)}")
            appendLine("  budget:    ${group(budget)}")
            appendLine("  over by:   ${group(actual - budget)}")
            appendLine()
            append(largestRows(request))
        }
    }

    /**
     * Growth against the committed baseline.
     *
     * **A missing baseline fails rather than passing.** Treating it as zero growth is how a gate ends
     * up green for a year while measuring nothing, and the message names the task that writes one
     * because a refusal that does not say what to do instead is only an obstacle.
     */
    private fun checkDelta(
        request: BudgetRequest,
        allowed: Double,
    ): String? {
        val diff =
            request.diff
                ?: return "${request.binary} has a growth budget and no baseline to measure it against. " +
                    "Run ${request.baselineTaskName} and commit the file it writes - a baseline that is " +
                    "not in the repository says something different on every machine."
        val fraction = diff.fraction ?: return null
        if (fraction <= allowed) return null
        return buildString {
            appendLine("${request.binary} grew more than its budget allows.")
            appendLine("  was:     ${group(diff.before)}")
            appendLine("  now:     ${group(diff.after)}")
            appendLine("  growth:  ${signed(diff.delta)} bytes, ${percent(fraction)}")
            appendLine("  allowed: ${percent(allowed)}")
            appendLine()
            append(TextDiff.render(diff, request.rows))
        }
    }

    private fun unruled(request: BudgetRequest): String =
        buildString {
            append("${request.binary}: ${request.measure.label} ${group(request.measured)}")
            append(" - no size rule is set for it, so nothing was checked")
            request.rulesHint?.let { append(". Set one with $it") }
            append(".")
        }

    private fun passed(request: BudgetRequest): String =
        buildString {
            append("${request.binary}: ${request.measure.label} ${group(request.measured)}")
            request.budgetBytes?.let { append(", ${group(it - request.measured)} under a budget of ${group(it)}") }
            request.diff?.let { append("; ${signed(it.delta)} against the baseline") }
        }

    /**
     * What a reader should look at first when there is no diff to show them.
     *
     * An absolute budget can be breached on the very first build, when there is nothing to compare
     * against — and "you are 4 MB over" without a table is the same dead end as a total-only diff.
     */
    private fun largestRows(request: BudgetRequest): String =
        buildString {
            appendLine("The largest things in it:")
            for (row in request.report.origins
                .sortedByDescending { it.bytes }
                .take(request.rows)) {
                if (row.bytes > 0) appendLine("  ${row.name.lowercase().padEnd(20)}${group(row.bytes)}")
            }
            val packages = request.report.packages.take(request.rows)
            if (packages.isNotEmpty()) {
                appendLine("Largest Kotlin packages:")
                for (row in packages) appendLine("  ${row.name.padEnd(40)}${group(row.bytes)}")
            }
        }

    /**
     * A share, with enough places to be worth printing.
     *
     * One decimal is right for the numbers a reader remembers and wrong for the ones that decide a
     * build: the first version of this message read "growth: 0.0%, and 0.0% is allowed", because a
     * 0.005% rise against a 0% allowance rounds both sides to the same figure and the sentence stops
     * making sense. So a value that would round to zero without being zero gets four more places -
     * and the byte delta is printed beside it either way, because that one needs no rounding at all.
     */
    private fun percent(fraction: Double): String {
        val tenths = (fraction * 1000).toLong()
        if (tenths != 0L || fraction == 0.0) {
            return "${tenths / 10}.${if (tenths < 0) -tenths % 10 else tenths % 10}%"
        }
        val small = (fraction * 1_000_000).toLong()
        val sign = if (small < 0) "-" else ""
        val absolute = if (small < 0) -small else small
        return "$sign${absolute / 10_000}.${(absolute % 10_000).toString().padStart(4, '0')}%"
    }

    private fun signed(value: Long): String = if (value >= 0) "+${group(value)}" else "-${group(-value)}"

    private fun group(value: Long): String =
        value
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
}

/** Everything the gate needs, and nothing that belongs to a build system. */
public data class BudgetRequest(
    val report: ReportDocument,
    /** Null when there is no committed baseline. Not the same as "nothing changed". */
    val diff: DiffDocument?,
    val budgetBytes: Long?,
    val deltaFraction: Double?,
    val measure: Measure = Measure.FILE_SIZE,
    val baselineTaskName: String = "the baseline task",
    /**
     * How a reader would give this binary a rule, in the words of whatever is running the check.
     *
     * Only ever printed when there is no rule at all. `core` does not know what a Gradle DSL looks
     * like, and the caller that does should not have to rebuild the rest of the sentence to add it.
     */
    val rulesHint: String? = null,
    val rows: Int = 8,
) {
    val binary: String get() = report.binary

    val measured: Long
        get() =
            when (measure) {
                Measure.FILE_SIZE -> report.fileSize
                Measure.ALLOCATED -> report.allocatedBytes
            }
}

/** The answer, and the words to print either way. */
public data class BudgetVerdict(
    val breached: Boolean,
    val message: String,
)

private val Measure.label: String
    get() =
        when (this) {
            Measure.FILE_SIZE -> "file size"
            Measure.ALLOCATED -> "allocated"
        }
