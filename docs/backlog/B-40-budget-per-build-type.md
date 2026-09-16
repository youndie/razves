---
id: B-40
title: "A budget is the release binary's, because the release binary is the one that ships"
status: done
priority: P1
size: S
stage: stage-2-gradle
epic: feature-size-budget-gate
---

# B-40 — A budget is the release binary's

`binarySize.budget` was one value applied to every `Executable`, so a service setting a ceiling for
what it puts in an image was also holding its **debug** binary to it. Those are not the same size by
a small margin — measured on `keel`, same module, same commit, 2026-09-16:

| binary | file size |
|---|---|
| `debugExecutable` | 28,580,560 |
| `releaseExecutable` | **9,227,448** |

3.1×, and only the second one is ever staged into an image. So the only ceiling that lets the build
through is one chosen for debug — and a gate that admits 28.6 MB has stopped watching the 9.2 MB
artefact altogether: what ships could triple before the build went red. Found by a consumer, which
worked around it by disabling `sizeBudgetCheckDebugExecutable` rather than raising the ceiling to
40 MiB and losing the gate on the thing it cared about.

- **The decision and its reason.** `budget` and `deltaPerChange` now apply to **release**
  executables, and debug binaries are ungated until `binarySize { debug { } }` says otherwise. The
  number a repository writes without thinking about it is the number for the artefact it ships,
  because that is the one it meant; the other build type is a different order of size and therefore
  a different number, not a looser reading of the same one. Nothing is inherited into `debug { }` —
  an inherited ceiling would be the release figure, which no debug binary has ever fitted, so every
  repository would meet the old defect on its first build and conclude the block does not work.
- **A behaviour change, and it is a silent one unless something says so.** Anyone who was gating
  debug on purpose loses that gate, and a gate that stops checking looks exactly like a gate that
  passes. So a `sizeBudgetCheck` with no rule to apply now prints, at `lifecycle` and into its
  verdict file, that nothing was checked and which block would change that. That sentence is the
  whole of the migration: it fires on the first build after the upgrade, in the log, next to the
  number it is not checking.
- **The alternative that was rejected: a per-binary override over an unchanged default** —
  `budget = 40.MiB; release { budget = 25.MiB }`. It keeps every existing build's behaviour, and it
  keeps the trap as the default: the repository that never reads this item still holds its release
  binary to a debug-sized ceiling, which is the state the item is about. A default that is wrong for
  almost every consumer is not made right by an opt-out.
- **The alternative that was rejected: documenting it.** The `budget` KDoc already warns about the
  strip-afterwards trap and it reads well, so the shape was available. It was refused because the
  two traps are not alike: a stripped artefact is 19–21% smaller than what the gate measures, which
  a person can hold in their head while choosing a number, and a debug binary is 310% of it, which
  means there is no single number to choose.
- Deliberately **not** covered: a per-target rule. `linuxX64` and `linuxArm64` release binaries of
  the same module are within a few percent of each other, so one number covers them; the build types
  are the split that was measured to matter. [B-39](B-39-target-in-the-task-names.md) gave every
  target its own task, so a per-target rule would have somewhere to go if a measurement ever asks
  for one.

- AC: with `binarySize { budget = 1.KiB }`, `sizeBudgetCheck<Target>ReleaseExecutable` fails and
  `sizeBudgetCheck<Target>DebugExecutable` passes. **Verified by
  `SizeReportTaskTest.aCeilingDoesNotApplyToTheDebugBinaryThatNothingShips` and
  `SizeReportTaskTest.aBinaryOverItsCeilingFailsTheBuildAndNamesWhatIsInIt`.**
- AC: with `binarySize { debug { budget = 1.KiB } }` the debug gate fails, and a rule written there
  cannot fail the release gate. **Verified by
  `SizeReportTaskTest.aDebugCeilingIsOptedIntoAndThenItApplies` and
  `SizeReportTaskTest.aDebugRuleDoesNotLeakOntoTheReleaseBinary`.**
- AC: a gate with no rule to apply says so, and names the block that would give it one, rather than
  reporting the same success as a rule that passed. **Verified by
  `BudgetTest.aBinaryWithNoRuleSaysThatNothingWasCheckedAndHowToGiveItOne`,
  `BudgetTest.aRuleThatPassesDoesNotClaimThereWasNoRule`.**
- Anchors: `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeRules.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/BinarySizeExtension.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/NativeBinaries.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/report/Budget.kt`
