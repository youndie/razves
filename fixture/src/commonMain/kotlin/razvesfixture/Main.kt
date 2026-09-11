package razvesfixture

import razvesfixture.alpha.alphaAll
import razvesfixture.beta.betaAll
import razvesfixture.gamma.deep.deepOne

/**
 * Everything the fixture declares is reachable from here.
 *
 * Kotlin/Native eliminates what nothing calls, so a fixture whose functions are never referenced
 * compiles into a binary that does not contain them - and a test asserting they are attributed
 * correctly then fails for a reason that has nothing to do with attribution.
 */
public fun main() {
    val seed = 17
    println(alphaAll(seed) + betaAll(seed) + deepOne(seed))
}
