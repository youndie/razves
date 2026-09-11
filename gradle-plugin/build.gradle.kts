plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm")
    id("io.github.youndie.sborka.jvm")
    id("io.github.youndie.sborka.lint")
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
