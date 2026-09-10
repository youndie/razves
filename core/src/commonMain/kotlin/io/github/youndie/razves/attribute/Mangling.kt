package io.github.youndie.razves.attribute

/**
 * Where a symbol came from.
 *
 * The top-level split of every report, and it is by origin rather than by package because measuring
 * four real release binaries put Kotlin at 36–40% of the attributed bytes and statically linked C in
 * the majority. A tool whose main table covers Kotlin and calls the rest "other" answers a third of
 * the question — see docs/research/research-architecture.md §1.2 and D3.
 */
public enum class Origin {
    /** Compiled from Kotlin: the twelve `k*:` prefixes the compiler emits. */
    KOTLIN,

    /** The Kotlin/Native runtime, written in C++ and mangled as `kotlin::` or `konan::`. */
    KOTLIN_RUNTIME,

    /** Rust, in either of the two schemes it uses. */
    RUST,

    /** C++ that is neither of the above: libc++, and anything a cinterop dependency brings. */
    CXX,

    /** A name with no mangling scheme at all. The C ABI has no namespaces, so neither has this. */
    C,
}

/**
 * Reads a symbol's origin out of its name.
 *
 * **The order of the tests is load-bearing, and getting it wrong is silent.** Rust's *legacy*
 * mangling emits `_ZN…` — the same prefix as Itanium C++ — and is distinguishable only by the
 * trailing `17h<16 hex digits>E` disambiguator. Testing "starts with `_Z` therefore C++" first
 * charges 1,263,149 bytes of `tokio`, `sqlx_postgres` and `core::ptr` to a Kotlin/Native runtime
 * that measures 40,871 bytes in the same binary — thirty-one times its real size, and the report
 * would name the runtime the third-largest thing in there. Nothing about it would look wrong.
 *
 * **This is not a demangler and must not become one.** razves needs the origin and the package, not
 * a readable signature, and a demangler is a much larger surface to keep correct across three
 * schemes. What is parsed of the Itanium form is the first component of a nested name, which is
 * enough to separate the Kotlin/Native runtime from everything else; a symbol whose first component
 * is a substitution (`S_`, `St`) is C++ by definition of what those substitutions refer to.
 */
public object Mangling {
    /**
     * Every prefix the Kotlin/Native compiler emits, counted over a `linuxX64` release binary and a
     * `macosArm64` debug binary. Twelve, not the three the tool was first sketched around: `kfun:`,
     * `kclass:` and `kvar:` are 12,125 symbols of the 23,336 Kotlin-prefixed ones in the ELF subject,
     * and the rest — vtables, interface tables, reference tables — are small individually and half
     * the symbols together. The last four appear only on Apple targets.
     */
    public val KOTLIN_PREFIXES: List<String> =
        listOf(
            "kfun",
            "kclass",
            "kifacevtable",
            "kifacetable",
            "krefs",
            "kintf",
            "kvar",
            "kassociatedobjects",
            "kexttype",
            "kextoff",
            "kextname",
            "ktypew",
        )

    private val KOTLIN_RUNTIME_NAMESPACES = setOf("kotlin", "konan")

    /** Rust's legacy scheme ends every symbol with a 16-hex-digit disambiguator. */
    private val RUST_LEGACY_HASH = Regex("17h[0-9a-f]{16}E$")

    /** Itanium cv-qualifiers and reference qualifiers, which may precede the first name component. */
    private const val NESTED_NAME_QUALIFIERS = "KVRO"

    public fun originOf(symbolName: String): Origin {
        if (kotlinPrefixOf(symbolName) != null) return Origin.KOTLIN
        val name = withoutMachOUnderscore(symbolName)
        if (name.startsWith("_R")) return Origin.RUST
        if (name.startsWith("_Z")) {
            // Before C++, because the two share this prefix and only this test separates them.
            if (RUST_LEGACY_HASH.containsMatchIn(name)) return Origin.RUST
            return if (firstNestedComponent(name) in KOTLIN_RUNTIME_NAMESPACES) Origin.KOTLIN_RUNTIME else Origin.CXX
        }
        return Origin.C
    }

    /**
     * The `k…` prefix of a Kotlin symbol, without its colon, or null if it has none.
     *
     * Mach-O puts a `_` in front of every symbol, so both spellings are accepted; ELF does not, so
     * accepting the underscore costs nothing there.
     */
    public fun kotlinPrefixOf(symbolName: String): String? {
        val name = withoutLeadingUnderscore(symbolName)
        val colon = name.indexOf(':')
        if (colon <= 0) return null
        val prefix = name.substring(0, colon)
        return if (prefix in KOTLIN_PREFIXES) prefix else null
    }

    /** The mangled body of a Kotlin symbol: everything after the `k…:` prefix. */
    public fun kotlinBodyOf(symbolName: String): String? {
        val prefix = kotlinPrefixOf(symbolName) ?: return null
        return withoutLeadingUnderscore(symbolName).substring(prefix.length + 1)
    }

    internal fun withoutLeadingUnderscore(name: String): String = if (name.startsWith('_')) name.drop(1) else name

    /**
     * Undoes Mach-O's leading underscore for a name that already begins with one.
     *
     * Stripping unconditionally is the obvious version and it is wrong: an Itanium or Rust symbol
     * starts with `_Z` or `_R` on ELF and with `__Z` or `__R` on Mach-O, so a blanket strip turns
     * every ELF C++ symbol into `Z…` and files the whole runtime under C. Only the doubled forms are
     * a Mach-O decoration.
     */
    private fun withoutMachOUnderscore(name: String): String =
        if (name.startsWith("__Z") || name.startsWith("__R")) name.drop(1) else name

    /**
     * The first component of an Itanium nested name: `_ZN6kotlin2gc…` yields `kotlin`.
     *
     * Returns null for anything else, including the substitution forms — `_ZSt…`, `_ZNSt…`, `_ZNS_…`
     * — which by construction refer to a namespace already seen, and never to the runtime this is
     * looking for.
     */
    private fun firstNestedComponent(name: String): String? {
        if (!name.startsWith("_ZN")) return null
        var at = 3
        while (at < name.length && name[at] in NESTED_NAME_QUALIFIERS) at++
        var end = at
        while (end < name.length && name[end].isDigit()) end++
        if (end == at) return null
        val length = name.substring(at, end).toIntOrNull() ?: return null
        if (length <= 0 || end + length > name.length) return null
        return name.substring(end, end + length)
    }
}
