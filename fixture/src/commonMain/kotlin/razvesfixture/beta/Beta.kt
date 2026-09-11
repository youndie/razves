package razvesfixture.beta

public fun betaOne(seed: Int): Int {
    var acc = seed
    for (i in 1..19) acc = acc * 7 + i - (acc shr 4)
    return acc
}

internal fun betaTwo(seed: Int): Int {
    var acc = seed
    for (i in 1..23) acc = acc + (acc shl 2) + i * 3
    return acc
}

public fun betaAll(seed: Int): Int = betaOne(seed) + betaTwo(seed)
