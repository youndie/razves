---
id: B-22
title: "Fold a package name no klib declares up to the longest one that is declared"
status: open
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

- AC: on the release subject, no package row names a package that none of the linked klibs declares.
- AC: with no klibs supplied, behaviour is unchanged and the report says module-level attribution was
  not available.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/attribute/Packages.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/klib/`
