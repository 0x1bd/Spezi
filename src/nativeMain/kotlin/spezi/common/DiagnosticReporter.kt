package spezi.common

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import spezi.domain.Token

class DiagnosticReporter(private val term: Terminal) {
    var hasErrors = false
        private set

    fun error(msg: String, loc: Token) {
        hasErrors = true
        printDiagnostic("ERROR", red, msg, loc)
    }

    fun warn(msg: String, loc: Token) {
        printDiagnostic("WARN", yellow, msg, loc)
    }

    fun info(msg: String) {
        term.println("${blue("INFO:")} $msg")
    }

    private fun printDiagnostic(
        level: String,
        color: TextStyle,
        msg: String,
        loc: Token
    ) {
        val source = loc.source
        val lineIdx = loc.line - 1
        val rawLine = source.lines.getOrNull(lineIdx) ?: ""

        var padCount = 0
        val chars = rawLine.toCharArray()
        for (i in 0 until minOf(loc.col - 1, chars.size)) {
            padCount += if (chars[i] == '\t') 4 else 1
        }

        val codeLine = rawLine.replace("\t", "    ")

        term.println()
        term.println("${color(bold(level))}: $msg")
        term.println("${gray("-->")} ${source.path}:${loc.line}:${loc.col}")
        term.println(" ${gray("|")}")
        term.println("${gray("${loc.line} |")} $codeLine")

        val pad = " ".repeat(padCount)
        val pointer = "^".repeat(loc.length.coerceAtLeast(1))

        term.println(" ${gray("|")} $pad${color(pointer)}")
        term.println()
    }
}

class CompilerException(msg: String) : RuntimeException(msg)