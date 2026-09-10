---
id: B-20
title: "Decide what the budget is measured on"
status: question
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

- **The decision and its reason.** Hypothesis: default to file size, because a gate should fire on
  the number in the argument, and let the DSL select another. It is a hypothesis and not a decision
  until there are real numbers.
- **This is `question` because the answer is data, not opinion.** Blocked by
  [B-16](B-16-run-on-the-three-subjects.md): a week of reports from three repositories will show how
  much each measure jitters without anyone changing code, and the least jittery defensible one wins.
- Does **not** cover: supporting all three forever. Whichever loses can still be dropped.

- AC: the research document records the observed jitter of each measure over the three subjects.
- AC: the DSL default is set from that, with the reason written down.
- Anchors: `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/BinarySizeExtension.kt`
