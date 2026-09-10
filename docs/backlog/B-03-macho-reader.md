---
id: B-03
title: "Mach-O reader with address-delta sizing, clamped at the section end"
status: done
priority: P0
size: M
stage: stage-0-readers
epic: feature-size-report
---

# B-03 — Mach-O reader with address-delta sizing, clamped at the section end

`llvm-nm --print-size` on a Mach-O binary warns and returns zero for every symbol: Mach-O's `nlist`
has no size field. Verified on `shildik/core/build/bin/macosArm64/debugTest/test.kexe`
([research §1.4](../research/research-architecture.md)). This is not a detail — it is a second
algorithm, and it is the reason Apple targets cannot be an afterthought.

- **The decision and its reason.** Sort defined symbols by address; a symbol's size is the distance
  to the next one, clamped at the end of the containing section. Measured, that attributes 8,863,076
  of 8,957,547 allocated bytes — 98.9%. Without the clamp the last symbol of each section absorbs
  the gap to the next section.
- **Sections are keyed by `(segment, section)`.** That binary has two sections named `__const`, in
  `__TEXT` and in `__DATA_CONST`. A name-keyed map silently loses one, on Apple targets only.
- Does **not** cover: fat/universal binaries, or making Mach-O and ELF numbers comparable at byte
  precision. They are not — address-delta sizes include the padding after a symbol.

- AC: attributed coverage of `__TEXT,__text` on that fixture is over 90%. Measured 100%: an
  address-delta charges every byte between two symbols to the earlier one.
- AC: both `__const` sections appear in the report with their own sizes.
- AC: the report states that sizes are address-derived.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/read/MachOReader.kt`,
  `core/src/commonTest/kotlin/io/github/youndie/razves/fixture/MachOBuilder.kt`

**Done, and it forced a change to the reconciliation.** Mach-O cannot be reconciled the way ELF is.
An ELF section header table lists every byte-bearing region, so a byte belonging to nothing is a
reader defect and razves refuses it. Mach-O's link-edit area is described by load commands, and a
reader that has not implemented one legitimately will not know what some bytes are. So the report
gained an explicit `unparsed` row, separate from padding: saying "razves did not account for these
bytes" is honest, and folding them into padding is the failure the reconciliation exists to prevent.

**Two things the item did not predict.** `__LINKEDIT` is *mapped* — so "allocated" is the letter of
the format — but it holds the symbol table, the string table and the fixup data, which is the role
ELF gives to non-allocated sections and exactly what `strip` removes. razves classifies by role, so
that a Mach-O report and an ELF report answer the same question; the `SectionKind` entry is called
`METADATA` rather than `NOT_ALLOCATED` because of it. And the gap in front of a segment is bounded by
the page it starts on, not by any section's alignment: without that bound three gaps totalling 30,424
bytes on the measured subject read as unaccounted-for when they are plainly padding.

**Measured on `shildik/core/build/bin/macosArm64/debugTest/test.kexe`, 13,745,928 bytes:** 38 named
regions, 4,120 of header and load commands, 8,875,759 allocated, 4,833,032 of `__LINKEDIT`, 33,017
of padding, and **zero unparsed**.
