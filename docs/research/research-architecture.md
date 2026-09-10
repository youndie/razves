---
id: research-architecture
title: razves — architecture research
type: research
status: active
date: 2026-09-11
---

# Research: the architecture of razves

`razves` answers one question — **what is inside this Kotlin/Native binary, and who put it there**
— and then turns the answer into a build gate. `bloaty` already attributes bytes to symbols, but a
Kotlin/Native symbol is `kfun:io.ktor.server.engine#embeddedServer(...)`, and a list of those is
not an answer to anybody. razves aggregates the same bytes into the units a Kotlin developer
actually reasons about: the package, and — when Gradle is present — the klib artifact the package
came from. The output is a report you can read and a budget the build can fail on, in the shape
Android has had for a decade with apk-size checks and Kotlin/Native has never had.

This document records **verified facts** (read in artefacts and measured on real binaries),
**decisions taken**, and **risks**. Anything unverified is marked as a hypothesis and says where it
will be checked.

Nothing here was measured on a synthetic example. The subjects are four Kotlin/Native binaries
already on disk in this portfolio, built by their own repositories with Kotlin 2.4.10.

### Measurement provenance

Everything in §1.2–§1.5 was produced on 2026-09-11 on the mac (macOS aarch64) with the `llvm-nm`,
`llvm-size` and `llvm-objdump` that ship with Xcode (`Apple LLVM version 21.0.0`, found through
`xcrun`), reading `linuxX64` and `macosArm64` binaries produced earlier by `shildik` and its
sibling repositories. A single run per binary is enough here because these are static
measurements of a file, not timings — the same file yields the same bytes every time. Nothing in
this document is a benchmark, and no number here is a claim about speed.

---

## 1. Verified facts

### 1.1 The Kotlin/Native toolchain does **not** ship the readers this tool needs

This is the first and most expensive finding, because the brief's opening premise rests on it:
"in `~/.konan/dependencies/llvm-*/bin` there are `llvm-nm`, `llvm-size`, `llvm-objdump`; they are
on every machine that builds K/N, so the dependency is free."

They are not there.

| Fact | Where verified |
|---|---|
| The only LLVM distribution Kotlin/Native 2.4.10 downloads on macOS aarch64 is `llvm-21-aarch64-macos-essentials-97` | `~/.konan/dependencies/` |
| Its `bin/` holds exactly nine entries: `clang`, `clang++`, `clang-21`, `clang-cache`, `ld.lld`, `lld`, `llvm-ar`, `llvm-cov`, `llvm-profdata` | `ls ~/.konan/dependencies/llvm-21-aarch64-macos-essentials-97/bin/` |
| There is no `llvm-nm`, no `llvm-size`, no `llvm-objdump`, no `llvm-strip` in it | same listing |
| `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.10/bin/` holds `cinterop`, `generate-platform`, `klib`, `konan-lldb`, `konanc`, `kotlinc-native`, `run_konan` — no binary reader either | `ls ~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.10/bin/` |
| On this mac the readers came from Xcode instead: `xcrun --find llvm-nm` → `…/XcodeDefault.xctoolchain/usr/bin/llvm-nm`; `/usr/bin/nm` reports itself as "llvm-nm, compatible with GNU nm, Apple LLVM version 21.0.0" | `xcrun --find llvm-nm`, `nm --version` |
| `xcrun --find llvm-strip` fails — Xcode has no `llvm-strip` either | `xcrun --find llvm-strip` → `unable to find utility "llvm-strip"` |

The word `essentials` in the distribution name is the whole story: JetBrains ships the subset of
LLVM needed to *compile and link*, not to *inspect*. There is no reason to expect that subset to
grow, and every reason to expect a CI image to have nothing else.

**Consequence 1.** "Free dependency" is false, and building the first version as a wrapper around
`llvm-nm` buys a tool that works on the author's mac (because Xcode is installed) and fails on a
Linux CI runner that has a JDK and nothing more. The subprocess route is not a cheap first version
— it is a first version with a hidden host requirement that only shows up in somebody else's
pipeline.

