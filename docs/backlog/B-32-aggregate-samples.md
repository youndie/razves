---
id: B-32
title: "Aggregate samples by origin, package and module"
status: open
priority: P1
size: M
stage: stage-4-profiler
epic: research-profiler
blocked_by: [B-30, B-31]
---

# B-32 — Aggregate samples by origin, package and module

The product: a stack of addresses becomes a row a Kotlin developer can act on - `io.ktor.client` 12%,
`stdlib` 7% - through the same grammar and the same klib map the size report uses.

- **The decision and its reason.** This is why the profiler is built on razves rather than beside it.
  The mangling grammar, the package folding, the klib module map and the ambiguity rules already
  exist and are tested against real binaries; a profiler that re-derived them would be a second
  implementation of the part that is hard.
- **Frames outside the binary are a named row.** Every sampled stack crosses into libc, the loader or
  a shared library - research §1.5 caught three frames of eight at once. They are named by the
  mapping they fall in, never folded into the nearest Kotlin frame and never dropped. The same rule
  as `unattributed`, for the same reason.
- On Apple targets the image slides, so the in-process half records the slide: it is known there and
  unknowable afterwards (research §1.6).
- Does **not** cover: a flame graph. The rows come first; a viewer is [B-33](B-33-pprof-writer.md).

- AC: the shares of every row sum to the samples taken, with the outside-the-binary row included and
  the dropped count stated separately.
- AC: a profile of a binary razves can also size reports the same package names as the size report
  does, which is the cross-check the pairing exists for.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/attribute/`
