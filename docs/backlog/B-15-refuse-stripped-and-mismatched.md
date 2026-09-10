---
id: B-15
title: "Refuse a stripped binary and a klib set for the wrong target"
status: open
priority: P1
size: S
stage: stage-1-attribution
epic: feature-size-report
---

# B-15 — Refuse a stripped binary and a klib set for the wrong target

Two inputs produce a report that looks fine and means nothing.

**A stripped binary.** `.symtab`+`.strtab` is 19–21% of the file in all four measured subjects
([research §1.2](../research/research-architecture.md)), so it is the first thing a size-conscious
build deletes — and razves reads exactly that. Without it every byte is unattributed, which is not
a finding about the binary.

**Klibs for the wrong target.** Each manifest carries `native_targets`
([research §1.5](../research/research-architecture.md)). A `linuxX64` binary attributed against
`macosArm64` klibs produces plausible, wrong module rows.

- **The decision and its reason.** Both are refusals with a named cause, not warnings. A warning on
  a report that is already printed is read after the reader has believed the numbers.
- Does **not** cover: analysing a stripped binary at section granularity. Possible, and a separate
  decision — the reconciliation level does not need symbols. Not now.

- AC: a stripped binary fails with a message pointing at the link output.
- AC: a target mismatch fails naming the binary's target and the klibs'.
- AC: neither case emits a report.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/read/`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/klib/KlibManifest.kt`
