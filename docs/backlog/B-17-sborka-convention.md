---
id: B-17
title: "A sborka convention that applies the gate from one property"
status: done
priority: P2
size: S
stage: stage-3-subjects
epic: feature-size-budget-gate
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

- AC: a repository adds one property and gets `sizeBudgetCheck` in its `check`. **Shipped as one
  property and one plugin line, and the difference is the point — see below. Verified in sborka's
  stand: `sborka.binaryBudget=50MiB` produced a verdict reading `file size 1,219,712, 51,209,088
  under a budget of 52,428,800`.**
- AC: repositories that do not set the property are unaffected — no task registered, no cost.
  **Verified, and now true by construction: sborka declares razves `compileOnly`, so a repository
  that does not apply razves does not have it on any classpath at all.**
- Anchors: `sborka/build-logic/` — the convention lives there, not here

**The AC said one property; what shipped is one property and one plugin line.** `implementation` in
the conventions would have bought the single property at the price of putting razves' plugin jar
(47,731 bytes), its `core` (211,901) and kotlinx-serialization onto the build classpath of **every**
repository taking **any** sborka convention — including the ones that ship no binary — and of pinning
a snapshot line that publishes a version per push. sborka already refuses that trade for the Kotlin
plugin, in as many words: it configures Kotlin, it does not choose its version. The gate is the same
shape: the repository applies razves at the version it wants, and the convention reacts with
`plugins.withId`.

**A budget set on a project that does not apply razves fails configuration**, naming the line to add.
A property nobody reads is worse than no property: the build would stay green forever while
measuring nothing.

**Three things came out of being the first consumer.**

* **`sborka.native-service` had never been applied anywhere.** The stand module written for this item
  is its first user, and it immediately hit an ordering the convention does not state: the entry
  point is read the moment a target declares a binary, so `nativeService { }` has to come before
  `kotlin { }` or the build fails with "property entryPoint has no value available", naming neither
  block.
* **Two modules loading the Kotlin plugin in separate classloader scopes cannot share its native
  build service** — the second one to declare a native target fails naming a build service class and
  neither module. The stand's root now carries the plugin with `apply false`.
* **The gate passes when there is no budget at all**, so a stand that only checked "the task ran"
  would pass a run in which the property never arrived. What the stand checks is the verdict razves
  wrote, against the number `gradle.properties` set.

**What it costs the conventions repository**, measured cold on one host: 43 seconds for the stand
module's whole `check`, which links both the debug and the release executable.
