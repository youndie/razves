# Backlog: a size profiler that knows what a Kotlin package is

> Role of this document: the product backlog. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `B-NN-<slug>.md`. What lives here is the index (generated)
> and everything that is not an item: the goal, the stages, and the decisions.
>
> New item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `B-NN`, and run `python3 scripts/backlog_index.py` after editing.

## Goal

`bloaty` will tell you that `kfun:io.ktor.server.engine#embeddedServer(...)` is 4,112 bytes. That
is a true statement nobody can act on. razves aggregates the same bytes into packages, into the
klib artifacts those packages came from, and into the non-Kotlin origins that turn out to be the
majority of a real binary — and then lets a build fail when the total grows.

The order below is forced by trust. The readers and the reconciliation oracle come first, because
every later number is only as good as they are and the oracle is what says they are wrong before a
report does. Attribution second. The Gradle gate third, on a core that is already right. The
article last, because it is a measurement and there is nothing to measure yet.

## Stages

A stage is a field on the item, not a directory. Items are cited by id from documents in every
layer, so re-prioritising an item must never move its file.

| Stage id | Stage | What it is |
|---|---|---|
| `stage-0-readers` | Read the file, and prove you read all of it | The ELF and Mach-O readers, and the arithmetic that fails when a byte is unaccounted for. |
| `stage-1-attribution` | Name the owner, or admit there isn't one | Mangling grammar, origin buckets, packages, klib modules, and the refusals that stop a plausible wrong answer. |
| `stage-2-gradle` | Make it fail a build | The plugin, the baseline, the row-level diff, the budget gate, the CLI. |
| `stage-3-subjects` | Point it at real binaries and publish what it says | The three subjects, the `sborka` convention, the article. |

## Marks

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX -->

## Open (5)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-09](docs/backlog/B-09-c-attribution-by-archive.md) `[?]` | Attribute C symbols to the static archive that defines them | P2 | L | - |
| [B-17](docs/backlog/B-17-sborka-convention.md) `[ ]` | A sborka convention that applies the gate from one property | P2 | S | B-25 |
| [B-25](docs/backlog/B-25-publish-razves.md) `[ ]` | Publish razves where another repository can reach it | P2 | S | B-24 |
| [B-19](docs/backlog/B-19-full-fqn-module-map.md) `[ ]` | Resolve ambiguous packages with a full declaration-to-module map | P3 | L | B-08 |
| [B-23](docs/backlog/B-23-size-lines-in-the-subjects.md) `[ ]` | Give each subject repository a generated size line | P3 | S | B-17 |

## Closed (20)

**Read the file, and prove you read all of it**

- [B-01](docs/backlog/B-01-elf-reader.md) `[x]` - Read ELF section headers and .symtab without a subprocess
- [B-02](docs/backlog/B-02-reconciliation-invariant.md) `[x]` - The report cannot be constructed with totals that do not add up
- [B-03](docs/backlog/B-03-macho-reader.md) `[x]` - Mach-O reader with address-delta sizing, clamped at the section end
- [B-18](docs/backlog/B-18-reconcile-the-46-mib-claim.md) `[x]` - Reconcile the 46 MiB figure, or retire it

**Name the owner, or admit there isn't one**

- [B-04](docs/backlog/B-04-synthetic-fixtures.md) `[x]` - Synthetic binaries whose attribution is known by construction
- [B-05](docs/backlog/B-05-mangling-grammar.md) `[x]` - The mangling grammar, with Rust tested before C++
- [B-06](docs/backlog/B-06-origin-buckets.md) `[x]` - Origin buckets and per-section coverage in the report model
- [B-07](docs/backlog/B-07-kotlin-packages.md) `[x]` - Aggregate Kotlin symbols by package
- [B-08](docs/backlog/B-08-klib-package-to-module.md) `[x]` - Map package to module from klib manifests, and report ambiguity as ambiguity
- [B-10](docs/backlog/B-10-report-renderer.md) `[x]` - Render the report as text and as JSON, coverage next to every conclusion
- [B-15](docs/backlog/B-15-refuse-stripped-and-mismatched.md) `[x]` - Refuse a stripped binary and a klib set for the wrong target
- [B-21](docs/backlog/B-21-attribute-nobits-sections.md) `[x]` - Attribute NOBITS sections so uninitialised state has an owner
- [B-22](docs/backlog/B-22-fold-undeclared-packages.md) `[x]` - Fold a package name no klib declares up to the longest one that is declared

**Make it fail a build**

- [B-11](docs/backlog/B-11-cli.md) `[x]` - The CLI: report and diff for any binary, with optional klibs
- [B-12](docs/backlog/B-12-gradle-size-report.md) `[x]` - Gradle plugin: sizeReport, wired to the link tasks, configuration-cache clean
- [B-13](docs/backlog/B-13-baseline-and-diff.md) `[x]` - A committed baseline and a row-level diff
- [B-14](docs/backlog/B-14-budget-gate.md) `[x]` - The budget gate, and a failure message that names the rows that moved
- [B-20](docs/backlog/B-20-decide-the-budget-unit.md) `[x]` - Decide what the budget is measured on
- [B-24](docs/backlog/B-24-consumable-by-coordinate.md) `[x]` - razves resolves by id, out of a repository

**Point it at real binaries and publish what it says**

- [B-16](docs/backlog/B-16-run-on-the-three-subjects.md) `[x]` - Report on shildik, booblik and telek, and write the article from the numbers

<!-- END INDEX -->

## Decisions worth not re-litigating

**The `llvm-*` wrapper is not a cheaper first version.**
It was the brief's plan and it is refused in [B-01](docs/backlog/B-01-elf-reader.md). The
Kotlin/Native LLVM distribution is `…-essentials-` and ships `clang`, `lld`, `llvm-ar`, `llvm-cov`
and `llvm-profdata` — no `nm`, no `size`, no `objdump`, no `strip`. It works on a mac with Xcode
and fails on CI, which is the worst possible place to discover it. This will be proposed again,
because the wrapper genuinely is less code.

**`unattributed` is a row, not a bug.**
20.5% of the allocated bytes of a real release binary belong to no symbol, and most of that is
unwind tables and dynamic-linking metadata. Any future change that makes the number smaller by
folding it into another row is a regression, not an improvement.

**A tie-break for ambiguous packages is worse than an ambiguous row.**
[B-08](docs/backlog/B-08-klib-package-to-module.md) refuses to guess for the 1.6% of packages that
two modules both declare. The escape hatch is [B-19](docs/backlog/B-19-full-fqn-module-map.md), and
it is deferred rather than rejected — the trigger for building it is somebody's ambiguous row being
big enough to matter, not a decision made in advance.

**The gate's failure message is the feature.**
[B-14](docs/backlog/B-14-budget-gate.md) is blocked by
[B-13](docs/backlog/B-13-baseline-and-diff.md) for that reason and no other. A gate that reports
only a total gets disabled the first week a dependency bump trips it. Shipping the gate before the
row-level diff would ship the version that gets switched off.

**Two questions are held open on purpose.**
[B-09](docs/backlog/B-09-c-attribution-by-archive.md) and
[B-20](docs/backlog/B-20-decide-the-budget-unit.md) are `question` because both answers are data
that does not exist yet. Writing the code first would be choosing an expensive answer by accident.
[B-18](docs/backlog/B-18-reconcile-the-46-mib-claim.md) is `question` because the project's own
headline number has not been reproduced.
