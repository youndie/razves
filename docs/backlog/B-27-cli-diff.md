---
id: B-27
title: "razves diff in the CLI, against a report the plugin wrote"
status: done
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
  row moved. **Verified on the built linuxX64 binary**, diffing razves' own fixture executable
  against the CLI itself: `-2,169,936 (-40.5%)`, with `io.github.youndie +296,048 new` and
  `kotlin.text.regex +247,286 new` among the rows.
- AC: two reports measured by different size algorithms are refused, with the reason, and not
  subtracted. **Verified in `AnalyseDiffTest`.**
- AC: a malformed or non-report JSON file is refused by name, not by a stack trace. **Verified**, and
  the message needed trimming: kotlinx.serialization explains itself to whoever wrote the `Json`
  builder - "use ignoreUnknownKeys" is advice for razves, not for the person holding the file - so
  only its first line is carried.
- Anchors: `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/`

**Two things the item did not ask for, both found by running the binary rather than the tests.**

* **`ReportDocument.formatVersion` had never been read by anything.** It was in the document from the
  first commit, for a baseline written by one release and read by the next, and nothing checked it -
  so a document from a later format would parse field by field until it did not, and the message
  would name a missing field rather than a version. `razves diff` is the first reader of two files
  off a disk, so the check belongs here.
* **Nothing this CLI refuses exited non-zero.** Not a missing file, not a file that is not a report,
  not two reports measured differently - and not clikt's own "missing argument" either, which is how
  it was found: clikt's `main` prints its message and returns normally. Measured on the built binary,
  before and after: every refusal was status 0, and is now 1, with an answer still 0. A tool that
  cannot say it refused is a tool a script reads as having agreed.

**What is not automated here:** the exit codes. A unit test cannot assert on a process it is running
inside, and the check that found this was `./cli.kexe … ; echo $?` against the linked binary. The
mapping is five lines of clikt plumbing in
[`Main.kt`](../../cli/src/commonMain/kotlin/io/github/youndie/razves/cli/Main.kt); what a test does
cover is that each of these paths raises the refusal it should.
