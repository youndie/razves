---
id: B-36
title: "The Apple half: no POSIX timer, a different context, a sliding image"
status: open
priority: P2
size: M
stage: stage-4-profiler
epic: research-profiler
blocked_by: [B-30]
---

# B-36 — The Apple half: no POSIX timer, a different context, a sliding image

Three differences, each measured rather than assumed
([research §1.3, §1.6](../research/research-profiler.md)):

* `timer_create` and `timer_settime` are **absent** from `platform.posix` on `macos_arm64` and present
  on `linux_x64`. What is left is `setitimer`, or a dedicated sampling thread.
* The register context is laid out differently: the Linux spike reads `uc_mcontext.gregs[16]`, an
  index written out because `REG_RIP` is not exported by the binding.
* A Kotlin/Native macOS executable has `MH_PIE` set, where the Linux one is `ET_EXEC`. On Linux the
  sampled address **is** the address razves knows; on Apple targets the image slides, and the slide
  is knowable only inside the process.

- **The decision and its reason.** Written against a real binary, the way the Mach-O reader was, and
  until it is, the honest state is "Linux only" said out loud rather than a target that silently
  produces a profile whose addresses resolve to the wrong functions.
- Does **not** cover: iOS. A sampler inside an app store binary is a different set of questions.

- AC: a profile taken on `macosArm64` resolves to the same function names as the same program built
  for `linuxX64`, which is the only check that catches a slide applied in the wrong direction.
- AC: the delivered rate is measured on that platform too - a `setitimer` ceiling is not the same
  number as Linux's 219 Hz.
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/read/MachOReader.kt`
