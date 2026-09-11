plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm")
    id("io.github.youndie.sborka.jvm")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
}

// What only the build knows: which binary was linked, and which klibs took part. Everything else is
// `core`, which has no Gradle API on its classpath and is testable without a daemon.
dependencies {
    implementation(project(":core"))
    compileOnly(libs.kotlin.gradle.plugin)
    testImplementation(gradleTestKit())
}

// The Kotlin Gradle Plugin is `compileOnly` because a published plugin must not bundle it - the
// build applying razves already has one, at its own version. TestKit, though, hands the plugin under
// test only its RUNTIME classpath, so without this the plugin cannot even be instantiated: Gradle
// resolves a plugin class's method signatures while decorating it, and `register(..., Executable)`
// names a KGP type. The failure arrives as "Could not generate a decorated class", which says
// nothing about a missing dependency.
// `compileOnly` itself cannot be resolved, so a resolvable configuration extending it is declared
// for the purpose and nothing else.
val testPluginClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    extendsFrom(configurations.compileOnly.get())
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(testPluginClasspath)
}

// A repository inside `build/`, so that one test can consume razves the way a real repository does -
// by id, through a resolver - rather than through TestKit's `withPluginClasspath()`, which hands the
// plugin over as a classpath and proves nothing about whether the published artifact works.
//
// Into `build/` rather than `~/.m2`: a test that writes to a developer's local repository leaves
// something behind, and "it worked because your machine already had it" is the failure this test is
// meant to catch.
val testRepository: Directory = layout.buildDirectory.dir("test-repository").get()

publishing {
    repositories {
        maven {
            name = "testRepository"
            url = testRepository.asFile.toURI()
        }
    }
}

tasks.named<Test>("test") {
    dependsOn(
        "publishAllPublicationsToTestRepositoryRepository",
        ":core:publishAllPublicationsToTestRepositoryRepository",
    )
    systemProperty("RAZVES_TEST_REPOSITORY", testRepository.asFile.path)
    systemProperty("RAZVES_VERSION", version.toString())
}

gradlePlugin {
    plugins {
        create("razves") {
            id = "io.github.youndie.razves"
            implementationClass = "io.github.youndie.razves.gradle.RazvesPlugin"
            displayName = "razves"
            description =
                "Attribute a Kotlin/Native binary by origin, package and module, and fail a build when it grows."
        }
    }
}
