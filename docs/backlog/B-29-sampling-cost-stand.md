---
id: B-29
title: "A stand that can resolve what sampling costs"
status: open
priority: P1
size: M
stage: stage-4-profiler
epic: research-profiler
---

# B-29 — A stand that can resolve what sampling costs

The number the brief asks for first - "sampling costs X% of CPU at 100 Hz" - cannot be given from
the spike that produced [research-profiler](../research/research-profiler.md) §1.4, and the reason
is the item: the run-to-run spread of the **unsampled** variant on that machine is 24-56% of its own
median. At 8,204 Hz, thirty-two thousand samples per run, the sampled runs were *faster* in four
pairs out of seven.

- **The decision and its reason.** The stand comes before the figure. A percentage published from a
  stand that cannot resolve it is a number with a suit on, and this repository has one rule about
  those. What the stand needs is the opposite of what the spike had: a quiet machine, one pinned
  core, a workload with no allocation-rate jitter, and a counter that is not wall time - instructions
  retired, or cycles, read per run rather than averaged over a session.
- Interleaved A/B stays: the pair runs back to back so a drifting machine moves both halves, and the
  statistic is the median of the paired differences rather than a difference of medians.
- Does **not** cover: publishing the number. That is the next item, and it names the stand.

- AC: the stand resolves a difference the size of one known-cost operation - injected deliberately,
  measured separately - which is what "can resolve" means and is checkable.
- AC: the spread of the control variant is reported beside every result, so a later run on a noisier
  machine cannot quietly pass.
- AC: the delivered sampling rate is counted, not requested: research §1.3 measured 219 Hz for a
  requested 1000.
- Anchors: `docs/research/research-profiler.md`
