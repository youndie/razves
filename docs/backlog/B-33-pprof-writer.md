---
id: B-33
title: "Write pprof, so existing viewers work"
status: done
priority: P1
size: M
stage: stage-4-profiler
epic: research-profiler
blocked_by: [B-32]
---

# B-33 — Write pprof, so existing viewers work

```
razves profile --format pprof > cpu.pb.gz
```

- **The decision and its reason.** A profiler nobody can look at is a file nobody keeps. pprof has
  viewers people already run, and its shape is small: a protobuf whose every string is an index into
  a `string_table`, gzipped on disk ([research §1.9](../research/research-profiler.md)). Writing it
  is a varint encoder and no dependency - razves already reads protobuf-shaped klib metadata by hand.
- **The first thing to check is the container, not the encoder.** razves implements DEFLATE
  decompression and no compression at all. A gzip member whose payload is *stored* blocks is valid
  gzip and needs only a CRC32 and a length. Whether `go tool pprof` accepts one is a hypothesis, and
  it is the first commit of this item rather than a discovery three days in.
- Does **not** cover: razves' own rendering. The text report by package stays a separate renderer
  over the same samples, the way the size report prints text beside JSON.

- AC: a profile written here opens in `go tool pprof` and in one browser viewer, checked by opening
  it rather than by reading the spec. **Verified for `go tool pprof`, and automated:
  `PprofReadableByGoTest` writes a profile, runs the real `go tool pprof -top` on it and asserts what
  comes back. A browser viewer is not checked, and this item does not claim it.**

  **And the skip nearly became the defect it was written to avoid.** The test skips itself where Go
  is absent, printing a line - which Gradle does not forward, so a CI run in which it quietly did
  nothing looks exactly like a run in which it held. It now fails when `CI` is set: there is no
  excuse for a missing toolchain there, and a check that passes by being absent is the shape this
  repository keeps finding in other people work.
- AC: the function names in the viewer are the Kotlin ones - `io.ktor.client...`, not
  `kfun:io.ktor.client...$lambda$3`. **Verified, from the tool own output.**
- AC: the sample count in the viewer equals the count the sampler reported, dropped samples included
  as a separate figure. **Verified for the count; the dropped figure goes into pprof's `comment`
  field, which every viewer shows and none interprets, because the format has no place for it.**
- Anchors: `core/src/commonMain/kotlin/io/github/youndie/razves/profile/Pprof.kt`,
  `core/src/commonMain/kotlin/io/github/youndie/razves/profile/GzipStored.kt`,
  `core/src/jvmTest/kotlin/io/github/youndie/razves/profile/PprofReadableByGoTest.kt`

**The hypothesis was checked first, and it holds.** A 90-byte gzip container whose DEFLATE payload is
a single *stored* block, holding a 67-byte hand-written profile, was read by `go tool pprof -top`,
which printed the sample in it. So razves needs no compressor: a stored block, a CRC32 and a length
are a valid gzip, and the reader on the other side does not mind. The cost is size, and that is a
trade to revisit with a measurement rather than a defect to fix now.

**What the real tool printed**, on a profile razves wrote from thirteen samples - ten inside
`map` called from `request`, three in `request` itself:

```
Type: samples
Showing nodes accounting for 13, 100% of 13 total
      flat  flat%   sum%        cum   cum%
        10 76.92% 76.92%         10 76.92%  kotlin.collections#map(){}
         3 23.08%   100%         13   100%  io.ktor.client#request(){}
```

Flat and cumulative are both right, which is the self-and-total split of
[B-32](B-32-aggregate-samples.md) surviving the trip through a format and back out of somebody else
reader.

**Identical stacks are folded into one sample with a count**, or a profile of a long run would be one
entry per signal delivered - linear in the sampling rate, for no information at all.

**What this item does not do is the live path.** Every test here hands the writer stacks that a test
made up, because a unit test cannot run the program it profiles. Sampler to file to rows, on a
running program, is [B-37](B-37-profile-command.md) - filed rather than implied.
