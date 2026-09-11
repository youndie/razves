package io.github.youndie.razves.cli

/**
 * Leave the process with a status, from common code.
 *
 * `kotlin.system.exitProcess` exists on the JVM and on native with the same name and is in neither
 * common stdlib, so common code cannot call it and the metadata compilation is what says so - the
 * native link had already succeeded, because there the name resolves. Two actuals of one line each
 * is the whole of it.
 */
internal expect fun exitWith(code: Int): Nothing
