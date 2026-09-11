package io.github.youndie.razves.attribute

/**
 * The Kotlin package a mangled symbol belongs to.
 *
 * The grammar was read off a real `linuxX64` release binary rather than recalled, and the reason it
 * needed reading is that Kotlin/Native emits **two forms** that look alike and are not:
 *
 * ```
 * kfun:dev.whyoleg.cryptography.providers.base#checkBounds(kotlin.Int){}
 * kfun:dev.whyoleg.cryptography.bigint.removeLeadingZeros#internal
 * ```
 *
 * Both are top-level functions. In the first the member name sits *after* the `#` and the container
 * is exactly the package; in the second — which is what an `internal` or `private` declaration gets
 * — the member name is the last segment *of the container* and there is no signature at all. A rule
 * that handles only the first form reports `dev.whyoleg.cryptography.bigint.removeLeadingZeros` as
 * a package, and does it for every private declaration in the binary.
 *
 * What separates them is the signature: a symbol whose qualifier carries a `(` puts its member after
 * the hash, and one whose qualifier does not — `#internal`, `#static`, nothing at all — puts it in
 * the container.
 */
public object Packages {
    /** What a package with no name is called in a report, so the row is never blank. */
    public const val ROOT: String = "<root>"

    /**
     * The package of a Kotlin symbol, or null if the symbol is not a Kotlin one.
     *
     * [depth] truncates the result to that many segments. It matters more than it looks: at full
     * depth a real binary produces hundreds of rows, at depth 1 it produces four, and at depth 3 it
     * produces a table a person reads — `ru.workinprogress.shildik`, `io.ktor.server`,
     * `io.ktor.client`, `kotlin.text.regex` and so on.
     */
    public fun of(
        symbolName: String,
        depth: Int = Int.MAX_VALUE,
    ): String? {
        require(depth > 0) { "a package depth of $depth would name nothing" }
        val body = Mangling.kotlinBodyOf(symbolName) ?: return null
        val hash = body.indexOf('#')
        val container = if (hash < 0) body else body.substring(0, hash)
        val qualifier = if (hash < 0) "" else body.substring(hash + 1)
        if (container.isEmpty()) return ROOT

        val segments = container.split('.')
        val packageLike = segments.takeWhile { isPackageLike(it) }
        val trimmed =
            if (packageLike.size == segments.size && !qualifier.contains('(')) {
                // No signature anywhere, and nothing in the container looks like a class: the last
                // segment is the declaration's own name. This is what every `#internal` symbol looks
                // like, and there are thousands of them in a real binary. It holds for a single
                // segment too - `kfun:topLevel#internal` is a declaration in the root package, not a
                // package called `topLevel`.
                packageLike.dropLast(1)
            } else {
                packageLike
            }
        if (trimmed.isEmpty()) return ROOT
        return trimmed.take(depth).joinToString(".")
    }

    /**
     * A segment belongs to a package name if it starts lowercase and carries no `$`.
     *
     * The `$` is what marks everything the compiler synthesised — `$init_global`,
     * `$handleFailureCOROUTINE$0`, `defaultProvider$1` — and those are classes, not packages. The
     * lowercase test is the ordinary Kotlin convention, and it is a convention rather than a rule:
     * a package segment spelled with a capital would be read as a class here. That is a limitation
     * worth stating rather than a case worth guessing at, because guessing would misfile the far
     * more common capitalised class.
     */
    private fun isPackageLike(segment: String): Boolean {
        if (segment.isEmpty()) return false
        if (segment.contains('$')) return false
        val first = segment[0]
        return first.isLowerCase() || first == '_'
    }
}
