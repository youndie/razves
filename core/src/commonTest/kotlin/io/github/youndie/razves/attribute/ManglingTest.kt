package io.github.youndie.razves.attribute

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManglingTest {
    @Test
    fun everyKotlinPrefixIsRecognised() {
        // All twelve, counted over a real linuxX64 release binary and a real macosArm64 one. The
        // three the tool was first sketched around are half the Kotlin symbols; the other nine are
        // Kotlin too, and a grammar that ignores them reports Kotlin as smaller than it is.
        for (prefix in Mangling.KOTLIN_PREFIXES) {
            val name = "$prefix:io.ktor.server.engine.embeddedServer"
            assertEquals(Origin.KOTLIN, Mangling.originOf(name), name)
            assertEquals(prefix, Mangling.kotlinPrefixOf(name), name)
        }
    }

    @Test
    fun theLeadingUnderscoreOfAMachOSymbolIsNotAPrefixOfItsOwn() {
        assertEquals(Origin.KOTLIN, Mangling.originOf("_kfun:kotlin.time.parseIso#internal"))
        assertEquals("kfun", Mangling.kotlinPrefixOf("_kfun:kotlin.time.parseIso#internal"))
        assertEquals("kotlin.time.parseIso#internal", Mangling.kotlinBodyOf("_kfun:kotlin.time.parseIso#internal"))
    }

    @Test
    fun aKotlinLookingPrefixThatIsNotOneIsNotKotlin() {
        assertNull(Mangling.kotlinPrefixOf("kernel:something"))
        assertEquals(Origin.C, Mangling.originOf("kernel:something"))
        assertNull(Mangling.kotlinPrefixOf("kfunny:something"))
    }

    @Test
    fun rustLegacyManglingIsRustAndNotTheKotlinRuntime() {
        // The trap. Rust's legacy scheme shares Itanium's `_ZN` prefix and is separated from it only
        // by the trailing 16-hex-digit disambiguator. Reading these as C++ charges about 964 KB of
        // tokio and sqlx_postgres to a Kotlin/Native runtime that is 14 KB in the same binary, and
        // nothing about the resulting report looks wrong.
        val samples =
            listOf(
                "_ZN5tokio7runtime7builder7Builder5build17h55d0632c5a2d2c9aE",
                "_ZN13sqlx_postgres10connection9establish17h7c2ac2d78526d9b3E",
                "_ZN15sqlx4k_postgres30decode_postgresql_column_value17h64cf441d6bd521f3E",
            )
        for (name in samples) assertEquals(Origin.RUST, Mangling.originOf(name), name)
    }

    @Test
    fun rustV0ManglingIsRust() {
        val name = "_RINvMs0_NtNtNtCs3JBboNF8E1m_3std12backtrace_rs9symbolize5gimliNtB6_5Cache11with_globalE"
        assertEquals(Origin.RUST, Mangling.originOf(name))
    }

    @Test
    fun aMachOCppSymbolCarriesTwoUnderscoresAndAnElfOneCarriesOne() {
        // The obvious version of undoing Mach-O's decoration — strip any leading underscore — turns
        // every ELF `_ZN…` into `ZN…` and files the entire Kotlin/Native runtime under C. Only the
        // doubled form is a decoration.
        assertEquals(Origin.KOTLIN_RUNTIME, Mangling.originOf("_ZN6kotlin2gc5State4nameEv"))
        assertEquals(Origin.KOTLIN_RUNTIME, Mangling.originOf("__ZN6kotlin2gc5State4nameEv"))
        assertEquals(Origin.RUST, Mangling.originOf("__ZN5tokio7runtime5build17h55d0632c5a2d2c9aE"))
    }

    @Test
    fun theKotlinNativeRuntimeIsItsOwnBucket() {
        assertEquals(Origin.KOTLIN_RUNTIME, Mangling.originOf("_ZN6kotlin2gc19GCSchedulerThreadDataC1Ev"))
        assertEquals(Origin.KOTLIN_RUNTIME, Mangling.originOf("_ZN5konan9internal12AllocatorMap5clearEv"))
    }

    @Test
    fun otherCxxIsCxx() {
        assertEquals(Origin.CXX, Mangling.originOf("_ZN3abc4funcEv"))
        assertEquals(Origin.CXX, Mangling.originOf("_ZNSt3__16vectorIiNS_9allocatorIiEEE6resizeEm"))
        assertEquals(Origin.CXX, Mangling.originOf("_ZSt9terminatev"))
    }

    @Test
    fun aQualifiedNestedNameStillFindsItsFirstComponent() {
        // `K` and `V` are cv-qualifiers and `R`/`O` are ref-qualifiers; any of them may sit between
        // `_ZN` and the first length-prefixed component, and skipping them is the difference between
        // finding `kotlin` and finding nothing.
        assertEquals(Origin.KOTLIN_RUNTIME, Mangling.originOf("_ZNK6kotlin2gc5State4nameEv"))
        assertEquals(Origin.KOTLIN_RUNTIME, Mangling.originOf("_ZNKR5konan4Heap4sizeEv"))
    }

    @Test
    fun unmangledNamesAreC() {
        // 26.7% of the attributed bytes of the measured release binary look like this. The C ABI has
        // no namespaces, so no grammar can recover where they came from — that is B-09's problem, not
        // this one's, and pretending otherwise here would be the wrong kind of confident.
        val samples =
            listOf(
                "ossl_aes_gcm_encrypt_avx512",
                "ecp_nistz256_precomputed",
                "nid_objs",
                "sha1_multi_block",
                "__unnamed_3673",
                "curve448_precomputed_base_table",
            )
        for (name in samples) assertEquals(Origin.C, Mangling.originOf(name), name)
    }

    @Test
    fun anEmptyOrTrivialNameDoesNotThrow() {
        assertEquals(Origin.C, Mangling.originOf(""))
        assertEquals(Origin.C, Mangling.originOf("_"))
        assertEquals(Origin.C, Mangling.originOf(":"))
        assertEquals(Origin.CXX, Mangling.originOf("_ZN"))
        assertEquals(Origin.CXX, Mangling.originOf("_ZN99xE"))
    }

    @Test
    fun theKotlinBodyKeepsEverythingAfterThePrefix() {
        assertEquals(
            "io.ktor.server.engine#embeddedServer(io.ktor.server.engine.ApplicationEngineFactory)",
            Mangling.kotlinBodyOf(
                "kfun:io.ktor.server.engine#embeddedServer(io.ktor.server.engine.ApplicationEngineFactory)",
            ),
        )
        assertNull(Mangling.kotlinBodyOf("ossl_aes_gcm_encrypt_avx512"))
    }
}
