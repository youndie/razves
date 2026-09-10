---
id: B-14
title: "The budget gate, and a failure message that names the rows that moved"
status: open
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

- AC: over budget → build fails with total, budget, overage, and the largest rows.
- AC: `deltaPerChange` configured with no baseline → build fails naming `sizeBaselineWrite`.
- AC: a breach leaves the baseline unchanged on disk.
- AC: the skip property produces a log line naming itself.
- Anchors: `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeBudgetCheckTask.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/BinarySizeExtension.kt`
