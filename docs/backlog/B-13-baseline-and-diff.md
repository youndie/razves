---
id: B-13
title: "A committed baseline and a row-level diff"
status: open
priority: P1
size: M
stage: stage-2-gradle
epic: feature-size-diff
---

# B-13 — A committed baseline and a row-level diff

`deltaPerChange` needs something to be a delta from, and "the previous build" is not it: a clean CI
runner has no previous build and a developer machine has several, from different branches.

- **The decision and its reason.** A committed baseline file — the core's serialised report — plus
  a diff that joins rows by key and sorts by absolute delta. Same shape as an API-compatibility
  dump: a checked-in file, a check that compares, a separate task that rewrites it deliberately.
- **Rows that exist on one side only are shown, not dropped.** A package that appeared and a
  package that vanished are the two most interesting rows in any diff.
- **A diff across size algorithms is refused.** ELF `st_size` and Mach-O address deltas are not
  comparable at byte precision ([research §1.4](../research/research-architecture.md)), so the diff
  fails naming both rather than subtracting.
- The price, stated: two branches that both move the number conflict in one file. That is the known
  cost of every baseline file, and cheaper than a gate that cannot fire on a fresh runner.
- Does **not** cover: history, series, or attributing a delta to a commit.

- AC: the sum of the row deltas equals the difference in file size.
- AC: running the check leaves the baseline file byte-identical.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/Diff.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeBaselineTask.kt`
