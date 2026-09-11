plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
}

// The half of the profiler that runs inside somebody else process, and the only part of razves that
// is ever linked into one. It is a C signal handler behind cinterop plus a thin Kotlin wrapper -
// see docs/research/research-profiler.md 1.1 for the measurement that put the handler in C.
//
// linuxX64 only, and said out loud rather than half-supported: timer_create does not exist on Apple
// targets, the register context is laid out differently and the image slides. That is B-36.
kotlin {
    jvm()

    linuxX64 {
        compilations.getByName("main").cinterops.create("sampler")

        // The subject of the survival test. A test inside one process cannot observe a process that
        // hangs, which is the failure this module exists to avoid.
        binaries.executable("probe") {
            entryPoint = "io.github.youndie.razves.sampler.main"
        }
    }
}

// The survival test runs the probe, so it needs the probe linked - and it is a JVM test because what
// it does is start processes and time them out, which is a thing to do from outside.
val probeLink = tasks.matching { it.name == "linkProbeDebugExecutableLinuxX64" }

tasks.named<Test>("jvmTest") {
    dependsOn(probeLink)
    systemProperty(
        "RAZVES_PROBE",
        layout.buildDirectory
            .file("bin/linuxX64/probeDebugExecutable/probe.kexe")
            .get()
            .asFile.path,
    )
}
