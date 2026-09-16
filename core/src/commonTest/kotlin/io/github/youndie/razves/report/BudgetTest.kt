package io.github.youndie.razves.report

import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.read.ElfReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BudgetTest {
    private fun document(vararg symbols: String): ReportDocument {
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        builder.text(address = 0x1000, size = 64 * (symbols.size + 1))
        symbols.forEachIndexed { i, name ->
            builder.symbol(ElfBuilder.SymbolSpec(name, 0x1000 + 64L * i, 64, text))
        }
        return ReportDocument.of(
            Attribution.report(ElfReader.read(builder.build(), "app.kexe"), packageDepth = Int.MAX_VALUE),
        )
    }

    private fun request(
        report: ReportDocument = document("kfun:alpha.one#internal"),
        diff: DiffDocument? = null,
        budgetBytes: Long? = null,
        deltaFraction: Double? = null,
        measure: Measure = Measure.FILE_SIZE,
    ) = BudgetRequest(
        report = report,
        diff = diff,
        budgetBytes = budgetBytes,
        deltaFraction = deltaFraction,
        measure = measure,
        baselineTaskName = "sizeBaselineWriteLinuxX64DebugExecutable",
        rulesHint = "binarySize { debug { budget = 40.MiB } }",
    )

    @Test
    fun aBinaryUnderBudgetPassesAndSaysByHowMuch() {
        val report = document("kfun:alpha.one#internal")

        val verdict = Budget.check(request(report, budgetBytes = report.fileSize * 2))

        assertFalse(verdict.breached)
        assertTrue("under a budget of" in verdict.message)
    }

    @Test
    fun aBinaryOverBudgetFailsAndNamesWhatIsInIt() {
        // An absolute budget can be breached on the very first build, when there is nothing to compare
        // against - and "you are 4 MB over" without a table is the same dead end as a total-only diff.
        val report = document("kfun:alpha.one#internal", "kfun:beta.two#internal")

        val verdict = Budget.check(request(report, budgetBytes = 100))

        assertTrue(verdict.breached)
        assertTrue("over its size budget" in verdict.message)
        assertTrue("over by:" in verdict.message)
        assertTrue("The largest things in it:" in verdict.message)
        assertTrue("alpha" in verdict.message, "the packages are named, not merely counted")
    }

    @Test
    fun aGrowthBudgetWithNoBaselineFailsLoudlyAndNamesTheTask() {
        // Treating a missing baseline as zero growth is how a gate ends up green for a year while
        // measuring nothing.
        val verdict = Budget.check(request(deltaFraction = 0.03))

        assertTrue(verdict.breached)
        assertTrue("no baseline" in verdict.message)
        assertTrue("sizeBaselineWriteLinuxX64DebugExecutable" in verdict.message)
        assertTrue("commit the file it writes" in verdict.message)
    }

    @Test
    fun growthWithinTheDeltaPasses() {
        val before = document("kfun:alpha.one#internal")
        val after = before.copy(fileSize = before.fileSize + before.fileSize / 100)

        val verdict = Budget.check(request(after, diff = DiffDocument.of(before, after), deltaFraction = 0.03))

        assertFalse(verdict.breached)
        assertTrue("against the baseline" in verdict.message)
    }

    @Test
    fun growthBeyondTheDeltaFailsAndPrintsTheRowsThatCausedIt() {
        // The reason the diff was built before the gate: a 3% budget is tripped by a dependency bump
        // as easily as by a mistake, and a total gives the reader nothing to decide with.
        val before = document("kfun:alpha.one#internal")
        val after = document("kfun:alpha.one#internal", "kfun:beta.two#internal", "kfun:beta.three#internal")

        val verdict = Budget.check(request(after, diff = DiffDocument.of(before, after), deltaFraction = 0.001))

        assertTrue(verdict.breached)
        assertTrue("grew more than its budget allows" in verdict.message)
        assertTrue("BY PACKAGE" in verdict.message)
        assertTrue("beta" in verdict.message)
        assertTrue("new" in verdict.message)
    }

    @Test
    fun aGrowthTooSmallForOneDecimalIsStillPrintedAsANumber() {
        // The first version of this message read "growth: 0.0%, and 0.0% is allowed", because a
        // 0.005% rise against a 0% allowance rounds both sides to the same figure and the sentence
        // stops making sense. The byte delta needs no rounding and is printed beside it.
        val before = document("kfun:alpha.one#internal")
        val after = before.copy(fileSize = before.fileSize + 528)

        val message = Budget.check(request(after, diff = DiffDocument.of(before, after), deltaFraction = 0.0)).message

        assertTrue("+528 bytes" in message, "the exact movement, which needs no rounding")
        assertTrue("allowed: 0.0%" in message)
        val growth = message.lines().single { it.startsWith("  growth:") }
        assertFalse(growth.endsWith("0.0%"), "a growth that rounds to zero gets more places: $growth")
    }

    @Test
    fun bothRulesCanBreachAtOnceAndBothAreReported() {
        val before = document("kfun:alpha.one#internal")
        val after = document("kfun:alpha.one#internal", "kfun:beta.two#internal")

        val verdict =
            Budget.check(
                request(after, diff = DiffDocument.of(before, after), budgetBytes = 100, deltaFraction = 0.0),
            )

        assertTrue(verdict.breached)
        assertTrue("over its size budget" in verdict.message)
        assertTrue("grew more than its budget allows" in verdict.message)
    }

    @Test
    fun noRulesAtAllIsNotABreach() {
        // A repository may want the report and not the gate. Configuring neither rule is a decision,
        // not an oversight, and it passes.
        val verdict = Budget.check(request())
        assertFalse(verdict.breached)
    }

    @Test
    fun aBinaryWithNoRuleSaysThatNothingWasCheckedAndHowToGiveItOne() {
        // The other half of the same decision, and the half that keeps it honest. "passed" and
        // "there was nothing to pass" are the same green task in a build log, which is how a debug
        // binary that nobody gated gets read for a year as a debug binary that is under budget. The
        // sentence has to name the absence, and then name the block that ends it - a refusal that
        // does not say what to do instead is only an obstacle.
        val verdict = Budget.check(request())

        assertFalse(verdict.breached)
        assertTrue("nothing was checked" in verdict.message, verdict.message)
        assertTrue("binarySize { debug { budget" in verdict.message, verdict.message)
        assertFalse("under a budget of" in verdict.message, "there is no budget to be under")
    }

    @Test
    fun aRuleThatPassesDoesNotClaimThereWasNoRule() {
        // The inverse, so the sentence above cannot be printed by everything.
        val report = document("kfun:alpha.one#internal")

        val message = Budget.check(request(report, budgetBytes = report.fileSize * 2)).message

        assertFalse("nothing was checked" in message, message)
    }

    @Test
    fun theMeasureDecidesWhichNumberIsCompared() {
        val report = document("kfun:alpha.one#internal")
        val between = (report.allocatedBytes + report.fileSize) / 2

        // Allocated is smaller than the file, because the file also carries the symbol table - so a
        // budget between the two passes on one measure and fails on the other. Which of them is the
        // default is B-20's question; that they are different numbers is not.
        assertTrue(report.allocatedBytes < report.fileSize)
        assertFalse(Budget.check(request(report, budgetBytes = between, measure = Measure.ALLOCATED)).breached)
        assertTrue(Budget.check(request(report, budgetBytes = between, measure = Measure.FILE_SIZE)).breached)
    }

    @Test
    fun anEmptyBaselineDoesNotProduceAnInfiniteGrowth() {
        val before = document("kfun:alpha.one#internal").copy(fileSize = 0)
        val after = document("kfun:alpha.one#internal")

        val verdict = Budget.check(request(after, diff = DiffDocument.of(before, after), deltaFraction = 0.03))

        assertFalse(verdict.breached, "a share of nothing is not a number to fail a build on")
    }

    @Test
    fun theFailureMessageCarriesTheActualNumbersAndNotJustAVerdict() {
        val report = document("kfun:alpha.one#internal")

        val message = Budget.check(request(report, budgetBytes = 100)).message

        assertTrue(report.fileSize.toString().chunkedGrouped() in message, "the measured size, grouped")
        assertTrue("100" in message)
        assertEquals(1, message.lines().count { it.startsWith("  over by:") })
    }

    private fun String.chunkedGrouped(): String =
        reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
}
