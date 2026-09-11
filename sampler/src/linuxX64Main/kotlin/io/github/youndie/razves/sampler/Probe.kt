package io.github.youndie.razves.sampler

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
    val hz = args.getOrNull(0)?.toIntOrNull() ?: 0
    val rounds = args.getOrNull(1)?.toIntOrNull() ?: 20_000
    val clock = if (args.getOrNull(2) == "cpu") SamplingClock.CPU else SamplingClock.WALL
    val dumpPath = args.getOrNull(3)

    if (hz > 0) Sampler.start(hz, clock)
    val acc = work(rounds)
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
        "hz=$hz clock=${clock.name.lowercase()} rounds=$rounds taken=${Sampler.taken} " +
            "dropped=${Sampler.dropped} drained=$kept dump=${dumpPath ?: "-"} acc=$acc",
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
