---
id: B-01
title: "Read ELF section headers and .symtab without a subprocess"
status: open
priority: P0
size: M
stage: stage-0-readers
epic: feature-size-report
---

# B-01 — Read ELF section headers and `.symtab` without a subprocess

The brief priced this as free: shell out to `llvm-nm` and `llvm-size`, which "are in `~/.konan`".
They are not. The Kotlin/Native 2.4.10 macOS distribution is `llvm-21-aarch64-macos-essentials-97`
and its `bin/` holds nine entries, none of which reads a binary
([research §1.1](../research/research-architecture.md)). On this machine the readers came from
Xcode; a Linux CI runner with a JDK has neither.

- **The decision and its reason.** Parse ELF directly, because it is the only version that runs
  where Kotlin/Native builds. Needed: the section header table (name, address, size, type, so
  NOBITS is distinguishable), `.symtab` and `.strtab`. `st_size` is in the symbol entry, so ELF
  needs no size derivation.
- Rejected: wrapping `llvm-nm`. Two failure modes instead of one — the tool being absent, and its
  column layout differing between versions.
- Does **not** cover: relocations, DWARF, program headers beyond what the file-size reconciliation
  needs, or any format but ELF64.

- AC: pointed at `shildik/distribution/build/bin/linuxX64/releaseExecutable/shildik.kexe`, it
  produces 36 sections and 58,164 symbols, of which 57,905 carry an address and a size — the counts
  [research §1.2](../research/research-architecture.md) measured through `llvm-nm`.
- AC: no process is spawned. A test asserts that.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/read/Elf.kt`
