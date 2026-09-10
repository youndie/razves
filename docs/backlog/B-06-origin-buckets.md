---
id: B-06
title: "Origin buckets and per-section coverage in the report model"
status: open
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

- AC: the report names the unowned sections individually — `.eh_frame`, `.eh_frame_hdr`,
  `.gcc_except_table`, `.dynsym`, `.dynstr`, `.gnu.hash` — rather than summing them into one
  anonymous remainder.
- AC: every section row carries its attributed percentage.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/attribute/Origin.kt`
