---
id: B-22
title: "Fold a package name no klib declares up to the longest one that is declared"
status: done
priority: P2
size: S
stage: stage-1-attribution
epic: feature-size-report
blocked_by: [B-08]
---

# B-22 — Fold a package name no klib declares up to the longest one that is declared

Found by the oracle written for [B-07](B-07-kotlin-packages.md), which holds every package razves
derives against the `package_<fqn>` entries of the klibs that were linked. On the release subject:
**210 package rows sit under a root some klib declares, and 23 of them name no declared package —
25,142 bytes, 0.4% of those rows.**

Every one has the same cause. A declaration whose own name is lowercase is indistinguishable from a
package segment by any grammar over names: a cinterop struct class keeps its C name
(`sockaddr_un`, `addrinfo`, `in6_addr`, `ossl_param_st`, `mutex_node`, `selection_set`), and so does
a lowercase object (`unicodeLT`) or a top-level property (`engines`).

- **The decision and its reason.** Do not try to fix this in the grammar — it cannot be fixed there,
  because the two cases are the same string. Fix it with data instead: [B-08](B-08-klib-package-to-module.md)
  reads the package list out of every linked klib, and a derived name that is not in that list folds
  up to the longest prefix that is. `platform.posix.addrinfo` becomes `platform.posix`, which is both
  correct and what a reader wanted.
- Rejected: a list of known cinterop type names. It would be right for today's dependencies and
  wrong for the next one.
- Rejected: treating a lowercase trailing segment as a class whenever the parent is a declared
  package. Same information, more fragile spelling of it.
- Does **not** cover: the case where no klibs are supplied. Without them there is no authority, the
  grammar is all there is, and the report says as much in its header.

- AC: on the release subject, folding through the klib lists accounts for strictly more than the
  grammar alone. Measured: **23 rows worth 25,142 bytes become 3 worth 11,753.**
- AC: with no klibs supplied, behaviour is unchanged and the report says module-level attribution was
  not available.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/report/Attribution.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/klib/PackageToModule.kt`,
  `core/src/commonTest/kotlin/io/github/youndie/razves/report/FoldedPackagesTest.kt`

**Done, and the three survivors say something useful.** `ru.workinprogress.shildik.distribution`
(10,527 bytes) is the executable module's own package, whose klib is genuinely not on the search
path; `io.ktor.io.interop.mutex.mutex_node` and `io.ktor.network.interop.selection_set` (613 bytes
each) belong to cinterop artifacts that were not supplied either. **After folding, every remaining
case is a klib nobody handed razves rather than a name razves misread** - which is the answer this
item was after, and it is also a small argument for the plugin supplying the full classpath rather
than a person supplying a directory.

**The fold moved the module layer with it**, because both layers now read the same folded name. Rows
with no declaring klib went from 27 to 7 and from 70,038 bytes to 56,649; resolved rows from 40 to
42. **87.1% of the Kotlin bytes of the release subject now land on a named module.**
