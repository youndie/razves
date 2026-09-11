---
id: feature-size-report
title: Size report — what is in this binary and who put it there
type: feature
status: draft
owner: unassigned
involved_services:
  - core
  - gradle-plugin
  - cli
client_entries: []
api: []
tags: [attribution, elf, macho, klib]
---

# Size report — what is in this binary and who put it there

> `status: draft` — nothing below is implemented. Every number quoted as an example comes from the
> measurements in [research-architecture](../research/research-architecture.md) §1.2–§1.5, taken
> with Xcode's `llvm-nm`/`llvm-objdump` on binaries `shildik` had already built. The scenarios are
> **target** behaviour until the code exists.

## 1. Overview

Given a Kotlin/Native executable, razves says where its bytes went, in units a Kotlin developer can
act on. The output has three levels and the reader can stop at any of them:

1. **Reconciliation** — file size split into allocated sections, the symbol table, debug
   information, and headers. This is the level at which "why is it 20 MB" gets its first real
   answer, and in the measured subjects 19–21% of it was the symbol table alone.
2. **Origin** — the allocated bytes split by where the code came from: Kotlin, the Kotlin/Native
   C++ runtime, Rust, statically linked C, and the sections no symbol owns.
3. **Kotlin detail** — the Kotlin bucket split by package, and, when klibs are available, by the
   module each package came from.

Every level prints its own `unattributed` line. That line is a required part of the report, not an
error condition: [research §1.2](../research/research-architecture.md) measured 20.5% of the
allocated bytes of a real release binary belonging to no symbol, most of it unwind tables and
dynamic-linking metadata.

## 2. Business rules

* **The totals reconcile, to the byte, or the report fails.** `allocated − NOBITS + non-allocated +
  headers = file size`. `attributed + unattributed = allocated`. A report that cannot make these
  identities hold is a bug in razves, not a finding about the binary.
* **`unattributed` is always printed, never absorbed.** No row of the report may be rounded into
  another to make a total work.
* **Per-section coverage is printed next to every section conclusion.** Measured on the release
  subject: `.text` 97.6%, `.data.rel.ro` 94.8%, `.data` 97.2%, `.rodata` 40.7%, `.init_array` 20.0%. A reader who cannot see the difference will
  draw a `.rodata` conclusion with the confidence a `.text` conclusion deserves.
* **What is left out is named, not merely absent.** NOBITS sections cost no file bytes and get a
  reconciliation line saying so, because a reader who cannot find `.bss` should not have to infer
  why.
* **The algorithm that produced a number is named in the report header.** ELF sizes come from
  `st_size`; Mach-O sizes are derived by address delta and include trailing alignment padding.
  These are not interchangeable and the report says which one ran.
* **A stripped binary is refused, with the reason.** Not reported as 100% unattributed.
* **A package claimed by more than one module is reported as ambiguous.** No tie-break.
* **Module attribution requires klibs and never guesses.** Without them the report stops at
  package level and says so in the header.
* **A package name nothing declares folds up to the longest one that is declared.** The klib lists
  are the authority on which packages exist, and a grammar over mangled names cannot be: cinterop
  keeps a C struct's own name. The fold never invents — with no declared prefix, the derived name
  stands.

## 3. Flow

1. Read the binary's magic bytes; pick the ELF or the Mach-O reader.
2. Read the section table. Classify each section: allocated / NOBITS / non-allocated.
3. Read the symbol table. On ELF take `st_size`; on Mach-O sort defined symbols by address and take
   the delta to the next, clamped at the containing section's end.
4. Classify every symbol by mangling scheme into an origin bucket — Kotlin (twelve `k*:` prefixes,
   optionally `_`-prefixed), Rust (`_R…`, and `_ZN…` ending in `17h<hex>E`), C++ (remaining `_Z…`),
   C (everything else).
5. For Kotlin symbols, parse the package out of the mangled name.
6. If klibs were supplied, read `unique_name` and the package list from each and map package →
   module; packages claimed by two or more modules become one `<ambiguous>` row.
7. If static archives were supplied, map C symbols to the archive that defines them; anything left
   falls back to labelled prefix heuristics, and the report says how many bytes came each way.
8. Reconcile. Emit.

## 4. Code anchors

