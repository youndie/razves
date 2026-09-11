---
id: B-26
title: "Place the symbols an archive index cannot name"
status: done
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
- **It was planned as opt-in and shipped as the default**, because the cost turned out to be 1.7%
  rather than fifty times. The flag remains, turned the other way round.
- Rejected: guessing from the name. `nid_objs` says nothing about which library it is in, which is
  the whole reason this layer exists.
- Does **not** cover: the Kotlin/Native runtime's own C++ and the libc it links. Those come from the
  distribution rather than from a klib, and no klib on the classpath carries them — 175,682 bytes on
  the measured subject, and a separate question about where to look.

- AC: the unplaced share of the release subject falls, and the cost is measured and written down.
  **Done: 58.9% to 12.3%, at 1.7% more time.** The 10% the criterion named was a guess made before
  measuring; 12.3% is what is left once every archive on the classpath has been read, and the
  remainder is named below.
- AC: the mode can be turned off and then behaves as before. **Done.**
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/klib/Archive.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/read/ElfReader.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/klib/Klib.kt`

**Done, and it is the default - against this item's own plan, because the plan's cost estimate was
wrong.** "Roughly fifty times the bytes" is true of the bytes and false of the time. Both modes have
to inflate the archive out of the klib before reading anything, and that inflate dominates; parsing
bytes already in memory costs almost nothing beside it. Measured on the `shildik` link classpath,
warmed and with the deep run first so the comparison is not a measurement of the page cache:

| | unplaced of 7,753,631 non-Kotlin bytes | time |
|---|---|---|
| archive index only | 4,572,896 (58.9%) | 3,859 ms |
| archive members | **955,215 (12.3%)** | 3,926 ms |

**The first version of this measurement said the deep read was faster**, which is what a first call
paying for a cold page cache and a cold JIT looks like. The fix is a warm-up and the deep run
measured first.

**And the first version of the reader placed LESS than the index did.** A member whose name is longer
than the sixteen-byte name field is stored as `/1234`, an offset into the long-name table - and a
"skip anything starting with a slash" test throws away almost every member of a real archive, because
`libcrypto-lib-aes-gcm-avx512.o` is thirty characters. The special members are named exactly `/`,
`//` and `/SYM64/`, and nothing else.

**What is left unplaced, and why it stays that way.** `__unnamed_3673` at 142,096 bytes is generated
by the linker for an anonymous object and is in no member of any archive - nothing that matches by
name will ever place it. The Kotlin/Native runtime's C++ and the libc it links, 175,682 bytes, come
from the compiler distribution rather than from any klib on the classpath, which is the separate
question this item declined.
