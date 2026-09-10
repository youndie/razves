---
id: B-19
title: "Resolve ambiguous packages with a full declaration-to-module map"
status: open
priority: P3
size: L
stage: stage-1-attribution
epic: feature-size-report
blocked_by: [B-08]
---

# B-19 — Resolve ambiguous packages with a full declaration-to-module map

[B-08](B-08-klib-package-to-module.md) reports 9 packages of 568 as ambiguous rather than guessing.
`klib dump-metadata` lists actual declarations, so a full FQN → module map would resolve every one
of them.

- **The decision and its reason.** Deferred deliberately. It costs a metadata dump per klib on
  every report, against a 1.6% ambiguity rate
  ([research §1.5](../research/research-architecture.md)). The item exists so that the answer is
  one command away when somebody's ambiguous row is finally big enough to complain about — which is
  a better trigger than a guess made now.
- Rejected: a tie-break. See [B-08](B-08-klib-package-to-module.md); a confidently wrong number
  costs more than a visible ambiguous row.
- Does **not** cover: making it the default. Even once it exists it should be opt-in, because the
  cost is per-report and the benefit is per-1.6%.

- AC: with the flag set, no ambiguous rows remain on the `shildik` subjects.
- AC: the cost of the flag on those subjects is measured and written down.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/klib/`
