---
id: B-08
title: "Map package to module from klib manifests, and report ambiguity as ambiguity"
status: open
priority: P1
size: M
stage: stage-1-attribution
epic: feature-size-report
---

# B-08 — Map package to module from klib manifests, and report ambiguity as ambiguity

The brief assumed this needed Gradle. It does not: `klib info` prints `unique_name=<group>:<artifact>`
and a `Non-empty package FQNs` list, so the mapping lives in the klib
([research §1.5](../research/research-architecture.md)). Gradle's advantage is narrower — it knows
*which* klibs were linked without being told.

Over 141 klibs the mapping covers 568 packages across 122 modules, and **9 packages are claimed by
two modules**: `androidx.lifecycle`, five `com.github.ajalt.clikt.*`,
`dev.inmo.tgbotapi.extensions.behaviour_builder`, `org.koin.core`, `org.koin.core.annotation`.

- **The decision and its reason.** Read the manifest directly rather than shelling out to the
  `klib` tool — same reason as [B-01](B-01-elf-reader.md). A package claimed by more than one module
  produces an `<ambiguous: a, b>` row. No tie-break: a tie-break is a number that is confidently
  wrong, and the value of this tool is that its totals can be trusted.
- Also read `native_targets`, so a `linuxX64` binary pointed at `macosArm64` klibs is refused rather
  than reported ([B-15](B-15-refuse-stripped-and-mismatched.md)).
- Does **not** cover: resolving the ambiguity via `dump-metadata`. Deferred to
  [B-19](B-19-full-fqn-module-map.md) until an ambiguous row is big enough to complain about.

- AC: `org.koin.core` symbols in the Postgres release subject land in an ambiguous row naming
  `io.insert-koin:koin-core` and `io.insert-koin:koin-ktor`, and in neither module's own row.
- AC: without klibs the report stops at package level and its header says so.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/klib/KlibManifest.kt`
