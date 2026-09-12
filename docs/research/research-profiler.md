---
id: research-profiler
title: A sampling profiler on top of razves — feasibility research
type: research
status: active
date: 2026-09-11
---

# Research: a sampling profiler on top of razves

A Kotlin/Native program has no profiler that speaks Kotlin. `perf` on Linux and Instruments on Apple
both work on a `.kexe` and both print what `bloaty` printed before razves existed —
`kfun:io.ktor.client.engine#executeWithinCallContext$lambda$3`, one symbol at a time, with no notion
of a package or of the klib a package came from. The gap is the same gap, one axis over: razves
turns bytes into packages and modules, and the same table turns **addresses** into them.

That is the whole argument for building this here and not elsewhere. The pairing is by code, not by
theme: a profiler needs a symbol table reader, an address-to-symbol map and a Kotlin mangling
grammar, and razves is three for three.

**The shape this research tests**: a tiny in-process sampler that does nothing but capture stacks
into a ring buffer, and everything else — symbolisation, aggregation, rendering — outside, by razves.
The tests below say that the shape survives contact, and that one half of it cannot be written in
the language the brief assumed.

This document records **verified facts**, **decisions with their reasons**, **deviations from the
brief**, and **risks with the machinery that would catch them**. Everything unverified says so and
names where it will be checked.

**Where this document lives.** In razves for now, because every fact in it was measured with razves
or against razves' own subjects. If §2.6 stands and the profiler becomes its own repository, this
file moves there in that repository's first commit.

---

## 1. Verified facts

All measurements on 2026-09-11, Kotlin 2.4.10, `kotlin-native-prebuilt-linux-x86_64-2.4.10`, on one
Linux x86-64 machine. The spike is four `.kt` files and two `.def` files compiled with
`kotlinc-native` directly — no Gradle, so that what is being measured is the runtime and not a build.

### 1.1 A Kotlin signal handler is not viable, and the failure is a race

The brief puts the in-process half in Kotlin: a timer, a signal handler, a ring buffer. The handler
was written exactly that way — `staticCFunction(::onProf)` into `sigaction`, `setitimer(ITIMER_PROF)`,
two atomic stores and a read of `uc_mcontext.gregs[16]`.

It works, and then it does not.

| Variant | Runs | Completed | Crashed | Hung |
|---|---|---|---|---|
| Kotlin handler, 100 Hz, allocating workload | 10 | 7 | 0 | **3** |
| Kotlin handler, 1000 Hz, allocating workload | 10 | 1 | 1 | **8** |
| Kotlin handler, 1000 Hz, **no ucontext read** — only an atomic increment | 10 | 2 | 2 | **6** |
| Kotlin handler, 100 Hz, **non-allocating** workload | 10 | 10 | 0 | 0 |
| No sampling at all | 5 | 5 | 0 | 0 |

Three readings fall out of that table and none of them is "it works":

* **The failure rate rises with the sampling rate** — 30% at 100 Hz, 90% at 1 kHz. A defect that
  arrives more often when you ask for more samples is not a flake.
* **It disappears when the workload stops allocating.** The handler is interrupting the allocator.
* **It is not the `ucontext` dereference.** A handler that only increments an atomic fails 8 times
  out of 10 at the same rate, so what is unsafe is *entering Kotlin at all* from a signal: a
  `staticCFunction` callback goes through the runtime's thread-state machinery, and doing that on a
  thread the collector has already stopped, or that holds the allocator, is a deadlock against
  oneself.

**This is the single most important fact in this document**, and it was found in twenty minutes of
running rather than in any amount of reading.

### 1.2 The same sampler written in C survives everything

The handler moved into the `---` section of a cinterop `.def` — plain C, no allocation, no lock, one
ring-buffer store — and the Kotlin side only starts it, stops it and reads the buffer afterwards.

| Variant | Runs | Completed | Crashed | Hung |
|---|---|---|---|---|
| C handler, 100 Hz | 10 | 10 | 0 | 0 |
| C handler, 1000 Hz | 10 | 10 | 0 | 0 |
| C handler, 10,000 Hz | 10 | 10 | 0 | 0 |
| C handler, 10,000 Hz, **with stack unwinding** (§1.5) | 10 | 10 | 0 | 0 |

Same workload, same machine, same session as the table above.

### 1.3 The rate you ask for is not the rate you get

Every tick was counted, so the delivered rate is arithmetic rather than assumption.

