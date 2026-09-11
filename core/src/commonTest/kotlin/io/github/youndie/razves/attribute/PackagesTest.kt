package io.github.youndie.razves.attribute

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Every symbol quoted here was copied out of `llvm-nm` on a real `linuxX64` release binary. That is
 * the point of the file: the grammar was read off a binary rather than recalled, and the two forms
 * below are the reason it had to be.
 */
class PackagesTest {
    @Test
    fun aPublicTopLevelFunctionPutsItsNameAfterTheHash() {
        assertEquals(
            "dev.whyoleg.cryptography.providers.base",
            Packages.of("kfun:dev.whyoleg.cryptography.providers.base#checkBounds(kotlin.Int;kotlin.Int;kotlin.Int){}"),
        )
        assertEquals(
            "io.ktor.server.engine",
            Packages.of(
                "kfun:io.ktor.server.engine#handleFailure#suspend(io.ktor.server.application.ApplicationCall){}",
            ),
        )
    }

    @Test
    fun anInternalTopLevelFunctionPutsItsNameInsideTheContainer() {
        // The form that breaks the obvious rule. `removeLeadingZeros` is a function, not a package,
        // and there are thousands of symbols shaped like this in a real binary - one for every
        // internal or private declaration.
        assertEquals(
            "dev.whyoleg.cryptography.bigint",
            Packages.of("kfun:dev.whyoleg.cryptography.bigint.removeLeadingZeros#internal"),
        )
        assertEquals("kotlin.time", Packages.of("kfun:kotlin.time.parseIso#internal"))
        assertEquals(
            "dev.whyoleg.cryptography.providers.openssl3.internal",
            Packages.of("kfun:dev.whyoleg.cryptography.providers.openssl3.internal.fail#internal"),
        )
    }

    @Test
    fun aMemberOfAClassStopsAtTheClass() {
        assertEquals("kotlin.time", Packages.of("kfun:kotlin.time.Clock.System#now(){}kotlin.time.Instant"))
        assertEquals("io.ktor.http", Packages.of("kclass:io.ktor.http.HttpStatusCode"))
        assertEquals(
            "dev.whyoleg.cryptography.providers.base.algorithms",
            Packages.of(
                "kfun:dev.whyoleg.cryptography.providers.base.algorithms.BaseAes.BaseKeyDecoder." +
                    "decodeFromByteArrayBlocking#internal",
            ),
        )
    }

    @Test
    fun aSyntheticSegmentIsAClassRatherThanAPackage() {
        // `$init_global`, `$handleFailureCOROUTINE$0`, `defaultProvider$1` - everything the compiler
        // generated carries a `$`, and none of it is a package.
        assertEquals("kotlin.time", Packages.of("kfun:kotlin.time.\$init_global#internal"))
        assertEquals(
            "io.ktor.server.engine",
            Packages.of("kfun:io.ktor.server.engine.\$handleFailureCOROUTINE\$0.invokeSuspend#internal"),
        )
        assertEquals(
            "dev.whyoleg.cryptography.providers.openssl3",
            Packages.of("kfun:dev.whyoleg.cryptography.providers.openssl3.defaultProvider\$1.invoke#internal"),
        )
    }

    @Test
    fun depthTruncatesTheName() {
        val symbol = "kfun:dev.whyoleg.cryptography.providers.base#checkBounds(kotlin.Int){}"
        assertEquals("dev", Packages.of(symbol, depth = 1))
        assertEquals("dev.whyoleg.cryptography", Packages.of(symbol, depth = 3))
        assertEquals("dev.whyoleg.cryptography.providers.base", Packages.of(symbol, depth = 99))
    }

    @Test
    fun aDepthOfZeroIsRefusedRatherThanReturningNothing() {
        assertFailsWith<IllegalArgumentException> {
            Packages.of("kfun:io.ktor.http#parse(kotlin.String){}", depth = 0)
        }
    }

    @Test
    fun everyKotlinPrefixCarriesAPackage() {
        for (prefix in Mangling.KOTLIN_PREFIXES) {
            assertEquals("io.ktor.http", Packages.of("$prefix:io.ktor.http.HttpStatusCode"), prefix)
        }
    }

    @Test
    fun theLeadingUnderscoreOfAMachOSymbolDoesNotChangeThePackage() {
        assertEquals("kotlin.time", Packages.of("_kfun:kotlin.time.parseIso#internal"))
    }

    @Test
    fun aSymbolThatIsNotKotlinHasNoPackage() {
        assertNull(Packages.of("ossl_aes_gcm_encrypt_avx512"))
        assertNull(Packages.of("_ZN5tokio7runtime5build17h55d0632c5a2d2c9aE"))
    }

    @Test
    fun theRootPackageIsNamedRatherThanBlank() {
        assertEquals(Packages.ROOT, Packages.of("kfun:#main(kotlin.Array<kotlin.String>){}"))
        assertEquals(Packages.ROOT, Packages.of("kclass:Main"))
        assertEquals(Packages.ROOT, Packages.of("kfun:topLevel#internal"))
    }

    @Test
    fun aCapitalisedPackageSegmentReadsAsAClass() {
        // A stated limitation rather than a case to guess at. Kotlin packages are lowercase by
        // convention, and guessing the other way would misfile the far more common capitalised
        // class - which is thousands of symbols against approximately none.
        assertEquals("com", Packages.of("kfun:com.Example.thing#internal"))
    }
}
