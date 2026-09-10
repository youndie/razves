---
id: feature-size-diff
title: Size diff — what moved, and which rows moved it
type: feature
status: draft
owner: unassigned
involved_services:
  - core
  - gradle-plugin
  - cli
client_entries: []
api: []
tags: [diff, baseline, flags]
---

# Size diff — what moved, and which rows moved it

> `status: draft` — nothing below is implemented.

## 1. Overview

A single report answers "what is in this binary". A diff answers the two questions people actually
open a size tool for: *what did my change cost*, and *what did that flag give me*. Both are the same
operation — attribute two binaries, subtract row by row — and both are what makes
[feature-size-budget-gate](feature-size-budget-gate.md) usable instead of merely correct.

The output is a row-level delta: not "+1.4 MB", but "`io.ktor.client` +180 KB, `openssl` +1.2 MB,
`.eh_frame` +31 KB". [Research risk 5](../research/research-architecture.md) is the reason this is
early work rather than a nicety — a gate that reports only a total gets switched off the first time
a dependency bump trips it, because a total gives the reader nothing to decide with.

The same mechanism produces the flag table the brief wants: build twice, diff, and the row-level
delta *is* the answer to "what does `-Xbinary=smallBinary=true` give". Note the ceiling that
[research §1.6](../research/research-architecture.md) establishes — `smallBinary` is `-Oz` over the
LLVM compilation of Kotlin code, and Kotlin is 36–40% of the attributed bytes of the measured
subjects, so the flag's reach is bounded by that share.

## 2. Business rules

* **A diff is between two reports, never between two files directly.** Both sides are attributed
  first, then subtracted, so the two identities of
  [feature-size-report](feature-size-report.md) hold on each side before anything is compared.
* **Rows that exist on one side only are shown, not dropped.** A package that appeared and a
  package that vanished are the two most interesting rows in any diff.
* **The baseline is a committed file, not a previous build.** A clean CI runner has no previous
  build; a developer machine has several, from different branches.
* **The baseline is rewritten by an explicit task and never as a side effect of a check.** A gate
  that updates its own baseline passes forever.
* **A diff states the algorithm on both sides and refuses to mix them.** An ELF report and a Mach-O
  report are not comparable at byte precision ([research §1.4](../research/research-architecture.md)),
  so comparing them fails rather than producing a plausible number.
* **Measurements carry their date and their toolchain version.** A flag comparison is a measurement
  of one compiler on one day, not a constant.

## 3. Flow

1. Attribute both binaries (the flow of [feature-size-report](feature-size-report.md)).
2. Refuse if the two reports were produced by different size algorithms, or name different targets.
3. Join the rows of each level by key — section name, origin bucket, package, module.
4. Emit rows sorted by absolute delta, largest first, with the appearing and disappearing rows kept.
5. For the gate, hand the joined rows to [feature-size-budget-gate](feature-size-budget-gate.md).

## 4. Code anchors

| Module | Code |
|---|---|
| core | `core/src/commonMain/kotlin/io/github/youndie/razves/report/Diff.kt` |
| core | `core/src/commonMain/kotlin/io/github/youndie/razves/report/Baseline.kt` — the serialised report format |
| gradle-plugin | `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeBaselineTask.kt` — writes the baseline |
| cli | `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/DiffCommand.kt` |

## 5. Scenarios (BDD / test cases)

### Scenario: a diff names the rows that moved — *target*
* **Given:** two attributed reports of the same target that differ by one added dependency.
* **When:** razves diffs them.
* **Then:** the output lists the changed rows sorted by absolute delta, and the sum of the row
  deltas equals the difference in file size.

### Scenario: an added package appears as a row — *target*
* **Given:** a baseline with no `io.ktor.client` rows and a current report that has them.
* **When:** razves diffs them.
* **Then:** the package appears with its full size as the delta, marked as new rather than omitted.

### Scenario: a removed package appears as a row — *target*
* **Given:** the reverse of the above.
* **Then:** the package appears with a negative delta, marked as gone.

### Scenario: comparing across size algorithms is refused — *target*
* **Given:** a baseline produced from an ELF binary and a current report produced from a Mach-O one.
* **When:** razves diffs them.
* **Then:** it fails, naming both algorithms and both targets, rather than subtracting.

### Scenario: the baseline is not written by the check — *target*
* **Given:** a project whose current binary is larger than its committed baseline.
* **When:** the check task runs.
* **Then:** the baseline file on disk is byte-identical to what it was before the run.

### Scenario: a flag comparison reports rows, not a headline — *target*
* **Given:** the same sources linked twice, once with `-Xbinary=smallBinary=true`.
* **When:** razves diffs the two binaries.
* **Then:** the output attributes the saving to rows, and records the Kotlin compiler version and
  the date alongside the numbers.

## 6. Out of scope

* Storing history. razves compares two points; a series belongs in whatever already collects build
  metrics.
* Attributing a delta to a commit. The diff says which rows moved; git says who moved them.
* Predicting the effect of a flag without building. There is no model, only measurement.

## 7. Quirks

* **A `-Oz` diff can show a row growing.** Optimising for size changes inlining decisions, so bytes
  move between functions as well as disappearing. Row deltas are not each individually a saving;
  only the total is.
* **The symbol table moves with everything else.** Adding code adds symbol names, so a file-size
  delta always contains a `.strtab` component that no package row explains. It has its own row for
  that reason.
