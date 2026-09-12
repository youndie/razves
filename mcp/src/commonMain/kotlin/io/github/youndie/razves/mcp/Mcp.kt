package io.github.youndie.razves.mcp

import io.github.youndie.razves.cli.Analyse
import io.github.youndie.razves.cli.OutputFormat
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * razves as an MCP server, over stdio.
 *
 * **Stdio rather than HTTP with a token, which is a deviation from the item and has a reason.**
 * `tracy` serves MCP over streamable HTTP because tracy is a service somewhere else; razves is a
 * command that runs next to the binaries it reads, started by whatever wants to ask it something.
 * A local tool that opened a port would be a local tool with an attack surface, and the token would
 * be guarding a machine that already trusts whoever can run the binary.
 *
 * **Read-only, and deliberately not able to sample.** There is no tool here that starts or stops a
 * sampler in somebody else process: a signal handler installed by an agent, in a program neither of
 * them wrote, is a different trust question and this repository has not answered it. What it can do
 * is read files that already exist.
 *
 * The tools are the commands. Same aggregation, same refusals, same words - a second interface over
 * one implementation rather than a second implementation with its own opinions.
 */
public object Mcp {
    public const val NAME: String = "razves"

    /** Builds the server with its tools. Separated from serving so that a test can inspect it. */
    public fun server(): Server {
        val server =
            Server(
                serverInfo = Implementation(name = NAME, version = "0.1.0"),
                options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
            )

        server.addTool(
            name = "size_report",
            description =
                "Where the bytes of a Kotlin/Native binary went: by origin, by Kotlin package and, " +
                    "when klib directories are supplied, by module. Reads the file; changes nothing.",
            inputSchema =
                schema(
                    required = listOf("binary"),
                    properties =
                        buildJsonObject {
                            put("binary", describe("string", "Path to the unstripped link output."))
                            put("klibs", describe("string", "Colon-separated klib directories. Optional."))
                        },
                ),
        ) { request ->
            text {
                Analyse.report(
                    binaryPath = request.stringArgument("binary"),
                    klibRoots = request.paths("klibs"),
                    format = OutputFormat.TEXT,
                    rows = DEFAULT_ROWS,
                )
            }
        }

        server.addTool(
            name = "profile",
            description =
                "Where the time went, from a sample dump a profiled process wrote and the binary it " +
                    "was taken in. Reads both files; does not sample anything itself.",
            inputSchema =
                schema(
                    required = listOf("dump", "binary"),
                    properties =
                        buildJsonObject {
                            put("dump", describe("string", "Path to the dump the sampled process wrote."))
                            put("binary", describe("string", "The binary those addresses are in."))
                            put("klibs", describe("string", "Colon-separated klib directories. Optional."))
                        },
                ),
        ) { request ->
            text {
                Analyse.profile(
                    dumpPath = request.stringArgument("dump"),
                    binaryPath = request.stringArgument("binary"),
                    klibRoots = request.paths("klibs"),
                    rows = DEFAULT_ROWS,
                )
            }
        }

        server.addTool(
            name = "size_diff",
            description =
                "What moved between two size reports in the JSON format razves writes: row by row, " +
                    "largest movement first.",
            inputSchema =
                schema(
                    required = listOf("before", "after"),
                    properties =
                        buildJsonObject {
                            put("before", describe("string", "The report to compare against."))
                            put("after", describe("string", "The report of the build in hand."))
                        },
                ),
        ) { request ->
            text { Analyse.diff(request.stringArgument("before"), request.stringArgument("after"), DEFAULT_ROWS) }
        }

        return server
    }

    /**
     * Serves on stdin and stdout until the client goes away.
     *
     * Nothing else may be written to stdout while this runs: the transport is a JSON-RPC stream, and
     * one stray line of logging makes the client disconnect with a parse error naming neither side.
     */
    public suspend fun serve() {
        // /dev/stdin rather than a platform handle: kotlinx-io has no stdio of its own, and that path
        // is stdin on every system this is built for. The writing side goes through [protocolSink],
        // which takes the channel out of reach of anything that prints.
        val transport =
            StdioServerTransport(
                SystemFileSystem.source(Path("/dev/stdin")).buffered(),
                protocolSink(),
            ) { }
        // A Server holds the tools; a session is one client talking over one transport, and creating
        // it starts the transport - calling `start` as well is how the first run of this aborted with
        // "StdioServerTransport already started".
        val session = server().createSession(transport)
        val until = CompletableDeferred<Unit>()
        session.onClose { until.complete(Unit) }
        until.await()
    }

    private const val DEFAULT_ROWS = 20

    private fun schema(
        required: List<String>,
        properties: JsonObject,
    ) = ToolSchema(properties = properties, required = required)

    private fun describe(
        type: String,
        description: String,
    ) = buildJsonObject {
        put("type", type)
        put("description", description)
    }
}

/** What the tool was handed, refused by name when it is missing rather than defaulted to nothing. */
private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.stringArgument(name: String): String =
    arguments?.get(name)?.jsonPrimitive?.content
        ?: error("the $name argument is required and was not given")

/** Repeatable paths arrive as one colon-separated string, the way a PATH does. */
private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.paths(name: String): List<String> =
    arguments
        ?.get(name)
        ?.jsonPrimitive
        ?.content
        ?.split(':')
        ?.filter { it.isNotBlank() }
        .orEmpty()

/** A refusal reaches the client as text rather than as a transport error, the way it reaches a terminal. */
private inline fun text(block: () -> String): CallToolResult =
    try {
        CallToolResult(content = listOf(TextContent(block())))
    } catch (e: IllegalArgumentException) {
        CallToolResult(content = listOf(TextContent(e.message ?: "razves refused and did not say why")), isError = true)
    } catch (e: IllegalStateException) {
        CallToolResult(content = listOf(TextContent(e.message ?: "razves refused and did not say why")), isError = true)
    }

/**
 * A binary of its own, and the reason is measured.
 *
 * Adding the MCP SDK to the `razves` command took it from 3,315,112 bytes to 6,948,816 - and razves,
 * asked about itself, said where: `io.modelcontextprotocol.kotlin` is 1,235,919 bytes across 5,858
 * symbols, the largest package in the binary, with coroutines and Ktor behind it. A size tool cannot
 * ship that in the command people run to measure their own binaries.
 *
 * So the second interface is a second executable. Whoever wants MCP runs this one; everybody else
 * carries none of it.
 */
public fun main(): Unit = kotlinx.coroutines.runBlocking { Mcp.serve() }
