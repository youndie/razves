---
id: B-14
title: "The budget gate, and a failure message that names the rows that moved"
status: done
priority: P0
size: M
stage: stage-2-gradle
epic: feature-size-budget-gate
blocked_by: [B-13]
---

# B-14 — The budget gate, and a failure message that names the rows that moved

```kotlin
binarySize { budget = 50.MiB; deltaPerChange = 3.percent }
```

This is the item that sells the tool. A report is interesting; a red build is installed.

- **The decision and its reason.** The failure message names the **rows** that moved, not the
  total. [Research risk 5](../research/research-architecture.md): a 3% budget is tripped by a Ktor
  patch release as easily as by a mistake, and "total grew 4.1%" gives the reader nothing to decide
  with, so the gate gets commented out. "`io.ktor.client` +180 KB, `openssl` +1.2 MB" turns a red
  build into a decision. This is why [B-13](B-13-baseline-and-diff.md) blocks it rather than
  following it.
- **`deltaPerChange` with no baseline fails loudly**, naming the task that creates one. Passing
  vacuously is how a gate ends up green for a year while measuring nothing.
- **`sizeBaselineWrite` is not in `check`.** Anything that both verifies and rewrites its own
  reference passes forever.
- **The skip property logs that it skipped.** A silent bypass becomes the repository's default
  state within a quarter.
- Does **not** cover: failing on anything but size.

- AC: over budget -> build fails with total, budget, overage, and the largest rows.
- AC: `deltaPerChange` configured with no baseline -> build fails naming
  `sizeBaselineWrite<Binary>`.
- AC: a breach leaves the baseline unchanged on disk.
- AC: the skip property produces a log line naming itself.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/Budget.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeBudgetCheckTask.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/BinarySizeExtension.kt`

**Done. The comparison is in `core` and only the wiring is in the plugin**, so the thing worth
arguing about - what the message says - is testable without a Gradle daemon, and the six TestKit
tests check that the wiring reaches it.

**A ceiling can be breached on the very first build**, when there is no baseline to diff against.
"You are 4 MB over" with no table is the same dead end as a total-only diff, so that failure prints
the origin split and the largest packages instead.

**The message had a defect that its own test found.** The first version read:

```
growth:  0.0%, and 0.0% is allowed
```

A 0.005% rise against a 0% allowance rounds both sides to the same figure and the sentence stops
making sense. The byte delta needs no rounding at all and is now printed beside the share, and a
share that would round to zero without being zero gets four more places.

**`matching` rather than `named` for `check`.** A project without a lifecycle plugin has no `check`
task, and `named` on a missing one fails the configuration of every build that applies razves to a
module that happens not to have one.
