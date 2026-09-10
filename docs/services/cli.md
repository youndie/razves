---
id: cli
title: razves CLI — the same attribution for any binary
type: service
repo_url: https://github.com/youndie/razves
module: cli
tech_stack: [Kotlin/Native, clikt]
owner: unassigned
depends_on:
  - core
publishes:
  - razves (native executable, linuxX64 + macosArm64)
---

# razves CLI

## 1. Responsibility

The same attribution as the plugin, for a binary nobody here built — a release artifact, a
container's contents, somebody else's `.kexe`. It is also the tool's own first subject: razves
ships as a Kotlin/Native binary and reports on itself.

What it deliberately does **not** do: guess. Without klibs it stops at package attribution and says
so in the report header; without archives it labels the C attribution as heuristic.

## 2. API contracts

```
razves report <binary> [--klibs <dir>] [--archives <dir>] [--format text|json]
razves diff <baseline.json> <binary> [--klibs <dir>]
```

The `json` format is the core's report model — the same file the plugin writes as a baseline, so a
CLI report can be diffed against a build's baseline and the other way round.

## 2a. Code anchors

| File | What is there |
|---|---|
| `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/Main.kt` | command tree |
| `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/ReportCommand.kt` | `report` |
| `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/DiffCommand.kt` | `diff` |
| `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/render/` | the text renderer |

## 3. How it is built

**`--klibs` is what makes the CLI equal to the plugin.** The brief assumed module attribution
belonged to Gradle. It does not: `unique_name` and the package list live in each klib's manifest
([research §1.5](../research/research-architecture.md)), so a directory of klibs — a Gradle cache,
a `~/.konan` distribution, an unpacked dependency set — is enough. Gradle's advantage is only that
it knows which klibs without being told.

**Target mismatch is refused rather than reported.** Each klib manifest carries `native_targets`,
so pointing a `linuxX64` binary at `macosArm64` klibs produces a refusal naming both, not a
plausible and wrong report.

**The renderer prints coverage next to every conclusion.** A `.rodata` row derived from 41% of the
section and a `.text` row derived from 98% of it must not look alike
([research risk 3](../research/research-architecture.md)).

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [core](core.md) | all attribution |
| Library | `clikt` | argument parsing |

## 5. Infrastructure and deploy

A native executable per target, attached to the GitHub release. No JVM required to run it — which
is the point, given that the tool exists to serve people who ship a single binary.

## 6. Local setup

```bash
~/.claude/bin/wsl-run ./gradlew :cli:linkReleaseExecutableLinuxX64
```

`macosArm64` is built locally with `LOCAL=1`; it does not cross-compile from the Linux box.

## 7. Configuration

None beyond the arguments above. No config file, no environment variables.

## 8. Quirks

* **`clikt` splits five of its packages across two artifacts.** `com.github.ajalt.clikt.core` and
  four neighbours are declared by both `clikt` and `clikt-mordant`
  ([research §1.5](../research/research-architecture.md)), so razves reporting on *itself* will
  print ambiguous rows. That is the right answer, it is visible in the tool's own output, and it is
  the cheapest possible demonstration that the ambiguity handling works.
* **The CLI's own binary is a subject.** Any regression in the readers shows up as a broken
  self-report, which is a better smoke test than anything written on purpose.
