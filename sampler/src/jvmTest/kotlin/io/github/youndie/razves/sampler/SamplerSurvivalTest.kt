package io.github.youndie.razves.sampler

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does the sampled process come back.
 *
 * **This is the acceptance criterion of the whole module, and it cannot be written inside one
 * process.** The failure the sampler exists to avoid is a process that hangs or dies: a Kotlin
 * signal handler doing nothing but incrementing an atomic hung 3 runs in 10 at 100 Hz and 8 in 10 at
 * 1 kHz (research 1.1). A test running inside the sampled process observes neither - it is the thing
 * that disappears. So this starts the probe as a process, with a timeout, and counts what came back.
 *
 * Ten runs per rate is not a ritual number: at 100 Hz the Kotlin handler failed 30% of the time, and
 * ten runs put the chance of missing that below 3%.
 *
 * JVM-only because starting processes and timing them out is a thing to do from outside, and the
 * probe is a Linux binary - this test skips itself by name where there is none.
 */
class SamplerSurvivalTest {
    private val probe: File? =
        System.getProperty("RAZVES_PROBE")?.let(::File)?.takeIf { it.isFile }

    private fun skipped(): Unit =
        println("SKIPPED SamplerSurvivalTest: no probe binary; linuxX64 is the only target that links one.")

    private data class Outcome(
        val completed: Int,
        val timedOut: Int,
        val failed: Int,
        val samples: Long,
        val dropped: Long,
    )

    private fun run(
        hz: Int,
        runs: Int,
        clock: String = "wall",
    ): Outcome {
        val binary = probe ?: error("no probe")
        var completed = 0
        var timedOut = 0
        var failed = 0
        var samples = 0L
        var dropped = 0L
        repeat(runs) {
            val process =
                ProcessBuilder(binary.path, hz.toString(), "20000", clock)
                    .redirectErrorStream(true)
                    .start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                timedOut++
                return@repeat
            }
            val output = process.inputStream.bufferedReader().readText()
            if (process.exitValue() != 0) {
                failed++
                return@repeat
            }
            completed++
            samples += Regex("taken=(\\d+)")
                .find(output)
                ?.groupValues
                ?.get(1)
                ?.toLong() ?: 0
            dropped += Regex("dropped=(\\d+)")
                .find(output)
                ?.groupValues
                ?.get(1)
                ?.toLong() ?: 0
        }
        return Outcome(completed, timedOut, failed, samples, dropped)
    }

    @Test
    fun theProcessSurvivesEveryRateItIsAskedFor() {
        if (probe == null) return skipped()

        for (hz in listOf(100, 1000, 10_000)) {
            val outcome = run(hz, runs = 10)
            assertEquals(
                10,
                outcome.completed,
                "at $hz Hz: ${outcome.timedOut} hung, ${outcome.failed} died. A Kotlin handler " +
                    "failed this way 3 times in 10 at 100 Hz; a C one must not fail at all.",
            )
            assertTrue(outcome.samples > 0, "at $hz Hz nothing was sampled, so nothing was proven")
        }
    }

    @Test
    fun theProcessSurvivesTheCpuClockToo() {
        if (probe == null) return skipped()

        val outcome = run(1000, runs = 10, clock = "cpu")

        assertEquals(10, outcome.completed, "${outcome.timedOut} hung, ${outcome.failed} died")
        assertTrue(outcome.samples > 0)
    }

    @Test
    fun whatTheRingCouldNotHoldIsCountedRatherThanHidden() {
        if (probe == null) return skipped()

        // 10 kHz against a ring of 4,096 overruns it by design: the point is that the overrun is a
        // number in the output. A profile that lost two thirds of its samples and does not say so is
        // a profile whose percentages are wrong in a way nobody can see.
        val fast = run(10_000, runs = 3)
        val slow = run(100, runs = 3)

        assertTrue(fast.dropped > 0, "10 kHz into a 4,096-slot ring dropped nothing, which cannot be")
        assertEquals(0, slow.dropped.toInt(), "100 Hz should fit in the ring with room to spare")
    }

    @Test
    fun aSamplerThatWasNeverStartedTakesNothingAndStopsCleanly() {
        if (probe == null) return skipped()

        val outcome = run(0, runs = 3)

        assertEquals(3, outcome.completed)
        assertEquals(0, outcome.samples.toInt(), "no rate, no timer, no samples")
    }
}
