package io.github.youndie.razves.mcp

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server as a client meets it: a process, JSON-RPC lines on stdin, answers on stdout.
 *
 * **In-process would prove less.** Two of the three defects this found are about the process rather
 * than the code - the SDK logs to stdout, which is the protocol channel, and creating a session
 * already starts the transport so starting it again aborts. Neither is visible to a test that calls
 * `server()` and inspects the object.
 */
class McpOverStdioTest {
    private val binary: File? = System.getProperty("RAZVES_MCP")?.let(::File)?.takeIf { it.isFile }

    private fun skipped(): Unit =
        println("SKIPPED McpOverStdioTest: no razves-mcp binary; linuxX64 is the only target that links one.")

    private fun ask(vararg lines: String): List<String> {
        val server = binary ?: error("no binary")
        // Stderr into a file rather than into the void. The first CI run of this on a mac failed with
        // "Stream closed" - the server had already exited when the test wrote to it - and the reason
        // was on a stream nobody was reading. A test that cannot say why it failed costs a whole run
        // to learn one line.
        val noise = File.createTempFile("razves-mcp", ".err")
        val process = ProcessBuilder(server.path).redirectError(noise).start()
        val complaint = { "razves-mcp said on stderr:\n" + noise.readText().take(2000) }
        try {
            process.outputStream.bufferedWriter().use { writer ->
                lines.forEach { writer.write(it + "\n") }
            }
        } catch (e: java.io.IOException) {
            throw AssertionError("could not write to razves-mcp (${e.message}). " + complaint())
        }
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "razves-mcp did not exit when its client did")
        if (out.isBlank()) throw AssertionError("razves-mcp answered nothing. " + complaint())
        noise.delete()
        return out.lines().filter { it.isNotBlank() }
    }

    private val handshake =
        arrayOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
        )

    @Test
    fun theChannelCarriesTheProtocolAndNothingElse() {
        // The SDK logs a line per registered tool before any request arrives. On stdout, where the
        // protocol is, that is a parse error at the client naming neither side - so razves-mcp moves
        // the real stdout aside and points descriptor 1 at stderr.
        if (binary == null) return skipped()

        val lines = ask(*handshake, """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")

        assertTrue(lines.isNotEmpty(), "the server said nothing at all")
        lines.forEach { line ->
            assertTrue(line.startsWith("{"), "something that is not JSON-RPC reached the channel: $line")
        }
    }

    @Test
    fun theToolsAreTheCommands() {
        if (binary == null) return skipped()

        val listed = ask(*handshake, """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
        val text = listed.joinToString("\n")

        assertTrue("\"size_report\"" in text)
        assertTrue("\"profile\"" in text)
        assertTrue("\"size_diff\"" in text)
    }

    @Test
    fun nothingHereCanStartOrStopASampler() {
        // Deliberate, and the item says so: a tool that installs a signal handler in a process
        // neither the agent nor razves wrote is a different trust question, and this repository has
        // not answered it. What is here reads files that already exist.
        if (binary == null) return skipped()

        val text = ask(*handshake, """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""").joinToString("\n")

        for (forbidden in listOf("start_sampl", "stop_sampl", "attach", "inject")) {
            assertTrue(forbidden !in text, "a tool named like $forbidden is in the list: $text")
        }
    }

    @Test
    fun aToolAnswersWithWhatTheCommandWouldSay() {
        if (binary == null) return skipped()
        val subject = binary

        val call =
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"size_report",""" +
                """"arguments":{"binary":"${subject.path}"}}}"""
        val text = ask(*handshake, call).joinToString("\n")

        assertTrue("WHERE THE FILE WENT" in text, "the report is not in the answer: ${text.take(400)}")
        assertTrue("io.modelcontextprotocol" in text, "razves reading razves-mcp must see the SDK in it")
    }

    @Test
    fun aRefusalArrivesAsTextRatherThanAsATransportError() {
        // The same words a terminal would get. A tool that fails the transport instead leaves the
        // client with a disconnect and no reason.
        if (binary == null) return skipped()

        val call =
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"size_report",""" +
                """"arguments":{"binary":"/definitely/not/here.kexe"}}}"""
        val text = ask(*handshake, call).joinToString("\n")

        assertTrue("does not exist" in text, text.take(400))
        assertTrue("isError" in text, "a refusal has to be marked as one: ${text.take(400)}")
    }

    @Test
    fun aToolNameRazvesDoesNotHaveIsRefusedRatherThanIgnored() {
        if (binary == null) return skipped()

        val call =
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"launch_missiles",""" +
                """"arguments":{}}}"""
        val text = ask(*handshake, call).joinToString("\n")

        assertTrue("error" in text.lowercase(), text.take(300))
    }
}
