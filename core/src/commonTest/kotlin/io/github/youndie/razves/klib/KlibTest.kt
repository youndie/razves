package io.github.youndie.razves.klib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KlibTest {
    private val entryNames =
        listOf(
            "default/manifest",
            "default/linkdata/module",
            "default/linkdata/package_io.ktor.content/",
            "default/linkdata/package_io.ktor.content/0_content.knm",
            "default/linkdata/package_io.ktor.http/",
            "default/linkdata/package_io.ktor.http/00_http.knm",
            "default/ir/bodies.knb",
        )

    @Test
    fun aManifestCoordinateIsUnescaped() {
        // Copied out of a real manifest. A module coordinate is written with the colon escaped,
        // because a colon would otherwise separate key from value - so a reader that does not
        // unescape produces a `unique_name` with a backslash in it, which then matches nothing.
        val klib =
            KlibReader.readUnpacked(
                manifestText =
                    """
                    abi_version=2.3.0
                    unique_name=io.ktor\:ktor-http
                    native_targets=linux_x64
                    """.trimIndent(),
                entryNames = entryNames,
                name = "ktor-http",
            )

        assertEquals("io.ktor:ktor-http", klib.uniqueName)
        assertEquals(listOf("linux_x64"), klib.targets)
    }

    @Test
    fun thePackageListComesFromTheEntryNames() {
        val klib = KlibReader.readUnpacked("unique_name=io.ktor\\:ktor-http", entryNames, "ktor-http")
        assertEquals(setOf("io.ktor.content", "io.ktor.http"), klib.packages)
    }

    @Test
    fun severalTargetsAreSplitOnSpaces() {
        val klib =
            KlibReader.readUnpacked(
                "unique_name=a\\:b\nnative_targets=linux_x64 linux_arm64 macos_arm64",
                entryNames,
                "a",
            )
        assertEquals(listOf("linux_x64", "linux_arm64", "macos_arm64"), klib.targets)
    }

    @Test
    fun aMetadataOnlyKlibHasNoTargetsRatherThanAnEmptyString() {
        val klib = KlibReader.readUnpacked("unique_name=a\\:b", entryNames, "a")
        assertTrue(klib.targets.isEmpty())
    }

    @Test
    fun aContinuedLineIsOneEntry() {
        // `depends` in a real manifest runs to several hundred characters and is wrapped.
        val klib =
            KlibReader.readUnpacked(
                "unique_name=a\\:b\ndepends=stdlib \\\n  org.jetbrains.kotlinx\\:atomicfu\nnative_targets=linux_x64",
                entryNames,
                "a",
            )
        assertEquals("a:b", klib.uniqueName)
        assertEquals(listOf("linux_x64"), klib.targets, "the wrapped line must not swallow what follows it")
    }

    @Test
    fun commentsAndBlankLinesAreSkipped() {
        val klib =
            KlibReader.readUnpacked(
                "# a comment\n\n! another\nunique_name=a\\:b\n",
                entryNames,
                "a",
            )
        assertEquals("a:b", klib.uniqueName)
    }

    @Test
    fun aManifestWithNoUniqueNameIsRefused() {
        val failure =
            assertFailsWith<IllegalStateException> {
                KlibReader.readUnpacked("abi_version=2.3.0", entryNames, "nameless")
            }
        assertTrue(failure.message.orEmpty().contains("unique_name"))
    }

    @Test
    fun anUnpackedDirectoryReadsTheSameAsAnArchive() {
        // The Kotlin/Native distribution ships the stdlib and every platform klib unpacked, so both
        // shapes have to produce the same Klib or half the packages in a report go missing.
        val klib =
            KlibReader.readUnpacked(
                "unique_name=org.jetbrains.kotlin.native.platform.posix",
                listOf("linkdata/package_platform.posix/", "linkdata/package_platform.posix/0_posix.knm"),
                "platform.posix",
            )
        assertEquals("org.jetbrains.kotlin.native.platform.posix", klib.uniqueName)
        assertEquals(setOf("platform.posix"), klib.packages)
    }
}
