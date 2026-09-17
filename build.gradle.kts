plugins {
    // Declared here with `apply false` so that a module can name them by id without repeating a
    // version. Without this the first module to say `id("io.github.youndie.sborka.kmp")` fails with
    // "plugin dependency must include a version number for this source" — the plugin is resolvable,
    // but nothing has told the build which release of it.
    alias(wip.plugins.kotlinMultiplatform) apply false
    alias(wip.plugins.kotlinSerialization) apply false
    alias(libs.plugins.sborkaKmp) apply false
    alias(libs.plugins.sborkaLint) apply false
    alias(libs.plugins.sborkaPublish) apply false
}
