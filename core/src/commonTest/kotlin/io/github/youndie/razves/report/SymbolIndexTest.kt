package io.github.youndie.razves.report

import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.read.ElfReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SymbolIndexTest {
    /** `Symbol(name, address, size)` triples in one `.text`, read back through the real ELF reader. */
    private fun index(vararg symbols: Triple<String, Long, Long>): SymbolIndex {
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        builder.text(address = TEXT, size = 0x400)
        symbols.forEach { (name, address, size) ->
            builder.symbol(ElfBuilder.SymbolSpec(name, address, size, text))
        }
        return SymbolIndex.of(ElfReader.read(builder.build(), "app.kexe"))
    }

    private fun symbol(
        name: String,
        address: Long,
        size: Long,
    ) = Triple(name, address, size)

    @Test
    fun anAddressInsideASymbolFindsIt() {
        val index = index(symbol("kfun:alpha#one", TEXT, 64), symbol("kfun:alpha#two", TEXT + 64, 64))

        assertEquals("kfun:alpha#one", index.at(TEXT)?.name, "the first byte of the first symbol")
        assertEquals("kfun:alpha#one", index.at(TEXT + 63)?.name, "its last byte")
        assertEquals("kfun:alpha#two", index.at(TEXT + 64)?.name, "the first byte of the next one")
        assertEquals("kfun:alpha#two", index.at(TEXT + 127)?.name, "the last byte of the last symbol")
    }

    @Test
    fun anAddressInAGapIsAMissRatherThanTheNameBeforeIt() {
        // "The nearest symbol" is how a profiler invents a hot function that was never called. Padding
        // between two functions belongs to neither.
        val index = index(symbol("kfun:alpha#one", TEXT, 32), symbol("kfun:alpha#two", TEXT + 64, 32))

        assertNull(index.at(TEXT + 32), "the first byte past the end of a symbol")
        assertNull(index.at(TEXT + 63), "the byte before the next one starts")
        assertNull(index.at(TEXT - 1), "before the section")
        assertNull(index.at(TEXT + 0x400), "past the section")
    }

    @Test
    fun aSymbolOfNoSizeOwnsNothing() {
        // Assembly labels and section markers have size 0. They own no byte, so they can answer for
        // no address - and must not swallow the range of the symbol that really covers it.
        val index = index(symbol("label", TEXT, 0), symbol("kfun:alpha#one", TEXT, 64))

        assertEquals("kfun:alpha#one", index.at(TEXT)?.name)
        assertEquals("kfun:alpha#one", index.at(TEXT + 32)?.name)
    }

    @Test
    fun theIndexAnswersWithTheSymbOLTheSweepChargedTheByteTo() {
        // The point of building this from the reconciliation rather than from the symbol table. Two
        // symbols at one address: the sweep gives the bytes to the larger, and an index built from
        // the raw table would have returned the other one about half the time.
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        builder.text(address = TEXT, size = 0x400)
        builder.symbol(ElfBuilder.SymbolSpec("kfun:alpha#definition", TEXT, 128, text))
        builder.symbol(ElfBuilder.SymbolSpec("alias", TEXT, 64, text))
        val image = ElfReader.read(builder.build(), "app.kexe")
        val reconciliation = Attribution.of(image)
        val index = SymbolIndex.of(reconciliation)

        val charged = reconciliation.sections.flatMap { it.owners }
        for (extent in charged) {
            for (address in listOf(extent.start, extent.end - 1)) {
                assertSame(
                    extent.symbol,
                    index.at(address),
                    "the index and the sweep disagree at 0x${address.toString(16)}",
                )
            }
        }
        assertEquals("kfun:alpha#definition", index.at(TEXT)?.name)
    }

    @Test
    fun everyByteASectionHasAnOwnerForIsFoundByTheIndex() {
        // The completeness check: walk the ranges the sweep produced and ask the index about every
        // boundary. A lookup that is right in the middle and wrong at the edges is the shape this
        // kind of code fails in.
        val image =
            ElfBuilder()
                .apply {
                    val text = nextSectionIndex
                    text(address = TEXT, size = 0x400)
                    symbol(ElfBuilder.SymbolSpec("kfun:a#one", TEXT, 100, text))
                    symbol(ElfBuilder.SymbolSpec("kfun:a#two", TEXT + 100, 1, text))
                    symbol(ElfBuilder.SymbolSpec("kfun:a#three", TEXT + 101, 299, text))
                }.build()
                .let { ElfReader.read(it, "app.kexe") }
        val reconciliation = Attribution.of(image)
        val index = SymbolIndex.of(reconciliation)

        var checked = 0
        for (extent in reconciliation.sections.flatMap { it.owners }) {
            var address = extent.start
            while (address < extent.end) {
                assertSame(extent.symbol, index.at(address), "at 0x${address.toString(16)}")
                checked++
                address++
            }
        }
        assertEquals(400, checked, "a run that checked nothing would pass every assertion above")
    }

    private companion object {
        const val TEXT = 0x1000L
    }
}
