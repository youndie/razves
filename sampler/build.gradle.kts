plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    // PUBLISHED, and it is the only module here that has to be. Everything else in razves reads files
    // after the fact; this is the half a profiled program links, so a profiler whose sampler lives
    // only in this build is a profiler nobody outside it can use.
    id("io.github.youndie.sborka.publish")
}

// The half of the profiler that runs inside somebody else process, and the only part of razves that
// is ever linked into one. It is a C signal handler behind cinterop plus a thin Kotlin wrapper -
// see docs/research/research-profiler.md 1.1 for the measurement that put the handler in C.
//
// Both native targets now, and the three differences are in the C rather than in a second module:
// Apple has no POSIX timers (setitimer instead), keeps the interrupted program counter in a named
// field rather than an indexed register, and loads a PIE executable at a slide the profile has to
// carry. The Kotlin above it is the same on both.
kotlin {
    // The jvm target carries the tests and no implementation: a sampler is a signal handler, and
    // there is no such thing to offer a JVM consumer. What they get if they ask is a compile error
    // naming `Sampler`, which is the honest answer - better than an empty class that does nothing at
    // run time.
    jvm()

    // The end-to-end test parses the dump with `core` and aggregates it. A TEST dependency only:
    // what goes into somebody else process is this module and nothing else.
    sourceSets.jvmTest.dependencies { implementation(project(":core")) }

    listOf(linuxX64(), macosArm64()).forEach { target ->
        target.compilations
            .getByName("main")
            .cinterops
            .create("sampler")

        // The subject of the survival test. A test inside one process cannot observe a process that
        // hangs, which is the failure this module exists to avoid.
        target.binaries.executable("probe") {
            entryPoint = "io.github.youndie.razves.sampler.main"
        }
    }
}

// The survival test runs the probe, so it needs the probe linked - and it is a JVM test because what
// it does is start processes and time them out, which is a thing to do from outside.
// The host target is the one a test can run: a Linux runner links no Mach-O and a mac links no ELF.
val hostTarget = if (System.getProperty("os.name").startsWith("Mac")) "MacosArm64" else "LinuxX64"
val probeLink = tasks.matching { it.name == "linkProbeDebugExecutable$hostTarget" }

tasks.named<Test>("jvmTest") {
    dependsOn(probeLink)
    systemProperty(
        "RAZVES_PROBE",
        layout.buildDirectory
            .file("bin/${hostTarget.replaceFirstChar { it.lowercase() }}/probeDebugExecutable/probe.kexe")
            .get()
            .asFile.path,
    )
}
