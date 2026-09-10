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
* **Per-section coverage is printed next to every section conclusion.** `.text` attribution is
  worth ~98%; `.rodata` attribution is worth ~41%. A reader who cannot see the difference will
  draw a `.rodata` conclusion with the confidence a `.text` conclusion deserves.
* **The algorithm that produced a number is named in the report header.** ELF sizes come from
  `st_size`; Mach-O sizes are derived by address delta and include trailing alignment padding.
  These are not interchangeable and the report says which one ran.
* **A stripped binary is refused, with the reason.** Not reported as 100% unattributed.
* **A package claimed by more than one module is reported as ambiguous.** No tie-break.
* **Module attribution requires klibs and never guesses.** Without them the report stops at
  package level and says so in the header.

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

### Scenario: a synthetic binary attributes to the byte — *target*
* **Given:** a binary compiled by the test suite from Kotlin sources whose functions have known,
  distinct sizes, in known packages.
* **When:** razves reports on it.
* **Then:** each package's attributed total equals the sum of its functions' sizes, exactly.

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

### Scenario: a stripped binary is refused — *target*
* **Given:** a binary with no `.symtab`.
* **When:** razves is pointed at it.
* **Then:** it fails, naming the binary as stripped and pointing at the link output.
* **And:** it does **not** emit a report showing 100% unattributed.

### Scenario: an ambiguous package is not silently assigned — *target*
* **Given:** a binary containing `org.koin.core` symbols and a klib set in which both
  `io.insert-koin:koin-core` and `io.insert-koin:koin-ktor` declare that package.
* **When:** razves attributes to modules.
* **Then:** those bytes appear in a row naming both modules, and in neither module's own row.

### Scenario: klibs for the wrong target are refused — *target*
* **Given:** a `linuxX64` binary and a klib directory whose manifests declare `native_targets=macos_arm64`.
* **When:** razves is asked for module attribution.
* **Then:** it refuses, naming the binary's target and the klibs' target.

## 6. Out of scope

* DWARF. Debug information is 9 MB of a debug build and absent from a release one, which is the
  subject; compile-unit attribution is explicitly rejected in
  [research D4](../research/research-architecture.md).
* Attributing string literals in `.rodata` to an owner. There is no owner in the symbol table.
* Correcting for inlining. The bias is known and one-directional; see Quirks.
* Bitcode. `JetBrains-Research/bitcode-tools` covers that and it is a different question.
* Any format but ELF and Mach-O. No PE, no Wasm.

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
* **`.rodata` coverage is about 41%.** Any conclusion about data size rests on less than half of
  the section, which is why coverage is printed per section.
