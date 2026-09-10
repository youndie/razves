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

// The real-binary checks need a subject, and a subject is 15-40 MB and belongs to another
// repository, so it is named from the outside rather than committed. An environment variable does
// not reach a Gradle test JVM on its own - the daemon's environment is whatever started it, which
// is rarely the shell that typed the command - so both spellings are turned into system properties
// here:
//
//   ./gradlew :core:jvmTest -PRAZVES_ELF_SUBJECT=../shildik/.../shildik.kexe
//
// Absent, the tests skip themselves by name rather than passing quietly.
tasks.withType<Test>().configureEach {
    for (key in listOf("RAZVES_ELF_SUBJECT", "RAZVES_MACHO_SUBJECT")) {
        val value = providers.gradleProperty(key).orNull ?: providers.environmentVariable(key).orNull
        if (value != null) systemProperty(key, value)
    }
}
