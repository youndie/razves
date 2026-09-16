---
id: feature-size-budget-gate
title: Size budget — a red build when the binary grows
type: feature
status: active
owner: unassigned
involved_services:
  - core
  - gradle-plugin
client_entries: []
api: []
tags: [gradle, gate, ci]
---

# Size budget — a red build when the binary grows

## 1. Overview

The report is what makes razves interesting; the gate is what makes it get installed. Android has
had apk-size checks for a decade and Kotlin/Native has nothing equivalent — a targeted search found
no size-budget plugin for it ([research §1.7](../research/research-architecture.md)).

Two rules, both optional, both configured in one block:

```kotlin
binarySize {
    budget = 25.MiB            // an absolute ceiling, on the binary that ships
    deltaPerChange = 3.percent // growth against the committed baseline

    debug {                    // opt in, with a number of its own, or leave debug ungated
        budget = 40.MiB
    }
}
```

A build that breaks either fails, and the failure message names **the rows that moved**, not just
the total. That is the whole difference between a gate that survives a year and a gate that is
commented out the first time a Ktor patch release trips it.

## 2. Business rules

* **Both rules are optional and independently configurable.** A repository may want only a ceiling
  (a firmware-style constraint) or only a delta (a "do not let it creep" constraint).
* **A rule belongs to a build type, and the unqualified one is the release binary's.** A debug
  binary is not a larger version of the one that ships but a different order of size — 28,580,560
  bytes against 9,227,448 for the same module at the same commit, 3.1× — and only the release one is
  ever staged into an image. One number covering both would have to clear the debug figure, and a
  ceiling that admits 28.6 MB has stopped watching the 9.2 MB artefact
  ([B-40](../backlog/B-40-budget-per-build-type.md)).
* **Debug binaries are ungated until `debug { }` says otherwise, and nothing is inherited into it.**
  An inherited ceiling would be the release figure, which no debug binary fits, so every repository
  would meet the original defect on its first build.
* **A gate with no rule to apply says so.** "Passed" and "there was nothing to pass" are the same
  green task in a build log, which is how a binary nobody gated gets read for a year as a binary
  under budget. The verdict names the absence and the block that would end it.
* **`deltaPerChange` requires a committed baseline.** Configuring it with no baseline file present
  fails with an instruction to run the baseline task, rather than passing vacuously.
* **The gate reads the unstripped link output**, before any packaging or image step
  ([research risk 1](../research/research-architecture.md)).
* **The failure message names rows.** Total, budget, overage, then the largest row deltas.
* **The gate is skippable, and skipping it is visible.** A property that turns it off must print
  that it did — an invisible bypass becomes the default state of the repository.
* **What the budget is measured on is explicit in the DSL.** File size by default, because that is
  the number that ends up in a ticket, with allocated size available for anyone who wants the more
  stable measure ([research open question 2](../research/research-architecture.md)).
* **One gate per binary, and a binary is a target and a build type.** The tasks and the files they
  read and write carry the target's name, because `debugExecutable` means a different binary under
  every target a module declares.
* **A target this host cannot link takes its gate with it.** The Kotlin Gradle Plugin disables that
  target's link task; razves follows, rather than failing `check` on a binary the host was never
  going to produce.
* **The task is cacheable and its inputs are declared** — the binary, the klibs, the baseline file.
  A gate that reruns on every build is a gate people move to a nightly job.

## 3. Flow

1. `sizeReport` attributes the binary produced by the target's link task.
2. `sizeBudgetCheck` compares against the configured budget and, if configured, against the
   committed baseline via [feature-size-diff](feature-size-diff.md).
3. On a breach the task fails with total, limit, overage and the top moved rows.
4. `sizeBaselineWrite` — a separate task, never run as part of `check` — rewrites the baseline.

## 4. Code anchors

| Module | Code |
|---|---|
| gradle-plugin | `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/RazvesPlugin.kt` — task wiring |
| gradle-plugin | `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/BinarySizeExtension.kt` — the DSL |
| gradle-plugin | `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeRules.kt` — the two rules, per build type |
| gradle-plugin | `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeBudgetCheckTask.kt` |
| gradle-plugin | `gradle-plugin/src/test/kotlin/io/github/youndie/razves/gradle/` — TestKit builds |
| core | `core/src/commonMain/kotlin/io/github/youndie/razves/report/Budget.kt` — the comparison, with no Gradle types |

## 5. Scenarios (BDD / test cases)

### Scenario: a ceiling does not apply to the debug binary
* **Given:** `budget = 1.KiB` and no `debug { }` block.
* **When:** the debug binary's gate runs.
* **Then:** it succeeds, because the ceiling is the release binary's.
* **And:** its verdict says that nothing was checked, and names the block that would change that.
* **Automated:** `SizeReportTaskTest.aCeilingDoesNotApplyToTheDebugBinaryThatNothingShips`,
  `BudgetTest.aBinaryWithNoRuleSaysThatNothingWasCheckedAndHowToGiveItOne`,
  `BudgetTest.aRuleThatPassesDoesNotClaimThereWasNoRule`

### Scenario: the same ceiling fails the release binary
* **Given:** the same `budget = 1.KiB`.
* **When:** `check` runs.
* **Then:** the **release** binary's gate is the one that fails.
* **Automated:** `SizeReportTaskTest.aBinaryOverItsCeilingFailsTheBuildAndNamesWhatIsInIt`

