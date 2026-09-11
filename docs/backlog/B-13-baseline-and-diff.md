---
id: B-13
title: "A committed baseline and a row-level diff"
status: done
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

- AC: the reconciliation deltas - the five terms that sum to the file size - sum to the change in it.
  A constructor-level identity would be wrong here: the section and package deltas sum to their own
  levels, not to the file.
- AC: running the diff leaves the baseline file byte-identical.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/Diff.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/report/TextDiff.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeDiffTask.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeBaselineWriteTask.kt`

**Done, and the output is the argument.** Measured on the plugin's own test project after adding one
function in one new package:

```
razves subject.kexe: +528 (+0.0%)
  BY PACKAGE
    subject.extra    +110   new
    subject           +16   202 -> 218
  WHERE THE FILE WENT
    metadata         +333   7,552,521 -> 7,552,854
```

A gate that printed only `+528` would tell the reader nothing; these four lines tell them that a new
package arrived, that most of the growth is the symbol table rather than code, and where to look.

**Rows that did not move are not rows.** A diff answers "what moved", and a row that did not move is
not an answer to it however large it is - so a diff of a binary against itself is empty rather than a
page of zeroes. Rows are sorted by the *absolute* delta, so a large fall outranks a small rise.

**`inBefore` and `inAfter` are carried rather than inferred from zeroes.** A row can legitimately be
present and empty, and "appeared" is a different fact from "grew from nothing" - the report prints
`new` and `gone` rather than a rise from zero.

**The baseline task is not in `check`**, and the plugin test asserts it by reading `check --dry-run`.
