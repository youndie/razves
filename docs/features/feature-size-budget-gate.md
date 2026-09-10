---
id: feature-size-budget-gate
title: Size budget — a red build when the binary grows
type: feature
status: draft
owner: unassigned
involved_services:
  - core
  - gradle-plugin
client_entries: []
api: []
tags: [gradle, gate, ci]
---

# Size budget — a red build when the binary grows

> `status: draft` — nothing below is implemented.

## 1. Overview

The report is what makes razves interesting; the gate is what makes it get installed. Android has
had apk-size checks for a decade and Kotlin/Native has nothing equivalent — a targeted search found
no size-budget plugin for it ([research §1.7](../research/research-architecture.md)).

Two rules, both optional, both configured in one block:

```kotlin
binarySize {
    budget = 50.MiB            // an absolute ceiling
    deltaPerChange = 3.percent // growth against the committed baseline
}
```

A build that breaks either fails, and the failure message names **the rows that moved**, not just
the total. That is the whole difference between a gate that survives a year and a gate that is
commented out the first time a Ktor patch release trips it.

## 2. Business rules

* **Both rules are optional and independently configurable.** A repository may want only a ceiling
  (a firmware-style constraint) or only a delta (a "do not let it creep" constraint).
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
| gradle-plugin | `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeBudgetCheckTask.kt` |
| gradle-plugin | `gradle-plugin/src/test/kotlin/io/github/youndie/razves/gradle/` — TestKit builds |
| core | `core/src/commonMain/kotlin/io/github/youndie/razves/report/Budget.kt` — the comparison, with no Gradle types |

## 5. Scenarios (BDD / test cases)

### Scenario: a binary under budget passes — *target*
* **Given:** `budget = 50.MiB` and a 20 MB binary.
* **When:** `sizeBudgetCheck` runs.
* **Then:** the task succeeds and prints the total and the headroom.

### Scenario: a binary over budget fails, naming rows — *target*
* **Given:** `budget = 15.MiB` and a 20 MB binary.
* **When:** `sizeBudgetCheck` runs.
* **Then:** the build fails.
* **And:** the message states the total, the budget and the overage.
* **And:** it lists the largest rows of the report, so the reader can see what to cut.

### Scenario: a delta rule with no baseline fails loudly — *target*
* **Given:** `deltaPerChange = 3.percent` and no baseline file.
* **When:** `sizeBudgetCheck` runs.
* **Then:** the build fails, naming the baseline task to run.
* **And:** it does **not** pass by treating a missing baseline as zero growth.

### Scenario: growth within the delta passes — *target*
* **Given:** a baseline of 20,000,000 B, `deltaPerChange = 3.percent`, and a current binary of
  20,400,000 B (+2.0%).
* **Then:** the task succeeds.

### Scenario: growth beyond the delta fails, naming the rows that caused it — *target*
* **Given:** the same baseline and a current binary of 21,000,000 B (+5.0%).
* **Then:** the build fails.
* **And:** the message lists row deltas largest first, so a reader sees which dependency grew
  rather than only that something did.

### Scenario: the check never rewrites the baseline — *target*
* **Given:** any breach.
* **When:** `sizeBudgetCheck` runs.
* **Then:** the baseline file is unchanged on disk.

### Scenario: turning the gate off says so — *target*
* **Given:** the disable property set.
* **When:** the build runs.
* **Then:** it succeeds and the log carries a line naming the property and the fact that the size
  gate did not run.

### Scenario: the task is up to date on a second run — *target*
* **Given:** a successful `sizeBudgetCheck` and no change to the binary, the klibs or the baseline.
* **When:** it runs again.
* **Then:** Gradle reports it as `UP-TO-DATE`.

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
