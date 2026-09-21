# razves

[![check](https://github.com/youndie/razves/actions/workflows/check.yaml/badge.svg)](https://github.com/youndie/razves/actions/workflows/check.yaml)
[![snapshots](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/razves/core?name=snapshots&color=blue&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/razves/core)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![native](https://img.shields.io/badge/Native-blue?logoColor=white)](https://kotlinlang.org/docs/native-overview.html)
[![jvm](https://img.shields.io/badge/JVM-orange?logoColor=white)](https://kotlinlang.org)
[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![licence](https://img.shields.io/badge/licence-MIT-green.svg)](LICENSE)

**Where did the bytes in your Kotlin/Native binary go — and where did the time?**

`bloaty` will tell you that `kfun:io.ktor.server.engine#embeddedServer(...)` is 4,112 bytes. `perf`
will tell you the same mangled symbol was on the stack. Both are true and neither is actionable.
razves turns bytes **and samples** into the units a Kotlin developer can act on — the package, and
the klib the package came from — out of one symbol table, and then lets a build fail when the binary
grows.

> 📦 every byte of the file is charged to exactly one row, and the rows add up to the file size
>
> ⏱ every sample lands in exactly one row too, including the two rows for the ones razves cannot name

Reads ELF and Mach-O itself, with no subprocess: not `llvm-nm`, not `bloaty`, not `strip`. The
Kotlin/Native toolchain ships none of them ([why](docs/research/research-architecture.md)).

### 🔍 What it says

razves reporting on its own CLI binary, which is a Kotlin/Native executable like any other:

```
razves cli.kexe
  ELF64, android_x64 or linux_x64
  symbol sizes: recorded by the symbol table
  modules: attributed from the klibs supplied

WHERE THE FILE WENT
file                                             3,183,440 (3.0 MiB)  100.0%
  container headers                                  2,976 (2.9 KiB)    0.1%
  allocated sections                             2,340,019 (2.2 MiB)   73.5%
  metadata (symbol tables, debug info)           840,407 (820.7 KiB)   26.4%
  padding between regions                                  38 (38 B)    0.0%
  in memory only (NOBITS)                            7,488 (7.3 KiB)   costs no download

WHERE THE ATTRIBUTED BYTES CAME FROM
attributed                                       1,984,242 (1.9 MiB)   84.8%
  kotlin                                         1,583,000 (1.5 MiB)   79.8%  5907 symbols
  kotlin_runtime                                   40,841 (39.9 KiB)    2.1%  58 symbols
  cxx                                            124,378 (121.5 KiB)    6.3%  900 symbols
  c                                              236,023 (230.5 KiB)   11.9%  2885 symbols
unattributed - no symbol claims these            355,777 (347.4 KiB)   15.2%

KOTLIN, BY PACKAGE
  kotlin.text.regex                              247,286 (241.5 KiB)   12.5%  755 symbols
  com.github.ajalt                               220,893 (215.7 KiB)   11.1%  822 symbols
  kotlinx.serialization.json                     183,496 (179.2 KiB)    9.2%  615 symbols

KOTLIN, BY MODULE
  stdlib                                         673,882 (658.1 KiB)   34.0%  2759 symbols
  org.jetbrains.kotl..x-serialization-json       183,496 (179.2 KiB)    9.2%  615 symbols
  <ambiguous: clikt:..clikt:clikt-mordant>       138,441 (135.2 KiB)    7.0%  576 symbols

SECTIONS, AND HOW MUCH OF EACH HAS AN OWNER
  .text                                          1,555,576 (1.5 MiB)   98.4% has an owner
  .strtab                                        583,186 (569.5 KiB)   not attributed
  .eh_frame                                      183,484 (179.2 KiB)   no owner at all
  .rodata                                          82,644 (80.7 KiB)   87.0% has an owner
```

Three things in there are the whole design. **`unattributed` is a row, never a remainder folded into
something else.** **Coverage sits next to every section**, because a conclusion about `.rodata` drawn
from 87% of it is a different claim from one drawn from all of it. And **a package two klibs both
declare reads `<ambiguous>`** rather than being assigned to whichever was found first.

### 🚀 Use it

As a Gradle plugin, which is the only thing that knows *which klibs took part in the link*:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://reposilite.kotlin.website/snapshots")
    }
}
```

```kotlin
// build.gradle.kts — the version is the one on the snapshots badge above
plugins {
    kotlin("multiplatform")
    id("io.github.youndie.razves") version "0.1.0.23"
}

binarySize {
    budget = 25.MiB            // the release binary, the one that goes in the image
    deltaPerChange = 3.percent

    debug {                    // opt in, with a number of its own, or leave debug ungated
        budget = 40.MiB
    }
}
```

`budget` and `deltaPerChange` are the **release** binary's, because the debug one is not a larger
version of it but a different order of size — 28,580,560 bytes against 9,227,448 for the same
module at the same commit. One number covering both would have to clear the first, and a ceiling
that admits 28.6 MB is no longer watching the 9.2 MB artefact at all. A gate with no rule to apply
says so rather than reporting the same success as one that passed.

| task | what it does |
|---|---|
| `sizeReport<Target><Binary>` | the report above, as text and as JSON |
| `sizeBaselineWrite<Target><Binary>` | writes the baseline you commit — deliberately **not** part of `check` |
| `sizeDiff<Target><Binary>` | what moved since that baseline, row by row |
| `sizeBudgetCheck<Target><Binary>` | the gate, in `check` |

One set per executable of every Kotlin/Native target: `sizeReportLinuxX64ReleaseExecutable`,
`sizeBudgetCheckMacosArm64DebugExecutable`. Reports land in
`build/reports/razves/<target>/`, baselines in `razves/<target>/`, and a target this host cannot
link skips rather than failing `check`.

Or as a CLI, on any binary, with no build system in sight:

```bash
razves report app.kexe --klibs ~/.gradle/caches/modules-2 --klibs ~/.konan/…/klib
razves diff before.json after.json
```

Without `--klibs` the report stops at package level and says so in its header. The report model on
its own is `io.github.youndie.razves:core` — multiplatform, no Gradle API on its classpath.

### ⏱ Where the time went

The same binary, the same packages, a different question. A program links the `:sampler` module,
runs, and writes a dump of raw addresses; razves names them afterwards, out of the file:

```
razves probe.kexe
  1,206 samples
  1000 Hz requested on the wall clock
  97.0% of the leaves have a name
  385 collections seen, 8.2 ms of pause in total, longest 494 us

BY ORIGIN
                                                    self       total
  kotlin                                           1,103 91.4%      1,206 100.0%
  kotlin_runtime                                       68 5.6%          101 8.3%
  <outside the binary>                                 35 2.9%      1,206 100.0%

BY PACKAGE
                                                    self       total
  io.github.youndie                                  674 55.8%      1,206 100.0%
  kotlin.collections                                 429 35.5%         449 37.2%
  kotlin_runtime                                       68 5.6%          101 8.3%
```

```bash
razves profile app.dump app.kexe --klibs ~/.konan/…/klib     # the table above
razves profile app.dump app.kexe --out cpu.pb.gz             # the same thing as pprof
```

**What runs inside your process is a C signal handler and a fixed ring buffer, and nothing else.**
No symbol is read there, no name resolved, no allocation on the sampled path — razves does all of
that afterwards, from the binary. The handler is C because a Kotlin one **hangs**: measured at 3 hung
runs in 10 at 100 Hz and 8 in 10 at 1 kHz, deadlocking against the allocator it interrupted. The C
one completed 10 runs of 10 at every rate up to 10 kHz.

**`self` and `total` are both there, always.** A row with a large total and no self is a caller; one
with both is where the work is. And what the ring could not hold is counted rather than dropped
silently — a profile that lost two thirds of its samples says so.

The pprof file opens in `go tool pprof` and the browser profilers, with Kotlin names rather than
`kfun:` ones. There is also `razves-mcp`, the same answers over MCP on stdio, read-only: it cannot
sample anything, deliberately.

The sampler is published like everything else — four coordinates, because a cinterop klib is made
per target:

```kotlin
// build.gradle.kts of the program you want to profile
kotlin {
    sourceSets.nativeMain.dependencies {
        implementation("io.github.youndie.razves:sampler:0.1.0.28")
    }
}
```

A project that had never seen this repository linked that, sampled itself for 2,662 samples, and
razves named 99.3% of its leaves.

**What it cannot profile: an event loop that does not retry `EINTR`.** Sampling means a timer
signal, and a signal interrupts `pselect`. Ktor's CIO engine on Kotlin/Native turns that `EINTR`
into an exception instead of retrying it, so such a server dies the moment sampling starts, with a
stack that names Ktor rather than razves. `SA_RESTART` is set on the handler and cannot help —
`signal(7)` never restarts `select`/`pselect`/`poll`/`ppoll`/`epoll_wait`. Lowering the rate is not
a workaround either: at 97 Hz the same service still died in two runs of three, which turns a crash
into an intermittent one. Profile such a program from outside, with `perf`; the measurement is in
[research §6](docs/research/research-profiler.md#6-corrections-found-while-implementing).

### 🚦 The gate is the point

A report is interesting once. What gets installed is the build that goes red:

```
stand-service.kexe is over its size budget.
  file size: 1,219,712
  budget:    1,048,576
  over by:   171,136

The largest things in it:
  kotlin              328,298
  c                   289,686
  cxx                 99,388
  kotlin_runtime      77,996
Largest Kotlin packages:
  kotlin.collections                      145,017
  kotlin                                   85,224
  kotlin.native.internal                   47,918
```

**The rows are the product, not the total.** A 3% growth budget is tripped by a dependency bump as
easily as by a mistake, and "the binary grew 7.8%" gives the reader nothing to decide with — so the
gate gets commented out the first week it fires. Against a committed baseline the same gate prints
what moved instead:

```
razves fixture.kexe: -4,856,712 (-90.7%)
  5,352,944 -> 496,232 bytes

BY ORIGIN
  KOTLIN                                            -169,474   238,253 -> 68,779
  KOTLIN_RUNTIME                                     -26,468   64,357 -> 37,889

BY SECTION
  .debug_str                                      -1,860,741   gone
  .debug_info                                     -1,145,773   gone
  .text                                             -273,184   455,160 -> 181,976
```

That one is the same program twice — a debug build against a release one. Nine tenths of the debug
binary is debug information, and what is left, 496,232 bytes, is the floor a Kotlin/Native
executable starts from.

In this portfolio the budget arrives through a
[`sborka`](https://github.com/youndie/sborka) convention, as one property:
`sborka.binaryBudget=50MiB`.

### 📐 What the measurements found

Measured by razves on six real Kotlin/Native binaries, all Kotlin 2.4.10, on 2026-09-11 — with
verification addresses in [the research document](docs/research/research-architecture.md):

* **The Kotlin/Native runtime is a fixed cost of about 40 KB.** 37,889 to 40,871 bytes across all
  six, a spread of under 3 KB over a **43× range of file size**. Its share falls from 17.6% to 0.2%,
  so "the runtime is 17.6% of this binary" means the binary does almost nothing — not that the
  runtime is heavy.
* **The floor is 496,232 bytes**: one `println`, one dependency, of which 135,032 is the symbol table.
* **Kotlin is a minority of a large release binary.** 36–40% of the attributed bytes in one real
  service, against 77% in a binary that is almost all Kotlin. The majority is statically linked C —
  OpenSSL arriving through `ktor-client-curl` — plus, where a Rust-backed driver is used, about
  1.3 MB of `tokio` and `sqlx`.
* **The symbol table is 19–27% of the file**, in every one of the six. It is the largest single
  removable thing in a Kotlin/Native binary, and it is also exactly what razves reads.
* **`_ZN…` is mostly Rust, not C++.** Rust's legacy mangling shares Itanium's prefix, so reading it
  as C++ charges the Kotlin/Native runtime **1,263,149 bytes** of `tokio` and `sqlx` — **31 times**
  the 40,871 bytes the runtime actually is, and enough to report it as the third-largest thing in the
  binary.
* **`nm` reports zero sizes for every Mach-O symbol.** Apple targets need an address-delta algorithm,
  not a flag.
* **9 of 568 packages are declared by two klibs.** razves reports those as ambiguous instead of
  picking one.

And from building the profiler on top of it, on one Linux x86-64 machine and one Apple arm64
([the second research document](docs/research/research-profiler.md)):

* **A Kotlin signal handler is not viable.** 3 hung runs in 10 at 100 Hz, 8 in 10 at 1 kHz, and the
  failures vanish when the sampled workload stops allocating. It is not the handler's body — one that
  only increments an atomic fails just as often — it is entering the Kotlin runtime from a signal.
* **The rate you ask for is not the rate you get.** A CPU-time clock on Linux saturates at ~200 Hz
  whatever you set, because it advances on the scheduler tick; a monotonic timer delivered 8,204 Hz
  for 10,000 requested. On macOS the same request gave 964 Hz on the wall clock and 334 on the CPU
  one. So the profile reports the rate it achieved, not the one it wanted.
* **Sampling costs less than this machine can measure.** A stand that proves its own resolution —
  deliberate extra work, climbing until it can see it — resolves 5% here, and at ~900 Hz the cost is
  under that. It also refused the obvious shortcut: the cost did not rise between 7,216 Hz and
  17,944 Hz, so it is not linear in the rate and no high-rate figure can be scaled down.
* **Two platforms, the same names, different shares.** The same program profiles to the same package
  rows on both, but `<outside the binary>` is 1.4% on Linux and 16.4% on macOS — because macOS links
  libsystem dynamically and the Linux build carries the same code inside the image.

### ⚠️ Before you trust a row

* **Inlined code is charged to the caller.** An inlined stdlib helper has no symbol of its own, so
  its bytes sit inside whichever function inlined it: the stdlib share is understated and the
  application share overstated. No total moves, so the gate is unaffected.
* **Data attributes worse than code.** `.rodata` coverage is 40.7% on the measured subject — a string
  literal has no owning symbol. Every section carries its coverage for exactly this reason.
* **Mach-O sizes include the padding after a symbol**, because the format records no size and the
  number is a distance to the next one. ELF and Mach-O totals are not comparable to each other at
  byte precision, and razves refuses to diff across the two.
* **A sampled stack always leaves the binary.** libc, the loader, a shared library: those frames are
  named `<outside the binary>` rather than folded into the nearest Kotlin one, and on Apple targets
  they are most of what razves cannot name.
* **The collector is polled, because there is nothing to subscribe to.** `GC.lastGCInfo` is all the
  runtime offers, so a poll slower than the collection rate loses some — and the profile says how
  many. Measured on one workload: 389 seen and none missed at one poll rate, 12 seen and 346 missed
  at another.

### 📚 Documentation

[`docs/`](docs/) — the research, the features with their acceptance scenarios, and one document per
module. What is planned and what was decided is in [`backlog.md`](backlog.md), one file per item.

```bash
make check     # the documentation gate; CI runs exactly this
./gradlew build
```

Start with [`docs/research/research-architecture.md`](docs/research/research-architecture.md): it
records the eighteen places where a measurement corrected the plan, six of which changed a decision.
[`research-profiler.md`](docs/research/research-profiler.md) does the same for the sampling half, and
its §6 is where two of its own facts were later withdrawn by better measurements.

### 📄 Licence

MIT — see [LICENSE](LICENSE).
