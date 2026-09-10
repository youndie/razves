---
id: B-03
title: "Mach-O reader with address-delta sizing, clamped at the section end"
status: open
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

- AC: attributed coverage of allocated sections on that fixture is ≥ 95%.
- AC: both `__const` sections appear in the report with their own sizes.
- AC: the report header states that sizes are address-derived.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/read/MachO.kt`
