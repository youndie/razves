package io.github.youndie.razves.report

import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.read.ElfReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiffTest {
    /** A binary with the named Kotlin functions, each 64 bytes, in `.text`. */
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

    @Test
    fun theReconciliationDeltasSumToTheChangeInFileSize() {
        // The identity that makes a diff checkable rather than merely plausible.
        val before = document("kfun:alpha.one#internal")
        val after = document("kfun:alpha.one#internal", "kfun:beta.two#internal")

        val diff = DiffDocument.of(before, after)

        assertEquals(diff.delta, diff.reconciliation.sumOf { it.delta })
        assertEquals(after.fileSize - before.fileSize, diff.delta)
    }

    @Test
    fun aPackageThatAppearedIsARowRatherThanAnOmission() {
        val diff =
            DiffDocument.of(
                document("kfun:alpha.one#internal"),
                document("kfun:alpha.one#internal", "kfun:beta.two#internal"),
            )

        val beta = diff.packages.single { it.name == "beta" }
        assertTrue(beta.appeared)
        assertEquals(0, beta.before)
        assertEquals(64, beta.after)
        assertEquals(64, beta.delta)
    }

    @Test
    fun aPackageThatVanishedIsARowToo() {
        val diff =
            DiffDocument.of(
                document("kfun:alpha.one#internal", "kfun:beta.two#internal"),
                document("kfun:alpha.one#internal"),
            )

        val beta = diff.packages.single { it.name == "beta" }
        assertTrue(beta.gone)
        assertEquals(-64, beta.delta)
    }

    @Test
    fun rowsThatDidNotMoveAreNotRows() {
        // A diff is an answer to "what moved". A row that did not move is not one, however large.
        val diff =
            DiffDocument.of(
                document("kfun:alpha.one#internal"),
                document("kfun:alpha.one#internal", "kfun:beta.two#internal"),
            )
        assertTrue(diff.packages.none { it.name == "alpha" }, "alpha is unchanged and should not be listed")
    }

    @Test
    fun theLargestMovementComesFirstWhicheverWayItWent() {
        val before = document("kfun:alpha.one#internal", "kfun:beta.two#internal", "kfun:beta.three#internal")
        val after = document("kfun:alpha.one#internal")

        val diff = DiffDocument.of(before, after)

        assertEquals("beta", diff.packages.first().name, "a 128-byte fall outranks a smaller rise")
    }

    @Test
    fun comparingAcrossSizeAlgorithmsIsRefused() {
        // A recorded symbol size and an address-derived one are not the same quantity: the second
        // includes whatever alignment follows a symbol. Subtracting them produces a number with no
        // meaning and no warning attached to it.
        val elf = document("kfun:alpha.one#internal")
        val machOish = elf.copy(sizeAlgorithm = "ADDRESS_DELTA")

        val failure = assertFailsWith<IllegalArgumentException> { DiffDocument.of(elf, machOish) }

        assertTrue(failure.message.orEmpty().contains("measured differently"))
        assertTrue(failure.message.orEmpty().contains("RECORDED"))
        assertTrue(failure.message.orEmpty().contains("ADDRESS_DELTA"))
    }

    @Test
    fun comparingDifferentTargetsIsRefused() {
        val linux = document("kfun:alpha.one#internal")
        val apple = linux.copy(targets = listOf("macos_arm64"))

        val failure = assertFailsWith<IllegalArgumentException> { DiffDocument.of(linux, apple) }

        assertTrue(failure.message.orEmpty().contains("different targets"))
    }

    @Test
    fun aDiffOfABinaryAgainstItselfIsEmpty() {
        // What the gate sees on a build that changed nothing. Every table has to be empty, not merely
        // summing to zero: a row that appears with a delta of zero is noise in a pull request.
        val same = document("kfun:alpha.one#internal")

        val diff = DiffDocument.of(same, same)

        assertEquals(0, diff.delta)
        assertTrue(diff.packages.isEmpty())
        assertTrue(diff.origins.isEmpty())
        assertTrue(diff.sections.isEmpty())
        assertTrue(diff.reconciliation.all { it.delta == 0L })
    }

    @Test
    fun theTextSaysWhatMovedRatherThanOnlyByHowMuch() {
        val diff =
            DiffDocument.of(
                document("kfun:alpha.one#internal"),
                document("kfun:alpha.one#internal", "kfun:beta.two#internal"),
            )

        val text = TextDiff.render(diff)

        assertTrue("BY PACKAGE" in text)
        assertTrue("beta" in text)
        assertTrue("new" in text, "a row that appeared says so rather than showing a rise from zero")
        assertTrue(Regex("""\+\d""").containsMatchIn(text), "a delta always carries its sign")
    }

    @Test
    fun theTextSaysNothingMovedWhenNothingDid() {
        val same = document("kfun:alpha.one#internal")
        val text = TextDiff.render(DiffDocument.of(same, same))
        assertTrue("nothing moved" in text)
    }

    @Test
    fun theShareIsAbsentRatherThanInfiniteAgainstAnEmptyBaseline() {
        val empty = document().copy(fileSize = 0)
        val diff = DiffDocument.of(empty, document("kfun:alpha.one#internal"))
        assertEquals(null, diff.fraction)
        assertTrue("%" !in TextDiff.render(diff).lines().first())
    }
}
