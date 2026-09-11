package io.github.youndie.razves.report

import io.github.youndie.razves.attribute.Origin
import io.github.youndie.razves.fixture.ElfBuilder
import io.github.youndie.razves.klib.Klib
import io.github.youndie.razves.read.ElfReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Folding a derived package name up to one a klib declares.
 *
 * The case this exists for: `kfun:platform.posix.addrinfo.$init_global#internal` names a cinterop
 * struct class that kept its C name, and no grammar over names can tell it from a package segment.
 * The klib package lists can, and folding is the whole fix.
 */
class FoldedPackagesTest {
    private fun binary(vararg symbols: String): ByteArray {
        val builder = ElfBuilder()
        val text = builder.nextSectionIndex
        builder.text(address = 0x1000, size = 64 * symbols.size)
        symbols.forEachIndexed { i, name ->
            builder.symbol(ElfBuilder.SymbolSpec(name, 0x1000 + 64L * i, 64, text))
        }
        return builder.build()
    }

    private fun klibs(vararg declared: Pair<String, List<String>>) =
        declared.map { (module, packages) -> Klib(module, listOf("linux_x64"), packages.toSet()) }

    @Test
    fun aCinteropStructClassFoldsIntoItsPackage() {
        val image =
            ElfReader.read(
                binary("kfun:platform.posix.addrinfo.\$init_global#internal", "kfun:platform.posix.socket#internal"),
                "fixture",
            )

        val bare = Attribution.report(image, packageDepth = Int.MAX_VALUE)
        assertEquals(
            setOf("platform.posix.addrinfo", "platform.posix"),
            bare.packages.map { it.name }.toSet(),
            "without klibs the grammar is all there is, and it reads the struct name as a package",
        )

        val folded =
            Attribution.report(
                image,
                packageDepth = Int.MAX_VALUE,
                klibs = klibs("org.jetbrains.kotlin.native.platform.posix" to listOf("platform.posix")),
            )
        assertEquals(listOf("platform.posix"), folded.packages.map { it.name })
        assertEquals(128, folded.packages.single().bytes, "both symbols, in one row")
    }

    @Test
    fun theFoldMovesTheModuleRowTooSoTheTwoLayersAgree() {
        val image = ElfReader.read(binary("kfun:platform.posix.addrinfo.\$init_global#internal"), "fixture")
        val map = klibs("org.jetbrains.kotlin.native.platform.posix" to listOf("platform.posix"))

        val r = Attribution.report(image, packageDepth = Int.MAX_VALUE, klibs = map)

        assertEquals(listOf("org.jetbrains.kotlin.native.platform.posix"), r.modules.map { it.name })
        assertEquals(
            listOf(ModuleRowKind.RESOLVED),
            r.modules.map { it.kind },
            "the row that folds into a declared package is the row that stops being unattributable",
        )
    }

    @Test
    fun theFoldNeverInvents() {
        // No declared prefix at all: the derived name stands, because there is no authority to appeal
        // to and pretending otherwise would be the one thing this tool must not do.
        val image = ElfReader.read(binary("kfun:ru.workinprogress.shildik.core.di.load#internal"), "fixture")

        val r =
            Attribution.report(
                image,
                packageDepth = Int.MAX_VALUE,
                klibs = klibs("io.ktor:ktor-http" to listOf("io.ktor.http")),
            )

        assertEquals(listOf("ru.workinprogress.shildik.core.di"), r.packages.map { it.name })
        assertEquals(listOf(ModuleRowKind.UNATTRIBUTED_TO_A_MODULE), r.modules.map { it.kind })
    }

    @Test
    fun aPackageThatIsAlreadyDeclaredIsNotFoldedShorter() {
        val image = ElfReader.read(binary("kfun:io.ktor.http.HttpStatusCode#toString(){}kotlin.String"), "fixture")
        val map = klibs("io.ktor:ktor-http" to listOf("io.ktor", "io.ktor.http"))

        val r = Attribution.report(image, packageDepth = Int.MAX_VALUE, klibs = map)

        assertEquals(listOf("io.ktor.http"), r.packages.map { it.name }, "the longest declared prefix is itself")
    }

    @Test
    fun foldingDoesNotMoveAnyBytes() {
        val image =
            ElfReader.read(
                binary(
                    "kfun:platform.posix.addrinfo.\$init_global#internal",
                    "kfun:io.ktor.http.HttpStatusCode#toString(){}kotlin.String",
                    "ossl_aes_gcm_encrypt_avx512",
                ),
                "fixture",
            )
        val map = klibs("org.jetbrains.kotlin.native.platform.posix" to listOf("platform.posix"))

        val bare = Attribution.report(image, packageDepth = Int.MAX_VALUE)
        val folded = Attribution.report(image, packageDepth = Int.MAX_VALUE, klibs = map)

        assertEquals(bare.bytesOf(Origin.KOTLIN), folded.bytesOf(Origin.KOTLIN))
        assertEquals(bare.packages.sumOf { it.bytes }, folded.packages.sumOf { it.bytes })
        assertEquals(folded.bytesOf(Origin.KOTLIN), folded.modules.sumOf { it.bytes })
        // Folding renames a row and, where two derived names fold to the same target, joins two into
        // one. It never moves a byte between origins and never loses one, which is what the sums
        // above say; here it is a rename, because each derived name appears once.
        assertTrue("platform.posix.addrinfo" in bare.packages.map { it.name })
        assertTrue("platform.posix" in folded.packages.map { it.name })
        assertEquals(bare.packages.size, folded.packages.size)
    }
}
