package spezi.common.diagnostic

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import spezi.common.CompilationState
import spezi.common.Context
import spezi.domain.Token

class DiagnosticReporter(private val ctx: Context) {

    private val _diagnostics = mutableListOf<Diagnostic>()
    val diagnostics: List<Diagnostic> get() = _diagnostics

    val hasErrors: Boolean
        get() = _diagnostics.any { it.level == Level.ERROR }

    fun report(level: Level, msg: String, loc: Token?) {
        _diagnostics.add(Diagnostic(level, msg, loc))
        if (ctx.options.verbose) {
            println("[${ctx.state}] $level: $msg")
        }
    }
}

enum class Level {
    INFO,
    WARN,
    ERROR
}