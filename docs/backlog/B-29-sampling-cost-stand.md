---
id: B-29
title: "A stand that can resolve what sampling costs"
status: done
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
  measured separately - which is what "can resolve" means and is checkable. **Done, and better than
  asked: the stand climbs a ladder of deliberate costs and reports the smallest one it can see,
  instead of being told a threshold I chose. On the build machine it resolved a deliberate 5% as
  +4.94%.**
- AC: the spread of the control variant is reported beside every result, so a later run on a noisier
  machine cannot quietly pass. **Done - and the first thing it showed was that the machine was busy
  with somebody else load test, at which point the stand refused rather than reporting.**
- AC: the delivered sampling rate is counted, not requested. **Done: 915 Hz delivered for 1,000
  requested, 7,216 for 8,000, 17,944 for 20,000.**
- Anchors: `scripts/sampling_cost.py`,
  `sampler/src/linuxX64Main/kotlin/io/github/youndie/razves/sampler/Probe.kt`

**Both halves of a pair run in one process**, seconds apart, alternating which goes first. Two
separate runs of the binary are two scheduling decisions and two moments in whatever else the machine
is doing: measured that way the stand saw a 30% spread and could not resolve a deliberate 2%. In one
process, pinned to a core, the same comparison resolves 5%.

**Hardware counters would beat time and are not available**: `perf` on this kernel answers *perf not
found for kernel 6.6.87.2-microsoft*. Recorded rather than worked around silently - a quieter machine
with working counters would resolve far more than 5%, and this stand would then report a figure where
today it reports a bound.

## What it measured, and what it refused to conclude

| Requested | Delivered | Samples per half | Cost of sampling | Per sample |
|---|---|---|---|---|
| 1,000 Hz | 915 Hz | 2,680 | +1.61% - **below the 5% this stand resolves** | - |
| 8,000 Hz | 7,216 Hz | 21,879 | +6.59% | 8,593 ns |
| 20,000 Hz | 17,944 Hz | 54,951 | +7.22% | 3,712 ns |

**The brief asked for "sampling costs X% of CPU at 100 Hz" and it is still not published, for a
reason sharper than noise.** At 915 Hz the cost is under what this stand resolves, so the honest form
is a bound rather than a figure. The obvious escape - measure where it is resolvable and scale down -
is what the last two rows refuse: the delivered rate rose by two and a half times and the cost did
not move, so the cost is **not linear in the rate** and the per-sample figure is not a constant to
multiply. Whatever dominates it at these rates is not the per-sample work.

So what can be said today, and is: **at roughly 900 Hz, sampling costs under 5% of CPU on this
machine, which is all this stand can resolve.** The figure at 100 Hz needs a quieter machine with
hardware counters, and the stand now exists to take it there.
