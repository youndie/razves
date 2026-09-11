---
id: B-26
title: "Place the symbols an archive index cannot name"
status: open
priority: P3
size: M
stage: stage-1-attribution
epic: feature-size-report
blocked_by: [B-09]
---

# B-26 — Place the symbols an archive index cannot name

[B-09](B-09-c-attribution-by-archive.md) reads the `ar` symbol index and places 41% of the non-Kotlin
bytes of the release subject. The other 59% has a precise cause: **an index lists only what an object
exports**, and the largest unplaced symbols are static data tables with internal linkage —
`__unnamed_3673` at 142,096 bytes, `nid_objs` at 60,040, `k25519Precomp` at 30,720 — physically
inside the archives razves already read and absent from their indexes. Rust is the same story: its
symbols are local to their objects, and 1,263,133 bytes of it go unplaced for that reason.

- **The decision and its reason.** Read each member object's own symbol table instead of the
  archive's index. The members are ELF, and razves already has an ELF reader — the work is iterating
  the archive rather than parsing a new format.
- **The cost is what the index was chosen to avoid.** `libcrypto.a` is 12,737,852 bytes and its index
  is 251,116: reading the members is roughly fifty times the bytes, on every report, for every
  archive on the classpath. That is a nightly-job cost, not a per-build one.
- So it should be **opt-in**, the same way [B-19](B-19-full-fqn-module-map.md) is: the report says how
  much it could not place, and a flag buys the rest.
- Rejected: guessing from the name. `nid_objs` says nothing about which library it is in, which is
  the whole reason this layer exists.
- Does **not** cover: the Kotlin/Native runtime's own C++ and the libc it links. Those come from the
  distribution rather than from a klib, and no klib on the classpath carries them — 175,682 bytes on
  the measured subject, and a separate question about where to look.

- AC: with the flag on, the unplaced share of the release subject falls below 10% of its non-Kotlin
  bytes, and the cost of the flag is measured and written down.
- AC: with the flag off, behaviour and timing are unchanged.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/klib/Archive.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/read/ElfReader.kt`
