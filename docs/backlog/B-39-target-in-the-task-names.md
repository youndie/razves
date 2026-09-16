---
id: B-39
title: "A module with two native targets must configure: the target belongs in the task names"
status: done
priority: P1
size: S
stage: stage-2-gradle
epic: feature-size-report
---

# B-39 — A module with two native targets must configure

```
> Cannot add task 'sizeReportDebugExecutable' as a task with that name already exists.
```

**Found by a consumer, not by a test** — `kafka-native-spike` B-04, where the workaround was to make
the `macosArm64` target opt-in and apply razves only when it is off. Every task razves registered was
named after `Executable.name` alone (`sizeReportDebugExecutable`, `sizeBudgetCheckReleaseExecutable`),
and `Executable.name` is the build type and the output kind: it is unique within one target and
identical across targets. A module with `linuxX64` and `macosArm64` therefore asked Gradle for the
same four task names twice and failed at configuration time, before anything could run.
[`RazvesPlugin`](../../gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/RazvesPlugin.kt)'s
own KDoc said `sizeReport<Target><BuildType>`, so the documentation had been right about it and the
code had never been.

- **The decision and its reason.** The name is `<prefix><Target><Binary>` —
  `sizeReportLinuxX64DebugExecutable` — because that pair is what identifies a binary in the Kotlin
  Gradle Plugin itself, and it is unique by construction, including for a module that declares two
  named executables (`executable("cli")`) in one target. Dropping the output kind to get the shorter
  `sizeReportLinuxX64Release` would reintroduce the same class of collision for exactly that case.
- **The report and baseline paths carry the target too, and that half mattered more.** Had only the
  task names been fixed, both targets would have written `build/reports/razves/debugExecutable.json`
  and the committed `razves/debugExecutable.json` — the second overwriting the first, a green build
  whose baseline describes whichever target happened to run last. A configuration failure is loud; a
  baseline that quietly describes the wrong binary is not. The files are now
  `build/reports/razves/<target>/<binary>.json` and `razves/<target>/<binary>.json`.
- **No compatibility alias for the old single-target names.** It was considered, because a rename is
  breaking for anyone who scripts the tasks, and it was rejected: an alias is only definable while a
  module has exactly one target, and registration happens as each binary appears — so razves would
  have to register `sizeReportDebugExecutable` for the first target and withdraw it when a second
  arrived, which Gradle does not allow. A name that exists or not depending on how many targets a
  module has is worse than a rename. The migration is loud in both places: an unknown task name
  fails at once, and a baseline at the old path is reported as no baseline, naming the task that
  writes one.
- **The fix is not complete without the disabled-target half.** A second target is, in practice, a
  target the host cannot link: the Kotlin Gradle Plugin disables that target's link task, so its
  output is never produced and a report wired to it fails with "Input file does not exist" — in
  `check`, on every machine that is not a mac. razves now follows `linkTaskProvider.get().enabled`,
  so the four tasks skip exactly when the link does. Without this, the rename alone would not have
  let `kafka-native-spike` drop its workaround; it would only have changed which error it got.
- Deliberately **not** covered: linking two targets in one test run. No host can, and the defect is
  a configuration-time one — declaring the second target is the whole of the reproduction.

- AC: a module with `linuxX64` and `macosArm64` executables configures, and each target has its own
  `sizeReport`, `sizeBudgetCheck`, `sizeBaselineWrite` and `sizeDiff`. **Verified by
  `SizeReportTaskTest.twoNativeTargetsInOneModuleEachGetTheirOwnTasks`, which reproduces the message
  above when the naming is reverted.**
- AC: each binary's report and committed baseline is its own file. **Verified by
  `SizeReportTaskTest.eachTargetWritesItsOwnReportAndItsOwnBaseline`, which fails with four paths
  where there should be eight when only the naming is fixed.**
- AC: `check` passes on a host that can link one of the two targets, and the other target's gate is
  `SKIPPED`. **Verified by `SizeReportTaskTest.aTargetThisHostCannotLinkIsSkippedRatherThanFailingCheck`.**
- Anchors: `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/NativeBinaries.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/RazvesPlugin.kt`,
  `gradle-plugin/src/test/kotlin/io/github/youndie/razves/gradle/SizeReportTaskTest.kt`
