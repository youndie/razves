package io.github.youndie.razves.mcp

import kotlinx.io.Sink

/**
 * The JSON-RPC channel, taken out of reach of anything that prints.
 *
 * **stdout is the protocol here**, and the first live run proved how fragile that is: the MCP SDK
 * itself logs `kotlin-logging: initializing...` and a line per registered tool - to stdout, before a
 * single request arrives. A client reading that gets a parse error naming neither side.
 *
 * So the real stdout is duplicated to a descriptor of its own and the original is pointed at stderr.
 * Whatever prints - this code, the SDK, a library three levels down - lands where a person can read
 * it, and the channel carries only the protocol.
 */
internal expect fun protocolSink(): Sink
