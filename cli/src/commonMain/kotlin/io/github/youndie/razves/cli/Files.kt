package io.github.youndie.razves.cli

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write

/**
 * The file system, kept out of `core` on purpose.
 *
 * `core` takes bytes and returns a report, which is what lets its tests build a whole ELF image in
 * memory and assert on it without touching a disk. Everything that has to know where a file is lives
 * here and in the Gradle plugin.
 */
internal object Files {
    fun read(path: String): ByteArray = SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

    /** pprof is a gzipped protobuf, so it goes to a file rather than through a terminal. */
    fun write(
        path: String,
        bytes: ByteArray,
    ): Unit = SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }

    fun exists(path: String): Boolean = SystemFileSystem.metadataOrNull(Path(path)) != null

    fun isDirectory(path: String): Boolean = SystemFileSystem.metadataOrNull(Path(path))?.isDirectory == true

    /**
     * Every file under [root] whose name ends with [suffix], and every directory that holds a klib
     * manifest.
     *
     * Both shapes, because a klib comes as either: a zip in the dependency cache, and an unpacked
     * directory in the Kotlin/Native distribution, which is where the stdlib and every `platform.*`
     * library live. A sweep that finds only the first leaves a quarter of a megabyte of
     * `kotlin.text.regex` with no declaring module.
     */
    fun walk(
        root: String,
        suffix: String,
    ): Sequence<String> =
        sequence {
            val stack = ArrayDeque(listOf(Path(root)))
            while (stack.isNotEmpty()) {
                val next = stack.removeLast()
                val metadata = SystemFileSystem.metadataOrNull(next) ?: continue
                if (metadata.isDirectory) {
                    if (SystemFileSystem.metadataOrNull(Path(next, "default/manifest")) != null) yield(next.toString())
                    stack.addAll(SystemFileSystem.list(next))
                } else if (next.name.endsWith(suffix)) {
                    yield(next.toString())
                }
            }
        }

    fun listUnder(directory: String): List<String> =
        sequence {
            val stack = ArrayDeque(listOf(Path(directory)))
            while (stack.isNotEmpty()) {
                val next = stack.removeLast()
                val metadata = SystemFileSystem.metadataOrNull(next) ?: continue
                yield(next.toString() + if (metadata.isDirectory) "/" else "")
                if (metadata.isDirectory) stack.addAll(SystemFileSystem.list(next))
            }
        }.toList()
}
