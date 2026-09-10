---
id: B-11
title: "The CLI: report and diff for any binary, with optional klibs"
status: open
priority: P1
size: M
stage: stage-2-gradle
epic: feature-size-report
---

# B-11 — The CLI: report and diff for any binary, with optional klibs

```
razves report <binary> [--klibs <dir>] [--archives <dir>] [--format text|json]
razves diff <baseline.json> <binary> [--klibs <dir>]
```

- **The decision and its reason.** `--klibs` is what makes the CLI equal to the plugin rather than
  a degraded version of it. [Research §1.5](../research/research-architecture.md) established that
  the package-to-module mapping lives in the klib manifests, so a directory — a Gradle cache, a
  `~/.konan` distribution, an unpacked dependency set — is enough. This was a deviation from the
  brief, which assumed module attribution belonged to Gradle.
- Ships as a native executable per target, because the tool exists to serve people who ship a
  single binary and telling them to install a JVM to measure one would be absurd.
- Does **not** cover: a config file, environment variables, or any state between runs.

- AC: `razves report` on razves' own release binary succeeds and prints ambiguous rows for
  `com.github.ajalt.clikt.*` — the collision [research §1.5](../research/research-architecture.md)
  measured, visible in the tool's own output.
- AC: without `--klibs`, the header says module attribution was not available.
- Anchors: `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/`
