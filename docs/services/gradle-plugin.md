---
id: gradle-plugin
title: razves Gradle plugin — sizeReport and the budget gate
type: service
repo_url: https://github.com/youndie/razves
module: gradle-plugin
tech_stack: [Kotlin, Gradle]
owner: unassigned
depends_on:
  - core
publishes:
  - io.github.youndie.razves (plugin marker)
  - io.github.youndie.razves:gradle-plugin
---

# razves Gradle plugin

## 1. Responsibility

Supplying the core with the three things only the build knows — **which** binary was linked,
**which** klibs took part, and **which** static archives came with the cinterop dependencies — and
turning the resulting report into a build outcome.

Tasks:

| Task | What it does |
|---|---|
| `sizeReport<Target><Binary>` | attributes that binary's link output and writes the report; one per executable |
| `sizeBudgetCheck<Target><Binary>` | fails the build on a breached budget or delta; wired into `check` |
| `sizeBaselineWrite<Target><Binary>` | rewrites the committed baseline in `razves/<target>/`; deliberately **not** wired into `check` |
| `sizeDiff<Target><Binary>` | what moved since the committed baseline |

`<Target>` is the Kotlin/Native target and `<Binary>` is the `Executable`'s own name, which is the
build type and the output kind: `sizeReportLinuxX64DebugExecutable`,
`sizeBudgetCheckMacosArm64ReleaseExecutable`. Both halves are needed — the second is what a binary
is called under *every* target, so a module with two of them had two tasks of each name and did not
configure at all ([B-39](../backlog/B-39-target-in-the-task-names.md)).

What it deliberately does **not** do: any attribution of its own. Everything interesting lives in
[core](core.md), so it is testable without a Gradle daemon.

## 2. API contracts

