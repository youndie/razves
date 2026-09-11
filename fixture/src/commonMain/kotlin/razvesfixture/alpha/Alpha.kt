package razvesfixture.alpha

/**
 * Three declarations in one package, in the three shapes the mangling distinguishes: a public
 * top-level function, an internal one, and a member of a class.
 *
 * The bodies are deliberately not one-liners. Kotlin/Native eliminates what nothing calls and inlines
 * what is trivial, and a fixture whose functions disappear proves nothing about attribution.
 */
public fun alphaPublicTopLevel(seed: Int): Int {
    var acc = seed
    for (i in 1..17) acc = acc * 31 + i * i - (acc shr 3)
    return acc
}

internal fun alphaInternalTopLevel(seed: Int): Int {
    var acc = seed
    for (i in 1..13) acc = acc xor (acc shl 5) + i
    return acc
}

public class AlphaClass(
    private val base: Int,
) {
    public fun member(seed: Int): Int {
        var acc = base + seed
        for (i in 1..11) acc = acc + i * base - (acc shr 2)
        return acc
    }
}

public fun alphaAll(seed: Int): Int =
    alphaPublicTopLevel(seed) + alphaInternalTopLevel(seed) + AlphaClass(seed).member(seed)
