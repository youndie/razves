---
id: B-10
title: "Render the report as text and as JSON, coverage next to every conclusion"
status: open
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

- AC: no renderer can omit `unattributed` — it is a field of the model, not a computed leftover.
- AC: the JSON of a report round-trips: deserialise, re-serialise, byte-identical.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/`,
  `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/render/`
