package razvesfixture.gamma.deep

/**
 * A package three segments deep, so the depth parameter has something to truncate and the package
 * parser has a name it could plausibly cut in the wrong place.
 */
public fun deepOne(seed: Int): Int {
    var acc = seed
    for (i in 1..29) acc = acc * 13 + i - (acc shr 5)
    return acc
}
