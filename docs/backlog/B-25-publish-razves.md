---
id: B-25
title: "Publish razves where another repository can reach it"
status: open
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
- AC: the version head lives in `gradle.properties` and CI appends the run number, as every other
  repository here does.
- Anchors: `gradle.properties`, `.github/workflows/`, `sborka/.github/workflows/publish-wip.yaml`