| Module | Code |
|---|---|
| core | `core/src/commonMain/kotlin/io/github/youndie/razves/read/` — the ELF and Mach-O readers |
| core | `core/src/commonMain/kotlin/io/github/youndie/razves/attribute/` — the mangling grammar and origin buckets |
| core | `core/src/commonMain/kotlin/io/github/youndie/razves/klib/` — `unique_name` and package list from a klib |
| core | `core/src/commonMain/kotlin/io/github/youndie/razves/report/` — the report model and reconciliation |
| gradle-plugin | `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/SizeReportTask.kt` |
| cli | `cli/src/commonMain/kotlin/io/github/youndie/razves/cli/Main.kt` |
| fixtures | `core/src/commonTest/resources/` — the synthetic binaries of known composition |

## 5. Scenarios (BDD / test cases)

### Scenario: the totals reconcile on a real ELF release binary
* **Given:** an unstripped `linuxX64` Kotlin/Native executable.
* **When:** razves reports on it.
* **Then:** `allocated − NOBITS + non-allocated + headers + padding` equals the file size exactly,
  with no tolerance.
* **And:** `attributed + unattributed` equals the allocated total exactly.
* **And:** on the `shildik` Postgres release subject specifically, that arithmetic is
  `16,213,972 + 27,696 NOBITS + 4,324,353 + 3,040 + 2,371 = 20,543,736` — the figures
  [research §1.2](../research/research-architecture.md) verified by hand before the reader existed.
* **Automated:** `RealBinaryTest.theMeasuredSubjectStillHasTheNumbersTheResearchRecorded` — it skips,
  by name, when the subject binary is not on the machine.

### Scenario: a lost or misread region fails instead of balancing
* **Given:** a reader that drops a section, misreads an offset, or lets two regions overlap.
* **When:** a report is constructed from it.
* **Then:** construction fails, naming the bytes nobody claims or the regions that collide.
* **And:** it does not silently absorb the difference into padding — the failure mode the first
  version of this arithmetic actually had.
* **Automated:** `ReconciliationTest.aReaderThatLosesASectionFailsAtConstruction`,
  `ReconciliationTest.sectionsThatOverlapInTheFileFail`

### Scenario: an alias is not charged twice
* **Given:** a section in which two symbols — a definition and an alias — cover the same bytes.
* **When:** razves attributes it.
* **Then:** the bytes are charged once, so the section is never more than 100% attributed.
* **And:** the same run twice produces the same owner, so a diff of a binary against itself is empty.
* **Automated:** `ReconciliationTest.overlappingSymbolsAreChargedOnce`

### Scenario: the totals reconcile on a Mach-O binary
* **Given:** a `macosArm64` Kotlin/Native executable.
* **When:** razves reports on it.
* **Then:** the same identities hold, with `unparsed` as an explicit term.
* **And:** the report states that sizes are address-derived.
* **And:** attributed coverage of `__TEXT,__text` is over 90%; measured 100% on the subject, because
  an address-delta charges every byte between two symbols to the earlier one.
* **And:** razves fails to account for less than 1% of the file; measured 0% — all 38 regions of the
  subject are named.
* **Automated:** `RealBinaryTest.theIdentitiesHoldOnARealMachOBinary` — skips, by name, when the
  subject is not on the machine.

### Scenario: a Mach-O symbol's size is the distance to the next one
* **Given:** two symbols in one section, 100 bytes apart, in a section that ends 200 bytes after the
  second.
* **When:** razves sizes them.
* **Then:** the first is 100 bytes and the second is 200 — clamped at the section's end rather than
  running into whatever follows.
* **Automated:** `MachOReaderTest.symbolSizesAreTheDistanceToTheNextSymbol`,
  `MachOReaderTest.theLastSymbolIsClampedAtTheEndOfItsSection`

### Scenario: a compiled binary attributes to the packages its source declares
* **Given:** a Kotlin/Native binary this repository compiled, declaring three packages with a public
  top-level function, an `internal` one and a class member in them.
* **When:** razves reports on it.
* **Then:** every declaration lands in the package it was written in, and no package row is named
  after a declaration.
* **And:** the `linuxX64` and `macosArm64` binaries — 5,352,944 and 1,221,280 bytes, built from the
  same source — produce identical package rows: `razvesfixture.alpha` 925, `razvesfixture.beta` 270,
  `razvesfixture.gamma.deep` 104, `razvesfixture` 156.
* **Automated:** `CompiledFixtureTest.theLinuxBinaryAttributesToThePackagesTheSourceDeclares`,
  `CompiledFixtureTest.theMacOsBinaryAttributesToTheSamePackages` — each skips, by name, on a host
  that cannot link its target.

