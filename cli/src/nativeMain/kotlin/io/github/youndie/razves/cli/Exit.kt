package io.github.youndie.razves.cli

import kotlin.system.exitProcess

internal actual fun exitWith(code: Int): Nothing = exitProcess(code)
