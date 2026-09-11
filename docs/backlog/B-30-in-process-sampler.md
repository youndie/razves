---
id: B-30
title: "The in-process sampler, in C, behind cinterop"
status: open
priority: P1
size: M
stage: stage-4-profiler
epic: research-profiler
---

# B-30 — The in-process sampler, in C, behind cinterop

A timer, a signal handler, a fixed ring buffer. Nothing else runs inside the profiled process: no
symbol table, no name resolution, no allocation on the sampled path.

- **In C, and that is a deviation from the brief.** [research §1.1](../research/research-profiler.md)
  measured a Kotlin handler hanging 3 runs in 10 at 100 Hz and 8 in 10 at 1 kHz, and crashing twice
  more - the failure rate rises with the rate and vanishes when the workload stops allocating,
  because the handler deadlocks against the allocator it interrupted. It is not the body: a handler
  that only increments an atomic fails just as often. The same sampler in C completed 10 runs of 10
  at every rate up to 10 kHz, unwinding included.
- **The clock is a setting.** Both CPU-time clocks saturate at about 200 Hz on the scheduler tick;
  `CLOCK_MONOTONIC` delivered 8,204 Hz. They answer different questions, so the caller picks and the
  profile reports what it got.
- `backtrace()` is warmed once at start-up: its first call loads libgcc, which is the one thing in it
  that is not async-signal-safe, and a cold first sample would deadlock in the loader.
- Does **not** cover: Apple targets. `timer_create` does not exist there and the register context is
  laid out differently - [B-36](B-36-apple-sampler.md).

- AC: the survival table of research §1.2 is a test, not a paragraph: four rates, ten runs each,
  completions counted.
- AC: the ring buffer is fixed at compile time and counts what it dropped; a profile that lost
  samples says so rather than looking complete.
- AC: stopping the sampler is idempotent and leaves no handler installed.
- Anchors: `docs/research/research-profiler.md`
