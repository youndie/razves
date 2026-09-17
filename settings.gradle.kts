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
    id("io.github.youndie.sborka.settings") version "0.4.0.87"
}

// The reader, the grammar and the report model. Everything interesting lives here, with no Gradle
// API on the classpath, so it is testable without a daemon — see docs/services/core.md.
include(":core")

// A Kotlin/Native binary of known composition, built by the test suite. Not published: it is the one
// fixture that can show attribution working from Kotlin source all the way to a package row.
include(":fixture")

// The tool as a command. A native executable per target, because razves exists for people who ship
// a single binary.
include(":cli")

// The in-process half of the profiler: a C signal handler behind cinterop and a thin wrapper. The
// only module here that is ever linked into a program razves did not build - which is why it is a
// module and not a package inside `core`, and why nothing else depends on it.
include(":sampler")

// The same answers over MCP, and a separate binary because of what the SDK weighs: measured at
// +3,633,704 bytes when it was a subcommand of `razves`, which is more than the tool itself.
include(":mcp")

// The gate. Its one irreplaceable job is supplying the link classpath: a directory sweep picks up
// transformed copies of a dependency and makes every package it declares look declared twice.
include(":gradle-plugin")
