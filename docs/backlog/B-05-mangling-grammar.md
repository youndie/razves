---
id: B-05
title: "The mangling grammar, with Rust tested before C++"
status: open
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
  `core::ptr` symbols.
- AC: all twelve prefixes are recognised, `_`-prefixed and not.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/attribute/Mangling.kt`
