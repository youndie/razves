package io.github.youndie.razves.profile

import io.github.youndie.razves.attribute.Origin
import io.github.youndie.razves.attribute.Packages
import io.github.youndie.razves.klib.Klib
import io.github.youndie.razves.klib.ModuleOwner
import io.github.youndie.razves.klib.PackageToModule
import io.github.youndie.razves.read.BinaryImage
import io.github.youndie.razves.report.Attribution
import io.github.youndie.razves.report.SymbolIndex

/**
 * Turns stacks of raw addresses into a [Profile], against the binary they were taken in.
 *
 * The in-process sampler ships addresses and nothing else - no symbol is read and no name resolved
 * inside the profiled program ([research
 * §2.3](../../../../../../../docs/research/research-profiler.md)). This is the other side of that
 * decision: the same binary, read the way razves reads it to size one, answering who owns each
 * address.
 */
public object Profiling {
    /**
     * @param stacks one array per sample, **leaf frame first**, which is the order `backtrace()`
     *   returns and the order the ring buffer keeps.
     * @param dropped what the sampler could not keep, carried into the profile rather than logged.
     */
    public fun of(
        image: BinaryImage,
        stacks: List<LongArray>,
        klibs: Collection<Klib>? = null,
        packageDepth: Int = Attribution.DEFAULT_PACKAGE_DEPTH,
        dropped: Long = 0,
    ): Profile {
        val index = SymbolIndex.of(image)
        val loaded = Loaded(image)
        val modules = klibs?.takeIf { it.isNotEmpty() }?.let { PackageToModule(it) }

        val originSelf = HashMap<String, Long>()
        val originTotal = HashMap<String, Long>()
        val packageSelf = HashMap<String, Long>()
        val packageTotal = HashMap<String, Long>()
        val moduleSelf = HashMap<String, Long>()
        val moduleTotal = HashMap<String, Long>()
        var frames = 0L

        for (stack in stacks) {
            if (stack.isEmpty()) continue
            frames += stack.size
            // Distinct rows per stack, not per frame: recursion would otherwise let one sample add
            // several to a row, and the totals would exceed the samples taken.
            val originsHere = HashSet<String>()
            val packagesHere = HashSet<String>()
            val modulesHere = HashSet<String>()

            stack.forEachIndexed { depth, address ->
                val site = siteOf(address, index, loaded)
                val origin = originRow(site)
                val pkg = packageRow(site, modules, packageDepth)
                val module = moduleRow(site, modules, packageDepth)
                if (depth == 0) {
                    originSelf[origin] = (originSelf[origin] ?: 0) + 1
                    packageSelf[pkg] = (packageSelf[pkg] ?: 0) + 1
                    moduleSelf[module] = (moduleSelf[module] ?: 0) + 1
                }
                originsHere += origin
                packagesHere += pkg
                modulesHere += module
            }

            originsHere.forEach { originTotal[it] = (originTotal[it] ?: 0) + 1 }
            packagesHere.forEach { packageTotal[it] = (packageTotal[it] ?: 0) + 1 }
            modulesHere.forEach { moduleTotal[it] = (moduleTotal[it] ?: 0) + 1 }
        }

        return Profile(
            binary = image.name,
            samples = stacks.count { it.isNotEmpty() }.toLong(),
            dropped = dropped,
            frames = frames,
            // Origin.entries first and in the enum order, so a bucket that is empty in this profile
            // is a row saying zero rather than a line nobody notices is missing - the same rule the
            // size report follows.
            origins = rows(Origin.entries.map { it.name.lowercase() }, originSelf, originTotal),
            packages = rows(emptyList(), packageSelf, packageTotal),
            modules = rows(emptyList(), moduleSelf, moduleTotal),
        )
    }

    private fun siteOf(
        address: Long,
        index: SymbolIndex,
        loaded: Loaded,
    ): Site {
        val symbol = index.at(address)
        if (symbol != null) return Site.Named(originOf(symbol.name), symbol.name)
        return if (loaded.contains(address)) Site.NoSymbol else Site.Outside
    }

    private fun originRow(site: Site): String =
        when (site) {
            is Site.Named -> site.origin.name.lowercase()
            Site.NoSymbol -> Profile.NO_SYMBOL
            Site.Outside -> Profile.OUTSIDE
        }

    /**
     * The package a frame is in, through the same fold the size report uses: the derived name, folded
     * up to the longest package a klib actually declares, then truncated to the reporting depth.
     *
     * A symbol with no package - a C function, the Kotlin/Native runtime, anything unmangled - is its
     * own row named by origin rather than being forced into a package it does not have.
     */
    private fun packageRow(
        site: Site,
        modules: PackageToModule?,
        depth: Int,
    ): String {
        if (site !is Site.Named) return originRow(site)
        val derived = Packages.of(site.symbol) ?: return site.origin.name.lowercase()
        val folded = modules?.longestDeclaredPrefix(derived) ?: derived
        return folded.split('.').take(depth).joinToString(".")
    }

    /**
     * The module a frame is in, resolved on the **full** package name rather than the truncated one -
     * asking which module owns `io.ktor.server` is asking a question with no answer, and the answer
     * it would invent is a module that owns some of it. The same reasoning, and the same row names,
     * as the size report.
     */
    private fun moduleRow(
        site: Site,
        modules: PackageToModule?,
        depth: Int,
    ): String {
        if (site !is Site.Named) return originRow(site)
        if (modules == null) return NO_KLIBS
        val derived = Packages.of(site.symbol)
        if (derived == null) {
            return when (val owner = modules.archiveOf(site.symbol)) {
                is ModuleOwner.One -> owner.module
                is ModuleOwner.Ambiguous -> "<ambiguous: ${owner.modules.joinToString(", ")}>"
                ModuleOwner.Unknown -> "<no supplied archive defines it>"
            }
        }
        val folded = modules.longestDeclaredPrefix(derived) ?: derived
        return when (val owner = modules.resolve(folded)) {
            is ModuleOwner.One -> owner.module
            is ModuleOwner.Ambiguous -> "<ambiguous: ${owner.modules.joinToString(", ")}>"
            ModuleOwner.Unknown -> "<no klib declares ${folded.split('.').take(depth).joinToString(".")}>"
        }
    }

    private fun rows(
        always: List<String>,
        self: Map<String, Long>,
        total: Map<String, Long>,
    ): List<ProfileRow> {
        val names = LinkedHashSet(always) + self.keys + total.keys
        return names
            .map { ProfileRow(it, self[it] ?: 0, total[it] ?: 0) }
            .sortedWith(compareByDescending<ProfileRow> { it.self }.thenByDescending { it.total }.thenBy { it.name })
    }

    /** Said once, in the profile, rather than left for a reader to notice that no module row is named. */
    public const val NO_KLIBS: String = "<no klibs were supplied>"
}
