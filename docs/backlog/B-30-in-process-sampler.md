---
id: B-30
title: "The in-process sampler, in C, behind cinterop"
status: done
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
  completions counted. **Verified: `SamplerSurvivalTest`, 39 process launches, 10 of 10 completed at
  100 Hz, 1 kHz and 10 kHz on the wall clock and at 1 kHz on the CPU clock, none hung, none died.**
- AC: the ring buffer is fixed at compile time and counts what it dropped; a profile that lost
  samples says so rather than looking complete. **Verified, and the number is the point: one run at
  10 kHz took 21,340 samples into 4,096 slots and reported 17,245 dropped.**
- AC: stopping the sampler is idempotent and leaves no handler installed. **Verified by construction
  and by the run with no rate at all: no timer, no samples, clean exit.**
- Anchors: `sampler/src/nativeInterop/cinterop/sampler.def`,
  `sampler/src/linuxX64Main/kotlin/io/github/youndie/razves/sampler/Sampler.kt`,
  `sampler/src/jvmTest/kotlin/io/github/youndie/razves/sampler/SamplerSurvivalTest.kt`

**What it measured on the way in**, one run each on the build machine, 20,000 rounds of an
allocating workload:

| Requested | Clock | Taken | Dropped | Mean depth |
|---|---|---|---|---|
| 100 Hz | wall | 199 | 0 | 12.5 |
| 1,000 Hz | wall | 2,127 | 0 | 12.3 |
| 10,000 Hz | wall | 21,340 | **17,245** | 12.3 |
| 1,000 Hz | cpu | 563 | 0 | 12.3 |

The CPU clock delivering 563 where the wall clock delivered 2,127 is research §1.3 arriving in the
module rather than in a document: a CPU clock advances on the scheduler tick, and no API argument
changes that.

**What it costs the build**, measured rather than guessed because it is a cost everybody pays:
`:sampler:build --rerun-tasks` takes **2m 11s** on the build machine, of which the survival test is
most — 39 process launches, each with its own allocating workload. The whole rest of the repository
builds in 19s warm. That is the price of an acceptance criterion that starts processes, and it is
worth it: no test inside one process can see a process that hangs.

**One thing the link found that no reading would have.** `timer_create` is in `librt` on the glibc
this toolchain links against, and without `linkerOpts.linux = -lrt` the link fails naming the
function rather than the library. The spike linked without it - through `kotlinc-native` directly,
which is not the path Gradle takes.
