package spezi.common.diagnostic

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import spezi.common.CompilationState
import spezi.common.Context
import spezi.domain.Token

class DiagnosticReporter(private val term: Terminal, private val ctx: Context) {
    var hasErrors = false
        private set

    var state: CompilationState = CompilationState.Reading
        set(value) {
            field = value
            if (ctx.options.verbose)
                info("Transitioning state to $value")
        }

    fun error(msg: String, loc: Token) {
        hasErrors = true
        printDiagnostic("ERROR", red, msg, loc)
    }

    fun error(msg: String) {
        hasErrors = true
        printDiagnostic("ERROR", red, msg)
    }

    fun warn(msg: String, loc: Token) {
        printDiagnostic("WARN", yellow, msg, loc)
    }

    fun info(msg: String) {
        term.println("${blue("INFO:")} $msg")
    }

    fun warn(msg: String) {
        term.println("${yellow("WARN:")} $msg")
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

        val lineNoStr = loc.line.toString()
        val gutterWidth = lineNoStr.length

        var padCount = 0
        for (i in 0 until minOf(loc.col - 1, rawLine.length)) {
            padCount += if (rawLine[i] == '\t') 4 else 1
        }

        val codeLine = rawLine.replace("\t", "    ")
        val pointerLen = loc.length.coerceAtLeast(1)

        val pad = " ".repeat(padCount)
        val caret = "^".repeat(pointerLen)

        val paddedLineNo = lineNoStr.padStart(gutterWidth)
        val emptyGutter = " ".repeat(gutterWidth)

        term.println()
        term.println("${color(bold(level))}: $msg")
        term.println("  ${gray("-->")} ${source.path}:${loc.line}:${loc.col}")
        term.println("  $emptyGutter${gray("|")}")

        term.println(" ${gray("$paddedLineNo |")} $codeLine")

        term.println(
            " ${gray("$emptyGutter |")} $pad${color(caret)} ${color(msg)}"
        )

        term.println()
    }

    private fun printDiagnostic(
        level: String,
        color: TextStyle,
        msg: String
    ) {
        term.println()
        term.println("${color(bold(level))}: $msg")
        term.println()
    }
}

enum class Level {
    INFO,
    WARN,
    ERROR
}

fun Context.report(level: Level, msg: String) {
    when (level) {
        Level.INFO -> reporter.info(msg)
        Level.WARN -> reporter.warn(msg)
        Level.ERROR -> reporter.error(msg)
    }
}

fun Context.report(level: Level, msg: String, loc: Token) {
    when (level) {
        Level.INFO -> reporter.info(msg)
        Level.WARN -> reporter.warn(msg, loc)
        Level.ERROR -> reporter.error(msg, loc)
    }
}
