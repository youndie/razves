---
id: B-27
title: "razves diff in the CLI, against a report the plugin wrote"
status: open
priority: P2
size: S
stage: stage-2-gradle
epic: feature-size-diff
blocked_by: [B-13]
---

# B-27 — razves diff in the CLI, against a report the plugin wrote

```
razves diff <before.json> <after.json> [--rows N]
```

**Found while publishing the repository, not while building it.** [B-11](B-11-cli.md) named the
command in its own title and deferred it to [B-13](B-13-baseline-and-diff.md); B-13 built the
subtraction and wired it to Gradle, and its acceptance criteria said nothing about a CLI. So both
items closed, [services/cli](../services/cli.md) went on promising `razves diff`, and the command
was in no one's scope. The machinery is entirely built - [`DiffDocument.of`](../../core/src/commonMain/kotlin/io/github/youndie/razves/report/Diff.kt)
and [`TextDiff`](../../core/src/commonMain/kotlin/io/github/youndie/razves/report/TextDiff.kt) take
two report documents and render rows - and what is missing is the subcommand that reads two files
and calls them.

- **The decision and its reason.** The CLI takes two *reports*, not two binaries. A report is the
  format the plugin already writes as a baseline, so a CI job can diff a local build against a
  committed baseline without a Gradle daemon; and reading binaries instead would quietly re-measure
  them, which is how a diff ends up comparing a recorded symbol size against an address-derived one.
  `DiffDocument.of` refuses that pair, and the CLI should surface the refusal rather than prevent it.
- Does **not** cover: writing a baseline from the CLI. What writes a baseline is the build that
  produced the binary, and a baseline written by hand on a laptop is the thing the gate's missing-
  baseline message already refuses.

- AC: `razves diff a.json b.json` prints the same table as the Gradle task, sorted by how far each
  row moved.
- AC: two reports measured by different size algorithms are refused, with the reason, and not
  subtracted.
- AC: a malformed or non-report JSON file is refused by name, not by a stack trace.
- Anchors: `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/`
