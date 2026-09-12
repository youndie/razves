---
id: B-36
title: "The Apple half: no POSIX timer, a different context, a sliding image"
status: done
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
  **Verified on the probe, built and run on both: the same package rows, in a different order.**
- AC: the delivered rate is measured on that platform too - a `setitimer` ceiling is not the same
  number as Linux's 219 Hz. **Measured: 964 Hz on the wall clock for 1,000 requested, and 334 Hz on
  the CPU clock. Neither is Linux's number, and the wall-clock one is barely a ceiling at all.**
- Anchors: `sampler/src/nativeInterop/cinterop/sampler.def`,
  `sampler/src/nativeMain/kotlin/io/github/youndie/razves/sampler/Sampler.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/read/Binaries.kt`

**Three differences, and all three are in the C rather than in a second module.** The Kotlin above
them is identical, so it moved to a shared `nativeMain`:

* **No POSIX timers.** `timer_create` and `timer_settime` are absent from `platform.posix` on
  `macos_arm64` - the type `timer_t` is not even declared, which is a sharper way of saying it than
  the research's table. What is left is `setitimer`, and its two interval timers answer the same pair
  of questions the Linux side answers with two clocks: `ITIMER_PROF` counts CPU time, `ITIMER_REAL`
  wall time. `ITIMER_REAL` raises `SIGALRM` rather than `SIGPROF`, so the handler is on both signals.
* **The interrupted program counter is somewhere else.** On x86-64 it is an indexed register whose
  index is `REG_RIP`, not exported by the binding, so the number 16 is written out; on arm64 Apple it
  is `uc_mcontext->__ss.__pc`, a named field behind a pointer.
* **The image slides.** A Kotlin/Native macOS executable is `MH_PIE`, so a sampled address is the
  link-time one plus whatever the loader chose - measured at 13,631,488 in one run. razves reads
  link-time addresses out of the binary and cannot know the slide; the process knew it and is gone by
  the time the profile is read. So the dump carries `slide`, and the addresses are corrected once,
  where they are parsed, rather than by every reader.

## The same program on two platforms

| package | linuxX64 self | macosArm64 self |
|---|---|---|
| `kotlin.collections` | 41.1% | 51.0% |
| `c` | 47.5% | 24.8% |
| `<outside the binary>` | 1.4% | **16.4%** |
| `kotlin_runtime` | 4.6% | 1.1% |
| `kotlin` | 3.1% | 3.5% |

**The names match and the shares do not, and the difference is the platform rather than the tool.**
macOS links libsystem dynamically, so the allocator frames are outside the image and razves can only
say so; the Linux build has the same code statically inside it, where the grammar files it under `c`.
A profiler that hid that behind one number would make the two runs look like two different programs.

**One flag makes this possible at all**: `kotlin.mpp.enableCInteropCommonization=true`. A cinterop
klib is produced per target, so a source set shared between two native targets sees none of its
symbols without it - the metadata compilation fails on every interop name while both real targets
compile.

**And a test taught the reader to sniff the file.** `LiveProfileTest` read the probe with `ElfReader`;
on a mac the probe is Mach-O and it failed with "is not an ELF file". The choice now lives in
`Binaries.read`, where the CLI already made it, rather than in two places that agree until one of them
runs on the other platform.
