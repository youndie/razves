---
id: B-35
title: "MCP as the second interface, in tracy's shape"
status: done
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

- AC: the tools answer from the same aggregation the CLI prints, not from a second path. **Done:
  every tool is a call into `Analyse`, the same object the commands use, so the words a client gets
  are the words a terminal gets - refusals included.**
- AC: no token, no transport - the endpoint does not exist rather than existing unauthenticated.
  **Superseded by the shape below: there is no endpoint at all.**
- Anchors: `mcp/src/commonMain/kotlin/io/github/youndie/razves/mcp/Mcp.kt`,
  `mcp/src/nativeMain/kotlin/io/github/youndie/razves/mcp/Channel.kt`,
  `mcp/src/jvmTest/kotlin/io/github/youndie/razves/mcp/McpOverStdioTest.kt`

**stdio rather than HTTP with a token, which is a deviation from this item and has a reason.**
`tracy` serves MCP over streamable HTTP because tracy is a service somewhere else. razves is a
command that runs next to the binaries it reads; a local tool that opened a port would be a local
tool with an attack surface, and the token would guard a machine that already trusts whoever can run
the binary. The SDK carries `StdioServerTransport` on native, verified in the klib metadata before a
line was written.

**A binary of its own, and that is a measurement rather than a preference.** Built as a subcommand of
`razves`, the MCP SDK took the CLI from **3,315,112 to 6,948,816 bytes** - it more than doubled. And
razves, asked about itself, named the reason: `io.modelcontextprotocol.kotlin` at **1,235,919 bytes
over 5,858 symbols**, the largest package in the file, with coroutines and Ktor behind it. A tool
whose whole argument is "where did your bytes go" cannot carry that in the command people run to find
out. So `razves` is back to 3,315,112 bytes and `razves-mcp` is 6,421,088 of its own.

**Read-only, and it cannot sample.** No tool starts or stops a sampler in anybody's process: a signal
handler installed by an agent, in a program neither of them wrote, is a different trust question and
this repository has not answered it. A test asserts the absence by name.

**Two defects the object could not have shown, found by speaking to the process:**

* **The SDK logs into the protocol channel.** `kotlin-logging: initializing...` and a line per
  registered tool, on stdout, before a single request arrives - a client reading that gets a parse
  error naming neither side. razves-mcp duplicates the real stdout to a descriptor of its own and
  points descriptor 1 at stderr, so whatever prints lands where a person can read it.
* **Creating a session already starts the transport.** Calling `start` as well aborted the process
  with "StdioServerTransport already started" - the 0.14 shape, where a server connected to a
  transport, is gone.

**What it looks like**, a real `tools/call` against razves' own binary over stdio:

```
razves cli.kexe
  ELF64, android_x64 or linux_x64
  symbol sizes: recorded by the symbol table
  modules: not available - no klibs were supplied, so this report stops at package level
```
