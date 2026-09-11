package io.github.youndie.razves.profile

import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.read.ElfReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opens what razves wrote with the tool people actually use.
 *
 * **Every other test here checks the bytes razves meant to write.** This one checks the only thing
 * that matters about a file format: that the reader on the other side accepts it. The container is a
 * gzip of *stored* blocks - razves has an inflater and no compressor, so the payload is not
 * compressed at all - and whether a real gzip reader minds was a hypothesis until this ran.
 *
 * Skips by name where Go is not installed, because a check that passes by being absent is worse than
 * no check.
 */
class PprofReadableByGoTest {
    private fun go(): String? =
        System
            .getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .map { File(it, "go") }
            .firstOrNull { it.canExecute() }
            ?.path

    private fun skipped() = println("SKIPPED PprofReadableByGoTest: go is not on PATH, so pprof cannot be asked.")

    @Test
    fun goToolPprofReadsTheProfileAndNamesTheKotlinFunctions() {
        val go = go() ?: return skipped()

        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        builder.text(address = TEXT, size = 0x400)
        builder.symbol(ElfBuilder.SymbolSpec("kfun:io.ktor.client#request(){}", TEXT, 64, text))
        builder.symbol(ElfBuilder.SymbolSpec("kfun:kotlin.collections#map(){}", TEXT + 64, 64, text))
        val image = ElfReader.read(builder.build(), "app.kexe")

        // Ten samples in `map`, called from `request`; three in `request` itself. A viewer that reads
        // the file at all will show those two names and thirteen samples.
        val stacks = List(10) { longArrayOf(TEXT + 70, TEXT + 10) } + List(3) { longArrayOf(TEXT + 10) }
        val file = File.createTempFile("razves", ".pb.gz")
        file.writeBytes(Pprof.of(image, stacks))

        val process =
            ProcessBuilder(go, "tool", "pprof", "-top", "-nodecount=10", file.path)
                .redirectErrorStream(true)
                .start()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "go tool pprof did not finish")
        val output = process.inputStream.bufferedReader().readText()
        file.delete()

        println("go tool pprof on a razves profile:\n$output")
        assertEquals(0, process.exitValue(), "go tool pprof refused the file:\n$output")
        assertTrue("kotlin.collections#map" in output, "the Kotlin name is what the viewer shows")
        assertTrue("io.ktor.client#request" in output)
        assertTrue("13" in output, "thirteen samples went in, so thirteen come out")
    }

    private companion object {
        const val TEXT = 0x1000L
    }
}
