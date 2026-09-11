---
id: B-33
title: "Write pprof, so existing viewers work"
status: open
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
  it rather than by reading the spec.
- AC: the function names in the viewer are the Kotlin ones - `io.ktor.client...`, not
  `kfun:io.ktor.client...$lambda$3`.
- AC: the sample count in the viewer equals the count the sampler reported, dropped samples included
  as a separate figure.
- Anchors: `docs/research/research-profiler.md`
