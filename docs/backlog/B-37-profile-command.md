---
id: B-37
title: "razves profile: the live path from a sampled process to a file"
status: open
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
- AC: the sample count in the rows equals the count the sampler reported, and what the ring dropped
  is in the output rather than only in the sampler.
- AC: the same run written as pprof opens in a viewer and shows the same count.
- Anchors: `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/`,
  `sampler/src/linuxX64Main/kotlin/io/github/youndie/razves/sampler/Sampler.kt`
