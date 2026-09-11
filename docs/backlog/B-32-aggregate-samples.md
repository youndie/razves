---
id: B-32
title: "Aggregate samples by origin, package and module"
status: done
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
  the dropped count stated separately. **Verified, and not by a test that could be deleted: the sum
  is a constructor invariant, so a `Profile` whose origin rows account for fewer samples than were
  taken cannot be built at all.**
- AC: a profile of a binary razves can also size reports the same package names as the size report
  does, which is the cross-check the pairing exists for. **Verified on the release binary of a real
  service: 241 samples synthesised from its own Kotlin symbols produced 38 package rows, of which
  **zero** are names the size report does not also have.**
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/profile/`,
  `core/src/commonTest/kotlin/io/github/youndie/razves/profile/ProfilingTest.kt`

**It went into `core`, not into a `:profile` module, and the reason is the one this item is about.**
The naming rules - the package fold, the truncation, the module resolution with its ambiguity rows -
are private inside `Attribution`. A separate module would have forced them into public API purely
for packaging, and the single thing this item must guarantee is that the profile and the size report
**cannot** disagree, because there is one rule rather than two that agree until they do not.
`:sampler` stays its own module because its boundary is real: it is the only code linked into a
program razves did not build.

**Self and total are both here because they answer different questions**, and `total` counts a row
once per stack rather than once per frame - recursion would otherwise let one sample add five to a
row and produce totals larger than the samples taken, which is a number no reader can interpret and
no downstream invariant can catch.

**The two ways of having no owner are two rows.** An address inside an allocated section that no
symbol covers is razves failing to name something - measured at 2.3% of `.text` addresses in
[B-31](B-31-symbol-at-address.md). An address outside every section is libc or the loader, and every
real stack has some: three frames of eight in the first stack the spike captured. One number for
both would be a number nobody can act on.

**What is not proven yet**: the live path, sampler to profile, on a running program. The stacks in
the real-binary check are synthesised from the binary own symbols, because a unit test cannot run the
program it is profiling. The end-to-end arrives with the command in
[B-33](B-33-pprof-writer.md).
