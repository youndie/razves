package io.github.youndie.razves.sampler

import io.github.youndie.razves.profile.Profile
import io.github.youndie.razves.profile.Profiling
import io.github.youndie.razves.profile.SampleDump
import io.github.youndie.razves.read.Binaries
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two halves meeting: a program samples itself, writes a dump, and razves names what it was
 * doing.
 *
 * **This is the test that found the defect every other test could not.** Written naively, the handler
 * calls `backtrace()` from inside itself, so frame 0 is the handler and frame 1 is the kernel
 * trampoline - every sample in every profile then has the same leaf, and razves reported one C
 * function as 100% of a workload that is mostly Kotlin. Nothing in the unit tests could see it,
 * because they hand the aggregation stacks that a test made up. Only a program that actually ran
 * could.
 */
class LiveProfileTest {
    private val probe: File? = System.getProperty("RAZVES_PROBE")?.let(::File)?.takeIf { it.isFile }

    private fun skipped(): Unit =
        println("SKIPPED LiveProfileTest: no probe binary; linuxX64 is the only target that links one.")

    @Test
    fun whatThePollerMissedIsCountedRatherThanQuietlyAbsent() {
        // There is no listener in the runtime - only `GC.lastGCInfo` to ask - so a poll slower than
        // the collection rate loses collections. That is not the defect; reporting the twelve it saw
        // as though they were all of them would be.
        val binary = probe ?: return skipped()

        fun run(pollEvery: Int): SampleDump {
            val dump = File.createTempFile("razves-gc", ".dump")
            val process =
                ProcessBuilder(binary.path, "1000", "60000", "wall", dump.path, pollEvery.toString())
                    .redirectErrorStream(true)
                    .start()
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the probe did not finish")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            return SampleDump.parse(dump.readText(), dump.path).also { dump.delete() }
        }

        val dense = run(64)
        val sparse = run(5_000)
        println(
            "gc: dense poll saw ${dense.collections.size} and missed ${dense.missedCollections}; " +
                "sparse saw ${sparse.collections.size} and missed ${sparse.missedCollections}",
        )

        assertTrue(dense.collections.size > 50, "this workload allocates; it must collect")
        assertEquals(0, dense.missedCollections, "a poll every 64 rounds should keep up")
        assertTrue(sparse.missedCollections > 0, "a poll every 5,000 rounds cannot have kept up")
        assertTrue(
            sparse.collections.size < dense.collections.size,
            "the sparse poll saw as many as the dense one, which would make the missed count fiction",
        )
        // And the arithmetic has to add up to roughly the same run: epochs are consecutive integers.
        val sparseTotal = sparse.collections.size + sparse.missedCollections
        assertTrue(
            sparseTotal > dense.collections.size / 2,
            "seen plus missed is $sparseTotal against ${dense.collections.size} collections observed densely",
        )

        val pauses = dense.collections.filter { it.pauseNs > 0 }
        assertTrue(pauses.isNotEmpty(), "every collection reported a zero pause, which cannot be")
    }

    @Test
    fun aProgramThatSampledItselfIsNamedByRazves() {
        val binary = probe ?: return skipped()
        val dump = File.createTempFile("razves", ".dump")

        val process =
            ProcessBuilder(binary.path, "1000", "20000", "wall", dump.path)
                .redirectErrorStream(true)
                .start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the probe did not finish")
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())

        val parsed = SampleDump.parse(dump.readText(), dump.path)
        val profile =
            Profiling.of(
                image = Binaries.read(binary.readBytes(), binary.name),
                stacks = parsed.stacks,
                dropped = parsed.dropped,
            )
        dump.delete()

        val byName = profile.packages.associateBy { it.name }
        println(
            "live profile of ${binary.name}: ${profile.samples} samples, " +
                "${(profile.namedShare * 1000).toInt() / 10.0}% named, top rows " +
                profile.packages
                    .filter { it.self > 0 }
                    .take(4)
                    .joinToString { "${it.name}=${it.self}" },
        )

        assertTrue(parsed.stacks.size > 100, "only ${parsed.stacks.size} samples came back")
        assertEquals(parsed.stacks.size.toLong(), profile.samples)
        // NOT a threshold on the named share, which is what this was and what a second machine
        // refused: 83.5% on one mac, 76.6% on a CI mac, 99.1% on Linux. That number measures how much
        // of the program lives outside the image - libsystem is dynamically linked on Apple targets
        // and statically present on this Linux build - which is a fact about the platform rather than
        // about razves.
        //
        // What razves is answerable for is the other kind of miss: an address INSIDE the image that
        // no symbol covers. That one is razves failing to name something, and it stays small
        // everywhere.
        val unnamedInside = profile.origins.singleOrNull { it.name == Profile.NO_SYMBOL }?.self ?: 0
        assertTrue(
            unnamedInside < profile.samples / 20,
            "$unnamedInside of ${profile.samples} leaves are inside the binary and unnamed",
        )

        // What the probe spends its time on is an ArrayList of boxed ints, so `kotlin.collections`
        // has to be executing - not merely on the stack.
        val collections = byName["kotlin.collections"]
        assertTrue(
            collections != null && collections.self > profile.samples / 10,
            "kotlin.collections is not where this program is executing: ${byName.keys.take(8)}",
        )

        // And the probe own package must be on the stack of every sample, because its `work` function
        // called all of it. A profile where the caller is missing is a profile whose stacks are
        // truncated.
        val own = byName["io.github.youndie"]
        assertTrue(
            own != null && own.total > profile.samples * 9 / 10,
            "the probe own package is on ${own?.total ?: 0} of ${profile.samples} stacks",
        )

        // The leaf must not be the sampler itself, which is the defect this test exists for.
        val sampler = profile.packages.singleOrNull { it.name == "razves.sampler" }
        assertTrue(sampler == null || sampler.self < profile.samples / 100, "the handler is in its own profile")
    }
}
