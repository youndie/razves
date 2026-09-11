---
id: B-35
title: "MCP as the second interface, in tracy's shape"
status: open
priority: P2
size: M
stage: stage-4-profiler
epic: research-profiler
blocked_by: [B-32]
---

# B-35 — MCP as the second interface, in tracy's shape

The aggregation is worth asking questions of, and an agent asking them is a different interface from
a file a person opens.

- **The decision and its reason.** Not a research question: `tracy` ships MCP from a server that
  builds for `jvm`, `macosArm64` and `linuxX64` with `io.modelcontextprotocol:kotlin-sdk-server` over
  a stateless streamable-HTTP transport, gated by a token
  ([research §1.10](../research/research-profiler.md)). Two of its decisions were paid for once and
  are taken here rather than rediscovered: the transport installs its own routing and cannot be
  nested in an `authenticate` block, and read-only tools have no session worth resuming.
- Read-only tools, one at a time, in the shape of `list_services` / `search_logs`: what is hot, what
  moved between two profiles, what a package costs.
- Does **not** cover: starting or stopping a sampler over MCP. A tool that can arm a signal handler
  in somebody else's process is a different trust question, and this repository has not answered it.

- AC: the tools answer from the same aggregation the CLI prints, not from a second path.
- AC: no token, no transport - the endpoint does not exist rather than existing unauthenticated.
- Anchors: `docs/research/research-profiler.md`