### Scenario: two sections with the same name do not collide
* **Given:** a Mach-O binary carrying both `__TEXT,__const` and `__DATA_CONST,__const`.
* **When:** razves reads the section table.
* **Then:** the two are distinct entries keyed by `(segment, section)`, and their sizes are
  reported separately rather than one overwriting the other.
* **Automated:** `MachOReaderTest.twoSectionsNamedConstStayTwoSections`, and the real-binary check in
  `RealBinaryTest.theIdentitiesHoldOnARealMachOBinary`

### Scenario: Rust legacy mangling is not attributed to the Kotlin/Native runtime — *target*
* **Given:** a binary linking `sqlx4k`, whose Rust core is compiled with the legacy `_ZN…` scheme.
* **When:** razves classifies its symbols.
* **Then:** symbols ending in `17h<16 hex digits>E` land in the Rust bucket.
* **And:** the C++ bucket contains the Kotlin/Native runtime and no `tokio`, `sqlx_postgres` or
  `core::ptr` symbols — the misattribution [research §1.3](../research/research-architecture.md)
  measured at about 964 KB.

### Scenario: a stripped binary is recognised as stripped
* **Given:** a binary with no `.symtab`.
* **When:** razves reads it.
* **Then:** the image reports that it carries no symbol table, rather than an empty symbol list that
  reads as a binary containing nothing.
* **Automated:** `ElfReaderTest.aStrippedBinaryIsReadableAndSaysItHasNoSymbolTable`

### Scenario: a stripped binary is refused
* **Given:** a binary with no `.symtab`.
* **When:** razves is asked to report on it.
* **Then:** it fails, naming the binary as stripped and pointing at the link output.
* **And:** it does **not** emit a report showing 100% unattributed.
* **And:** section-level reconciliation still works on it — the refusal is about attribution, not
  about reading.
* **Automated:** `RefusalsTest.aStrippedBinaryIsRefusedAndNotReportedAsEmpty`,
  `RefusalsTest.theReconciliationStillWorksOnAStrippedBinary`

### Scenario: the Kotlin bytes are split by package
* **Given:** a binary carrying Kotlin symbols in several packages.
* **When:** razves reports on it.
* **Then:** the package rows sum to the Kotlin origin bucket exactly, and a report whose rows do not
  cannot be constructed.
* **And:** no row name carries a `$` or a capitalised segment — those are classes, not packages.
* **And:** the default depth of 3 produces a readable table; measured on the release subject,
  `ru.workinprogress.shildik` 991,047, `io.ktor.server` 443,998, `io.ktor.client` 358,921.
* **Automated:** `PackagesTest`, `RealBinaryTest.theKotlinBytesSplitByPackage`

### Scenario: a derived package is one that actually exists
* **Given:** the klibs that were linked, each carrying its own `package_<fqn>` list.
* **When:** every package razves derived is held against those lists.
* **Then:** at most 2% of the checkable Kotlin bytes name a package no klib declares. Measured 0.4%
  — 23 rows of 210, all of them declarations whose own name is lowercase.
* **Automated:** `RealBinaryTest.everyPackageAtFullDepthIsOneAKlibDeclares` — skips, by name, without
  a klib directory.

### Scenario: an ambiguous package is not silently assigned
* **Given:** a binary containing symbols of a package that two klibs both declare.
* **When:** razves attributes to modules.
* **Then:** those bytes appear in a row naming both modules, and in neither module's own row.
* **And:** measured on the release subject with a realistic link classpath — 40 resolved rows, 8
  ambiguous worth 606,570 bytes, 27 with no declaring klib worth 70,038.
* **Automated:** `PackageToModuleTest.aPackageTwoKlibsDeclareIsAmbiguousAndNotResolved`,
  `RealBinaryTest.theKotlinBytesSplitByModule`

### Scenario: the unmangled C bytes are placed by the archive that defines them
* **Given:** a binary and the cinterop klibs from its link classpath, which carry static archives.
* **When:** razves attributes the bytes that are not Kotlin.
* **Then:** each one gets a row naming the archive and the module that ships it, or a row saying no
  supplied archive defines it — never a guess from the name.
* **And:** a symbol two archives both define is reported as ambiguous, not assigned. Measured on the
  release subject: **2,366,786 bytes are defined by two different OpenSSL builds**, one from
  `cryptography-provider-openssl3-prebuilt` and one from `ktor-client-curl`.
