---
id: B-34
title: "Garbage collection, by polling, saying what it missed"
status: done
priority: P2
size: S
stage: stage-4-profiler
epic: research-profiler
---

# B-34 — Garbage collection, by polling, saying what it missed

`GC.lastGCInfo` carries the epoch, the start and end, both pause windows, the root set, the marked
count and the memory usage before and after ([research §1.7](../research/research-profiler.md)).

- **The decision and its reason.** There is no listener, callback or event stream anywhere in the
  2.4.10 standard library - verified against 54,835 lines of dumped metadata. So this is a poll, and
  a poll has a defect built into it: two collections between two polls leave one invisible.
- **It says what it missed.** Epochs are consecutive integers, so a gap between them is arithmetic.
  A profiler that reports four collections when there were nine is worse than one that reports none,
  and the fix is one subtraction rather than a faster poll.
- Does **not** cover: allocation profiling. Knowing which line allocated is a different mechanism,
  and nothing in the runtime exposes it today.

- AC: consecutive epochs are deduplicated; a gap is reported as a count of collections not seen.
  **Verified live, and the demonstration is the item: the same workload polled every 64 rounds saw
  389 collections and missed none; polled every 5,000 rounds it saw 12 and missed 346.** A poller
  reporting those twelve as though they were all of them is the defect this exists to prevent.
- AC: the poll interval is a setting and the collections-per-window figure names it, because the two
  are read together or not at all. **Done - and the profile prints the missed count beside the
  pauses rather than in a footnote.**
- Anchors: `sampler/src/linuxX64Main/kotlin/io/github/youndie/razves/sampler/GcWatch.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/profile/SampleDump.kt`,
  `sampler/src/jvmTest/kotlin/io/github/youndie/razves/sampler/LiveProfileTest.kt`

**It is a poll because there is nothing to subscribe to.** `kotlin.native.runtime.GC` offers
`lastGCInfo` and no listener, callback or stream - checked against 54,835 lines of the 2.4.10
standard library metadata. So the arithmetic carries the honesty: epochs are consecutive integers,
and the gap between the one seen last and the one seen now is the number of collections that happened
unobserved.

**What a real run reports**, from the probe at 1 kHz over 60,000 rounds of an allocating workload:

```
  389 collections seen, 4.2 ms of pause in total, longest 301 us
```

and with the sparse poll:

```
  12 collections seen, 0.1 ms of pause in total, longest 19 us
  346 collections happened between two polls and were not seen - the numbers above are of what was
```

**The pause is both stop-the-world windows added together.** A cycle has one or two, and the second
is absent often enough that reporting only the first understates what the program felt.

**What this does not cover**: allocation profiling. Knowing which line allocated is a different
mechanism and nothing in the runtime exposes it - said here rather than left for someone to discover
the profiler cannot answer it.
