---
id: B-31
title: "Address to symbol, in core"
status: done
priority: P1
size: S
stage: stage-4-profiler
epic: research-profiler
---

# B-31 — Address to symbol, in core

`BinaryImage.symbols` is a list; a profiler needs `at(address)`. There is no such function anywhere
in `core` today - the attribution sweep walks symbols against sections and never the other way
([research §1.8](../research/research-profiler.md)).

- **The decision and its reason.** It belongs in `core`, beside the reader that produced the
  symbols, because the size report wants the same lookup the moment anyone asks "what is at this
  address" - and because a second copy of it in a profiler module would drift from the folding and
  ambiguity rules the report already has.
- Sorted array plus binary search over the symbols that have a size, with the address range checked
  rather than the nearest name returned: a hit past the end of a symbol is a miss, and a miss is an
  answer.
- Does **not** cover: inline frames. A single address maps to the function that owns it; inlined code
  is charged to the caller here exactly as it is in the size report, and for the same reason.

- AC: an address inside a symbol returns it; an address in the gap between two symbols returns
  nothing rather than the one before it. **Verified in `SymbolIndexTest`.**
- AC: the first and last symbol of a section are both reachable - the off-by-one this shape always
  has. **Verified, and by walking every byte of a three-symbol section rather than by picking
  boundaries to check: 400 lookups, each compared against the extent the sweep produced.**
- AC: measured on a real binary, the share of a real profile's addresses that fall in no symbol is
  reported rather than assumed. **Measured on the release binary of a real service: 54,869 owned
  ranges, and of 173,872 addresses walked across `.text` at a prime stride, 4,079 - 2.3% - have no
  symbol at all.**
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/SymbolIndex.kt`,
  `core/src/commonTest/kotlin/io/github/youndie/razves/report/SymbolIndexTest.kt`

**It is built from the reconciliation, not from the symbol table, and that is the whole design.**
The sweep already decides who owns a byte - earliest start wins, and on a tie the larger symbol, so
that an alias does not steal the bytes of the definition it aliases. An index built from the raw
table would answer differently for exactly those addresses, and a profile and a size report of the
same binary would then name different functions for the same bytes with nothing to say which was
right. So `SymbolExtent` now carries the start of the range it owns, and both answers come from it.

**2.3% is the number to hold onto.** It is what a profile of that binary will report as having no
owner, before any question of what razves can name - and it is the reason the aggregation in
[B-32](B-32-aggregate-samples.md) must have a row for addresses nothing covers rather than dropping
them.

**What the run also found, and it is not this item's defect.** Three klib-dependent tests in
`RealBinaryTest` fail on a mac when handed the macOS Kotlin/Native distribution as the klib
directory: the subject is a `linuxX64` binary, razves filters klibs by target as it should, and
almost nothing resolves. Verified as pre-existing by stashing this change and running the same test.
The klib set for a Linux subject has to be a Linux one.
