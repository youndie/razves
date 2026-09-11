---
id: B-17
title: "A sborka convention that applies the gate from one property"
status: open
priority: P2
size: S
stage: stage-3-subjects
epic: feature-size-budget-gate
blocked_by: [B-25]
---

# B-17 — A sborka convention that applies the gate from one property

`sborka` is where this portfolio's repositories get their conventions in one line of
`gradle.properties` instead of dozens of lines of Kotlin. The size gate belongs there; the tool
does not.

**Re-blocked when it came up.** The item assumed a `sborka` convention could apply
`io.github.youndie.razves` as soon as the gate existed. It cannot: a convention plugin that applies
another plugin needs that plugin resolvable on the build classpath of every repository taking the
convention, and **razves had never been published anywhere**. [B-24](B-24-consumable-by-coordinate.md)
made it publishable and proved a project can apply it by id;
[B-25](B-25-publish-razves.md) is the remote, and this waits on that rather than on the gate.

- **The decision and its reason.** razves stays its own repository and `sborka` calls it
  ([research D9](../research/research-architecture.md)). A tool whose CLI is a product needs its
  own name, README and release line; folding it into the conventions plugin makes the CLI
  unshippable and ties its version to a repository that bumps for unrelated reasons.
- The convention applies the razves plugin to native-service projects and reads the budget from a
  property, in the shape `sborka.native-service` already uses.
- Does **not** cover: turning the gate on by default anywhere. A gate that arrives unannounced with
  a dependency bump is a gate people disable before they read it.

- AC: a repository adds one property and gets `sizeBudgetCheck` in its `check`.
- AC: repositories that do not set the property are unaffected — no task registered, no cost.
- Anchors: `sborka/build-logic/` — the convention lives there, not here
