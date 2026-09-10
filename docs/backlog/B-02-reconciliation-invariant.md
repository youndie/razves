---
id: B-02
title: "The report cannot be constructed with totals that do not add up"
status: open
priority: P0
size: S
stage: stage-0-readers
epic: feature-size-report
---

# B-02 — The report cannot be constructed with totals that do not add up

Two identities were verified by hand against a real binary
([research §1.2](../research/research-architecture.md)):
`allocated − NOBITS + non-allocated + headers = file size`, and
`attributed + unattributed = allocated`. On the `shildik` Postgres release binary that is
`16,241,668 − 27,696 + 4,324,353 + 5,411 = 20,543,736` exactly.

- **The decision and its reason.** Make it a constructor invariant of the report model rather than
  a section of the output. This is the oracle that runs on every invocation on every machine, which
  is why [research D7](../research/research-architecture.md) ranks it above the bloaty cross-check:
  an oracle that has to be installed is an oracle that gets skipped, and a skipped check that
  reports success is worse than no check.
- Rejected: a tolerance. There is no reason for these numbers to be approximately right.
- Does **not** cover: whether the attribution is *correct* — only that it is complete. A symbol
  charged to the wrong package still reconciles. That is [B-04](B-04-synthetic-fixtures.md)'s job.

- AC: a deliberately broken reader — one that drops a section — fails at report construction with
  both sides of the identity in the message, rather than producing a report.
- AC: `unattributed` is a field of the model, so it cannot be omitted from a renderer by accident.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/Reconcile.kt`
