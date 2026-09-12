package io.github.youndie.razves.mcp

import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered

/**
 * The JVM half exists for the tests and serves nobody: the binary people run is the native one.
 *
 * There is no `dup` here and none is needed - a JVM process can move `System.out` itself, and this
 * target is not where the protocol is spoken.
 */
internal actual fun protocolSink(): Sink = System.out.asSink().buffered()
