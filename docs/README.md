# docs — razves

A size profiler for Kotlin/Native binaries that aggregates bytes into Kotlin packages and klib
modules, and a Gradle gate that fails a build when the binary grows. The documentation is layered;
links run top to bottom.

```
[ Research (why the architecture is what it is) ]
                     │
[ Feature (behaviour + BDD) ]
                     │
[ Service (module ownership, how it is built, quirks) ]
```

| Layer | Directory | Answers | Source of truth |
|---|---|---|---|
| Research | `research/` | *why* it is built this way; what is verified, what is a hypothesis | the artefacts and binaries each fact names |
| Feature | `features/` | *what* the tool does and *why*; BDD scenarios | this repository |
| Service | `services/` | which module owns what, how it is built, its quirks | this repository |

**No `screens/`** — there is no client. **No `api/`** — there is no HTTP surface; the two contracts
this project has, the Gradle DSL and the CLI arguments, live in
[`services/gradle-plugin.md`](services/gradle-plugin.md) §2 and [`services/cli.md`](services/cli.md)
§2 respectively. A missing directory is a valid answer; a renamed one is not — the checkers in
[`scripts/`](../scripts/) look for these names.

**Backlog** — [backlog.md](../backlog.md): the index and the decisions; the items themselves are
one file each in [`backlog/`](backlog/), cited as `[B-14](backlog/B-14-budget-gate.md)`.

## Where to start

**Read [`research/research-architecture.md`](research/research-architecture.md) first.** More of
this project's decisions are counter-intuitive than is usual, because the measurements contradicted
the plan in six separate places — §5 of that document collects them. A task started without it will
reach for the obvious approach, and the obvious approach here is wrong about the toolchain, about
what a Kotlin/Native binary is made of, and about which mangling scheme `_ZN` means.

Then the backlog, then the layer document the task belongs to.

## Conventions

- **`id`** in the frontmatter is unique and equals the filename.
- Cross-layer links are ids in the frontmatter and ordinary markdown links in the body.
- One document, one entity.
- **No number is written that was not measured**, and every measured number names what was measured
  and how. A figure with no provenance is a hypothesis wearing a suit.
- BDD scenarios describing behaviour that does not exist yet are marked *target*, and the documents
  that carry them are `status: draft` until the code does.
- **The primary consumer is a coding agent.** Every document carries code anchors. Do not duplicate
  what lives in code; give the path.
- Language: English throughout, including the research. Identifiers, flags and symbol names verbatim
  as they appear in the toolchain.

## Templates

`templates/` holds a copy of the document templates, so the format travels with the repository.

## Checks

```bash
pip install pyyaml
make check
```

or the pieces:

```bash
python3 scripts/backlog_index.py --check
python3 scripts/docs_check.py
python3 scripts/coverage_map.py --check
python3 scripts/bdd_report.py
python3 scripts/code_anchors.py --repos .
```

The last two are reports, not gates: demanding a percentage of automated scenarios is meaningless
while nothing is implemented, and an anchor goes stale because of a refactor a machine cannot
distinguish from a path quoted as obsolete.

## Coverage map

The list below is **checked** against the files on disk: a document missing here, or an entry with
no file behind it, fails `coverage_map.py`. The grouping and the descriptions are written by a
person — the machine only guards the membership.

### Research (2)

- [x] [research-architecture](research/research-architecture.md) — what was measured on four real
  Kotlin/Native binaries, the nine decisions that follow, and the six places the measurements
  contradicted the brief
- [x] [research-profiler](research/research-profiler.md) — whether a sampling profiler can sit on
  razves' symbol table: what a signal handler may do in a Kotlin/Native process, what the
  sampling rate really is, and what it costs

### Services (3)

- [x] [core](services/core.md) — the ELF and Mach-O readers, the mangling grammar, the report model
  and the arithmetic that must hold; knows nothing about Gradle
- [x] [gradle-plugin](services/gradle-plugin.md) — `sizeReport`, the budget gate, and the baseline
  task that is deliberately not part of `check`
- [x] [cli](services/cli.md) — the same attribution for a binary nobody here built, including module
  attribution when pointed at klibs

### Features (3)

What the tool computes:
- [x] [feature-size-report](features/feature-size-report.md) — reconciliation, origin, Kotlin
  detail, and the `unattributed` line that is a required row rather than a failure

What makes it usable:
- [x] [feature-size-diff](features/feature-size-diff.md) — row-level deltas against a committed
  baseline; also how the `-Xbinary` flag table gets produced
- [x] [feature-size-budget-gate](features/feature-size-budget-gate.md) — `budget` and
  `deltaPerChange`, and a failure message that names the rows that moved
