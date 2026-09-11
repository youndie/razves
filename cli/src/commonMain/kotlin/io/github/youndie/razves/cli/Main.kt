package io.github.youndie.razves.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.main
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
public fun main(args: Array<String>): Unit = Razves().subcommands(Report()).main(args)

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
        echo(Analyse.report(binary, klibs, format, rows))
    }
}
