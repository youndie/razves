package io.github.youndie.razves.profile

import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.read.BinaryImage
import io.github.youndie.razves.read.ElfReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfilingTest {
    private fun image(): BinaryImage {
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        builder.text(address = TEXT, size = 0x400)
        builder.symbol(ElfBuilder.SymbolSpec("kfun:io.ktor.client#request(){}", TEXT, 64, text))
        builder.symbol(ElfBuilder.SymbolSpec("kfun:io.ktor.client.engine#send(){}", TEXT + 64, 64, text))
        builder.symbol(ElfBuilder.SymbolSpec("kfun:kotlin.collections#map(){}", TEXT + 128, 64, text))
        builder.symbol(ElfBuilder.SymbolSpec("ossl_aes_gcm_encrypt", TEXT + 192, 64, text))
        // 0x1100 onwards is inside the section and owned by nobody.
        return ElfReader.read(builder.build(), "app.kexe")
    }

    @Test
    fun theLeafDecidesSelfAndTheWholeStackDecidesTotal() {
        // Two samples, both executing inside `kotlin.collections`, both called from `io.ktor.client`.
        // The self time is the leaf; the total is what the call passed through.
        val stacks =
            listOf(
                longArrayOf(TEXT + 130, TEXT + 70, TEXT + 10),
                longArrayOf(TEXT + 140, TEXT + 70, TEXT + 10),
            )

        val profile = Profiling.of(image(), stacks)

        val packages = profile.packages.associateBy { it.name }
        assertEquals(2, packages.getValue("kotlin.collections").self, "both leaves are in collections")
        assertEquals(2, packages.getValue("kotlin.collections").total)
        assertEquals(0, packages.getValue("io.ktor.client").self, "the caller is executing nothing itself")
        assertEquals(2, packages.getValue("io.ktor.client").total, "both stacks passed through it")
    }

    @Test
    fun recursionAddsOneToTotalPerStackRatherThanPerFrame() {
        // The same package five frames deep in one sample. Counting per frame would make a row total
        // five samples out of one, and no invariant downstream could catch it.
        val deep = longArrayOf(TEXT + 130, TEXT + 132, TEXT + 134, TEXT + 136, TEXT + 138)

        val profile = Profiling.of(image(), listOf(deep))

        val collections = profile.packages.single { it.name == "kotlin.collections" }
        assertEquals(1, collections.self)
        assertEquals(1, collections.total, "one sample cannot be five")
        assertEquals(5, profile.frames, "the frames are still counted, they are just not five samples")
    }

    @Test
    fun theTwoWaysOfHavingNoOwnerAreDifferentRows() {
        // Inside the binary and unowned is not the same fact as not being in the binary at all, and a
        // profile that prints one number for both is a profile that cannot be acted on: the first is
        // razves failing to name something, the second is libc.
        val stacks =
            listOf(
                longArrayOf(TEXT + 0x300),
                longArrayOf(0x7f0000000000L),
            )

        val profile = Profiling.of(image(), stacks)

        val origins = profile.origins.associateBy { it.name }
        assertEquals(1, origins.getValue(Profile.NO_SYMBOL).self)
        assertEquals(1, origins.getValue(Profile.OUTSIDE).self)
        assertEquals(0.0, profile.namedShare, "neither sample was named")
    }

    @Test
    fun everySampleLandsInExactlyOneOriginRowAndTheSumIsTheInvariant() {
        val stacks =
            listOf(
                longArrayOf(TEXT + 10),
                longArrayOf(TEXT + 200),
                longArrayOf(TEXT + 0x300),
                longArrayOf(0x7f0000000000L),
                longArrayOf(TEXT + 130, TEXT + 10),
            )

        val profile = Profiling.of(image(), stacks)

        assertEquals(5, profile.samples)
        assertEquals(5, profile.origins.sumOf { it.self }, "the constructor would have refused otherwise")
        assertEquals(5, profile.packages.sumOf { it.self })
        assertEquals(1, profile.origins.single { it.name == "c" }.self, "the OpenSSL frame is C")
        assertEquals(0.6, profile.namedShare, "three of five leaves have a symbol")
    }

    @Test
    fun anEmptyProfileIsAProfileRatherThanAFailure() {
        // Nothing sampled - a program that was idle, or a window in which the sampler was off. Every
        // origin row is present and zero, because a missing row is a row nobody notices.
        val profile = Profiling.of(image(), emptyList())

        assertEquals(0, profile.samples)
        assertTrue(profile.origins.isNotEmpty(), "the origin rows are always there")
        assertEquals(0, profile.origins.sumOf { it.self })
    }

    @Test
    fun withoutKlibsTheModuleRowSaysSoRatherThanBeingAbsent() {
        val profile = Profiling.of(image(), listOf(longArrayOf(TEXT + 10)))

        assertEquals(Profiling.NO_KLIBS, profile.modules.single { it.self > 0 }.name)
    }

    @Test
    fun droppedSamplesAreCarriedIntoTheProfile() {
        // A profile that lost two thirds of its samples and does not say so is a profile whose
        // percentages are wrong in a way nobody can see.
        val profile = Profiling.of(image(), listOf(longArrayOf(TEXT + 10)), dropped = 17_245)

        assertEquals(17_245, profile.dropped)
    }

    private companion object {
        const val TEXT = 0x1000L
    }
}
