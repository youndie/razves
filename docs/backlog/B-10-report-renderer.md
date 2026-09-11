---
id: B-10
title: "Render the report as text and as JSON, coverage next to every conclusion"
status: done
priority: P1
size: M
stage: stage-1-attribution
epic: feature-size-report
---

# B-10 — Render the report as text and as JSON, coverage next to every conclusion

Three levels the reader can stop at: reconciliation, origin, Kotlin detail. Each prints its own
`unattributed` line, because 20.5% of the allocated bytes of a real release binary belong to no
symbol ([research §1.2](../research/research-architecture.md)) and hiding that is how a size tool
loses its credibility.

- **The decision and its reason.** JSON is the core's serialised report model, so the same file is
  the baseline that [B-13](B-13-baseline-and-diff.md) diffs against. One format, two uses — a
  separate "export" format would drift from the model within a release.
- **Coverage is printed next to every section conclusion**, so a `.rodata` row derived from 41% of
  the section does not look like a `.text` row derived from 98%.
- The header states: the size algorithm, whether klibs were supplied, whether the binary carried a
  symbol table, and the toolchain version if it can be read.
- Does **not** cover: HTML, charts, or anything that needs a browser.

- AC: no renderer can omit `unattributed` - it is a field of the model, not a computed leftover.
- AC: the JSON of a report round-trips: deserialise, re-serialise, byte-identical.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/ReportDocument.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/report/TextReport.kt`,
  `core/src/commonTest/kotlin/io/github/youndie/razves/report/ReportRenderingTest.kt`

**Done. The document stores no derived number.** Coverage and percentages are computed by the
renderer, never written to the file: a rounded float in something that has to round-trip byte for
byte is a bug waiting for a locale, and a stored derived value can disagree with the computed one.
A test asserts the words `coverage`, `percent` and `share` do not appear in the JSON at all.

**The header is three lines that change what every number below them means** - which size algorithm
produced them, whether klibs were supplied (so that the absence of module rows is not read as an
absence of modules), and whether package names were truncated. The module table is *absent* rather
than empty when there are no klibs: an empty table reads as an answer.

**Two things the item did not anticipate.** Long names break a fixed column, and an ambiguous module
row lists every module that declares the package - a real one from the release subject runs to 108
characters. Cutting at the right loses the last module, so names are cut from the middle, which loses
the part two long coordinates have in common. And the renderer's rounding caught an inconsistency in
this repository's own documentation: `.rodata` coverage was written as 40.6% in two places and 40.7%
in a third, because some figures came from a debug print that truncated and others from one that
rounded. The tool's own output is now the single source, and all four places say 40.7%.
