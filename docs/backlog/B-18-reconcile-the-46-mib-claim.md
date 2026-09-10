---
id: B-18
title: "Reconcile the 46 MiB figure, or retire it"
status: question
priority: P2
size: XS
stage: stage-0-readers
---

# B-18 — Reconcile the 46 MiB figure, or retire it

The premise of the whole project is "why is the binary 46 MiB". No artifact in `shildik` is 46 MiB.
Measured on 2026-09-11: the release binaries are 15,398,536, 20,543,736 and 21,555,648 bytes, and
the largest thing in the tree is a **debug** binary at 39,818,016
([research §1.2](../research/research-architecture.md)).

- **The decision and its reason.** Find where 46 MiB came from before anything quotes it. Likely
  candidates: a container image rather than a binary, an older Kotlin version, a different target,
  or a build with `sourceInfoType` set. Each is a different story and two of them would change what
  the tool should measure first.
- **This is `question`, not `open`.** There is no work here until the number is located, and
  writing the article around an unverified headline is exactly the failure this documentation
  format exists to prevent.
- Does **not** cover: making the tool handle images. If 46 MiB turns out to be an image, that is a
  finding about layering, not a feature request.

- AC: the research document either carries the reproduction — binary, version, flags, date — or
  records that the figure was not reproduced and where it probably came from.
- Anchors: `shildik/docker/`, `shildik/distribution/build/bin/linuxX64/`
