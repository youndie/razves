package io.github.youndie.razves.klib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackageToModuleTest {
    private fun klib(
        name: String,
        vararg packages: String,
    ) = Klib(name, listOf("linux_x64"), packages.toSet())

    @Test
    fun aPackageOneKlibDeclaresResolvesToIt() {
        val map = PackageToModule(listOf(klib("io.ktor:ktor-http", "io.ktor.http", "io.ktor.content")))
        assertEquals(ModuleOwner.One("io.ktor:ktor-http"), map.resolve("io.ktor.http"))
    }

    @Test
    fun aPackageTwoKlibsDeclareIsAmbiguousAndNotResolved() {
        // Measured: 9 of 568 packages across 141 real klibs are declared twice, and `org.koin.core`
        // is one of them and is in the subject binary. A tie-break here produces a number that is
        // confidently wrong, which is the one thing this tool cannot afford.
        val map =
            PackageToModule(
                listOf(
                    klib("io.insert-koin:koin-core", "org.koin.core"),
                    klib("io.insert-koin:koin-ktor", "org.koin.core"),
                ),
            )
        assertEquals(
            ModuleOwner.Ambiguous(listOf("io.insert-koin:koin-core", "io.insert-koin:koin-ktor")),
            map.resolve("org.koin.core"),
        )
    }

    @Test
    fun theModulesOfAnAmbiguousPackageAreSortedSoTwoRunsAgree() {
        val forwards = PackageToModule(listOf(klib("b:b", "p"), klib("a:a", "p")))
        val backwards = PackageToModule(listOf(klib("a:a", "p"), klib("b:b", "p")))
        assertEquals(forwards.resolve("p"), backwards.resolve("p"))
    }

    @Test
    fun aPackageNoKlibDeclaresIsUnknownRatherThanGuessed() {
        val map = PackageToModule(listOf(klib("io.ktor:ktor-http", "io.ktor.http")))
        assertEquals(ModuleOwner.Unknown, map.resolve("ru.workinprogress.shildik.core"))
    }

    @Test
    fun theLongestDeclaredPrefixIsWhatFoldsAMisreadNameBack() {
        // What B-22 will use: `platform.posix.addrinfo` is a cinterop struct class read as a package,
        // and the klib list is the only thing that knows `platform.posix` is where it belongs.
        val map = PackageToModule(listOf(klib("org.jetbrains.kotlin.native.platform.posix", "platform.posix")))
        assertEquals("platform.posix", map.longestDeclaredPrefix("platform.posix.addrinfo"))
        assertEquals("platform.posix", map.longestDeclaredPrefix("platform.posix"))
        assertNull(map.longestDeclaredPrefix("io.ktor.http"))
    }

    @Test
    fun theDeclaredPackagesAreTheAuthorityOnWhatExists() {
        val map =
            PackageToModule(
                listOf(klib("a:a", "one.two", "one.three"), klib("b:b", "four")),
            )
        assertEquals(setOf("one.two", "one.three", "four"), map.declaredPackages)
        assertEquals(2, map.moduleCount)
    }

    @Test
    fun noKlibsAtAllIsAnEmptyMapRatherThanAFailure() {
        val map = PackageToModule(emptyList())
        assertEquals(ModuleOwner.Unknown, map.resolve("anything"))
        assertEquals(0, map.moduleCount)
    }
}
