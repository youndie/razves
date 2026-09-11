package io.github.youndie.razves.sampler

/**
 * The subject of the survival test: a process that samples itself and prints what happened.
 *
 * It exists because the failure this module was written to avoid is a **process** that hangs or
 * dies, and no test inside one process can observe that. `SamplerSurvivalTest` runs this binary,
 * with a timeout, and counts how many times it came back.
 *
 * The workload allocates on purpose. That is what made the Kotlin handler deadlock, so it is what
 * the C one has to survive: research 1.1 measured the same workload passing every run once it
 * stopped allocating, which would have made a green test that proved nothing.
 */
public fun main(args: Array<String>) {
    val hz = args.getOrNull(0)?.toIntOrNull() ?: 0
    val rounds = args.getOrNull(1)?.toIntOrNull() ?: 20_000
    val clock = if (args.getOrNull(2) == "cpu") SamplingClock.CPU else SamplingClock.WALL

    if (hz > 0) Sampler.start(hz, clock)
    val acc = work(rounds)
    if (hz > 0) Sampler.stop()

    val stacks = Sampler.drain()
    val depth = if (stacks.isEmpty()) 0.0 else stacks.sumOf { it.size }.toDouble() / stacks.size
    println(
        "hz=$hz clock=${clock.name.lowercase()} rounds=$rounds taken=${Sampler.taken} " +
            "dropped=${Sampler.dropped} drained=${stacks.size} meanDepth=$depth acc=$acc",
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
