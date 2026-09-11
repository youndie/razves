package io.github.youndie.razves.read

import io.github.youndie.razves.attribute.Mangling
import io.github.youndie.razves.attribute.Origin
import io.github.youndie.razves.klib.KlibReader
import io.github.youndie.razves.klib.PackageToModule
import io.github.youndie.razves.report.Attribution
import io.github.youndie.razves.report.ModuleRowKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reader against a Kotlin/Native binary somebody actually shipped.
 *
 * A hand-built fixture proves the reader does what the reader was written to do. Only a real binary
 * proves the reader was written against the real format — and the fixtures in this repository were
 * written by the same person, from the same understanding, so they cannot catch a misunderstanding.
 *
 * **This test skips itself, by name, when it cannot find a subject.** The binaries are 15–40 MB and
 * belong to other repositories; committing one is not an option and neither is a check that silently
 * reports success because its input was missing. Point it at a subject with:
 *
 * ```
 * RAZVES_ELF_SUBJECT=../shildik/distribution/build/bin/linuxX64/releaseExecutable/shildik.kexe
 * ```
 *
 * JVM-only because it needs a file system, and the reader itself is common code: reading a real file
 * here proves the parser, not the platform.
 */
class RealBinaryTest {
    private fun subject(): File? {
        val configured = System.getenv(SUBJECT_ENV) ?: System.getProperty(SUBJECT_ENV)
        val candidates = listOfNotNull(configured) + DEFAULT_CANDIDATES
        return candidates.map { File(it) }.firstOrNull { it.isFile }
    }

    private fun skipped(reason: String) {
        println("SKIPPED RealBinaryTest: $reason. Set $SUBJECT_ENV to a Kotlin/Native ELF binary to run it.")
    }

    @Test
    fun theIdentitiesHoldOnARealKotlinNativeBinary() {
        val file = subject() ?: return skipped("no subject binary found")
        val image = ElfReader.read(file.readBytes(), file.name)
        val r = Attribution.of(image)

        // Reaching this line at all is most of the test: every identity is a constructor invariant,
        // so a reader that loses a section, misreads an offset or double-counts a symbol throws
        // rather than returning a plausible report.
        assertEquals(file.length(), r.fileSize)
        assertEquals(
            r.fileSize,
            r.headerBytes + r.allocatedBytes + r.metadataBytes + r.interRegionPadding + r.unparsedBytes,
        )
        assertEquals(r.allocatedBytes, r.attributedBytes + r.unattributedBytes)
        assertTrue(image.hasSymbolTable, "${file.name} is stripped; razves has nothing to read in it")
        assertEquals(
            setOf("linux_x64", "android_x64"),
            image.targets,
            "an ELF x86-64 file is one of these two and the container cannot say which",
        )

        val text =
            assertNotNull(
                r.sections.firstOrNull { it.section.name == ".text" },
                "a Kotlin/Native executable has a .text section",
            )
        assertTrue(
            text.coverage > 0.9,
            "symbols should claim almost all of .text; got ${(text.coverage * 100).toInt()}%",
        )

        val symtab = r.sections.filter { it.section.name in setOf(".symtab", ".strtab", ".shstrtab") }
        val symbolTableShare = symtab.sumOf { it.section.size }.toDouble() / r.fileSize
        println(
            buildString {
                appendLine("razves read ${file.name}")
                appendLine("  file                  ${r.fileSize}")
                appendLine("  container headers     ${r.headerBytes}")
                appendLine("  allocated             ${r.allocatedBytes}")
                appendLine("  NOBITS (memory only)  ${r.nobitsBytes}")
                appendLine("  metadata              ${r.metadataBytes}")
                appendLine("  unparsed              ${r.unparsedBytes}")
                appendLine("  inter-region padding  ${r.interRegionPadding}")
                appendLine("  attributed            ${r.attributedBytes}")
                appendLine("  unattributed          ${r.unattributedBytes}")
                appendLine("  symbol tables         ${(symbolTableShare * 1000).toInt() / 10.0}% of the file")
                appendLine("  .text coverage        ${(text.coverage * 1000).toInt() / 10.0}%")
            },
        )
    }

