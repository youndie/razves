---
id: B-09
title: "Attribute C symbols to the static archive that defines them"
status: done
priority: P2
size: L
stage: stage-1-attribution
epic: feature-size-report
---

# B-09 — Attribute C symbols to the static archive that defines them

26.7% of the attributed bytes of the Postgres release subject carry names with no mangling scheme
at all — `ecp_nistz256_precomputed`, `nid_objs`, `sha1_multi_block`, `huff_decode_table`. The C ABI
has no namespaces, so no grammar recovers their origin
([research §1.3](../research/research-architecture.md)). Prefix lists catch 16.1% and go stale with
every dependency bump.

- **The decision and its reason.** Build a `symbol → archive` map by reading each linked `.a`'s
  member index and the defined symbols of each member, then key by exact name. The archives are on
  disk: cinterop klibs ship them and `klib info` names the artifact that carries each one.
- Rejected: **DWARF compile-unit attribution**, which is how bloaty does it. It needs debug
  information, and the subject is a release binary where there is none
  ([research D4](../research/research-architecture.md)).
- Rejected as the *primary* mechanism: prefix heuristics. They stay as a labelled fallback, and the
  report says how many bytes were attributed each way — a heuristic whose share is invisible is a
  heuristic nobody audits.
- Does **not** cover: symbols no archive claims. Those stay in a named fallback bucket.

- **It was `question` because the answer was data.** It is now data: the archives are there, they
  are readable, and they place 41% of the non-Kotlin bytes. The fallback the item feared - "the C
  bucket stays one row" - was not needed.

- AC: the share of non-Kotlin bytes attributed by archive is measured and written down. **Done.**
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/klib/Archive.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/klib/PackageToModule.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/report/Attribution.kt`

**The open question is answered: the archives are right there.** A cinterop klib carries them under
`default/targets/<target>`, and `cryptography-provider-openssl3-prebuilt` ships a 12,737,852-byte
`libcrypto.a` that way. Eleven were read off `shildik`'s link classpath.

**Reading the index, not the members.** A GNU `ar` writes a first member holding every symbol its
objects define, sorted. `libcrypto.a` declares 9,023 of them in 251,116 bytes; parsing every object
inside 12 MB to learn the same thing would cost thousands of times more.

**Measured on the Postgres release subject: 7,753,631 non-Kotlin bytes, of which 3,178,771 (41%)
land in an archive.** The split is the interesting part:

| | bytes | |
|---|---|---|
| `<ambiguous: libcrypto.a (cryptography-provider-openssl3-prebuilt), libcrypto.a (ktor-client-curl)>` | 2,366,786 | **two OpenSSL builds define the same symbols** |
| `libssl.a`, `libcurl.a`, `libnghttp2.a`, `libsqlx4k_postgres.a`, `libcrypto.a` (curl's) | 811,985 | resolved to exactly one archive |
| no supplied archive defines it | 4,572,896 | see below |

**The duplicate OpenSSL is the finding.** 2,366,786 bytes - 11.5% of the whole binary - are defined
by *both* providers, and razves refuses to pick one, the same way it refuses an ambiguous package.
That is not a defect in the attribution; it is a fact about the binary that nothing else was saying.

**And the 59% has a precise cause rather than a shrug.** An `ar` index lists only what an object
**exports**. By origin, the unplaced bytes are C 3,134,081, Rust 1,263,133, C++ 134,811 and
Kotlin/Native runtime 40,871 - and the largest are `__unnamed_3673` (142,096), `nid_objs` (60,040),
`k25519Precomp` (30,720): static data tables with internal linkage, physically inside the archives
razves read and absent from their indexes. The Rust is the same story - its symbols are local to
their objects. Reading each member's own symbol table would place them, at the cost the index was
chosen to avoid: [B-26](B-26-archive-members.md).
