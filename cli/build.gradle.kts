plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
}

// The same attribution as the plugin, for a binary nobody here built. A native executable rather
// than a jar, because the tool exists to serve people who ship a single binary and telling them to
// install a JVM to measure one would be absurd.
//
// `jvm` is here for the tests only: nothing is published from it, and the CLI's own logic is common
// code either way.
kotlin {
    jvm()
    linuxX64 { binaries.executable { entryPoint = "io.github.youndie.razves.cli.main" } }
    macosArm64 { binaries.executable { entryPoint = "io.github.youndie.razves.cli.main" } }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.clikt)
            implementation(libs.kotlinx.io)
        }
    }
}