    @Test
    fun theMeasuredSubjectStillHasTheNumbersTheResearchRecorded() {
        val file = subject() ?: return skipped("no subject binary found")
        if (file.length() != MEASURED_FILE_SIZE) {
            return skipped(
                "the subject is ${file.length()} bytes, and the figures in research §1.2 were taken " +
                    "on a $MEASURED_FILE_SIZE-byte one",
            )
        }
        val r = Attribution.of(ElfReader.read(file.readBytes(), file.name))

        // Read by hand through llvm-nm and llvm-objdump on 2026-09-11, before any of this code
        // existed, and written into docs/research/research-architecture.md §1.2. If razves disagrees
        // with them, either the reader is wrong or the research is — and both are worth stopping for.
        assertEquals(MEASURED_ALLOCATED_IN_FILE, r.allocatedBytes, "allocated section bytes that are in the file")
        assertEquals(MEASURED_NOBITS, r.nobitsBytes, "NOBITS bytes")
        assertEquals(MEASURED_ALLOCATED_TOTAL, r.virtualSize, "what llvm-size called the allocated total")
        assertEquals(MEASURED_METADATA, r.metadataBytes, "metadata section bytes")
        assertEquals(MEASURED_HEADERS_AND_PADDING, r.headerBytes + r.interRegionPadding, "headers plus padding")
    }

    @Test
    fun theIdentitiesHoldOnARealMachOBinary() {
        val file = machOSubject() ?: return skipped("no Mach-O subject binary found")
        val image = MachOReader.read(file.readBytes(), file.name)
        val r = Attribution.of(image)

        assertEquals(file.length(), r.fileSize)
        assertEquals(
            r.fileSize,
            r.headerBytes + r.allocatedBytes + r.metadataBytes + r.interRegionPadding + r.unparsedBytes,
        )
        assertEquals(SizeAlgorithm.ADDRESS_DELTA, image.sizeAlgorithm)
        assertEquals(
            setOf("macos_arm64"),
            image.targets,
            "LC_BUILD_VERSION names the platform and the header names the CPU; together they are exact",
        )

        val text = assertNotNull(r.sections.firstOrNull { it.section.qualifiedName == "__TEXT,__text" })
        assertTrue(
            text.coverage > 0.9,
            "symbols should claim almost all of __TEXT,__text; got ${(text.coverage * 100).toInt()}%",
        )

        // Mach-O's link-edit is described by load commands rather than by a table of every region, and
        // razves does not parse all of them. The bound is what the reader is allowed to not know:
        // measured at 0.24% of a real macosArm64 binary on 2026-09-11, before this test existed.
        val unparsedShare = r.unparsedBytes.toDouble() / r.fileSize
        assertTrue(
            unparsedShare < 0.01,
            "razves failed to account for ${(unparsedShare * 1000).toInt() / 10.0}% of ${file.name}, " +
                "which is more link-edit than it is allowed not to know",
        )

        val consts = r.sections.filter { it.section.name == "__const" }
        assertTrue(
            consts.size >= 2,
            "a Kotlin/Native Mach-O carries __const in more than one segment; a name-keyed map loses one",
        )
        assertEquals(consts.size, consts.map { it.section.qualifiedName }.distinct().size)

        println(
            buildString {
                appendLine("razves read ${file.name}")
                appendLine("  file                  ${r.fileSize}")
                appendLine("  header+load commands  ${r.headerBytes}")
                appendLine("  allocated             ${r.allocatedBytes}")
                appendLine("  metadata (__LINKEDIT) ${r.metadataBytes}")
                appendLine("  padding               ${r.interRegionPadding}")
                appendLine("  unparsed              ${r.unparsedBytes}")
                appendLine("  attributed            ${r.attributedBytes}")
                appendLine("  unattributed          ${r.unattributedBytes}")
                appendLine("  __TEXT,__text cover   ${(text.coverage * 1000).toInt() / 10.0}%")
            },
        )
    }