| Timer | Requested | Delivered (median) |
|---|---|---|
| `setitimer(ITIMER_PROF)` | 100 Hz | ~90 Hz |
| `setitimer(ITIMER_PROF)` | 1000 Hz | **219 Hz** |
| `setitimer(ITIMER_PROF)` | 10,000 Hz | ~220 Hz |
| `timer_create(CLOCK_THREAD_CPUTIME_ID)` | 1000 Hz | **207 Hz** |
| `timer_create(CLOCK_THREAD_CPUTIME_ID)` | 10,000 Hz | ~200 Hz |
| `timer_create(CLOCK_MONOTONIC)` | 1000 Hz | ~817 Hz |
| `timer_create(CLOCK_MONOTONIC)` | 10,000 Hz | **8,204 Hz** |

The ceiling is not the API. Both CPU-time clocks saturate at the same ~200 Hz, because a process or
thread CPU clock is only advanced on the scheduler tick, and asking a tick-bound clock for a
millisecond gets you four milliseconds. The monotonic clock is driven by a high-resolution timer and
delivers what it was asked for, to within 18%.

The two are not interchangeable: a CPU-time timer samples a thread **only while it runs**, which is
what a CPU profile means; a monotonic timer fires whether the thread is running or blocked, which is
what a wall-clock profile means. Both are legitimate and they answer different questions.

### 1.4 What sampling costs: below what this stand can measure

The number the brief asks for first — "sampling costs X% of CPU at 100 Hz" — cannot honestly be
given yet, and the reason is worth more than a figure would be.

Interleaved A/B, sampling off and on in pairs so that a drifting machine moves both halves, CPU time
measured with `clock_gettime(CLOCK_PROCESS_CPUTIME_ID)` inside the process:

| Rate delivered | Samples per run | Median CPU off | Median CPU on | Difference | Pairs where "on" was **faster** |
|---|---|---|---|---|---|
| 219 Hz | ~100 | 403 ms | 397 ms | −1.5% | 8 of 15 |
| 219 Hz, 10× longer workload | ~880 | 4,069 ms | 3,835 ms | −5.8% | 6 of 7 |
| **8,204 Hz**, 10× longer workload | **~32,000** | 3,823 ms | 3,924 ms | +2.6% | 4 of 7 |

The run-to-run spread of the *unsampled* variant alone is 24–56% of its own median on this machine.
At 8.2 kHz — eighty times the rate the brief asks about, thirty-two thousand signals delivered and
unwound per run — the cost is still inside that spread, and in most pairs the sampled run came out
ahead.

So the honest statement is: **at 100 Hz the cost is not measurable on this stand, and an upper bound
from the medians at 8.2 kHz is about 3 µs per sample** — which, if it were real, would be 0.03% of a
core at 100 Hz. The number the brief wants is a deliverable of a *stand*, not of a guess: a quiet
machine, a pinned core, and instruction counts rather than wall time. That stand is the first
backlog item, before any figure is published.

### 1.5 Unwinding works inside the handler, and must be warmed first

A program counter names the leaf; a profile needs the path to it. glibc's `backtrace()` walks
`.eh_frame`, which a Kotlin/Native binary carries — razves measures it at 183,484 bytes in one
release binary, 5.8% of the file.

Called from the C handler at 1 kHz: **mean depth 8.89 frames, maximum 16, ten runs out of ten
completed.**

`backtrace()` is not async-signal-safe on its *first* call, which loads `libgcc` — so the spike calls
it once at start-up before arming the timer. Without that warm-up the first sample can deadlock in
the dynamic loader, which is the same class of defect as §1.1 and would be just as intermittent.

The deepest stack captured, verbatim:

```
0x245734 0x7ed158245330 0x7ed158298e4f 0x7ed15829b8cd 0x2528f1 0x2392ab 0x23c0d7 0x23c6a6
```

Three of those eight frames are not in the executable at all — they are in libc, in the middle of an
allocation. Which is the next fact.

### 1.6 Addresses: no bias on Linux, a slide on Apple, and frames outside the binary

| Fact | Where verified |
|---|---|
| A Kotlin/Native Linux executable is `ET_EXEC`, not PIE | `e_type = 2` read from the ELF header of `cli.kexe` |
| A Kotlin/Native macOS executable is `MH_EXECUTE` **with `MH_PIE` set** | flags of `fixture.kexe`, `macosArm64` |
| Sampled stacks contain addresses outside the executable | §1.5, three frames of eight at `0x7ed1…` against a binary based at `0x200000` |

