---
id: B-38
title: "Publish the sampler, which is the one artefact a consumer has to link"
status: done
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
  repository, checked by asking the server rather than by a green publish task. **Verified at
  0.1.0.28: `sampler`, `sampler-jvm`, `sampler-linuxx64` and `sampler-macosarm64` all answer 200,
  and proba reports nothing but the empty jvm jar it correctly cannot judge.**
- AC: a project that has never seen this build links the sampler by coordinate, samples itself, and
  gets a dump razves reads. **Verified: a throwaway Kotlin/Native project declaring
  `io.github.youndie.razves:sampler:0.1.0.28` out of the snapshot repository linked, sampled itself -
  2,662 samples, none dropped - and razves named 99.3% of its leaves, with `kotlin.collections` at
  44.8% self.**
- Anchors: `sampler/build.gradle.kts`, `.github/workflows/publish-snapshot.yaml`

**The first publish put a promise on the server it could not keep, and proba said so in one line.**
The root module named a `macosArm64` variant and the repository answered 404 for it: a Linux runner
**skips** `cinteropSamplerMacosArm64`, because a klib cross-compiles and a cinterop against the Apple
SDK does not. Measured the other way round too - a mac builds `cinteropSamplerLinuxX64` and its klib -
so one host can publish all of it and that host is the mac. The publish runs there now.

**That move cost two more runs, and both of them were worth what they taught.** The Kotlin tests ran
nowhere except inside the publish job, so moving it to a mac took the pprof oracle to a runner with
no Go - where the guard from [B-33](B-33-pprof-writer.md) refused, exactly as instructed. The tests
have their own Linux job now, with Go installed deliberately rather than inherited from an image.

And the mac run failed again on two tests whose assertions were about the machine rather than about
razves: `:mcp` was handed the `linuxX64` binary because its test wiring hardcoded the target its
neighbour resolves by host, and the live profile asserted that 80% of leaves have a name - which is
83.5% on one mac, 76.6% on a CI mac and 99.1% on Linux, because it measures how much of a program
lives outside its image rather than anything razves does. The first cost a whole run to diagnose,
because the test read stdout and let stderr go nowhere; it reports stderr now.
