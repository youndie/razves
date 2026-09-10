---
id: B-07
title: "Aggregate Kotlin symbols by package"
status: open
priority: P1
size: S
stage: stage-1-attribution
epic: feature-size-report
---

# B-07 — Aggregate Kotlin symbols by package

The payload of the whole tool: `kfun:io.ktor.server.engine#embeddedServer(...)` becomes a row
called `io.ktor.server`.

- **The decision and its reason.** Parse the package out of the mangled name — everything before
  the first `#` or `(`, then the leading lowercase-initial segments — and aggregate at a
  configurable depth. Depth matters: at depth 3 the Postgres release subject splits into
  `ru.workinprogress.shildik` 939,503 B, `io.ktor.server` 417,230 B, `io.ktor.client` 335,625 B,
  `kotlin.text.regex` 248,294 B and so on, which is a table a person reads; at depth 1 it is four
  rows and at full depth it is hundreds.
- Rejected: guessing the class/function boundary by anything other than the capital letter. The
  mangling is regular enough not to need heuristics, and [B-04](B-04-synthetic-fixtures.md) is what
  proves it.
- Does **not** cover: the module a package came from — [B-08](B-08-klib-package-to-module.md).

- AC: on the Postgres release subject, summing the package rows equals the Kotlin origin bucket
  exactly.
- AC: depth is a parameter, and the default produces a table that fits on a screen.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/attribute/Packages.kt`
