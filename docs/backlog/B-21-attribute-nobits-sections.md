---
id: B-21
title: "Attribute NOBITS sections so uninitialised state has an owner"
status: open
priority: P3
size: S
stage: stage-1-attribution
epic: feature-size-report
blocked_by: [B-20]
---

# B-21 — Attribute NOBITS sections so uninitialised state has an owner

Found while building [B-06](B-06-origin-buckets.md), and it is a silent gap rather than a bug: the
attribution sweep runs over `SectionKind.ALLOCATED` only, so symbols in `.bss` and `.tbss` are
dropped entirely. A package with a megabyte of uninitialised state gets no row for it, and nothing
in the report says that it does not.

- **The decision and its reason.** Leave it, for now, and say so out loud in the report's
  Limitations rather than in a code comment nobody opens. While the subject is file size the
  omission is correct — NOBITS costs nothing to download, and counting it would put bytes in a total
  the user does not pay for.
- **It stops being correct the moment the budget is set on the allocated size** instead, which is
  one of the three answers [B-20](B-20-decide-the-budget-unit.md) is weighing. That is why this is
  blocked by it rather than scheduled: if file size wins, this item is a Limitations paragraph and
  nothing more; if allocated size wins, it is a correctness defect in the gate.
- Does **not** cover: reporting NOBITS in the same total as file bytes. They are different
  quantities and the report already keeps `virtualSize` apart from `fileSize` for that reason.

- AC: whichever way [B-20](B-20-decide-the-budget-unit.md) goes, the report states what it does with
  NOBITS rather than leaving a reader to infer it from a number that is missing.
- AC: if NOBITS is attributed, its bytes appear in the virtual-size column and never in the file one.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/Attribution.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/report/SizeReport.kt`
