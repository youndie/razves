package io.github.youndie.razves.report

import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.fixture.MachOBuilder
import io.github.youndie.razves.klib.Klib
import io.github.youndie.razves.read.ElfReader
import io.github.youndie.razves.read.MachOReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two inputs that produce a report which looks fine and means nothing.
 *
 * Both are refusals rather than warnings, and that is the whole design: a warning printed above a
 * report is read after the reader has already believed the numbers.
 */
class RefusalsTest {
    private fun elf(): ByteArray {
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        return builder
            .text(address = 0x1000, size = 256)
            .symbol(ElfBuilder.SymbolSpec("kfun:io.ktor.http#parse(kotlin.String){}", 0x1000, 256, text))
            .build()
    }

    private fun klibList(targets: List<String>) = listOf(Klib("io.ktor:ktor-http", targets, setOf("io.ktor.http")))

    @Test
    fun aStrippedBinaryIsRefusedAndNotReportedAsEmpty() {
        val stripped = ElfReader.read(ElfBuilder().text(address = 0x1000, size = 256).stripped().build(), "stripped")

        val failure = assertFailsWith<IllegalArgumentException> { Attribution.report(stripped) }

        assertTrue(failure.message.orEmpty().contains("stripped"))
        assertTrue(
            failure.message.orEmpty().contains("link output"),
            "the message has to say what to do instead, or the refusal is just an obstacle",
        )
    }

    @Test
    fun theReconciliationStillWorksOnAStrippedBinary() {
        // The refusal is about attribution, not about reading. Section-level reconciliation needs no
        // symbols and is a legitimate thing to want from a stripped artifact - it is simply not what
        // `report` promises, so the lower level stays open.
        val stripped = ElfReader.read(ElfBuilder().text(address = 0x1000, size = 256).stripped().build(), "stripped")

        val r = Attribution.of(stripped)

        assertEquals(256, r.allocatedBytes)
        assertEquals(256, r.unattributedBytes)
        assertEquals(0, r.attributedBytes)
    }

    @Test
    fun anElfBinaryAgainstAppleKlibsIsRefused() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                Attribution.report(ElfReader.read(elf(), "app.kexe"), klibs = klibList(listOf("macos_arm64")))
            }

        assertTrue(failure.message.orEmpty().contains("macos_arm64"))
        assertTrue(
            failure.message.orEmpty().contains("linux_x64"),
            "the message names both sides; one of them alone leaves the reader guessing which is wrong",
        )
    }

    @Test
    fun anElfBinaryAgainstLinuxKlibsIsAccepted() {
        val report = Attribution.report(ElfReader.read(elf(), "app.kexe"), klibs = klibList(listOf("linux_x64")))
        assertTrue(report.hasModuleAttribution)
    }

    @Test
    fun anElfX86BinaryIsEitherLinuxOrAndroidAndNeitherIsRefused() {
        // The container says x86-64 and nothing about the operating system, so razves names both
        // rather than guessing one - and then an Android klib set is not a contradiction.
        val report = Attribution.report(ElfReader.read(elf(), "app.kexe"), klibs = klibList(listOf("android_x64")))
        assertTrue(report.hasModuleAttribution)
    }

    @Test
    fun aMachOBinaryKnowsItIsMacOsFromItsBuildVersion() {
        val builder = MachOBuilder()
        val text = builder.nextSectionOrdinal
        val bytes =
            builder
                .section(MachOBuilder.SectionSpec("__TEXT", "__text", 0x1000, 256))
                .symbol(MachOBuilder.SymbolSpec("_kfun:io.ktor.http#parse(kotlin.String){}", 0x1000, text))
                .build()
        val image = MachOReader.read(bytes, "app.kexe")

        // The fixture emits no LC_BUILD_VERSION, so the platform is unknown - and an unknown platform
        // means an unknown target, which means no refusal. A check that cannot identify its subject
        // must not veto it.
        assertTrue(image.targets.isEmpty())
        val report = Attribution.report(image, klibs = klibList(listOf("linux_x64")))
        assertTrue(report.hasModuleAttribution, "razves cannot name this target, so it claims nothing about it")
    }

    @Test
    fun aKlibThatNamesNoTargetIsDroppedRatherThanTrusted() {
        // A klib with no `native_targets` is metadata-only and is not what the linker read. Dropping
        // it is right; when dropping leaves nothing, razves says so rather than reporting on an empty
        // map as though no klibs had been supplied at all.
        val failure =
            assertFailsWith<IllegalArgumentException> {
                Attribution.report(ElfReader.read(elf(), "app.kexe"), klibs = klibList(emptyList()))
            }

        assertTrue(failure.message.orEmpty().contains("no target at all"))
    }

    @Test
    fun klibsForAnotherPlatformAreDroppedAndTheRestStillAnswer() {
        // The case this filter exists for. Pointing at a Gradle cache picks up the JS and Wasm
        // standard libraries, whose `unique_name` is `kotlin` where the Kotlin/Native distribution
        // calls the same library `stdlib` - and the pair makes every standard-library package
        // ambiguous. Measured on razves' own binary before the filter: 1,248,065 bytes in one row.
        val mixed =
            listOf(
                Klib("kotlin", emptyList(), setOf("io.ktor.http")),
                Klib("io.ktor:ktor-http", listOf("linux_x64"), setOf("io.ktor.http")),
            )

        val report = Attribution.report(ElfReader.read(elf(), "app.kexe"), klibs = mixed)

        assertEquals(listOf("io.ktor:ktor-http"), report.modules.map { it.name })
        assertEquals(
            listOf(ModuleRowKind.RESOLVED),
            report.modules.map { it.kind },
            "the klib that could not have been linked is gone, and the ambiguity with it",
        )
    }

    @Test
    fun anElfBinaryReportsItsTargetsWithoutKlibs() {
        assertEquals(setOf("linux_x64", "android_x64"), ElfReader.read(elf(), "app.kexe").targets)
    }
}
