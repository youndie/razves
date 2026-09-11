---
id: B-08
title: "Map package to module from klib manifests, and report ambiguity as ambiguity"
status: done
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

- AC: a package two klibs declare lands in an ambiguous row naming both, and in neither module's own
  row. Measured on the release subject: 8 such rows, 606,570 bytes.
- AC: without klibs the report stops at package level and `hasModuleAttribution` says so.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/klib/Klib.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/klib/PackageToModule.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/klib/Zip.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/klib/Inflate.kt`

**Done, and it cost a DEFLATE decompressor.** The package list comes out of the zip's central
directory, which is never compressed - but `unique_name` and `native_targets` live in
`default/manifest`, and every entry of every klib is deflated (measured: 86 entries of
`ktor-http-linuxX64Main-3.5.1.klib`, all method 8). `java.util.zip` is JVM-only and the CLI is a
native binary; a cinterop binding to zlib adds a def file and a platform link; guessing the
coordinate from the Gradle cache's directory layout is a guess about someone else's implementation
detail. Two hundred lines of RFC 1951 was the cheapest of the four and the only one that survives the
next front end. **Verified against `java.util.zip` on 8,642 entries, 87,025,540 bytes, byte for
byte.**

**Two traps, both found by measuring rather than by reading.**

A klib emits a `package_<fqn>/` directory for every *intermediate* level of its packages, each with a
real metadata fragment - `package_co/0_co.knm` is 14 bytes beside a 3,985-byte
`package_co.touchlab.stately.collections/0_collections.knm`, and nothing is declared in `co`.
Counting directories made the ambiguous share 9.3% against the 1.6% `klib info` reports. An empty
fragment is recognisable structurally: its metadata opens with three zero-length fields,
`0A 00 12 00 1A 00`. Only prefixes of other packages are inflated to check.

**The klib set must be the link classpath, not every klib on disk.** A project's build directory
holds `kotlinTransformedMetadataLibraries/` copies whose `unique_name` is the source-set form -
`kotlinx-datetime_commonMain` beside the published `org.jetbrains.kotlinx:kotlinx-datetime`. Feeding
both in makes every package that library declares look declared twice: **69 ambiguous rows worth 3.5
MB of 5.1 MB of Kotlin**, and nothing about the report looks broken. With a realistic classpath the
same binary gives **40 resolved rows, 8 ambiguous (606,570 bytes) and 27 with no declaring klib
(70,038 bytes) - 86.8% of the Kotlin bytes on a named module.** This is exactly the thing the Gradle
plugin knows and a directory sweep does not, and it is now the first requirement of
[B-12](B-12-gradle-size-report.md).
