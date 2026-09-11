package io.github.youndie.razves.klib

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * razves' own DEFLATE against the JVM's, on every klib it can find.
 *
 * This is the second-implementation oracle of
 * [research D7](../../../../../../../docs/research/research-architecture.md), and it is the one
 * place in the project where it is worth the trouble. A hand-written decompressor either produces
 * the right bytes or produces plausible rubbish, and nothing about a klib manifest full of rubbish
 * looks wrong — it just fails to parse, or parses into a module name nobody notices is missing.
 * `java.util.zip.Inflater` is a thirty-year-old reference implementation that happens to be on the
 * classpath of this one source set, so the comparison costs nothing here and is impossible in the
 * native tests, which is exactly the asymmetry that makes it worth doing here.
 *
 * It skips itself, by name, when there is nothing to read.
 */
class InflateOracleTest {
    @Test
    fun everyEntryOfEveryKlibInflatesToWhatTheJvmSays() {
        val klibs = klibFiles()
        if (klibs.isEmpty()) {
            println(
                "SKIPPED InflateOracleTest: no .klib archives found. Set $KLIB_DIR_PROPERTY to a directory of them.",
            )
            return
        }

        var entries = 0
        var bytes = 0L
        var deflated = 0
        for (file in klibs) {
            val data = file.readBytes()
            val ours = Zip.entries(data)
            ZipFile(file).use { theirs ->
                assertEquals(theirs.size(), ours.size, "${file.name}: razves found a different number of entries")
                for (entry in ours) {
                    val reference = theirs.getEntry(entry.name)
                    assertTrue(reference != null, "${file.name}: the JVM does not have an entry called ${entry.name}")
                    if (entry.compressionMethod != 0) deflated++
                    val expected = theirs.getInputStream(reference).readBytes()
                    assertContentEquals(expected, Zip.read(data, entry), "${file.name}!${entry.name}")
                    entries++
                    bytes += expected.size
                }
            }
        }
        println(
            "razves inflated $entries entries ($deflated of them compressed, $bytes bytes) from ${klibs.size} klibs, byte for byte as the JVM does",
        )
        assertTrue(deflated > 0, "an oracle that only ever saw stored entries has checked nothing")
    }

    @Test
    fun everyKlibNamesItselfAndItsPackages() {
        val klibs = klibFiles()
        if (klibs.isEmpty()) {
            println("SKIPPED InflateOracleTest: no .klib archives found.")
            return
        }
        val read = klibs.map { KlibReader.readArchive(it.readBytes(), it.name) }
        val map = PackageToModule(read)

        val ambiguous = map.declaredPackages.filter { map.resolve(it) is ModuleOwner.Ambiguous }
        println(
            "${read.size} klibs, ${map.moduleCount} modules, ${map.declaredPackages.size} packages, " +
                "${ambiguous.size} of them declared by more than one module: ${ambiguous.sorted().take(12)}",
        )

        assertTrue(read.all { it.uniqueName.isNotBlank() }, "a klib with no name")
        assertTrue(map.declaredPackages.isNotEmpty())
        // Measured over 141 linuxX64 klibs on 2026-09-11 through `klib info`: 9 packages of 568,
        // 1.6%. That figure is what research D5 rests on - an ambiguity this small is worth reporting
        // honestly and would not be worth a tie-break even if one were defensible. The bound here is
        // deliberately loose, because the set of klibs on any given machine is whatever that machine
        // happens to have built; what it guards is the shape of the answer, not the digits.
        assertTrue(
            ambiguous.size.toDouble() / map.declaredPackages.size < 0.05,
            "${ambiguous.size} of ${map.declaredPackages.size} packages are ambiguous, far more than the " +
                "1.6% measured when D5 was decided - which usually means empty intermediate package " +
                "fragments are being counted as declarations",
        )
    }

    private fun klibFiles(): List<File> {
        val configured = System.getProperty(KLIB_DIR_PROPERTY) ?: return emptyList()
        val all =
            configured
                .split(File.pathSeparatorChar)
                .map(::File)
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name.endsWith(".klib") } }
                .toList()
        // One target, because a package map built across targets is not one map: the same library
        // appears once per platform and every package it has looks declared several times over. The
        // research figure this test is held against - 9 ambiguous packages of 568 - was measured over
        // the linuxX64 set, so the sample has to be the same shape or the comparison means nothing.
        val oneTarget = all.filter { it.name.contains("linuxX64Main") }
        return (if (oneTarget.size >= MIN_KLIBS) oneTarget else all).take(MAX_KLIBS)
    }

    private companion object {
        const val KLIB_DIR_PROPERTY = "RAZVES_KLIB_DIR"

        /** Enough to be convincing without turning a unit test into a disk sweep. */
        const val MAX_KLIBS = 200

        /** Below this the single-target filter is not worth applying; take whatever is there. */
        const val MIN_KLIBS = 20
    }
}
