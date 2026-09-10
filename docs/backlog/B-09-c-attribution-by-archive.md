---
id: B-09
title: "Attribute C symbols to the static archive that defines them"
status: question
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

- **Status is `question` on purpose.** [Research open question 1](../research/research-architecture.md)
  is unanswered: OpenSSL reaches these binaries through
  `cryptography-provider-openssl3-prebuilt`, which may ship a readable archive or something already
  linked. Settle that first; if the archives are not there, this item degrades to the fallback and
  the C bucket stays one row.

- AC: on the four `shildik` subjects, the share of C bytes attributed by archive rather than by
  heuristic is measured and written into the research document.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/attribute/Archives.kt`
