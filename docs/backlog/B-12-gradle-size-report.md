---
id: B-12
title: "Gradle plugin: sizeReport, wired to the link tasks, configuration-cache clean"
status: done
priority: P1
size: M
stage: stage-2-gradle
epic: feature-size-report
---

# B-12 — Gradle plugin: `sizeReport`, wired to the link tasks, configuration-cache clean

The plugin supplies the three things only the build knows: which binary was linked, which klibs
took part, and which static archives came with the cinterop dependencies.

- **The decision and its reason.** Register through the target container's `all { }` at plugin
  application, not in `afterEvaluate` — targets are declared after the plugin block, and
  `afterEvaluate` is already too late to see a target added by a convention plugin applied later.
- **The klib set is a lazily-resolved `FileCollection`.** Resolving a configuration during
  configuration breaks the configuration cache, and every repository in this portfolio runs with it
  on.
- **The report task does not fail builds.** That is [B-14](B-14-budget-gate.md)'s job, and keeping
  them separate is what makes the gate's input cacheable.
- Does **not** cover: per-module budgets. The unit is a linked binary; a klib's size is not what
  ships.

- AC: `sizeReport` runs on a project with the configuration cache enabled and the cache is reused
  on a second invocation.
- AC: a second run with no change reports `UP-TO-DATE`.
- Anchors: `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/RazvesPlugin.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeReportTask.kt`,
  `gradle-plugin/src/test/kotlin/io/github/youndie/razves/gradle/SizeReportTaskTest.kt`

**Done, and the link classpath is measurably the point.** The tests build a real Kotlin/Native
project with one dependency and link it. Its module table comes out with **zero ambiguous rows** -
`stdlib`, `org.jetbrains.kotlinx:kotlinx-datetime`, `…-serialization-core`, and
`<no klib declares subject>` for the application's own package, which is the right answer. A
directory sweep of the same kind of tree gave 69 ambiguous rows worth 3.5 MB of 5.1 MB of Kotlin on
`shildik`. Nothing in either report says which one you are reading; only the plugin can know.

**Three things cost time that the item did not predict.**

*`KotlinNativeLink.outputFile` carries no producer.* It is a plain `Provider<File>`, so wiring it
into the task's input alone schedules the report before the link and fails with "Input file does not
exist". The provider supplies the path and an explicit `dependsOn` supplies the order; a hard-coded
path would have supplied neither correctly.

*TestKit hands the plugin only its runtime classpath.* The Kotlin Gradle Plugin is `compileOnly`,
because a published plugin must not bundle one - the build applying razves has its own, at its own
version. Without adding it back for the test the plugin cannot even be instantiated: Gradle resolves
a plugin class's method signatures while decorating it, and `register(…, Executable)` names a KGP
type. The failure reads "Could not generate a decorated class", which says nothing about a missing
dependency. And `compileOnly` is not resolvable, so a resolvable configuration extending it has to be
declared for the purpose.

*A test project is a separate build.* Its Kotlin and dependency versions are pinned in the test
rather than read from this repository's catalog, and the first version of its source used an API that
had moved - which the test caught as a compile error in the subject, not in razves.
