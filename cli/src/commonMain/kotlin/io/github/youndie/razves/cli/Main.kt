package io.github.youndie.razves.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int

/**
 * `clikt-core` rather than `clikt`, and that is not a preference.
 *
 * The umbrella `clikt` artifact pulls in `clikt-mordant`, and the two both define
 * `kfun:com.github.ajalt.clikt.core#selfAndAncestors…` - which `ld.lld` refuses outright on a native
 * link with "duplicate symbol". razves reports that same pair as an ambiguous package, from the klib
 * manifests, before a linker ever sees it: the 1.6% of packages two modules both declare is not
 * always a cosmetic ambiguity.
 *
 * The cost is mordant's help formatting, which a size tool does not need.
 */
public fun main(args: Array<String>) {
    val razves = Razves().subcommands(Report(), Diff())
    try {
        razves.parse(args)
    } catch (e: CliktError) {
        // `parse` and an explicit exit rather than clikt's `main`, which prints the same message and
        // then returns normally: measured on the built binary, every refusal this tool has - a
        // missing file, a file that is not a report, two reports measured differently, and clikt's
        // own "missing argument" - left the process with status 0. A tool that cannot say it refused
        // is a tool a script reads as having agreed.
        razves.echoFormattedHelp(e)
        exitWith(e.statusCode)
    }
}

private class Razves : CoreCliktCommand(name = "razves") {
    override fun help(context: Context): String = "Where did the bytes in your Kotlin/Native binary go?"

    override fun run(): Unit = Unit
}

private class Report : CoreCliktCommand(name = "report") {
    override fun help(context: Context): String = "Attribute a Kotlin/Native binary by origin, package and module."

    private val binary by argument(name = "binary", help = "The unstripped link output to read.")

    // Repeatable rather than a separated list, because a real link pulls klibs from the dependency
    // cache AND from the Kotlin/Native distribution, and a path separator inside one option is a
    // shape people get wrong on Windows.
    private val klibs by option("--klibs")
        .multiple()
        .help(
            "A directory of klibs, repeatable. Without them the report stops at package level. " +
                "Supply the link classpath: a sweep of a build tree picks up transformed copies of a " +
                "dependency and makes every package it declares look declared twice.",
        )

    private val format by option("--format")
        .enum<OutputFormat> { it.name.lowercase() }
        .default(OutputFormat.TEXT)
        .help("text for a person, json for the document the Gradle plugin commits as a baseline.")

    private val rows by option("--rows").int().default(20).help("How many rows of each table to print.")

    override fun run() {
        echo(refused { Analyse.report(binary, klibs, format, rows) })
    }
}

private class Diff : CoreCliktCommand(name = "diff") {
    override fun help(context: Context): String = "What moved between two reports, row by row."

    private val before by argument(name = "before", help = "The report to compare against - a committed baseline.")

    private val after by argument(name = "after", help = "The report of the build in hand.")

    private val rows by option("--rows").int().default(20).help("How many rows of each table to print.")

    override fun run() {
        echo(refused { Analyse.diff(before, after, rows) })
    }
}

/**
 * A refusal reaches the reader as a sentence, not as a stack trace.
 *
 * Everything razves declines to do, it declines through `require` and `check`, because those messages
 * are written for the person holding the file - "these two reports were measured differently" says
 * what to do next. Uncaught, a Kotlin/Native binary prints that message under a stack trace and an
 * exception class, which reads like a crash in the tool rather than an answer about the input.
 * [CliktError] is what clikt already prints and exits non-zero on.
 */
private inline fun refused(block: () -> String): String =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        throw CliktError(e.message ?: "razves refused the input and did not say why, which is a bug")
    } catch (e: IllegalStateException) {
        throw CliktError(e.message ?: "razves refused the input and did not say why, which is a bug")
    }
