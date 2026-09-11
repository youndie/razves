package io.github.youndie.razves.profile

import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.klib.Inflate
import io.github.youndie.razves.read.BinaryImage
import io.github.youndie.razves.read.ElfReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PprofTest {
    private fun image(): BinaryImage {
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        builder.text(address = TEXT, size = 0x400)
        builder.symbol(ElfBuilder.SymbolSpec("kfun:io.ktor.client#request(){}", TEXT, 64, text))
        builder.symbol(ElfBuilder.SymbolSpec("kfun:kotlin.collections#map(){}", TEXT + 64, 64, text))
        builder.symbol(ElfBuilder.SymbolSpec("ossl_aes_gcm_encrypt", TEXT + 128, 64, text))
        return ElfReader.read(builder.build(), "app.kexe")
    }

    /** The payload of the gzip container, read back with razves own inflater. */
    private fun inflated(blob: ByteArray): ByteArray {
        assertEquals(0x1F, blob[0].toInt() and 0xFF, "gzip magic")
        assertEquals(0x8B, blob[1].toInt() and 0xFF, "gzip magic")
        assertEquals(0x08, blob[2].toInt() and 0xFF, "the compression method is DEFLATE")
        return Inflate.raw(blob, from = 10, length = blob.size - 18, expectedSize = 0)
    }

    @Test
    fun theContainerIsAGzipRazvesOwnInflaterCanRead() {
        // The closed loop: razves writes a gzip of stored blocks because it has no compressor, and
        // its own DEFLATE reader - written for klibs - is a reader that had never seen one.
        val blob = Pprof.of(image(), listOf(longArrayOf(TEXT + 10)))

        val payload = inflated(blob)

        assertTrue(payload.isNotEmpty(), "a container that unpacks to nothing is not a profile")
        // The CRC32 and the length in the trailer are what a real gzip reader checks first.
        val length =
            (blob[blob.size - 4].toLong() and 0xFF) or
                ((blob[blob.size - 3].toLong() and 0xFF) shl 8) or
                ((blob[blob.size - 2].toLong() and 0xFF) shl 16) or
                ((blob[blob.size - 1].toLong() and 0xFF) shl 24)
        assertEquals(payload.size.toLong(), length, "the trailer length must equal what was stored")
    }

    @Test
    fun theKotlinNameIsWhatAViewerShowsAndTheMangledOneIsKept() {
        // pprof has two name fields for exactly this: what to display, and what the binary calls it.
        // A viewer that shows `kfun:io.ktor.client#request(){}` is a viewer nobody reads twice.
        val payload = inflated(Pprof.of(image(), listOf(longArrayOf(TEXT + 10)))).decodeToString()

        assertTrue("io.ktor.client#request" in payload, "the readable name is in the string table")
        assertTrue("kfun:io.ktor.client#request" in payload, "and so is the mangled one")
    }

    @Test
    fun aFrameWithNoSymbolStillGetsAFunctionSayingWhatItIs() {
        // A viewer with a hole where the loader should be is a viewer whose percentages do not add
        // up, and no reader can tell which frames went missing.
        val payload =
            inflated(
                Pprof.of(image(), listOf(longArrayOf(0x7f0000000000L), longArrayOf(TEXT + 0x300))),
            ).decodeToString()

        assertTrue(Profile.OUTSIDE in payload)
        assertTrue(Profile.NO_SYMBOL in payload)
    }

    @Test
    fun identicalStacksAreOneSampleWithACount() {
        // A profile of a long run is mostly the same stack over and over. One entry per signal
        // delivered would make the file linear in the sampling rate for no information at all.
        val stack = longArrayOf(TEXT + 70, TEXT + 10)
        val one = Pprof.of(image(), listOf(stack))
        val hundred = Pprof.of(image(), List(100) { stack })

        assertTrue(
            hundred.size < one.size + 8,
            "a hundred identical stacks grew the file by ${hundred.size - one.size} bytes, so they " +
                "were not folded into a count",
        )
    }

    @Test
    fun whatTheSamplerDroppedIsInTheFileRatherThanOnlyInALog() {
        // pprof has no field for it, and a profile that lost a third of its samples without saying so
        // is a profile whose percentages are wrong in a way the reader cannot see. The comment field
        // is shown by every viewer and interpreted by none, which is exactly what this needs.
        val payload =
            inflated(Pprof.of(image(), listOf(longArrayOf(TEXT + 10)), dropped = 17_245)).decodeToString()

        assertTrue("17245 samples were dropped" in payload, payload.take(400))
    }

    @Test
    fun anEmptyProfileIsStillAReadableContainer() {
        val blob = Pprof.of(image(), emptyList())

        // Not an empty file: a profile with no samples still declares what its values would have
        // meant, and a viewer that is handed a truly empty proto says the file is corrupt rather
        // than saying the program was idle.
        val payload = inflated(blob).decodeToString()
        assertTrue("samples" in payload && "count" in payload, "the value type is still declared")
        assertTrue(blob.size < 100, "and nothing else is in it: ${blob.size} bytes")
    }

    private companion object {
        const val TEXT = 0x1000L
    }
}
