---
id: B-21
title: "Attribute NOBITS sections so uninitialised state has an owner"
status: done
priority: P3
size: S
stage: stage-1-attribution
epic: feature-size-report
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
  NOBITS rather than leaving a reader to infer it from a number that is missing. **Done.**
- AC: if NOBITS is attributed, its bytes appear in the virtual-size column and never in the file one.
  **Moot: it is not attributed, and the reason is now decided rather than deferred.**
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/TextReport.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/read/Binary.kt`

**Closed as a Limitations paragraph, which is what its own first criterion asked for.**
[B-20](B-20-decide-the-budget-unit.md) settled on **file size**, so the omission is correct rather
than merely tolerable: NOBITS costs nothing to download, and counting it would put bytes into a total
the user does not pay for.

**The report says so in the place it would otherwise be missing.** The reconciliation table carries
the line rather than leaving a reader to notice an absence:

```
  in memory only (NOBITS)      27,696 (27.0 KiB)   costs no download
```

That is the whole of the fix. A reader who wonders where `.bss` went finds it named, with the reason
beside it, in the same table as everything else.

**What would reopen this:** a budget set on `Measure.ALLOCATED`, which the DSL allows. A gate on the
allocated size does count NOBITS, and uninitialised state with no owner then becomes a correctness
gap rather than a deliberate omission. It is not the default and nothing in this portfolio sets it,
so reopening waits for somebody who does.
