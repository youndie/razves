# razves

**Where did the bytes in your Kotlin/Native binary go?**

`bloaty` will tell you that `kfun:io.ktor.server.engine#embeddedServer(...)` is 4,112 bytes. True,
and useless. razves aggregates the same bytes into the units a Kotlin developer can act on — the
package, and the klib artifact the package came from — and then lets a build fail when the total
grows.

```
razves report build/bin/linuxX64/releaseExecutable/app.kexe --klibs ~/.gradle/caches/…
```

```kotlin
binarySize {
    budget = 50.MiB
    deltaPerChange = 3.percent
}
```

> **Status: nothing is implemented yet.** What exists is the research and the plan, and the
> research was measured rather than assumed. Start at
> [`docs/research/research-architecture.md`](docs/research/research-architecture.md).

## What the research already found

Measured by razves on six real Kotlin/Native binaries on 2026-09-11, all Kotlin 2.4.10 — details and
verification addresses in the research document:

* **The Kotlin/Native runtime is a fixed cost of about 40 KB.** 37,889 to 40,871 bytes across all
  six, a spread of under 3 KB over a **43× range of file size**. Its share falls from 17.6% to 0.2%,
  so "the runtime is 17.6% of this binary" means the binary does almost nothing — not that the
  runtime is heavy.
* **The floor is 496,232 bytes**: one `println`, one dependency, of which 135,032 is the symbol
  table.

* **Kotlin is a minority of a large release binary.** 36–40% of the attributed bytes in `shildik`,
  against 77% in a binary that is almost all Kotlin.
  The majority is statically linked C — OpenSSL arriving through `ktor-client-curl` — plus, where a
  Rust-backed driver is used, about 1.3 MB of `tokio` and `sqlx`.
* **The symbol table is 19–27% of the file**, in every one of the six. It is the largest single removable
  thing in a Kotlin/Native binary, and it is also exactly what razves reads.
* **The Kotlin/Native toolchain does not ship a binary reader.** The LLVM distribution it downloads
  is `…-essentials-` and contains no `llvm-nm`, `llvm-size`, `llvm-objdump` or `llvm-strip`. So
  razves reads ELF and Mach-O itself, with no subprocess.
* **`_ZN…` is mostly Rust, not C++.** Rust's legacy mangling shares Itanium's prefix. Grouping it as
  "the Kotlin/Native runtime" misattributes about 964 KB in the subject binary; the genuine C++
  runtime there is 14 KB.
* **`nm` reports zero sizes for every Mach-O symbol.** Apple targets need an address-delta
  algorithm, not a flag.
* **9 of 568 packages are declared by two klibs.** razves reports those as ambiguous instead of
  picking one.

## Documentation

[`docs/`](docs/) — research, features with acceptance scenarios, and one document per module.
The plan is in [`backlog.md`](backlog.md).

```bash
make check     # the documentation gate; CI runs exactly this
```

## Licence

MIT — see [LICENSE](LICENSE).