**Consequence 2.** The order of work in the brief inverts. Reading ELF and Mach-O symbol tables
directly is not the "second version if needed" — it is the only version that satisfies the stated
goal of running wherever Kotlin/Native builds. See [D1](#d1-read-the-symbol-tables-directly-no-subprocess-to-a-tool-the-toolchain-does-not-ship).

**Consequence 3.** The findings in §1.2–§1.4 below were nevertheless produced *through* those
Xcode tools. That is legitimate — they are the reference implementation this tool must agree with,
and cross-checking against them belongs in the test suite ([D7](#d7-the-oracle-is-arithmetic-first-and-a-second-reader-second)). It is shipping a dependency on them that is refused.

### 1.2 What a Kotlin/Native binary is actually made of

Four release/debug binaries from `shildik`, measured whole. `attributed` is the sum of ELF symbol
sizes; the grouping is by the mangling scheme of the symbol name (§1.3).

| Subject | File | Allocated sections | `.symtab`+`.strtab` | Debug sections | Attributed by symbol |
|---|---|---|---|---|---|
| `distribution` release (Postgres) | 20,543,736 | 16,241,668 (79.1%) | 4,323,906 (21.0%) | — | 12,917,296 (79.5% of allocated) |
| `distribution` debug | 39,818,016 | 22,412,342 (56.3%) | 8,421,700 (21.2%) | 9,012,672 (22.6%) | 18,423,601 (82.2%) |
| `distribution-sqlite` release | 21,555,648 | 17,235,072 (80.0%) | 4,343,054 (20.1%) | — | 14,038,141 (81.5%) |
| `cli` release | 15,398,536 | 12,497,572 (81.2%) | 2,920,385 (19.0%) | — | 9,853,024 (78.8%) |

And the attributed bytes, by origin:

| Subject | Kotlin | C / unmangled | Rust | C++ (konan runtime + libc++) |
|---|---|---|---|---|
| `distribution` release | 5,131,534 (39.7%) | 6,339,537 (49.1%) | 1,263,517 (9.8%) | 182,708 (1.4%) |
| `distribution` debug | 10,047,536 (54.5%) | 6,843,749 (37.1%) | 1,263,517 (6.9%) | 268,799 (1.5%) |
| `distribution-sqlite` release | 5,131,161 (36.6%) | 8,012,244 (57.1%) | 715,295 (5.1%) | 179,441 (1.3%) |
| `cli` release | 3,776,680 (38.3%) | 5,897,438 (59.9%) | — | 178,906 (1.8%) |

| Fact | Where verified |
|---|---|
| Section table, sizes and types of all four binaries | `xcrun llvm-objdump --section-headers <binary>` |
| Symbol sizes | `xcrun llvm-nm --print-size --radix=d <binary>` |
| `shildik/distribution/build/bin/linuxX64/releaseExecutable/shildik.kexe` is `ELF 64-bit LSB executable, x86-64 … not stripped` | `file <binary>` |
| The two single largest symbols in the Postgres release binary are `ossl_aes_gcm_encrypt_avx512` (337,642 B) and `ossl_aes_gcm_decrypt_avx512` (337,638 B) | `llvm-nm --print-size --size-sort` on that binary |
| The largest Kotlin symbol in it is `kfun:kotlin.text.regex.AbstractCharClass.Companion.CharClasses.$init_global#internal` (65,465 B) | same listing |
| `.text` is 97.7% attributable by symbol; `.rodata` only 40.7%; `.eh_frame` (1,287,092 B), `.eh_frame_hdr` (213,092 B) and `.gcc_except_table` (42,907 B) are 0% attributable by symbol | per-section attribution run over the Postgres release binary |
| `.dynsym`+`.dynstr`+`.gnu.hash`+`.gnu.version` = 668,452 B, also 0% attributable by symbol | same run |

**Consequence 1 — the headline of the first article is not the one the brief predicted.** The
brief expects the answer to "why is it this big" to be *stdlib, ktor, serialization*. In every
release subject measured, Kotlin is a **minority** of the attributed bytes: 36–40%. The majority
is statically linked C — OpenSSL, arriving through `ktor-client-curl` and `cryptography-provider-openssl3-prebuilt`
— plus, where `sqlx4k` is used, about 1.3 MB of Rust (`tokio`, `sqlx_postgres`, `url`, `chrono`).
A tool that only groups Kotlin packages answers a third of the question and silently drops the
rest into "other". Attribution of non-Kotlin origins is a first-class requirement, not a footnote.
See [D3](#d3-non-kotlin-origins-are-first-class-buckets-not-other).

**Consequence 2 — the symbol table is 19–21% of the file, in all four subjects.** `.symtab` plus
`.strtab` is the single largest *removable* thing in a Kotlin/Native release binary, and it is
what `strip` deletes. It is also exactly the input razves reads. So the tool must:

* report it as its own line, because it is the cheapest 20% anyone will ever save;
* run on the **unstripped** binary and say so, because on a stripped one there is nothing to read;
* express the saving as a **prediction** computed from the section table, never by stripping a copy
  and comparing — `llvm-strip` is not available (§1.1) and mutating the artefact to measure it is
  the wrong shape for a build gate anyway.

**Consequence 3 — file size, VM size and attributed size are three different numbers, and the
report must show all three.** The naive identity "sum of attributed = size of sections" is false in
two directions at once, and both were measured:

* NOBITS sections (`.bss`, `.tbss`, `.relro_padding` — 27,696 B in the Postgres release binary)
  have a size but occupy no file bytes;
* non-allocated sections (`.symtab`, `.strtab`, `.shstrtab`, `.comment`, and in a debug build
  9,012,672 B of DWARF) occupy file bytes but no address space.

Reconciled: 16,241,668 allocated − 27,696 NOBITS + 4,324,353 non-allocated + 5,411 of ELF header,
program headers and section headers = 20,543,736, the file size exactly. That identity is the
tool's primary oracle ([D7](#d7-the-oracle-is-arithmetic-first-and-a-second-reader-second)).

**Consequence 4 — `unattributed` is a large, structural number, not a rounding error.** 20.5% of
the allocated bytes of the Postgres release binary belong to no symbol, and most of it is
*identifiable* even though it is not attributable: 1.54 MB of unwind and exception tables, 0.67 MB
of dynamic linking tables, and 0.74 MB of `.rodata` with no owning symbol. The report should name
these as sections rather than lump them into one anonymous remainder — a reader who sees
"unattributed: 3.3 MB" learns nothing, and a reader who sees "`.eh_frame`: 1.29 MB" learns that
exception unwinding costs them 6% of the binary.

### 1.3 The Kotlin/Native symbol grammar, as it actually appears

The brief calls this "a grammar for a day". It is roughly right about the effort and wrong about
the alphabet. Counted over two binaries — a `linuxX64` ELF release and a `macosArm64` Mach-O debug
test — the Kotlin prefixes present are:

| Prefix | ELF (release) | Mach-O (debug test) |
|---|---|---|
| `kfun:` | 7,795 | 11,047 |
| `kclass:` | 3,572 | 1,824 |
| `kifacevtable:` | 3,203 | 1,538 |
| `kifacetable:` | 3,020 | 1,381 |
| `krefs:` | 2,513 | 1,143 |
| `kintf:` | 2,397 | 1,158 |
| `kvar:` | 758 | 479 |
| `kassociatedobjects:` | 78 | 11 |
| `kexttype:` / `kextoff:` / `kextname:` | — | 1,240 each |
| `ktypew:` | — | 1,238 |

| Fact | Where verified |
|---|---|
| The twelve prefixes above and their counts | `llvm-nm <binary> \| grep -oE '^k[a-zA-Z_]+:'` over both binaries |
| Mach-O symbols carry a leading `_`, so the prefix match must be `^_?k…` | `llvm-nm` output of the `macosArm64` binary: `_kfun:kotlin.text.regex…` |
| `kexttype:`, `kextoff:`, `kextname:`, `ktypew:` appear only on the Apple target | absence from the ELF listing above |

**Consequence 1.** `kfun:`/`kclass:`/`kvar:` — the three the brief names — are 12,125 symbols of
the 23,336 Kotlin-prefixed ones in the ELF binary. The other nine prefixes are half the symbols;
they are mostly small (vtables, interface tables, reference tables) but they are *Kotlin*, and a
grammar that ignores them reports Kotlin as smaller than it is.

**Consequence 2 — the one that would have silently corrupted every report.** The brief says
"`konan::` (the C++ runtime)". In practice the Kotlin/Native runtime is compiled with Itanium C++
mangling (`_ZN…`), and so is the Rust code that `sqlx4k` links in, because Rust's *legacy* mangling
scheme also produces `_ZN…`. Grouping `_ZN` as "konan runtime" attributes about 964 KB of `tokio`,
`sqlx_postgres` and `core::ptr` to the Kotlin runtime.

| Fact | Where verified |
|---|---|
| Demangling `_Z*` symbols of the Postgres release binary and grouping by top two namespaces yields `tokio::runtime` 191,871 B, `sqlx_postgres::connection` 151,172 B, `core::ptr` 49,331 B, and `kotlin::gc` only 14,309 B | `llvm-nm --print-size \| llvm-cxxfilt`, grouped by `::` prefix |
| Rust legacy symbols are distinguishable by the trailing disambiguator `17h<16 hex digits>E` | e.g. `_ZN13sqlx_postgres10connection9establish…17h7c2ac2d78526d9b3E` in that listing |
| Rust v0 symbols (`_R…`) are also present — 299,916 B over 595 symbols | same run, separated by the `_R` prefix |
| With `17h…E` split out as Rust, the genuine C++ bucket in that binary falls to 182,708 B (1.4%) | grouping run in §1.2 |

**Consequence 3.** 26.7% of the attributed bytes carry names with **no mangling scheme at all** —
`ecp_nistz256_precomputed`, `nid_objs`, `sha1_multi_block`, `huff_decode_table`, `__unnamed_3673`.
These are plain C symbols and the C ABI has no namespaces, so no grammar can recover their origin.
Prefix heuristics (`ossl_`, `EVP_`, `X509`, `curl_`, `nghttp2_`, `sqlite3`) catch 16.1% and leave
the rest guessing. This is a hard limit on name-based attribution and drives
[D4](#d4-attribute-c-symbols-by-static-archive-membership-not-by-name).

### 1.4 `nm` reports symbol sizes on ELF and **zero** on Mach-O

| Fact | Where verified |
|---|---|
| `llvm-nm --print-size` on a Mach-O binary prints a warning and zero for every symbol: `warning: sizes with --print-size for Mach-O files are always zero` | `xcrun llvm-nm --print-size shildik/core/build/bin/macosArm64/debugTest/test.kexe` |
| On ELF the same flag yields a size for 57,905 of 58,164 symbols (the 259 without one are undefined `U` and weak-undefined `w` entries) | the same command on the `linuxX64` release binary |
| Deriving Mach-O sizes by sorting defined symbols by address and taking the delta to the next symbol, clamped at the end of the containing section, attributes 8,863,076 B of 8,957,547 B of allocated sections — 98.9% | address-delta run over `test.kexe` (50,360 defined symbols, 33 sections) |
| Mach-O has two distinct sections both named `__const` (in `__TEXT` and in `__DATA_CONST`), at different addresses | `llvm-size --format=sysv` on the same binary lists `__const` twice, at 4301131776 and 4302405632 |

**Consequence 1.** There are two size algorithms, not one. ELF carries `st_size` in the symbol
table; Mach-O's `nlist` has no size field, so the size of a symbol is the distance to the next one.
That is not an implementation detail to be hidden — it changes what the numbers mean. On Mach-O
the "size" of a symbol silently includes any alignment padding that follows it, so Mach-O totals
are slightly generous where ELF totals are slightly short. The report must say which algorithm
produced it.

**Consequence 2.** A section map keyed by name is wrong on Mach-O. The key is
`(segment, section)`, and any code that does `sections["__const"]` has a bug that only appears on
Apple targets.

**Consequence 3.** The address-delta pass needs the section boundary as a clamp, or the last
symbol in a section absorbs the gap to the next section. Verified: without the clamp the same run
attributes more than the section holds.

### 1.5 Package → module comes from the klib, not from Gradle

The brief says the package-to-module mapping is something "the plugin knows and a CLI without
Gradle does not". Half true, and the half that is false is the useful half.

| Fact | Where verified |
|---|---|
| `klib info <library>` prints `unique_name=<group>:<artifact>` and a `Non-empty package FQNs` list | `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.10/bin/klib info <klib>` |
| Example: `crypto-linuxX64Main-0.2.0-probe.klib` → `unique_name=ru.workinprogress.shildik:crypto`, packages `[ru.workinprogress.shildik.crypto]` | that command on `shildik/crypto/build/libs/crypto-linuxX64Main-0.2.0-probe.klib` |
| It also prints `depends=`, `native_targets=`, `compiler_version=`, `abi_version=` and a per-section size breakdown of the klib itself | same output |
| Over 141 klibs — every `*linuxX64Main*.klib` in the Gradle module cache plus the Kotlin/Native `klib/common` and `klib/platform/linux_x64` distributions — the mapping covers 568 packages across 122 modules | scripted `klib info` sweep, 2026-09-11 |
| **9 of those 568 packages (1.6%) are claimed by more than one module** | same sweep |
| The collisions: `androidx.lifecycle` (lifecycle-common + lifecycle-runtime); five `com.github.ajalt.clikt.*` packages (clikt + clikt-mordant); `dev.inmo.tgbotapi.extensions.behaviour_builder` (tgbotapi.behaviour_builder + …fsm); `org.koin.core` (koin-core + koin-ktor); `org.koin.core.annotation` (koin-core + koin-core-annotations) | same sweep |

**Consequence 1.** The mapping is a property of the *klib files*, not of Gradle. Gradle's job is
narrower and still necessary: it says **which** klibs took part in this specific link, and it is
the only thing that knows a project module's klib is `build/classes/kotlin/…` rather than a cache
entry. But a CLI handed a directory of klibs can do module attribution too. That widens the CLI
from "package-level only" to "package-level, or module-level if you point it at the klibs", which
is a materially better product for anyone analysing a binary they did not build.

**Consequence 2.** Package → module is *nearly* a function and must not be modelled as one. 1.6%
ambiguity is small enough to ignore in a chart and large enough to be wrong about a specific
library — and `org.koin.core` is in the subject binary. See
[D5](#d5-ambiguous-packages-are-reported-as-ambiguous-not-resolved-by-a-tie-break).

### 1.6 Which `-Xbinary` options exist in Kotlin 2.4.10, and which touch size

The brief promises the report will say "which `-Xbinary` flag gives what". The list of flags was
read out of the compiler rather than recalled.

| Fact | Where verified |
|---|---|
| Kotlin/Native 2.4.10 registers 56 binary options | `javap -p` over `org/jetbrains/kotlin/config/nativeBinaryOptions/BinaryOptions.class`, extracted from `kotlin-native-compiler-embeddable.jar` in the prebuilt distribution |
| The size-relevant ones present in that class: `smallBinary`, `stripDebugInfoFromNativeLibs`, `sourceInfoType`, `preCodegenInlineThreshold`, `latin1Strings`, `packFields`, `linkRuntime`, `gc` | same listing |
| `-Xbinary=list` is not a thing — the compiler answers `error: incorrect property format: expected '<key>=<value>', got 'list'` | `konanc -Xbinary=list` |
| `smallBinary`: `true`/`false`, default `false`, experimental since 2.2.20, "decreases the binary size for release binaries", implemented by making `-Oz` the default LLVM optimisation argument | [kotlinlang.org/docs/native-binary-options.html](https://kotlinlang.org/docs/native-binary-options.html), [What's new in Kotlin 2.2.20](https://kotlinlang.org/docs/whatsnew2220.html) |
| `latin1Strings`: `true`/`false`, default `false`, experimental since 2.2.0, reduces binary size and adjusts memory consumption | same doc |
| `sourceInfoType`: `libbacktrace` / `coresymbolication` / `noop`, default `noop`; non-`noop` adds file and line information to stack traces, and therefore size | same doc |
| `preCodegenInlineThreshold`: `UInt`, disabled by default, recommended 40 | same doc |
| `gc`: `cms` (default since 2.4.0), `pmcs`, `stwms`, `noop` | same doc |
| `shildik` sets **none** of these — the only `freeCompilerArgs` in the repository is `-Xexpect-actual-classes` in one module | `grep -rn "Xbinary\|binaryOption\|freeCompilerArgs" shildik --include=*.kts` |

**Consequence.** "Which flag gives what" cannot be answered from documentation, only measured, and
razves is the thing that measures it. But note what §1.2 implies about the ceiling: `smallBinary`
is `-Oz` on the **LLVM compilation of Kotlin code**. Kotlin code is 36–40% of the attributed bytes
of these binaries, so an optimisation that only reaches Kotlin has at most that much surface. The
flag-comparison table is a razves *output*, produced by building twice and diffing — see
[feature-size-diff](../features/feature-size-diff.md) — and its rows are measurements with a date
on them, not constants written into a README.

### 1.7 Prior art: what already exists

| Fact | Where verified |
|---|---|
| `google/bloaty` attributes bytes with its own ELF, DWARF and Mach-O parsers, breaks down by section, symbol, segment or compile unit, reports VM size and file size as two separate columns, and supports diffing two binaries | [github.com/google/bloaty](https://github.com/google/bloaty), [blog.reverberate.org](https://blog.reverberate.org/2016/11/07/introducing-bloaty-mcbloatface.html) |
| `JetBrains-Research/bitcode-tools` is a Gradle plugin for Kotlin/Native, but it works on **bitcode** — `decompileBitcode`, `extractBitcode` — not on the linked binary, and reports no sizes | [github.com/JetBrains-Research/bitcode-tools](https://github.com/JetBrains-Research/bitcode-tools) |
| Jake Wharton's "Shrinking a Kotlin binary by 99.2%" is the reference write-up on the subject and is a manual technique narrative, not a tool | [jakewharton.com/shrinking-a-kotlin-binary](https://jakewharton.com/shrinking-a-kotlin-binary/) |
| Targeted searches for a Kotlin/Native size-report or size-budget Gradle plugin returned no such tool | web searches, 2026-09-11, for "kotlin native binary size gradle plugin budget check CI regression size report" and neighbours |

**Consequence 1.** The niche looks empty, and the honest form of that claim is "a targeted search
found nothing", not "nothing exists". That is enough to justify building; it is not enough to put
in a README.

**Consequence 2.** bloaty's two-column VM-size/file-size model is the right prior art to copy, and
§1.2 Consequence 3 is the same problem arriving independently. Copy the model; do not copy the
dependency. bloaty is a C++ build that is on no Kotlin CI image, and it has never heard of a
Kotlin package.

**Consequence 3.** bloaty's *compile-unit* attribution via DWARF is the mechanism that would solve
the unmangled-C problem of §1.3 Consequence 3 — but only in a debug build, where §1.2 shows DWARF
is 9 MB, and razves must work on release binaries. Rejected for that reason; the archive-membership
route of [D4](#d4-attribute-c-symbols-by-static-archive-membership-not-by-name) reaches the same
answer without needing debug information.

---

## 2. Decisions

### D1. Read the symbol tables directly; no subprocess to a tool the toolchain does not ship

Brief: version one wraps `llvm-nm --size-sort` and `llvm-size` and parses their output; a
hand-written ELF/Mach-O reader is a possible version two.

Decision: the reader is version one. There is no subprocess.

Why:

- the tools are not in `~/.konan` (§1.1) — the dependency the brief priced at zero is a
  requirement for Xcode on macOS and binutils or an LLVM package on Linux, neither of which a
  Kotlin CI image has by default;
- the two formats needed are the two the brief already judged simple: an ELF section header table
  plus `.symtab`/`.strtab`, and a Mach-O `LC_SYMTAB` plus section commands. Neither needs DWARF,
  neither needs relocations, and both are a few hundred lines;
- parsing another program's text output adds a second failure mode — a version whose column layout
  differs — on top of the first one, the program being absent;
- the price: about a week of work that the brief hoped to defer, and two format readers to keep
  correct. Paid down by [D7](#d7-the-oracle-is-arithmetic-first-and-a-second-reader-second).

### D2. Two units of attribution, and the report always shows both

Section-level and symbol-level are not alternatives. §1.2 Consequence 4 measured 20.5% of allocated
bytes with no owning symbol, structured as identifiable sections. So every report has a section
table *and* a symbol-derived attribution table, and the section table is what the totals reconcile
against.

Rejected: a single flat "here is where your bytes went" list. It hides the fact that a fifth of the
binary is unwind tables and dynamic linking metadata that no package owns.

### D3. Non-Kotlin origins are first-class buckets, not "other"

Brief: attribution is by Kotlin package and module; `konan::` and "LLVM/libc" are mentioned in
passing.

Decision: the top-level split is by **origin** — Kotlin, C++ (Kotlin/Native runtime), Rust, C
(static libraries), and the sections nobody owns — and only the Kotlin bucket subdivides into
packages and modules.

Why:

- measured, Kotlin is 36–40% of a release binary in every subject (§1.2). A tool whose main table
  covers a third of the file and calls the rest "other" answers the wrong question;
- the origin split is what makes the answer *actionable*: "OpenSSL is 4 MB" points at
  `ktor-client-curl` and a CIO engine; "ktor is 1.3 MB" points at nothing you can do;
- the price: three mangling schemes to detect rather than one, and the Rust-versus-C++ trap of §1.3
  Consequence 2 to get right. Both are covered by fixture tests.

### D4. Attribute C symbols by static-archive membership, not by name

C symbols have no namespace and 26.7% of attributed bytes are C (§1.3 Consequence 3). Prefix lists
(`ossl_`, `curl_`, `nghttp2_`) are a guess that goes stale with every dependency bump.

Decision: build the map from the static archives that were linked — read each `.a`'s member index
and the defined symbols of each member object, and key the resulting `symbol → archive` map by
exact name. The archives are on disk: cinterop klibs ship them, and `klib info` names the artifact
that carries each one.

Rejected: **DWARF compile-unit attribution**, which is how bloaty does it. It needs debug
information, and razves' subject is a release binary where there is none (§1.7 Consequence 3).

Rejected: **prefix heuristics as the primary mechanism.** They stay as the labelled fallback for
symbols no archive claims, and the report says how many bytes were attributed each way, because a
heuristic whose share is invisible is a heuristic nobody audits.

Hypothesis, to check in M2: the archive index is enough to attribute the great majority of the
unmangled C bytes in the `shildik` release binaries. If it is not — if OpenSSL arrives through a
prebuilt klib that does not carry the archive — this decision degrades to the fallback and the
report says so honestly rather than inventing an owner.

### D5. Ambiguous packages are reported as ambiguous, not resolved by a tie-break

1.6% of packages are claimed by two modules (§1.5), and one of them, `org.koin.core`, is in the
subject binary.

Decision: when a package maps to more than one klib, its bytes go to an `<ambiguous: a, b>` row.
No "pick the first", no "pick the larger".

Why: a tie-break produces a number that is confidently wrong, and the whole value of this tool is
that its totals can be trusted. An ambiguous row is a visible, small, correct answer.

Escape hatch, deferred: `klib dump-metadata` lists actual declarations, so a full FQN → module map
is possible and would resolve every case. It costs a metadata dump per klib on every report. Not
worth it until an ambiguous row is big enough for someone to complain about; the backlog item
exists so the answer is one command away when they do.

### D6. The gate compares against a stored baseline; the report is a by-product

Brief: `binarySize { budget = 50.MiB; deltaPerChange = 3.percent }`.

Decision: keep both knobs, and add the thing that makes `deltaPerChange` mean anything — a
committed baseline file. A percentage delta needs something to be a delta *from*, and "the previous
build on this machine" is not it: a clean CI runner has no previous build, and a local one has a
different set of them.

Why:

- the same design as an API-compatibility dump: a checked-in file, a check that compares, and a
  task that rewrites it deliberately;
- it makes the diff readable in a pull request, which is where a 3% growth actually gets discussed;
- the price: one more generated file to keep current, and a merge conflict whenever two branches
  both move the number — the known cost of every baseline file, and cheaper than the alternative of
  a gate that cannot fire on a fresh runner.

### D7. The oracle is arithmetic first, and a second reader second

Three checks, in the order of how much they are worth:

1. **Reconciliation, on every run, in the report.** `allocated − NOBITS + non-allocated + headers =
   file size`, and `attributed + unattributed = allocated`. Verified to the byte on a real binary
   in §1.2 Consequence 3. `unattributed` is a printed line of the report, never a silent remainder.
2. **A synthetic binary with functions of known size**, compiled in the test suite, where the
   expected attribution is known by construction.
3. **Agreement with an independent reader on one file, in a tolerance, once, in tests** — not at
   runtime, and not as a build dependency. bloaty is the brief's suggestion and is a reasonable
   *test-time* oracle where it is installed; `llvm-nm` is the better one, because §1.1 established
   it is present on a developer mac even though it is absent from CI. The test skips itself with a
   named reason when neither is there.

Why 3 is last: an oracle that must be installed is an oracle that will be skipped, and a skipped
check that reports success is worse than no check. 1 runs everywhere, always, and would have caught
every arithmetic error made while writing this document.

### D8. One core, two front ends, and the core knows nothing about Gradle

`core` reads binaries and klibs and produces a report model. `gradle-plugin` supplies which binary
and which klibs, and owns the budget gate. `cli` supplies the same from arguments. This is the
shape `sborka` already uses — a plain JVM `core` module with no Gradle API on its classpath, plus
plugin modules around it — and it is what makes the core testable without a Gradle test kit.

Consequence of §1.5: the CLI is not restricted to package-level attribution. Given `--klibs <dir>`
it does module attribution as well; without it, packages only.

### D9. razves is its own repository; sborka calls it

Chosen over adding a `sborka.binary-size` convention. The gate belongs in `sborka` — one line in
`gradle.properties`, like every other convention there — but it will call a plugin published from
here. A tool whose CLI is a product needs its own name, its own README and its own release line;
folding it into the conventions plugin makes the CLI unshippable and ties its version to a
repository that bumps for unrelated reasons.

---

## 3. Risks and open questions

**Risk 1. The tool's input is the first thing a size-conscious build deletes.** `.symtab`+`.strtab`
is 19–21% of the file (§1.2), so anyone who takes size seriously strips the binary, and a stripped
binary tells razves nothing. Mitigation: the gate reads the unstripped artifact from the Gradle
build directory, before any packaging step, and the report states in its header whether the file
carried a symbol table; a stripped input fails with "this binary is stripped; point me at the link
output" instead of reporting a 100%-unattributed binary as if that were a finding.

**Risk 2. Inlined code is attributed to the caller, so the stdlib share is understated.** This is
the brief's own limitation and it is real: an inlined `kotlin.collections` helper has no symbol and
its bytes live inside the calling function. Mitigation: it goes in Limitations with a direction and
a rough magnitude rather than an apology — the bias is one-directional (stdlib under, application
over), it does not move totals, and therefore it does not affect the gate, which compares a binary
against itself over time. Do not attempt to correct for it.

**Risk 3. `.rodata` attribution is 40.7% and the missing part is strings.** Measured, not assumed
(§1.2). String literals are emitted without owning symbols, so "which module's messages cost a
megabyte" is a question razves cannot answer from the symbol table. Mitigation: state the coverage
percentage *per section* in every report, so a reader can see that a `.rodata` conclusion rests on
40% of that section and a `.text` conclusion rests on 98%. A number without its coverage is the
failure mode here.

**Risk 4. The Mach-O address-delta algorithm silently includes alignment padding.** 98.9% coverage
(§1.4) is partly real attribution and partly padding folded into whichever symbol precedes it.
Mitigation: compute and report the padding separately where two consecutive symbols are separated
by more than the section's alignment, and mark Mach-O totals in the report as address-derived.

**Risk 5. A gate that fires on an unrelated dependency bump gets disabled.** A 3% budget is
tripped by a Ktor patch release as easily as by a mistake. Mitigation: the failure message must
name the *rows that moved*, not just the total — "`io.ktor.client` +180 KB, `openssl` +1.2 MB"
turns a red build into a decision instead of a nuisance. This is the difference between the gate
being kept and being commented out, and it is the reason the diff feature is M1 and not M3.

**Open question 1.** Does the archive-membership map of [D4](#d4-attribute-c-symbols-by-static-archive-membership-not-by-name)
actually reach the OpenSSL bytes in `shildik`? The provider arrives as
`cryptography-provider-openssl3-prebuilt`, which may ship a prebuilt archive razves can read, or may
ship something already linked. Settled in M2 by running against the four subject binaries; the
answer decides whether the C bucket subdivides by library or stays one row.

**Open question 2.** Is the right unit of the gate the file size, the allocated size, or the sum of
attributed Kotlin? File size is what a user pays for and is the most jittery (it moves with the
symbol table). Allocated size is the most stable. Hypothesis: default to file size because it is
the number in the ticket, and let the budget DSL select another. Settled in M3, once there is a
week of real numbers from `shildik`, `booblik` and `telek`.

**Open question 3.** What does razves do with a binary whose target it cannot infer? Both readers
work from magic bytes, so the format is never in question — but the *module* map needs klibs for
the right target, and pointing the CLI at a `linuxX64` binary with `macosArm64` klibs would produce
a plausible, wrong report. Hypothesis: read `native_targets` from each klib manifest (§1.5 shows it
is there) and refuse the mismatch. Settled in M2.

---

## 4. What happens next

The order is forced by what everything else depends on. The two format readers and the
reconciliation oracle come first, because every later number is only as trustworthy as they are —
and because the oracle is what tells you the reader is wrong before a report does. Attribution
grammar second. The Gradle plugin and the budget gate third, on top of a core that is already
right. The article last, because it is a measurement and there is nothing to measure until the tool
exists.

The stages and the items are in [backlog.md](../../backlog.md).

## 5. Deviations from the brief, collected

Scattered above; gathered here because these are the entries most likely to be re-proposed.

| The brief said | What was found | Where |
|---|---|---|
| `llvm-nm`/`llvm-size`/`llvm-objdump` are in `~/.konan`, so the dependency is free | The macOS distribution is `llvm-*-essentials` and ships none of them; no `llvm-strip` in Xcode either | §1.1, [D1](#d1-read-the-symbol-tables-directly-no-subprocess-to-a-tool-the-toolchain-does-not-ship) |
| Wrap the tools first, write a reader later if needed | The reader is the only version that runs where Kotlin/Native builds | §1.1, [D1](#d1-read-the-symbol-tables-directly-no-subprocess-to-a-tool-the-toolchain-does-not-ship) |
| The question is how much stdlib, ktor and serialization weigh | Kotlin is 36–40% of a release binary; statically linked C is the majority | §1.2, [D3](#d3-non-kotlin-origins-are-first-class-buckets-not-other) |
| `konan::` is the C++ runtime bucket | `_ZN…` is mostly **Rust** legacy mangling; the genuine C++ runtime is 1.4% | §1.3 |
| `kfun:`, `kclass:`, `kvar:` plus a couple of others | Twelve prefixes, four of them Apple-only; the three named are half the Kotlin symbols | §1.3 |
| Parse `llvm-nm --size-sort` output | Sizes are always zero on Mach-O; Apple targets need an address-delta algorithm | §1.4 |
| Package → module needs Gradle; the CLI cannot do it | `klib info` carries the mapping, so the CLI can do it from a klib directory | §1.5, [D8](#d8-one-core-two-front-ends-and-the-core-knows-nothing-about-gradle) |
| Package → module is a mapping | 9 of 568 packages are claimed by two modules | §1.5, [D5](#d5-ambiguous-packages-are-reported-as-ambiguous-not-resolved-by-a-tie-break) |
| `shildik` is 46 MiB | The binaries on disk are 15.4–21.6 MB release and 39.8 MB debug. **Not reconciled.** The 46 MiB figure may name a container image, an older build, or a different target; it is not reproduced by any artifact in the repository as of 2026-09-11 | §1.2 |
| Compare against bloaty in tests | Kept, but as the *second* oracle — arithmetic reconciliation is the one that runs everywhere | [D7](#d7-the-oracle-is-arithmetic-first-and-a-second-reader-second) |
