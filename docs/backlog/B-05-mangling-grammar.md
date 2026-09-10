---
id: B-05
title: "The mangling grammar, with Rust tested before C++"
status: done
priority: P0
size: M
stage: stage-1-attribution
epic: feature-size-report
---

# B-05 — The mangling grammar, with Rust tested before C++

Twelve Kotlin prefixes appear in real binaries, not three:
`kfun: kclass: kifacevtable: kifacetable: krefs: kintf: kvar: kassociatedobjects:` everywhere, plus
`kexttype: kextoff: kextname: ktypew:` on Apple targets, and all of them carry a leading `_` on
Mach-O ([research §1.3](../research/research-architecture.md)). The three the brief names are half
the Kotlin symbols.

- **The decision and its reason.** One ordered classifier, and the order is load-bearing. Rust's
  *legacy* mangling emits `_ZN…` — the same prefix as Itanium C++ — and is distinguished only by
  the trailing `17h<16 hex digits>E`. Testing "`_Z` → C++" first charges about 964 KB of `tokio`,
  `sqlx_postgres` and `core::ptr` to the Kotlin/Native runtime, which in that binary is 14 KB. A
  fixture test pins the order.
- Rejected: demangling. razves needs the origin and the package, not a readable signature, and a
  demangler is a much larger surface to keep correct.
- Does **not** cover: C symbols, which have no scheme at all — see
  [B-09](B-09-c-attribution-by-archive.md).

- AC: on the Postgres release subject the C++ bucket contains no `tokio`, `sqlx_postgres` or
  `core::ptr` symbols, and the Kotlin/Native runtime is smaller than the Rust beside it.
- AC: all twelve prefixes are recognised, `_`-prefixed and not.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/attribute/Mangling.kt`,
  `core/src/commonTest/kotlin/io/github/youndie/razves/attribute/ManglingTest.kt`

**Done, and the number is better than the item guessed.** Split by the first component of the
Itanium nested name, the Kotlin/Native runtime in the Postgres release subject is **40,871 bytes**,
0.3% of the attributed bytes - and the same 40,871 to the byte in the SQLite build and in the CLI,
three differently-shaped programs carrying one runtime. The Rust beside it is 1,263,149 bytes.
Reading Rust's legacy mangling as C++ would have charged the runtime **31 times its real size**, and
the report would have named it the third-largest thing in the binary.

**The trap that was not in the item.** Undoing Mach-O's leading underscore by stripping any leading
`_` is the obvious version and it is wrong: an Itanium or Rust symbol is `_Z…`/`_R…` on ELF and
`__Z…`/`__R…` on Mach-O, so a blanket strip turns every ELF C++ symbol into `Z…` and files the whole
runtime under C. Six tests failed on the first run for exactly that, which is the cheapest way that
could have been found.

**Measured origin split of the four subjects** is in
[research §1.2](../research/research-architecture.md), re-measured with this grammar; the shares it
carried before are unchanged to a tenth of a percent, and the runtime now has a column of its own.
