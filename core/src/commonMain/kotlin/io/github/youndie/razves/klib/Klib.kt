package io.github.youndie.razves.klib

/**
 * What razves needs out of one klib: which module it is, which targets it was built for, and which
 * packages it declares.
 *
 * [packages] is the half the brief did not expect to be there. It assumed the package-to-module map
 * was something only Gradle knew; in fact every klib carries its own package list, as
 * `default/linkdata/package_<fqn>` entries, so a directory of klibs is enough and Gradle's advantage
 * is only that it knows *which* klibs took part without being told.
 */
public data class Klib(
    /** `<group>:<artifact>`, as the manifest spells it. */
    public val uniqueName: String,
    /** `linux_x64`, `macos_arm64`, … — empty for a metadata-only klib. */
    public val targets: List<String>,
    public val packages: Set<String>,
    /**
     * The static archives this klib carries, by name, with every symbol each one defines.
     *
     * A cinterop klib ships them at the `included` directory under `default/targets/<target>` - which is how OpenSSL
     * reaches a Kotlin/Native binary, and the only thing that can say so: the C ABI has no
     * namespaces, so 26.7% of the attributed bytes of a release binary carry names no grammar can
     * place.
     */
    public val archives: Map<String, Set<String>> = emptyMap(),
)

/**
 * Reads a klib in either shape it comes in: a zip in the dependency cache, or an unpacked directory
 * in the Kotlin/Native distribution, which is where the stdlib and every `platform.*` klib live.
 *
 * No subprocess, and in particular not the `klib` tool that ships with the compiler — for the same
 * reason as everything else in this module. `klib info` would print exactly this and would need a
 * JVM, a compiler distribution and a process per library.
 */
public object KlibReader {
    private const val MANIFEST = "default/manifest"
    private const val PACKAGE_MARKER = "/package_"
    private const val UNIQUE_NAME = "unique_name"
    private const val NATIVE_TARGETS = "native_targets"
    private const val ARCHIVE_SUFFIX = ".a"
    private val EMPTY_FRAGMENT_MARKER = byteArrayOf(0x0A, 0x00, 0x12, 0x00, 0x1A, 0x00)

    /** A klib as a single archive. */
    public fun readArchive(
        data: ByteArray,
        name: String,
    ): Klib {
        val entries = Zip.entries(data)
        val manifest =
            entries.firstOrNull { it.name == MANIFEST || it.name.endsWith("/$MANIFEST") }
                ?: error("$name carries no $MANIFEST and is therefore not a klib")
        val byName = entries.associateBy { it.name }
        return build(
            manifestText = Zip.read(data, manifest).decodeToString(),
            entryNames = entries.map { it.name },
            name = name,
            contentOf = { entryName -> byName[entryName]?.let { Zip.read(data, it) } },
        )
    }

    /**
     * A klib already on disk as a directory: [manifestText] is the contents of `default/manifest`,
     * and [entryNames] is every path under the directory, in any form that keeps the
     * `…/package_<fqn>` segments intact.
     *
     * [contentOf] reads one of those entries. It is optional and costs nothing when it is not
     * supplied - without it razves cannot tell an empty intermediate package from a real one, and
     * says so by keeping both.
     */
    public fun readUnpacked(
        manifestText: String,
        entryNames: List<String>,
        name: String,
        contentOf: (String) -> ByteArray? = { null },
    ): Klib = build(manifestText, entryNames, name, contentOf)

    private fun build(
        manifestText: String,
        entryNames: List<String>,
        name: String,
        contentOf: (String) -> ByteArray?,
    ): Klib {
        val properties = parseManifest(manifestText)
        val uniqueName =
            properties[UNIQUE_NAME]
                ?: error("$name has a manifest with no $UNIQUE_NAME; razves cannot name the module it is")
        return Klib(
            uniqueName = uniqueName,
            targets = properties[NATIVE_TARGETS]?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
            packages = nonEmptyPackages(entryNames, contentOf),
            archives = archives(entryNames, contentOf),
        )
    }

    /**
     * Every `.a` the klib carries, read once for its symbol index.
     *
     * Only archives, and only under the target directories a cinterop klib puts them in: the rest of
     * a klib is metadata and IR, and reading a 12 MB member to discover it is not an archive is the
     * kind of cost that turns a report into a nightly job.
     */
    private fun archives(
        entryNames: List<String>,
        contentOf: (String) -> ByteArray?,
    ): Map<String, Set<String>> {
        val out = mutableMapOf<String, Set<String>>()
        for (entryName in entryNames) {
            if (!entryName.endsWith(ARCHIVE_SUFFIX)) continue
            val content = contentOf(entryName) ?: continue
            val symbols = Archive.definedSymbols(content)
            if (symbols.isNotEmpty()) out[entryName.substringAfterLast('/')] = symbols
        }
        return out
    }

