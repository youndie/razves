package io.github.youndie.razves.read

import io.github.youndie.razves.report.Attribution
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
            r.headerBytes + r.allocatedBytes + r.notAllocatedBytes + r.interRegionPadding,
        )
        assertEquals(r.allocatedBytes, r.attributedBytes + r.unattributedBytes)
        assertTrue(image.hasSymbolTable, "${file.name} is stripped; razves has nothing to read in it")

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
                appendLine("  not allocated         ${r.notAllocatedBytes}")
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
        assertEquals(MEASURED_NOT_ALLOCATED, r.notAllocatedBytes, "non-allocated section bytes")
        assertEquals(MEASURED_HEADERS_AND_PADDING, r.headerBytes + r.interRegionPadding, "headers plus padding")
    }

    private companion object {
        const val SUBJECT_ENV = "RAZVES_ELF_SUBJECT"

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
        const val MEASURED_NOT_ALLOCATED = 4_324_353L
        const val MEASURED_HEADERS_AND_PADDING = 5_411L
    }
}
