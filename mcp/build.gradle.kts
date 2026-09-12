plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
}

// The second interface, in a binary of its own - and that is a measurement rather than a preference.
// Built into the `razves` command, the MCP SDK took it from 3,315,112 bytes to 6,948,816, and razves
// asked about itself named the reason: `io.modelcontextprotocol.kotlin` at 1,235,919 bytes over 5,858
// symbols, the largest package in the file, with coroutines and Ktor behind it. A tool that exists to
// tell people where their bytes went cannot carry that in the command they run to find out.
//
// It depends on `:cli` for `Analyse`, so the tools answer with the same code the commands do: one
// implementation, two interfaces, rather than two implementations with their own opinions.
kotlin {
    jvm()
    linuxX64 { binaries.executable { entryPoint = "io.github.youndie.razves.mcp.main" } }
    macosArm64 { binaries.executable { entryPoint = "io.github.youndie.razves.mcp.main" } }

    sourceSets {
        commonMain.dependencies {
            api(project(":cli"))
            implementation(libs.mcp.server)
            implementation(libs.kotlinx.coroutines)
        }
    }
}

// The tests speak to the binary rather than to the object, because two of the three defects found
// while writing this were about the process: the SDK logging into the protocol channel, and a
// transport that is already started by the session.
val mcpLink = tasks.matching { it.name == "linkReleaseExecutableLinuxX64" }

tasks.named<Test>("jvmTest") {
    dependsOn(mcpLink)
    systemProperty(
        "RAZVES_MCP",
        layout.buildDirectory
            .file("bin/linuxX64/releaseExecutable/mcp.kexe")
            .get()
            .asFile.path,
    )
}
