---
id: B-12
title: "Gradle plugin: sizeReport, wired to the link tasks, configuration-cache clean"
status: open
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
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeReportTask.kt`