    @Test
    fun theOriginSplitOfARealReleaseBinary() {
        val file = subject() ?: return skipped("no subject binary found")
        val r = Attribution.of(ElfReader.read(file.readBytes(), file.name))

        val byOrigin =
            r.sections
                .flatMap { it.owners }
                .groupBy { Mangling.originOf(it.symbol.name) }
                .mapValues { (_, extents) -> extents.sumOf { it.bytes } }
        val total = byOrigin.values.sum()

        println("origin split of ${file.name} (${r.attributedBytes} attributed):")
        Origin.entries.forEach { origin ->
            val bytes = byOrigin[origin] ?: 0
            println("  ${origin.name.padEnd(16)}$bytes  ${(1000.0 * bytes / total).toInt() / 10.0}%")
        }

        assertEquals(r.attributedBytes, total, "every attributed byte has an origin")
        assertTrue(
            (byOrigin[Origin.KOTLIN_RUNTIME] ?: 0) < (byOrigin[Origin.RUST] ?: 0),
            "the Kotlin/Native runtime is far smaller than the Rust this binary links; if it is not, " +
                "Rust's legacy mangling is being read as C++ and charged to the runtime",
        )

        if (file.length() != MEASURED_FILE_SIZE) return
        // Measured on 2026-09-11 and written into research §1.2. The runtime figure is the one worth
        // pinning: it is what Rust's legacy mangling would have been charged to, and it is 31 times
        // smaller than that Rust.
        assertEquals(5_125_516L, byOrigin[Origin.KOTLIN], "Kotlin")
        assertEquals(40_871L, byOrigin[Origin.KOTLIN_RUNTIME], "the Kotlin/Native runtime")
        assertEquals(1_263_149L, byOrigin[Origin.RUST], "Rust")
        assertEquals(134_811L, byOrigin[Origin.CXX], "other C++")
        assertEquals(6_314_800L, byOrigin[Origin.C], "unmangled C")
    }

    @Test
    fun theSectionsNobodyOwnsAreNamedOneByOne() {
        val file = subject() ?: return skipped("no subject binary found")
        val r = Attribution.report(ElfReader.read(file.readBytes(), file.name))

        println("sections of ${file.name} that no symbol claims a byte of:")
        r.unownedSections.forEach { println("  ${it.section.name.padEnd(20)}${it.section.size}") }
        println("owned sections and their coverage:")
        r.ownedSections.forEach {
            println("  ${it.section.name.padEnd(20)}${it.section.size}  ${(it.coverage * 1000).toInt() / 10.0}%")
        }

        val unowned = r.unownedSections.associate { it.section.name to it.section.size }
        assertEquals(
            r.reconciliation.unattributedBytes,
            r.unownedSections.sumOf { it.section.size } + r.ownedSections.sumOf { it.unattributed },
            "every unattributed byte is in a named section row",
        )
        // Exception unwinding and dynamic linking: 2.2 MB of a 20 MB binary that belongs to no
        // package and would be invisible as a single "unattributed" figure. Measured 2026-09-11.
        if (file.length() != MEASURED_FILE_SIZE) return
        assertEquals(1_287_092L, unowned[".eh_frame"], ".eh_frame")
        assertEquals(213_092L, unowned[".eh_frame_hdr"], ".eh_frame_hdr")
        assertEquals(42_907L, unowned[".gcc_except_table"], ".gcc_except_table")
        assertEquals(269_136L, unowned[".dynsym"], ".dynsym")
        assertEquals(289_336L, unowned[".dynstr"], ".dynstr")
        assertEquals(87_552L, unowned[".gnu.hash"], ".gnu.hash")
    }

