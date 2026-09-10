---
id: B-16
title: "Report on shildik, booblik and telek, and write the article from the numbers"
status: open
priority: P2
size: M
stage: stage-3-subjects
epic: feature-size-report
blocked_by: [B-10]
---

# B-16 — Report on shildik, booblik and telek, and write the article from the numbers

Three subjects with different structure: `shildik` links OpenSSL and a Rust database driver,
`telek` is a Telegram router, `booblik` is different again. Each one's page gets a line saying what
its binary is made of.

- **The decision and its reason.** The article writes itself from the output, and its headline is
  not the one the brief predicted. Measured, Kotlin is 36–40% of a release binary and statically
  linked C is the majority ([research §1.2](../research/research-architecture.md)); the symbol
  table alone is 19–21%. "What is in 20 MB: a fifth of it is symbol names, half of it is OpenSSL,
  and a third of it is your code" is a better article than the one about stdlib and ktor, and it is
  true.
- **Every number carries what was measured and how**: which binary, which Kotlin version, which
  flags, which date. A figure in a README with no provenance stops being a measurement the first
  time anything changes underneath it.
- Does **not** cover: the flag comparison table. That needs a build per flag and belongs with
  [B-13](B-13-baseline-and-diff.md)'s diff — same mechanism, separate work.

- AC: each of the three repositories gets a size line, generated rather than typed, with a date.
- AC: the "46 MiB" figure of [B-18](B-18-reconcile-the-46-mib-claim.md) is either reproduced or
  retired before the article quotes anything.
- Anchors: `docs/research/research-architecture.md` — §1.2 is where the numbers land
