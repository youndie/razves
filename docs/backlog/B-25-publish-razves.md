---
id: B-25
title: "Publish razves where another repository can reach it"
status: done
priority: P2
size: S
stage: stage-3-subjects
epic: feature-size-budget-gate
blocked_by: [B-24]
---

# B-25 — Publish razves where another repository can reach it

[B-24](B-24-consumable-by-coordinate.md) proved razves is publishable and that a project can apply it
by id. What is left is the part that cannot be verified from a working copy: a version line, a CI
workflow, and an artifact on a server another repository resolves from.

- **The decision and its reason.** Use what the portfolio already has rather than writing a publish
  job: `sborka` exposes `.github/workflows/publish-wip.yaml` as a workflow a repository calls with
  `uses:`, and `.github/actions/determine-version` for the run-number tail. Five hand-written copies
  of a publish block existed across the portfolio before that, and no two of them knew the same
  things.
- **Nothing here writes an unverified workflow.** A CI file that has never run is a file that says
  what somebody hoped would happen, and this repository's whole argument is that a number without a
  measurement is a hypothesis wearing a suit. The same goes for a job.
- Does **not** cover: Maven Central. The snapshot server is what the rest of the portfolio resolves
  first-party coordinates from, and Central is a separate decision with its own signing.

- AC: `io.github.youndie.razves:io.github.youndie.razves.gradle.plugin` resolves from the snapshot
  repository, checked by asking the server rather than by a green build - a publish task reports
  success and uploads nothing often enough that `sborka`'s own conventions carry a comment about it.
  **Verified at 0.1.0.3**, and not by the green build: a project that had never seen this build
  declared the marker in its `buildSrc`, compiled a convention that applies `io.github.youndie.razves`
  by id and sets `measure` to a `Measure` from `core`, and got all eight tasks -
  `sizeReport`/`sizeDiff`/`sizeBaselineWrite`/`sizeBudgetCheck`, debug and release.
- AC: the version head lives in `gradle.properties` and CI appends the run number, as every other
  repository here does. **Verified: 0.1.0.1, 0.1.0.2, 0.1.0.3 on three pushes.**
- Anchors: `gradle.properties`, `.github/workflows/`, `sborka/.github/workflows/publish-wip.yaml`

**What the first two published versions cost, and why that is the point.** The upload succeeded
every time; what nobody could do was use the result, and the only thing that said so was the job
that asks the repository afterwards.

* **0.1.0.1 resolved for nobody.** `sborka.jvmFloor` was 25, so a consumer on Java 21 is told there
  is no matching variant - and for a Gradle plugin that floor is the floor of who can apply razves
  at all, because a plugin runs on the JVM the consumer's build runs on. Now built by 25, targeted
  at 17; measured on the jar, class major 61.
* **0.1.0.1 also hid `core` from anything compiling against the plugin**: `BinarySizeExtension.measure`
  is a `Property<Measure>` and `Measure` lives in `core`, which was `implementation`. That is B-17's
  own scenario, and it would have failed in `sborka` rather than here.
* **0.1.0.2 handed out `KSerializer` and `Json` that a consumer cannot name.** Not a leak to plug:
  every `@Serializable` class hands out a serializer, and `ReportDocument.JSON` is public on purpose.
  The declaration was wrong, not the API.
* **And applying razves without KGP on the classpath crashes rather than doing nothing** -
  [B-28](B-28-apply-without-kgp.md), found while building the consumer that verified this item.

Three defects, none of which any test in this repository could see, because every one of them is
about what a *different* build receives.
