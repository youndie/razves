package io.github.youndie.razves.klib

/**
 * Which module a package came from, built from the klibs that were linked.
 *
 * **Package → module is nearly a function and is not one.** Measured over 141 klibs — every
 * `*linuxX64Main*.klib` in the Gradle cache plus the Kotlin/Native distribution's own — the map
 * covers 568 packages across 122 modules, and **9 of those packages are declared by two modules**:
 * `androidx.lifecycle` by lifecycle-common and lifecycle-runtime, five `com.github.ajalt.clikt.*`
 * by clikt and clikt-mordant, `org.koin.core` by koin-core and koin-ktor, and so on. 1.6%.
 *
 * So there is no tie-break. A package two modules declare resolves to [Ambiguous], and its bytes get
 * a row naming both rather than being handed to whichever module sorted first. A tie-break produces
 * a number that is confidently wrong, and the whole value of this tool is that its totals can be
 * trusted; an ambiguous row is a visible, small, correct answer instead.
 */
public class PackageToModule(
    klibs: Collection<Klib>,
) {
    private val owners: Map<String, List<String>> =
        klibs
            .flatMap { klib -> klib.packages.map { it to klib.uniqueName } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, modules) -> modules.distinct().sorted() }

    /** Every package any of the klibs declares. The authority on which packages exist at all. */
    public val declaredPackages: Set<String> get() = owners.keys

    public val moduleCount: Int = klibs.map { it.uniqueName }.distinct().size

    /**
     * Every target any of these klibs was built for.
     *
     * Metadata-only klibs declare none and are simply absent from this, which is why an empty set
     * means "nothing here says what it was built for" rather than "built for nothing".
     */
    public val targets: Set<String> = klibs.flatMap { it.targets }.toSet()

    /**
     * Which archive, in which module, defines a given C symbol.
     *
     * The answer to the 26.7% of a release binary that carries no mangling scheme at all. A symbol
     * defined by two archives resolves to neither, for the same reason an ambiguous package does:
     * a tie-break produces a number that is confidently wrong.
     */
    private val definedBy: Map<String, List<String>> =
        klibs
            .flatMap { klib ->
                klib.archives.flatMap { (archive, symbols) ->
                    symbols.map { it to "$archive (${klib.uniqueName})" }
                }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, sources) -> sources.distinct().sorted() }

    /** How many archives were read at all. Zero means nothing was supplied that carries one. */
    public val archiveCount: Int = klibs.sumOf { it.archives.size }

    public fun archiveOf(symbolName: String): ModuleOwner =
        when (val sources = definedBy[symbolName]) {
            null -> ModuleOwner.Unknown
            else -> if (sources.size == 1) ModuleOwner.One(sources[0]) else ModuleOwner.Ambiguous(sources)
        }

    public fun resolve(packageName: String): ModuleOwner =
        when (val modules = owners[packageName]) {
            null -> ModuleOwner.Unknown
            else -> if (modules.size == 1) ModuleOwner.One(modules[0]) else ModuleOwner.Ambiguous(modules)
        }

    /**
     * The longest declared package that is a prefix of [packageName], or null.
     *
     * This is what turns a derived name back into a real one. A grammar over mangled names cannot
     * tell a lowercase declaration from a package segment — cinterop keeps a C struct's own name, so
     * `platform.posix.addrinfo` and `…internal.cinterop.ossl_param_st` come out of it looking like
     * packages. Measured on the release subject, that misfiles 0.4% of the checkable Kotlin bytes.
     * The klib lists are the authority that fixes it.
     */
    public fun longestDeclaredPrefix(packageName: String): String? {
        if (packageName in owners) return packageName
        var candidate = packageName
        while (true) {
            val cut = candidate.lastIndexOf('.')
            if (cut < 0) return null
            candidate = candidate.substring(0, cut)
            if (candidate in owners) return candidate
        }
    }
}

/** Who owns a package, including the honest answers. */
public sealed interface ModuleOwner {
    public data class One(
        val module: String,
    ) : ModuleOwner

    /** Two or more klibs declare this package. Reported as such; never resolved by a tie-break. */
    public data class Ambiguous(
        val modules: List<String>,
    ) : ModuleOwner

    /** No supplied klib declares it — the application's own code, or no klibs were supplied at all. */
    public data object Unknown : ModuleOwner
}
