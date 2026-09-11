package io.github.youndie.razves.fixture

import io.github.youndie.razves.attribute.Origin
import io.github.youndie.razves.read.ElfReader
import io.github.youndie.razves.read.MachOReader
import io.github.youndie.razves.read.SizeAlgorithm
import io.github.youndie.razves.report.Attribution
import io.github.youndie.razves.report.SizeReport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Attribution from Kotlin source to a package row, on a binary this repository compiled.
 *
 * The two fixtures already in `core` cannot do this. A hand-built ELF says "given exactly these
 * bytes, the reader must produce exactly this", which is the right check for a reader and says
 * nothing about whether real Kotlin lands in the right package. A real third-party binary is the
 * opposite: it exercises everything and nobody can state the expected answer for it.
 *
 * Here the expected answer is known by construction. Three packages are declared, with declarations
 * in the three shapes the mangling distinguishes — a public top-level function, an `internal` one,
 * and a class member — and the assertion is that each lands in the package it was written in and
 * that **no package row is named after one of the declarations**, which is precisely the mistake the
 * two symbol forms invite and the one B-07 was built around.
 */
class CompiledFixtureTest {
    @Test
    fun theLinuxBinaryAttributesToThePackagesTheSourceDeclares() {
        val file = binary(LINUX) ?: return skipped("linuxX64")
        val report = Attribution.report(ElfReader.read(file.readBytes(), file.name), packageDepth = Int.MAX_VALUE)
        assertEquals(SizeAlgorithm.RECORDED, report.image.sizeAlgorithm)
        assertTheFixtureIsAttributed(report)
    }

    @Test
    fun theMacOsBinaryAttributesToTheSamePackages() {
        val file = binary(MACOS) ?: return skipped("macosArm64")
        val report = Attribution.report(MachOReader.read(file.readBytes(), file.name), packageDepth = Int.MAX_VALUE)
        assertEquals(SizeAlgorithm.ADDRESS_DELTA, report.image.sizeAlgorithm)
        assertTheFixtureIsAttributed(report)
    }

    private fun assertTheFixtureIsAttributed(report: SizeReport) {
        val rows = report.packages.associateBy { it.name }
        println(
            "${report.image.name}: ${report.reconciliation.fileSize} bytes, " +
                "${report.bytesOf(Origin.KOTLIN)} of Kotlin in ${report.packages.size} packages; the fixture's own: " +
                rows.filterKeys { it.startsWith(ROOT) }.map { (name, row) -> "$name=${row.bytes}" }.sorted(),
        )

        for (declared in DECLARED_PACKAGES) {
            val row = rows[declared]
            assertTrue(
                row != null,
                "razves found no row for $declared; it has ${rows.keys.filter { it.startsWith(ROOT) }}",
            )
            assertTrue(row.bytes > 0, "$declared has a row and no bytes")
        }

        // The failure this test exists to catch. An `internal` declaration is mangled with its own
        // name inside the container and no signature at all, so a parser that reads the container as
        // a package invents `razvesfixture.alpha.alphaInternalTopLevel` and does it for every private
        // declaration in every binary razves will ever read.
        val invented = rows.keys.filter { it.startsWith(ROOT) && it !in DECLARED_PACKAGES }
        assertTrue(
            invented.isEmpty(),
            "these rows are named after declarations rather than packages: $invented",
        )

        // Each declaration is where it was written, by symbol rather than only by total.
        val kotlinSymbols =
            report.reconciliation.sections
                .flatMap { it.owners }
                .map { it.symbol.name }
                .filter { it.substringAfter('_', it).startsWith("kfun:$ROOT") }
        for ((declaration, packageName) in DECLARATIONS) {
            val matches = kotlinSymbols.filter { it.contains(declaration) }
            assertTrue(matches.isNotEmpty(), "$declaration is not in the binary at all; did the linker drop it?")
            assertTrue(
                matches.all { it.contains(packageName) },
                "$declaration should be under $packageName; found $matches",
            )
        }

        assertTrue(report.bytesOf(Origin.KOTLIN) > 0)
        assertEquals(report.bytesOf(Origin.KOTLIN), report.packages.sumOf { it.bytes })
    }

    @Test
    fun theDepthTruncatesTheDeepestPackageAndNoOther() {
        val file = binary(LINUX) ?: return skipped("linuxX64")
        val image = ElfReader.read(file.readBytes(), file.name)

        val deep = Attribution.report(image, packageDepth = Int.MAX_VALUE).packages.map { it.name }
        val shallow = Attribution.report(image, packageDepth = 2).packages.map { it.name }

        assertTrue("$ROOT.gamma.deep" in deep)
        assertTrue("$ROOT.gamma.deep" !in shallow, "depth 2 cannot name a three-segment package")
        assertTrue(shallow.any { it == "$ROOT.gamma" }, "it is truncated rather than dropped")
    }

    private fun binary(property: String): File? = System.getProperty(property)?.let(::File)?.takeIf { it.isFile }

    private fun skipped(target: String) {
        println("SKIPPED CompiledFixtureTest: no $target binary; this host cannot link that target.")
    }

    private companion object {
        const val LINUX = "RAZVES_FIXTURE_LINUX"
        const val MACOS = "RAZVES_FIXTURE_MACOS"
        const val ROOT = "razvesfixture"

        val DECLARED_PACKAGES =
            setOf(
                "$ROOT.alpha",
                "$ROOT.beta",
                "$ROOT.gamma.deep",
                ROOT,
            )

        /** Every declaration the fixture writes, and the package it is written in. */
        val DECLARATIONS =
            listOf(
                "alphaPublicTopLevel" to "$ROOT.alpha",
                "alphaInternalTopLevel" to "$ROOT.alpha",
                "AlphaClass" to "$ROOT.alpha",
                "betaOne" to "$ROOT.beta",
                "betaTwo" to "$ROOT.beta",
                "deepOne" to "$ROOT.gamma.deep",
            )
    }
}
