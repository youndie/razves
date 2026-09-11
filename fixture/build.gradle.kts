plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
}

// A Kotlin/Native binary whose contents razves knows by construction: three packages, with
// declarations in the shapes the mangling distinguishes. Nothing here is published - it exists so
// that the attribution can be checked end to end, from Kotlin source to a package row, which no
// hand-built ELF fixture can demonstrate and no real binary can state the expected answer for.
kotlin {
    jvm()
    linuxX64 { binaries.executable { entryPoint = "razvesfixture.main" } }
    macosArm64 { binaries.executable { entryPoint = "razvesfixture.main" } }

    sourceSets {
        jvmTest.dependencies { implementation(project(":core")) }
    }
}

// The checks live in this module rather than in `core` because the thing under test is this module's
// own build output: wiring a cross-project dependency on a native link task would be more Gradle than
// the test is worth, and the test would still have to skip when the target cannot link on this host.
val nativeLinkTasks = tasks.matching { it.name.startsWith("linkDebugExecutable") }

tasks.named<Test>("jvmTest") {
    dependsOn(nativeLinkTasks)
    // Both paths, and the test skips by name for whichever target this host cannot link: a Linux
    // runner builds no macosArm64 binary, and a check that passed quietly because its input was
    // missing is worse than no check.
    systemProperty(
        "RAZVES_FIXTURE_LINUX",
        layout.buildDirectory
            .file("bin/linuxX64/debugExecutable/fixture.kexe")
            .get()
            .asFile.path,
    )
    systemProperty(
        "RAZVES_FIXTURE_MACOS",
        layout.buildDirectory
            .file("bin/macosArm64/debugExecutable/fixture.kexe")
            .get()
            .asFile.path,
    )
}
