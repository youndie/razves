---
id: B-04
title: "Synthetic binaries whose attribution is known by construction"
status: open
priority: P0
size: M
stage: stage-0-readers
epic: feature-size-report
---

# B-04 — Synthetic binaries whose attribution is known by construction

[B-02](B-02-reconciliation-invariant.md) proves the attribution is complete. Nothing yet proves it
is *right*: a symbol charged to the wrong package reconciles perfectly.

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
