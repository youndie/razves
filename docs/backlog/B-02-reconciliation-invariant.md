---
id: B-02
title: "The report cannot be constructed with totals that do not add up"
status: done
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

- AC: a deliberately broken reader - one that drops a section - fails at report construction,
  naming the bytes nobody claims, rather than producing a report.
- AC: `unattributed` is a field of the model, so it cannot be omitted from a renderer by accident.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/Reconciliation.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/report/Attribution.kt`

**Done, and it earned its keep on the first run.** The first version computed
`padding = fileSize - headers - sections`, which made the identity true by construction: the test
that drops a section passed, because the section's bytes became padding. So the padding is now
measured from the offsets and every gap is held against the alignment of the region it precedes -
a bound that is exact rather than heuristic, verified across 34 to 43 regions on each of the four
subjects with zero violations. The correction is written into
[research §1.2](../research/research-architecture.md) at the point of divergence.

**A second finding came out of the same work:** charging each byte to exactly one symbol rather than
summing recorded sizes lowers the attributed total by 0.07% to 0.11% on the four subjects - aliases
and weak definitions cover the same bytes twice. Any tool that sums `nm` sizes over-reports its own
attribution by about that much.