    /**
     * The packages that actually contain declarations.
     *
     * A klib emits a `package_<fqn>/` directory for every *intermediate* level of every package it
     * has, and each of those carries a real metadata fragment rather than being empty on disk:
     * `stately-concurrent-collections.klib` has `package_co/0_co.knm` (14 bytes) beside
     * `package_co.touchlab.stately.collections/0_collections.knm` (3,985 bytes). Nothing is declared
     * in `co`.
     *
     * Counting every directory therefore invents `co` as a package, and then two klibs from the same
     * organisation both appear to declare it and the pair reads as an ambiguity that is not there.
     * That is how this was found: the ambiguous share over 200 real klibs came out at 9.3% against
     * the 1.6% measured through `klib info`, and every extra entry was a bare prefix.
     *
     * **An empty fragment is recognised structurally.** Its metadata begins with three
     * zero-length fields - `0A 00 12 00 1A 00` - where a fragment with anything in it begins with a
     * length-delimited one. Read off the four fragments of that klib and checked against every klib
     * the oracle can find. Only *candidates* are inflated: a package that is not a strict prefix of
     * another package in the same klib cannot be an intermediate node, and most are not.
     */
    private fun nonEmptyPackages(
        entryNames: List<String>,
        contentOf: (String) -> ByteArray?,
    ): Set<String> {
        val fragments = mutableMapOf<String, MutableList<String>>()
        for (entryName in entryNames) {
            val marker = entryName.substringAfterLast(PACKAGE_MARKER, "")
            if (marker.isEmpty()) continue
            val slash = marker.indexOf('/')
            if (slash <= 0 || marker.length <= slash + 1) continue
            fragments.getOrPut(marker.substring(0, slash)) { mutableListOf() } += entryName
        }
        val all = fragments.keys
        return all
            .filterNot { fqn ->
                val isPrefixOfAnother = all.any { it.length > fqn.length && it.startsWith("$fqn.") }
                isPrefixOfAnother && fragments.getValue(fqn).all { isEmptyFragment(contentOf(it)) }
            }.toSet()
    }

    /** Three zero-length fields at the head of the metadata: nothing is declared in this package. */
    private fun isEmptyFragment(content: ByteArray?): Boolean {
        if (content == null || content.size < EMPTY_FRAGMENT_MARKER.size) return false
        return EMPTY_FRAGMENT_MARKER.indices.all { content[it] == EMPTY_FRAGMENT_MARKER[it] }
    }

    /**
     * The manifest is a Java properties file, and the part of that format that matters here is the
     * backslash escaping: a module coordinate is written `org.jetbrains.kotlinx\:atomicfu`, because
     * a colon would otherwise separate key from value. Reading it without unescaping produces a
     * `unique_name` with a stray backslash in it, which then matches nothing.
     */
    private fun parseManifest(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var pending = StringBuilder()
        for (rawLine in text.lineSequence()) {
            val line = if (pending.isEmpty()) rawLine.trimStart() else rawLine
            if (pending.isEmpty() && (line.isEmpty() || line.startsWith('#') || line.startsWith('!'))) continue
            // A line ending in an odd number of backslashes continues into the next one.
            val trailing = line.length - line.trimEnd('\\').length
            if (trailing % 2 == 1) {
                pending.append(line.dropLast(1))
                continue
            }
            pending.append(line)
            val entry = pending.toString()
            pending = StringBuilder()
            val separator = indexOfSeparator(entry)
            if (separator < 0) continue
            out[unescape(entry.substring(0, separator)).trim()] = unescape(entry.substring(separator + 1)).trim()
        }
        return out
    }

    private fun indexOfSeparator(entry: String): Int {
        var i = 0
        while (i < entry.length) {
            when (entry[i]) {
                '\\' -> i++
                '=', ':' -> return i
            }
            i++
        }
        return -1
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i == value.length - 1) {
                out.append(c)
                i++
                continue
            }
            when (val escaped = value[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                else -> out.append(escaped)
            }
            i += 2
        }
        return out.toString()
    }
}
