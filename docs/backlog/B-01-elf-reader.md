---
id: B-01
title: "Read ELF section headers and .symtab without a subprocess"
status: done
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
  reproduces the section figures [research §1.2](../research/research-architecture.md) measured
  through `llvm-nm` and `llvm-objdump`: 16,213,972 allocated file bytes, 27,696 NOBITS, 4,324,353
  non-allocated, 3,040 of container headers and 2,371 of padding - 20,543,736 exactly.
- AC: no process is spawned. There is nothing in the module that could spawn one.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/read/ElfReader.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/read/Bytes.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/read/Binary.kt`

**Done.** Twelve tests over a hand-built ELF fixture
(`core/src/commonTest/kotlin/io/github/youndie/razves/fixture/ElfBuilder.kt`) and two against the
real subject (`core/src/jvmTest/kotlin/io/github/youndie/razves/read/RealBinaryTest.kt`), the second
pinned to the figures the research recorded and skipping by name when the binary is absent.

**What it cost that the item did not predict:** the symbol table needs three exclusions nobody thinks
of - `STT_SECTION` entries label a whole section and would double every byte in it, `STT_FILE`
entries name a source file, and `SHN_ABS` and above are not sections at all.