### Scenario: a debug rule is opted into, and stays on debug
* **Given:** `binarySize { budget = 500.MiB; debug { budget = 1.KiB } }`.
* **Then:** the debug gate fails and the release gate does not.
* **And:** a rule written only in `debug { }` leaves the release binary ungated, saying so.
* **Automated:** `SizeReportTaskTest.aDebugCeilingIsOptedIntoAndThenItApplies`,
  `SizeReportTaskTest.aDebugRuleDoesNotLeakOntoTheReleaseBinary`

### Scenario: a binary under budget passes
* **Given:** `budget = 50.MiB` and a 20 MB binary.
* **When:** `sizeBudgetCheck` runs.
* **Then:** the task succeeds and prints the total and the headroom.
* **Automated:** `BudgetTest.aBinaryUnderBudgetPassesAndSaysByHowMuch`,
  `SizeReportTaskTest.aBinaryUnderItsCeilingPassesCheck`

### Scenario: a binary over budget fails, naming rows
* **Given:** `budget = 15.MiB` and a 20 MB binary.
* **When:** `sizeBudgetCheck` runs.
* **Then:** the build fails.
* **And:** the message states the total, the budget and the overage.
* **And:** it lists the largest rows of the report, so the reader can see what to cut — a ceiling can
  be breached on the first build, when there is no baseline to diff against.
* **Automated:** `BudgetTest.aBinaryOverBudgetFailsAndNamesWhatIsInIt`,
  `SizeReportTaskTest.aBinaryOverItsCeilingFailsTheBuildAndNamesWhatIsInIt`

### Scenario: a delta rule with no baseline fails loudly
* **Given:** `deltaPerChange = 3.percent` and no baseline file.
* **When:** `sizeBudgetCheck` runs.
* **Then:** the build fails, naming the baseline task to run.
* **And:** it does **not** pass by treating a missing baseline as zero growth.
* **Automated:** `BudgetTest.aGrowthBudgetWithNoBaselineFailsLoudlyAndNamesTheTask`,
  `SizeReportTaskTest.aGrowthBudgetWithNoBaselineFailsRatherThanPassingQuietly`

### Scenario: growth within the delta passes
* **Given:** a baseline of 20,000,000 B, `deltaPerChange = 3.percent`, and a current binary of
  20,400,000 B (+2.0%).
* **Then:** the task succeeds.
* **Automated:** `BudgetTest.growthWithinTheDeltaPasses`

### Scenario: growth beyond the delta fails, naming the rows that caused it
* **Given:** the same baseline and a current binary of 21,000,000 B (+5.0%).
* **Then:** the build fails.
* **And:** the message lists row deltas largest first, so a reader sees which dependency grew
  rather than only that something did.
* **And:** the growth is printed in bytes as well as a share — a share that rounds to zero without
  being zero gets four more places, because "0.0%, and 0.0% is allowed" is not a sentence.
* **Automated:** `BudgetTest.growthBeyondTheDeltaFailsAndPrintsTheRowsThatCausedIt`,
  `BudgetTest.aGrowthTooSmallForOneDecimalIsStillPrintedAsANumber`,
  `SizeReportTaskTest.growthBeyondTheDeltaFailsAndNamesTheRowsThatCausedIt`

### Scenario: the check never rewrites the baseline
* **Given:** any breach.
* **When:** `sizeBudgetCheck` runs.
* **Then:** the baseline file is unchanged on disk.
* **Automated:** `SizeReportTaskTest.aBreachLeavesTheBaselineAlone`,
  `SizeReportTaskTest.theBaselineTaskIsNotPartOfCheck`

### Scenario: turning the gate off says so
* **Given:** the disable property set.
* **When:** the build runs.
* **Then:** it succeeds and the log carries a line naming the property and the fact that the size
  gate did not run.
* **Automated:** `SizeReportTaskTest.turningTheGateOffSaysSo`

### Scenario: a target this host cannot link does not fail the gate
* **Given:** a module with a `linuxX64` and a `macosArm64` executable, on a Linux host.
* **When:** `check` runs.
* **Then:** the `linuxX64` gate runs, and the `macosArm64` one is `SKIPPED` — as its link task is.
* **And:** it does not fail with "Input file does not exist", which is a gate every mac-less machine
  in the repository would switch off.
* **Automated:** `SizeReportTaskTest.aTargetThisHostCannotLinkIsSkippedRatherThanFailingCheck`

### Scenario: the task is up to date on a second run
* **Given:** a successful `sizeBudgetCheck` and no change to the binary, the klibs or the baseline.
* **When:** it runs again.
* **Then:** Gradle reports it as `UP-TO-DATE`.
* **Automated:** `SizeReportTaskTest.theTaskIsUpToDateOnASecondRunAndCacheable`

## 6. Out of scope

* Failing on anything but size. Not a linter.
* Per-module budgets. The unit is a linked binary; a klib's size is not what ships.
* Uploading anything anywhere. The gate is local to the build.

## 7. Quirks

* **The budget applies to a number that includes the symbol table.** A repository that strips as
  part of packaging is measured on the pre-strip artifact and will see a number 19–21% higher than
  what it ships ([research §1.2](../research/research-architecture.md)). The report says so; the
  budget has to be set with that in mind.
* **A dependency bump can breach the delta without any local change.** That is the gate working,
  not a false positive — but it is why the message must name the rows.
* **Upgrading from a version where `budget` covered every binary silently removes a debug gate.**
  The one thing that makes it not silent is the "nothing was checked" verdict, printed on the first
  build after the upgrade, next to the number it is no longer checking.
