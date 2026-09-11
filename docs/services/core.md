---
id: core
title: razves-core — binary readers, attribution, report model
type: service
repo_url: https://github.com/youndie/razves
module: core
tech_stack: [Kotlin Multiplatform, kotlinx-serialization]
owner: unassigned
depends_on: []
publishes:
  - io.github.youndie.razves:core
---

# razves-core

## 1. Responsibility

Everything that turns a file on disk into a report model: the ELF reader, the Mach-O reader, the
symbol grammar, the klib manifest reader, the archive index reader, the attribution, and the
arithmetic that reconciles the totals.

What it deliberately does **not** do:

* **know anything about Gradle.** No Gradle API on its classpath, so the whole of the interesting
  logic is testable without a TestKit build. This is the arrangement `sborka` already uses for
  `build-logic/core`, and the reason is the same: a test that needs a Gradle daemon is a test that
  gets run less often.
* **run subprocesses.** Not `llvm-nm`, not `llvm-size`, not `bloaty`, not `strip`. Kotlin/Native's
  own LLVM distribution ships none of them ([research §1.1](../research/research-architecture.md)),
  so a subprocess is a hidden host requirement that only fails in someone else's CI.
* **read DWARF.** Explicitly rejected in [research D4](../research/research-architecture.md): the
  subject is a release binary, where there is none.
* **print anything.** Rendering belongs to the front ends.

## 2. API contracts

* **Report model:** `core/src/commonMain/kotlin/io/github/youndie/razves/report/` — the serialised
  form is also the baseline file format read by [feature-size-diff](../features/feature-size-diff.md).
* **Entry point:** a single `analyze(binary, klibs, archives)` returning a report; both front ends
  call nothing else.
* **Versioning:** the baseline format carries a version field, because a baseline written by one
  release is read by the next.

## 2a. Code anchors

| File | What is there |
|---|---|
| `core/src/commonMain/kotlin/io/github/youndie/razves/read/ElfReader.kt` | section headers, `.symtab`/`.strtab`, `st_size` |
| `core/src/commonMain/kotlin/io/github/youndie/razves/read/MachO.kt` | load commands, `LC_SYMTAB`, address-delta sizing |
| `core/src/commonMain/kotlin/io/github/youndie/razves/attribute/Mangling.kt` | the twelve `k*:` prefixes, Rust v0 and legacy, Itanium C++ |
| `core/src/commonMain/kotlin/io/github/youndie/razves/attribute/Origin.kt` | the origin buckets |
| `core/src/commonMain/kotlin/io/github/youndie/razves/klib/KlibManifest.kt` | `unique_name`, package list, `native_targets` |
| `core/src/commonMain/kotlin/io/github/youndie/razves/report/Reconcile.kt` | the two identities; the report fails if they do not hold |
| `core/src/commonTest/kotlin/io/github/youndie/razves/` | fixture-driven tests |

## 3. How it is built

**Two size algorithms, named in the output.** ELF symbol tables carry `st_size`; Mach-O's `nlist`
has no size field at all, so a symbol's size is the distance to the next symbol, clamped at the end
of its section. Verified in [research §1.4](../research/research-architecture.md): the clamped
address-delta pass attributes 98.9% of a real `macosArm64` binary, and without the clamp the last
symbol of each section swallows the gap to the next one. The two algorithms do not produce
comparable numbers at byte precision, so the report names which one ran and the diff refuses to mix
them.

**Sections are keyed by `(segment, section)`, not by name.** Mach-O has two sections called
`__const`, in `__TEXT` and in `__DATA_CONST`. A name-keyed map loses one of them, and the loss is
silent and Apple-only.

**Classification order matters, and one ordering is wrong.** Rust's legacy mangling produces
`_ZN…` — the same prefix as Itanium C++. Testing "starts with `_Z` → C++" before "ends with
`17h<hex>E` → Rust" attributes about 964 KB of `tokio` and `sqlx_postgres` to the Kotlin/Native
runtime ([research §1.3](../research/research-architecture.md)). The Rust test runs first, and a
fixture test pins the order.

**Reconciliation is a constructor invariant, not a report section.** The report model cannot be
built with totals that do not add up; the failure is raised at construction with both sides of the
identity in the message. That is the check that runs on every invocation everywhere, and it is why
it outranks the bloaty cross-check in [research D7](../research/research-architecture.md).

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Library | `kotlinx-serialization` | the baseline file format |
| Test fixture | prebuilt `.kexe` files | the readers' inputs; see §8 |
| Test oracle | `llvm-nm` / `bloaty` if present | the third oracle, skipped by name when absent |

No runtime dependency on the Kotlin/Native toolchain: razves reads klib manifests itself rather
than shelling out to the `klib` tool.

## 5. Infrastructure and deploy

Published to Maven Central as `io.github.youndie.razves:core` alongside the plugin and the CLI. No
service, no image, no health endpoint.

## 6. Local setup

```bash
~/.claude/bin/wsl-run ./gradlew :core:allTests
```

Kotlin/Native targets build on the Linux box; `macosArm64` and any test that needs a Mach-O
fixture built on the spot run locally with `LOCAL=1`.

## 7. Configuration

None. The core takes arguments, not configuration.

## 8. Quirks

* **The test fixtures are binaries, and binaries are large.** Committing a 20 MB `.kexe` to get a
  realistic reader test is not an option. There are three kinds instead, and they check different
  things: hand-built ELF and Mach-O images assembled in memory (`fixture/ElfBuilder`,
  `fixture/MachOBuilder`), which state the expected bytes exactly; a compiled Kotlin/Native binary in
  the `:fixture` module, which is the only one that shows attribution working from source to a
  package row; and the real subjects, referenced by path, whose tests skip themselves by name when
  the path is absent.
* **A test resource is not a thing in a Kotlin/Native test.** Fixtures reach a native test through
  a generated source file or a path passed in, not through a resource loader.
* **The third oracle is allowed to be absent and must say so.** A cross-check against `llvm-nm` or
  `bloaty` that silently reports success when neither is installed is worse than not having it.
