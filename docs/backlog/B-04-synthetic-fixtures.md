---
id: B-04
title: "Synthetic binaries whose attribution is known by construction"
status: done
priority: P0
size: M
stage: stage-1-attribution
epic: feature-size-report
blocked_by: [B-05, B-07]
---

# B-04 — Synthetic binaries whose attribution is known by construction

[B-02](B-02-reconciliation-invariant.md) proves the attribution is complete. Nothing yet proves it
is *right*: a symbol charged to the wrong package reconciles perfectly.

**Re-staged from `stage-0-readers` when it came up.** Its first acceptance criterion is about
per-package totals, and packages do not exist until [B-05](B-05-mangling-grammar.md) and
[B-07](B-07-kotlin-packages.md) do — there is nothing for a compiled fixture to be checked against
before then. The item was written as stage-0 because building a fixture *looks* like reader work.
It is not: the fixture's whole value is verifying that attribution lands in the right place, and
until there are places, it can only restate what the reader already said.

- **The decision and its reason.** The test suite compiles small Kotlin sources into a native
  binary whose functions have distinct, known sizes in known packages, and asserts the per-package
  totals exactly. This is the only oracle that can catch a grammar that parses a package wrongly.
- Rejected: committing prebuilt binaries. A realistic one is 20 MB, and a small one is small
  precisely because it exercises nothing.
- Does **not** cover: the real subjects. Those are referenced by path and their tests skip, by
  name, when the path is absent — see [B-16](B-16-run-on-the-three-subjects.md).

- AC: for each package in the fixture, attributed bytes equal the sum of its functions' sizes.
- AC: the fixture builds for both `linuxX64` and `macosArm64`, so both readers are covered.
- AC: a test resource is not used — a Kotlin/Native test cannot load one; the path arrives through
  generated source or an argument.
- Anchors: `core/src/commonTest/kotlin/io/github/youndie/razves/fixture/`
