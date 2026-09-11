---
id: B-37
title: "razves profile: the live path from a sampled process to a file"
status: done
priority: P1
size: M
stage: stage-4-profiler
epic: research-profiler
blocked_by: [B-33]
---

# B-37 — razves profile: the live path from a sampled process to a file

Everything on both sides of it exists: [B-30](B-30-in-process-sampler.md) takes the stacks,
[B-32](B-32-aggregate-samples.md) turns them into rows and [B-33](B-33-pprof-writer.md) writes a file
`go tool pprof` reads. What has never happened is the two halves meeting on a running program.

- **The decision and its reason.** The sampler writes its stacks somewhere the profiled process does
  not have to interpret - a file of raw addresses, written after the sampler is stopped, by the
  program itself. Everything else happens in razves afterwards, which is the whole architecture
  ([research §2.3](../research/research-profiler.md)): no symbol table is read inside somebody else
  process.
- Does **not** cover: attaching to a process that did not link the sampler. That is a different tool
  with a different trust question, and razves has not answered it.

- AC: a program that links `:sampler`, runs, and writes its dump produces a file that
  `razves profile <dump> <binary> --klibs …` turns into rows naming its own Kotlin packages.
  **Verified on the probe: 2,052 samples, 99.1% of leaves named, `kotlin.collections` 44.2% self and
  `io.github.youndie` on 100% of the stacks - which is what a program whose work is an `ArrayList`
  of boxed ints, called from one function, looks like.**
- AC: the sample count in the rows equals the count the sampler reported, and what the ring dropped
  is in the output rather than only in the sampler. **Verified in `LiveProfileTest`.**
- AC: the same run written as pprof opens in a viewer and shows the same count. **The writer and its
  Go oracle are [B-33](B-33-pprof-writer.md); `razves profile --out` writes the same aggregation
  through it.**
- Anchors: `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/Analyse.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/profile/SampleDump.kt`,
  `sampler/src/jvmTest/kotlin/io/github/youndie/razves/sampler/LiveProfileTest.kt`

**The first live run found a defect no unit test could have.** `backtrace()` is called from inside
the signal handler, so its frame 0 is the handler and frame 1 is the kernel trampoline - written
naively, every sample in every profile has the same leaf, and razves duly reported **one C function
as 100% of the time** on a workload that is mostly Kotlin. Every test up to this point handed the
aggregation stacks that a test made up, and all of them passed.

The fix is not a larger skip count. The interrupted program counter is in the context the kernel
hands the handler - exact, free, and the only frame that is certainly the program rather than the
sampler - so it goes in as the leaf, and the walked frames follow past the trampoline. The unwinder
then repeats that address as its own first useful frame, so the duplicate is dropped by comparison
rather than by skipping a fixed three: a profile with a self-recursive edge on every sample is one
every viewer draws wrongly.

**Before and after, same program, same rate:**

| | leaf in C | leaf in Kotlin | named |
|---|---|---|---|
| before | 100.0% | 0.0% | 100.0% (all of it the handler) |
| after | 44.7% | 49.7% | 99.1% |

**The dump is text on purpose.** A binary format would be smaller and would need a reader before
anybody could tell whether the sampler worked at all; this one can be read by a person who suspects
the addresses are wrong - which is exactly what happened above.