On Linux the sampled PC **is** the link-time address razves already knows: symbolisation is a lookup
with no arithmetic. On Apple targets the image slides, and the slide has to be recorded by the
in-process half — the value is known there and unknowable afterwards.

And a profile of a real program will always contain frames razves cannot name, because they are in
libc, in the dynamic loader, or in a shared library. That is the same situation as `unattributed` in
a size report, and it gets the same treatment: a named row, never a silent drop.

### 1.7 Garbage-collection statistics exist, but only as a snapshot of the last one

Verified in the metadata of the 2.4.10 standard library klib (`klib dump-metadata`, package
`kotlin.native.runtime`):

| Fact | Where verified |
|---|---|
| `GC.lastGCInfo: GCInfo?`, `@ExperimentalStdlibApi` | `kotlin/native/runtime/GC` |
| `GCInfo` carries `epoch`, `startTimeNs`, `endTimeNs`, `firstPauseRequestTimeNs`, `firstPauseStartTimeNs`, `firstPauseEndTimeNs`, three nullable second-pause fields, `postGcCleanupTimeNs`, `rootSet`, `markedCount`, `sweepStatistics`, `memoryUsageBefore`, `memoryUsageAfter` | `kotlin/native/runtime/GCInfo` constructor |
| The GC is tunable from the program: `regularGCInterval`, `targetHeapBytes`, `targetHeapUtilization`, `minHeapBytes`, `maxHeapBytes`, `pauseOnTargetHeapOverflow` | `kotlin/native/runtime/GC` |
| `Debugging.dumpMemory(fd)` exists | `kotlin/native/runtime/Debugging` |
| **There is no listener, callback or event stream for collections** | nothing matching `GCListener`/`onGC`/`gcCallback` in 54,835 lines of dumped metadata |

So GC statistics are *polled*, and the only handle is the **last** collection. Two consequences that
belong in the design rather than in a surprise later: the poller must deduplicate by `epoch`, and a
poll slower than the collection rate **misses collections silently**. A profiler that reports "4
collections in this window" when there were nine is worse than one that reports none, so the poller
has to say when consecutive epochs are not consecutive.

### 1.8 razves has the symbol table and does not yet have the lookup

`BinaryImage.symbols: List<Symbol>` and `Symbol(name, address, size, sectionIndex)` are exactly the
input a symboliser needs
([`read/Binary.kt`](../../core/src/commonMain/kotlin/io/github/youndie/razves/read/Binary.kt)), and
there is **no address-to-symbol function anywhere in `core`** — the attribution sweep walks symbols
against sections, never the other way. That is a small, named gap: a sorted array and a binary
search, in `core`, where the profiler and `razves report` would both use it.

### 1.9 pprof is a protobuf with a string table, gzipped