* **DSL:** the `binarySize { }` extension —
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/BinarySizeExtension.kt`. Contract
  and rules in [feature-size-budget-gate](../features/feature-size-budget-gate.md).
* **Baseline file:** the core's serialised report; the plugin owns only its location.
* **Plugin id:** `io.github.youndie.razves`, applied per project, wiring itself to every
  `executable` binary of every Kotlin/Native target present.

## 2a. Code anchors

| File | What is there |
|---|---|
| `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/RazvesPlugin.kt` | the extension, its defaults, and the guard that keeps every Kotlin type out of the plugin class |
| `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/NativeBinaries.kt` | task registration and naming, wiring to the link tasks, and the klib set they were linked against |
| `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/BinarySizeExtension.kt` | the DSL and its units |
| `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeRules.kt` | the two rules, and why there is a set of them per build type |
| `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeReportTask.kt` | inputs, outputs, cacheability |
| `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeBudgetCheckTask.kt` | the comparison and the failure message |
| `gradle-plugin/src/test/kotlin/io/github/youndie/razves/gradle/` | TestKit builds |

## 3. How it is built

**`KotlinNativeLink.outputFile` carries no producer.** It is a plain `Provider<File>`, so setting
the task's input from it alone schedules the report before the link and fails with "Input file does
not exist". The provider supplies the path; an explicit `dependsOn` supplies the order. A hard-coded
path would supply neither correctly, which is how a report ends up describing yesterday's binary.

**The Kotlin Gradle Plugin is `compileOnly`, and TestKit needs it back.** A published plugin must not
bundle KGP — the build applying razves has its own, at its own version. But TestKit hands the plugin
under test only its runtime classpath, and Gradle resolves a plugin class's method signatures while
decorating it, so a `register(…, Executable)` signature makes instantiation fail with "Could not
generate a decorated class" — a message that says nothing about a missing dependency.
`compileOnly` is not resolvable, so `gradle-plugin/build.gradle.kts` declares a resolvable
configuration extending it for that one purpose.

**The klib set comes from the compilation, resolved lazily.** The plugin needs the artifacts on the
native compilation's classpath, and it must ask for them as a lazily-resolved `FileCollection` — a
plugin that resolves a configuration while the build is being configured breaks the configuration
cache and forces a resolution nobody asked for. What the plugin knows and a bare CLI does not is
*which* klibs; the package-to-module mapping itself lives inside each klib
([research §1.5](../research/research-architecture.md)) and is read by the core.

**A target whose link task is disabled takes its four tasks with it.** A module with a `macosArm64`
target on a Linux machine has a link task the Kotlin Gradle Plugin disabled, so the binary is never
produced; a gate wired to it and sitting in `check` would fail with Gradle's own "Input file does
not exist" on every machine that is not a mac. razves reads `linkTaskProvider.get().enabled` rather
than deciding for itself which targets a host supports, so a hand-disabled link task behaves the
same way.

**Wiring happens at plugin-application time, not in `afterEvaluate`.** Targets are declared after
the plugin block, so the registration has to be driven by the target container's `all { }` rather
than by a single pass once evaluation is done.

**A rule is per build type, and the unqualified one is the release binary's.** `binarySize.budget`
used to apply to every `Executable`, which made it the debug binary's number by force: the debug and
release binaries of one module measured 28,580,560 and 9,227,448 bytes at the same commit, so the
only ceiling both fit is one three times what ships. razves picks the rules by
`Executable.buildType` and leaves `debug { }` empty unless somebody fills it
([B-40](../backlog/B-40-budget-per-build-type.md)). Nothing is inherited into that block, because an
inherited ceiling would be the release number and no debug binary has ever fitted it.

**A gate with no rule prints that it checked nothing.** Otherwise it is the same green task as a
gate whose rule passed, and that is how a binary nobody gated is read for a year as a binary under
budget. The sentence is built in `core`, which knows nothing about Gradle, out of a hint string the
plugin supplies — the plugin is the half that knows whether `debug { }` or the outer block is the
one to name.

**The gate is a separate task from the report.** The report is useful on its own and should not
fail a build; the gate is what fails it. Splitting them also keeps the report's output an input of
the gate, which is what makes the gate cacheable.

**The baseline task is not in `check`.** Anything that both verifies and rewrites its own reference
passes forever. `sborka` makes the same split for `mutationTest`, for the same reason.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [core](core.md) | all attribution |
| External | Kotlin Gradle Plugin | the native targets, their link tasks and their compilations |

## 5. Infrastructure and deploy

Published to the Gradle Plugin Portal and Maven Central. Version line and publishing come from
`sborka`'s conventions, like every other repository in this portfolio.

## 6. Local setup

```bash
./gradlew :gradle-plugin:test
```

## 7. Configuration

| Key | Description | Required |
|---|---|---|
| `binarySize.budget` | absolute ceiling, on the **release** binary | no |
| `binarySize.deltaPerChange` | growth against the committed baseline, on the **release** binary | no |
| `binarySize.debug { }` | the same two rules for the debug binaries; absent means they are ungated | no |
| `binarySize.measure` | `Measure.FILE_SIZE` (default) or `Measure.ALLOCATED` | no |
| `binarySize.baseline` | baseline file location | no |
| `razves.skip` | Gradle property; disables the gate and logs that it did | no |

`50.MiB`, `512.KiB` and `3.percent` are member extensions of the extension, so they resolve inside
the `binarySize { }` block and nowhere else.

Do not copy the full list here as it grows — the extension class is the source of truth.

## 8. Quirks

* **Two of the plugin's four tasks are useless without something committed.** `sizeDiff` and the
  delta half of `sizeBudgetCheck` need a baseline file in the repository. Both fail with the name
  of the task that creates one rather than passing vacuously.
* **The gate measures the link output, not what ships.** A project that strips or packs afterwards
  is being measured on a number 19–21% larger than its artifact
  ([research §1.2](../research/research-architecture.md)).
* **A rename of the tasks renames the baseline files too.** Baselines written before the target was
  part of the path sit in `razves/<binary>.json` and are no longer read; the gate fails with the name
  of the task that writes the new one, which is a loud migration rather than a silent one.
* **`razves.skip` must log.** A silent bypass property becomes the repository's default state
  within a quarter and nobody remembers it is set.
* **Upgrading past [B-40](../backlog/B-40-budget-per-build-type.md) removes a debug gate that was
  there before.** A repository that had set `budget` for its debug binary keeps the number and loses
  the binary it was checking; the gate's own "nothing was checked" line is the notice, and moving the
  number into `debug { }` is the migration.
