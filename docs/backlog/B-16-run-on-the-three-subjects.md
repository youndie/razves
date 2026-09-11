---
id: B-16
title: "Report on shildik, booblik and telek, and write the article from the numbers"
status: done
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

**Corrected on doing it.** `telek` has no native executable - it is a library, and its only
`linuxX64` binaries are test runners. The three subjects that exist are `shildik` (four binaries),
`booblik` (a conformance client) and this repository's own two, which turned out to be the most
useful of the set: the fixture is as close to a floor as a Kotlin/Native binary gets.

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

- AC: the "46 MiB" figure of [B-18](B-18-reconcile-the-46-mib-claim.md) is either reproduced or
  retired before the article quotes anything. **Done: it names a container image.**
- AC: the measurements land in the research with their provenance - which binary, which Kotlin
  version, which date. **Done: §1.2a.**
- Anchors: `docs/research/research-architecture.md` - §1.2 and §1.2a are where the numbers land

**Done, and the article has a spine it did not have before.** Six binaries, all Kotlin 2.4.10, all
`linuxX64`, all measured by razves itself on 2026-09-11:

**The Kotlin/Native runtime is 37,889 to 40,871 bytes across all six — under 3 KB of spread over a
43x range of file size.** Its share of the attributed bytes falls from 17.6% to 0.2%. It is a fixed
cost, it does not grow with the program, and "the runtime is 17.6% of this binary" means "this binary
does almost nothing" rather than "the runtime is heavy".

**The floor is 496,232 bytes** - this repository's own fixture, one `println` and one dependency, of
which 135,032 is its symbol table. Half a megabyte before a line of business logic, and a tool that
reports a 700 KB binary without saying that most of it is unavoidable invites the wrong conclusion.

**Symbol tables are 19% to 27% of the file in every one of the six.**

**`telek` is not a subject and the brief's premise was wrong about it.** It declares no native
executable at all - it is a library of router and transport modules whose only `linuxX64` binaries
are test runners. A library has no binary to attribute.

**The other half of this item is split out as [B-23](B-23-size-lines-in-the-subjects.md):** a
generated size line in each subject's own README is work in other repositories, with their own gates,
and should be a deliberate change there rather than a side effect of an iteration here.