* **And:** the rows sum to the non-Kotlin attributed bytes exactly.
* **And:** 88% of those bytes land in an archive, because razves reads the members rather than only
  the index — an index lists what an object *exports*, and static data tables have internal linkage.
  Measured on the release subject: unplaced falls from 4,572,896 bytes to 955,215, at 1.7% more time,
  because both modes have to inflate the archive out of the klib and that inflate dominates.
* **And:** what stays unplaced is named: `__unnamed_3673` at 142,096 bytes is generated by the linker
  for an anonymous object and is in no archive at all, and 175,682 bytes are the Kotlin/Native runtime
  and libc, which come from the compiler distribution rather than from a klib.
* **Automated:** `RealBinaryTest.theUnmangledCBytesAreAttributedToTheArchivesThatDefineThem`

### Scenario: a derived package folds up to one a klib declares
* **Given:** symbols in `platform.posix.addrinfo`, a cinterop struct class that kept its C name, and
  a klib declaring `platform.posix`.
* **When:** razves reports with those klibs supplied.
* **Then:** the bytes appear under `platform.posix`, and the module row resolves instead of saying no
  klib declares it.
* **And:** with no declared prefix the derived name stands — the fold never invents.
* **And:** measured on the release subject, folding takes the rows naming no declared package from
  23 worth 25,142 bytes to 3 worth 11,753, and the rows with no declaring module from 27 worth
  70,038 bytes to 7 worth 56,649.
* **Automated:** `FoldedPackagesTest`, `RealBinaryTest.everyPackageAtFullDepthIsOneAKlibDeclares`

### Scenario: razves reads a klib without a subprocess
* **Given:** a klib as a zip, and a klib unpacked as a directory.
* **When:** razves reads its `unique_name`, `native_targets` and package list.
* **Then:** both shapes produce the same answer, and no process is spawned — the manifest is
  inflated by razves' own DEFLATE.
* **And:** that decompressor agrees with `java.util.zip` byte for byte; measured over 8,642 entries
  and 87,025,540 bytes.
* **Automated:** `KlibTest`, `InflateOracleTest.everyEntryOfEveryKlibInflatesToWhatTheJvmSays`

### Scenario: the Gradle plugin attributes against the link classpath
* **Given:** a Kotlin/Native project with a dependency, and the razves plugin applied.
* **When:** `sizeReport<Binary>` runs.
* **Then:** the report describes the file the link task wrote, and its module rows name the klibs
  that link used.
* **And:** there are no ambiguous rows — measured on a project with one dependency: `stdlib`,
  `kotlinx-datetime`, `kotlinx-serialization-core`, and `<no klib declares subject>` for the
  application's own package. A directory sweep of a comparable tree produced 69 ambiguous rows worth
  3.5 MB of 5.1 MB of Kotlin.
* **And:** a second run is `UP-TO-DATE`, and the configuration cache is reused.
* **Automated:** `SizeReportTaskTest` — skips, by name, on a host with no linkable target.

### Scenario: klibs for the wrong target are refused, and only when they contradict
* **Given:** a `linuxX64` binary and klibs whose manifests declare `native_targets=macos_arm64`.
* **When:** razves is asked for module attribution.
* **Then:** it refuses, naming both sides.
* **And:** an ELF x86-64 binary against `android_x64` klibs is **accepted** — the container names the
  CPU and says nothing about the operating system, so razves names both possibilities rather than
  guessing one.
* **And:** a Mach-O with no `LC_BUILD_VERSION`, or klibs with no `native_targets`, refuses nothing:
  a check that cannot identify its subject must not veto it.
* **And:** measured on the real subjects — an ELF x86-64 is `linux_x64` or `android_x64`; a Mach-O
  with `cputype=0x0100000C` and `platform=1` is exactly `macos_arm64`.
* **Automated:** `RefusalsTest`, `RealBinaryTest.theIdentitiesHoldOnARealKotlinNativeBinary`,
  `RealBinaryTest.theIdentitiesHoldOnARealMachOBinary`

## 6. Out of scope

* DWARF. Debug information is 9 MB of a debug build and absent from a release one, which is the
  subject; compile-unit attribution is explicitly rejected in
  [research D4](../research/research-architecture.md).
* Attributing string literals in `.rodata` to an owner. There is no owner in the symbol table.
* Correcting for inlining. The bias is known and one-directional; see Quirks.
* Bitcode. `JetBrains-Research/bitcode-tools` covers that and it is a different question.
* Any format but ELF and Mach-O. No PE, no Wasm.

