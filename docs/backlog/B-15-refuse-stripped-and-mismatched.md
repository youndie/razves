---
id: B-15
title: "Refuse a stripped binary and a klib set for the wrong target"
status: done
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
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/read/Targets.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/report/Attribution.kt`,
  `core/src/commonTest/kotlin/io/github/youndie/razves/report/RefusalsTest.kt`

**Done, and the target half needed a third state.** "Refuse a mismatch" assumes razves can always
name the binary's target. It cannot, and pretending otherwise turns a check into a veto on things it
does not understand.

* **ELF says the CPU and nothing about the operating system.** An x86-64 ELF file is `linux_x64` or
  `android_x64` and Kotlin/Native emits both. razves names both rather than guessing one, so an
  Android klib set is not a contradiction with a Linux-looking binary.
* **Mach-O needs two halves**: the CPU from the header and the platform from `LC_BUILD_VERSION`.
  Measured on the real subject and on the compiled fixture: `cputype=0x0100000C`, `platform=1` -
  exactly `macos_arm64`.
* **Neither is always available.** A Mach-O without `LC_BUILD_VERSION`, an architecture razves does
  not know, a metadata-only klib with no `native_targets`: in every one of those the answer is the
  empty set and **nothing is refused**. A check that cannot identify its subject must not veto it.

**The stripped refusal is at the report, not at the reader.** Section-level reconciliation needs no
symbols and is a legitimate thing to want from a stripped artifact, so `Attribution.of` stays open
and `Attribution.report` is what refuses - with a message that says to point at the link output,
because a refusal that does not say what to do instead is only an obstacle.
