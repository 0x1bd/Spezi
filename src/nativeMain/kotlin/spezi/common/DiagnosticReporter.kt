package spezi.common

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import spezi.domain.Token

class DiagnosticReporter(private val term: Terminal) {
    var hasErrors = false
        private set

    fun error(msg: String, loc: Token, source: SourceFile) {
        hasErrors = true
        printDiagnostic("ERROR", red, msg, loc, source)
    }

    fun warn(msg: String, loc: Token, source: SourceFile) {
        printDiagnostic("WARN", yellow, msg, loc, source)
    }

    fun info(msg: String) {
        term.println("${blue("INFO:")} $msg")
    }

    private fun printDiagnostic(
        level: String,
        color: TextStyle,
        msg: String,
        loc: Token,
        source: SourceFile
    ) {
        val lineIdx = loc.line - 1
        val codeLine = source.lines.getOrNull(lineIdx)?.replace("\t", "    ") ?: ""

        term.println()
        term.println("${color(bold(level))}: $msg")
        term.println("${gray("-->")} ${source.path}:${loc.line}:${loc.col}")
        term.println(" ${gray("|")}")
        term.println("${gray("${loc.line} |")} $codeLine")

        val pad = " ".repeat(loc.col - 1)
        val pointer = "^".repeat(loc.length.coerceAtLeast(1))
        term.println(" ${gray("|")} $pad${color(pointer)}")
        term.println()
    }
}

class CompilerException(msg: String) : RuntimeException(msg)