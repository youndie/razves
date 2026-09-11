rootProject.name = "razves"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("io\\.github\\.youndie.*") }
        }
    }
}

plugins {
    id("io.github.youndie.sborka.settings") version "0.4.0.43"
}

// The reader, the grammar and the report model. Everything interesting lives here, with no Gradle
// API on the classpath, so it is testable without a daemon — see docs/services/core.md.
include(":core")

// A Kotlin/Native binary of known composition, built by the test suite. Not published: it is the one
// fixture that can show attribution working from Kotlin source all the way to a package row.
include(":fixture")