### Scenario: the report renders as text and as JSON, and the JSON round-trips
* **Given:** any report.
* **When:** razves renders it as JSON, parses it back, and renders it again.
* **Then:** the two strings are identical, byte for byte — the same file is what `--format json`
  prints and what the plugin commits as a baseline, so a format that reformats itself would produce
  a diff with no change in it.
* **And:** the document stores no derived number: coverage, percentages and shares are computed by
  the renderer, and the words do not appear in the file.
* **And:** the text header says which size algorithm produced the numbers, whether klibs were
  supplied, and whether package names were truncated.
* **And:** the module table is absent rather than empty when there are no klibs.
* **Automated:** `ReportRenderingTest`, `RealBinaryTest.theRenderedReportOfARealBinary`

### Scenario: the sections nobody owns are named one by one
* **Given:** a binary whose `.eh_frame` no symbol claims.
* **When:** razves reports on it.
* **Then:** `.eh_frame` is a row of its own with its size and 0% coverage, not part of a single
  "unattributed" figure.
* **And:** every unattributed byte is inside a named section row — the 22 unowned sections of the
  release subject plus the shortfall of the five owned ones add up to its `unattributed` exactly.
* **Automated:** `SizeReportTest.aSectionNoSymbolClaimsIsNamedRatherThanSummedAway`,
  `RealBinaryTest.theSectionsNobodyOwnsAreNamedOneByOne`

### Scenario: every attributed byte has exactly one origin
* **Given:** a binary containing Kotlin, Kotlin/Native runtime, Rust and plain C symbols.
* **When:** razves splits the attributed bytes by origin.
* **Then:** the rows sum to the attributed total exactly, and a report whose rows do not sum to it
  cannot be constructed.
* **And:** all five origins get a row, including the ones this binary has nothing in — a row that
  appears and disappears between two builds is noise in a diff.
* **Automated:** `SizeReportTest.everyAttributedByteLandsInExactlyOneOrigin`,
  `SizeReportTest.everyOriginGetsARowInTheEnumsOrderEvenWhenItIsEmpty`,
  `RealBinaryTest.theOriginSplitOfARealReleaseBinary`

## 7. Quirks

* **Inlined code is charged to the caller.** An inlined stdlib helper has no symbol of its own, so
  its bytes are inside whichever function inlined it. The stdlib share is therefore understated and
  the application share overstated. This does not move any total and therefore does not affect the
  gate, which compares a binary against itself over time.
* **Mach-O "sizes" include the padding after a symbol.** They are distances to the next symbol, not
  recorded sizes. ELF totals are slightly short where Mach-O totals are slightly generous, and the
  two platforms' numbers are not comparable to each other at byte precision.
* **The symbol table razves reads is 19–21% of the binary it is describing.** Measured on four
  subjects. It is simultaneously the tool's input and the largest single saving available, and
  removing it removes the tool's ability to see anything.
* **A lowercase declaration name reads as a package segment when no klibs are supplied.** cinterop
  keeps a C struct's own name, so `platform.posix.addrinfo` and
  `dev.whyoleg…internal.cinterop.ossl_param_st` appear as package rows. Measured at 0.4% of the
  checkable Kotlin bytes without klibs, and 0.2% with them — after folding, what remains is a klib
  nobody supplied rather than a name razves misread.
* **`.rodata` coverage is 40.7%.** Any conclusion about data size rests on less than half of the
  section, which is why coverage is printed per section.
* **The klib set must be the link classpath.** A directory sweep of a project's build tree picks up
  `kotlinTransformedMetadataLibraries/` copies of dependencies, whose `unique_name` is the source-set
  form — so the same library appears twice and every package it declares reads as ambiguous. Measured:
  69 ambiguous rows worth 3.5 MB against 8 worth 0.6 MB. Nothing in the report looks wrong when this
  happens, which is why the plugin supplies the classpath rather than a directory.
* **NOBITS sections have no owner at all.** `.bss` and `.tbss` are counted in the virtual size and
  excluded from attribution entirely, so a package with a megabyte of uninitialised state gets no
  row for it. That is correct while the budget is on file size, which
  [research §1.2b](../research/research-architecture.md) settled: NOBITS costs nothing to download,
  and counting it would put bytes in a total the user does not pay for. The report names the line
  rather than leaving the absence to be noticed. A budget set on `Measure.ALLOCATED` would reopen it
  — see [B-21](../backlog/B-21-attribute-nobits-sections.md).
