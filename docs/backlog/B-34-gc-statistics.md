---
id: B-34
title: "Garbage collection, by polling, saying what it missed"
status: open
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
- AC: the poll interval is a setting and the collections-per-window figure names it, because the two
  are read together or not at all.
- Anchors: `docs/research/research-profiler.md`
