plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
}

kotlin {
    // The three that matter: `jvm` because the Gradle plugin runs there, and the two native ones
    // because the CLI ships as a binary and because a Mach-O fixture cannot be built anywhere else.
    jvm()
    linuxX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        // `kotlin-test` is not declared here: `io.github.youndie.sborka.kmp` puts it on commonTest,
        // version-managed by the Kotlin plugin, and a second constraint on the same module makes the
        // metadata compilation resolve neither.
    }
}
