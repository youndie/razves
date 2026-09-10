# razves — how to start a session

**Read [`docs/research/research-architecture.md`](docs/research/research-architecture.md) before
touching anything.** This project has an unusually high ratio of decisions that look wrong until
you know why, because the measurements contradicted the original plan in six places; §5 of that
document collects them. Working from the obvious approach here produces code that is wrong about
the toolchain (§1.1), about what a Kotlin/Native binary contains (§1.2), and about which mangling
scheme `_ZN` indicates (§1.3).

Then, in order:

1. [`backlog.md`](backlog.md) — the stages, the item you are working on, and the decisions in the
   bottom section that are not worth re-litigating.
2. The layer document the task belongs to — [`docs/features/`](docs/features/) for behaviour,
   [`docs/services/`](docs/services/) for a module's mechanics and quirks.
3. The code anchors in that document. They point at the directory, not the line.

## Rules specific to this repository

- **No number goes into a document unless it was measured**, and a measured number names what was
  measured, how, and when. A figure without provenance is a hypothesis wearing a suit.
- **No subprocess.** Not `llvm-nm`, not `bloaty`, not `strip`. See research §1.1 for why the
  "free dependency" is not free. This will look like extra work every single time; it is the
  decision the project rests on.
- **`unattributed` is never absorbed into another row** to make a total work.
- **The reconciliation identities are a constructor invariant**, not a report section. If a change
  makes them fail, the change is wrong; the identities are not negotiable.
- **`main` describes what exists.** A document describing unbuilt behaviour is `status: draft` and
  lives in an open pull request; its scenarios say *target*. Flip to `active` in the same pull
  request that lands the code, not before.
- Documentation language is English, including the research.

## Where things build

Per the global instructions: Gradle and tests run on the WSL box through `~/.claude/bin/wsl-run`.
Only `macosArm64`, `xcodebuild` and anything prefixed `LOCAL=1` stay on the mac. A Mach-O fixture
is therefore a local build, and the ELF side is not.

## Checks

```bash
make check
```

That is the gate, and CI runs exactly it. `make report` is the two non-blocking reports;
`make fix` regenerates the backlog index and the coverage-map membership.
