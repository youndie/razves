---
id: B-06
title: "Origin buckets and per-section coverage in the report model"
status: done
priority: P1
size: M
stage: stage-1-attribution
epic: feature-size-report
---

# B-06 — Origin buckets and per-section coverage in the report model

Measured on four release binaries, Kotlin is 36–40% of the attributed bytes and statically linked C
is the majority ([research §1.2](../research/research-architecture.md)). A tool whose main table
covers Kotlin and calls the rest "other" answers a third of the question.

- **The decision and its reason.** The top-level split is by origin — Kotlin, C++ (Kotlin/Native
  runtime), Rust, C, and the sections nobody owns — and only Kotlin subdivides further. The origin
  split is what makes an answer actionable: "OpenSSL is 4 MB" points at `ktor-client-curl`;
  "ktor is 1.3 MB" points at nothing anyone can do.
- **Coverage is a property of every section row.** `.text` attribution is worth ~98%, `.rodata`
  ~41%. A reader who cannot see the difference draws a `.rodata` conclusion with `.text` confidence
  ([research risk 3](../research/research-architecture.md)).
- Does **not** cover: naming the C library. That is [B-09](B-09-c-attribution-by-archive.md).

- AC: the report names the unowned sections individually - `.eh_frame`, `.eh_frame_hdr`,
  `.gcc_except_table`, `.dynsym`, `.dynstr`, `.gnu.hash` - rather than summing them into one
  anonymous remainder. Measured: **22** such sections in the release subject, those six of them
  2,189,115 bytes together, 10.7% of the file.
- AC: every section row carries its attributed percentage. Measured on the same binary: `.text`
  97.6%, `.data.rel.ro` 94.8%, `.data` 97.2%, `.rodata` 40.7%, `.init_array` 20.0%.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/SizeReport.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/report/Attribution.kt`

**Done.** The origin split is a third constructor invariant: the rows must sum to what the
reconciliation attributed, and a report whose rows do not cannot be built. Every origin gets a row
even when this binary has nothing in it, because a row that appears and disappears between two
builds is noise in the diff [B-13](B-13-baseline-and-diff.md) will be reading.

**A gap found by building it, and left open deliberately.** The sweep runs over allocated sections
only, so symbols in `.bss` and `.tbss` are dropped and uninitialised state has no owner at all. That
is right while the subject is file size and wrong the moment a budget is set on the allocated size -
[B-21](B-21-attribute-nobits-sections.md), blocked by
[B-20](B-20-decide-the-budget-unit.md), which is the decision that settles it.
