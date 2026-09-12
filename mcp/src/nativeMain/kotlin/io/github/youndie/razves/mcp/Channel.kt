package io.github.youndie.razves.mcp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.dup
import platform.posix.dup2

@OptIn(ExperimentalForeignApi::class)
internal actual fun protocolSink(): Sink {
    // dup first, then overwrite: the duplicate keeps the terminal or pipe the parent gave us, and
    // descriptor 1 becomes a second handle on stderr. Two syscalls, and they have to be in this
    // order - overwriting first would leave nothing to duplicate.
    val channel = dup(STDOUT)
    check(channel > 0) { "razves-mcp could not duplicate stdout, so it cannot keep the channel clean" }
    dup2(STDERR, STDOUT)
    return SystemFileSystem.sink(Path("/dev/fd/$channel")).buffered()
}

private const val STDOUT = 1
private const val STDERR = 2
