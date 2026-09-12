package io.github.youndie.razves.sampler

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_PROCESS_CPUTIME_ID
import platform.posix.clock_gettime
import platform.posix.timespec

/**
 * The subject of the survival test, and now of the end-to-end one: a process that samples itself and
 * either reports what happened or leaves a dump behind.
 *
 * It exists because the failure this module was written to avoid is a **process** that hangs or dies,
 * and no test inside one process can observe that. `SamplerSurvivalTest` runs this binary, with a
 * timeout, and counts how many times it came back; `LiveProfileTest` runs it once more and reads what
 * it wrote.
 *
 * The workload allocates on purpose. That is what made a Kotlin signal handler deadlock, so it is
 * what the C one has to survive: research §1.1 measured the same workload passing every run once it
 * stopped allocating, which would have made a green test that proved nothing.
 *
 * ```
 * probe <hz> <rounds> [wall|cpu] [dump path]
 * ```
 */
public fun main(args: Array<String>) {
    if (args.getOrNull(0) == "ab") return ab(args)
    val hz = args.getOrNull(0)?.toIntOrNull() ?: 0
    val rounds = args.getOrNull(1)?.toIntOrNull() ?: 20_000
    val clock = if (args.getOrNull(2) == "cpu") SamplingClock.CPU else SamplingClock.WALL
    val dumpPath = args.getOrNull(3)

    if (hz > 0) Sampler.start(hz, clock)
    val start = cpuNanos()
    val acc = work(rounds)
    // CPU time from inside the process, so that neither process start-up nor the shell is in the
    // number the stand reads. scripts/sampling_cost.py is the only reader that cares, and it cares
    // a great deal.
    val cpuMs = (cpuNanos() - start) / 1_000_000
    if (hz > 0) Sampler.stop()

    // Either the dump or the count, never both: draining the ring is what produces the dump, and
    // asking for the samples afterwards would report an empty one.
    val kept: Int
    if (dumpPath != null) {
        val text = Sampler.dump(hz = hz.takeIf { it > 0 }, clock = clock)
        kept = text.lineSequence().count { it.startsWith("0x") }
        Sampler.writeText(dumpPath, text)
    } else {
        kept = Sampler.drain().size
    }

    println(
        "hz=$hz clock=${clock.name.lowercase()} rounds=$rounds cpu_ms=$cpuMs taken=${Sampler.taken} " +
            "dropped=${Sampler.dropped} drained=$kept dump=${dumpPath ?: "-"} acc=$acc",
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun cpuNanos(): Long =
    memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_PROCESS_CPUTIME_ID, ts.ptr)
        ts.tv_sec * 1_000_000_000L + ts.tv_nsec
    }

/**
 * Both halves of one comparison, in one process, seconds apart.
 *
 * **The pair is what the noise is fought with.** Two separate runs of this binary are two scheduling
 * decisions, two page-cache states and two moments in whatever else the machine is doing; the stand
 * that took them that way measured a 30% spread and could not resolve a deliberate 2%. Here the two
 * halves share a process, a heap and a core, and what differs between them is the thing under test.
 *
 * The order alternates, because whichever half runs second inherits a warmed allocator - a bias that
 * is invisible until it is the entire signal.
 *
 * ```
 * probe ab <rounds> <hz> [extra percent on the b side] [swap]
 * ```
 */
private fun ab(args: Array<String>) {
    val rounds = args.getOrNull(1)?.toIntOrNull() ?: 20_000
    val hz = args.getOrNull(2)?.toIntOrNull() ?: 0
    val extra = args.getOrNull(3)?.toIntOrNull() ?: 0
    val swap = args.getOrNull(4) == "swap"
    val bRounds = rounds + rounds * extra / 100

    fun half(
        sampled: Boolean,
        count: Int,
    ): Long {
        if (sampled && hz > 0) Sampler.start(hz, SamplingClock.WALL)
        val start = cpuNanos()
        work(count)
        val spent = cpuNanos() - start
        if (sampled && hz > 0) Sampler.stop()
        return spent / 1_000_000
    }

    val aMs: Long
    val bMs: Long
    if (swap) {
        bMs = half(sampled = true, count = bRounds)
        aMs = half(sampled = false, count = rounds)
    } else {
        aMs = half(sampled = false, count = rounds)
        bMs = half(sampled = true, count = bRounds)
    }
    Sampler.drain()
    println(
        "ab rounds=$rounds hz=$hz extra=$extra swap=$swap a_ms=$aMs b_ms=$bMs " +
            "taken=${Sampler.taken} dropped=${Sampler.dropped}",
    )
}

private fun work(rounds: Int): Long {
    var acc = 0L
    for (r in 0 until rounds) {
        val list = ArrayList<Int>(1024)
        for (i in 0 until 1024) list.add(i * r)
        for (v in list) acc += v.toLong() xor acc
    }
    return acc
}
