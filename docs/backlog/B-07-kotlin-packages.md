---
id: B-07
title: "Aggregate Kotlin symbols by package"
status: done
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
  exactly. It is a constructor invariant of the report, the fourth one.
- AC: depth is a parameter, and the default of 3 produces a table that fits on a screen.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/attribute/Packages.kt`,
  `core/src/commonTest/kotlin/io/github/youndie/razves/attribute/PackagesTest.kt`

**Done, and the grammar was harder than one line.** Kotlin/Native emits **two forms** that look
alike and are not:

```
kfun:dev.whyoleg.cryptography.providers.base#checkBounds(kotlin.Int){}
kfun:dev.whyoleg.cryptography.bigint.removeLeadingZeros#internal
```

Both are top-level functions. In the first the member name sits after the `#`; in the second - what
every `internal` or `private` declaration gets - the member name is the last segment *of the
container* and there is no signature at all. A rule that handles only the first reports
`dev.whyoleg.cryptography.bigint.removeLeadingZeros` as a package, and does it for thousands of
symbols. What separates them is the signature: a qualifier carrying a `(` puts the member after the
hash, one without it puts the member in the container.

**An oracle, and it earned its place immediately.** Every klib carries its own package list as
`default/linkdata/package_<fqn>`, so a package razves derives can be held against the packages that
actually exist. On the first run it reported 32 invented packages - all of them the application's
own, because the oracle had been pointed only at the dependency cache. Widened to the project's
klibs and the Kotlin/Native distribution's unpacked ones, the number is **23 of 210 rows, 25,142
bytes, 0.4%** - and the largest of those, 10,527 bytes, is a real package whose klib is simply not
on the search path.

The rest are one cause: a declaration whose own name is lowercase cannot be told from a package
segment. cinterop struct classes keep their C names - `sockaddr_un`, `addrinfo`, `ossl_param_st`,
`mutex_node`, `selection_set`. The grammar cannot fix that and
[B-22](B-22-fold-undeclared-packages.md) fixes it with data instead, once
[B-08](B-08-klib-package-to-module.md) has read the lists.

**Measured top of the table at depth 3** on the release subject: `ru.workinprogress.shildik` 991,047,
`io.ktor.server` 443,998, `io.ktor.client` 358,921, `dev.whyoleg.cryptography` 323,553,
`io.ktor.http` 264,911, `kotlin.text.regex` 251,770.
