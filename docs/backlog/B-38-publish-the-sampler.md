---
id: B-38
title: "Publish the sampler, which is the one artefact a consumer has to link"
status: open
priority: P2
size: S
stage: stage-4-profiler
epic: research-profiler
blocked_by: [B-36]
---

# B-38 — Publish the sampler, which is the one artefact a consumer has to link

`core`, `gradle-plugin` and the plugin marker are published on every push
([B-25](B-25-publish-razves.md)). `:sampler` is not, and it is the only module a profiled program
must have: everything else reads files afterwards. Until it is published, the profiler works for this
repository and for nobody else - which the README now says out loud rather than implying otherwise.

- **The decision and its reason.** It publishes like the rest, through `io.github.youndie.sborka.publish`,
  which `:sampler` does not apply today. What makes it different from `core` is that a cinterop klib
  is produced per target, so the coordinates are `sampler`, `sampler-linuxx64` and
  `sampler-macosarm64` rather than one - and the root module is what a consumer names.
- **It needs a route on the snapshot server**, and that is not a code change. The token
  `youndie/razves` holds was issued for six coordinates and the sampler is in none of them; a
  publish would fail with a 401 that names neither. The `reposilite-token` workflow in the infra
  repository re-issues with the coordinates listed - dispatched by a person, because it writes a
  credential.
- Does **not** cover: a Gradle plugin that adds the sampler to a consumer's build. Linking a signal
  handler into somebody's program is a decision they make in their own build file, in one line they
  can see.

- AC: `io.github.youndie.razves:sampler` and its two target artefacts resolve from the snapshot
  repository, checked by asking the server rather than by a green publish task.
- AC: a project that has never seen this build links the sampler by coordinate, samples itself, and
  gets a dump razves reads - the shape `LiveProfileTest` proves inside this build.
- Anchors: `sampler/build.gradle.kts`, `.github/workflows/publish-snapshot.yaml`