Verified against [`profile.proto`](https://raw.githubusercontent.com/google/pprof/main/proto/profile.proto):

* `Profile` = `sample_type` (repeated `ValueType`), `sample`, `mapping`, `location`, `function`,
  `string_table`, `time_nanos`, `duration_nanos`, `period_type`, `period`, …
* Every string is an **index into `string_table`**, whose element 0 is the empty string.
* A `Sample` is a list of `location_id` plus values; a `Location` carries an address and lines; a
  `Function` carries a name.
* **On disk the serialised proto must be gzip-compressed.**

Writing it needs a protobuf *writer* — varints, length-delimited fields — which is a day of work and
no dependency. razves already reads protobuf-shaped klib metadata by hand and already implements
DEFLATE **decompression** from scratch; what it has no code for is compression.

*Hypothesis, to check when the writer lands*: a gzip member whose DEFLATE payload is **stored**
(uncompressed) blocks is valid gzip, needs only a CRC32 and a length, and will be accepted by
`go tool pprof` and by every viewer that uses a standard gzip reader. If that holds, no compressor
is needed at all, at the price of a larger file. If it does not, the fallback is a fixed-Huffman
encoder, which is a further day.

### 1.10 MCP on a native binary is already proven in this portfolio

`tracy` ships MCP from a server that builds for `jvm`, `macosArm64` and `linuxX64`
(`tracy/server/build.gradle.kts`), using `io.modelcontextprotocol:kotlin-sdk-server` over a stateless
streamable-HTTP transport, gated by a token
(`tracy/server/src/commonMain/kotlin/io/github/youndie/tracy/server/mcp/McpTransport.kt`), with
read-only tools registered one by one (`RegisterTools.kt`: `list_services`, `search_logs`,
`get_trace`, `search_spans`, …).

So "MCP as a second interface" is not a research question here; it is a pattern with a working
precedent in a sibling repository, including the two decisions that cost tracy something to learn —
that the transport installs its own routing and cannot be nested in an `authenticate` block, and
that read-only tools have no session worth resuming.

### 1.11 Prior art: nothing that knows what a Kotlin package is

A targeted search found IDEA's bundled async profiler and YourKit used **on the Kotlin/Native
compiler** (a JVM process), Android Studio's profiler for Android, and `async-profiler` for the JVM.
Nothing profiles a Kotlin/Native binary as such; on Linux people run `perf`, on Apple targets
Instruments, and both print mangled symbols one at a time.

The niche is the same one razves found: the tools exist, and none of them knows that
`kfun:io.ktor.client.plugins…` and `kfun:io.ktor.client.engine…` are the same dependency.

---

## 2. Decisions

### 2.1 The in-process half is C, not Kotlin — a deviation from the brief

The brief says the in-process part is a timer, a signal handler and a ring buffer, and implies they
are Kotlin. §1.1 says that costs a 30% chance of a hung process at 100 Hz. The handler is C, in the
`---` section of a cinterop `.def` — about forty lines — and Kotlin owns everything on the other side
of the buffer.

**The alternative rejected**: keeping Kotlin and making the handler "safe enough" — no allocation, no
object access. §1.1's third row is that experiment: a handler that touches nothing but an atomic
still hung 6 runs in 10. What is unsafe is the entry, not the body.

This makes the in-process half *smaller* than the brief imagined, not larger, and it keeps the
promise the brief was making: what runs inside the profiled process is one signal handler and one
ring buffer, with a cost measured rather than asserted.

### 2.2 The clock is a choice the profiler states, not hides

CPU time and wall time answer different questions, and on Linux the first is capped at ~200 Hz by the
scheduler tick (§1.3). So the profiler takes the clock as a setting, and **reports the rate it
actually achieved beside every profile** — asking for 1000 and getting 219 is normal, and a profile
that claims its requested rate is a profile whose percentages are wrong.

### 2.3 Symbolisation is razves, outside the process

Raw addresses leave the process; nothing else does. No symbol table is read in-process, no name is
resolved, no allocation happens on the sampled path. razves reads the same binary afterwards and
turns addresses into `kfun:` names, then into packages and modules with the grammar and the klib map
it already has.

This is what makes the pairing structural rather than thematic, and it is also what makes the
in-process half measurable: there is almost nothing in it.

### 2.4 pprof is the output format

Not a format of our own. pprof has viewers people already run (`go tool pprof`, Speedscope,
Firefox Profiler through a converter), and a profiler nobody can look at is a file nobody keeps.
Writing it is a protobuf writer plus a gzip container (§1.9).

The razves-flavoured output — by package, by module, with the same `unattributed` discipline — is a
*second* renderer over the same samples, the way `razves report` prints text beside JSON.

### 2.5 Frames outside the binary are a row, not a loss

Every sampled stack crosses into libc, the loader and shared libraries (§1.5, §1.6). Those frames
are named as what they are: the mapping they fall in. They are never folded into the nearest Kotlin
frame, and never dropped — the same rule as `unattributed` in a size report, for the same reason.

### 2.6 Its own repository, with razves as a library

razves publishes `io.github.youndie.razves:core` as a multiplatform library with no Gradle API on its
classpath, which is exactly what a second tool needs. A profiler folded into razves would tie a CLI
that measures files to a runtime component that ships inside somebody's process, and would put a
signal handler in the dependency graph of a size report.

*Open, and the user's call*: whether that repository is created now or after the first end-to-end
profile exists in a branch here.

---

## 3. Deviations from the brief, in one place

| The brief says | What was measured | What follows |
|---|---|---|
| In-process: a signal handler by timer, a ring buffer, in Kotlin | A Kotlin handler hangs 3 runs in 10 at 100 Hz and 8 in 10 at 1 kHz (§1.1) | The handler is C; Kotlin stays outside the signal (§2.1) |
| "Sampling costs X% of CPU at 100 Hz" as the first number | At 8.2 kHz the cost is still inside a 24% noise floor (§1.4) | The first deliverable is the **stand** that can resolve it; the number comes second and with its error |
| GC statistics from `kotlin.native.runtime` | Only `lastGCInfo`, no event stream (§1.7) | Polling, deduplicated by epoch, reporting the collections it knows it missed |
| 100 Hz as the working rate | A CPU-time clock caps at ~200 Hz; a monotonic clock reaches 8.2 kHz (§1.3) | The rate is a setting, and the achieved rate is reported (§2.2) |

---

## 4. Risks, with the machinery that would catch each

1. **A profiler that hangs the profiled process is worse than no profiler.** §1.1 is that risk
   realised, in the most benign way available: in a spike. *Mitigation*: the in-process half has an
   acceptance test that runs it at four rates, N times each, and counts completions — the tables in
   §1.1 and §1.2 are that test's output, and it is the first thing to automate.
2. **Frame-pointer-free code unwinds badly.** `backtrace()` reached a mean of 8.89 frames on an
   allocating workload, which looks right, but nothing yet proves the *top* of those stacks is the
   real caller chain rather than a truncation. *Mitigation*: a fixture whose call depth is known by
   construction — razves already has that shape in its `fixture` module — and an assertion on the
   depth, not on the names.
3. **Sampling bias at a tick-bound rate.** At 219 Hz with a workload that allocates in bursts, the
   samples can align with the tick and over-report whatever runs just after it. *Mitigation*: a
   randomised interval (jitter) as an option, and a profile that reports its own rate so the reader
   can see the resolution they are getting.
4. **The gzip hypothesis (§1.9) may be wrong**, and then the writer needs a Huffman encoder before
   anything can be viewed. *Mitigation*: the check is cheap and comes first — write one profile with
   stored blocks and open it in `go tool pprof` before building anything on top.
5. **Apple targets are a second implementation, not a flag.** `timer_create` does not exist there
   (§1.3 is Linux-only), the mcontext layout differs, and the image slides (§1.6). *Mitigation*: the
   same discipline razves used for Mach-O — the second reader is written against a real binary, and
   the honest state until then is "Linux only", said out loud.
6. **A sampler is a thing that runs in someone else's process.** It has to be impossible to leave on
   by accident: off unless started, stopped on close, and with a bounded buffer that drops rather
   than grows. *Mitigation*: the ring buffer is fixed at compile time and the drop count is part of
   the profile, so a profile that lost samples says so.

---

## 5. What the first items would be

1. The stand that can resolve the cost of sampling (§1.4) — quiet machine, pinned core, instruction
   counts. **Before** any published percentage.
2. The C sampler as a `.def`, with the survival test of §1.2 as its acceptance criterion.
3. `Symbols.at(address)` in razves' `core` (§1.8), with the folding and ambiguity rules the size
   report already has.
4. The pprof writer, starting with the stored-block check (§1.9, risk 4).
5. GC polling with epoch deduplication and an explicit "collections missed" figure (§1.7).
6. MCP over the same aggregation, in tracy's shape (§1.10).

---

## 6. Corrections found while implementing

**§1.4's upper bound of "about 3 µs per sample" is withdrawn.** A stand built for the question
([B-29](../backlog/B-29-sampling-cost-stand.md)) - both halves of each pair in one process, pinned to
a core, alternating order, climbing a ladder of deliberate costs until it can see one - measured
+6.59% at 7,216 Hz and +7.22% at 17,944 Hz. The rate rose two and a half times and the cost did not,
so **the cost is not linear in the delivered rate**, the per-sample figure is not a constant, and no
measurement at a high rate can be scaled down to 100 Hz. The spike's 3 µs came from dividing one
noisy difference by a sample count, which assumed exactly the linearity these two rows deny.

**§2 and risk 5 said "Linux only", and that is no longer true.** The Apple half landed in
[B-36](../backlog/B-36-apple-sampler.md): `setitimer` in place of the POSIX timers that do not exist
there, the program counter read from `uc_mcontext->__ss.__pc` rather than from an indexed register,
and the `MH_PIE` slide - 13,631,488 in one measured run - carried in the dump because only the
process knows it. The same program profiled on both resolves to the same package names; the shares
differ because macOS links libsystem dynamically and the Linux build does not, which razves reports
as `<outside the binary>` at 16.4% against 1.4%.

**§1.1's failure table is now a test rather than a table**, and the live path found what it could
not: `backtrace()` called from inside the handler returns the handler as frame 0 and the kernel
trampoline as frame 1, so every sample in every profile had the same leaf until
[B-37](../backlog/B-37-profile-command.md). razves reported one C function as 100% of a workload that
is mostly Kotlin, and every unit test passed while it did - they all handed the aggregation stacks a
test had made up.