    @Test
    fun theKotlinBytesSplitByPackage() {
        val file = subject() ?: return skipped("no subject binary found")
        val r = Attribution.report(ElfReader.read(file.readBytes(), file.name))

        println("Kotlin packages of ${file.name} at depth ${r.packageDepth}:")
        r.packages.take(20).forEach {
            println("  ${it.name.padEnd(36)}${it.bytes.toString().padStart(9)}  ${it.symbols} symbols")
        }
        println("  ... ${r.packages.size} rows in total")

        assertEquals(
            r.bytesOf(Origin.KOTLIN),
            r.packages.sumOf { it.bytes },
            "the package rows are the Kotlin bucket, split - not a sample of it",
        )
        assertTrue(r.packages.isNotEmpty())
        assertTrue(
            r.packages.none { it.name.contains('$') },
            "a synthetic segment is a class, and no package row may carry one",
        )
        assertTrue(
            r.packages.none { row -> row.name.split('.').any { it.isNotEmpty() && it[0].isUpperCase() } },
            "a capitalised segment is a class; found ${r.packages.filter { row ->
                row.name.split('.').any { it.isNotEmpty() && it[0].isUpperCase() }
            }.map { it.name }}",
        )
    }

    @Test
    fun everyPackageAtFullDepthIsOneAKlibDeclares() {
        // The oracle this layer needed. The packages razves derives from mangled names must be
        // packages that actually exist, and something else already knows which those are: every klib
        // manifest carries its own `Non-empty package FQNs` list. A grammar that invents a package -
        // by reading a function name as one, which is exactly the mistake the two symbol forms invite
        // - produces a name no klib has ever heard of.
        val file = subject() ?: return skipped("no subject binary found")
        val klibs = System.getProperty(KLIB_DIR_PROPERTY) ?: return skipped("no klib directory given")
        // More than one root, separated by the path separator: a real link pulls klibs from the
        // dependency cache *and* from the project's own build directory, and an oracle that sees only
        // the first reports every one of the application's own packages as invented. That is what it
        // did on the first run - 32 rows, all of them `ru.workinprogress.shildik.*`.
        val declared = klibs.split(File.pathSeparatorChar).flatMap { declaredPackages(File(it)) }.toSet()
        if (declared.isEmpty()) return skipped("no klib linkdata found under $klibs")

        val r = Attribution.report(ElfReader.read(file.readBytes(), file.name), packageDepth = Int.MAX_VALUE)
        val roots0 = klibs.split(File.pathSeparatorChar).map(::File).filter { it.isDirectory }
        val folded =
            Attribution.report(
                ElfReader.read(file.readBytes(), file.name),
                packageDepth = Int.MAX_VALUE,
                modules = PackageToModule(linkClasspathKlibs(roots0) + unpackedKlibs(roots0)),
            )
        // The application's own packages are not in any dependency's klib, so only the ones that
        // share a root with a declared package can be held against the list.
        val roots = declared.map { it.substringBefore('.') }.toSet()
        val checkable = r.packages.filter { it.name.substringBefore('.') in roots }
        val invented = checkable.filterNot { it.name in declared }
        val inventedBytes = invented.sumOf { it.bytes }
        val share = inventedBytes.toDouble() / checkable.sumOf { it.bytes }

        println(
            "${checkable.size} package rows are under a root some klib declares; " +
                "${invented.size} name no declared package, worth $inventedBytes bytes " +
                "(${(share * 1000).toInt() / 10.0}% of those rows)",
        )
        invented.sortedByDescending { it.bytes }.take(10).forEach {
            println("  not a declared package: ${it.name.padEnd(60)}${it.bytes}")
        }

        // Every one of these is a declaration whose name is lowercase and therefore indistinguishable
        // from a package segment: a cinterop struct class (`sockaddr_un`, `ossl_param_st`,
        // `selection_set`), a lowercase object (`unicodeLT`), a top-level property (`engines`). No
        // grammar over names alone can separate them - but the klib package list can, and B-22 folds
        // such a row up to the longest declared prefix once B-08 has read it.
        //
        // Until then the bound is what it costs, and the bound is measured rather than hoped for.
        assertTrue(
            share < 0.02,
            "package rows naming no declared package are worth ${(share * 1000).toInt() / 10.0}% of the " +
                "checkable Kotlin bytes, which is more than the grammar is allowed to misfile",
        )

        // And with the klib lists supplied, the fold of B-22 removes them: a derived name nothing
        // declares becomes the longest prefix of it that something does.
        val stillInvented =
            folded.packages
                .filter { it.name.substringBefore('.') in roots }
                .filterNot { it.name in declared }
        println(
            "after folding through the klib lists: ${stillInvented.size} rows still name no declared " +
                "package, worth ${stillInvented.sumOf { it.bytes }} bytes ${stillInvented.take(5).map { it.name }}",
        )
        assertTrue(
            stillInvented.sumOf { it.bytes } < invented.sumOf { it.bytes },
            "folding through the klib lists must account for strictly more than the grammar alone",
        )
    }

    private fun declaredPackages(root: File): Set<String> {
        if (!root.isDirectory) return emptySet()
        // A klib carries its package list as `default/linkdata/package_<fqn>/`, and it comes in two
        // shapes: a zip in the dependency cache, and an unpacked directory in the Kotlin/Native
        // distribution - which is where the stdlib and every `platform.*` klib live. Reading only the
        // zips leaves `kotlin.native.ref` and `kotlinx.cinterop` looking invented when they are the
        // most declared packages there are.
        val out = mutableSetOf<String>()
        root.walkTopDown().forEach { file ->
            when {
                file.isFile && file.name.endsWith(".klib") -> {
                    runCatching {
                        java.util.zip.ZipFile(file).use { zip ->
                            zip.entries().asSequence().forEach { entry ->
                                val marker = entry.name.substringAfterLast("/package_", "")
                                if (marker.isNotEmpty()) out += marker.substringBefore('/')
                            }
                        }
                    }
                }

                file.isDirectory && file.name.startsWith("package_") -> {
                    out += file.name.removePrefix("package_")
                }
            }
        }
        return out
    }

    @Test
    fun theKotlinBytesSplitByModule() {
        val file = subject() ?: return skipped("no subject binary found")
        val klibDir = System.getProperty(KLIB_DIR_PROPERTY) ?: return skipped("no klib directory given")
        val roots = klibDir.split(File.pathSeparatorChar).map(::File).filter { it.isDirectory }
        val klibs = linkClasspathKlibs(roots) + unpackedKlibs(roots)
        if (klibs.isEmpty()) return skipped("no readable klibs under $klibDir")

        val map = PackageToModule(klibs)
        val r = Attribution.report(ElfReader.read(file.readBytes(), file.name), modules = map)

        println("Kotlin modules of ${file.name}, from ${map.moduleCount} klibs:")
        r.modules.take(15).forEach {
            println("  ${it.kind.name.take(1)} ${it.name.take(58).padEnd(60)}${it.bytes}")
        }

        assertTrue(r.hasModuleAttribution)
        assertEquals(
            r.bytesOf(Origin.KOTLIN),
            r.modules.sumOf { it.bytes },
            "the module rows are the Kotlin bucket, split - not a sample of it",
        )
        val ambiguous = r.modules.filter { it.kind == ModuleRowKind.AMBIGUOUS }
        val unresolved = r.modules.filter { it.kind == ModuleRowKind.UNATTRIBUTED_TO_A_MODULE }
        println(
            "  resolved ${r.modules.count { it.kind == ModuleRowKind.RESOLVED }} rows, " +
                "${ambiguous.size} ambiguous (${ambiguous.sumOf { it.bytes }} bytes), " +
                "${unresolved.size} with no declaring klib (${unresolved.sumOf { it.bytes }} bytes)",
        )
        assertTrue(
            ambiguous.none { it.name.count { c -> c == ':' } < 2 },
            "an ambiguous row names every module that declares the package, so it carries at least two",
        )
        // Measured on the release subject with a realistic link classpath, 2026-09-11: 40 resolved
        // rows, 8 ambiguous worth 606,570 bytes, 27 with no declaring klib worth 70,038 - so 86.8% of
        // the Kotlin bytes land on a named module. The bounds are loose because the klib set depends
        // on what the machine has built; what they guard is that the answer has not collapsed into
        // ambiguity, which is what a klib set full of build intermediates does to it.
        val kotlin = r.bytesOf(Origin.KOTLIN)
        assertTrue(
            ambiguous.sumOf { it.bytes }.toDouble() / kotlin < 0.25,
            "${ambiguous.sumOf { it.bytes }} of $kotlin Kotlin bytes are ambiguous, which usually means the " +
                "klib set carries a library twice - a published klib and a build intermediate of it",
        )
        assertTrue(
            r.modules.count { it.kind == ModuleRowKind.RESOLVED } > r.modules.size / 2,
            "most module rows should name one module",
        )
    }

    /**
     * The klibs that a link would actually see, which is not the same as every klib on disk.
     *
     * Found the hard way. Sweeping a project's whole build directory picks up
     * `build/kotlinTransformedMetadataLibraries/`, whose copies of a dependency carry a source-set
     * `unique_name` - `kotlinx-datetime_commonMain` beside the published
     * `org.jetbrains.kotlinx:kotlinx-datetime`. They are the same library, and feeding both in makes
     * every package that library declares look declared twice: 69 ambiguous rows worth 3.5 MB of 5.1
     * MB of Kotlin, against the 1.6% the research measured. Nothing about the report looked broken.
     *
     * The rule razves needs is "the link classpath", which is precisely the thing the Gradle plugin
     * knows and a directory sweep does not.
     */
    private fun linkClasspathKlibs(roots: List<File>) =
        roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name.endsWith(".klib") } }
            .filterNot { it.path.contains("kotlinTransformedMetadataLibraries") }
            .filterNot { it.path.contains("/commonized/") }
            .filter { it.name.contains("linuxX64Main") || it.path.contains("/shildik/") }
            .mapNotNull { runCatching { KlibReader.readArchive(it.readBytes(), it.name) }.getOrNull() }

    /**
     * The stdlib and every `platform.*` library ship unpacked in the Kotlin/Native distribution.
     * Without them a quarter of a megabyte of `kotlin.text.regex` has no declaring module.
     */
    private fun unpackedKlibs(roots: List<File>) =
        roots
            .flatMap { root -> root.walkTopDown().filter { it.isDirectory && File(it, "default/manifest").isFile } }
            .mapNotNull { dir ->
                runCatching {
                    KlibReader.readUnpacked(
                        manifestText = File(dir, "default/manifest").readText(),
                        entryNames =
                            dir
                                .walkTopDown()
                                .map {
                                    it
                                        .relativeTo(
                                            dir,
                                        ).path + if (it.isDirectory) "/" else ""
                                }.toList(),
                        name = dir.name,
                        contentOf = { relative -> File(dir, relative).takeIf { it.isFile }?.readBytes() },
                    )
                }.getOrNull()
            }

    private fun machOSubject(): File? {
        val configured = System.getenv(MACHO_SUBJECT_ENV) ?: System.getProperty(MACHO_SUBJECT_ENV)
        return (listOfNotNull(configured) + DEFAULT_MACHO_CANDIDATES).map { File(it) }.firstOrNull { it.isFile }
    }

    private companion object {
        const val SUBJECT_ENV = "RAZVES_ELF_SUBJECT"
        const val MACHO_SUBJECT_ENV = "RAZVES_MACHO_SUBJECT"
        const val KLIB_DIR_PROPERTY = "RAZVES_KLIB_DIR"

        val DEFAULT_MACHO_CANDIDATES =
            listOf(
                "../shildik/core/build/bin/macosArm64/debugTest/test.kexe",
                "../../shildik/core/build/bin/macosArm64/debugTest/test.kexe",
            )

        val DEFAULT_CANDIDATES =
            listOf(
                "../shildik/distribution/build/bin/linuxX64/releaseExecutable/shildik.kexe",
                "../../shildik/distribution/build/bin/linuxX64/releaseExecutable/shildik.kexe",
            )

        // The shildik Postgres release binary, research §1.2. The figure the research calls
        // "allocated sections" is what llvm-size totals, which includes NOBITS; razves separates the
        // two because only one of them costs file bytes, so both halves are pinned here.
        const val MEASURED_FILE_SIZE = 20_543_736L
        const val MEASURED_ALLOCATED_TOTAL = 16_241_668L
        const val MEASURED_ALLOCATED_IN_FILE = 16_213_972L
        const val MEASURED_NOBITS = 27_696L
        const val MEASURED_METADATA = 4_324_353L
        const val MEASURED_HEADERS_AND_PADDING = 5_411L
    }
}
