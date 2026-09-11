---
id: B-20
title: "Decide what the budget is measured on"
status: done
priority: P2
size: XS
stage: stage-2-gradle
epic: feature-size-budget-gate
blocked_by: [B-16]
---

# B-20 — Decide what the budget is measured on

File size, allocated size, or the sum of attributed Kotlin. Three defensible answers
([research open question 2](../research/research-architecture.md)).

- File size is what a user pays for and what ends up in a ticket. It is also the jumpiest: it moves
  with the symbol table, which is 19–21% of it and grows with every symbol name added.
- Allocated size is the most stable and is not what anyone downloads.
- Attributed Kotlin is the only one that isolates what the team actually controls, and it ignores
  the dependency bump that doubled the binary.

- **The decision and its reason.** File size, and the reason is below rather than in the hypothesis
  this item was written around.
- **It was `question` because the answer is data, not opinion.** That was right; what was wrong was
  which data. "How much does each measure jitter without a code change" has the answer "not at all",
  for all three.
- Does **not** cover: supporting all three forever. Whichever loses can still be dropped.

- AC: the research document records the observed behaviour of each measure. **Done: §1.2b.**
- AC: the DSL default is set from that, with the reason written down. **`Measure.FILE_SIZE`.**
- Anchors: `docs/research/research-architecture.md` §1.2b,
  `core/src/commonMain/kotlin/io/github/youndie/razves/report/Budget.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/BinarySizeExtension.kt`

**Done, and the question as written could not have been answered.** It asked which measure jitters
least and expected a week of reports to show it. Two experiments on this repository's own fixture
settled it in an afternoon, and neither answered the question as posed.

**Three clean relinks of unchanged source give byte-identical numbers on all four measures.** A
Kotlin/Native link is reproducible, so "least jittery" does not choose between them. The axis was
wrong.

**What separates them is which changes they respond to.** A pure rename - every identifier and
package in the fixture lengthened, nothing else touched - moves file size by **+224 B**, allocated by
**+96 B**, metadata by **+126 B**, and attributed Kotlin by **0**.

* Only attributed Kotlin is rename-proof, because it counts the bytes symbols own rather than the
  bytes their names take. That is also its disqualification: it is blind to the thing that actually
  makes these binaries big, and OpenSSL and a Rust driver are 59% of `shildik`'s attributed bytes.
* **Allocated is not rename-proof either**, which was the surprise. It moved 96 bytes with no code
  change, because type names travel into `.rodata` and `.data.rel.ro` as strings. The intuition that
  stripping the symbol table makes a measure name-independent is simply wrong.
* The objection to file size is now quantified and it is small: **224 bytes, 0.045%**, one
  sixty-sixth of a 3% budget.

So **file size**, on evidence rather than on the hypothesis the item recorded: the only measure that
is what a user downloads, the only one that responds to everything that makes a binary bigger, and
its one disadvantage is two hundred bytes.
