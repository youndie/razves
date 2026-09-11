---
id: B-31
title: "Address to symbol, in core"
status: open
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
  nothing rather than the one before it.
- AC: the first and last symbol of a section are both reachable - the off-by-one this shape always
  has.
- AC: measured on a real binary, the share of a real profile's addresses that fall in no symbol is
  reported rather than assumed.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/read/Binary.kt`
